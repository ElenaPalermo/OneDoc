import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Генератор иконки приложения из рисунка-плитки art/onedoci.png (512×512: свой бирюзовый фон,
 * скругления и свечение по краю, надпись «OneDoc» внизу).
 *
 * Что делает: вырезает внутреннюю часть плитки без свечения, заливает её углы за дугой скругления
 * цветом фона и кладёт плитку TILE_DP по центру холста 108 dp; снаружи плитки до краёв холста — повтор
 * её крайних пикселей. Скругляет углы маска лаунчера. Пишет:
 * - mipmap-*dpi/ic_launcher_foreground.png и ic_launcher_monochrome.png (силуэт листков) в каталог res;
 * - иконку для Google Play 512×512 без прозрачности;
 * - необязательно — превью в круглой и скруглённой масках и тематической иконки.
 * Фон адаптивной иконки (TILE_BACKGROUND) задан отдельно в values/ic_launcher_background.xml.
 *
 * Запуск из корня репозитория (Java из Android Studio, без сборки):
 *   "C:/Program Files/Android/Android Studio/jbr/bin/java.exe" art/TileIconGen.java  *       art/onedoci.png app/src/main/res art/play_icon_512.png [каталог превью]
 * Размер плитки можно попробовать без правки кода: -DtileDp=58 перед именем файла.
 *
 * Константы CROP_*, CORNER_* и TILE_BACKGROUND подобраны по замерам именно этого рисунка.
 * Для другого рисунка их нужно измерить заново, иначе останутся свечение или срезанные края.
 */
public class TileIconGen {
    /** Внутренняя часть плитки в исходнике 512×512: без свечения по краю. */
    static final int CROP_LEFT = 58, CROP_TOP = 50, CROP_RIGHT = 456, CROP_BOTTOM = 462;
    /**
     * Сторона плитки на холсте 108 dp. Видимая область маски — 72 dp, но надпись «OneDoc» почти во всю
     * ширину стоит у нижнего края плитки: при 56 dp она целиком помещается даже в круглую маску
     * (при 62 dp круг срезал «O» и «c»). Зазор до краёв маски — продолжение краёв плитки (см. layer).
     */
    static final double TILE_DP = Double.parseDouble(System.getProperty("tileDp", "56"));
    /** Доля стороны иконки Play, которую занимает плитка (остальное — продолжение её краёв). */
    static final double PLAY_TILE_SHARE = 0.90;
    /** Цвет фона плитки у боковых краёв: им залит фон адаптивной иконки. */
    static final Color TILE_BACKGROUND = new Color(0x03CCD3);

    public static void main(String[] args) throws Exception {
        BufferedImage src = toArgb(ImageIO.read(new File(args[0])));
        BufferedImage tile = cleanCorners(onBackground(src.getSubimage(CROP_LEFT, CROP_TOP, CROP_RIGHT - CROP_LEFT, CROP_BOTTOM - CROP_TOP)));
        BufferedImage mono = monochrome(tile);
        // Консоль Windows по умолчанию не в UTF-8 — без этого русский текст выводится вопросами.
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        out.println("плитка " + tile.getWidth() + "x" + tile.getHeight());

        String[] dirs = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        double[] scale = {1, 1.5, 2, 3, 4};
        for (int i = 0; i < dirs.length; i++) {
            int size = (int) Math.round(108 * scale[i]);
            File dir = new File(args[1], "mipmap-" + dirs[i]);
            dir.mkdirs();
            ImageIO.write(layer(tile, size, true), "png", new File(dir, "ic_launcher_foreground.png"));
            ImageIO.write(layer(mono, size, false), "png", new File(dir, "ic_launcher_monochrome.png"));
        }

        // Иконка для Play: квадрат без прозрачности, углы Play скругляет сам.
        BufferedImage play = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = quality(play.createGraphics());
        g.drawImage(extended(tile, 512, (int) Math.round(512 * PLAY_TILE_SHARE)), 0, 0, null);
        g.dispose();
        ImageIO.write(play, "png", new File(args[2]));

        if (args.length < 4) return;
        new File(args[3]).mkdirs();
        int p = 432;
        ImageIO.write(masked(layer(tile, p, true), TILE_BACKGROUND, p, true, null), "png", new File(args[3], "circle.png"));
        ImageIO.write(masked(layer(tile, p, true), TILE_BACKGROUND, p, false, null), "png", new File(args[3], "squircle.png"));
        ImageIO.write(masked(layer(mono, p, false), new Color(0xD7E3FF), p, true, new Color(0x2B4678)), "png", new File(args[3], "themed.png"));
    }

    static BufferedImage toArgb(BufferedImage in) {
        BufferedImage out = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(in, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Прозрачные места (за скруглёнными углами плитки) заливаются цветом фона. */
    static BufferedImage onBackground(BufferedImage img) {
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setColor(TILE_BACKGROUND);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    /**
     * Центры дуг скругления тела плитки в координатах вырезанного прямоугольника (по замерам исходника:
     * края тела — x 52 и 462, y 40 и 468, радиус скругления около 55 px), отдельно по осям.
     */
    static final int CORNER_LEFT_X = 49, CORNER_RIGHT_INSET = 48, CORNER_TOP_Y = 45, CORNER_BOTTOM_Y = 363;
    /** Дальше этого радиуса от центра дуги — скругление и свечение исходной плитки. */
    static final double CORNER_KEEP_RADIUS = 47;

    /**
     * Убирает скругления и свечение в углах вырезанной плитки: пиксели за дугой заливаются цветом фона,
     * взятым с прямых участков краёв рядом с углом. Пиксели с луча не копируются — надпись у нижнего края
     * иначе растекалась бы в углы.
     */
    static BufferedImage cleanCorners(BufferedImage tile) {
        int w = tile.getWidth(), h = tile.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(tile, 0, 0, null);
        g.dispose();
        int right = w - 1 - CORNER_RIGHT_INSET;
        // Центр дуги, направление к углу и точки на прямых участках краёв для цвета заливки.
        int[][] corners = {
                {CORNER_LEFT_X, CORNER_TOP_Y, -1, -1, CORNER_LEFT_X + 12, 3, 3, CORNER_TOP_Y + 12},
                {right, CORNER_TOP_Y, 1, -1, right - 12, 3, w - 4, CORNER_TOP_Y + 12},
                {CORNER_LEFT_X, CORNER_BOTTOM_Y, -1, 1, CORNER_LEFT_X + 12, h - 4, 3, CORNER_BOTTOM_Y - 12},
                {right, CORNER_BOTTOM_Y, 1, 1, right - 12, h - 4, w - 4, CORNER_BOTTOM_Y - 12},
        };
        for (int[] c : corners) {
            int fill = average(tile.getRGB(c[4], c[5]), tile.getRGB(c[6], c[7]));
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((x - c[0]) * c[2] <= 0 || (y - c[1]) * c[3] <= 0) continue;
                    double dx = x - c[0], dy = y - c[1];
                    if (Math.sqrt(dx * dx + dy * dy) > CORNER_KEEP_RADIUS) out.setRGB(x, y, fill);
                }
            }
        }
        return out;
    }

    static int average(int a, int b) {
        int r = (((a >> 16) & 0xFF) + ((b >> 16) & 0xFF)) / 2;
        int g = (((a >> 8) & 0xFF) + ((b >> 8) & 0xFF)) / 2;
        int bl = ((a & 0xFF) + (b & 0xFF)) / 2;
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * Силуэт листков: пиксели, заметно отличающиеся от бирюзового фона и не светлые.
     * Белые буквы и светлые значки фона становятся «дырками», поэтому P, W, T, X читаются.
     */
    static BufferedImage monochrome(BufferedImage tile) {
        int br = TILE_BACKGROUND.getRed(), bg = TILE_BACKGROUND.getGreen(), bb = TILE_BACKGROUND.getBlue();
        BufferedImage out = new BufferedImage(tile.getWidth(), tile.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < tile.getHeight(); y++) {
            for (int x = 0; x < tile.getWidth(); x++) {
                int c = tile.getRGB(x, y);
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                double distance = Math.sqrt((r - br) * (r - br) + (g - bg) * (g - bg) + (b - bb) * (b - bb));
                double lum = 0.299 * r + 0.587 * g + 0.114 * b;
                double far = clamp((distance - 110) / 60);
                double dark = clamp((200 - lum) / 40);
                int alpha = (int) Math.round(255 * far * dark);
                out.setRGB(x, y, (alpha << 24) | 0xFFFFFF);
            }
        }
        return out;
    }

    static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /**
     * Холст size×size (108 dp), плитка TILE_DP по центру. Для цветного слоя (extend) пиксели
     * вокруг плитки повторяют её ближайший край — фон плитки продолжается до краёв маски без шва.
     */
    static BufferedImage layer(BufferedImage tile, int size, boolean extend) {
        int side = (int) Math.round(size * TILE_DP / 108);
        if (extend) return extended(tile, size, side);
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(out.createGraphics());
        int offset = (size - side) / 2;
        g.drawImage(scale(tile, side, side), offset, offset, null);
        g.dispose();
        return out;
    }

    /** Плитка стороной side по центру холста size; снаружи — повтор ближайшего пикселя края плитки. */
    static BufferedImage extended(BufferedImage tile, int size, int side) {
        BufferedImage scaled = scale(tile, side, side);
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int offset = (size - side) / 2;
        for (int y = 0; y < size; y++) {
            int sy = Math.max(0, Math.min(side - 1, y - offset));
            for (int x = 0; x < size; x++) {
                int sx = Math.max(0, Math.min(side - 1, x - offset));
                out.setRGB(x, y, scaled.getRGB(sx, sy));
            }
        }
        return out;
    }

    /** Масштабирование ступенями по половине — тонкие линии не теряются. */
    static BufferedImage scale(BufferedImage img, int w, int h) {
        BufferedImage cur = img;
        int cw = img.getWidth(), ch = img.getHeight();
        do {
            cw = cw / 2 >= w ? cw / 2 : w;
            ch = ch / 2 >= h ? ch / 2 : h;
            BufferedImage next = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = quality(next.createGraphics());
            g.drawImage(cur, 0, 0, cw, ch, null);
            g.dispose();
            cur = next;
        } while (cw != w || ch != h);
        return cur;
    }

    /** Превью: фон + слой под маской (видимая часть — 72 из 108 dp). */
    static BufferedImage masked(BufferedImage fg, Color bg, int size, boolean circle, Color tint) {
        BufferedImage full = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(full.createGraphics());
        g.setColor(bg);
        g.fillRect(0, 0, size, size);
        if (tint != null) {
            BufferedImage tinted = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D t = tinted.createGraphics();
            t.drawImage(fg, 0, 0, null);
            t.setComposite(AlphaComposite.SrcIn);
            t.setColor(tint);
            t.fillRect(0, 0, size, size);
            t.dispose();
            g.drawImage(tinted, 0, 0, null);
        } else {
            g.drawImage(fg, 0, 0, null);
        }
        g.dispose();
        int visible = size * 72 / 108, offset = (size - visible) / 2;
        BufferedImage out = new BufferedImage(visible, visible, BufferedImage.TYPE_INT_ARGB);
        Graphics2D o = quality(out.createGraphics());
        o.setClip(circle ? new Ellipse2D.Double(0, 0, visible, visible)
                : new RoundRectangle2D.Double(0, 0, visible, visible, visible * 0.6, visible * 0.6));
        o.drawImage(full, -offset, -offset, null);
        o.dispose();
        return out;
    }

    static Graphics2D quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return g;
    }
}

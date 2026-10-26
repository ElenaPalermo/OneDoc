import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Правка баннера OneDoc под требования Google Play (товарные знаки, соответствие интерфейсу):
 * 1) значки «Word» и «Excel» внизу слева → нейтральные «DOC» (документ с полосками) и «SHEET» (документ с сеткой);
 * 2) на летающих карточках квадраты с «W» и «X» → квадраты с полосками текста и с сеткой, зелёная сетка → синяя;
 * 3) экран телефона → реальный скриншот приложения с перспективой, скруглёнными углами и «островом» камеры поверх;
 * 4) синяя иконка слева от названия → иконка приложения (art/play_icon_512.png) в том же скруглённом квадрате.
 *
 * Координаты измерены на art/banner_source.png (1796×876); для другого исходника их нужно измерить заново.
 *
 * Запуск из корня репозитория (Java из Android Studio, без сборки):
 *   "C:/Program Files/Android/Android Studio/jbr/bin/java.exe" art/EditBanner.java  *       art/banner_source.png play/screenshots/en/01_library.png art/play_icon_512.png \
 *       art/banner_play.png play/feature-graphic.png
 * Аргументы: <исходник> <скриншот 1080×2160> <иконка приложения> <результат в полном размере> [обложка 1024×500]
 */
public class EditBanner {
    static BufferedImage img;
    static final PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        img = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g0 = img.createGraphics();
        g0.drawImage(src, 0, 0, null);
        g0.dispose();
        BufferedImage original = copy(img);

        bottomIcons(original);
        wordCard();
        excelCard();
        phoneScreen(ImageIO.read(new File(a[1])), original);
        appIcon(ImageIO.read(new File(a[2])));

        ImageIO.write(img, "png", new File(a[3]));
        out.println("готово: " + a[3]);
        if (a.length > 4) {
            // Обложка для Play — 1024×500: уменьшение по высоте, лишний пиксель ширины срезается поровну.
            int w = 1024, h = 500;
            int sw = (int) Math.round(img.getWidth() * (double) h / img.getHeight());
            BufferedImage scaled = scaleDown(img, sw, h);
            BufferedImage fg = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = fg.createGraphics();
            g.drawImage(scaled, (w - sw) / 2, 0, null);
            g.dispose();
            ImageIO.write(fg, "png", new File(a[4]));
            out.println("обложка: " + a[4]);
        }
    }

    // ------------------------------------------------------------ общие помощники

    static BufferedImage copy(BufferedImage s) {
        BufferedImage c = new BufferedImage(s.getWidth(), s.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = c.createGraphics();
        g.drawImage(s, 0, 0, null);
        g.dispose();
        return c;
    }

    static int[] rgb(int c) { return new int[]{(c >> 16) & 255, (c >> 8) & 255, c & 255}; }

    static int pack(double r, double g, double b) {
        return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    static int clamp255(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }

    static double lum(int c) { int[] p = rgb(c); return 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]; }

    static int mix(int c1, int c2, double t) {
        int[] p = rgb(c1), q = rgb(c2);
        return pack(p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t, p[2] + (q[2] - p[2]) * t);
    }

    static int avg(BufferedImage im, int x0, int y0, int x1, int y1) {
        double r = 0, g = 0, b = 0; int n = 0;
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            int[] p = rgb(im.getRGB(x, y)); r += p[0]; g += p[1]; b += p[2]; n++;
        }
        return pack(r / n, g / n, b / n);
    }

    static Graphics2D quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        return g;
    }

    /** Стирает прямоугольник: каждая строка — линейный переход между цветами фона слева и справа. */
    static void eraseRows(int x0, int y0, int x1, int y1) {
        for (int y = y0; y <= y1; y++) {
            int l = avg(img, x0 - 4, y, x0 - 1, y), r = avg(img, x1 + 1, y, x1 + 4, y);
            for (int x = x0; x <= x1; x++) img.setRGB(x, y, mix(l, r, (x - x0 + 1.0) / (x1 - x0 + 2.0)));
        }
    }

    // ------------------------------------------------------------ 1. значки внизу слева

    static final int TEXT_X0 = 550, TEXT_Y0 = 590, TEXT_X1 = 655, TEXT_Y1 = 712; // значок «Text» с полями
    static final Color DOC_COLOR = new Color(0x2F6FE0), SHEET_COLOR = new Color(0x0E9FB3);

    static void bottomIcons(BufferedImage original) throws Exception {
        // Подпись «Text»: размер шрифта и базовая линия подбираются по ней.
        Rectangle2D textBox = darkBox(original, 560, 715, 645, 768);
        int labelColor = darkest(original, 560, 715, 645, 768);
        out.printf("подпись Text: %s, цвет %06X%n", textBox, labelColor);

        eraseRows(232, 588, 360, 770);  // Word + подпись
        eraseRows(392, 588, 515, 770);  // Excel + подпись

        // Новые значки — перекрашенные копии значка «Text» (документ с полосками текста).
        int dxDoc = 292 - 602, dxSheet = 452 - 602;
        recolorCopy(original, dxDoc, DOC_COLOR, false);
        recolorCopy(original, dxSheet, SHEET_COLOR, true);

        // Подписи тем же шрифтом и цветом, что «Text», с центром по значку.
        Font font = fitFont("Text", textBox.getWidth());
        Graphics2D g = quality(img.createGraphics());
        g.setColor(new Color(labelColor));
        drawCentered(g, font, "DOC", 292, textBox.getMaxY());
        drawCentered(g, font, "SHEET", 452, textBox.getMaxY());
        g.dispose();
    }

    /** Габариты тёмных пикселей (буквы подписи) в прямоугольнике. */
    static Rectangle2D darkBox(BufferedImage im, int x0, int y0, int x1, int y1) {
        int minX = x1, minY = y1, maxX = x0, maxY = y0;
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            if (lum(im.getRGB(x, y)) < 140) {
                minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        return new Rectangle2D.Double(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /** Цвет букв: среднее по «сплошным» пикселям штрихов, без сглаженных краёв. */
    static int darkest(BufferedImage im, int x0, int y0, int x1, int y1) {
        double r = 0, g = 0, b = 0; int n = 0;
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            int c = im.getRGB(x, y);
            if (lum(c) < 70) { int[] p = rgb(c); r += p[0]; g += p[1]; b += p[2]; n++; }
        }
        return pack(r / n, g / n, b / n);
    }

    /** Шрифт, в котором слово занимает по ширине столько же, сколько в баннере (по видимым контурам). */
    static Font fitFont(String word, double width) throws Exception {
        Font base = Font.createFont(Font.TRUETYPE_FONT, new File("C:/Windows/Fonts/segoeui.ttf"));
        FontRenderContext frc = new FontRenderContext(null, true, true);
        double size = 30;
        for (int i = 0; i < 30; i++) {
            double w = base.deriveFont((float) size).createGlyphVector(frc, word).getVisualBounds().getWidth();
            size *= width / w;
        }
        out.printf("шрифт: Segoe UI %.2f px%n", size);
        return base.deriveFont((float) size);
    }

    static void drawCentered(Graphics2D g, Font font, String text, double cx, double baselineBottom) {
        GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), text);
        Rectangle2D vb = gv.getVisualBounds();
        // Низ видимых контуров (у этих слов без выносных элементов — базовая линия) — на уровне «Text».
        float x = (float) (cx - vb.getCenterX()), y = (float) (baselineBottom - vb.getMaxY() - 0.5);
        java.awt.Shape outline = gv.getOutline(x, y);
        g.fill(outline);
        // Обычное начертание Segoe UI чуть тоньше букв баннера, полужирное — толще: добавляем обводку в полпикселя.
        g.setStroke(new BasicStroke(0.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(outline);
    }

    /**
     * Копирует значок «Text» со сдвигом dx, перекрашивая тело в newColor: каждый пиксель раскладывается
     * как фон + a·(цвет тела − фон), и a переносится на новый цвет поверх уже стёртого фона.
     * Для таблицы полоски текста заменяются белой сеткой.
     */
    static void recolorCopy(BufferedImage original, int dx, Color newColor, boolean grid) {
        int body = avg(original, 562, 640, 572, 690);
        int[] c0 = rgb(body), c1 = {newColor.getRed(), newColor.getGreen(), newColor.getBlue()};
        BufferedImage icon = new BufferedImage(TEXT_X1 - TEXT_X0 + 1, TEXT_Y1 - TEXT_Y0 + 1, BufferedImage.TYPE_INT_ARGB);
        for (int y = TEXT_Y0; y <= TEXT_Y1; y++) {
            int bl = avg(original, TEXT_X0 - 4, y, TEXT_X0 - 1, y), br = avg(original, TEXT_X1 + 1, y, TEXT_X1 + 4, y);
            for (int x = TEXT_X0; x <= TEXT_X1; x++) {
                int[] b = rgb(mix(bl, br, (x - TEXT_X0 + 1.0) / (TEXT_X1 - TEXT_X0 + 2.0)));
                int[] p = rgb(original.getRGB(x, y));
                double num = 0, den = 0;
                for (int k = 0; k < 3; k++) { num += (p[k] - b[k]) * (c0[k] - b[k]); den += (c0[k] - b[k]) * (c0[k] - b[k]); }
                double t = Math.max(-0.3, Math.min(1.25, num / den));
                // Храним «силу» тела в альфе (0..1 → 0..255), белое (t<0) — отдельно, чтобы не потерять полоски.
                int alpha = (int) Math.round(Math.max(0, t) * 255 / 1.25);
                int white = (int) Math.round(Math.max(0, -t) * 255 / 0.3);
                icon.setRGB(x - TEXT_X0, y - TEXT_Y0, (alpha << 24) | (white << 16));
            }
        }
        if (grid) {
            // Полоски текста (x 572..634, y 626..684) убираем: «сила» тела интерполируется по вертикали.
            for (int x = 572; x <= 634; x++) {
                int top = icon.getRGB(x - TEXT_X0, 624 - TEXT_Y0) >>> 24, bot = icon.getRGB(x - TEXT_X0, 686 - TEXT_Y0) >>> 24;
                for (int y = 625; y <= 685; y++) {
                    int al = (int) Math.round(top + (bot - top) * (y - 624.0) / 62.0);
                    icon.setRGB(x - TEXT_X0, y - TEXT_Y0, al << 24);
                }
            }
        }
        for (int y = TEXT_Y0; y <= TEXT_Y1; y++) {
            for (int x = TEXT_X0; x <= TEXT_X1; x++) {
                int v = icon.getRGB(x - TEXT_X0, y - TEXT_Y0);
                double t = (v >>> 24) * 1.25 / 255.0, w = ((v >> 16) & 255) * 0.3 / 255.0;
                int dxPix = x + dx;
                int[] b = rgb(img.getRGB(dxPix, y));
                double[] r = new double[3];
                for (int k = 0; k < 3; k++) r[k] = b[k] + t * (c1[k] - b[k]) + w * (255 - b[k]);
                img.setRGB(dxPix, y, pack(r[0], r[1], r[2]));
            }
        }
        if (grid) {
            Graphics2D g = quality(img.createGraphics());
            g.translate(dx, 0);
            g.setColor(new Color(0xF4F8FC));
            float sw = 3.2f;
            g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double x0 = 576, y0 = 630, x1 = 630, y1 = 682;
            g.draw(new RoundRectangle2D.Double(x0, y0, x1 - x0, y1 - y0, 7, 7));
            g.fill(new RoundRectangle2D.Double(x0, y0, x1 - x0, 13, 7, 7));   // строка заголовков
            g.fill(new Rectangle2D.Double(x0, y0 + 6, x1 - x0, 7));
            for (int i = 1; i < 3; i++) g.draw(new Line2D.Double(x0 + (x1 - x0) * i / 3, y0, x0 + (x1 - x0) * i / 3, y1));
            for (int i = 2; i < 4; i++) g.draw(new Line2D.Double(x0, y0 + 13 + (y1 - y0 - 13) * (i - 1) / 3.0, x1, y0 + 13 + (y1 - y0 - 13) * (i - 1) / 3.0));
            g.dispose();
        }
    }

    // ------------------------------------------------------------ 4. иконка приложения

    /** Старая иконка слева от «OneDoc»: скруглённый квадрат x 82–294, y 137–346, радиус около 40. */
    static final int ICON_X = 82, ICON_Y = 137, ICON_W = 213, ICON_H = 210, ICON_R = 44;

    /**
     * Стирает старую иконку (фон — переход между краями) и кладёт иконку приложения в тот же скруглённый
     * квадрат. Край сглаживается вручную: доля покрытия пикселя квадратом по 4×4 подвыборкам
     * (клип Java2D края не сглаживает).
     */
    static void appIcon(BufferedImage icon) {
        eraseRows(ICON_X - 10, ICON_Y - 10, ICON_X + ICON_W + 10, ICON_Y + ICON_H + 10);
        BufferedImage small = scaleDown(icon, ICON_W, ICON_H);
        RoundRectangle2D shape = new RoundRectangle2D.Double(ICON_X, ICON_Y, ICON_W, ICON_H, ICON_R * 2, ICON_R * 2);
        for (int y = ICON_Y - 1; y <= ICON_Y + ICON_H; y++) for (int x = ICON_X - 1; x <= ICON_X + ICON_W; x++) {
            int in = 0;
            for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++) if (shape.contains(x + (i + 0.5) / 4, y + (j + 0.5) / 4)) in++;
            if (in == 0) continue;
            int u = Math.max(0, Math.min(ICON_W - 1, x - ICON_X)), v = Math.max(0, Math.min(ICON_H - 1, y - ICON_Y));
            img.setRGB(x, y, mix(img.getRGB(x, y), small.getRGB(u, v), in / 16.0));
        }
    }

    // ------------------------------------------------------------ 2. летающие карточки

    /** Повёрнутый прямоугольник минимальной площади вокруг точек: {cx, cy, w, h, угол}. */
    static double[] minRect(List<int[]> pts) {
        double best = Double.MAX_VALUE; double[] res = null;
        for (double deg = -35; deg <= 35; deg += 0.25) {
            double t = Math.toRadians(deg), c = Math.cos(t), s = Math.sin(t);
            double minU = 1e9, maxU = -1e9, minV = 1e9, maxV = -1e9;
            for (int[] p : pts) {
                double u = p[0] * c + p[1] * s, v = -p[0] * s + p[1] * c;
                minU = Math.min(minU, u); maxU = Math.max(maxU, u); minV = Math.min(minV, v); maxV = Math.max(maxV, v);
            }
            double area = (maxU - minU) * (maxV - minV);
            if (area < best) {
                best = area;
                double cu = (minU + maxU) / 2, cv = (minV + maxV) / 2;
                res = new double[]{cu * c - cv * s, cu * s + cv * c, maxU - minU + 1, maxV - minV + 1, t};
            }
        }
        return res;
    }

    /**
     * Значки на карточках квадратные; лишняя высота — захваченный край соседнего элемента (сетки):
     * сторона = меньшей стороне, центр сдвигается к верху (лишнее — снизу).
     */
    static double[] square(double[] r) {
        double side = Math.min(r[2], r[3]), shift = (r[3] - side) / 2;
        return new double[]{r[0] + Math.sin(r[4]) * shift, r[1] - Math.cos(r[4]) * shift, side, side, r[4]};
    }

    static List<int[]> collect(int x0, int y0, int x1, int y1, IntPredicate test) {
        List<int[]> pts = new ArrayList<>();
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) if (test.test(img.getRGB(x, y))) pts.add(new int[]{x, y});
        return pts;
    }

    /** Средний цвет точек в верхней и нижней трети квадрата (в его собственных координатах). */
    static int[] topBottom(List<int[]> pts, double[] r) {
        double c = Math.cos(r[4]), s = Math.sin(r[4]);
        double[][] acc = new double[2][4];
        for (int[] p : pts) {
            double v = -(p[0] - r[0]) * s + (p[1] - r[1]) * c;
            int k = v < -r[3] / 6 ? 0 : v > r[3] / 6 ? 1 : -1;
            if (k < 0) continue;
            int[] q = rgb(img.getRGB(p[0], p[1]));
            acc[k][0] += q[0]; acc[k][1] += q[1]; acc[k][2] += q[2]; acc[k][3]++;
        }
        return new int[]{pack(acc[0][0] / acc[0][3], acc[0][1] / acc[0][3], acc[0][2] / acc[0][3]),
                pack(acc[1][0] / acc[1][3], acc[1][1] / acc[1][3], acc[1][2] / acc[1][3])};
    }

    /** Графика в системе координат квадрата: начало — центр, ось x — вдоль его верхней стороны. */
    static Graphics2D squareGraphics(double[] r) {
        Graphics2D g = quality(img.createGraphics());
        g.translate(r[0], r[1]);
        g.rotate(r[4]);
        return g;
    }

    static void fillSquare(Graphics2D g, double[] r, int[] colors) {
        double w = r[2] + 3, h = r[3] + 3;
        g.setPaint(new GradientPaint(0, (float) (-h / 2), new Color(colors[0]), 0, (float) (h / 2), new Color(colors[1])));
        g.fill(new RoundRectangle2D.Double(-w / 2, -h / 2, w, h, w * 0.26, h * 0.26));
    }

    static void wordCard() {
        List<int[]> pts = collect(900, 350, 1025, 475, c -> {
            int[] p = rgb(c); return p[2] > 150 && p[0] < 90 && p[2] - p[0] > 100 && p[1] < 150;
        });
        double[] r = square(minRect(pts));
        out.printf("квадрат W: центр %.1f,%.1f  %.1f×%.1f  угол %.1f°  (%d точек)%n", r[0], r[1], r[2], r[3], Math.toDegrees(r[4]), pts.size());
        int[] colors = topBottom(pts, r);
        Graphics2D g = squareGraphics(r);
        fillSquare(g, r, colors);
        // Нейтральный документ: четыре полоски текста разной длины.
        double w = r[2];
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke((float) (w * 0.085), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double[] lens = {0.56, 0.56, 0.56, 0.36};
        for (int i = 0; i < 4; i++) {
            double y = -w * 0.27 + i * w * 0.18, x0 = -w * 0.28;
            g.draw(new Line2D.Double(x0, y, x0 + w * lens[i], y));
        }
        g.dispose();
    }

    static void excelCard() {
        List<int[]> pts = collect(1595, 170, 1700, 276, c -> {
            int[] p = rgb(c); return p[1] > 100 && p[1] < 175 && p[0] < 45 && p[2] < 115 && p[1] - p[2] > 25;
        });
        double[] r = square(minRect(pts));
        out.printf("квадрат X: центр %.1f,%.1f  %.1f×%.1f  угол %.1f°  (%d точек)%n", r[0], r[1], r[2], r[3], Math.toDegrees(r[4]), pts.size());
        // Зелёная сетка на карточке → синяя (оттенок палитры баннера).
        float hueBlue = 212f / 360f;
        for (int y = 140; y <= 470; y++) for (int x = 1575; x <= 1775; x++) {
            int[] p = rgb(img.getRGB(x, y));
            if (p[1] > p[0] + 12 && p[1] > p[2] + 6) {
                float[] hsb = Color.RGBtoHSB(p[0], p[1], p[2], null);
                img.setRGB(x, y, Color.HSBtoRGB(hueBlue, Math.min(1f, hsb[1] * 1.05f), hsb[2]) & 0xFFFFFF);
            }
        }
        // Квадрат: те же оттенки после перекраски, внутри — белая таблица.
        int[] colors = {0x2F80E6, 0x1E5FC8};
        Graphics2D g = squareGraphics(r);
        fillSquare(g, r, colors);
        double w = r[2] * 0.6, h = r[3] * 0.56;
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke((float) (r[2] * 0.06), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new RoundRectangle2D.Double(-w / 2, -h / 2, w, h, w * 0.18, w * 0.18));
        g.fill(new RoundRectangle2D.Double(-w / 2, -h / 2, w, h * 0.28, w * 0.18, w * 0.18));
        g.fill(new Rectangle2D.Double(-w / 2, -h / 2 + h * 0.14, w, h * 0.14));
        for (int i = 1; i < 3; i++) g.draw(new Line2D.Double(-w / 2 + w * i / 3, -h / 2, -w / 2 + w * i / 3, h / 2));
        g.draw(new Line2D.Double(-w / 2, -h / 2 + h * 0.28 + (h * 0.72) / 2, w / 2, -h / 2 + h * 0.28 + (h * 0.72) / 2));
        g.dispose();
    }

    // ------------------------------------------------------------ 3. экран телефона

    // Углы экрана (пересечения сторон рамки, найдены Edges.java): TL, TR, BR, BL.
    static final double[][] QUAD = {{1174.7, 40.0}, {1563.3, 84.0}, {1398.8, 887.6}, {1000.5, 811.9}};
    /** Радиус скругления экрана в пикселях скриншота (1080 по ширине). */
    static final double CORNER_R = 112;

    /** Высота строки состояния на скриншоте и где на экране макета у неё должна быть верхняя граница (на уровне «острова»). */
    static final int STATUS_BAR = 63, STATUS_TOP = 50;

    /**
     * На макете строка состояния стоит по бокам от «острова», а не у самого края экрана:
     * сверху добавляется полоса цвета строки состояния, низ (полоска жестов) срезается.
     */
    static BufferedImage withStatusInset(BufferedImage shot) {
        BufferedImage s = new BufferedImage(shot.getWidth(), shot.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = s.createGraphics();
        g.setColor(new Color(shot.getRGB(shot.getWidth() / 2, 4)));
        g.fillRect(0, 0, s.getWidth(), STATUS_TOP);
        g.drawImage(shot, 0, STATUS_TOP, null);
        g.dispose();
        return s;
    }

    static void phoneScreen(BufferedImage shot, BufferedImage original) {
        shot = withStatusInset(shot);
        int sw = 405, sh = 810;  // скриншот заранее уменьшается до размера экрана на баннере
        BufferedImage small = scaleDown(shot, sw, sh);
        double[] h = homography(QUAD, sw, sh); // экран баннера → координаты уменьшенного скриншота
        double r = CORNER_R * sw / shot.getWidth();
        int minX = 990, maxX = 1575, minY = 30, maxY = img.getHeight() - 1;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                // Покрытие пикселя: 4×4 подвыборки — гладкий край по скруглению и рамке.
                int inside = 0; double su = 0, sv = 0;
                for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++) {
                    double[] uv = apply(h, x + (i + 0.5) / 4, y + (j + 0.5) / 4);
                    if (insideRounded(uv[0], uv[1], sw, sh, r)) { inside++; su += uv[0]; sv += uv[1]; }
                }
                if (inside == 0) continue;
                int c = bilinear(small, su / inside, sv / inside);
                img.setRGB(x, y, mix(img.getRGB(x, y), c, inside / 16.0));
            }
        }
        // «Остров» с камерой — поверх скриншота, из оригинала.
        for (int y = 60; y <= 122; y++) for (int x = 1295; x <= 1435; x++) {
            int o = original.getRGB(x, y);
            double t = Math.max(0, Math.min(1, (95 - lum(o)) / 55));
            if (t > 0) img.setRGB(x, y, mix(img.getRGB(x, y), o, t));
        }
    }

    static boolean insideRounded(double u, double v, double w, double h, double r) {
        if (u < 0 || v < 0 || u > w || v > h) return false;
        double cx = Math.max(r, Math.min(w - r, u)), cy = Math.max(r, Math.min(h - r, v));
        return (u - cx) * (u - cx) + (v - cy) * (v - cy) <= r * r;
    }

    static int bilinear(BufferedImage im, double u, double v) {
        u = Math.max(0, Math.min(im.getWidth() - 1.001, u - 0.5));
        v = Math.max(0, Math.min(im.getHeight() - 1.001, v - 0.5));
        int x = (int) u, y = (int) v; double fx = u - x, fy = v - y;
        int top = mix(im.getRGB(x, y), im.getRGB(x + 1, y), fx), bot = mix(im.getRGB(x, y + 1), im.getRGB(x + 1, y + 1), fx);
        return mix(top, bot, fy);
    }

    static BufferedImage scaleDown(BufferedImage s, int w, int h) {
        BufferedImage cur = s; int cw = s.getWidth(), ch = s.getHeight();
        do {
            cw = Math.max(w, cw / 2); ch = Math.max(h, ch / 2);
            BufferedImage n = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = quality(n.createGraphics());
            g.drawImage(cur, 0, 0, cw, ch, null);
            g.dispose();
            cur = n;
        } while (cw != w || ch != h);
        return cur;
    }

    /** Гомография, переводящая четырёхугольник quad в прямоугольник w×h (решение системы 8×8). */
    static double[] homography(double[][] quad, double w, double h) {
        double[][] dst = {{0, 0}, {w, 0}, {w, h}, {0, h}};
        double[][] m = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = quad[i][0], y = quad[i][1], u = dst[i][0], v = dst[i][1];
            m[2 * i] = new double[]{x, y, 1, 0, 0, 0, -u * x, -u * y, u};
            m[2 * i + 1] = new double[]{0, 0, 0, x, y, 1, -v * x, -v * y, v};
        }
        for (int c = 0; c < 8; c++) {
            int p = c;
            for (int r = c + 1; r < 8; r++) if (Math.abs(m[r][c]) > Math.abs(m[p][c])) p = r;
            double[] t = m[c]; m[c] = m[p]; m[p] = t;
            for (int r = 0; r < 8; r++) {
                if (r == c) continue;
                double f = m[r][c] / m[c][c];
                for (int k = c; k < 9; k++) m[r][k] -= f * m[c][k];
            }
        }
        double[] hh = new double[9];
        for (int i = 0; i < 8; i++) hh[i] = m[i][8] / m[i][i];
        hh[8] = 1;
        return hh;
    }

    static double[] apply(double[] h, double x, double y) {
        double d = h[6] * x + h[7] * y + h[8];
        return new double[]{(h[0] * x + h[1] * y + h[2]) / d, (h[3] * x + h[4] * y + h[5]) / d};
    }
}

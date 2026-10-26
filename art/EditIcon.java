import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Нейтральная версия иконки: плитки «Word» (W) и «Excel» (X) из art/onedoci.png — знаки, похожие на
 * логотипы Microsoft, — перерисовываются как на обложке: синий документ с полосками текста («Doc»)
 * и бирюзовая таблица с сеткой («Sheet»). Тёмная обводка плиток и остальной рисунок не меняются.
 *
 * Запуск из корня репозитория (Java из Android Studio, без сборки):
 *   "C:/Program Files/Android/Android Studio/jbr/bin/java.exe" art/EditIcon.java art/onedoci.png art/onedoc_neutral.png
 * Координаты плиток измерены на art/onedoci.png (512×512).
 */
public class EditIcon {
    static BufferedImage img;
    static final PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

    /** Синяя грань плитки «Word» и зелёная грань «Excel»: x0, y0, x1, y1 (внутри тёмной обводки). */
    static final int[] WORD_FACE = {262, 104, 356, 211}, EXCEL_FACE = {264, 220, 356, 322};
    /** Подпись «PDF» на соседней плитке — образец шрифта и базовой линии. */
    static final int[] PDF_LABEL = {170, 185, 230, 207};
    static final Color SHEET_TOP = new Color(0x0B7A87), SHEET_BOTTOM = new Color(0x065A66);

    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        img = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g0 = img.createGraphics();
        g0.drawImage(src, 0, 0, null);
        g0.dispose();

        // Зелёная фигура за плиткой «Excel» → бирюзовая (сама плитка перерисовывается ниже).
        float teal = 186f / 360f;
        for (int y = 170; y <= 360; y++) for (int x = 240; x <= 400; x++) {
            int c = img.getRGB(x, y);
            int r = (c >> 16) & 255, gr = (c >> 8) & 255, b = c & 255;
            if (gr > r + 25 && gr > b + 10) {
                float[] hsb = Color.RGBtoHSB(r, gr, b, null);
                img.setRGB(x, y, (c & 0xFF000000) | (Color.HSBtoRGB(teal, hsb[1], hsb[2]) & 0xFFFFFF));
            }
        }

        Rectangle2D labelBox = whiteBox(PDF_LABEL);
        Font font = labelFont(labelBox.getWidth());
        int[] wordColors = faceColors(WORD_FACE);

        Graphics2D g = quality(img.createGraphics());
        // Плитка «Doc»: грань заново, белые полоски текста, подпись.
        face(g, WORD_FACE, new Color(wordColors[0]), new Color(wordColors[1]));
        double cx = (WORD_FACE[0] + WORD_FACE[2]) / 2.0;
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double[] lens = {50, 50, 50, 32};
        for (int i = 0; i < 4; i++) {
            double y = 124 + i * 15, x0 = cx - 25;
            g.draw(new Line2D.Double(x0, y, x0 + lens[i], y));
        }
        label(g, font, "Doc", cx, labelBox.getMaxY());

        // Плитка «Sheet»: бирюзовая грань, белая таблица (строка заголовков + сетка 3×3), подпись.
        face(g, EXCEL_FACE, SHEET_TOP, SHEET_BOTTOM);
        cx = (EXCEL_FACE[0] + EXCEL_FACE[2]) / 2.0;
        double w = 58, h = 46, x0 = cx - w / 2, y0 = 238;
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new RoundRectangle2D.Double(x0, y0, w, h, 9, 9));
        g.fill(new RoundRectangle2D.Double(x0, y0, w, 13, 9, 9));
        g.fill(new Rectangle2D.Double(x0, y0 + 6, w, 7));
        for (int i = 1; i < 3; i++) g.draw(new Line2D.Double(x0 + w * i / 3, y0, x0 + w * i / 3, y0 + h));
        for (int i = 1; i < 3; i++) {
            double y = y0 + 13 + (h - 13) * i / 3;
            g.draw(new Line2D.Double(x0, y, x0 + w, y));
        }
        label(g, font, "Sheet", cx, EXCEL_FACE[3] - 7);
        g.dispose();

        ImageIO.write(img, "png", new File(a[1]));
        out.printf("подпись PDF: %s; готово: %s%n", labelBox, a[1]);
    }

    static Graphics2D quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    /** Грань плитки — скруглённый прямоугольник с вертикальным градиентом, чуть шире замеренной (без зазора с обводкой). */
    static void face(Graphics2D g, int[] f, Color top, Color bottom) {
        g.setPaint(new GradientPaint(0, f[1], top, 0, f[3], bottom));
        g.fill(new RoundRectangle2D.Double(f[0] - 1.5, f[1] - 1.5, f[2] - f[0] + 3, f[3] - f[1] + 3, 22, 22));
    }

    /** Средний цвет синей грани у левого края: верх и низ (буква и листок там не заходят). */
    static int[] faceColors(int[] f) {
        return new int[]{avg(f[0] + 3, f[1] + 30, f[0] + 6, f[1] + 40), avg(f[0] + 3, f[3] - 40, f[0] + 6, f[3] - 30)};
    }

    static int avg(int x0, int y0, int x1, int y1) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            int c = img.getRGB(x, y); r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
        }
        return (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
    }

    /** Габариты белых букв подписи. */
    static Rectangle2D whiteBox(int[] r) {
        int minX = r[2], minY = r[3], maxX = r[0], maxY = r[1];
        for (int y = r[1]; y <= r[3]; y++) for (int x = r[0]; x <= r[2]; x++) {
            int c = img.getRGB(x, y);
            if (((c >> 16) & 255) > 215 && ((c >> 8) & 255) > 185 && (c & 255) > 185) {
                minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        return new Rectangle2D.Double(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /** Segoe UI Bold такого размера, что «PDF» занимает ту же ширину, что и на иконке. */
    static Font labelFont(double width) throws Exception {
        Font base = Font.createFont(Font.TRUETYPE_FONT, new File("C:/Windows/Fonts/segoeuib.ttf"));
        FontRenderContext frc = new FontRenderContext(null, true, true);
        double size = 20;
        for (int i = 0; i < 30; i++) size *= width / base.deriveFont((float) size).createGlyphVector(frc, "PDF").getVisualBounds().getWidth();
        out.printf("шрифт подписей: Segoe UI Bold %.1f px%n", size);
        return base.deriveFont((float) size);
    }

    /** Белая подпись по центру плитки; bottom — нижний край букв (у этих слов без выносных элементов). */
    static void label(Graphics2D g, Font font, String text, double cx, double bottom) {
        GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), text);
        Rectangle2D vb = gv.getVisualBounds();
        g.setColor(Color.WHITE);
        g.fill(gv.getOutline((float) (cx - vb.getCenterX()), (float) (bottom - vb.getMaxY())));
    }
}

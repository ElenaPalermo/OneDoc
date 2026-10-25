package io.doccraftstudio.onedoc.reader.xlsx

import io.doccraftstudio.onedoc.reader.sheet.CellStyle
import io.doccraftstudio.onedoc.reader.sheet.HorizontalAlignment
import io.doccraftstudio.onedoc.reader.xml.XmlScanner
import io.doccraftstudio.onedoc.reader.xml.attr
import io.doccraftstudio.onedoc.reader.xml.forEachChild
import io.doccraftstudio.onedoc.reader.xml.moveToRoot
import io.doccraftstudio.onedoc.reader.xml.relationshipAttr
import io.doccraftstudio.onedoc.reader.xml.toggleValue
import org.xmlpull.v1.XmlPullParser

/** Связь части пакета OOXML: [path] — уже разрешённый путь внутри архива. */
internal class XlsxRelationship(val path: String, val type: String, val external: Boolean)

/** Разбирает `*.rels`; относительные пути разрешаются от папки [baseDir] (например, `xl/`). */
internal fun parseRelationships(parser: XmlPullParser, baseDir: String): Map<String, XlsxRelationship> {
    val result = HashMap<String, XlsxRelationship>()
    if (!parser.moveToRoot()) return result
    parser.forEachChild { name ->
        if (name != "Relationship") return@forEachChild
        val id = parser.attr("Id") ?: return@forEachChild
        val target = parser.attr("Target") ?: return@forEachChild
        val external = parser.attr("TargetMode") == "External"
        result[id] = XlsxRelationship(
            path = if (external) target else resolvePartPath(baseDir, target),
            type = parser.attr("Type").orEmpty(),
            external = external,
        )
    }
    return result
}

/** `xl/` + `worksheets/sheet1.xml` → `xl/worksheets/sheet1.xml`; `..` и абсолютные пути учитываются. */
internal fun resolvePartPath(baseDir: String, target: String): String {
    if (target.startsWith("/")) return target.removePrefix("/")
    val parts = ArrayList<String>()
    for (part in (baseDir + target).split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull()
            else -> parts += part
        }
    }
    return parts.joinToString("/")
}

internal class XlsxSheetRef(val name: String, val relationshipId: String?, val hidden: Boolean)

internal class XlsxWorkbook(val sheets: List<XlsxSheetRef>, val date1904: Boolean) {
    companion object {
        fun parse(parser: XmlPullParser): XlsxWorkbook {
            val sheets = ArrayList<XlsxSheetRef>()
            var date1904 = false
            if (!parser.moveToRoot()) return XlsxWorkbook(sheets, date1904)
            parser.forEachChild { name ->
                when (name) {
                    "workbookPr" -> date1904 = parser.attr("date1904")?.let { it == "1" || it == "true" } ?: false
                    "sheets" -> parser.forEachChild { child ->
                        if (child == "sheet") {
                            sheets += XlsxSheetRef(
                                name = parser.attr("name").orEmpty(),
                                relationshipId = parser.relationshipAttr("id"),
                                hidden = parser.attr("state").let { it == "hidden" || it == "veryHidden" },
                            )
                        }
                    }
                }
            }
            return XlsxWorkbook(sheets, date1904)
        }
    }
}

/** Таблица общих строк; у строк с оформлением (`<r>`) склеиваются все фрагменты, фонетика (`rPh`) пропускается. */
internal fun parseSharedStrings(scanner: XmlScanner): List<String> {
    val strings = ArrayList<String>()
    if (!scanner.moveToRoot()) return strings
    val uniqueCount = scanner.attrInt(UNIQUE_COUNT, 0)
    if (uniqueCount > 0) strings.ensureCapacity(uniqueCount.coerceAtMost(MAX_PRESIZE))
    val text = StringBuilder()
    scanner.forEachChild {
        if (!scanner.isName(SI)) return@forEachChild
        text.setLength(0)
        scanner.forEachChild {
            when {
                scanner.isName(T) -> text.append(scanner.text())
                scanner.isName(R) -> scanner.forEachChild { if (scanner.isName(T)) text.append(scanner.text()) }
            }
        }
        strings += text.toString()
    }
    return strings
}

private val UNIQUE_COUNT = "uniqueCount".toByteArray()
private val SI = "si".toByteArray()
private val T = "t".toByteArray()
private val R = "r".toByteArray()

private const val MAX_PRESIZE = 1_000_000

/**
 * Стили ячеек (`cellXfs`): индекс стиля ячейки (`s`) → оформление и формат числа.
 */
internal class XlsxStyles(
    val cellStyles: List<CellStyle>,
    private val formatIds: IntArray,
    private val customFormats: Map<Int, String>,
) {
    fun formatId(style: Int): Int = formatIds.getOrElse(style) { 0 }

    /** Код пользовательского формата; для встроенных — `null` (их знает форматтер). */
    fun formatCode(formatId: Int): String? = customFormats[formatId]

    companion object {
        val EMPTY = XlsxStyles(listOf(CellStyle.DEFAULT), IntArray(1), emptyMap())

        fun parse(parser: XmlPullParser, themeColors: List<Int>): XlsxStyles {
            val customFormats = HashMap<Int, String>()
            val fonts = ArrayList<FontProps>()
            val fills = ArrayList<Int?>()
            val styles = ArrayList<CellStyle>()
            val formatIds = ArrayList<Int>()
            if (!parser.moveToRoot()) return EMPTY
            parser.forEachChild { name ->
                when (name) {
                    "numFmts" -> parser.forEachChild { child ->
                        if (child == "numFmt") {
                            val id = parser.attr("numFmtId")?.toIntOrNull()
                            val code = parser.attr("formatCode")
                            if (id != null && code != null) customFormats[id] = code
                        }
                    }
                    "fonts" -> parser.forEachChild { if (it == "font") fonts += parser.readFont(themeColors) }
                    "fills" -> parser.forEachChild { if (it == "fill") fills += parser.readFill(themeColors) }
                    "cellXfs" -> parser.forEachChild { child ->
                        if (child != "xf") return@forEachChild
                        val font = parser.attr("fontId")?.toIntOrNull()?.let(fonts::getOrNull) ?: FontProps()
                        val fill = parser.attr("fillId")?.toIntOrNull()?.let(fills::getOrNull)
                        formatIds += parser.attr("numFmtId")?.toIntOrNull() ?: 0
                        var alignment = HorizontalAlignment.GENERAL
                        parser.forEachChild { if (it == "alignment") alignment = horizontalAlignment(parser.attr("horizontal")) }
                        styles += CellStyle(
                            bold = font.bold,
                            italic = font.italic,
                            underline = font.underline,
                            strikethrough = font.strikethrough,
                            textColor = font.color,
                            fillColor = fill,
                            alignment = alignment,
                        )
                    }
                }
            }
            if (styles.isEmpty()) return XlsxStyles(listOf(CellStyle.DEFAULT), IntArray(1), customFormats)
            return XlsxStyles(styles, formatIds.toIntArray(), customFormats)
        }

        private fun horizontalAlignment(value: String?): HorizontalAlignment = when (value) {
            "left", "justify", "fill", "distributed" -> HorizontalAlignment.LEFT
            "center", "centerContinuous" -> HorizontalAlignment.CENTER
            "right" -> HorizontalAlignment.RIGHT
            else -> HorizontalAlignment.GENERAL
        }
    }
}

private class FontProps(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val color: Int? = null,
)

private fun XmlPullParser.readFont(themeColors: List<Int>): FontProps {
    var bold = false
    var italic = false
    var underline = false
    var strikethrough = false
    var color: Int? = null
    forEachChild { name ->
        when (name) {
            "b" -> bold = toggleValue()
            "i" -> italic = toggleValue()
            "u" -> underline = attr("val") != "none"
            "strike" -> strikethrough = toggleValue()
            "color" -> color = readColor(themeColors)
        }
    }
    return FontProps(bold, italic, underline, strikethrough, color)
}

/** Цвет заливки ячейки: у сплошной и узорной заливки берётся цвет переднего плана. */
private fun XmlPullParser.readFill(themeColors: List<Int>): Int? {
    var color: Int? = null
    forEachChild { name ->
        if (name != "patternFill") return@forEachChild
        val pattern = attr("patternType")
        forEachChild { child -> if (child == "fgColor") color = readColor(themeColors) }
        if (pattern == null || pattern == "none" || pattern == "gray125") color = null
    }
    return color
}

/** `rgb="FFRRGGBB"`, `theme="4" tint="0.4"` или `indexed="10"`; `auto` и системные цвета — `null`. */
internal fun XmlPullParser.readColor(themeColors: List<Int>): Int? {
    val base = attr("rgb")?.let(::parseArgb)
        ?: attr("theme")?.toIntOrNull()?.let(themeColors::getOrNull)
        ?: attr("indexed")?.toIntOrNull()?.let(INDEXED_COLORS::getOrNull)
        ?: return null
    val tint = attr("tint")?.toDoubleOrNull() ?: 0.0
    return if (tint == 0.0) base else applyTint(base, tint)
}

/** `FFRRGGBB` или `RRGGBB`; нулевая альфа встречается у старых генераторов и означает непрозрачный цвет. */
internal fun parseArgb(value: String): Int? {
    val hex = value.trim().takeLast(8)
    val parsed = hex.toLongOrNull(16)?.toInt() ?: return null
    return if (hex.length <= 6 || parsed ushr 24 == 0) parsed or (0xFF shl 24) else parsed
}

/** Оттенок цвета темы, как в Excel: tint < 0 затемняет, > 0 осветляет (по светлоте HSL). */
internal fun applyTint(argb: Int, tint: Double): Int {
    val r = (argb shr 16 and 0xFF) / 255.0
    val g = (argb shr 8 and 0xFF) / 255.0
    val b = (argb and 0xFF) / 255.0
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    var lightness = (max + min) / 2
    val delta = max - min
    val saturation = if (delta == 0.0) 0.0 else delta / (1 - kotlin.math.abs(2 * lightness - 1))
    val hue = when {
        delta == 0.0 -> 0.0
        max == r -> 60 * (((g - b) / delta).mod(6.0))
        max == g -> 60 * ((b - r) / delta + 2)
        else -> 60 * ((r - g) / delta + 4)
    }
    lightness = if (tint < 0) lightness * (1 + tint) else lightness * (1 - tint) + tint
    lightness = lightness.coerceIn(0.0, 1.0)

    val chroma = (1 - kotlin.math.abs(2 * lightness - 1)) * saturation
    val x = chroma * (1 - kotlin.math.abs((hue / 60).mod(2.0) - 1))
    val m = lightness - chroma / 2
    val (r1, g1, b1) = when {
        hue < 60 -> Triple(chroma, x, 0.0)
        hue < 120 -> Triple(x, chroma, 0.0)
        hue < 180 -> Triple(0.0, chroma, x)
        hue < 240 -> Triple(0.0, x, chroma)
        hue < 300 -> Triple(x, 0.0, chroma)
        else -> Triple(chroma, 0.0, x)
    }
    fun channel(value: Double) = ((value + m) * 255).toInt().coerceIn(0, 255)
    return (argb and 0xFF000000.toInt()) or (channel(r1) shl 16) or (channel(g1) shl 8) or channel(b1)
}

/**
 * Цвета темы (`xl/theme/theme1.xml`) в порядке индексов SpreadsheetML: lt1, dk1, lt2, dk2,
 * accent1–6, hlink, folHlink. В файле темы первые пары идут наоборот (dk1, lt1…).
 */
internal fun parseThemeColors(parser: XmlPullParser): List<Int> {
    val colors = HashMap<String, Int>()
    if (!parser.moveToRoot()) return emptyList()
    fun walk() {
        parser.forEachChild { name ->
            when (name) {
                "themeElements" -> walk()
                "clrScheme" -> parser.forEachChild { slot ->
                    var color: Int? = null
                    parser.forEachChild { kind ->
                        color = when (kind) {
                            "srgbClr" -> parser.attr("val")?.let(::parseArgb)
                            "sysClr" -> parser.attr("lastClr")?.let(::parseArgb)
                            else -> color
                        }
                    }
                    color?.let { colors[slot] = it }
                }
            }
        }
    }
    walk()
    return THEME_SLOTS.map { colors[it] ?: 0xFF000000.toInt() }
}

private val THEME_SLOTS = listOf(
    "lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink",
)

/** Стандартная палитра Excel для `indexed`-цветов (0–63); 64 и 65 — системные цвета «авто». */
private val INDEXED_COLORS: List<Int> = listOf(
    0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
    0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
    0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
    0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
    0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
    0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
    0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
    0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
).map { it or (0xFF shl 24) }

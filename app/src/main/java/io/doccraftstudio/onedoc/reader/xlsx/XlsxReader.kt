package io.doccraftstudio.onedoc.reader.xlsx

import io.doccraftstudio.onedoc.reader.sheet.CellFormatter
import io.doccraftstudio.onedoc.reader.sheet.CellKind
import io.doccraftstudio.onedoc.reader.sheet.CellStyle
import io.doccraftstudio.onedoc.reader.sheet.SheetCell
import io.doccraftstudio.onedoc.reader.sheet.SheetSink
import io.doccraftstudio.onedoc.reader.sheet.SpreadsheetSource
import io.doccraftstudio.onedoc.reader.sheet.parseCellRange
import io.doccraftstudio.onedoc.reader.xml.XmlParserFactory
import io.doccraftstudio.onedoc.reader.xml.XmlScanner
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Потоковый парсер XLSX (ZIP + SpreadsheetML) без Apache POI. При открытии читаются только
 * книга, стили, тема и общие строки; листы разбираются по одному, по требованию, прямо из архива —
 * строки сразу уходят в [SheetSink], весь лист в памяти не собирается.
 * Листы и общие строки (самые большие части книги) читает [XmlScanner], остальное — `XmlPullParser`.
 */
class XlsxReader(private val parsers: XmlParserFactory, private val locale: Locale = Locale.getDefault()) {

    fun open(file: File): SpreadsheetSource {
        val zip = ZipFile(file)
        try {
            val workbookPath = zip.parsePart(ROOT_RELATIONSHIPS) { parseRelationships(it, "") }
                ?.values?.firstOrNull { it.type.endsWith(OFFICE_DOCUMENT) && !it.external }?.path
                ?: DEFAULT_WORKBOOK
            val baseDir = workbookPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val workbook = zip.parsePart(workbookPath) { XlsxWorkbook.parse(it) }
                ?: throw IOException("В архиве нет $workbookPath — это не книга Excel")
            val relationships = zip.parsePart("${baseDir}_rels/${workbookPath.substringAfterLast('/')}.rels") {
                parseRelationships(it, baseDir)
            }.orEmpty()

            fun partOfType(type: String, fallback: String) =
                relationships.values.firstOrNull { it.type.endsWith(type) && !it.external }?.path ?: fallback

            val theme = zip.parsePart(partOfType("/theme", "${baseDir}theme/theme1.xml")) { parseThemeColors(it) }.orEmpty()
            val styles = zip.parsePart(partOfType("/styles", "${baseDir}styles.xml")) { XlsxStyles.parse(it, theme) }
                ?: XlsxStyles.EMPTY
            val sharedStrings = scanPart(zip, partOfType("/sharedStrings", "${baseDir}sharedStrings.xml")) {
                parseSharedStrings(it)
            }.orEmpty()

            // Листы-диаграммы и скрытые листы не показываются.
            val sheets = workbook.sheets.mapNotNull { sheet ->
                val relationship = relationships[sheet.relationshipId] ?: return@mapNotNull null
                if (sheet.hidden || !relationship.type.endsWith(WORKSHEET) || zip.getEntry(relationship.path) == null) {
                    return@mapNotNull null
                }
                sheet.name to relationship.path
            }
            if (sheets.isEmpty()) throw IOException("В книге нет листов с данными")
            return XlsxSource(zip, sheets, styles, sharedStrings, workbook.date1904, locale)
        } catch (e: Throwable) {
            zip.close()
            throw e
        }
    }

    private fun <T> ZipFile.parsePart(path: String, block: (XmlPullParser) -> T): T? =
        parsePart(this, parsers, path, block)

    private companion object {
        const val ROOT_RELATIONSHIPS = "_rels/.rels"
        const val DEFAULT_WORKBOOK = "xl/workbook.xml"
        const val OFFICE_DOCUMENT = "/officeDocument"
        const val WORKSHEET = "/worksheet"
    }
}

private fun <T> scanPart(zip: ZipFile, path: String, block: (XmlScanner) -> T): T? {
    val entry = zip.getEntry(path) ?: return null
    return zip.getInputStream(entry).use { block(XmlScanner.open(it)) }
}

private fun <T> parsePart(zip: ZipFile, parsers: XmlParserFactory, path: String, block: (XmlPullParser) -> T): T? {
    val entry = zip.getEntry(path) ?: return null
    return zip.getInputStream(entry).buffered(64 * 1024).use { stream ->
        val parser = parsers.create()
        parser.setInput(stream, null)
        block(parser)
    }
}

private class XlsxSource(
    private val zip: ZipFile,
    private val sheets: List<Pair<String, String>>,
    private val xlsxStyles: XlsxStyles,
    private val sharedStrings: List<String>,
    private val date1904: Boolean,
    private val locale: Locale,
) : SpreadsheetSource {
    override val sheetNames: List<String> = sheets.map { it.first }
    override val styles: List<CellStyle> = xlsxStyles.cellStyles

    override fun readSheet(index: Int, sink: SheetSink) {
        val path = sheets[index].second
        scanPart(zip, path) { scanner ->
            SheetParser(scanner, sink, xlsxStyles, sharedStrings, date1904, CellFormatter(locale)).parse()
        } ?: throw IOException("Лист $path не найден")
    }

    override fun close() = zip.close()
}

/**
 * Разбор листа сканером [XmlScanner]: на листе в миллион строк системный `XmlPullParser`
 * занимал большую часть времени.
 */
private class SheetParser(
    private val scanner: XmlScanner,
    private val sink: SheetSink,
    private val styles: XlsxStyles,
    private val sharedStrings: List<String>,
    private val date1904: Boolean,
    private val formatter: CellFormatter,
) {
    private val cells = ArrayList<SheetCell>()
    private val inlineText = StringBuilder()

    fun parse() {
        // Если в файле ширина по умолчанию не указана, у Excel она 8.43 символа.
        sink.defaultColumnWidth(DEFAULT_COLUMN_WIDTH)
        if (!scanner.moveToRoot()) return
        scanner.forEachChild {
            when {
                scanner.isName(DIMENSION) ->
                    scanner.attr(REF)?.let(::parseCellRange)?.let { sink.expectedRowCount(it.lastRow + 1) }
                scanner.isName(SHEET_FORMAT) -> {
                    val width = scanner.attr(DEFAULT_COL_WIDTH)?.toFloatOrNull()
                        ?: scanner.attr(BASE_COL_WIDTH)?.toFloatOrNull()?.plus(BASE_WIDTH_PADDING)
                    width?.let(sink::defaultColumnWidth)
                }
                scanner.isName(COLS) -> scanner.forEachChild {
                    if (!scanner.isName(COL)) return@forEachChild
                    val min = scanner.attrInt(MIN, 0).takeIf { it > 0 } ?: return@forEachChild
                    val max = scanner.attrInt(MAX, min)
                    val hidden = scanner.attr(HIDDEN).let { it == "1" || it == "true" }
                    val width = scanner.attr(WIDTH)?.toFloatOrNull()
                    if (hidden) sink.columnWidth(min - 1, max - 1, 0f) else if (width != null) sink.columnWidth(min - 1, max - 1, width)
                }
                scanner.isName(SHEET_DATA) -> parseSheetData()
                scanner.isName(MERGE_CELLS) -> scanner.forEachChild {
                    if (scanner.isName(MERGE_CELL)) scanner.attr(REF)?.let(::parseCellRange)?.let(sink::merge)
                }
            }
        }
    }

    private fun parseSheetData() {
        var previousRow = -1
        scanner.forEachChild {
            if (!scanner.isName(ROW)) return@forEachChild
            val rowIndex = scanner.attrInt(R, 0).takeIf { it > 0 }?.minus(1) ?: (previousRow + 1)
            previousRow = rowIndex
            cells.clear()
            var previousColumn = -1
            scanner.forEachChild {
                if (!scanner.isName(C)) return@forEachChild
                val column = scanner.attrColumn(R).takeIf { it >= 0 } ?: (previousColumn + 1)
                previousColumn = column
                parseCell(column, scanner.attrInt(S, 0), scanner.attrIndexOf(T, CELL_TYPES))?.let(cells::add)
            }
            if (cells.isNotEmpty()) sink.row(rowIndex, ArrayList(cells))
        }
    }

    /** [type] — номер типа в [CELL_TYPES], `-1` — число (атрибута `t` нет). */
    private fun parseCell(column: Int, style: Int, type: Int): SheetCell? {
        var value: String? = null
        var inline: String? = null
        scanner.forEachChild {
            when {
                scanner.isName(V) -> value = scanner.text()
                scanner.isName(IS) -> {
                    inlineText.setLength(0)
                    scanner.forEachChild {
                        when {
                            scanner.isName(T) -> inlineText.append(scanner.text())
                            scanner.isName(R) -> scanner.forEachChild { if (scanner.isName(T)) inlineText.append(scanner.text()) }
                        }
                    }
                    inline = inlineText.toString()
                }
                // f — формула: показываем сохранённый результат из v, как Excel до пересчёта.
            }
        }
        val raw = value
        // Без Pair: на миллионах ячеек каждый лишний объект — это работа сборщика мусора во время прокрутки.
        var kind = CellKind.TEXT
        val text = when (type) {
            TYPE_SHARED -> raw?.trim()?.toIntOrNull()?.let(sharedStrings::getOrNull) ?: ""
            TYPE_INLINE -> inline ?: raw.orEmpty()
            TYPE_STR -> raw.orEmpty()
            TYPE_BOOLEAN -> {
                kind = CellKind.BOOLEAN
                if (raw == null) "" else formatter.formatBoolean(raw.trim() == "1")
            }
            TYPE_ERROR -> {
                kind = CellKind.ERROR
                raw.orEmpty()
            }
            TYPE_DATE -> {
                kind = CellKind.NUMBER
                raw.orEmpty()
            }
            else -> {
                val formatId = styles.formatId(style)
                val number = raw?.let { formatter.formatNumberText(it.trim(), formatId, styles.formatCode(formatId), date1904) }
                if (number != null) kind = CellKind.NUMBER
                number ?: raw.orEmpty()
            }
        }
        // Пустую ячейку храним только ради заливки (цветные шапки и полосы таблиц).
        if (text.isEmpty() && styles.cellStyles.getOrNull(style)?.fillColor == null) return null
        return SheetCell(column, text, kind, style.takeIf { it in styles.cellStyles.indices } ?: 0)
    }

    private companion object {
        const val DEFAULT_COLUMN_WIDTH = 8.43f
        /** Ширина по умолчанию = базовая ширина + поля ячейки (~5 пикселей при шрифте 11 пт). */
        const val BASE_WIDTH_PADDING = 0.71f

        val DIMENSION = "dimension".toByteArray()
        val SHEET_FORMAT = "sheetFormatPr".toByteArray()
        val DEFAULT_COL_WIDTH = "defaultColWidth".toByteArray()
        val BASE_COL_WIDTH = "baseColWidth".toByteArray()
        val COLS = "cols".toByteArray()
        val COL = "col".toByteArray()
        val MIN = "min".toByteArray()
        val MAX = "max".toByteArray()
        val HIDDEN = "hidden".toByteArray()
        val WIDTH = "width".toByteArray()
        val SHEET_DATA = "sheetData".toByteArray()
        val MERGE_CELLS = "mergeCells".toByteArray()
        val MERGE_CELL = "mergeCell".toByteArray()
        val REF = "ref".toByteArray()
        val ROW = "row".toByteArray()
        val C = "c".toByteArray()
        val V = "v".toByteArray()
        val IS = "is".toByteArray()
        /** `r` — и номер строки или ячейки, и прогон форматированного текста внутри `is`. */
        val R = "r".toByteArray()
        val S = "s".toByteArray()
        val T = "t".toByteArray()

        /** Значения атрибута `t` ячейки; номера — константы `TYPE_*`. */
        val CELL_TYPES = arrayOf("s", "inlineStr", "str", "b", "e", "d").map { it.toByteArray() }.toTypedArray()
        const val TYPE_SHARED = 0
        const val TYPE_INLINE = 1
        const val TYPE_STR = 2
        const val TYPE_BOOLEAN = 3
        const val TYPE_ERROR = 4
        const val TYPE_DATE = 5
    }
}

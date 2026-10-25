package io.doccraftstudio.onedoc.reader.xls

import io.doccraftstudio.onedoc.reader.sheet.CellFormatter
import io.doccraftstudio.onedoc.reader.sheet.CellKind
import io.doccraftstudio.onedoc.reader.sheet.CellRange
import io.doccraftstudio.onedoc.reader.sheet.CellStyle
import io.doccraftstudio.onedoc.reader.sheet.HorizontalAlignment
import io.doccraftstudio.onedoc.reader.sheet.SheetCell
import io.doccraftstudio.onedoc.reader.sheet.SheetSink
import io.doccraftstudio.onedoc.reader.sheet.SpreadsheetSource
import org.apache.poi.hssf.record.PaletteRecord
import org.apache.poi.hssf.usermodel.HSSFCell
import org.apache.poi.hssf.usermodel.HSSFCellStyle
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.Font
import org.apache.poi.ss.usermodel.FormulaError
import java.io.InputStream
import java.util.Locale
import org.apache.poi.ss.usermodel.HorizontalAlignment as PoiAlignment

/**
 * Excel 97–2003 (.xls) через Apache POI HSSF. Книга целиком читается в память (формат ограничен
 * 65 536 строками), листы отдаются в [SheetSink] так же, как у XLSX.
 *
 * Цвета берутся из палитры книги ([PaletteRecord]) напрямую: `HSSFColor` использует `java.awt.Color`,
 * которого на Android нет.
 */
object XlsReader {
    fun open(input: InputStream, locale: Locale = Locale.getDefault()): SpreadsheetSource = XlsSource(HSSFWorkbook(input), locale)
}

private class XlsSource(private val workbook: HSSFWorkbook, private val locale: Locale) : SpreadsheetSource {
    private val visibleSheets = (0 until workbook.numberOfSheets).filter {
        !workbook.isSheetHidden(it) && !workbook.isSheetVeryHidden(it)
    }
    private val palette: PaletteRecord = workbook.internalWorkbook.customPalette

    override val sheetNames: List<String> = visibleSheets.map(workbook::getSheetName)

    override val styles: List<CellStyle> = (0 until workbook.numCellStyles)
        .map { convertStyle(workbook.getCellStyleAt(it)) }
        .ifEmpty { listOf(CellStyle.DEFAULT) }

    override fun readSheet(index: Int, sink: SheetSink) {
        val sheet = workbook.getSheetAt(visibleSheets[index])
        val formatter = CellFormatter(locale)
        val date1904 = workbook.internalWorkbook.isUsing1904DateWindowing

        sink.defaultColumnWidth(sheet.defaultColumnWidth + BASE_WIDTH_PADDING)
        sink.expectedRowCount(sheet.lastRowNum + 1)
        val lastColumn = sheet.maxOfOrNull { row -> row.lastCellNum.toInt() }?.coerceAtLeast(0) ?: 0
        for (column in 0 until lastColumn) {
            val width = if (sheet.isColumnHidden(column)) 0f else sheet.getColumnWidth(column) / WIDTH_UNITS_PER_CHAR
            sink.columnWidth(column, column, width)
        }

        val cells = ArrayList<SheetCell>()
        for (row in sheet) {
            cells.clear()
            for (cell in row) {
                convertCell(cell as HSSFCell, formatter, date1904)?.let(cells::add)
            }
            if (cells.isNotEmpty()) sink.row(row.rowNum, ArrayList(cells))
        }
        for (i in 0 until sheet.numMergedRegions) {
            val region = sheet.getMergedRegion(i)
            sink.merge(CellRange(region.firstRow, region.lastRow, region.firstColumn, region.lastColumn))
        }
    }

    private fun convertCell(cell: HSSFCell, formatter: CellFormatter, date1904: Boolean): SheetCell? {
        val style = cell.cellStyle
        fun number(value: Double) =
            formatter.formatNumber(value, style.dataFormat.toInt(), style.dataFormatString, date1904) to CellKind.NUMBER
        fun error(code: Byte) = (runCatching { FormulaError.forInt(code).string }.getOrNull() ?: "#ERR") to CellKind.ERROR

        val (text, kind) = when (cell.cellType) {
            CellType.NUMERIC -> number(cell.numericCellValue)
            CellType.STRING -> cell.richStringCellValue.string.orEmpty() to CellKind.TEXT
            CellType.BOOLEAN -> formatter.formatBoolean(cell.booleanCellValue) to CellKind.BOOLEAN
            CellType.ERROR -> error(cell.errorCellValue)
            // Формулы не пересчитываются: показывается результат, сохранённый Excel.
            CellType.FORMULA -> when (cell.cachedFormulaResultType) {
                CellType.NUMERIC -> number(cell.numericCellValue)
                CellType.STRING -> cell.richStringCellValue.string.orEmpty() to CellKind.TEXT
                CellType.BOOLEAN -> formatter.formatBoolean(cell.booleanCellValue) to CellKind.BOOLEAN
                CellType.ERROR -> error(cell.errorCellValue)
                else -> "" to CellKind.TEXT
            }
            else -> "" to CellKind.TEXT
        }
        val styleIndex = style.index.toInt().takeIf { it in styles.indices } ?: 0
        if (text.isEmpty() && styles[styleIndex].fillColor == null) return null
        return SheetCell(cell.columnIndex, text, kind, styleIndex)
    }

    private fun convertStyle(style: HSSFCellStyle): CellStyle {
        val font = style.getFont(workbook)
        return CellStyle(
            bold = font.bold,
            italic = font.italic,
            underline = font.underline != Font.U_NONE,
            strikethrough = font.strikeout,
            textColor = paletteColor(font.color.toInt()),
            fillColor = if (style.fillPattern == FillPatternType.NO_FILL) null else paletteColor(style.fillForegroundColor.toInt()),
            alignment = when (style.alignment) {
                PoiAlignment.LEFT, PoiAlignment.JUSTIFY, PoiAlignment.FILL, PoiAlignment.DISTRIBUTED -> HorizontalAlignment.LEFT
                PoiAlignment.CENTER, PoiAlignment.CENTER_SELECTION -> HorizontalAlignment.CENTER
                PoiAlignment.RIGHT -> HorizontalAlignment.RIGHT
                else -> HorizontalAlignment.GENERAL
            },
        )
    }

    /** Цвет из палитры книги; индексы 0–7 дублируют 8–15, 64 и выше — «авто» (цвет темы). */
    private fun paletteColor(index: Int): Int? {
        val normalized = if (index in 0 until PaletteRecord.FIRST_COLOR_INDEX) index + PaletteRecord.FIRST_COLOR_INDEX else index
        val rgb = runCatching { palette.getColor(normalized) }.getOrNull() ?: return null
        if (rgb.size < 3) return null
        return (0xFF shl 24) or ((rgb[0].toInt() and 0xFF) shl 16) or ((rgb[1].toInt() and 0xFF) shl 8) or (rgb[2].toInt() and 0xFF)
    }

    override fun close() = workbook.close()

    private companion object {
        /** Ширина колонки в HSSF — в 1/256 ширины символа. */
        const val WIDTH_UNITS_PER_CHAR = 256f
        const val BASE_WIDTH_PADDING = 0.71f
    }
}

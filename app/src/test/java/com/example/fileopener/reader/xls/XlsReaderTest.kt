package com.example.fileopener.reader.xls

import com.example.fileopener.reader.sheet.CellKind
import com.example.fileopener.reader.sheet.CellRange
import com.example.fileopener.reader.sheet.HorizontalAlignment
import com.example.fileopener.reader.sheet.RecordingSink
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.util.CellRangeAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Locale
import org.apache.poi.ss.usermodel.HorizontalAlignment as PoiAlignment

class XlsReaderTest {

    private fun buildWorkbook(): ByteArray {
        val workbook = HSSFWorkbook()
        val sheet = workbook.createSheet("Данные")
        workbook.createSheet("Скрытый")
        workbook.setSheetHidden(1, true)
        workbook.createSheet("Пустой")

        val header = workbook.createCellStyle().apply {
            setFont(workbook.createFont().apply {
                bold = true
                color = IndexedColors.RED.index
            })
            fillForegroundColor = IndexedColors.YELLOW.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = PoiAlignment.CENTER
        }
        val money = workbook.createCellStyle().apply { dataFormat = workbook.createDataFormat().getFormat("#,##0.00") }
        val date = workbook.createCellStyle().apply { dataFormat = 14 }

        sheet.createRow(0).apply {
            createCell(0).apply { setCellValue("Имя"); cellStyle = header }
            createCell(1).apply { setCellValue("Сумма"); cellStyle = header }
            createCell(2).apply { cellStyle = header }
        }
        sheet.createRow(1).apply {
            createCell(0).setCellValue("Иван")
            createCell(1).apply { setCellValue(1234.5); cellStyle = money }
            createCell(2).apply { setCellValue(45292.0); cellStyle = date }
            val formula = createCell(3).apply { cellFormula = "B2*2"; cellStyle = money }
            workbook.creationHelper.createFormulaEvaluator().evaluateFormulaCell(formula)
            createCell(4).setCellValue(true)
        }
        sheet.createRow(3).createCell(0).setCellValue("Объединено")
        sheet.addMergedRegion(CellRangeAddress(3, 3, 0, 2))
        sheet.setColumnWidth(0, 20 * 256)
        sheet.setColumnHidden(4, true)

        return ByteArrayOutputStream().also { workbook.write(it) }.toByteArray()
    }

    private val source = XlsReader.open(buildWorkbook().inputStream(), Locale.US)

    @Test
    fun visibleSheetsOnly() {
        assertEquals(listOf("Данные", "Пустой"), source.sheetNames)
        assertTrue(RecordingSink().also { source.readSheet(1, it) }.rows.isEmpty())
    }

    @Test
    fun values_formatsAndFormulaResults() {
        val sink = RecordingSink().also { source.readSheet(0, it) }
        assertEquals("Имя", sink.text(0, 0))
        assertEquals("1,234.50", sink.text(1, 1))
        assertEquals("1/1/2024", sink.text(1, 2))
        assertEquals("2,469.00", sink.text(1, 3))
        assertEquals(CellKind.NUMBER, sink.cell(1, 3)?.kind)
        assertEquals("TRUE", sink.text(1, 4))
        assertNull(sink.rows[2])
        assertEquals("пустая ячейка с заливкой сохраняется", "", sink.text(0, 2))
    }

    @Test
    fun columnsAndMerges() {
        val sink = RecordingSink().also { source.readSheet(0, it) }
        assertEquals(Triple(0, 0, 20f), sink.widths.first())
        assertEquals(0f, sink.widths.first { it.first == 4 }.third)
        assertEquals(listOf(CellRange(3, 3, 0, 2)), sink.merges)
    }

    @Test
    fun styles_fromWorkbookPalette() {
        val sink = RecordingSink().also { source.readSheet(0, it) }
        val style = source.styles[sink.cell(0, 0)!!.style]
        assertTrue(style.bold)
        assertEquals(0xFFFF0000.toInt(), style.textColor)
        assertEquals(0xFFFFFF00.toInt(), style.fillColor)
        assertEquals(HorizontalAlignment.CENTER, style.alignment)
        assertNull(source.styles[sink.cell(1, 0)!!.style].fillColor)
    }
}

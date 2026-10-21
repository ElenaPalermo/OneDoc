package com.example.fileopener.reader.xlsx

import com.example.fileopener.reader.docx.DocxReaderTest
import com.example.fileopener.reader.sheet.CellKind
import com.example.fileopener.reader.sheet.CellRange
import com.example.fileopener.reader.sheet.HorizontalAlignment
import com.example.fileopener.reader.sheet.RecordingSink
import com.example.fileopener.reader.sheet.SpreadsheetSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxReaderTest {
    private lateinit var file: File
    private lateinit var source: SpreadsheetSource

    @Before
    fun setUp() {
        file = File.createTempFile("book", ".xlsx")
        writeZip(file, BOOK)
        source = XlsxReader(DocxReaderTest.testParsers, Locale.US).open(file)
    }

    @After
    fun tearDown() {
        source.close()
        file.delete()
    }

    private fun readSheet(index: Int) = RecordingSink().also { source.readSheet(index, it) }

    @Test
    fun hiddenSheetsAreSkipped_absoluteTargetsResolved() {
        assertEquals(listOf("Продажи", "Итоги"), source.sheetNames)
        val totals = readSheet(1)
        // Ячейки без ссылок r нумеруются по порядку.
        assertEquals("7", totals.text(0, 0))
        assertEquals("8", totals.text(0, 1))
    }

    @Test
    fun cellValues_areFormattedByType() {
        val sheet = readSheet(0)
        assertEquals("Товар", sheet.text(0, 0))
        assertEquals("rich text склеивается, фонетика отбрасывается", "Сумма", sheet.text(0, 1))
        assertEquals("Яблоки", sheet.text(1, 0))
        assertEquals("1,234.50", sheet.text(1, 1))
        assertEquals(CellKind.NUMBER, sheet.cell(1, 1)?.kind)
        assertEquals("01.01.2024", sheet.text(1, 3))
        assertEquals("12.50%", sheet.text(1, 4))
        assertEquals("Итого", sheet.text(3, 0))
        assertEquals("формула — сохранённый результат", "1,234.50", sheet.text(3, 1))
        assertEquals("TRUE", sheet.text(3, 2))
        assertEquals(CellKind.BOOLEAN, sheet.cell(3, 2)?.kind)
        assertEquals("#DIV/0!", sheet.text(3, 3))
        assertEquals(CellKind.ERROR, sheet.cell(3, 3)?.kind)
        assertEquals("текст", sheet.text(3, 4))
        assertNull("пропущенная строка", sheet.rows[2])
    }

    @Test
    fun emptyCellWithFill_isKept() {
        val cell = readSheet(0).cell(0, 2)
        assertNotNull(cell)
        assertEquals("", cell!!.text)
        assertEquals(1, cell.style)
    }

    @Test
    fun columnsAndMerges() {
        val sheet = readSheet(0)
        assertEquals(8.43f, sheet.defaultWidth)
        assertEquals(listOf(Triple(0, 0, 20f), Triple(2, 2, 0f)), sheet.widths)
        assertEquals(listOf(CellRange(5, 6, 0, 2)), sheet.merges)
    }

    @Test
    fun styles_fontsFillsThemeColorsAndAlignment() {
        val header = source.styles[1]
        assertTrue(header.bold)
        assertEquals(0xFFFF0000.toInt(), header.textColor)
        assertEquals(HorizontalAlignment.CENTER, header.alignment)
        // accent1 (#4472C4) с tint 0.5 — светлее исходного по всем каналам.
        val fill = header.fillColor!!
        assertTrue((fill shr 16 and 0xFF) > 0x44 && (fill shr 8 and 0xFF) > 0x72 && (fill and 0xFF) > 0xC4)
        // Цвет шрифта по умолчанию — theme 1 (dk1), то есть чёрный.
        assertEquals(0xFF000000.toInt(), source.styles[0].textColor)
        assertNull(source.styles[0].fillColor)
    }

    @Test
    fun applyTint_darkensAndLightens() {
        // Белый, затемнённый наполовину, — средне-серый.
        val gray = applyTint(0xFFFFFFFF.toInt(), -0.5)
        assertTrue(listOf(gray shr 16 and 0xFF, gray shr 8 and 0xFF, gray and 0xFF).all { it in 126..129 })
        assertEquals(0xFFFFFFFF.toInt(), applyTint(0xFF000000.toInt(), 1.0))
    }

    @Test
    fun resolvePartPath_handlesRelativeAndAbsolute() {
        assertEquals("xl/worksheets/sheet1.xml", resolvePartPath("xl/", "worksheets/sheet1.xml"))
        assertEquals("xl/media/a.png", resolvePartPath("xl/worksheets/", "../media/a.png"))
        assertEquals("xl/worksheets/sheet3.xml", resolvePartPath("xl/", "/xl/worksheets/sheet3.xml"))
    }

    companion object {
        private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val PKG = "http://schemas.openxmlformats.org/package/2006/relationships"

        fun writeZip(file: File, entries: Map<String, String>) {
            ZipOutputStream(file.outputStream()).use { zip ->
                for ((name, content) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
        }

        val BOOK = mapOf(
            "_rels/.rels" to """<Relationships xmlns="$PKG">
                <Relationship Id="rId1" Type="$REL/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<workbook xmlns="$MAIN" xmlns:r="$REL"><workbookPr/><sheets>
                <sheet name="Продажи" sheetId="1" r:id="rId1"/>
                <sheet name="Скрытый" sheetId="2" state="hidden" r:id="rId2"/>
                <sheet name="Итоги" sheetId="3" r:id="rId3"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="$PKG">
                <Relationship Id="rId1" Type="$REL/worksheet" Target="worksheets/sheet1.xml"/>
                <Relationship Id="rId2" Type="$REL/worksheet" Target="worksheets/sheet2.xml"/>
                <Relationship Id="rId3" Type="$REL/worksheet" Target="/xl/worksheets/sheet3.xml"/>
                <Relationship Id="rId4" Type="$REL/styles" Target="styles.xml"/>
                <Relationship Id="rId5" Type="$REL/sharedStrings" Target="sharedStrings.xml"/>
                <Relationship Id="rId6" Type="$REL/theme" Target="theme/theme1.xml"/></Relationships>""",
            "xl/theme/theme1.xml" to """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office">
                <a:themeElements><a:clrScheme name="Office">
                <a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>
                <a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
                <a:dk2><a:srgbClr val="44546A"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>
                <a:accent1><a:srgbClr val="4472C4"/></a:accent1></a:clrScheme></a:themeElements></a:theme>""",
            "xl/styles.xml" to """<styleSheet xmlns="$MAIN">
                <numFmts count="1"><numFmt numFmtId="164" formatCode="dd.mm.yyyy"/></numFmts>
                <fonts count="2"><font><sz val="11"/><color theme="1"/></font><font><b/><color rgb="FFFF0000"/></font></fonts>
                <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
                <fill><patternFill patternType="solid"><fgColor theme="4" tint="0.5"/></patternFill></fill></fills>
                <cellStyleXfs count="1"><xf numFmtId="0" fontId="1" fillId="2"/></cellStyleXfs>
                <cellXfs count="5"><xf numFmtId="0" fontId="0" fillId="0"/>
                <xf numFmtId="0" fontId="1" fillId="2"><alignment horizontal="center"/></xf>
                <xf numFmtId="4" fontId="0" fillId="0"/><xf numFmtId="164" fontId="0" fillId="0"/>
                <xf numFmtId="10" fontId="0" fillId="0"/></cellXfs></styleSheet>""",
            "xl/sharedStrings.xml" to """<sst xmlns="$MAIN" count="3" uniqueCount="3"><si><t>Товар</t></si>
                <si><r><t>Сум</t></r><r><rPr><b/></rPr><t>ма</t></r><rPh><t>x</t></rPh></si><si><t>Яблоки</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="$MAIN"><sheetFormatPr defaultRowHeight="15"/>
                <cols><col min="1" max="1" width="20" customWidth="1"/><col min="3" max="3" hidden="1" width="5"/></cols>
                <sheetData>
                <row r="1"><c r="A1" s="1" t="s"><v>0</v></c><c r="B1" s="1" t="s"><v>1</v></c><c r="C1" s="1"/></row>
                <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2" s="2"><v>1234.5</v></c><c r="D2" s="3"><v>45292</v></c>
                  <c r="E2" s="4"><v>0.125</v></c></row>
                <row r="4"><c r="A4" t="inlineStr"><is><t>Итого</t></is></c><c r="B4" s="2"><f>SUM(B2:B3)</f><v>1234.5</v></c>
                  <c r="C4" t="b"><v>1</v></c><c r="D4" t="e"><v>#DIV/0!</v></c><c r="E4" t="str"><f>A4</f><v>текст</v></c></row>
                </sheetData><mergeCells count="1"><mergeCell ref="A6:C7"/></mergeCells></worksheet>""",
            "xl/worksheets/sheet2.xml" to """<worksheet xmlns="$MAIN"><sheetData/></worksheet>""",
            "xl/worksheets/sheet3.xml" to """<worksheet xmlns="$MAIN"><sheetData><row><c><v>7</v></c><c><v>8</v></c></row></sheetData></worksheet>""",
        )
    }
}

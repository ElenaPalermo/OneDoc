package io.doccraftstudio.onedoc.reader.csv

import io.doccraftstudio.onedoc.reader.sheet.CellKind
import io.doccraftstudio.onedoc.reader.sheet.RecordingSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset

class CsvReaderTest {

    private fun read(bytes: ByteArray) = RecordingSink().also { CsvReader.read(bytes.inputStream(), it) }

    private fun read(text: String) = read(text.toByteArray())

    @Test
    fun estimateRowCount_scalesLinesInSampleToFileSize() {
        val sample = "a;b\n".repeat(25).toByteArray() // 100 байт, 25 строк
        assertEquals(250_000, CsvReader.estimateRowCount(sample, sample.size, sizeBytes = 1_000_000))
        // Файл целиком поместился в образец — строки посчитает сам разбор.
        assertEquals(null, CsvReader.estimateRowCount(sample, sample.size, sizeBytes = sample.size.toLong()))
    }

    @Test
    fun detectDelimiter_semicolonWithQuotedDelimiters() {
        assertEquals(';', CsvReader.detectDelimiter("a;b;c\n1;2;3\n\"x;y\";5;6\n"))
    }

    @Test
    fun detectDelimiter_commaWhenSemicolonsOnlyInsideQuotes() {
        val sample = "name,comment\n\"Иван\",\"привет; как дела\"\n\"Пётр\",\"ок\"\n"
        assertEquals(',', CsvReader.detectDelimiter(sample))
    }

    @Test
    fun detectDelimiter_tabAndDefault() {
        assertEquals('\t', CsvReader.detectDelimiter("a\tb\n1\t2\n"))
        assertEquals(',', CsvReader.detectDelimiter("одна колонка\nбез разделителей\n"))
    }

    @Test
    fun quotedFields_withEscapedQuotesAndLineBreaks() {
        val sink = read("id;text;price\r\n1;\"Он сказал \"\"привет\"\"\";10,5\r\n2;\"две\nстроки\";\r\n")
        assertEquals("Он сказал \"привет\"", sink.text(1, 1))
        assertEquals("две\nстроки", sink.text(2, 1))
        assertEquals(CellKind.NUMBER, sink.cell(1, 2)?.kind)
        assertEquals(CellKind.TEXT, sink.cell(1, 1)?.kind)
        // Пустое поле не хранится, но колонки после него не сдвигаются.
        assertNull(sink.cell(2, 2))
        assertEquals(setOf(0, 1, 2), sink.rows.keys)
    }

    @Test
    fun emptyLines_keepRowNumbers_lastLineWithoutNewline() {
        val sink = read("a,b\n\n1,2")
        assertEquals("1", sink.text(2, 0))
        assertEquals("2", sink.text(2, 1))
    }

    @Test
    fun windows1251_isDecoded() {
        val sink = read("Город;Население\nМосква;13000000\n".toByteArray(Charset.forName("windows-1251")))
        assertEquals("Население", sink.text(0, 1))
        assertEquals("Москва", sink.text(1, 0))
    }

    @Test
    fun utf8Bom_isSkipped() {
        val sink = read(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Имя,Возраст\n".toByteArray())
        assertEquals("Имя", sink.text(0, 0))
    }

    @Test
    fun quotedNumber_staysText() {
        val sink = read("\"007\",42,\"1 234,5\"\n")
        assertEquals(CellKind.TEXT, sink.cell(0, 0)?.kind)
        assertEquals(CellKind.NUMBER, sink.cell(0, 1)?.kind)
        assertEquals("1 234,5", sink.text(0, 2))
    }
}

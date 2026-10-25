package io.doccraftstudio.onedoc.reader.txt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TextReaderTest {
    private val cp1251 = Charset.forName("windows-1251")

    @Test
    fun utf8WithoutBom_isDetected() {
        val document = TextReader.read("Привет\nмир".toByteArray(Charsets.UTF_8).inputStream())
        assertEquals(Charsets.UTF_8, document.charset)
        assertEquals(listOf("Привет", "мир"), document.paragraphs)
    }

    @Test
    fun utf8Bom_isSkipped() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Текст".toByteArray()
        assertEquals(listOf("Текст"), TextReader.read(bytes.inputStream()).paragraphs)
    }

    @Test
    fun utf16LeBom_isDecoded() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Строка 1\r\nСтрока 2".toByteArray(Charsets.UTF_16LE)
        assertEquals(listOf("Строка 1", "Строка 2"), TextReader.read(bytes.inputStream()).paragraphs)
    }

    @Test
    fun invalidUtf8_fallsBackToWindows1251() {
        val document = TextReader.read("Отчёт за квартал".toByteArray(cp1251).inputStream())
        assertEquals(cp1251, document.charset)
        assertEquals(listOf("Отчёт за квартал"), document.paragraphs)
    }

    @Test
    fun longLine_isSplitIntoChunks() {
        val line = "a".repeat(TextReader.MAX_PARAGRAPH_LENGTH * 2 + 10)
        val paragraphs = TextReader.read(line.byteInputStream()).paragraphs
        assertEquals(listOf(TextReader.MAX_PARAGRAPH_LENGTH, TextReader.MAX_PARAGRAPH_LENGTH, 10), paragraphs.map { it.length })
    }

    @Test
    fun longLine_doesNotSplitSurrogatePair() {
        val line = "a".repeat(TextReader.MAX_PARAGRAPH_LENGTH - 1) + "😀" + "b"
        val paragraphs = TextReader.read(line.byteInputStream()).paragraphs
        assertEquals(line, paragraphs.joinToString(""))
        assertTrue(paragraphs.none { Character.isHighSurrogate(it.last()) })
    }

    @Test
    fun utf8Sequence_cutAtSampleBoundary_isStillValid() {
        val bytes = "ж".toByteArray(Charsets.UTF_8)
        assertTrue(CharsetDetector.isValidUtf8(bytes, length = 1))
        assertFalse(CharsetDetector.isValidUtf8(byteArrayOf(0xD0.toByte(), 0x41), length = 2))
    }
}

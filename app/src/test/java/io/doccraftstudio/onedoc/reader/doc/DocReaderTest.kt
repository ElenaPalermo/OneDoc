package io.doccraftstudio.onedoc.reader.doc

import io.doccraftstudio.onedoc.reader.docx.DocxReaderTest
import io.doccraftstudio.onedoc.reader.flow.ParagraphBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocReaderTest {
    @Test
    fun sampleDoc_isConvertedToParagraphsWithNormalizedSizes() {
        val document = DocxReaderTest.resource("word/SampleDoc.doc").inputStream().use(DocReader::read)
        document.use {
            val paragraphs = it.blocks.filterIsInstance<ParagraphBlock>()
            val text = paragraphs.joinToString("\n") { p -> p.plainText }
            assertTrue(text, text.contains("I am a test document"))
            assertTrue(text, text.contains("This is page two"))
            // Служебные символы Word (\r, \u0007, коды полей) в текст не попадают.
            assertTrue(text.none { c -> c < ' ' && c != '\n' && c != '\t' })
            // Основной размер текста — масштаб 1.
            val body = paragraphs.first { p -> p.plainText.contains("I am a test document") }
            assertEquals(1f, body.runs.first().sizeScale, 0.001f)
            assertEquals(null, body.runs.first().color)
            // COLORREF 0x00D48D54 (BGR) → синий #548DD4.
            val blue = paragraphs.first { p -> p.plainText.contains("also in blue") }
            assertEquals(0xFF548DD4.toInt(), blue.runs.first().color)
            assertTrue(blue.runs.first().sizeScale > 1.2f)
        }
    }
}

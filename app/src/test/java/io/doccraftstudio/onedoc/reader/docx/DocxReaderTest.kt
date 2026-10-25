package io.doccraftstudio.onedoc.reader.docx

import io.doccraftstudio.onedoc.reader.TocEntry
import io.doccraftstudio.onedoc.reader.flow.FlowDocument
import io.doccraftstudio.onedoc.reader.flow.ImageBlock
import io.doccraftstudio.onedoc.reader.flow.PageBreakBlock
import io.doccraftstudio.onedoc.reader.flow.ParagraphBlock
import io.doccraftstudio.onedoc.reader.flow.TableRowBlock
import io.doccraftstudio.onedoc.reader.xml.XmlParserFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.File

class DocxReaderTest {
    private lateinit var document: FlowDocument

    @Before
    fun setUp() {
        document = DocxReader(testParsers).read(resource("word/rich.docx"))
    }

    @After
    fun tearDown() = document.close()

    private fun paragraph(index: Int) = document.blocks[index] as ParagraphBlock

    @Test
    fun headings_comeFromStylesWithSizesRelativeToDefault() {
        assertEquals(1, paragraph(0).headingLevel)
        assertEquals("Отчёт о работе", paragraph(0).plainText)
        // Title: 28 пт при основном 11 пт → ограничено сверху 2.5.
        assertEquals(2.5f, paragraph(0).runs.single().sizeScale, 0.001f)

        val heading = paragraph(1)
        assertEquals(1, heading.headingLevel)
        assertTrue(heading.runs.single().bold)
        assertEquals(32f / 22f, heading.runs.single().sizeScale, 0.001f)

        // Heading2 основан на Heading1: жирность наследуется, размер свой.
        val subheading = document.blocks.filterIsInstance<ParagraphBlock>().single { it.plainText == "Заключение" }
        assertEquals(2, subheading.headingLevel)
        assertTrue(subheading.runs.single().bold)
        assertEquals(26f / 22f, subheading.runs.single().sizeScale, 0.001f)
    }

    @Test
    fun runs_keepFormattingAndColor() {
        val runs = paragraph(2).runs
        assertEquals(listOf("Обычный текст, ", "жирный", " и ", "курсив красным", "."), runs.map { it.text })
        assertTrue(runs[1].bold)
        assertTrue(runs[3].italic)
        assertEquals(0xFFC00000.toInt(), runs[3].color)
        assertFalse(runs[0].bold)
    }

    @Test
    fun fieldCodesAndDeletedTextAreHidden() {
        assertEquals("Страница 7", paragraph(3).plainText)
    }

    @Test
    fun lists_areNumberedPerLevelLikeWord() {
        val items = (4..8).map { paragraph(it) }
        assertEquals(listOf("1.", "a)", "b)", "2.", "•"), items.map { it.listMarker })
        assertEquals(listOf(1, 2, 2, 1, 1), items.map { it.indentLevel })
    }

    @Test
    fun hyperlink_keepsExternalUrl() {
        assertEquals("https://example.com/", paragraph(9).runs.single().link)
    }

    @Test
    fun table_isSplitIntoRowsWithSharedLayout() {
        val rows = document.blocks.filterIsInstance<TableRowBlock>()
        assertEquals(3, rows.size)
        assertTrue(rows.first().isFirst && rows.last().isLast)
        assertEquals(3, rows.first().table.columnCount)
        assertEquals(listOf("Итог за два месяца", "2 550 000"), rows.last().cells.map { it.plainText })
        assertEquals(2, rows.last().cells.first().columnSpan)
    }

    @Test
    fun image_isFoundOnceWithAspectAndLoadsFromArchive() {
        val images = document.blocks.filterIsInstance<ImageBlock>()
        // Картинка из mc:Fallback — дубль, её быть не должно.
        assertEquals(1, images.size)
        assertEquals("word/media/image1.png", images.single().key)
        assertEquals(0.5f, images.single().aspectRatio!!, 0.001f)
        val bytes = document.resources?.loadImage(images.single().key)
        assertNotNull(bytes)
        assertEquals(0x89.toByte(), bytes!![0])
    }

    @Test
    fun pageBreak_isKept() {
        assertTrue(document.blocks.contains(PageBreakBlock))
    }

    @Test
    fun tableOfContents_isBuiltFromHeadings() {
        val toc = document.tableOfContents
        assertEquals(listOf("Отчёт о работе", "1. Введение", "2. Таблица", "Заключение"), toc.map { it.title })
        assertEquals(1, toc.last().level)
        assertEquals(paragraph(1), document.blocks[toc[1].targetIndex])
        assertEquals(TocEntry("Отчёт о работе", 0, 0), toc.first())
    }

    @Test
    fun realWordDocument_isReadWithoutStylesOrNumbering() {
        DocxReader(testParsers).read(resource("word/SampleDoc.docx")).use { sample ->
            val text = sample.blocks.joinToString("\n") { it.plainText }
            assertTrue(text.contains("I am a test document"))
            assertTrue(text.contains("This is page two"))
        }
    }

    companion object {
        val testParsers = XmlParserFactory {
            KXmlParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
        }

        fun resource(path: String): File =
            File(checkNotNull(DocxReaderTest::class.java.classLoader?.getResource(path)) { "Нет ресурса $path" }.toURI())
    }
}

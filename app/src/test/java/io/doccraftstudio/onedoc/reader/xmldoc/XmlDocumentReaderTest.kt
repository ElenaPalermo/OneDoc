package io.doccraftstudio.onedoc.reader.xmldoc

import io.doccraftstudio.onedoc.reader.docx.DocxReaderTest.Companion.testParsers
import io.doccraftstudio.onedoc.reader.flow.DocumentNotice
import io.doccraftstudio.onedoc.reader.flow.FlowDocument
import io.doccraftstudio.onedoc.reader.flow.ParagraphBlock
import io.doccraftstudio.onedoc.reader.flow.TextLineBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class XmlDocumentReaderTest {
    private val cp1251 = Charset.forName("windows-1251")

    private fun read(bytes: ByteArray): FlowDocument = XmlDocumentReader.read({ bytes.inputStream() }, testParsers)

    private fun read(text: String): FlowDocument = read(text.toByteArray(Charsets.UTF_8))

    /** Строки документа с отступом: два пробела на уровень. */
    private fun FlowDocument.lines(): List<String> = blocks.map { block ->
        val indent = (block as? ParagraphBlock)?.indentLevel ?: 0
        "  ".repeat(indent) + block.plainText
    }

    @Test
    fun nestedElements_areIndentedByDepth() {
        val document = read("""<?xml version="1.0"?><catalog><book id="1" lang="ru"><title>Война и мир</title><empty/></book></catalog>""")
        assertEquals(
            listOf(
                """<?xml version="1.0"?>""",
                "<catalog>",
                """  <book id="1" lang="ru">""",
                "    <title>Война и мир</title>",
                "    <empty/>",
                "  </book>",
                "</catalog>",
            ),
            document.lines(),
        )
        assertNull(document.notice)
    }

    @Test
    fun multilineTextAndComments_areSplitIntoTrimmedLines() {
        val document = read("<root>\n  <!-- первая\n  вторая -->\n  <p>строка 1\n     строка 2</p>\n</root>")
        assertEquals(
            listOf("<root>", "  <!-- первая", "  вторая -->", "  <p>", "    строка 1", "    строка 2", "  </p>", "</root>"),
            document.lines(),
        )
        val comment = (document.blocks[1] as ParagraphBlock).runs.single()
        assertTrue(comment.italic)
    }

    @Test
    fun entitiesAndCdata_areShownAsInSource() {
        val document = read("""<a title="x &amp; &quot;y&quot;">1 &lt; 2 &amp; 3&#160;<![CDATA[<raw>]]></a>""")
        assertEquals(
            listOf(
                """<a title="x &amp; &quot;y&quot;">""",
                "  1 &lt; 2 &amp; 3&#160;",
                "  <![CDATA[<raw>]]>",
                "</a>",
            ),
            document.lines(),
        )
    }

    @Test
    fun namespacePrefixes_areKept() {
        val document = read("""<w:document xmlns:w="urn:w"><w:p w:val="1"/></w:document>""")
        assertEquals(
            listOf("""<w:document xmlns:w="urn:w">""", """  <w:p w:val="1"/>""", "</w:document>"),
            document.lines(),
        )
    }

    @Test
    fun tagsAttributesAndValues_areHighlighted() {
        val runs = (read("""<a b="c"/>""").blocks.single() as ParagraphBlock).runs
        assertEquals(listOf("<a", " b", "=", "\"c\"", "/>"), runs.map { it.text })
        assertEquals(3, runs.mapNotNull { it.color }.distinct().size)
    }

    @Test
    fun emptyOrBlankFile_hasNoBlocks() {
        assertTrue(read(ByteArray(0)).blocks.isEmpty())
        assertTrue(read(" \n\t\n").blocks.isEmpty())
        // BOM без содержимого — тоже пустой файл.
        assertTrue(read(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())).blocks.isEmpty())
    }

    @Test
    fun malformedXml_fallsBackToSourceTextWithLine() {
        val source = "<root>\n  <item>1</item>\n  <item>2</wrong>\n</root>"
        val document = read(source)
        assertEquals(source.lines(), document.blocks.map { it.plainText })
        assertTrue(document.blocks.all { it is TextLineBlock })
        assertEquals(DocumentNotice.MalformedXml(3), document.notice)
    }

    @Test
    fun truncatedXml_fallsBackToSourceText() {
        val document = read("<root><item>")
        assertEquals(listOf("<root><item>"), document.blocks.map { it.plainText })
        assertTrue(document.notice is DocumentNotice.MalformedXml)
    }

    @Test
    fun notXmlAtAll_fallsBackToSourceText() {
        val document = read("просто текст, а не XML")
        assertEquals(listOf("просто текст, а не XML"), document.blocks.map { it.plainText })
        assertTrue(document.notice is DocumentNotice.MalformedXml)
    }

    @Test
    fun dtdEntities_areNotExpanded() {
        val billionLaughs = """
            <?xml version="1.0"?>
            <!DOCTYPE lolz [
              <!ENTITY lol "lol">
              <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
            ]>
            <lolz>&lol2;</lolz>
        """.trimIndent()
        val document = read(billionLaughs)
        assertEquals(DocumentNotice.XmlEntitiesNotExpanded, document.notice)
        assertEquals(billionLaughs.lines(), document.blocks.map { it.plainText })
    }

    @Test
    fun doctypeWithoutEntities_isFormatted() {
        val document = read("""<!DOCTYPE note SYSTEM "note.dtd"><note>текст</note>""")
        assertNull(document.notice)
        assertEquals(listOf("""<!DOCTYPE note SYSTEM "note.dtd">""", "<note>текст</note>"), document.lines())
    }

    @Test
    fun declaredWindows1251_isDecoded() {
        val bytes = """<?xml version="1.0" encoding="windows-1251"?><a>Отчёт</a>""".toByteArray(cp1251)
        assertEquals("<a>Отчёт</a>", read(bytes).lines().last())
    }

    @Test
    fun undeclaredWindows1251_isDetected() {
        val bytes = "<a>Привет, мир</a>".toByteArray(cp1251)
        assertEquals(listOf("<a>Привет, мир</a>"), read(bytes).lines())
    }

    @Test
    fun wronglyDeclaredUtf8_fallsBackToWindows1251() {
        val bytes = """<?xml version="1.0" encoding="UTF-8"?><a>Счёт</a>""".toByteArray(cp1251)
        assertEquals("<a>Счёт</a>", read(bytes).lines().last())
    }

    @Test
    fun utf16WithBom_isDecoded() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<a>Текст</a>".toByteArray(Charsets.UTF_16LE)
        assertEquals(listOf("<a>Текст</a>"), read(bytes).lines())
    }

    @Test
    fun utf16WithoutBom_isDetectedFromDeclaration() {
        val bytes = """<?xml version="1.0" encoding="UTF-16"?><a>Да</a>""".toByteArray(Charsets.UTF_16BE)
        assertEquals("<a>Да</a>", read(bytes).lines().last())
    }

    @Test
    fun unknownDeclaredEncoding_isIgnored() {
        val document = read("""<?xml version="1.0" encoding="x-unknown-42"?><a>ok</a>""")
        assertEquals("<a>ok</a>", document.lines().last())
    }

    @Test
    fun invalidBytes_areReplacedInsteadOfFailing() {
        // Начало файла — корректный UTF-8, а за пределами выборки для определения кодировки — битый байт.
        val bytes = "<a>".toByteArray() + "я".repeat(40_000).toByteArray() + byteArrayOf(0xFF.toByte()) + "</a>".toByteArray()
        val document = read(bytes)
        assertNull(document.notice)
        assertTrue(document.blocks.any { '�' in it.plainText })
    }

    @Test
    fun deepNesting_capsIndent() {
        val depth = 40
        val xml = (0 until depth).joinToString("") { "<e$it>" } + (depth - 1 downTo 0).joinToString("") { "</e$it>" }
        val levels = read(xml).blocks.map { (it as ParagraphBlock).indentLevel }
        // Самый вложенный элемент пустой и занимает одну строку.
        assertEquals(depth * 2 - 1, levels.size)
        assertTrue(levels.max() <= 12)
    }

    @Test
    fun declaresEntities_handlesIncompleteSample() {
        // Подмножество DTD обрезано границей выборки — проверить нельзя, считаем опасным.
        assertTrue(XmlDocumentReader.declaresEntities("<!DOCTYPE a [ <!ELEMENT a ANY>", wholeFileSampled = false))
        // Корня в выборке нет — DOCTYPE может быть дальше.
        assertTrue(XmlDocumentReader.declaresEntities("<!-- длинный комментарий", wholeFileSampled = false))
        assertTrue(!XmlDocumentReader.declaresEntities("<a><!-- <!ENTITY в тексте --></a>", wholeFileSampled = false))
    }
}

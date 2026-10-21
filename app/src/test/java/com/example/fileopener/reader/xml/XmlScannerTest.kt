package com.example.fileopener.reader.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class XmlScannerTest {

    /** Отдаёт данные по одному байту: каждый тег и текст рвутся на границах чтения. */
    private class TrickleInput(bytes: ByteArray) : InputStream() {
        private val source = ByteArrayInputStream(bytes)
        override fun read(): Int = source.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            if (length == 0) 0 else source.read(buffer, offset, 1)
    }

    private fun scanners(xml: String, charset: java.nio.charset.Charset = Charsets.UTF_8): List<XmlScanner> {
        val bytes = xml.toByteArray(charset)
        return listOf(XmlScanner.open(ByteArrayInputStream(bytes)), XmlScanner.open(TrickleInput(bytes)))
    }

    /** Простая запись дерева: `имя[атрибут=значение]{текст}` — для сравнения разборов. */
    private fun dump(scanner: XmlScanner, attributes: List<String>): String {
        val out = StringBuilder()
        assertTrue(scanner.moveToRoot())
        fun element(name: String) {
            out.append(name)
            for (attribute in attributes) scanner.attr(attribute.toByteArray())?.let { out.append("[$attribute=$it]") }
        }
        fun walk() {
            scanner.forEachChild {
                val name = NAMES.first { scanner.isName(it.toByteArray()) }
                if (name in TEXT_ELEMENTS) {
                    element(name)
                    out.append("{").append(scanner.text()).append("}")
                } else {
                    element(name)
                    out.append("(")
                    walk()
                    out.append(")")
                }
            }
        }
        element(NAMES.first { scanner.isName(it.toByteArray()) })
        out.append("(")
        walk()
        out.append(")")
        return out.toString()
    }

    @Test
    fun elementsAttributesAndText() {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <!-- комментарий с <тегом> -->
            <x:root xmlns:x="urn:main" xmlns:r="urn:rel" r:id='rId1'>
              <x:row r="1" a="x>y" b='он сказал "да"'>
                <x:v>Привет &amp; &lt;мир&gt; &#1046;&#x416; &unknown; &amp</x:v>
                <x:v><![CDATA[<не тег> &amp;]]></x:v>
                <x:v/>
                <x:v>до<x:skip>внутри</x:skip>после</x:v>
              </x:row>
              <row r = "2" />
            </x:root>"""
        for (scanner in scanners(xml)) {
            assertEquals(
                "root(row[r=1][a=x>y][b=он сказал \"да\"](" +
                    "v{Привет & <мир> ЖЖ &unknown; &amp}v{<не тег> &amp;}v{}v{допосле})row[r=2]())",
                dump(scanner, listOf("r", "a", "b")),
            )
        }
    }

    @Test
    fun numericAttributesAndCellReferences() {
        for (scanner in scanners("""<c r="XFD1048576" s="12" big="12345678901" bad="1a" low="ab12"/>""")) {
            assertTrue(scanner.moveToRoot())
            assertEquals(16383, scanner.attrColumn("r".toByteArray()))
            assertEquals(27, scanner.attrColumn("low".toByteArray()))
            assertEquals(12, scanner.attrInt("s".toByteArray(), -1))
            assertEquals(-1, scanner.attrInt("big".toByteArray(), -1))
            assertEquals(-1, scanner.attrInt("bad".toByteArray(), -1))
            assertEquals(-1, scanner.attrInt("missing".toByteArray(), -1))
            assertEquals(-1, scanner.attrColumn("s".toByteArray()))
            assertNull(scanner.attr("xmlns".toByteArray()))
        }
    }

    @Test
    fun longTextGrowsBuffer() {
        val long = "Ж".repeat(200_000)
        for (scanner in scanners("<a><t>$long</t><t>end</t></a>")) {
            assertTrue(scanner.moveToRoot())
            val texts = ArrayList<String>()
            scanner.forEachChild { texts += scanner.text() }
            assertEquals(listOf(long, "end"), texts)
            assertEquals(XmlScanner.END_DOCUMENT, scanner.next())
        }
    }

    @Test
    fun utf16IsReencoded() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><a><t>Текст 😀</t></a>"
        for (charset in listOf(Charsets.UTF_16, Charsets.UTF_16LE)) {
            for (scanner in scanners(xml, charset)) {
                assertTrue(scanner.moveToRoot())
                var text = ""
                scanner.forEachChild { text = scanner.text() }
                assertEquals("Текст 😀", text)
            }
        }
    }

    @Test
    fun emptyDocument() {
        assertFalse(XmlScanner.open(ByteArrayInputStream(ByteArray(0))).moveToRoot())
        assertFalse(XmlScanner.open(ByteArrayInputStream("<?xml version=\"1.0\"?>  ".toByteArray())).moveToRoot())
    }

    private companion object {
        val NAMES = listOf("root", "row", "v", "skip", "a", "t")
        val TEXT_ELEMENTS = setOf("v", "t")
    }
}

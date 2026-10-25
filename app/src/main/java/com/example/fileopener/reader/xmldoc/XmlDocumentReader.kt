package com.example.fileopener.reader.xmldoc

import com.example.fileopener.reader.flow.DocumentNotice
import com.example.fileopener.reader.flow.FlowDocument
import com.example.fileopener.reader.flow.TextLineBlock
import com.example.fileopener.reader.txt.CharsetDetector
import com.example.fileopener.reader.txt.DetectedCharset
import com.example.fileopener.reader.txt.TextReader
import com.example.fileopener.reader.txt.readNBytesCompat
import com.example.fileopener.reader.xml.XmlParserFactory
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.charset.Charset

/**
 * XML как отформатированный текст: каждый тег — строка с отступом по глубине, с подсветкой
 * ([XmlPrettyPrinter]). Разбор — `XmlPullParser` без пространств имён (префиксы показываются как есть).
 *
 * Крайние случаи:
 * - пустой файл (или только пробелы) — документ без блоков, интерфейс пишет «Документ пуст»;
 * - кодировка — BOM, затем объявление `<?xml encoding?>`, иначе UTF-8 или windows-1251
 *   ([detectCharset]); байты, которые в ней не читаются, заменяются на «�», а не роняют разбор;
 * - повреждённый XML — исходный текст с уведомлением [DocumentNotice.MalformedXml];
 * - сущности в DTD — исходный текст без разбора ([DocumentNotice.XmlEntitiesNotExpanded]):
 *   парсер Android раскрывает их без ограничений, и вложенные сущности («billion laughs»)
 *   исчерпали бы память. Внешние DTD и сущности парсер не загружает.
 */
object XmlDocumentReader {

    /** [openStream] вызывается повторно, если вместо разобранного XML нужно показать исходный текст. */
    fun read(openStream: () -> InputStream, parsers: XmlParserFactory): FlowDocument {
        openStream().use { input ->
            val buffered = BufferedInputStream(input, CharsetDetector.SAMPLE_SIZE)
            buffered.mark(CharsetDetector.SAMPLE_SIZE)
            val sample = ByteArray(CharsetDetector.SAMPLE_SIZE)
            val sampleLength = buffered.readNBytesCompat(sample)
            buffered.reset()

            val charset = detectCharset(sample, sampleLength)
            val sampleText = String(sample, charset.bomLength, sampleLength - charset.bomLength, charset.charset)
            val wholeFileSampled = sampleLength < sample.size
            if (wholeFileSampled && sampleText.isBlank()) return FlowDocument.of(emptyList(), paragraphSpacing = false)
            if (declaresEntities(sampleText, wholeFileSampled)) {
                return sourceText(openStream, charset, DocumentNotice.XmlEntitiesNotExpanded)
            }

            buffered.skip(charset.bomLength.toLong())
            val parser = parsers.create().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(buffered.reader(charset.charset))
            }
            try {
                val blocks = XmlPrettyPrinter(parser, xmlDeclaration(sampleText)).print()
                return FlowDocument.of(blocks, paragraphSpacing = false)
            } catch (e: XmlPullParserException) {
                // Поток закрывается здесь же, а исходный текст читается заново с начала.
                val line = e.lineNumber.takeIf { it > 0 } ?: parser.lineNumber.takeIf { it > 0 }
                return sourceText(openStream, charset, DocumentNotice.MalformedXml(line))
            }
        }
    }

    private fun sourceText(openStream: () -> InputStream, charset: DetectedCharset, notice: DocumentNotice): FlowDocument {
        val lines = openStream().use { input ->
            val buffered = input.buffered()
            buffered.skip(charset.bomLength.toLong())
            TextReader.readParagraphs(buffered.reader(charset.charset))
        }
        return FlowDocument.of(lines.map(::TextLineBlock), paragraphSpacing = false, notice = notice)
    }

    /**
     * BOM → UTF-16 без BOM (по байтам `<?`) → кодировка из объявления → UTF-8 или windows-1251.
     * Объявленному UTF-8 не верим, если байты им не являются: частая ошибка генераторов,
     * которые пишут windows-1251 с шаблонным заголовком.
     */
    fun detectCharset(sample: ByteArray, length: Int = sample.size): DetectedCharset {
        val detected = CharsetDetector.detect(sample, length)
        if (detected.bomLength > 0) return detected
        if (length >= 4) {
            val b = IntArray(4) { sample[it].toInt() and 0xFF }
            if (b[0] == 0x3C && b[1] == 0 && b[2] == 0x3F && b[3] == 0) return DetectedCharset(Charsets.UTF_16LE, 0)
            if (b[0] == 0 && b[1] == 0x3C && b[2] == 0 && b[3] == 0x3F) return DetectedCharset(Charsets.UTF_16BE, 0)
        }
        val declared = declaredCharset(sample, length)
        return if (declared == null || declared == Charsets.UTF_8) detected else DetectedCharset(declared, 0)
    }

    private val ENCODING_DECLARATION = Regex("""^<\?xml\s[^>]*?encoding\s*=\s*["']([A-Za-z][A-Za-z0-9._:-]*)["']""")

    /** Кодировка из `<?xml … encoding="…"?>`, если Java её знает и она совместима с ASCII (иначе объявление ложно). */
    private fun declaredCharset(sample: ByteArray, length: Int): Charset? {
        val head = String(sample, 0, minOf(length, DECLARATION_SCAN_BYTES), Charsets.ISO_8859_1)
        val name = ENCODING_DECLARATION.find(head)?.groupValues?.get(1) ?: return null
        val charset = try {
            Charset.forName(name)
        } catch (e: IllegalArgumentException) {
            // IllegalCharsetNameException и UnsupportedCharsetException.
            return null
        }
        val asciiCompatible = charset.canEncode() && "<?".toByteArray(charset).contentEquals(byteArrayOf(0x3C, 0x3F))
        return charset.takeIf { asciiCompatible }
    }

    /**
     * Объявлены ли сущности во внутреннем подмножестве DTD. Если по началу файла это не проверить
     * (DOCTYPE или его подмножество не уместились в выборку), считаем, что объявлены.
     */
    internal fun declaresEntities(text: String, wholeFileSampled: Boolean): Boolean {
        val doctype = text.indexOf("<!DOCTYPE")
        if (doctype < 0) {
            // DOCTYPE стоит до корневого элемента: если корня в выборке нет, DOCTYPE может быть дальше.
            return !wholeFileSampled && !ELEMENT_START.containsMatchIn(text)
        }
        val rest = text.substring(doctype)
        val tagEnd = rest.indexOf('>')
        val subsetStart = rest.indexOf('[')
        val declaration = if (subsetStart >= 0 && (tagEnd < 0 || subsetStart < tagEnd)) {
            val subsetEnd = SUBSET_END.find(rest, subsetStart) ?: return !wholeFileSampled
            rest.substring(0, subsetEnd.range.last + 1)
        } else {
            rest
        }
        return "<!ENTITY" in declaration
    }

    private val ELEMENT_START = Regex("<[A-Za-z_:]")
    private val SUBSET_END = Regex("""]\s*>""")

    /** Объявление `<?xml …?>` для первой строки: парсеры Android и kxml2 сами его не отдают. */
    private fun xmlDeclaration(text: String): String? {
        if (!text.startsWith("<?xml")) return null
        val end = text.indexOf("?>")
        return if (end in 0 until DECLARATION_SCAN_BYTES) text.substring(0, end + 2) else null
    }

    private const val DECLARATION_SCAN_BYTES = 512
}

package io.doccraftstudio.onedoc.reader.xmldoc

import io.doccraftstudio.onedoc.reader.flow.FlowBlock
import io.doccraftstudio.onedoc.reader.flow.ParagraphBlock
import io.doccraftstudio.onedoc.reader.flow.TextRun
import io.doccraftstudio.onedoc.reader.txt.TextReader
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * Превращает поток токенов `XmlPullParser.nextToken()` в строки с отступом по глубине:
 * - открывающий и закрывающий теги — отдельные строки, пустой элемент — `<a/>`;
 * - элемент только с однострочным текстом — одна строка `<a>текст</a>`;
 * - текст, комментарии, CDATA, DOCTYPE и инструкции — построчно, пробелы по краям строк убираются;
 * - ссылки на сущности (`&amp;`, `&#160;`) показываются как в исходнике, значения атрибутов
 *   экранируются обратно — то есть видна разметка, а не её интерпретация.
 *
 * Строка — [ParagraphBlock] с отступом `indentLevel` и цветными фрагментами: длинная строка
 * переносится по ширине экрана, сохраняя отступ.
 */
internal class XmlPrettyPrinter(
    private val parser: XmlPullParser,
    /** Объявление `<?xml …?>` из начала файла: парсер его не отдаёт, но показать его полезно. */
    private val declaration: String?,
) {
    private val blocks = ArrayList<FlowBlock>()
    private var depth = 0

    /** Открывающий тег, ещё не выведенный: если внутри только короткий текст, элемент займёт одну строку. */
    private var pendingStart: MutableList<TextRun>? = null
    private val text = StringBuilder()
    private var skipNextEndTag = false
    private var rootSeen = false

    fun print(): List<FlowBlock> {
        declaration?.let { emitLines(it, COMMENT_COLOR) }
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.START_TAG -> onStartTag()
                XmlPullParser.END_TAG -> onEndTag()
                XmlPullParser.TEXT, XmlPullParser.IGNORABLE_WHITESPACE -> text.append(parser.text)
                XmlPullParser.ENTITY_REF -> text.append('&').append(parser.name).append(';')
                XmlPullParser.CDSECT -> {
                    flushPending()
                    emitLines("<![CDATA[${parser.text}]]>", color = null)
                }
                XmlPullParser.COMMENT -> {
                    flushPending()
                    emitLines("<!--${parser.text}-->", COMMENT_COLOR, italic = true)
                }
                XmlPullParser.PROCESSING_INSTRUCTION -> {
                    flushPending()
                    val instruction = parser.text.orEmpty()
                    if (!(declaration != null && XML_DECLARATION_TARGET.matches(instruction))) {
                        emitLines("<?$instruction?>", COMMENT_COLOR)
                    }
                }
                XmlPullParser.DOCDECL -> {
                    flushPending()
                    emitLines("<!DOCTYPE${parser.text}>", COMMENT_COLOR)
                }
                XmlPullParser.END_DOCUMENT -> {
                    // kxml2 мягче парсера Android: оборванный документ или текст без корня он не считает ошибкой.
                    if (!rootSeen || depth > 0) throw XmlPullParserException("Unexpected end of document", parser, null)
                    flushPending()
                    return blocks
                }
            }
        }
    }

    private fun onStartTag() {
        if (depth == 0) {
            if (rootSeen) throw XmlPullParserException("Multiple root elements", parser, null)
            if (text.isNotBlank()) throw XmlPullParserException("Text before root element", parser, null)
            rootSeen = true
        }
        flushPending()
        val runs = mutableListOf(TextRun("<${parser.name}", color = TAG_COLOR))
        for (i in 0 until parser.attributeCount) {
            runs += TextRun(" ${parser.getAttributeName(i)}", color = ATTRIBUTE_COLOR)
            runs += TextRun("=")
            runs += TextRun("\"${escapeAttribute(parser.getAttributeValue(i))}\"", color = VALUE_COLOR)
        }
        if (parser.isEmptyElementTag) {
            // Парсер всё равно отдаст END_TAG для `<a/>` — его пропускаем.
            runs += TextRun("/>", color = TAG_COLOR)
            emit(runs, depth)
            skipNextEndTag = true
        } else {
            runs += TextRun(">", color = TAG_COLOR)
            pendingStart = runs
            depth++
        }
    }

    private fun onEndTag() {
        if (skipNextEndTag) {
            skipNextEndTag = false
            return
        }
        val endTag = TextRun("</${parser.name}>", color = TAG_COLOR)
        val start = pendingStart
        val content = text.trim()
        if (start != null && '\n' !in content && content.length <= TextReader.MAX_PARAGRAPH_LENGTH) {
            if (content.isNotEmpty()) start += TextRun(content.toString())
            start += endTag
            emit(start, depth - 1)
            pendingStart = null
            text.setLength(0)
        } else {
            flushPending()
            emit(listOf(endTag), depth - 1)
        }
        depth--
    }

    /** Выводит отложенный открывающий тег и накопленный текст (его глубина — внутри текущего элемента). */
    private fun flushPending() {
        pendingStart?.let { emit(it, depth - 1) }
        pendingStart = null
        if (text.isNotBlank()) emitLines(text.toString(), color = null)
        text.setLength(0)
    }

    private fun emitLines(value: String, color: Int?, italic: Boolean = false) {
        for (line in value.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // Очень длинные строки (base64, минифицированный текст) режутся, как в TXT.
            trimmed.chunkedSafely(TextReader.MAX_PARAGRAPH_LENGTH).forEach { chunk ->
                emit(listOf(TextRun(chunk, italic = italic, color = color)), depth)
            }
        }
    }

    private fun emit(runs: List<TextRun>, level: Int) {
        blocks += ParagraphBlock(runs, indentLevel = level.coerceIn(0, MAX_INDENT_LEVEL))
    }

    private companion object {
        /** Дальше отступ не растёт, иначе глубокие документы на телефоне уходят за край экрана. */
        const val MAX_INDENT_LEVEL = 12

        // Цвета подобраны с контрастом не ниже 3:1 к фону всех тем чтения (светлой, тёмной, сепии).
        const val TAG_COLOR = 0xFF2F74D0.toInt()
        const val ATTRIBUTE_COLOR = 0xFF9C4DCC.toInt()
        const val VALUE_COLOR = 0xFF3A8A3A.toInt()
        const val COMMENT_COLOR = 0xFF7A7A7A.toInt()

        val XML_DECLARATION_TARGET = Regex("""xml(\s.*)?""", RegexOption.DOT_MATCHES_ALL)

        fun escapeAttribute(value: String): String =
            value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

        /** Как `chunked`, но не разрывает суррогатную пару. */
        fun String.chunkedSafely(size: Int): List<String> {
            if (length <= size) return listOf(this)
            val chunks = ArrayList<String>()
            var start = 0
            while (start < length) {
                var end = minOf(start + size, length)
                if (end < length && Character.isHighSurrogate(this[end - 1])) end--
                chunks += substring(start, end)
                start = end
            }
            return chunks
        }
    }
}

package com.example.fileopener.reader.xml

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.SequenceInputStream
import java.nio.charset.Charset

/**
 * Быстрый потоковый разбор XML прямо по байтам UTF-8 — для больших частей XLSX (лист на миллион строк —
 * это сотни мегабайт XML), где `XmlPullParser` Android тратит большую часть времени разбора.
 *
 * Понимает ровно то, что встречается в SpreadsheetML: элементы, атрибуты в любых кавычках, текст
 * с сущностями, CDATA; комментарии, инструкции обработки и DOCTYPE пропускаются. Имена элементов
 * и атрибутов сравниваются по локальной части (`x:row` = `row`), объявления `xmlns` не считаются
 * атрибутами. Пространства имён не проверяются, корректность документа — тоже.
 *
 * Имя и атрибуты текущего тега доступны до следующего вызова [next] или [text].
 * Глубина ведётся как в `XmlPullParser`: у корневого элемента 1, у END_TAG — глубина его элемента.
 */
class XmlScanner private constructor(private val input: InputStream) {
    private var buffer = ByteArray(BUFFER_SIZE)
    private var position = 0
    private var limit = 0
    private var eof = false

    var depth = 0
        private set
    private var leaveElement = false
    private var selfClosing = false

    private var nameStart = 0
    private var nameEnd = 0
    private var attributeCount = 0
    /** На атрибут четыре числа: начало и конец локального имени, начало и конец значения. */
    private var attributes = IntArray(4 * 8)

    private var textStart = 0
    private var textEnd = 0
    private var textIsCdata = false

    /** Следующее событие: [START_TAG], [END_TAG], [TEXT] или [END_DOCUMENT]. */
    fun next(): Int {
        if (leaveElement) {
            depth--
            leaveElement = false
        }
        if (selfClosing) {
            selfClosing = false
            leaveElement = true
            return END_TAG
        }
        while (true) {
            if (!ensure(1)) return END_DOCUMENT
            if (buffer[position] != LT) {
                val end = indexOf(LT, 0).let { if (it < 0) limit else it }
                textStart = position
                textEnd = end
                textIsCdata = false
                position = end
                return TEXT
            }
            if (!ensure(2)) return END_DOCUMENT
            when (buffer[position + 1]) {
                SLASH -> {
                    position = indexOfOrFail(GT, 2) + 1
                    leaveElement = true
                    return END_TAG
                }
                QUESTION -> skipPast(PI_END, 2)
                BANG -> when {
                    startsWith(CDATA_START) -> {
                        val end = indexOfSequence(CDATA_END, CDATA_START.size)
                        if (end < 0) throw IOException("Не закрыт CDATA")
                        textStart = position + CDATA_START.size
                        textEnd = end
                        textIsCdata = true
                        position = end + CDATA_END.size
                        return TEXT
                    }
                    startsWith(COMMENT_START) -> skipPast(COMMENT_END, COMMENT_START.size)
                    else -> position = indexOfOrFail(GT, 2) + 1 // DOCTYPE
                }
                else -> {
                    readStartTag()
                    depth++
                    return START_TAG
                }
            }
        }
    }

    /** Локальное имя текущего START_TAG равно [name] (байты ASCII). */
    fun isName(name: ByteArray): Boolean = rangeEquals(nameStart, nameEnd, name)

    fun attr(name: ByteArray): String? {
        val i = attributeIndex(name)
        return if (i < 0) null else decode(attributes[i + 2], attributes[i + 3], entities = true)
    }

    /**
     * Номер значения атрибута [name] в [values] — сравнение байтов, без создания строки;
     * `-1`, если атрибута нет или значение другое.
     */
    fun attrIndexOf(name: ByteArray, values: Array<ByteArray>): Int {
        val i = attributeIndex(name)
        if (i < 0) return -1
        for (v in values.indices) if (rangeEquals(attributes[i + 2], attributes[i + 3], values[v])) return v
        return -1
    }

    /** Целое неотрицательное значение атрибута; [missing] — если атрибута нет или это не число. */
    fun attrInt(name: ByteArray, missing: Int): Int {
        val i = attributeIndex(name)
        if (i < 0) return missing
        val start = attributes[i + 2]
        val end = attributes[i + 3]
        if (start == end || end - start > 9) return missing
        var value = 0
        for (p in start until end) {
            val digit = buffer[p] - ZERO
            if (digit !in 0..9) return missing
            value = value * 10 + digit
        }
        return value
    }

    /** Колонка из ссылки на ячейку (`B12` → 1); `-1`, если атрибута нет или в нём нет букв. */
    fun attrColumn(name: ByteArray): Int {
        val i = attributeIndex(name)
        if (i < 0) return -1
        var column = 0
        var letters = 0
        for (p in attributes[i + 2] until attributes[i + 3]) {
            val upper = buffer[p].toInt() and 0xDF
            if (upper !in 'A'.code..'Z'.code || letters == MAX_COLUMN_LETTERS) break
            column = column * 26 + (upper - 'A'.code + 1)
            letters++
        }
        return if (letters == 0) -1 else column - 1
    }

    /**
     * Текст текущего элемента (парсер стоит на его START_TAG), как `XmlPullParser.nextText()`:
     * после вызова — на его END_TAG. Вложенные элементы пропускаются.
     */
    fun text(): String {
        simpleText()?.let { return it }
        val elementDepth = depth
        var result = ""
        var builder: StringBuilder? = null
        while (true) {
            when (next()) {
                TEXT -> {
                    val part = decode(textStart, textEnd, entities = !textIsCdata)
                    when {
                        result.isEmpty() && builder == null -> result = part
                        else -> (builder ?: StringBuilder(result).also { builder = it }).append(part)
                    }
                }
                START_TAG -> skipElement()
                END_TAG -> if (depth == elementDepth) return builder?.toString() ?: result
                END_DOCUMENT -> return builder?.toString() ?: result
            }
        }
    }

    /**
     * Короткий путь [text] для самого частого случая — `<v>123.45</v>`: текст без разметки и сущностей
     * сразу до закрывающего тега. Иначе `null`, и позиция не меняется.
     */
    private fun simpleText(): String? {
        if (selfClosing) return null
        var ascii = true
        var offset = 0
        while (true) {
            var p = position + offset
            while (p < limit) {
                val c = buffer[p]
                if (c == LT) break
                if (c == AMPERSAND) return null
                if (c < 0) ascii = false
                p++
            }
            offset = p - position
            if (p < limit) break
            if (!fill()) return null
        }
        if (!ensure(offset + 2) || buffer[position + offset + 1] != SLASH) return null
        val end = position + offset
        val text = when {
            end == position -> ""
            // ASCII — подмножество Latin-1: однобайтовая строка без декодера UTF-8.
            ascii -> String(buffer, position, end - position, Charsets.ISO_8859_1)
            else -> String(buffer, position, end - position, Charsets.UTF_8)
        }
        position = indexOfOrFail(GT, offset + 2) + 1
        leaveElement = true
        return text
    }

    /** Пропускает текущий элемент (парсер на его START_TAG) до его END_TAG. */
    fun skipElement() = forEachChild { }

    /** Обходит потомков текущего элемента, как `XmlPullParser.forEachChild` из XmlPull.kt. */
    inline fun forEachChild(block: () -> Unit) {
        val parentDepth = depth
        while (true) {
            when (next()) {
                END_DOCUMENT -> return
                END_TAG -> if (depth == parentDepth) return
                START_TAG -> if (depth == parentDepth + 1) block()
            }
        }
    }

    /** Переходит к корневому элементу документа. */
    fun moveToRoot(): Boolean {
        while (true) {
            when (next()) {
                START_TAG -> return true
                END_DOCUMENT -> return false
            }
        }
    }

    private fun readStartTag() {
        val end = findTagEnd()
        var p = position + 1
        val qualifiedStart = p
        while (p < end && !isNameEnd(buffer[p])) p++
        nameStart = localNameStart(qualifiedStart, p)
        nameEnd = p
        attributeCount = 0
        selfClosing = buffer[end - 1] == SLASH
        val attributesEnd = if (selfClosing) end - 1 else end
        while (true) {
            while (p < attributesEnd && isSpace(buffer[p])) p++
            if (p >= attributesEnd) break
            val attrNameStart = p
            while (p < attributesEnd && buffer[p] != EQUALS && !isSpace(buffer[p])) p++
            val attrNameEnd = p
            while (p < attributesEnd && buffer[p] != QUOTE && buffer[p] != APOSTROPHE) p++
            if (p >= attributesEnd) break
            val quote = buffer[p]
            val valueStart = ++p
            while (p < attributesEnd && buffer[p] != quote) p++
            val valueEnd = p
            p++
            if (isNamespaceDeclaration(attrNameStart, attrNameEnd)) continue
            if (attributes.size < (attributeCount + 1) * 4) attributes = attributes.copyOf(attributes.size * 2)
            val base = attributeCount * 4
            attributes[base] = localNameStart(attrNameStart, attrNameEnd)
            attributes[base + 1] = attrNameEnd
            attributes[base + 2] = valueStart
            attributes[base + 3] = valueEnd
            attributeCount++
        }
        position = end + 1
    }

    private fun attributeIndex(name: ByteArray): Int {
        for (i in 0 until attributeCount) {
            val base = i * 4
            if (rangeEquals(attributes[base], attributes[base + 1], name)) return base
        }
        return -1
    }

    private fun isNamespaceDeclaration(start: Int, end: Int): Boolean {
        if (end - start < XMLNS.size) return false
        for (i in XMLNS.indices) if (buffer[start + i] != XMLNS[i]) return false
        return end - start == XMLNS.size || buffer[start + XMLNS.size] == COLON
    }

    private fun localNameStart(start: Int, end: Int): Int {
        for (p in end - 1 downTo start) if (buffer[p] == COLON) return p + 1
        return start
    }

    private fun rangeEquals(start: Int, end: Int, name: ByteArray): Boolean {
        if (end - start != name.size) return false
        for (i in name.indices) if (buffer[start + i] != name[i]) return false
        return true
    }

    private fun decode(start: Int, end: Int, entities: Boolean): String {
        if (start == end) return ""
        val text = String(buffer, start, end - start, Charsets.UTF_8)
        return if (entities && text.indexOf('&') >= 0) decodeEntities(text) else text
    }

    /** Конец текущего тега (`>`) с учётом кавычек: в значениях атрибутов `>` допустим. */
    private fun findTagEnd(): Int {
        var offset = 1
        var quote: Byte = 0
        while (true) {
            var p = position + offset
            while (p < limit) {
                val c = buffer[p]
                if (quote != NO_QUOTE) {
                    if (c == quote) quote = NO_QUOTE
                } else if (c == QUOTE || c == APOSTROPHE) {
                    quote = c
                } else if (c == GT) {
                    return p
                }
                p++
            }
            offset = p - position
            if (!fill()) throw IOException("Не закрыт тег")
        }
    }

    /** Абсолютный индекс [byte] начиная со смещения [from] от текущей позиции; `-1` — до конца файла нет. */
    private fun indexOf(byte: Byte, from: Int): Int {
        var offset = from
        while (true) {
            var p = position + offset
            while (p < limit) {
                if (buffer[p] == byte) return p
                p++
            }
            offset = p - position
            if (!fill()) return -1
        }
    }

    private fun indexOfOrFail(byte: Byte, from: Int): Int =
        indexOf(byte, from).also { if (it < 0) throw IOException("Неожиданный конец XML") }

    private fun indexOfSequence(sequence: ByteArray, from: Int): Int {
        var offset = from
        while (true) {
            val p = indexOf(sequence[0], offset)
            if (p < 0) return -1
            val relative = p - position
            if (!ensure(relative + sequence.size)) return -1
            val start = position + relative
            if ((1 until sequence.size).all { buffer[start + it] == sequence[it] }) return start
            offset = relative + 1
        }
    }

    private fun skipPast(sequence: ByteArray, from: Int) {
        val end = indexOfSequence(sequence, from)
        if (end < 0) throw IOException("Неожиданный конец XML")
        position = end + sequence.size
    }

    private fun startsWith(sequence: ByteArray): Boolean {
        if (!ensure(sequence.size)) return false
        for (i in sequence.indices) if (buffer[position + i] != sequence[i]) return false
        return true
    }

    /** В буфере есть хотя бы [count] байт от текущей позиции. */
    private fun ensure(count: Int): Boolean {
        while (limit - position < count) {
            if (!fill()) return false
        }
        return true
    }

    /**
     * Дочитывает данные, сдвигая непрочитанное (от [position]) в начало буфера; индексы внутри
     * буфера после этого меняются. Буфер растёт, если в него не влезает один тег или текст.
     */
    private fun fill(): Boolean {
        if (eof) return false
        val pending = limit - position
        if (position > 0) {
            System.arraycopy(buffer, position, buffer, 0, pending)
        } else if (pending == buffer.size) {
            buffer = buffer.copyOf(buffer.size * 2)
        }
        position = 0
        limit = pending
        val read = input.read(buffer, limit, buffer.size - limit)
        if (read < 0) {
            eof = true
            return false
        }
        limit += read
        return true
    }

    companion object {
        const val START_TAG = 2
        const val END_TAG = 3
        const val TEXT = 4
        const val END_DOCUMENT = 1

        /**
         * Сканер для [input]. XLSX почти всегда в UTF-8; документ в другой кодировке (UTF-16,
         * объявленная `encoding`) перекодируется в UTF-8 на лету.
         */
        fun open(input: InputStream): XmlScanner {
            val head = ByteArray(HEAD_SIZE)
            var length = 0
            while (length < head.size) {
                val read = input.read(head, length, head.size - length)
                if (read < 0) break
                length += read
            }
            val stream = SequenceInputStream(ByteArrayInputStream(head, 0, length), input)
            val charset = detectCharset(head, length)
            return XmlScanner(if (charset == Charsets.UTF_8) stream else Utf8Input(InputStreamReader(stream, charset)))
        }

        internal fun detectCharset(head: ByteArray, length: Int): Charset {
            fun at(i: Int) = if (i < length) head[i].toInt() and 0xFF else -1
            if ((at(0) == 0xFE && at(1) == 0xFF) || (at(0) == 0xFF && at(1) == 0xFE)) return Charsets.UTF_16
            if (at(0) == 0 && at(1) == '<'.code) return Charsets.UTF_16BE
            if (at(0) == '<'.code && at(1) == 0) return Charsets.UTF_16LE
            val declaration = String(head, 0, length, Charsets.ISO_8859_1).substringBefore("?>")
            val name = DECLARED_ENCODING.find(declaration)?.groupValues?.get(1) ?: return Charsets.UTF_8
            return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
        }

        internal fun decodeEntities(text: String): String {
            val result = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val end = if (c == '&') text.indexOf(';', i + 1) else -1
                if (end < 0 || end - i > MAX_ENTITY_LENGTH) {
                    result.append(c)
                    i++
                    continue
                }
                val entity = text.substring(i + 1, end)
                val code = when {
                    entity == "lt" -> '<'.code
                    entity == "gt" -> '>'.code
                    entity == "amp" -> '&'.code
                    entity == "quot" -> '"'.code
                    entity == "apos" -> '\''.code
                    entity.startsWith("#x") || entity.startsWith("#X") -> entity.substring(2).toIntOrNull(16)
                    entity.startsWith("#") -> entity.substring(1).toIntOrNull()
                    else -> null
                }
                if (code == null || !Character.isValidCodePoint(code)) {
                    result.append(c)
                    i++
                } else {
                    result.appendCodePoint(code)
                    i = end + 1
                }
            }
            return result.toString()
        }

        private const val BUFFER_SIZE = 64 * 1024
        private const val HEAD_SIZE = 256
        private const val MAX_ENTITY_LENGTH = 10
        /** XFD — последняя колонка Excel; длиннее ссылок не бывает. */
        private const val MAX_COLUMN_LETTERS = 3
        private val DECLARED_ENCODING = Regex("""encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""")

        private const val LT = '<'.code.toByte()
        private const val GT = '>'.code.toByte()
        private const val SLASH = '/'.code.toByte()
        private const val QUESTION = '?'.code.toByte()
        private const val BANG = '!'.code.toByte()
        private const val EQUALS = '='.code.toByte()
        private const val QUOTE = '"'.code.toByte()
        private const val APOSTROPHE = '\''.code.toByte()
        private const val COLON = ':'.code.toByte()
        private const val AMPERSAND = '&'.code.toByte()
        private const val NO_QUOTE: Byte = 0
        private const val ZERO = '0'.code.toByte()
        private val XMLNS = "xmlns".toByteArray()
        private val PI_END = "?>".toByteArray()
        private val COMMENT_START = "<!--".toByteArray()
        private val COMMENT_END = "-->".toByteArray()
        private val CDATA_START = "<![CDATA[".toByteArray()
        private val CDATA_END = "]]>".toByteArray()

        private fun isSpace(c: Byte) = c == ' '.code.toByte() || c == '\n'.code.toByte() ||
            c == '\r'.code.toByte() || c == '\t'.code.toByte()

        private fun isNameEnd(c: Byte) = isSpace(c) || c == SLASH || c == GT
    }
}

/** Отдаёт символы [reader] байтами UTF-8; суррогатная пара на границе порции не разрывается. */
private class Utf8Input(private val reader: Reader) : InputStream() {
    private val chars = CharArray(8 * 1024)
    private var carry: Char? = null
    private var bytes = ByteArray(0)
    private var position = 0

    override fun read(): Int {
        if (!refill()) return -1
        return bytes[position++].toInt() and 0xFF
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!refill()) return -1
        val count = minOf(length, bytes.size - position)
        System.arraycopy(bytes, position, target, offset, count)
        position += count
        return count
    }

    private fun refill(): Boolean {
        while (position >= bytes.size) {
            var start = 0
            carry?.let {
                chars[0] = it
                start = 1
                carry = null
            }
            val read = reader.read(chars, start, chars.size - start)
            if (read < 0 && start == 0) return false
            var count = start + maxOf(read, 0)
            if (read >= 0 && count > 0 && Character.isHighSurrogate(chars[count - 1])) {
                carry = chars[count - 1]
                count--
            }
            bytes = String(chars, 0, count).toByteArray(Charsets.UTF_8)
            position = 0
        }
        return true
    }

    override fun close() = reader.close()
}

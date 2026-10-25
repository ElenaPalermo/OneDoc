package io.doccraftstudio.onedoc.reader.txt

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.Reader
import java.nio.charset.Charset

/** Текст, разбитый на абзацы для ленивого отображения в `LazyColumn`. */
data class TextDocument(
    val paragraphs: List<String>,
    val charset: Charset,
)

/** Потоковое чтение TXT: кодировка определяется по первым 64 КБ, текст читается построчно. */
object TextReader {
    /** Очень длинные строки (минифицированные логи, CSV без переносов) режутся, чтобы элемент списка не был гигантским. */
    const val MAX_PARAGRAPH_LENGTH = 4_000

    fun read(input: InputStream): TextDocument {
        val buffered = BufferedInputStream(input, CharsetDetector.SAMPLE_SIZE)
        buffered.mark(CharsetDetector.SAMPLE_SIZE)
        val sample = ByteArray(CharsetDetector.SAMPLE_SIZE)
        val sampleLength = buffered.readNBytesCompat(sample)
        buffered.reset()

        val detected = CharsetDetector.detect(sample, sampleLength)
        buffered.skip(detected.bomLength.toLong())

        return TextDocument(readParagraphs(buffered.reader(detected.charset)), detected.charset)
    }

    /** Строки текста; слишком длинные режутся на куски по [MAX_PARAGRAPH_LENGTH]. */
    fun readParagraphs(reader: Reader): List<String> {
        val paragraphs = ArrayList<String>()
        reader.buffered().useLines { lines ->
            lines.forEach { line -> paragraphs.addChunked(line) }
        }
        return paragraphs
    }

    private fun ArrayList<String>.addChunked(line: String) {
        if (line.length <= MAX_PARAGRAPH_LENGTH) {
            add(line)
            return
        }
        var start = 0
        while (start < line.length) {
            var end = minOf(start + MAX_PARAGRAPH_LENGTH, line.length)
            // Не разрываем суррогатную пару (эмодзи и редкие символы).
            if (end < line.length && Character.isHighSurrogate(line[end - 1])) end--
            add(line.substring(start, end))
            start = end
        }
    }
}

/** `InputStream.readNBytes` появился только в API 33. */
internal fun InputStream.readNBytesCompat(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val read = read(buffer, total, buffer.size - total)
        if (read < 0) break
        total += read
    }
    return total
}

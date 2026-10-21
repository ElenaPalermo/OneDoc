package com.example.fileopener.reader.csv

import com.example.fileopener.reader.sheet.CellKind
import com.example.fileopener.reader.sheet.CellStyle
import com.example.fileopener.reader.sheet.MAX_SHEET_COLUMNS
import com.example.fileopener.reader.sheet.SheetCell
import com.example.fileopener.reader.sheet.SheetSink
import com.example.fileopener.reader.sheet.SpreadsheetSource
import com.example.fileopener.reader.txt.CharsetDetector
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.Reader

/** CSV как книга из одного листа; файл читается заново при разборе листа. */
/** [sizeBytes] — размер файла для оценки числа строк; 0, если неизвестен. */
class CsvSource(
    sheetName: String,
    private val sizeBytes: Long = 0,
    private val openStream: () -> InputStream,
) : SpreadsheetSource {
    override val sheetNames: List<String> = listOf(sheetName)
    override val styles: List<CellStyle> = listOf(CellStyle.DEFAULT)

    override fun readSheet(index: Int, sink: SheetSink) {
        openStream().use { CsvReader.read(it, sink, sizeBytes) }
    }

    override fun close() = Unit
}

/**
 * Потоковый разбор CSV по RFC 4180: поля в кавычках с `""` внутри и переносами строк.
 * Кодировка определяется как у TXT (BOM → UTF-8 → windows-1251), разделитель (`,` `;` таб `|`) —
 * по первым строкам: выбирается тот, что встречается одинаковое число раз в большинстве строк.
 */
object CsvReader {
    private val DELIMITERS = charArrayOf(',', ';', '\t', '|')
    private const val DELIMITER_SAMPLE_LINES = 50
    private const val NEWLINE = '\n'.code.toByte()
    /** Поле с незакрытой кавычкой может растянуться на весь файл — обрезаем. */
    private const val MAX_FIELD_CHARS = 32 * 1024
    private val NUMBER = Regex("""^[-+]?\d[\d   .,']*([eE][-+]?\d+)?%?$""")

    fun read(input: InputStream, sink: SheetSink, sizeBytes: Long = 0) {
        val buffered = BufferedInputStream(input, CharsetDetector.SAMPLE_SIZE)
        buffered.mark(CharsetDetector.SAMPLE_SIZE)
        val sample = ByteArray(CharsetDetector.SAMPLE_SIZE)
        var sampleLength = 0
        while (sampleLength < sample.size) {
            val read = buffered.read(sample, sampleLength, sample.size - sampleLength)
            if (read < 0) break
            sampleLength += read
        }
        buffered.reset()

        val detected = CharsetDetector.detect(sample, sampleLength)
        val sampleText = String(sample, detected.bomLength, sampleLength - detected.bomLength, detected.charset)
        val delimiter = detectDelimiter(sampleText, truncated = sampleLength == sample.size)
        estimateRowCount(sample, sampleLength, sizeBytes)?.let(sink::expectedRowCount)
        buffered.skip(detected.bomLength.toLong())
        parse(buffered.reader(detected.charset), delimiter, sink)
    }

    /** Оценка числа строк по доле переводов строки в начале файла; `null`, если файл прочитан целиком в образец. */
    internal fun estimateRowCount(sample: ByteArray, sampleLength: Int, sizeBytes: Long): Int? {
        if (sizeBytes <= sampleLength || sampleLength == 0) return null
        val lines = (0 until sampleLength).count { sample[it] == NEWLINE }
        if (lines == 0) return null
        return (sizeBytes * lines / sampleLength).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    internal fun detectDelimiter(sample: String, truncated: Boolean = false): Char {
        val lines = splitRecords(sample).let { if (truncated && it.size > 1) it.dropLast(1) else it }
            .filter { it.isNotBlank() }
            .take(DELIMITER_SAMPLE_LINES)
        if (lines.isEmpty()) return ','
        var best = ','
        var bestScore = 0.0
        for (candidate in DELIMITERS) {
            val counts = lines.map { line -> countOutsideQuotes(line, candidate) }
            val mode = counts.filter { it > 0 }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: continue
            val consistency = counts.count { it == mode }.toDouble() / counts.size
            // Согласованность важнее количества; при равной — больше колонок.
            val score = consistency * 1_000 + mode.coerceAtMost(999)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    /** Делит выборку на записи с учётом переносов внутри кавычек. */
    private fun splitRecords(text: String): List<String> {
        val records = ArrayList<String>()
        var quoted = false
        var start = 0
        for (i in text.indices) {
            when (text[i]) {
                '"' -> quoted = !quoted
                '\n' -> if (!quoted) {
                    records += text.substring(start, i).trimEnd('\r')
                    start = i + 1
                }
            }
        }
        if (start < text.length) records += text.substring(start)
        return records
    }

    private fun countOutsideQuotes(line: String, delimiter: Char): Int {
        var quoted = false
        var count = 0
        for (char in line) {
            if (char == '"') quoted = !quoted else if (!quoted && char == delimiter) count++
        }
        return count
    }

    internal fun parse(reader: Reader, delimiter: Char, sink: SheetSink) {
        val buffer = CharArray(64 * 1024)
        val field = StringBuilder()
        val cells = ArrayList<SheetCell>()
        var column = 0
        var rowIndex = 0
        var inQuotes = false
        var quotePending = false
        var fieldQuoted = false
        var rowHasContent = false

        fun endField() {
            if (field.isNotEmpty() && column < MAX_SHEET_COLUMNS) {
                val text = field.toString()
                val kind = if (!fieldQuoted && NUMBER.matches(text.trim())) CellKind.NUMBER else CellKind.TEXT
                cells += SheetCell(column, text, kind)
            }
            field.setLength(0)
            fieldQuoted = false
            column++
        }

        fun endRow() {
            sink.row(rowIndex, ArrayList(cells))
            cells.clear()
            column = 0
            rowIndex++
            rowHasContent = false
        }

        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            for (i in 0 until read) {
                val char = buffer[i]
                if (inQuotes) {
                    if (quotePending) {
                        quotePending = false
                        if (char == '"') {
                            field.appendLimited('"')
                            continue
                        }
                        inQuotes = false
                        // Кавычка закрыла поле — текущий символ разбирается как обычный.
                    } else {
                        if (char == '"') quotePending = true else field.appendLimited(char)
                        continue
                    }
                }
                rowHasContent = true
                when (char) {
                    '"' -> if (field.isEmpty() && !fieldQuoted) {
                        inQuotes = true
                        fieldQuoted = true
                    } else {
                        field.appendLimited(char)
                    }
                    delimiter -> endField()
                    '\n' -> {
                        endField()
                        endRow()
                    }
                    '\r' -> Unit
                    else -> field.appendLimited(char)
                }
            }
        }
        if (inQuotes || rowHasContent || field.isNotEmpty()) {
            endField()
            endRow()
        }
    }

    private fun StringBuilder.appendLimited(char: Char) {
        if (length < MAX_FIELD_CHARS) append(char)
    }
}

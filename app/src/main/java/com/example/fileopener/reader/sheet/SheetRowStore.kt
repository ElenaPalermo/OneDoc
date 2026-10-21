package com.example.fileopener.reader.sheet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Опубликованное состояние листа: сколько строк уже можно читать и как раскладывать колонки.
 * @property columnWidths ширина каждой колонки в символах (единица Excel); 0 — скрытая колонка.
 * @property merges объединённые ячейки по номеру строки; известны после разбора всего листа.
 * @property expectedRowCount сколько строк обещает файл (0 — неизвестно); до конца разбора по нему
 *   выбирается ширина колонки номеров строк.
 * @property publishedBytes длина опубликованной части файла кэша — граница для поиска во время разбора.
 */
class SheetStoreState(
    val rowCount: Int = 0,
    val columnCount: Int = 0,
    val columnWidths: FloatArray = FloatArray(0),
    val merges: Map<Int, List<CellRange>> = emptyMap(),
    val complete: Boolean = false,
    val failed: Boolean = false,
    val expectedRowCount: Int = 0,
    val publishedBytes: Long = 0,
)

/** Совпадения поиска и смещение, с которого продолжать поиск по дописанным позже строкам. */
class SheetSearchChunk(val matches: List<CellPosition>, val nextOffset: Long)

data class CellPosition(val row: Int, val column: Int)

/**
 * Лист, записанный во временный файл: парсер дописывает строки в конец, интерфейс читает окна строк
 * по индексу смещений. Так в памяти не держится весь лист (миллион строк XLSX — сотни мегабайт),
 * а первые строки видны до окончания разбора.
 *
 * Запись идёт из одного потока (парсер), чтение — из любых. Новые строки становятся доступны
 * для чтения после публикации ([state]): сначала после первых [FIRST_PUBLISH_ROWS], затем пачками.
 *
 * Формат записи строки: varint индекс, varint число ячеек, затем на ячейку —
 * varint колонка, varint стиль, байт [CellKind], строка в modified UTF-8.
 */
class SheetRowStore(private val file: File) : SheetSink, Closeable {
    private val output = BufferedOutputStream(FileOutputStream(file), IO_BUFFER)
    private val rowBuffer = ByteArrayOutputStream()
    private val rowData = DataOutputStream(rowBuffer)
    private var bytesWritten = 0L

    /** Смещение записи строки в файле, `-1` — пустая строка. Массив растёт, поэтому доступ под [lock]. */
    private var offsets = LongArray(INITIAL_ROWS).apply { fill(-1) }
    private val lock = Any()

    private var rowCount = 0
    private var columnCount = 0
    private var rowsSincePublish = 0
    private var published = false

    private var expectedRows = 0
    private var defaultWidth: Float? = null
    private val explicitWidths = ArrayList<Pair<IntRange, Float>>()
    private val merges = ArrayList<CellRange>()

    /** Самый длинный текст колонки по всем записанным строкам — для подбора ширины. */
    private var sampleChars = IntArray(16)

    private val _state = MutableStateFlow(SheetStoreState())
    val state: StateFlow<SheetStoreState> = _state.asStateFlow()

    override fun defaultColumnWidth(widthChars: Float) {
        if (widthChars > 0f) defaultWidth = widthChars
    }

    override fun columnWidth(firstColumn: Int, lastColumn: Int, widthChars: Float) {
        val first = firstColumn.coerceAtLeast(0)
        val last = lastColumn.coerceAtMost(MAX_SHEET_COLUMNS - 1)
        if (first <= last) explicitWidths += first..last to widthChars.coerceAtLeast(0f)
    }

    override fun expectedRowCount(count: Int) {
        expectedRows = count.coerceIn(0, MAX_SHEET_ROWS)
    }

    override fun merge(range: CellRange) {
        if (range.firstRow < MAX_SHEET_ROWS) merges += range
    }

    override fun row(index: Int, cells: List<SheetCell>) {
        if (index !in 0 until MAX_SHEET_ROWS) return
        val visible = cells.filter { it.column in 0 until MAX_SHEET_COLUMNS }
        if (visible.isEmpty()) return

        rowBuffer.reset()
        rowData.writeVarInt(index)
        rowData.writeVarInt(visible.size)
        for (cell in visible) {
            rowData.writeVarInt(cell.column)
            rowData.writeVarInt(cell.style.coerceAtLeast(0))
            rowData.writeByte(cell.kind.ordinal)
            rowData.writeUTF(if (cell.text.length > MAX_CELL_CHARS) cell.text.take(MAX_CELL_CHARS) else cell.text)
        }
        rowBuffer.writeTo(output)
        synchronized(lock) {
            if (index >= offsets.size) {
                val grown = LongArray(maxOf(index + 1, offsets.size * 2)).apply { fill(-1) }
                offsets.copyInto(grown)
                offsets = grown
            }
            offsets[index] = bytesWritten
        }
        bytesWritten += rowBuffer.size()
        rowCount = maxOf(rowCount, index + 1)
        columnCount = maxOf(columnCount, visible.last().column + 1)
        sample(visible)

        rowsSincePublish++
        if (rowsSincePublish >= if (published) PUBLISH_ROWS else FIRST_PUBLISH_ROWS) publish(complete = false)
    }

    /** Разбор окончен: публикуются все строки и объединённые ячейки. */
    fun finish() = publish(complete = true)

    /** Разбор прервался ошибкой: прочитанное остаётся доступным. */
    fun fail() {
        runCatching { output.flush() }
        _state.update {
            SheetStoreState(
                rowCount, columnCount, it.columnWidths, it.merges,
                complete = true, failed = true, publishedBytes = bytesWritten,
            )
        }
    }

    private fun publish(complete: Boolean) {
        output.flush()
        rowsSincePublish = 0
        published = true
        _state.value = SheetStoreState(
            rowCount = rowCount,
            columnCount = columnCount,
            columnWidths = columnWidths(),
            merges = if (complete) mergesByRow() else emptyMap(),
            complete = complete,
            expectedRowCount = expectedRows,
            publishedBytes = bytesWritten,
        )
    }

    private fun sample(cells: List<SheetCell>) {
        val last = cells.last().column
        if (last >= sampleChars.size) sampleChars = sampleChars.copyOf(maxOf(last + 1, sampleChars.size * 2))
        for (cell in cells) {
            sampleChars[cell.column] = maxOf(sampleChars[cell.column], cell.text.length)
        }
    }

    private fun columnWidths(): FloatArray = FloatArray(columnCount) { column ->
        explicitWidths.lastOrNull { column in it.first }?.second
            ?: defaultWidth
            ?: (sampleChars.getOrElse(column) { 0 } + AUTO_WIDTH_PADDING).toFloat().coerceIn(MIN_AUTO_WIDTH, MAX_AUTO_WIDTH)
    }

    /** Объединения по строкам; огромные (весь лист) не раскладываются построчно, чтобы не тратить память. */
    private fun mergesByRow(): Map<Int, List<CellRange>> {
        val result = HashMap<Int, MutableList<CellRange>>()
        var entries = 0
        for (range in merges) {
            for (row in range.firstRow..minOf(range.lastRow, rowCount)) {
                if (entries++ >= MAX_MERGE_ENTRIES) return result
                result.getOrPut(row) { ArrayList(2) } += range
            }
        }
        return result
    }

    /**
     * Строки [from] until [from] + [count] из опубликованных; пустые и ещё не прочитанные — `null`.
     */
    fun readRows(from: Int, count: Int): List<SheetRow?> {
        val end = minOf(from + count, _state.value.rowCount)
        if (from >= end) return emptyList()
        val positions = synchronized(lock) { LongArray(end - from) { offsets.getOrElse(from + it) { -1 } } }
        val result = arrayOfNulls<SheetRow>(end - from)
        var stream: PositionedInput? = null
        try {
            for (i in positions.indices) {
                val position = positions[i]
                if (position < 0) continue
                // Строки обычно записаны подряд — читаем одним потоком, перепрыгивая вперёд.
                val current = stream?.takeIf { it.position <= position } ?: PositionedInput(file, position).also {
                    stream?.close()
                    stream = it
                }
                current.skipTo(position)
                result[i] = current.readRecord().second
            }
        } catch (e: EOFException) {
            // Строку перезаписали после публикации и она ещё в буфере записи — покажем при следующем чтении.
        } finally {
            stream?.close()
        }
        return result.asList()
    }

    /**
     * Ищет [query] без учёта регистра во всех записанных строках; не больше [limit] совпадений.
     * [isActive] проверяется по ходу, чтобы отменённый поиск не дочитывал файл.
     */
    fun search(query: String, limit: Int, isActive: () -> Boolean = { true }): List<CellPosition> =
        searchRange(query, limit, fromOffset = 0, untilOffset = Long.MAX_VALUE, isActive).matches

    /**
     * Поиск по записям между смещениями [fromOffset] и [untilOffset] файла кэша (граница — обычно
     * [SheetStoreState.publishedBytes]). Строки пишутся по возрастанию, поэтому поиск по частям
     * с [SheetSearchChunk.nextOffset] даёт совпадения в порядке листа.
     */
    fun searchRange(
        query: String,
        limit: Int,
        fromOffset: Long,
        untilOffset: Long,
        isActive: () -> Boolean = { true },
    ): SheetSearchChunk {
        if (query.isEmpty() || fromOffset >= untilOffset) return SheetSearchChunk(emptyList(), fromOffset)
        val matches = ArrayList<CellPosition>()
        val next = RecordSearcher(file, fromOffset, CellMatcher(query)).use {
            it.search(matches, limit, untilOffset, isActive)
        }
        return SheetSearchChunk(matches.sortedWith(compareBy(CellPosition::row, CellPosition::column)), next)
    }

    override fun close() {
        runCatching { output.close() }
        file.delete()
    }

    /** Поток чтения записей со своей позицией в файле. */
    private class PositionedInput(file: File, private val origin: Long) : Closeable {
        // Счётчик стоит над буфером, поэтому считает ровно байты, отданные разбору записей.
        private val counting = CountingInput(
            BufferedInputStream(FileInputStream(file).apply { channel.position(origin) }, IO_BUFFER),
        )
        private val data = DataInputStream(counting)

        val position: Long get() = origin + counting.bytes

        fun skipTo(target: Long) {
            var remaining = target - position
            while (remaining > 0) {
                val skipped = counting.skip(remaining)
                if (skipped <= 0) throw EOFException()
                remaining -= skipped
            }
        }

        fun readRecord(): Pair<Int, SheetRow> {
            val index = data.readVarInt()
            val cellCount = data.readVarInt()
            val cells = ArrayList<SheetCell>(cellCount)
            repeat(cellCount) {
                val column = data.readVarInt()
                val style = data.readVarInt()
                val kind = CellKind.entries.getOrElse(data.readUnsignedByte()) { CellKind.TEXT }
                cells += SheetCell(column, data.readUTF(), kind, style)
            }
            return index to SheetRow(cells)
        }

        override fun close() = data.close()
    }

    /**
     * Поиск по записям прямо в байтах файла: на листе в миллион строк создание [SheetCell] и строки
     * на каждую ячейку стоило больше самого сравнения.
     */
    private class RecordSearcher(file: File, origin: Long, private val matcher: CellMatcher) : Closeable {
        private val input = FileInputStream(file).apply { channel.position(origin) }
        private val buffer = ByteArray(SEARCH_BUFFER)
        /** Смещение в файле байта `buffer[0]`. */
        private var bufferOrigin = origin
        private var pos = 0
        private var end = 0

        private val position: Long get() = bufferOrigin + pos

        /**
         * Ищет, пока не наберётся [limit] совпадений в [matches] или не начнётся запись после [untilOffset].
         * Возвращает смещение после последней прочитанной целиком записи.
         */
        fun search(matches: MutableList<CellPosition>, limit: Int, untilOffset: Long, isActive: () -> Boolean): Long {
            var next = position
            var records = 0
            try {
                while (matches.size < limit && position < untilOffset) {
                    if (records++ % ACTIVE_CHECK_RECORDS == 0 && !isActive()) break
                    val row = readVarInt()
                    repeat(readVarInt()) {
                        val column = readVarInt()
                        readVarInt() // стиль
                        ensure(3)
                        pos++ // тип ячейки
                        val length = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
                        pos += 2
                        ensure(length)
                        if (matches.size < limit && matcher.matches(buffer, pos, length)) matches += CellPosition(row, column)
                        pos += length
                    }
                    next = position
                }
            } catch (e: EOFException) {
                // Конец записанной части файла: недописанная запись найдётся при следующем поиске.
            }
            return next
        }

        private fun readVarInt(): Int {
            var result = 0
            var shift = 0
            while (true) {
                ensure(1)
                val byte = buffer[pos++].toInt() and 0xFF
                result = result or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (shift > 28) throw java.io.IOException("Повреждён кэш листа")
            }
        }

        /** Дочитывает буфер, чтобы с [pos] было доступно [count] байт. */
        private fun ensure(count: Int) {
            if (end - pos >= count) return
            buffer.copyInto(buffer, 0, pos, end)
            bufferOrigin += pos
            end -= pos
            pos = 0
            while (end < count) {
                val read = input.read(buffer, end, buffer.size - end)
                if (read <= 0) throw EOFException()
                end += read
            }
        }

        override fun close() = input.close()
    }

    /**
     * Сравнение текста ячейки в modified UTF-8 с запросом без учёта регистра, как `contains(ignoreCase = true)`.
     * Ячейки из одних ASCII-символов (числа, коды, латиница) сравниваются побайтово, остальные декодируются.
     */
    private class CellMatcher(private val query: String) {
        /** Запрос в нижнем регистре ASCII; `null`, если в нём есть другие символы. */
        private val asciiQuery: ByteArray? =
            if (query.all { it.code < 0x80 }) ByteArray(query.length) { asciiLower(query[it].code.toByte()) } else null

        /**
         * Запрос не из ASCII не найдётся в ASCII-тексте, если ни один его символ не совпадает
         * без учёта регистра с ASCII-символом (как знак кельвина `K` с `k`).
         */
        private val skipAsciiText: Boolean = asciiQuery == null && query.none { char ->
            char.code >= 0x80 && (char.uppercaseChar().code < 0x80 || char.lowercaseChar().code < 0x80 ||
                char.uppercaseChar().lowercaseChar().code < 0x80)
        }

        private val text = StringBuilder()

        fun matches(bytes: ByteArray, offset: Int, length: Int): Boolean {
            val end = offset + length
            var ascii = true
            for (i in offset until end) {
                if (bytes[i] < 0) {
                    ascii = false
                    break
                }
            }
            if (ascii) {
                if (asciiQuery != null) return asciiContains(bytes, offset, end, asciiQuery)
                if (skipAsciiText) return false
            }
            decode(bytes, offset, end)
            return text.contains(query, ignoreCase = true)
        }

        private fun asciiContains(bytes: ByteArray, offset: Int, end: Int, pattern: ByteArray): Boolean {
            val last = end - pattern.size
            val first = pattern[0]
            for (start in offset..last) {
                if (asciiLower(bytes[start]) != first) continue
                var j = 1
                while (j < pattern.size && asciiLower(bytes[start + j]) == pattern[j]) j++
                if (j == pattern.size) return true
            }
            return false
        }

        /** Modified UTF-8 из [DataOutputStream.writeUTF]: символы по 1–3 байта, суррогаты — отдельно. */
        private fun decode(bytes: ByteArray, offset: Int, end: Int) {
            text.setLength(0)
            var i = offset
            while (i < end) {
                val byte = bytes[i].toInt() and 0xFF
                when {
                    byte < 0x80 -> {
                        text.append(byte.toChar())
                        i++
                    }
                    byte < 0xE0 -> {
                        text.append((((byte and 0x1F) shl 6) or (bytes[i + 1].toInt() and 0x3F)).toChar())
                        i += 2
                    }
                    else -> {
                        val code = ((byte and 0x0F) shl 12) or ((bytes[i + 1].toInt() and 0x3F) shl 6) or
                            (bytes[i + 2].toInt() and 0x3F)
                        text.append(code.toChar())
                        i += 3
                    }
                }
            }
        }

        private fun asciiLower(byte: Byte): Byte = if (byte >= UPPER_A && byte <= UPPER_Z) (byte + CASE_SHIFT).toByte() else byte

        private companion object {
            const val UPPER_A = 'A'.code.toByte()
            const val UPPER_Z = 'Z'.code.toByte()
            const val CASE_SHIFT = 'a'.code - 'A'.code
        }
    }

    /** Считает байты, отданные читателю, — это позиция в файле относительно начала чтения. */
    private class CountingInput(private val source: InputStream) : InputStream() {
        var bytes = 0L
            private set

        override fun read(): Int = source.read().also { if (it >= 0) bytes++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            source.read(buffer, offset, length).also { if (it > 0) bytes += it }

        override fun skip(count: Long): Long = source.skip(count).also { if (it > 0) bytes += it }

        override fun close() = source.close()
    }

    private companion object {
        const val IO_BUFFER = 64 * 1024
        /** Больше самого длинного поля записи (строка modified UTF-8 — до 65 537 байт). */
        const val SEARCH_BUFFER = 256 * 1024
        /** Как часто поиск проверяет отмену. */
        const val ACTIVE_CHECK_RECORDS = 256
        const val INITIAL_ROWS = 1024
        const val FIRST_PUBLISH_ROWS = 100
        const val PUBLISH_ROWS = 5_000
        const val AUTO_WIDTH_PADDING = 1
        const val MIN_AUTO_WIDTH = 4f
        const val MAX_AUTO_WIDTH = 40f
        /** Длинный текст в ячейке обрезается: в сетке всё равно видна одна строка. */
        const val MAX_CELL_CHARS = 2_000
        const val MAX_MERGE_ENTRIES = 200_000
    }
}

private fun DataOutputStream.writeVarInt(value: Int) {
    var rest = value
    while (rest and 0x7F.inv() != 0) {
        writeByte((rest and 0x7F) or 0x80)
        rest = rest ushr 7
    }
    writeByte(rest)
}

private fun DataInputStream.readVarInt(): Int {
    var result = 0
    var shift = 0
    while (true) {
        val byte = readUnsignedByte()
        result = result or ((byte and 0x7F) shl shift)
        if (byte and 0x80 == 0) return result
        shift += 7
        if (shift > 28) throw java.io.IOException("Повреждён кэш листа")
    }
}

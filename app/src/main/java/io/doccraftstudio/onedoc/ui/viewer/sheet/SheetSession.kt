package io.doccraftstudio.onedoc.ui.viewer.sheet

import io.doccraftstudio.onedoc.domain.model.ReadingPosition
import io.doccraftstudio.onedoc.reader.sheet.CellPosition
import io.doccraftstudio.onedoc.reader.sheet.CellStyle
import io.doccraftstudio.onedoc.reader.sheet.SheetCell
import io.doccraftstudio.onedoc.reader.sheet.SheetRow
import io.doccraftstudio.onedoc.reader.sheet.SheetRowStore
import io.doccraftstudio.onedoc.reader.sheet.SheetSink
import io.doccraftstudio.onedoc.reader.sheet.SheetStoreState
import io.doccraftstudio.onedoc.reader.sheet.SpreadsheetSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class SheetSearchState(
    val query: String = "",
    val sheet: Int = 0,
    val matches: List<CellPosition> = emptyList(),
    val currentIndex: Int = -1,
    val inProgress: Boolean = false,
) {
    val current: CellPosition? get() = matches.getOrNull(currentIndex)
    val isActive: Boolean get() = query.isNotEmpty()

    /** Совпадения для подсветки: проверка ячейки за O(1). */
    val matchKeys: Set<Long> by lazy { matches.mapTo(HashSet(matches.size)) { cellKey(it.row, it.column) } }
}

fun cellKey(row: Int, column: Int): Long = (row.toLong() shl 32) or column.toLong()

/**
 * Разобранный (или разбираемый) лист: строки лежат в [SheetRowStore] на диске,
 * в памяти — только окна строк вокруг видимой области (LRU по [CHUNK_ROWS] строк).
 */
class SheetData internal constructor(val index: Int, internal val store: SheetRowStore) {
    val state: StateFlow<SheetStoreState> get() = store.state

    private class Chunk(val rows: List<SheetRow?>, val coveredRows: Int)

    private val chunks = object : LinkedHashMap<Int, Chunk>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Chunk>?) = size > MAX_CHUNKS
    }

    private val _revision = MutableStateFlow(0)

    /** Меняется при каждой загрузке окна строк — сигнал перерисовать видимые строки. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun cachedRow(row: Int): SheetRow? = synchronized(chunks) {
        chunks[row / CHUNK_ROWS]?.rows?.getOrNull(row % CHUNK_ROWS)
    }

    /**
     * Подгружает строки [rows] с диска. Окно, прочитанное до того, как парсер дописал в него строки,
     * перечитывается.
     */
    suspend fun loadRows(rows: IntRange) {
        if (rows.isEmpty()) return
        val rowCount = state.value.rowCount
        for (chunkIndex in rows.first.coerceAtLeast(0) / CHUNK_ROWS..rows.last / CHUNK_ROWS) {
            val start = chunkIndex * CHUNK_ROWS
            if (start >= rowCount) break
            val needed = minOf(start + CHUNK_ROWS, rowCount)
            val existing = synchronized(chunks) { chunks[chunkIndex] }
            if (existing != null && existing.coveredRows >= needed) continue
            val loaded = withContext(Dispatchers.IO) { store.readRows(start, CHUNK_ROWS) }
            synchronized(chunks) { chunks[chunkIndex] = Chunk(loaded, start + loaded.size) }
            _revision.update { it + 1 }
        }
    }

    companion object {
        const val CHUNK_ROWS = 64
        /** ~2500 строк в памяти: несколько экранов вокруг видимой области. */
        private const val MAX_CHUNKS = 40
    }
}

/**
 * Открытая таблица и всё, что должно пережить поворот экрана: разобранные листы, текущий лист,
 * последние позиции на каждом листе и поиск. Принадлежит [io.doccraftstudio.onedoc.ui.viewer.ViewerViewModel].
 *
 * Лист разбирается в фоне при первом показе; строки появляются в сетке по мере разбора.
 *
 * @param createStore новое хранилище строк для листа (временный файл).
 * @param onNavigate переход к ячейке (совпадение поиска): позиция — левая верхняя видимая ячейка.
 * @param closeScope область, в которой после ухода с экрана дожидаемся остановки разбора и удаляем кэш.
 */
class SheetSession(
    private val source: SpreadsheetSource,
    private val createStore: () -> SheetRowStore,
    parentScope: CoroutineScope,
    private val closeScope: CoroutineScope,
    initialPosition: ReadingPosition,
    private val onNavigate: (ReadingPosition) -> Unit,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    val sheetNames: List<String> = source.sheetNames
    val styles: List<CellStyle> = source.styles

    private val sheets = arrayOfNulls<SheetData>(sheetNames.size)

    private val _currentSheet = MutableStateFlow(initialPosition.sheet.coerceIn(0, sheetNames.lastIndex.coerceAtLeast(0)))
    val currentSheet: StateFlow<Int> = _currentSheet.asStateFlow()

    /** Последняя позиция на каждом листе: при возврате на лист открываем то же место. */
    private val positions = HashMap<Int, ReadingPosition>().apply {
        put(_currentSheet.value, initialPosition.copy(sheet = _currentSheet.value))
    }

    private val _search = MutableStateFlow(SheetSearchState())
    val search: StateFlow<SheetSearchState> = _search.asStateFlow()
    private var searchJob: Job? = null

    /** Лист [index]; при первом обращении запускается его разбор. */
    fun sheet(index: Int): SheetData = synchronized(sheets) {
        sheets[index] ?: SheetData(index, createStore()).also {
            sheets[index] = it
            startParsing(it)
        }
    }

    private fun startParsing(data: SheetData) {
        scope.launch(Dispatchers.IO) {
            val job = coroutineContext.job
            try {
                source.readSheet(data.index, CancellableSink(data.store, job))
                data.store.finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Повреждённый лист: показываем то, что успели прочитать.
                data.store.fail()
            } catch (e: OutOfMemoryError) {
                data.store.fail()
            }
        }
    }

    fun selectSheet(index: Int) {
        if (index !in sheetNames.indices || index == _currentSheet.value) return
        clearSearch()
        _currentSheet.value = index
    }

    /** Где открыть лист: последняя позиция на нём или начало с текущим масштабом. */
    fun positionFor(sheet: Int): ReadingPosition =
        positions[sheet] ?: ReadingPosition(sheet = sheet, zoom = positions[_currentSheet.value]?.zoom ?: 1f)

    fun rememberPosition(position: ReadingPosition) {
        positions[position.sheet] = position
    }

    /** Текст строки для заголовка закладки (строка видна на экране, значит, она в кэше). */
    fun rowText(sheet: Int, row: Int): String? = synchronized(sheets) { sheets.getOrNull(sheet) }?.cachedRow(row)?.plainText

    /**
     * Ищет по ячейкам текущего листа. Если лист ещё разбирается, ищет по уже прочитанным строкам
     * и дополняет результат по мере разбора. Первым выбирается совпадение на текущей строке или ниже,
     * иначе (когда лист прочитан целиком) — первое на листе.
     */
    fun search(query: String) {
        val trimmed = query.trim()
        searchJob?.cancel()
        if (trimmed.isEmpty()) {
            _search.value = SheetSearchState()
            return
        }
        val sheetIndex = _currentSheet.value
        val data = sheet(sheetIndex)
        val fromRow = positions[sheetIndex]?.index ?: 0
        _search.value = SheetSearchState(query = trimmed, sheet = sheetIndex, inProgress = true)
        searchJob = scope.launch {
            val job = coroutineContext.job
            val matches = ArrayList<CellPosition>()
            var offset = 0L
            try {
                while (true) {
                    val published = data.state.value
                    // Кусками, чтобы первые совпадения показывались, не дожидаясь прохода по всему листу.
                    val until = minOf(published.publishedBytes, offset + SEARCH_CHUNK_BYTES)
                    val chunk = withContext(Dispatchers.IO) {
                        data.store.searchRange(trimmed, MAX_MATCHES - matches.size, offset, until) { job.isActive }
                    }
                    matches += chunk.matches
                    offset = chunk.nextOffset
                    val done = (published.complete && offset >= published.publishedBytes) || matches.size >= MAX_MATCHES
                    val previous = _search.value
                    val current = when {
                        previous.currentIndex >= 0 -> previous.currentIndex
                        else -> matches.indexOfFirst { it.row >= fromRow }.takeIf { it >= 0 }
                            ?: if (done && matches.isNotEmpty()) 0 else -1
                    }
                    if (done || chunk.matches.isNotEmpty() || current != previous.currentIndex) {
                        _search.value = SheetSearchState(trimmed, sheetIndex, matches.toList(), current, inProgress = !done)
                    }
                    if (previous.currentIndex < 0 && current >= 0) navigateToCurrent()
                    if (done) break
                    // Всё опубликованное просмотрено — ждём, пока парсер допишет новые строки.
                    if (offset >= published.publishedBytes) data.state.first { it.complete || it.publishedBytes > offset }
                }
            } catch (e: IOException) {
                _search.update { it.copy(inProgress = false) }
            }
        }
    }

    fun nextMatch() = moveMatch(+1)

    fun previousMatch() = moveMatch(-1)

    private fun moveMatch(step: Int) {
        val state = _search.value
        if (state.matches.isEmpty()) return
        _search.value = state.copy(currentIndex = (state.currentIndex + step).mod(state.matches.size))
        navigateToCurrent()
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = SheetSearchState()
    }

    private fun navigateToCurrent() {
        val state = _search.value
        val match = state.current ?: return
        // Пара строк и колонка сверху-слева от совпадения, чтобы оно не прилипало к краю.
        onNavigate(
            ReadingPosition(
                index = (match.row - MATCH_ROW_MARGIN).coerceAtLeast(0),
                sheet = state.sheet,
                column = (match.column - 1).coerceAtLeast(0),
                zoom = positions[state.sheet]?.zoom ?: 1f,
            ),
        )
    }

    fun close() {
        val parsing = scope.coroutineContext.job
        closeScope.launch(Dispatchers.IO) {
            // Разбор проверяет отмену на каждой строке; ждём его остановки, прежде чем закрыть файлы.
            parsing.cancelAndJoin()
            synchronized(sheets) { sheets.forEach { it?.store?.close() } }
            runCatching { source.close() }
        }
    }

    /** Прерывает блокирующий разбор листа при отмене корутины. */
    private class CancellableSink(private val target: SheetRowStore, private val job: Job) : SheetSink by target {
        override fun row(index: Int, cells: List<SheetCell>) {
            job.ensureActive()
            target.row(index, cells)
        }
    }

    companion object {
        const val MAX_MATCHES = 10_000
        /** Сколько байт кэша листа просматривать между обновлениями результатов (~300 тыс. ячеек). */
        private const val SEARCH_CHUNK_BYTES = 4L * 1024 * 1024
        private const val MATCH_ROW_MARGIN = 2
    }
}

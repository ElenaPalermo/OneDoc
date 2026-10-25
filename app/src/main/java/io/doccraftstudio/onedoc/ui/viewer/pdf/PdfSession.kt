package io.doccraftstudio.onedoc.ui.viewer.pdf

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.LruCache
import io.doccraftstudio.onedoc.domain.model.ReadingPosition
import io.doccraftstudio.onedoc.reader.pdf.PageSize
import io.doccraftstudio.onedoc.reader.pdf.PdfDocumentHandle
import io.doccraftstudio.onedoc.reader.pdf.PdfSearchMatch
import io.doccraftstudio.onedoc.reader.TocEntry
import io.doccraftstudio.onedoc.reader.pdf.searchPages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.io.IOException
import kotlin.math.roundToInt

data class PdfSearchState(
    val query: String = "",
    val matches: List<PdfSearchMatch> = emptyList(),
    val currentIndex: Int = -1,
    val inProgress: Boolean = false,
) {
    val current: PdfSearchMatch? get() = matches.getOrNull(currentIndex)
    val isActive: Boolean get() = query.isNotEmpty()
}

/**
 * Открытый PDF и всё, что должно пережить поворот экрана: размеры страниц, оглавление,
 * поиск и кэш отрендеренных страниц. Принадлежит [io.doccraftstudio.onedoc.ui.viewer.ViewerViewModel].
 *
 * Все обращения к документу идут через [mutex]: закрытие не может прийтись на середину рендера.
 *
 * @param onNavigate переход к позиции (совпадение поиска); offset — в 1/10000 высоты страницы.
 * @param closeScope область, в которой документ закрывается после ухода с экрана.
 */
class PdfSession(
    private val handle: PdfDocumentHandle,
    parentScope: CoroutineScope,
    private val closeScope: CoroutineScope,
    private val onNavigate: (ReadingPosition) -> Unit,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutex = Mutex()
    private var closed = false

    val pageCount: Int get() = handle.pageCount

    private val _pageSizes = MutableStateFlow<List<PageSize>>(emptyList())

    /** Пока размеры не прочитаны, для всех страниц используется размер первой (обычно они одинаковые). */
    val pageSizes: StateFlow<List<PageSize>> = _pageSizes.asStateFlow()

    private val _tableOfContents = MutableStateFlow<List<TocEntry>?>(null)
    val tableOfContents: StateFlow<List<TocEntry>?> = _tableOfContents.asStateFlow()

    private val _search = MutableStateFlow(PdfSearchState())
    val search: StateFlow<PdfSearchState> = _search.asStateFlow()
    private var searchJob: Job? = null

    /** Кэш базовых битмапов страниц; создаётся при первом рендере (размер — 1/8 доступной куче памяти). */
    private val bitmapCache by lazy {
        val maxBytes = (Runtime.getRuntime().maxMemory() / BITMAP_CACHE_FRACTION).toInt()
        object : LruCache<String, Bitmap>(maxBytes) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    }

    /** Читает первую страницу (нужна для раскладки), остальные размеры и оглавление — в фоне. */
    suspend fun initialize() {
        val first = runCatching { op { handle.pageSize(0) } }.getOrDefault(PageSize.A4)
        _pageSizes.value = List(pageCount) { first }
        scope.launch {
            _tableOfContents.value = runCatching { op { handle.tableOfContents() } }.getOrDefault(emptyList())
        }
        scope.launch { loadPageSizes() }
    }

    private suspend fun loadPageSizes() {
        val sizes = _pageSizes.value.toMutableList()
        for (index in 1 until pageCount) {
            sizes[index] = runCatching { op { handle.pageSize(index) } }.getOrDefault(sizes[index])
            if (index % PAGE_SIZE_BATCH == 0 || index == pageCount - 1) _pageSizes.value = sizes.toList()
        }
    }

    /** Страница целиком шириной [widthPx]; результат кэшируется. */
    suspend fun renderPage(pageIndex: Int, widthPx: Int): Bitmap {
        val key = "$pageIndex:$widthPx"
        bitmapCache.get(key)?.let { return it }
        val heightPx = (widthPx * pageAspect(pageIndex)).toInt().coerceAtLeast(1)
        val bitmap = op { handle.render(pageIndex, widthPx, heightPx) }
        bitmapCache.put(key, bitmap)
        return bitmap
    }

    /** Любой ранее отрисованный вариант страницы — показывается, пока рендерится нужное разрешение. */
    fun cachedPage(pageIndex: Int): Bitmap? =
        bitmapCache.snapshot().entries.firstOrNull { it.key.startsWith("$pageIndex:") }?.value

    /** Фрагмент страницы в полном разрешении для сильного увеличения; не кэшируется. */
    suspend fun renderRegion(
        pageIndex: Int,
        pageWidthPx: Int,
        pageHeightPx: Int,
        region: Rect,
    ): Bitmap = op {
        handle.render(pageIndex, pageWidthPx, pageHeightPx, region.width(), region.height(), region.left, region.top)
    }

    fun pageAspect(pageIndex: Int): Float =
        _pageSizes.value.getOrNull(pageIndex)?.aspectRatio ?: PageSize.A4.aspectRatio

    /** Запускает поиск; первым выбирается совпадение на странице [fromPage] или после неё. */
    fun search(query: String, fromPage: Int) {
        val trimmed = query.trim()
        searchJob?.cancel()
        if (trimmed.isEmpty()) {
            _search.value = PdfSearchState()
            return
        }
        _search.value = PdfSearchState(query = trimmed, inProgress = true)
        searchJob = scope.launch {
            try {
                handle.searchPages(trimmed) { block -> op(block) }.collect { pageMatches ->
                    _search.update { state ->
                        val matches = state.matches + pageMatches
                        val selectNow = state.currentIndex < 0 && pageMatches.first().pageIndex >= fromPage
                        state.copy(matches = matches, currentIndex = if (selectNow) state.matches.size else state.currentIndex)
                    }
                    if (_search.value.current in pageMatches) navigateToCurrent()
                    yield()
                }
                // Ниже текущей страницы ничего не нашлось — начинаем сначала документа.
                if (_search.value.currentIndex < 0 && _search.value.matches.isNotEmpty()) {
                    _search.update { it.copy(currentIndex = 0) }
                    navigateToCurrent()
                }
            } catch (e: IOException) {
                // Страница без текстового слоя или повреждённая — показываем то, что успели найти.
            } finally {
                _search.update { it.copy(inProgress = false) }
            }
        }
    }

    fun nextMatch() = moveMatch(+1)

    fun previousMatch() = moveMatch(-1)

    private fun moveMatch(step: Int) {
        val state = _search.value
        if (state.matches.isEmpty()) return
        val next = (state.currentIndex + step).mod(state.matches.size)
        _search.value = state.copy(currentIndex = next)
        navigateToCurrent()
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = PdfSearchState()
    }

    private fun navigateToCurrent() {
        val match = _search.value.current ?: return
        // Небольшой отступ сверху, чтобы найденная строка не прилипала к краю верхней панели.
        val top = (match.rects.minOf { it.top } - MATCH_TOP_MARGIN).coerceAtLeast(0f)
        onNavigate(ReadingPosition(index = match.pageIndex, offset = (top * OFFSET_SCALE).roundToInt()))
    }

    private suspend fun <T> op(block: suspend () -> T): T = mutex.withLock {
        if (closed) throw IOException("Документ уже закрыт")
        block()
    }

    fun close() {
        scope.cancel()
        closeScope.launch {
            mutex.withLock {
                closed = true
                handle.close()
            }
        }
    }

    companion object {
        /** Единица смещения внутри страницы в [ReadingPosition.offset] для PDF: доли высоты страницы. */
        const val OFFSET_SCALE = 10_000
        private const val MATCH_TOP_MARGIN = 0.03f
        private const val BITMAP_CACHE_FRACTION = 8
        private const val PAGE_SIZE_BATCH = 32
    }
}

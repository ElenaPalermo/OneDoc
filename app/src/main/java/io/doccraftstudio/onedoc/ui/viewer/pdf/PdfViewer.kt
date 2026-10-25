package io.doccraftstudio.onedoc.ui.viewer.pdf

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.doccraftstudio.onedoc.domain.model.ReaderTheme
import io.doccraftstudio.onedoc.domain.model.ReadingPosition
import io.doccraftstudio.onedoc.reader.pdf.PageSize
import io.doccraftstudio.onedoc.reader.pdf.PdfSearchMatch
import io.doccraftstudio.onedoc.ui.viewer.common.LocalReaderPalette
import io.doccraftstudio.onedoc.ui.viewer.common.ReaderPalette
import io.doccraftstudio.onedoc.ui.viewer.common.palette
import io.doccraftstudio.onedoc.ui.viewer.common.pinchToZoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f

/** Начиная с этого масштаба видимые фрагменты дорисовываются в полном разрешении. */
private const val DETAIL_ZOOM_THRESHOLD = 1.05f
private const val DETAIL_DELAY_MS = 150L
private val PageGap = 8.dp
private val SearchHighlight = Color(0x59FFD600)
private val CurrentSearchHighlight = Color(0x80FF6D00)

/** Фрагмент увеличенной страницы в полном разрешении; [region] — в пикселях страницы при текущем масштабе. */
@Immutable
private class DetailPatch(val region: Rect, val bitmap: Bitmap)

/** Точка, которая должна остаться под пальцами после применения нового масштаба. */
private class ZoomAnchor(
    val factor: Float,
    val centroid: Offset,
    val scrollX: Int,
    val itemIndex: Int,
    val itemScrollAfter: Float,
)

/**
 * Просмотр PDF: страницы друг под другом в `LazyColumn`, ширина по экрану, pinch-to-zoom до 5×
 * с горизонтальной прокруткой. Страницы рендерятся по ширине экрана (кэшируются в [PdfSession]),
 * при увеличении видимая часть дорисовывается в полном разрешении после остановки прокрутки.
 *
 * Позиция: индекс страницы + смещение в 1/[PdfSession.OFFSET_SCALE] её высоты + масштаб.
 */
@Composable
fun PdfViewer(
    session: PdfSession,
    initialPosition: ReadingPosition,
    navigationRequests: Flow<ReadingPosition>,
    contentPadding: PaddingValues,
    onPositionChanged: (ReadingPosition) -> Unit,
) {
    val pageSizes by session.pageSizes.collectAsStateWithLifecycle()
    val search by session.search.collectAsStateWithLifecycle()
    val palette = LocalReaderPalette.current
    val colorFilter = remember(palette) { palette.pdfColorFilter() }
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(palette.chrome)
            .padding(
                start = contentPadding.calculateStartPadding(layoutDirection),
                end = contentPadding.calculateEndPadding(layoutDirection),
            )
            .clipToBounds(),
    ) {
        val viewportWidthPx = constraints.maxWidth
        val viewportHeightPx = constraints.maxHeight
        var zoom by rememberSaveable { mutableFloatStateOf(initialPosition.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)) }
        var gestureScale by remember { mutableFloatStateOf(1f) }
        var gestureCentroid by remember { mutableStateOf(Offset.Zero) }
        var pendingAnchor by remember { mutableStateOf<ZoomAnchor?>(null) }
        var patches by remember { mutableStateOf<Map<Int, DetailPatch>>(emptyMap()) }

        // Функции читают состояния (zoom, pageSizes) при каждом вызове, поэтому актуальны и внутри эффектов.
        val contentWidthPx = { (viewportWidthPx * zoom).roundToInt() }
        val pageHeightPx = { index: Int ->
            val aspect = pageSizes.getOrNull(index)?.aspectRatio ?: PageSize.A4.aspectRatio
            (contentWidthPx() * aspect).roundToInt().coerceAtLeast(1)
        }
        val gapPx = with(density) { PageGap.roundToPx() }

        val lastPage = (session.pageCount - 1).coerceAtLeast(0)
        // Смещение сохраняется долей высоты страницы, а не в пикселях: после поворота экрана страница
        // другой высоты, и пиксельное смещение уводило бы к другому месту страницы.
        val listState = rememberSaveable(
            saver = Saver(
                save = { state: LazyListState ->
                    val index = state.firstVisibleItemIndex
                    listOf(index, state.firstVisibleItemScrollOffset.toFloat() / pageHeightPx(index))
                },
                restore = { saved ->
                    val index = (saved[0] as Int).coerceIn(0, lastPage)
                    LazyListState(index, ((saved[1] as Float) * pageHeightPx(index)).roundToInt())
                },
            ),
        ) {
            LazyListState(
                firstVisibleItemIndex = initialPosition.index.coerceIn(0, lastPage),
                firstVisibleItemScrollOffset =
                    (initialPosition.offset / PdfSession.OFFSET_SCALE.toFloat() * pageHeightPx(initialPosition.index)).roundToInt(),
            )
        }
        val horizontalScroll = rememberScrollState()

        LaunchedEffect(listState, viewportWidthPx) {
            snapshotFlow {
                val index = listState.firstVisibleItemIndex
                val fraction = listState.firstVisibleItemScrollOffset.toFloat() / pageHeightPx(index)
                ReadingPosition(
                    index = index,
                    offset = (fraction * PdfSession.OFFSET_SCALE).roundToInt().coerceIn(0, PdfSession.OFFSET_SCALE),
                    zoom = zoom,
                )
            }.collect { currentOnPositionChanged(it) }
        }

        LaunchedEffect(listState, navigationRequests) {
            navigationRequests.collect { target ->
                val index = target.index.coerceIn(0, lastPage)
                val offset = (target.offset / PdfSession.OFFSET_SCALE.toFloat() * pageHeightPx(index)).roundToInt()
                listState.scrollToItem(index, offset)
            }
        }

        // При увеличении подводим совпадение поиска и по горизонтали.
        LaunchedEffect(search.current, zoom) {
            val match = search.current ?: return@LaunchedEffect
            if (zoom <= MIN_ZOOM) return@LaunchedEffect
            val left = match.rects.minOf { it.left } * contentWidthPx() - viewportWidthPx * 0.1f
            horizontalScroll.animateScrollTo(left.roundToInt().coerceIn(0, horizontalScroll.maxValue))
        }

        // Новый масштаб применён к раскладке — сдвигаем прокрутку, чтобы точка под пальцами не убежала.
        LaunchedEffect(pendingAnchor) {
            val anchor = pendingAnchor ?: return@LaunchedEffect
            withFrameNanos { }
            val newScrollX = (anchor.scrollX + anchor.centroid.x) * anchor.factor - anchor.centroid.x
            horizontalScroll.scrollTo(newScrollX.roundToInt().coerceIn(0, horizontalScroll.maxValue))
            listState.scrollToItem(anchor.itemIndex)
            listState.scrollBy(anchor.itemScrollAfter)
            pendingAnchor = null
        }

        LaunchedEffect(zoom, viewportWidthPx, viewportHeightPx) {
            patches = emptyMap()
            if (zoom < DETAIL_ZOOM_THRESHOLD) return@LaunchedEffect
            snapshotFlow {
                if (listState.isScrollInProgress || horizontalScroll.isScrollInProgress) {
                    null
                } else {
                    Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, horizontalScroll.value)
                }
            }.filterNotNull().distinctUntilChanged().collectLatest {
                delay(DETAIL_DELAY_MS)
                patches = renderVisibleDetails(
                    session = session,
                    listState = listState,
                    scrollX = horizontalScroll.value,
                    pageWidthPx = contentWidthPx(),
                    pageHeightPx = pageHeightPx,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                )
            }
        }

        val matchesByPage = remember(search.matches) { search.matches.groupBy { it.pageIndex } }
        val widthDp = with(density) { contentWidthPx().toDp() }

        Box(
            Modifier
                .fillMaxSize()
                .pinchToZoom(
                    onZoom = { factor, centroid ->
                        gestureScale = (gestureScale * factor).coerceIn(MIN_ZOOM / zoom, MAX_ZOOM / zoom)
                        gestureCentroid = centroid
                    },
                    onZoomEnd = {
                        val newZoom = (zoom * gestureScale).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        val factor = newZoom / zoom
                        val info = listState.layoutInfo
                        val first = info.visibleItemsInfo.firstOrNull()
                        if (first != null && factor != 1f) {
                            val itemTop = first.offset + info.beforeContentPadding
                            val distanceInItem = gestureCentroid.y - itemTop
                            pendingAnchor = ZoomAnchor(
                                factor = factor,
                                centroid = gestureCentroid,
                                scrollX = horizontalScroll.value,
                                itemIndex = first.index,
                                itemScrollAfter = info.beforeContentPadding - gestureCentroid.y + distanceInItem * factor,
                            )
                        }
                        zoom = newZoom
                        gestureScale = 1f
                    },
                )
                .graphicsLayer {
                    scaleX = gestureScale
                    scaleY = gestureScale
                    transformOrigin = TransformOrigin(
                        pivotFractionX = if (size.width > 0) gestureCentroid.x / size.width else 0.5f,
                        pivotFractionY = if (size.height > 0) gestureCentroid.y / size.height else 0.5f,
                    )
                }
                .horizontalScroll(horizontalScroll, enabled = zoom > MIN_ZOOM),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(widthDp).fillMaxHeight(),
                contentPadding = PaddingValues(
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
            ) {
                items(count = session.pageCount, key = { it }) { index ->
                    PdfPage(
                        session = session,
                        pageIndex = index,
                        pageWidthPx = contentWidthPx(),
                        pageHeightPx = pageHeightPx(index),
                        // Базовый рендер всегда по ширине экрана: одинаковый ключ кэша при любом масштабе.
                        baseWidthPx = viewportWidthPx,
                        gapPx = gapPx,
                        pageColor = palette.page,
                        colorFilter = colorFilter,
                        matches = matchesByPage[index].orEmpty(),
                        currentMatch = search.current,
                        patch = patches[index],
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPage(
    session: PdfSession,
    pageIndex: Int,
    pageWidthPx: Int,
    pageHeightPx: Int,
    baseWidthPx: Int,
    gapPx: Int,
    pageColor: Color,
    colorFilter: ColorFilter?,
    matches: List<PdfSearchMatch>,
    currentMatch: PdfSearchMatch?,
    patch: DetailPatch?,
) {
    val density = LocalDensity.current
    val bitmap by produceState(session.cachedPage(pageIndex), pageIndex, baseWidthPx) {
        value = try {
            session.renderPage(pageIndex, baseWidthPx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            value
        }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }

    with(density) {
        Box(
            Modifier
                .padding(bottom = gapPx.toDp())
                .size(pageWidthPx.toDp(), pageHeightPx.toDp())
                .background(pageColor),
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    colorFilter = colorFilter,
                )
            }
            if (patch != null) {
                val patchImage = remember(patch) { patch.bitmap.asImageBitmap() }
                Image(
                    bitmap = patchImage,
                    contentDescription = null,
                    modifier = Modifier
                        .offset { IntOffset(patch.region.left, patch.region.top) }
                        .size(patch.region.width().toDp(), patch.region.height().toDp()),
                    contentScale = ContentScale.FillBounds,
                    colorFilter = colorFilter,
                )
            }
            if (matches.isNotEmpty()) {
                Canvas(Modifier.fillMaxSize()) {
                    for (match in matches) {
                        val color = if (match == currentMatch) CurrentSearchHighlight else SearchHighlight
                        for (rect in match.rects) {
                            drawRect(
                                color = color,
                                topLeft = Offset(rect.left * size.width, rect.top * size.height),
                                size = Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Рендерит видимую часть каждой видимой страницы в полном разрешении текущего масштаба. */
private suspend fun renderVisibleDetails(
    session: PdfSession,
    listState: LazyListState,
    scrollX: Int,
    pageWidthPx: Int,
    pageHeightPx: (Int) -> Int,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
): Map<Int, DetailPatch> {
    val info = listState.layoutInfo
    val result = mutableMapOf<Int, DetailPatch>()
    for (item in info.visibleItemsInfo) {
        val pageHeight = pageHeightPx(item.index)
        val top = item.offset + info.beforeContentPadding
        val region = Rect(
            max(0, scrollX),
            max(0, -top),
            min(pageWidthPx, scrollX + viewportWidthPx),
            min(pageHeight, viewportHeightPx - top),
        )
        if (region.width() <= 0 || region.height() <= 0) continue
        try {
            result[item.index] = DetailPatch(region, session.renderRegion(item.index, pageWidthPx, pageHeight, region))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Нет детализации — останется базовый рендер страницы.
        }
    }
    return result
}

/**
 * Перекраска страниц под тему чтения: белый → цвет страницы, чёрный → цвет текста
 * (в тёмной теме это инверсия). Для светлой темы фильтр не нужен.
 */
private fun ReaderPalette.pdfColorFilter(): ColorFilter? {
    if (this == ReaderTheme.LIGHT.palette()) return null
    fun channel(page: Float, text: Float) = (page - text) to text * 255f
    val (scaleR, offsetR) = channel(page.red, text.red)
    val (scaleG, offsetG) = channel(page.green, text.green)
    val (scaleB, offsetB) = channel(page.blue, text.blue)
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                scaleR, 0f, 0f, 0f, offsetR,
                0f, scaleG, 0f, 0f, offsetG,
                0f, 0f, scaleB, 0f, offsetB,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

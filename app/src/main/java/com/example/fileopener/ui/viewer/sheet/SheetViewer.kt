package com.example.fileopener.ui.viewer.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.fileopener.R
import com.example.fileopener.domain.model.ReadingPosition
import com.example.fileopener.reader.sheet.CellKind
import com.example.fileopener.reader.sheet.CellRange
import com.example.fileopener.reader.sheet.CellStyle
import com.example.fileopener.reader.sheet.HorizontalAlignment
import com.example.fileopener.reader.sheet.SheetCell
import com.example.fileopener.reader.sheet.SheetRow
import com.example.fileopener.reader.sheet.columnName
import com.example.fileopener.ui.viewer.common.LocalReaderPalette
import com.example.fileopener.ui.viewer.common.ReaderPalette
import com.example.fileopener.ui.viewer.common.pinchToZoom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val BASE_FONT_SP = 13f
private const val MIN_ZOOM = 0.4f
private const val MAX_ZOOM = 3f

/** Высота строки и ширина символа — в долях размера шрифта, поэтому зум масштабирует всю сетку. */
private const val ROW_HEIGHT_EM = 2.1f
private const val CHAR_WIDTH_EM = 0.5f
private const val CELL_PADDING_EM = 0.4f
private const val DIGIT_WIDTH_EM = 0.62f

/** Текст «заезжает» на пустые ячейки справа не дальше этого числа колонок. */
private const val MAX_OVERFLOW_COLUMNS = 12
private const val PREFETCH_ROWS = 40
private const val TRAILING_COLUMNS = 3
/** Ширина колонки Excel по умолчанию, в символах. */
private const val DEFAULT_COLUMN_CHARS = 8.43f
private const val MIN_TEXT_CONTRAST = 3.0
private const val NUMBER_OVERFLOW = "###"

/**
 * Просмотр таблиц (XLSX, XLS, CSV): вкладки листов — снаружи, здесь — сетка текущего листа.
 * Переходы на другой лист (закладки) переключают лист; на текущем — прокручивают сетку.
 */
@Composable
fun SheetViewer(
    session: SheetSession,
    freezeHeaderRow: Boolean,
    navigationRequests: Flow<ReadingPosition>,
    contentPadding: PaddingValues,
    onPositionChanged: (ReadingPosition) -> Unit,
) {
    val currentSheet by session.currentSheet.collectAsStateWithLifecycle()
    val search by session.search.collectAsStateWithLifecycle()
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    val jumps = remember(session) { MutableSharedFlow<ReadingPosition>(extraBufferCapacity = 1) }

    LaunchedEffect(session, navigationRequests) {
        navigationRequests.collect { target ->
            val current = session.currentSheet.value
            if (target.sheet != current && target.sheet in session.sheetNames.indices) {
                // Новый лист откроется сразу в нужном месте, масштаб сохраняется.
                session.rememberPosition(target.copy(zoom = session.positionFor(current).zoom))
                session.selectSheet(target.sheet)
            } else {
                jumps.emit(target)
            }
        }
    }

    key(session, currentSheet) {
        SheetGrid(
            data = session.sheet(currentSheet),
            styles = session.styles,
            initialPosition = session.positionFor(currentSheet),
            jumps = jumps,
            freezeHeaderRow = freezeHeaderRow,
            search = search.takeIf { it.isActive && it.sheet == currentSheet },
            contentPadding = contentPadding,
            onPositionChanged = { position ->
                session.rememberPosition(position)
                currentOnPositionChanged(position)
            },
        )
    }
}

/** Оформление стиля ячейки, уже приведённое к теме чтения. */
private class ResolvedStyle(val fill: Color?, val text: TextStyle, val alignment: HorizontalAlignment)

/** Левые границы колонок в пикселях (последний элемент — общая ширина). */
@Stable
private class GridGeometry(private val starts: FloatArray) {
    val columnCount: Int get() = starts.size - 1
    val totalWidth: Float get() = starts.last()

    fun start(column: Int): Float = starts[column.coerceIn(0, columnCount)]

    fun width(first: Int, last: Int): Float = start(last + 1) - start(first)

    /** Колонка, в которую попадает координата [x]. */
    fun columnAt(x: Float): Int {
        if (columnCount == 0) return 0
        var low = 0
        var high = columnCount - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (starts[mid] <= x) low = mid else high = mid - 1
        }
        return low
    }

    fun visibleRange(scrollX: Float, viewportWidth: Float): IntRange =
        if (columnCount == 0) IntRange.EMPTY else columnAt(scrollX)..columnAt(scrollX + viewportWidth)
}

/** Всё, что нужно строке сетки; одно значение вместо десятка параметров. */
@Stable
private class GridContext(
    val geometry: GridGeometry,
    val visibleColumns: State<IntRange>,
    val scrollX: () -> Float,
    val rowHeight: Dp,
    val rowHeaderWidth: Dp,
    val cellPadding: Dp,
    val styles: List<ResolvedStyle>,
    val headerText: TextStyle,
    val palette: ReaderPalette,
    val gridLine: Color,
    val matchColor: Color,
    val currentMatchColor: Color,
)

@Composable
private fun SheetGrid(
    data: SheetData,
    styles: List<CellStyle>,
    initialPosition: ReadingPosition,
    jumps: Flow<ReadingPosition>,
    freezeHeaderRow: Boolean,
    search: SheetSearchState?,
    contentPadding: PaddingValues,
    onPositionChanged: (ReadingPosition) -> Unit,
) {
    val state by data.state.collectAsStateWithLifecycle()
    val palette = LocalReaderPalette.current
    val layoutDirection = LocalLayoutDirection.current

    if (state.rowCount == 0) {
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            if (!state.complete) {
                CircularProgressIndicator()
            } else {
                Text(
                    stringResource(if (state.failed) R.string.sheet_read_error else R.string.sheet_empty),
                    color = palette.secondaryText,
                )
            }
        }
        return
    }

    val revision by data.revision.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var zoom by remember { mutableFloatStateOf(initialPosition.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)) }
    var gestureScale by remember { mutableFloatStateOf(1f) }
    var gestureCentroid by remember { mutableStateOf(Offset.Zero) }
    var scrollX by remember { mutableFloatStateOf(0f) }
    var restored by remember { mutableStateOf(false) }

    val frozen = freezeHeaderRow && state.rowCount > 1
    val firstListRow = if (frozen) 1 else 0
    val fontSize = (BASE_FONT_SP * zoom).sp
    val fontDp = with(density) { fontSize.toDp() }
    val rowHeight = fontDp * ROW_HEIGHT_EM
    // Во время разбора ширина колонки номеров берётся по ожидаемому числу строк и не уменьшается,
    // чтобы сетка не сдвигалась с каждой новой пачкой строк.
    val maxDigits = remember(data) { intArrayOf(0) }
    val digits = if (state.complete) {
        digitCount(state.rowCount)
    } else {
        maxOf(maxDigits[0], digitCount(maxOf(state.rowCount, state.expectedRowCount)))
    }.also { maxDigits[0] = it }
    val rowHeaderWidth = fontDp * (digits * DIGIT_WIDTH_EM + 2 * CELL_PADDING_EM + 0.4f)

    val geometry = remember(state.columnWidths, fontDp, density) { buildGeometry(state.columnWidths, fontDp, density) }
    val resolvedStyles = remember(styles, palette, fontSize) { styles.map { it.resolve(palette, fontSize) } }
    val colors = MaterialTheme.colorScheme
    val currentGeometry by rememberUpdatedState(geometry)
    val currentFirstListRow by rememberUpdatedState(firstListRow)

    suspend fun scrollTo(target: ReadingPosition) {
        data.state.first { it.complete || it.rowCount > target.index }
        listState.scrollToItem((target.index - currentFirstListRow).coerceAtLeast(0), target.offset)
        scrollX = currentGeometry.start(target.column)
    }

    // Сначала восстанавливаем позицию, и только потом начинаем сообщать о прокрутке — иначе затрём сохранённую.
    LaunchedEffect(data) {
        scrollTo(initialPosition)
        restored = true
        jumps.collect { scrollTo(it) }
    }
    LaunchedEffect(data, listState) {
        snapshotFlow {
            if (!restored) {
                null
            } else {
                ReadingPosition(
                    index = listState.firstVisibleItemIndex + currentFirstListRow,
                    offset = listState.firstVisibleItemScrollOffset,
                    sheet = data.index,
                    column = currentGeometry.columnAt(scrollX),
                    zoom = zoom,
                )
            }
        }.filterNotNull().distinctUntilChanged().collect(onPositionChanged)
    }
    // Подгружаем с диска строки вокруг видимой области (и закреплённую первую строку).
    LaunchedEffect(data, listState) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = (visible.firstOrNull()?.index ?: listState.firstVisibleItemIndex) + currentFirstListRow
            val last = (visible.lastOrNull()?.index ?: first) + currentFirstListRow
            Triple(first, last, state.rowCount)
        }.distinctUntilChanged().collectLatest { (first, last) ->
            data.loadRows(0..0)
            data.loadRows((first - PREFETCH_ROWS).coerceAtLeast(0)..last + PREFETCH_ROWS)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(
                start = contentPadding.calculateStartPadding(layoutDirection),
                end = contentPadding.calculateEndPadding(layoutDirection),
                top = contentPadding.calculateTopPadding(),
            ),
    ) {
        val rowHeaderWidthPx = with(density) { rowHeaderWidth.toPx() }
        val headerHeightPx = with(density) { rowHeight.toPx() }
        val viewportWidthPx = constraints.maxWidth - rowHeaderWidthPx
        val maxScrollX = (geometry.totalWidth - viewportWidthPx).coerceAtLeast(0f)
        val currentMaxScrollX by rememberUpdatedState(maxScrollX)
        LaunchedEffect(maxScrollX) { scrollX = scrollX.coerceIn(0f, maxScrollX) }

        val scrollableState = rememberScrollableState { delta ->
            val old = scrollX
            scrollX = (old - delta).coerceIn(0f, currentMaxScrollX)
            old - scrollX
        }
        val visibleColumns = remember(geometry, viewportWidthPx) {
            derivedStateOf {
                val range = geometry.visibleRange(scrollX, viewportWidthPx)
                if (range.isEmpty()) range else (range.first - 1).coerceAtLeast(0)..(range.last + 1).coerceAtMost(geometry.columnCount - 1)
            }
        }
        val context = GridContext(
            geometry = geometry,
            visibleColumns = visibleColumns,
            scrollX = { scrollX },
            rowHeight = rowHeight,
            rowHeaderWidth = rowHeaderWidth,
            cellPadding = fontDp * CELL_PADDING_EM,
            styles = resolvedStyles,
            headerText = TextStyle(color = palette.secondaryText, fontSize = fontSize * 0.85f),
            palette = palette,
            gridLine = palette.secondaryText.copy(alpha = 0.22f),
            matchColor = colors.tertiary.copy(alpha = 0.28f),
            currentMatchColor = colors.primary.copy(alpha = 0.5f),
        )

        fun applyZoom() {
            val newZoom = (zoom * gestureScale).coerceIn(MIN_ZOOM, MAX_ZOOM)
            val factor = newZoom / zoom
            gestureScale = 1f
            if (factor == 1f) return
            // Точка между пальцами остаётся на месте: пересчитываем прокрутку под новый масштаб.
            val anchorX = (gestureCentroid.x - rowHeaderWidthPx).coerceAtLeast(0f)
            val anchorY = (gestureCentroid.y - headerHeightPx).coerceAtLeast(0f)
            scrollX = (scrollX + anchorX) * factor - anchorX
            zoom = newZoom
            val firstItem = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            scope.launch {
                listState.scrollToItem(firstItem, (offset * factor).roundToInt())
                listState.scrollBy(anchorY * (factor - 1))
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .pinchToZoom(
                    onZoom = { change, centroid ->
                        gestureScale = (gestureScale * change).coerceIn(MIN_ZOOM / zoom, MAX_ZOOM / zoom)
                        gestureCentroid = centroid
                    },
                    onZoomEnd = ::applyZoom,
                )
                .graphicsLayer {
                    // Во время щипка сетка масштабируется картинкой; чёткий текст — после перекладки по окончании жеста.
                    scaleX = gestureScale
                    scaleY = gestureScale
                    transformOrigin = if (size.width > 0f && size.height > 0f) {
                        TransformOrigin(gestureCentroid.x / size.width, gestureCentroid.y / size.height)
                    } else {
                        TransformOrigin.Center
                    }
                    clip = true
                }
                .scrollable(scrollableState, Orientation.Horizontal),
        ) {
            ColumnHeaderRow(context)
            if (frozen) {
                val headerRow = remember(revision) { data.cachedRow(0) }
                GridRow(0, headerRow, state.merges[0], context, search, frozen = true)
            }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                ) {
                    items(count = (state.rowCount - firstListRow).coerceAtLeast(0)) { item ->
                        val row = item + firstListRow
                        val sheetRow = remember(revision, row) { data.cachedRow(row) }
                        GridRow(row, sheetRow, state.merges[row], context, search)
                    }
                    if (state.failed) {
                        item {
                            Text(
                                stringResource(R.string.sheet_read_partial),
                                color = palette.secondaryText,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
                if (!state.complete) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                }
            }
        }
    }
}

/**
 * Геометрия колонок. Справа от данных добавляются [TRAILING_COLUMNS] пустых колонок, как в Excel:
 * длинный текст последней колонки продолжается на них, а не обрезается краем сетки.
 */
private fun buildGeometry(widthsInChars: FloatArray, fontDp: Dp, density: Density): GridGeometry {
    val charPx = with(density) { (fontDp * CHAR_WIDTH_EM).toPx() }
    val paddingPx = with(density) { (fontDp * CELL_PADDING_EM * 2).toPx() }
    val starts = FloatArray(widthsInChars.size + TRAILING_COLUMNS + 1)
    for (column in 0 until starts.size - 1) {
        val chars = widthsInChars.getOrElse(column) { DEFAULT_COLUMN_CHARS }
        starts[column + 1] = starts[column] + if (chars <= 0f) 0f else chars * charPx + paddingPx
    }
    return GridGeometry(starts)
}

/** Цвет текста ячейки: свой, если он читается на фоне ячейки, иначе — цвет темы или чёрный/белый на заливке. */
private fun CellStyle.resolve(palette: ReaderPalette, fontSize: TextUnit): ResolvedStyle {
    val fill = fillColor?.let(::Color)
    val background = fill ?: palette.page
    fun readable(color: Color) = ColorUtils.calculateContrast(color.toArgb(), background.toArgb()) >= MIN_TEXT_CONTRAST
    val ownColor = textColor?.let(::Color)?.takeIf(::readable)
    val color = ownColor ?: when {
        readable(palette.text) -> palette.text
        background.luminance() > 0.5f -> Color.Black
        else -> Color.White
    }
    val decorations = listOfNotNull(
        TextDecoration.Underline.takeIf { underline },
        TextDecoration.LineThrough.takeIf { strikethrough },
    )
    return ResolvedStyle(
        fill = fill,
        text = TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        ),
        alignment = alignment,
    )
}

/** Строка заголовков колонок (A, B, C…) с угловой ячейкой над номерами строк. */
@Composable
private fun ColumnHeaderRow(context: GridContext) {
    val visible by context.visibleColumns
    val spans = remember(visible) { visible.map { it to it } }
    Row(Modifier.height(context.rowHeight).background(context.palette.chrome)) {
        HeaderCell("", context, Modifier.width(context.rowHeaderWidth))
        ColumnsLayout(spans, context, Modifier.fillMaxSize()) { index ->
            HeaderCell(columnName(spans[index].first), context, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun HeaderCell(text: String, context: GridContext, modifier: Modifier) {
    Box(modifier.fillMaxSize().cellBorders(context.gridLine), contentAlignment = Alignment.Center) {
        BasicText(text, style = context.headerText, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/** Блок ячеек в строке: обычная ячейка, ячейка с текстом поверх пустых соседей или объединённая. */
private class CellBox(val first: Int, val last: Int, val cell: SheetCell, val merged: Boolean)

@Composable
private fun GridRow(
    rowIndex: Int,
    row: SheetRow?,
    merges: List<CellRange>?,
    context: GridContext,
    search: SheetSearchState?,
    frozen: Boolean = false,
) {
    val visible by context.visibleColumns
    val boxes = remember(row, merges, visible, context.geometry) {
        cellBoxes(row, rowIndex, merges, visible, context.geometry.columnCount) { cell ->
            context.styles.getOrNull(cell.style)?.alignment.let { it == null || it == HorizontalAlignment.GENERAL || it == HorizontalAlignment.LEFT }
        }
    }
    val frozenModifier = if (frozen) {
        val line = context.gridLine.copy(alpha = 0.6f)
        Modifier.drawBehind { drawLine(line, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 3f) }
    } else {
        Modifier
    }
    Row(Modifier.height(context.rowHeight).background(context.palette.page).then(frozenModifier)) {
        Box(
            Modifier.width(context.rowHeaderWidth).fillMaxSize().background(context.palette.chrome).cellBorders(context.gridLine),
            contentAlignment = Alignment.Center,
        ) {
            BasicText((rowIndex + 1).toString(), style = context.headerText, maxLines = 1, softWrap = false)
        }
        val gridLine = context.gridLine
        ColumnsLayout(
            spans = remember(boxes) { boxes.map { it.first to it.last } },
            context = context,
            modifier = Modifier.fillMaxSize().drawBehind {
                // Линии сетки рисуются для всех видимых колонок, включая пустые.
                val scroll = context.scrollX()
                for (column in visible) {
                    val x = context.geometry.start(column + 1) - scroll
                    drawLine(gridLine, Offset(x, 0f), Offset(x, size.height))
                }
                drawLine(gridLine, Offset(0f, size.height), Offset(size.width, size.height))
            },
        ) { index ->
            val box = boxes[index]
            val highlight = when {
                search == null -> null
                search.current?.let { it.row == rowIndex && it.column == box.cell.column } == true -> context.currentMatchColor
                cellKey(rowIndex, box.cell.column) in search.matchKeys -> context.matchColor
                else -> null
            }
            CellView(box, context, highlight)
        }
    }
}

@Composable
private fun CellView(box: CellBox, context: GridContext, highlight: Color?) {
    val cell = box.cell
    val style = context.styles.getOrElse(cell.style) { context.styles.first() }
    // Заливка — только на свою ячейку (у объединённой — на всю); текст может заходить на соседние.
    val ownWidth = if (box.merged) Float.MAX_VALUE else context.geometry.width(cell.column, cell.column)
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val area = Size(minOf(ownWidth, size.width), size.height)
                style.fill?.let { drawRect(it, size = area) }
                highlight?.let { drawRect(it, size = area) }
            }
            .padding(horizontal = context.cellPadding),
        contentAlignment = when (style.alignment) {
            HorizontalAlignment.LEFT -> Alignment.CenterStart
            HorizontalAlignment.CENTER -> Alignment.Center
            HorizontalAlignment.RIGHT -> Alignment.CenterEnd
            HorizontalAlignment.GENERAL -> when (cell.kind) {
                CellKind.NUMBER -> Alignment.CenterEnd
                CellKind.BOOLEAN, CellKind.ERROR -> Alignment.Center
                CellKind.TEXT -> Alignment.CenterStart
            }
        },
    ) {
        if (cell.text.isNotEmpty()) {
            // Не помещающееся число Excel сначала округляет (дробную часть), а если не влезает и целая часть —
            // заменяет решётками: обрезанное число вводило бы в заблуждение.
            var shown by remember(cell.text, box.first, box.last, style.text.fontSize) { mutableStateOf(cell.text) }
            BasicText(
                text = shown.replace('\n', ' '),
                style = style.text,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                onTextLayout = { layout ->
                    if (cell.kind == CellKind.NUMBER && shown != NUMBER_OVERFLOW && layout.hasVisualOverflow) {
                        // Цифры в шрифтах одной ширины, поэтому число влезающих символов — пропорция ширин.
                        val needed = layout.multiParagraph.intrinsics.maxIntrinsicWidth.coerceAtLeast(1f)
                        val fits = (shown.length * layout.size.width / needed).toInt()
                        shown = fitDecimal(shown, fits) ?: NUMBER_OVERFLOW
                    }
                },
            )
        }
    }
}

/**
 * Раскладывает дочерние элементы по колонкам: элемент [spans]`[i]` занимает колонки first..last.
 * Горизонтальная прокрутка читается только при размещении, поэтому не вызывает перекомпоновку.
 */
@Composable
private fun ColumnsLayout(
    spans: List<Pair<Int, Int>>,
    context: GridContext,
    modifier: Modifier,
    content: @Composable (index: Int) -> Unit,
) {
    Layout(
        content = { spans.forEachIndexed { index, span -> key(span.first) { content(index) } } },
        // Обрезка — первой: линии сетки из modifier (drawBehind) у ушедших влево колонок не должны залезать на номера строк.
        modifier = Modifier.clipToBounds().then(modifier),
    ) { measurables, constraints ->
        val height = constraints.maxHeight
        val placeables = measurables.mapIndexed { index, measurable ->
            val (first, last) = spans[index]
            val width = context.geometry.width(first, last).roundToInt().coerceAtLeast(0)
            measurable.measure(Constraints.fixed(width, height))
        }
        layout(constraints.maxWidth, height) {
            val scroll = context.scrollX()
            placeables.forEachIndexed { index, placeable ->
                placeable.place((context.geometry.start(spans[index].first) - scroll).roundToInt(), 0)
            }
        }
    }
}

private fun Modifier.cellBorders(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(size.width, 0f), Offset(size.width, size.height))
    drawLine(color, Offset(0f, size.height), Offset(size.width, size.height))
}

/**
 * Ячейки строки, попадающие в видимые колонки [visible]. Текст, выровненный влево ([leftAligned]),
 * продолжается на пустые ячейки справа (как в Excel); объединённые ячейки — один блок.
 */
private fun cellBoxes(
    row: SheetRow?,
    rowIndex: Int,
    merges: List<CellRange>?,
    visible: IntRange,
    columnCount: Int,
    leftAligned: (SheetCell) -> Boolean,
): List<CellBox> {
    if (row == null || visible.isEmpty()) return emptyList()
    val result = ArrayList<CellBox>()
    fun mergeAt(column: Int) = merges?.firstOrNull { column in it.firstColumn..it.lastColumn }
    fun intersects(first: Int, last: Int) = first <= visible.last && last >= visible.first

    val cells = row.cells
    for ((i, cell) in cells.withIndex()) {
        if (cell.column >= columnCount) break
        val merge = mergeAt(cell.column)
        if (merge != null) {
            // Содержимое объединения — только в его левой верхней ячейке.
            if (merge.firstRow == rowIndex && merge.firstColumn == cell.column) {
                val last = merge.lastColumn.coerceAtMost(columnCount - 1)
                if (intersects(cell.column, last)) result += CellBox(cell.column, last, cell, merged = true)
            }
            continue
        }
        var last = cell.column
        if (cell.text.isNotEmpty() && cell.kind == CellKind.TEXT && leftAligned(cell)) {
            val nextFilled = (i + 1 until cells.size).firstOrNull { cells[it].text.isNotEmpty() }?.let { cells[it].column }
            val limit = minOf(
                (nextFilled ?: columnCount) - 1,
                columnCount - 1,
                cell.column + MAX_OVERFLOW_COLUMNS,
            )
            while (last < limit && mergeAt(last + 1) == null) last++
        }
        if (intersects(cell.column, last)) result += CellBox(cell.column, last, cell, merged = false)
    }
    return result
}

private fun digitCount(rows: Int): Int = rows.toString().length.coerceAtLeast(2)

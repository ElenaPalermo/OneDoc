package io.doccraftstudio.onedoc.ui.viewer.flow

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.doccraftstudio.onedoc.domain.model.MAX_READER_FONT_SP
import io.doccraftstudio.onedoc.domain.model.MIN_READER_FONT_SP
import io.doccraftstudio.onedoc.domain.model.ReaderFont
import io.doccraftstudio.onedoc.domain.model.ReaderSettings
import io.doccraftstudio.onedoc.domain.model.ReadingPosition
import io.doccraftstudio.onedoc.reader.flow.FlowDocument
import io.doccraftstudio.onedoc.reader.flow.ImageBlock
import io.doccraftstudio.onedoc.reader.flow.PageBreakBlock
import io.doccraftstudio.onedoc.reader.flow.ParagraphBlock
import io.doccraftstudio.onedoc.reader.flow.TableBlock
import io.doccraftstudio.onedoc.reader.flow.TableLayout
import io.doccraftstudio.onedoc.reader.flow.TableRowBlock
import io.doccraftstudio.onedoc.reader.flow.TextLineBlock
import io.doccraftstudio.onedoc.ui.viewer.common.LocalReaderPalette
import io.doccraftstudio.onedoc.ui.viewer.common.pinchToZoom
import kotlinx.coroutines.flow.Flow

/**
 * Просмотр документов с переносом (TXT, DOCX, DOC): каждый блок — элемент `LazyColumn`,
 * поэтому компонуются только видимые абзацы и строки таблиц. Текст переносится по ширине экрана;
 * шрифт, размер и интервал задаются настройками чтения, pinch-to-zoom меняет размер шрифта.
 * Позиция — индекс первого видимого блока и смещение внутри него в пикселях.
 */
@Composable
fun FlowViewer(
    document: FlowDocument,
    settings: ReaderSettings,
    initialPosition: ReadingPosition,
    navigationRequests: Flow<ReadingPosition>,
    contentPadding: PaddingValues,
    onPositionChanged: (ReadingPosition) -> Unit,
    onFontSizeChanged: (Float) -> Unit,
) {
    val listState = rememberLazyListState(initialPosition.index, initialPosition.offset)
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)

    LaunchedEffect(listState) {
        snapshotFlow { ReadingPosition(index = listState.firstVisibleItemIndex, offset = listState.firstVisibleItemScrollOffset) }
            .collect { currentOnPositionChanged(it) }
    }
    LaunchedEffect(listState, navigationRequests) {
        navigationRequests.collect { listState.scrollToItem(it.index, it.offset) }
    }

    // Размер шрифта во время щипка меняется локально; в настройки пишется по окончании жеста.
    var liveFontSize by remember(settings.fontSizeSp) { mutableFloatStateOf(settings.fontSizeSp) }
    val palette = LocalReaderPalette.current
    val baseStyle = TextStyle(
        color = palette.text,
        fontSize = liveFontSize.sp,
        lineHeight = settings.lineSpacing.em,
        fontFamily = settings.font.toFontFamily(),
        // Без обрезки: иначе у каждого блока срезается интервал над первой и под последней строкой,
        // и соседние строки TXT стоят теснее, чем строки одного абзаца.
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    // Строки одной таблицы прокручиваются по горизонтали вместе: у них общий ScrollState.
    val tableScrollStates = remember(document) { mutableMapOf<TableLayout, ScrollState>() }
    val paragraphGap = if (document.paragraphSpacing) (liveFontSize * PARAGRAPH_GAP_EM).dp else 0.dp

    val layoutDirection = LocalLayoutDirection.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val start = contentPadding.calculateStartPadding(layoutDirection)
        val end = contentPadding.calculateEndPadding(layoutDirection)
        // На широком экране (ландшафт, планшет) колонка текста ограничена по ширине, как в книге:
        // слишком длинные строки трудно читать. Шрифт крупнее — колонка шире, чтобы в строке было столько же слов.
        val maxTextWidth = MAX_TEXT_WIDTH * (liveFontSize / DEFAULT_TEXT_SIZE_SP).coerceAtLeast(1f)
        val sideMargin = ((maxWidth - start - end - maxTextWidth) / 2).coerceAtLeast(HORIZONTAL_MARGIN)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pinchToZoom(
                    onZoom = { zoom, _ -> liveFontSize = (liveFontSize * zoom).coerceIn(MIN_READER_FONT_SP, MAX_READER_FONT_SP) },
                    onZoomEnd = { onFontSizeChanged(liveFontSize) },
                ),
            contentPadding = PaddingValues(
                start = start + sideMargin,
                end = end + sideMargin,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            items(
                count = document.blocks.size,
                contentType = { document.blocks[it]::class },
            ) { index ->
                when (val block = document.blocks[index]) {
                    is TextLineBlock -> Text(block.text, style = baseStyle)
                    is ParagraphBlock -> FlowParagraph(block, baseStyle, Modifier.padding(bottom = paragraphGap))
                    is TableRowBlock -> FlowTableRow(
                        row = block,
                        baseStyle = baseStyle,
                        scrollState = tableScrollStates.getOrPut(block.table) { ScrollState(0) },
                        modifier = if (block.isLast) Modifier.padding(bottom = paragraphGap) else Modifier,
                    )
                    is ImageBlock -> FlowImage(block, document.resources, Modifier.padding(bottom = paragraphGap))
                    // Таблицы целиком в документе не встречаются: FlowDocument.of разбивает их на строки.
                    is TableBlock -> Unit
                    PageBreakBlock -> HorizontalDivider(Modifier.padding(vertical = 16.dp), color = palette.secondaryText.copy(alpha = 0.3f))
                }
            }
        }
    }
}

private val HORIZONTAL_MARGIN = 16.dp
private val MAX_TEXT_WIDTH = 680.dp
private const val DEFAULT_TEXT_SIZE_SP = 18f
private const val PARAGRAPH_GAP_EM = 0.5f

fun ReaderFont.toFontFamily(): FontFamily = when (this) {
    ReaderFont.SANS -> FontFamily.SansSerif
    ReaderFont.SERIF -> FontFamily.Serif
    ReaderFont.MONO -> FontFamily.Monospace
}

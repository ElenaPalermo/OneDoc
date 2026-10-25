package io.doccraftstudio.onedoc.ui.viewer.flow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.graphics.ColorUtils
import io.doccraftstudio.onedoc.reader.flow.Baseline
import io.doccraftstudio.onedoc.reader.flow.FlowResources
import io.doccraftstudio.onedoc.reader.flow.ImageBlock
import io.doccraftstudio.onedoc.reader.flow.ParagraphBlock
import io.doccraftstudio.onedoc.reader.flow.TableRowBlock
import io.doccraftstudio.onedoc.reader.flow.TextAlignment
import io.doccraftstudio.onedoc.reader.flow.TextRun
import io.doccraftstudio.onedoc.ui.viewer.common.LocalReaderPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

private val INDENT_STEP = 16.dp
private val LIST_MARKER_WIDTH = 28.dp
private val LIST_LEVEL_STEP = 24.dp

/** Цвета документа, которые почти не видны на фоне темы (чёрный на тёмной), заменяются цветом текста темы. */
private const val MIN_COLOR_CONTRAST = 3.0
private const val SCRIPT_SCALE = 0.7f
private const val TAB_SPACES = "    "

/** Абзац документа: заголовок, пункт списка или обычный текст с оформлением фрагментов. */
@Composable
internal fun FlowParagraph(block: ParagraphBlock, baseStyle: TextStyle, modifier: Modifier = Modifier) {
    val palette = LocalReaderPalette.current
    val linkColor = MaterialTheme.colorScheme.primary
    // Размер абзаца — по самому крупному фрагменту, чтобы межстрочный интервал (в em) не давал наложений.
    val paragraphScale = max(1f, block.runs.maxOfOrNull { it.sizeScale } ?: 1f)
    val text = remember(block, palette, linkColor, paragraphScale) {
        block.toAnnotatedString(paragraphScale, palette.page, linkColor)
    }
    val style = baseStyle.copy(
        fontSize = baseStyle.fontSize * paragraphScale,
        textAlign = when (block.alignment) {
            TextAlignment.START -> TextAlign.Start
            TextAlignment.CENTER -> TextAlign.Center
            TextAlignment.END -> TextAlign.End
            TextAlignment.JUSTIFY -> TextAlign.Justify
        },
    )
    val headingSpace = if (block.headingLevel > 0) (baseStyle.fontSize.value * 0.6f).dp else 0.dp
    val marker = block.listMarker

    if (marker == null) {
        val indent = INDENT_STEP * block.indentLevel.coerceAtLeast(0)
        Text(text, style = style, modifier = modifier.padding(start = indent, top = headingSpace))
    } else {
        // Пункт первого уровня начинается у края (место занимает маркер), каждый следующий уровень — правее.
        val listIndent = LIST_LEVEL_STEP * (block.indentLevel - 1).coerceAtLeast(0)
        Row(modifier.padding(start = listIndent, top = headingSpace)) {
            Text(marker, style = style.copy(textAlign = TextAlign.End), modifier = Modifier.width(LIST_MARKER_WIDTH).padding(end = 6.dp))
            Text(text, style = style)
        }
    }
}

private fun ParagraphBlock.toAnnotatedString(paragraphScale: Float, page: Color, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        for (run in runs) {
            val spanStyle = run.toSpanStyle(paragraphScale, page)
            val text = run.text.replace("\t", TAB_SPACES)
            val link = run.link
            if (link != null) {
                val linkStyle = TextLinkStyles(spanStyle.merge(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))
                withLink(LinkAnnotation.Url(link, linkStyle)) { append(text) }
            } else {
                withStyle(spanStyle) { append(text) }
            }
        }
    }

private fun TextRun.toSpanStyle(paragraphScale: Float, page: Color): SpanStyle {
    val decorations = listOfNotNull(
        TextDecoration.Underline.takeIf { underline },
        TextDecoration.LineThrough.takeIf { strikethrough },
    )
    val relativeSize = sizeScale / paragraphScale
    val color = color?.takeIf { ColorUtils.calculateContrast(it, page.toArgb()) >= MIN_COLOR_CONTRAST }
    return SpanStyle(
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        color = color?.let(::Color) ?: Color.Unspecified,
        fontSize = when (baseline) {
            Baseline.NORMAL -> relativeSize.em
            else -> (relativeSize * SCRIPT_SCALE).em
        },
        baselineShift = when (baseline) {
            Baseline.SUPERSCRIPT -> BaselineShift.Superscript
            Baseline.SUBSCRIPT -> BaselineShift.Subscript
            Baseline.NORMAL -> null
        },
    )
}

/**
 * Строка таблицы. Ширина колонок общая для всей таблицы ([io.doccraftstudio.onedoc.reader.flow.TableLayout]),
 * широкие таблицы прокручиваются по горизонтали — все строки синхронно через общий [scrollState].
 */
@Composable
internal fun FlowTableRow(row: TableRowBlock, baseStyle: TextStyle, scrollState: ScrollState, modifier: Modifier = Modifier) {
    val palette = LocalReaderPalette.current
    val borderColor = palette.secondaryText.copy(alpha = 0.4f)
    val cellStyle = baseStyle.copy(fontSize = baseStyle.fontSize * TABLE_TEXT_SCALE)
    val columnWidths = remember(row.table, baseStyle.fontSize) {
        row.table.columnChars.map { chars -> columnWidth(chars, cellStyle.fontSize) }
    }

    Row(
        modifier
            .horizontalScroll(scrollState)
            .height(IntrinsicSize.Min)
            .padding(top = if (row.isFirst) 4.dp else 0.dp),
    ) {
        var column = 0
        for (cell in row.cells) {
            val span = cell.columnSpan.coerceAtLeast(1)
            val width = columnWidths.drop(column).take(span).fold(0.dp) { sum, w -> sum + w }
                .takeIf { it > 0.dp } ?: columnWidths.lastOrNull() ?: MIN_COLUMN_WIDTH
            column += span
            Column(
                Modifier
                    .width(width)
                    .fillMaxHeight()
                    .border(0.5.dp, borderColor)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                for (paragraph in cell.paragraphs) FlowParagraph(paragraph, cellStyle)
            }
        }
    }
}

private const val TABLE_TEXT_SCALE = 0.9f
/** Средняя ширина символа с запасом под шрифт с засечками — чтобы слова не рвались внутри ячейки. */
private const val CHAR_WIDTH_EM = 0.62f
private const val CELL_HORIZONTAL_PADDING = 20
private val MIN_COLUMN_WIDTH = 56.dp
private val MAX_COLUMN_WIDTH = 320.dp

private fun columnWidth(chars: Int, fontSize: TextUnit): Dp =
    (chars * fontSize.value * CHAR_WIDTH_EM + CELL_HORIZONTAL_PADDING).dp.coerceIn(MIN_COLUMN_WIDTH, MAX_COLUMN_WIDTH)

/**
 * Картинка документа. Декодируется в фоне с уменьшением до ширины экрана (экономия памяти);
 * пока грузится, место резервируется по известным пропорциям, чтобы текст не прыгал.
 * Форматы, которые Android не декодирует (EMF/WMF), просто не показываются.
 */
@Composable
internal fun FlowImage(block: ImageBlock, resources: FlowResources?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val maxWidthPx = constraints.maxWidth
        val image by produceState<ImageBitmap?>(null, block.key, maxWidthPx) {
            value = withContext(Dispatchers.IO) {
                resources?.loadImage(block.key)?.let { decodeSampled(it, maxWidthPx) }?.asImageBitmap()
            }
        }
        val density = LocalDensity.current
        val loaded = image
        if (loaded != null) {
            // Мелкие картинки (значки, подписи) не растягиваем на всю ширину.
            val naturalWidth = with(density) { (loaded.width * SMALL_IMAGE_UPSCALE).toDp() }
            Image(
                bitmap = loaded,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(minOf(maxWidth, naturalWidth))
                    .aspectRatio(loaded.width.toFloat() / loaded.height.coerceAtLeast(1)),
            )
        } else {
            val aspect = block.aspectRatio
            if (aspect != null && aspect > 0f) {
                Spacer(Modifier.fillMaxWidth().aspectRatio(1f / aspect))
            }
        }
    }
}

private const val SMALL_IMAGE_UPSCALE = 1.5f

private fun decodeSampled(bytes: ByteArray, maxWidthPx: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidthPx) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
} catch (e: OutOfMemoryError) {
    null
}

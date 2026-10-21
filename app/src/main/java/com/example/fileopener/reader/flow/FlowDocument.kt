package com.example.fileopener.reader.flow

import com.example.fileopener.reader.TocEntry
import java.io.Closeable

/**
 * Документ «с переносом» (TXT, DOCX, DOC): последовательность блоков, которые раскладываются
 * по ширине экрана. Каждый блок — отдельный элемент списка в просмотрщике, поэтому индекс блока
 * и есть позиция чтения; таблицы заранее разбиты на строки ([TableRowBlock]).
 *
 * @property paragraphSpacing добавлять ли отступ между абзацами (в TXT каждая строка — абзац, отступы не нужны).
 * @property resources картинки документа, читаются лениво; закрываются вместе с документом.
 */
class FlowDocument private constructor(
    val blocks: List<FlowBlock>,
    val paragraphSpacing: Boolean,
    val resources: FlowResources? = null,
) : Closeable {
    /** Оглавление из заголовков первых трёх уровней. */
    val tableOfContents: List<TocEntry> by lazy {
        blocks.mapIndexedNotNull { index, block ->
            val heading = (block as? ParagraphBlock)?.headingLevel ?: 0
            val title = block.plainText.trim()
            if (heading in 1..MAX_TOC_LEVEL && title.isNotEmpty()) TocEntry(title, index, heading - 1) else null
        }
    }

    override fun close() {
        resources?.close()
    }

    companion object {
        private const val MAX_TOC_LEVEL = 3

        /** Разбивает таблицы на строки: так большая таблица не компонуется целиком одним элементом. */
        fun of(blocks: List<FlowBlock>, paragraphSpacing: Boolean, resources: FlowResources? = null): FlowDocument {
            val flat = ArrayList<FlowBlock>(blocks.size)
            for (block in blocks) {
                if (block is TableBlock) {
                    if (block.rows.isEmpty()) continue
                    val layout = TableLayout(block)
                    block.rows.indices.mapTo(flat) { TableRowBlock(layout, it) }
                } else {
                    flat += block
                }
            }
            return FlowDocument(flat, paragraphSpacing, resources)
        }
    }
}

sealed interface FlowBlock {
    /** Текст блока без разметки — для заголовков закладок и оглавления. */
    val plainText: String
}

/** Строка простого текста (TXT): минимум объектов на строку для больших файлов. */
data class TextLineBlock(val text: String) : FlowBlock {
    override val plainText: String get() = text
}

enum class TextAlignment { START, CENTER, END, JUSTIFY }

enum class Baseline { NORMAL, SUPERSCRIPT, SUBSCRIPT }

/**
 * Фрагмент текста с единым оформлением.
 * @property sizeScale размер относительно основного текста документа (1 — обычный).
 * @property color ARGB; `null` — цвет текста темы чтения.
 */
data class TextRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val color: Int? = null,
    val sizeScale: Float = 1f,
    val baseline: Baseline = Baseline.NORMAL,
    val link: String? = null,
)

/**
 * Абзац документа. [headingLevel] 0 — обычный текст, 1..6 — заголовки.
 * [listMarker] — маркер или номер пункта списка («•», «2.», «б)»).
 */
data class ParagraphBlock(
    val runs: List<TextRun>,
    val headingLevel: Int = 0,
    val alignment: TextAlignment = TextAlignment.START,
    val indentLevel: Int = 0,
    val listMarker: String? = null,
) : FlowBlock {
    override val plainText: String get() = runs.joinToString("") { it.text }
}

data class TableCell(val paragraphs: List<ParagraphBlock>, val columnSpan: Int = 1) {
    val plainText: String get() = paragraphs.joinToString("\n") { it.plainText }
}

/** Таблица целиком — только результат парсера; в [FlowDocument] она превращается в [TableRowBlock]. */
data class TableBlock(val rows: List<List<TableCell>>) : FlowBlock {
    override val plainText: String get() = rows.joinToString(" ") { row -> row.joinToString(" ") { it.plainText } }
}

/**
 * Общие для всех строк таблицы данные: число колонок и их относительная ширина
 * (по самому длинному тексту в колонке, в символах), чтобы строки выравнивались друг под другом.
 */
class TableLayout(table: TableBlock) {
    val rows: List<List<TableCell>> = table.rows
    val columnCount: Int = rows.maxOf { row -> row.sumOf { it.columnSpan.coerceAtLeast(1) } }
    val columnChars: IntArray = IntArray(columnCount) { MIN_COLUMN_CHARS }.also { chars ->
        for (row in rows) {
            var column = 0
            for (cell in row) {
                if (cell.columnSpan <= 1 && column < columnCount) {
                    val longest = cell.paragraphs.maxOfOrNull { it.plainText.length } ?: 0
                    chars[column] = maxOf(chars[column], longest.coerceAtMost(MAX_COLUMN_CHARS))
                }
                column += cell.columnSpan.coerceAtLeast(1)
            }
        }
    }

    private companion object {
        const val MIN_COLUMN_CHARS = 4
        const val MAX_COLUMN_CHARS = 32
    }
}

data class TableRowBlock(val table: TableLayout, val rowIndex: Int) : FlowBlock {
    val cells: List<TableCell> get() = table.rows[rowIndex]
    val isFirst: Boolean get() = rowIndex == 0
    val isLast: Boolean get() = rowIndex == table.rows.lastIndex
    override val plainText: String get() = cells.joinToString(" ") { it.plainText }
}

/** Картинка; [key] — идентификатор в [FlowResources], [aspectRatio] — высота/ширина, если известна заранее. */
data class ImageBlock(val key: String, val aspectRatio: Float? = null) : FlowBlock {
    override val plainText: String get() = ""
}

data object PageBreakBlock : FlowBlock {
    override val plainText: String get() = ""
}

/** Ленивый доступ к картинкам документа. */
interface FlowResources : Closeable {
    /** Байты картинки или `null`, если её нет или она слишком большая. */
    fun loadImage(key: String): ByteArray?
}

package com.example.fileopener.reader.doc

import com.example.fileopener.reader.flow.Baseline
import com.example.fileopener.reader.flow.FlowBlock
import com.example.fileopener.reader.flow.FlowDocument
import com.example.fileopener.reader.flow.FlowResources
import com.example.fileopener.reader.flow.ImageBlock
import com.example.fileopener.reader.flow.PageBreakBlock
import com.example.fileopener.reader.flow.ParagraphBlock
import com.example.fileopener.reader.flow.TableBlock
import com.example.fileopener.reader.flow.TextAlignment
import com.example.fileopener.reader.flow.TextRun
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.model.PicturesTable
import org.apache.poi.hwpf.usermodel.CharacterRun
import org.apache.poi.hwpf.usermodel.Paragraph
import org.apache.poi.hwpf.usermodel.Range
import java.io.InputStream
import com.example.fileopener.reader.flow.TableCell as FlowTableCell

/**
 * Word 97–2003 (.doc) через Apache POI HWPF. Переносится текст с основным оформлением
 * (жирный, курсив, подчёркивание, размер), заголовки, списки, таблицы и картинки.
 * Коды полей скрываются — показывается только их результат, как в Word.
 */
object DocReader {

    fun read(input: InputStream): FlowDocument {
        val document = HWPFDocument(input)
        val converter = Converter(document)
        val blocks = converter.convert()
        return FlowDocument.of(normalizeSizes(blocks), paragraphSpacing = true, resources = MemoryResources(converter.images))
    }

    private class Converter(private val document: HWPFDocument) {
        val images = mutableMapOf<String, ByteArray>()
        private val pictures: PicturesTable? = runCatching { document.picturesTable }.getOrNull()
        private val listCounters = mutableMapOf<Int, IntArray>()

        fun convert(): List<FlowBlock> {
            val range = document.range
            val blocks = mutableListOf<FlowBlock>()
            var index = 0
            while (index < range.numParagraphs()) {
                val paragraph = range.getParagraph(index)
                if (paragraph.isInTable) {
                    val consumed = runCatching { convertTable(range, paragraph, blocks) }.getOrNull()
                    if (consumed != null && consumed > 0) {
                        index += consumed
                        continue
                    }
                }
                convertParagraph(paragraph, blocks)
                index++
            }
            return blocks
        }

        /** Возвращает число абзацев, занятых таблицей. */
        private fun convertTable(range: Range, first: Paragraph, out: MutableList<FlowBlock>): Int {
            val table = range.getTable(first)
            val rows = (0 until table.numRows()).map { rowIndex ->
                val row = table.getRow(rowIndex)
                (0 until row.numCells()).map { cellIndex ->
                    val cell = row.getCell(cellIndex)
                    val paragraphs = mutableListOf<FlowBlock>()
                    for (p in 0 until cell.numParagraphs()) convertParagraph(cell.getParagraph(p), paragraphs)
                    FlowTableCell(paragraphs.filterIsInstance<ParagraphBlock>())
                }
            }
            out += TableBlock(rows.filter { it.isNotEmpty() })
            return table.numParagraphs()
        }

        private fun convertParagraph(paragraph: Paragraph, out: MutableList<FlowBlock>) {
            val runs = mutableListOf<TextRun>()
            val paragraphImages = mutableListOf<ImageBlock>()
            var pageBreak = false
            val fields = FieldFilter()
            val headingLevel = headingLevel(paragraph)

            for (runIndex in 0 until paragraph.numCharacterRuns()) {
                val run = paragraph.getCharacterRun(runIndex)
                val text = StringBuilder()
                for (char in run.text()) {
                    if (!fields.accept(char)) continue
                    when (char) {
                        '\r', CELL_MARK, FOOTNOTE_MARK, ANNOTATION_MARK -> Unit
                        '\u000b' -> text.append('\n')
                        PAGE_BREAK -> pageBreak = true
                        PICTURE_MARK, OBJECT_MARK -> extractPicture(run)?.let { paragraphImages += it }
                        else -> if (char >= ' ' || char == '\t') text.append(char)
                    }
                }
                if (text.isEmpty()) continue
                runs += TextRun(
                    text = text.toString(),
                    bold = run.isBold || headingLevel > 0,
                    italic = run.isItalic,
                    underline = run.underlineCode != 0,
                    strikethrough = run.isStrikeThrough || run.isDoubleStrikeThrough,
                    color = colorOf(run),
                    // Абсолютный размер в полупунктах; в долю от основного текста переводится в normalizeSizes.
                    sizeScale = run.fontSize.toFloat(),
                    baseline = when (run.subSuperScriptIndex.toInt()) {
                        1 -> Baseline.SUPERSCRIPT
                        2 -> Baseline.SUBSCRIPT
                        else -> Baseline.NORMAL
                    },
                )
            }

            val marker = listMarker(paragraph)
            if (runs.isNotEmpty() || paragraphImages.isEmpty()) {
                out += ParagraphBlock(
                    runs = runs,
                    headingLevel = headingLevel,
                    alignment = when (paragraph.justification) {
                        1 -> TextAlignment.CENTER
                        2 -> TextAlignment.END
                        3 -> TextAlignment.JUSTIFY
                        else -> TextAlignment.START
                    },
                    indentLevel = if (marker != null) {
                        paragraph.ilvl + 1
                    } else {
                        (paragraph.indentFromLeft / TWIPS_PER_INDENT_LEVEL).coerceIn(0, MAX_INDENT_LEVEL)
                    },
                    listMarker = marker,
                )
            }
            out += paragraphImages
            if (pageBreak) out += PageBreakBlock
        }

        private fun headingLevel(paragraph: Paragraph): Int {
            val name = runCatching { document.styleSheet.getStyleDescription(paragraph.styleIndex.toInt())?.name }
                .getOrNull() ?: return 0
            if (name.equals("title", ignoreCase = true)) return 1
            return HEADING_NAME.find(name.trim())?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 1..6 } ?: 0
        }

        /** Номер пункта по формату уровня списка; счётчики ведутся по ходу документа, как в Word. */
        private fun listMarker(paragraph: Paragraph): String? {
            if (!paragraph.isInList) return null
            return runCatching {
                val list = paragraph.list
                val level = paragraph.ilvl.toInt().coerceIn(0, MAX_LIST_LEVEL)
                val format = list.getNumberFormat(level.toChar()).toInt()
                if (format == BULLET_FORMAT) return@runCatching BULLETS[level % BULLETS.size]
                val counters = listCounters.getOrPut(paragraph.ilfo) { IntArray(MAX_LIST_LEVEL + 1) }
                counters[level] = if (counters[level] == 0) list.getStartAt(level.toChar()) else counters[level] + 1
                for (deeper in level + 1..MAX_LIST_LEVEL) counters[deeper] = 0
                formatNumber(counters[level], format) + "."
            }.getOrDefault(BULLETS[0])
        }

        private fun extractPicture(run: CharacterRun): ImageBlock? {
            val table = pictures ?: return null
            return runCatching {
                if (!table.hasPicture(run)) return@runCatching null
                val content = table.extractPicture(run, false)?.content ?: return@runCatching null
                val key = "doc-image-${images.size}"
                images[key] = content
                ImageBlock(key)
            }.getOrNull()
        }
    }

    /** Цвет фрагмента: Word хранит COLORREF (0x00BBGGRR), −1 — «авто» (цвет темы). */
    private fun colorOf(run: CharacterRun): Int? {
        val colorRef = run.ico24
        if (colorRef == -1) return null
        val red = colorRef and 0xFF
        val green = (colorRef shr 8) and 0xFF
        val blue = (colorRef shr 16) and 0xFF
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    /**
     * Поля Word хранятся как «\u0013 код \u0014 результат \u0015»; код (HYPERLINK, PAGE…) скрываем.
     * Поля бывают вложенными, поэтому храним стек: для каждого уровня — видим ли сейчас результат.
     */
    private class FieldFilter {
        private val showingResult = ArrayDeque<Boolean>()

        fun accept(char: Char): Boolean {
            when (char) {
                FIELD_BEGIN -> showingResult.addLast(false)
                FIELD_SEPARATOR -> if (showingResult.isNotEmpty()) showingResult[showingResult.lastIndex] = true
                FIELD_END -> showingResult.removeLastOrNull()
                else -> return showingResult.all { it }
            }
            return false
        }
    }

    /** Размеры шрифта: основной (самый частый по объёму текста) размер становится масштабом 1. */
    private fun normalizeSizes(blocks: List<FlowBlock>): List<FlowBlock> {
        val paragraphs = blocks.flatMap { block ->
            when (block) {
                is ParagraphBlock -> listOf(block)
                is TableBlock -> block.rows.flatten().flatMap { it.paragraphs }
                else -> emptyList()
            }
        }
        val baseSize = paragraphs.flatMap { it.runs }
            .groupBy { it.sizeScale }
            .maxByOrNull { (_, runs) -> runs.sumOf { it.text.length } }
            ?.key?.takeIf { it > 0f } ?: DEFAULT_SIZE_HALF_POINTS

        fun ParagraphBlock.normalized() = copy(
            runs = runs.map { it.copy(sizeScale = (it.sizeScale / baseSize).coerceIn(MIN_SCALE, MAX_SCALE)) },
        )
        return blocks.map { block ->
            when (block) {
                is ParagraphBlock -> block.normalized()
                is TableBlock -> block.copy(
                    rows = block.rows.map { row -> row.map { cell -> cell.copy(paragraphs = cell.paragraphs.map { it.normalized() }) } },
                )
                else -> block
            }
        }
    }

    private class MemoryResources(private val images: Map<String, ByteArray>) : FlowResources {
        override fun loadImage(key: String): ByteArray? = images[key]
        override fun close() = Unit
    }

    private const val FIELD_BEGIN = '\u0013'
    private const val FIELD_SEPARATOR = '\u0014'
    private const val FIELD_END = '\u0015'
    private const val CELL_MARK = '\u0007'
    private const val FOOTNOTE_MARK = '\u0002'
    private const val ANNOTATION_MARK = '\u0005'
    private const val PAGE_BREAK = '\u000c'
    private const val PICTURE_MARK = '\u0001'
    private const val OBJECT_MARK = '\u0008'
    private const val BULLET_FORMAT = 23
    private const val MAX_LIST_LEVEL = 8
    private const val TWIPS_PER_INDENT_LEVEL = 720
    private const val MAX_INDENT_LEVEL = 4
    private const val DEFAULT_SIZE_HALF_POINTS = 24f
    private const val MIN_SCALE = 0.6f
    private const val MAX_SCALE = 2.5f
    private val BULLETS = listOf("•", "◦", "▪")
    private val HEADING_NAME = Regex("""^(heading|заголовок)\s*(\d)$""", RegexOption.IGNORE_CASE)

    private fun formatNumber(value: Int, format: Int): String = when (format) {
        1 -> roman(value)
        2 -> roman(value).lowercase()
        3 -> letter(value)
        4 -> letter(value).lowercase()
        else -> value.toString()
    }

    private fun letter(value: Int): String =
        if (value <= 0) value.toString() else ('A' + (value - 1) % 26).toString().repeat((value - 1) / 26 + 1)

    private fun roman(value: Int): String {
        if (value !in 1..3999) return value.toString()
        val numerals = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
            50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        var rest = value
        return buildString {
            for ((number, numeral) in numerals) {
                while (rest >= number) {
                    append(numeral)
                    rest -= number
                }
            }
        }
    }
}

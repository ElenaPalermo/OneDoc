package com.example.fileopener.reader.docx

import com.example.fileopener.reader.flow.Baseline
import com.example.fileopener.reader.flow.FlowBlock
import com.example.fileopener.reader.flow.FlowDocument
import com.example.fileopener.reader.flow.FlowResources
import com.example.fileopener.reader.flow.ImageBlock
import com.example.fileopener.reader.flow.PageBreakBlock
import com.example.fileopener.reader.flow.ParagraphBlock
import com.example.fileopener.reader.flow.TableBlock
import com.example.fileopener.reader.flow.TableCell
import com.example.fileopener.reader.flow.TextAlignment
import com.example.fileopener.reader.flow.TextRun
import com.example.fileopener.reader.xml.XmlParserFactory
import com.example.fileopener.reader.xml.attr
import com.example.fileopener.reader.xml.forEachChild
import com.example.fileopener.reader.xml.moveToRoot
import com.example.fileopener.reader.xml.relationshipAttr
import com.example.fileopener.reader.xml.skipElement
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Потоковый парсер DOCX (ZIP + WordprocessingML): абзацы с оформлением, заголовки, списки,
 * таблицы, картинки, гиперссылки. Вёрстка Word (колонтитулы, поля, точное положение объектов)
 * не воспроизводится — текст перетекает по ширине экрана.
 *
 * Нужен файл на диске: [ZipFile] читает части архива в любом порядке (стили до текста),
 * а картинки потом достаются по одной, без повторного чтения всего архива.
 */
class DocxReader(private val parsers: XmlParserFactory) {

    fun read(file: File): FlowDocument {
        val zip = ZipFile(file)
        try {
            val styles = zip.parsePart(STYLES) { DocxStyles.parse(it) } ?: DocxStyles.EMPTY
            val numbering = zip.parsePart(NUMBERING) { DocxNumbering.parse(it) } ?: DocxNumbering.EMPTY
            val relationships = zip.parsePart(RELATIONSHIPS) { DocxRelationships.parse(it) } ?: DocxRelationships.EMPTY
            val blocks = zip.parsePart(DOCUMENT) { BodyParser(it, styles, numbering, relationships).parse() }
                ?: throw IOException("В архиве нет word/document.xml — это не документ Word")
            return FlowDocument.of(blocks, paragraphSpacing = true, resources = ZipResources(zip))
        } catch (e: Throwable) {
            zip.close()
            throw e
        }
    }

    private fun <T> ZipFile.parsePart(path: String, block: (XmlPullParser) -> T): T? {
        val entry = getEntry(path) ?: return null
        return getInputStream(entry).buffered().use { stream ->
            val parser = parsers.create()
            parser.setInput(stream, null)
            block(parser)
        }
    }

    private companion object {
        const val DOCUMENT = "word/document.xml"
        const val STYLES = "word/styles.xml"
        const val NUMBERING = "word/numbering.xml"
        const val RELATIONSHIPS = "word/_rels/document.xml.rels"
    }
}

/** Картинки читаются из того же архива по требованию; доступ к [ZipFile] синхронизирован. */
private class ZipResources(private val zip: ZipFile) : FlowResources {
    override fun loadImage(key: String): ByteArray? = synchronized(zip) {
        val entry = zip.getEntry(key) ?: return null
        if (entry.size > MAX_IMAGE_BYTES) return null
        zip.getInputStream(entry).use { it.readBytes() }
    }

    override fun close() = synchronized(zip) { zip.close() }

    private companion object {
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    }
}

private class BodyParser(
    private val parser: XmlPullParser,
    private val styles: DocxStyles,
    private val numbering: DocxNumbering,
    private val relationships: DocxRelationships,
) {
    fun parse(): List<FlowBlock> {
        val blocks = mutableListOf<FlowBlock>()
        if (!parser.moveToRoot()) return blocks
        parser.forEachChild { name -> if (name == "body") parseBlockContainer(blocks) }
        return blocks
    }

    /** body, ячейка таблицы, содержимое sdt: абзацы и таблицы на уровне блоков. */
    private fun parseBlockContainer(out: MutableList<FlowBlock>) {
        parser.forEachChild { name ->
            when (name) {
                "p" -> parseParagraph(out)
                "tbl" -> out += parseTable()
                "sdt" -> parser.forEachChild { if (it == "sdtContent") parseBlockContainer(out) }
                "customXml", "ins" -> parseBlockContainer(out)
            }
        }
    }

    private class RunCollector {
        val runs = mutableListOf<TextRun>()
        val images = mutableListOf<ImageBlock>()
        var pageBreak = false
    }

    private fun parseParagraph(out: MutableList<FlowBlock>) {
        var props = ParagraphProps()
        val collector = RunCollector()
        parser.forEachChild { name ->
            if (name == "pPr") props = parser.readParagraphProps() else parseInline(name, props, collector, link = null)
        }

        val style = styles[props.styleId]
        val numId = props.numId ?: style?.paragraph?.numId
        val numLevel = props.numLevel ?: style?.paragraph?.numLevel ?: 0
        // numId="0" в Word означает «нумерация снята».
        val marker = numId?.takeIf { it != "0" }?.let { numbering.nextMarker(it, numLevel) }
        val headingLevel = props.outlineLevel?.let { it + 1 }?.takeIf { it in 1..6 } ?: style?.headingLevel ?: 0
        val indentTwips = props.indentTwips ?: style?.paragraph?.indentTwips ?: 0
        val indentLevel = when {
            marker != null -> numLevel + 1
            else -> (indentTwips / TWIPS_PER_INDENT_LEVEL).coerceIn(0, MAX_INDENT_LEVEL)
        }

        val runs = mergeRuns(collector.runs)
        if (runs.isNotEmpty() || collector.images.isEmpty()) {
            out += ParagraphBlock(
                runs = runs,
                headingLevel = headingLevel,
                alignment = props.alignment ?: style?.paragraph?.alignment ?: TextAlignment.START,
                indentLevel = indentLevel,
                listMarker = marker,
            )
        }
        out += collector.images
        if (collector.pageBreak) out += PageBreakBlock
    }

    /** Элементы внутри абзаца: фрагменты, ссылки, вставки, поля и прочие «прозрачные» обёртки. */
    private fun parseInline(name: String, paragraph: ParagraphProps, collector: RunCollector, link: String?) {
        when (name) {
            "r" -> parseRun(paragraph, collector, link)
            "hyperlink" -> {
                val url = relationships.externalUrl(parser.relationshipAttr("id"))
                parser.forEachChild { parseInline(it, paragraph, collector, url ?: link) }
            }
            "ins", "smartTag", "fldSimple", "customXml", "sdtContent", "dir", "bdo" ->
                parser.forEachChild { parseInline(it, paragraph, collector, link) }
            "sdt" -> parser.forEachChild { if (it == "sdtContent") parseInline(it, paragraph, collector, link) }
            // del — удалённый при рецензировании текст, его не показываем.
        }
    }

    private fun parseRun(paragraph: ParagraphProps, collector: RunCollector, link: String?) {
        var direct = RunProps()
        val text = StringBuilder()
        parser.forEachChild { name ->
            when (name) {
                "rPr" -> direct = parser.readRunProps()
                "t" -> text.append(parser.nextText())
                "tab", "ptab" -> text.append('\t')
                "br" -> if (parser.attr("type") == "page") collector.pageBreak = true else text.append('\n')
                "cr" -> text.append('\n')
                "noBreakHyphen" -> text.append('-')
                "softHyphen" -> text.append('­')
                "drawing", "pict", "object", "AlternateContent" -> collectImages(collector.images)
                // instrText (коды полей), delText, footnoteReference и прочее пропускаются.
            }
        }
        if (text.isEmpty()) return
        val paragraphStyle = styles[paragraph.styleId]
        val characterStyle = styles[direct.characterStyleId]
        val props = styles.defaultRun
            .overriddenBy(paragraphStyle?.run ?: RunProps())
            .overriddenBy(characterStyle?.run ?: RunProps())
            .overriddenBy(direct)
        val headingLevel = paragraph.outlineLevel?.let { it + 1 } ?: paragraphStyle?.headingLevel ?: 0
        collector.runs += TextRun(
            text = text.toString(),
            bold = props.bold ?: (headingLevel > 0),
            italic = props.italic ?: false,
            underline = props.underline ?: false,
            strikethrough = props.strikethrough ?: false,
            color = props.color,
            sizeScale = sizeScale(props.sizeHalfPoints, headingLevel),
            baseline = props.baseline ?: Baseline.NORMAL,
            link = link,
        )
    }

    /** Размер относительно основного текста; заголовки без явного размера получают типовой масштаб. */
    private fun sizeScale(sizeHalfPoints: Int?, headingLevel: Int): Float {
        val explicit = sizeHalfPoints?.takeIf { it > 0 }?.let { it.toFloat() / styles.defaultSizeHalfPoints }
        val scale = when {
            explicit != null && (explicit != 1f || headingLevel == 0) -> explicit
            headingLevel > 0 -> HEADING_SCALES.getOrElse(headingLevel - 1) { 1f }
            else -> 1f
        }
        return scale.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /**
     * Ищет картинки в drawing/pict/AlternateContent: `a:blip r:embed` (DrawingML) и `v:imagedata r:id` (VML).
     * Ветка `mc:Fallback` пропускается — в ней та же картинка в старом формате.
     */
    private fun collectImages(out: MutableList<ImageBlock>) {
        var aspect: Float? = null
        fun walk() {
            parser.forEachChild { name ->
                when (name) {
                    "Fallback" -> parser.skipElement()
                    "extent" -> {
                        val cx = parser.attr("cx")?.toFloatOrNull()
                        val cy = parser.attr("cy")?.toFloatOrNull()
                        if (cx != null && cy != null && cx > 0f) aspect = cy / cx
                    }
                    "blip" -> relationships.partPath(parser.relationshipAttr("embed"))?.let { out += ImageBlock(it, aspect) }
                    "imagedata" -> relationships.partPath(parser.relationshipAttr("id"))?.let { out += ImageBlock(it, aspect) }
                    else -> walk()
                }
            }
        }
        walk()
    }

    private fun parseTable(): TableBlock {
        val rows = mutableListOf<List<TableCell>>()
        parser.forEachChild { name ->
            if (name != "tr") return@forEachChild
            val cells = mutableListOf<TableCell>()
            parser.forEachChild { cellName ->
                if (cellName == "tc") cells += parseCell()
            }
            if (cells.isNotEmpty()) rows += cells
        }
        return TableBlock(rows)
    }

    private fun parseCell(): TableCell {
        var span = 1
        var mergedContinuation = false
        val blocks = mutableListOf<FlowBlock>()
        parser.forEachChild { name ->
            when (name) {
                "tcPr" -> parser.forEachChild { prop ->
                    when (prop) {
                        "gridSpan" -> span = parser.attr("val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        // Продолжение вертикально объединённой ячейки: текст уже в верхней ячейке.
                        "vMerge" -> mergedContinuation = parser.attr("val").let { it == null || it == "continue" }
                    }
                }
                "p" -> parseParagraph(blocks)
                // Вложенная таблица разворачивается в абзацы ячейки.
                "tbl" -> parseTable().rows.flatten().forEach { cell -> blocks += cell.paragraphs }
                "sdt" -> parser.forEachChild { if (it == "sdtContent") parseBlockContainer(blocks) }
            }
        }
        val paragraphs = if (mergedContinuation) emptyList() else blocks.filterIsInstance<ParagraphBlock>()
        return TableCell(paragraphs, span)
    }

    /** Соседние фрагменты с одинаковым оформлением склеиваются: Word дробит текст на десятки run-ов. */
    private fun mergeRuns(runs: List<TextRun>): List<TextRun> {
        if (runs.size < 2) return runs
        val merged = ArrayList<TextRun>(runs.size)
        for (run in runs) {
            val last = merged.lastOrNull()
            if (last != null && last.copy(text = run.text) == run) {
                merged[merged.lastIndex] = last.copy(text = last.text + run.text)
            } else {
                merged += run
            }
        }
        return merged
    }

    private companion object {
        /** ~1,27 см отступа = один уровень. */
        const val TWIPS_PER_INDENT_LEVEL = 720
        const val MAX_INDENT_LEVEL = 4
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 2.5f
        val HEADING_SCALES = listOf(1.6f, 1.4f, 1.25f, 1.15f, 1.05f, 1.05f)
    }
}

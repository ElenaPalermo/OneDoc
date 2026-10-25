package io.doccraftstudio.onedoc.reader.docx

import io.doccraftstudio.onedoc.reader.flow.Baseline
import io.doccraftstudio.onedoc.reader.flow.TextAlignment
import io.doccraftstudio.onedoc.reader.xml.attr
import io.doccraftstudio.onedoc.reader.xml.forEachChild
import io.doccraftstudio.onedoc.reader.xml.moveToRoot
import io.doccraftstudio.onedoc.reader.xml.toggleValue
import org.xmlpull.v1.XmlPullParser

/** Оформление фрагмента; `null` — не задано на этом уровне (наследуется от стиля). */
internal data class RunProps(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strikethrough: Boolean? = null,
    val color: Int? = null,
    val sizeHalfPoints: Int? = null,
    val baseline: Baseline? = null,
    val characterStyleId: String? = null,
) {
    /** Значения [over] важнее значений этого уровня. */
    fun overriddenBy(over: RunProps) = RunProps(
        bold = over.bold ?: bold,
        italic = over.italic ?: italic,
        underline = over.underline ?: underline,
        strikethrough = over.strikethrough ?: strikethrough,
        color = over.color ?: color,
        sizeHalfPoints = over.sizeHalfPoints ?: sizeHalfPoints,
        baseline = over.baseline ?: baseline,
        characterStyleId = over.characterStyleId ?: characterStyleId,
    )
}

internal data class ParagraphProps(
    val styleId: String? = null,
    val numId: String? = null,
    val numLevel: Int? = null,
    val alignment: TextAlignment? = null,
    val outlineLevel: Int? = null,
    val indentTwips: Int? = null,
)

internal fun XmlPullParser.readRunProps(): RunProps {
    var props = RunProps()
    forEachChild { name ->
        props = when (name) {
            "b" -> props.copy(bold = toggleValue())
            "i" -> props.copy(italic = toggleValue())
            "u" -> props.copy(underline = attr("val").let { it != null && it != "none" })
            "strike", "dstrike" -> props.copy(strikethrough = toggleValue())
            "color" -> props.copy(color = parseColor(attr("val")))
            "sz" -> props.copy(sizeHalfPoints = attr("val")?.toIntOrNull())
            "vertAlign" -> props.copy(
                baseline = when (attr("val")) {
                    "superscript" -> Baseline.SUPERSCRIPT
                    "subscript" -> Baseline.SUBSCRIPT
                    else -> Baseline.NORMAL
                },
            )
            "rStyle" -> props.copy(characterStyleId = attr("val"))
            else -> props
        }
    }
    return props
}

internal fun XmlPullParser.readParagraphProps(): ParagraphProps {
    var props = ParagraphProps()
    forEachChild { name ->
        props = when (name) {
            "pStyle" -> props.copy(styleId = attr("val"))
            "numPr" -> {
                var numId: String? = null
                var level: Int? = null
                forEachChild { child ->
                    when (child) {
                        "numId" -> numId = attr("val")
                        "ilvl" -> level = attr("val")?.toIntOrNull()
                    }
                }
                props.copy(numId = numId, numLevel = level)
            }
            "jc" -> props.copy(alignment = parseAlignment(attr("val")))
            "outlineLvl" -> props.copy(outlineLevel = attr("val")?.toIntOrNull())
            "ind" -> props.copy(indentTwips = (attr("left") ?: attr("start"))?.toIntOrNull())
            else -> props
        }
    }
    return props
}

private fun parseColor(value: String?): Int? {
    if (value == null || value.equals("auto", ignoreCase = true) || value.length != 6) return null
    return value.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
}

private fun parseAlignment(value: String?): TextAlignment? = when (value) {
    "center" -> TextAlignment.CENTER
    "right", "end" -> TextAlignment.END
    "both", "distribute" -> TextAlignment.JUSTIFY
    "left", "start" -> TextAlignment.START
    else -> null
}

/** Стиль из styles.xml с уже применённой цепочкой `basedOn`. */
internal data class StyleDef(
    val headingLevel: Int = 0,
    val paragraph: ParagraphProps = ParagraphProps(),
    val run: RunProps = RunProps(),
)

/** Стили документа и размер шрифта по умолчанию (в полупунктах). */
internal class DocxStyles(
    private val styles: Map<String, StyleDef>,
    val defaultSizeHalfPoints: Int,
    val defaultRun: RunProps,
) {
    operator fun get(styleId: String?): StyleDef? = styleId?.let(styles::get)

    companion object {
        /** По спецификации OOXML, если размер нигде не задан, — 10 пт. */
        private const val SPEC_DEFAULT_SIZE = 20
        private val HEADING_NAME = Regex("""^(heading|заголовок)\s*(\d)$""", RegexOption.IGNORE_CASE)

        val EMPTY = DocxStyles(emptyMap(), SPEC_DEFAULT_SIZE, RunProps())

        fun parse(parser: XmlPullParser): DocxStyles {
            data class RawStyle(
                val name: String?,
                val basedOn: String?,
                val paragraph: ParagraphProps,
                val run: RunProps,
            )

            val raw = mutableMapOf<String, RawStyle>()
            var defaultRun = RunProps()
            if (!parser.moveToRoot()) return EMPTY
            parser.forEachChild { name ->
                when (name) {
                    "docDefaults" -> parser.forEachChild { section ->
                        if (section == "rPrDefault") {
                            parser.forEachChild { if (it == "rPr") defaultRun = parser.readRunProps() }
                        }
                    }
                    "style" -> {
                        val id = parser.attr("styleId") ?: return@forEachChild
                        var styleName: String? = null
                        var basedOn: String? = null
                        var paragraph = ParagraphProps()
                        var run = RunProps()
                        parser.forEachChild { child ->
                            when (child) {
                                "name" -> styleName = parser.attr("val")
                                "basedOn" -> basedOn = parser.attr("val")
                                "pPr" -> paragraph = parser.readParagraphProps()
                                "rPr" -> run = parser.readRunProps()
                            }
                        }
                        raw[id] = RawStyle(styleName, basedOn, paragraph, run)
                    }
                }
            }

            val resolved = mutableMapOf<String, StyleDef>()
            fun resolve(id: String, seen: Set<String>): StyleDef {
                resolved[id]?.let { return it }
                val style = raw[id] ?: return StyleDef()
                val parent = style.basedOn?.takeIf { it !in seen }?.let { resolve(it, seen + id) } ?: StyleDef()
                val ownHeading = headingLevel(style.name) ?: style.paragraph.outlineLevel?.let { it + 1 }
                val result = StyleDef(
                    headingLevel = (ownHeading ?: parent.headingLevel).takeIf { it in 1..MAX_HEADING } ?: 0,
                    paragraph = ParagraphProps(
                        numId = style.paragraph.numId ?: parent.paragraph.numId,
                        numLevel = style.paragraph.numLevel ?: parent.paragraph.numLevel,
                        alignment = style.paragraph.alignment ?: parent.paragraph.alignment,
                        indentTwips = style.paragraph.indentTwips ?: parent.paragraph.indentTwips,
                    ),
                    run = parent.run.overriddenBy(style.run),
                )
                resolved[id] = result
                return result
            }
            raw.keys.forEach { resolve(it, emptySet()) }
            return DocxStyles(resolved, defaultRun.sizeHalfPoints ?: SPEC_DEFAULT_SIZE, defaultRun)
        }

        private const val MAX_HEADING = 6

        private fun headingLevel(name: String?): Int? {
            if (name == null) return null
            if (name.equals("title", ignoreCase = true)) return 1
            if (name.equals("subtitle", ignoreCase = true)) return 2
            return HEADING_NAME.find(name.trim())?.groupValues?.get(2)?.toIntOrNull()
        }
    }
}

/** Уровень списка из numbering.xml. */
internal data class ListLevel(val format: String, val text: String, val start: Int)

/**
 * Нумерация списков: определения уровней и счётчики. Номера считаются по мере обхода документа,
 * как это делает Word: увеличение уровня сбрасывает счётчики вложенных уровней.
 */
internal class DocxNumbering(private val levels: Map<String, Map<Int, ListLevel>>) {
    private val counters = mutableMapOf<String, IntArray>()

    fun nextMarker(numId: String, level: Int): String? {
        val definition = levels[numId] ?: return null
        val levelDef = definition[level] ?: return null
        if (levelDef.format == "bullet") return BULLETS[level % BULLETS.size]
        if (levelDef.format == "none") return ""
        val values = counters.getOrPut(numId) { IntArray(MAX_LEVELS) { UNSET } }
        values[level] = if (values[level] == UNSET) levelDef.start else values[level] + 1
        for (deeper in level + 1 until MAX_LEVELS) values[deeper] = UNSET
        return PLACEHOLDER.replace(levelDef.text) { match ->
            val referenced = match.groupValues[1].toInt() - 1
            val value = values.getOrNull(referenced)?.takeIf { it != UNSET }
                ?: definition[referenced]?.start
                ?: 1
            formatNumber(value, definition[referenced]?.format ?: "decimal")
        }
    }

    companion object {
        private const val MAX_LEVELS = 9
        private const val UNSET = Int.MIN_VALUE
        private val BULLETS = listOf("•", "◦", "▪")
        private val PLACEHOLDER = Regex("%(\\d)")

        val EMPTY = DocxNumbering(emptyMap())

        fun parse(parser: XmlPullParser): DocxNumbering {
            val abstracts = mutableMapOf<String, MutableMap<Int, ListLevel>>()
            val nums = mutableMapOf<String, Pair<String, Map<Int, Int>>>()
            if (!parser.moveToRoot()) return EMPTY
            parser.forEachChild { name ->
                when (name) {
                    "abstractNum" -> {
                        val id = parser.attr("abstractNumId") ?: return@forEachChild
                        val levelMap = abstracts.getOrPut(id) { mutableMapOf() }
                        parser.forEachChild { child ->
                            if (child != "lvl") return@forEachChild
                            val level = parser.attr("ilvl")?.toIntOrNull() ?: return@forEachChild
                            var format = "decimal"
                            var text = "%${level + 1}."
                            var start = 1
                            parser.forEachChild { prop ->
                                when (prop) {
                                    "numFmt" -> format = parser.attr("val") ?: format
                                    "lvlText" -> text = parser.attr("val") ?: text
                                    "start" -> start = parser.attr("val")?.toIntOrNull() ?: start
                                }
                            }
                            levelMap[level] = ListLevel(format, text, start)
                        }
                    }
                    "num" -> {
                        val numId = parser.attr("numId") ?: return@forEachChild
                        var abstractId: String? = null
                        val startOverrides = mutableMapOf<Int, Int>()
                        parser.forEachChild { child ->
                            when (child) {
                                "abstractNumId" -> abstractId = parser.attr("val")
                                "lvlOverride" -> {
                                    val level = parser.attr("ilvl")?.toIntOrNull()
                                    parser.forEachChild { override ->
                                        val start = parser.attr("val")?.toIntOrNull()
                                        if (override == "startOverride" && level != null && start != null) {
                                            startOverrides[level] = start
                                        }
                                    }
                                }
                            }
                        }
                        abstractId?.let { nums[numId] = it to startOverrides }
                    }
                }
            }
            val levels = nums.mapValues { (_, value) ->
                val (abstractId, overrides) = value
                abstracts[abstractId].orEmpty().mapValues { (level, def) ->
                    overrides[level]?.let { def.copy(start = it) } ?: def
                }
            }
            return DocxNumbering(levels)
        }

        internal fun formatNumber(value: Int, format: String): String = when (format) {
            "lowerLetter" -> letters(value).lowercase()
            "upperLetter" -> letters(value)
            "lowerRoman" -> roman(value).lowercase()
            "upperRoman" -> roman(value)
            "decimalZero" -> value.toString().padStart(2, '0')
            "russianLower" -> russianLetter(value)
            "russianUpper" -> russianLetter(value).uppercase()
            else -> value.toString()
        }

        /** 1 → A, 26 → Z, 27 → AA (как в Word). */
        private fun letters(value: Int): String {
            if (value <= 0) return value.toString()
            val letter = 'A' + (value - 1) % 26
            return letter.toString().repeat((value - 1) / 26 + 1)
        }

        private fun russianLetter(value: Int): String {
            // Без «ё», «й», «ъ», «ы», «ь» — как в нумерации Word.
            val alphabet = "абвгдежзиклмнопрстуфхцчшщэюя"
            if (value <= 0) return value.toString()
            return alphabet[(value - 1) % alphabet.length].toString().repeat((value - 1) / alphabet.length + 1)
        }

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
}

/** Связи document.xml: идентификатор → путь к части архива (картинки) или внешняя ссылка. */
internal class DocxRelationships(private val targets: Map<String, Pair<String, Boolean>>) {
    /** Путь к части внутри архива (например, `word/media/image1.png`). */
    fun partPath(id: String?): String? {
        val (target, external) = targets[id] ?: return null
        if (external) return null
        return if (target.startsWith("/")) target.removePrefix("/") else "word/" + target.removePrefix("./")
    }

    /** Адрес внешней гиперссылки. */
    fun externalUrl(id: String?): String? = targets[id]?.takeIf { it.second }?.first

    companion object {
        val EMPTY = DocxRelationships(emptyMap())

        fun parse(parser: XmlPullParser): DocxRelationships {
            val targets = mutableMapOf<String, Pair<String, Boolean>>()
            if (!parser.moveToRoot()) return EMPTY
            parser.forEachChild { name ->
                if (name != "Relationship") return@forEachChild
                val id = parser.attr("Id") ?: return@forEachChild
                val target = parser.attr("Target") ?: return@forEachChild
                targets[id] = target to (parser.attr("TargetMode") == "External")
            }
            return DocxRelationships(targets)
        }
    }
}

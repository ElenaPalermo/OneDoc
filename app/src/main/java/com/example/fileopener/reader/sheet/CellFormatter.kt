package com.example.fileopener.reader.sheet

import org.apache.poi.ss.usermodel.BuiltinFormats
import org.apache.poi.ss.usermodel.DataFormatter
import java.math.BigDecimal
import java.math.MathContext
import java.text.DateFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * Форматирует числа и даты по коду формата Excel (`#,##0.00`, `dd.mm.yyyy`, `0%`…) через
 * `DataFormatter` из ядра POI. Не потокобезопасен: на каждый разбираемый лист — свой экземпляр.
 *
 * Многосекционные форматы (`плюс;минус;ноль`) разбираются здесь: `DataFormatter` отдаёт их
 * в `CellFormat`, который использует `java.awt.Color`, отсутствующий на Android.
 */
class CellFormatter(private val locale: Locale = Locale.getDefault()) {
    private val formatter = DataFormatter(locale)

    /** Встроенный формат 14 в Excel показывается коротким форматом даты системы, а не `m/d/yy`. */
    private val shortDate: String = excelShortDatePattern(locale)

    private val decimalSeparator = DecimalFormatSymbols.getInstance(locale).decimalSeparator

    /** Разобранные коды форматов: у листа их единицы, а ячеек — миллионы. */
    private val parsedFormats = HashMap<String, ParsedFormat>()

    fun formatNumber(value: Double, formatIndex: Int, formatCode: String?, date1904: Boolean = false): String {
        if (value.isNaN() || value.isInfinite()) return ERROR_NUM
        // Самый частый случай — «Общий» формат; без разбора кода и без DataFormatter.
        if (formatIndex == 0 && formatCode.isNullOrBlank()) return general(value)
        val code = when (formatIndex) {
            SHORT_DATE_FORMAT -> shortDate
            SHORT_DATE_TIME_FORMAT -> "$shortDate h:mm"
            else -> formatCode?.takeIf { it.isNotBlank() } ?: BuiltinFormats.getBuiltinFormat(formatIndex) ?: GENERAL
        }
        val format = parsedFormats.getOrPut(code) { ParsedFormat.of(code) }
        val (index, sectionValue) = format.select(value)
        val section = format.sections[index]
        if (section.isEmpty()) return ""
        format.literals[index]?.let { return it }
        return try {
            if (format.general[index]) {
                general(sectionValue)
            } else {
                formatter.formatRawCellContents(sectionValue, formatIndex, section, date1904)
            }
        } catch (e: RuntimeException) {
            general(value)
        } catch (e: LinkageError) {
            // Части POI, завязанные на java.awt, на Android недоступны — показываем число как есть.
            general(value)
        }
    }

    /**
     * Число, записанное в файле текстом ([raw] — значение `<v>` XLSX), в формате [formatIndex]/[formatCode];
     * `null`, если это не число. Для «Общего» формата простая запись (`835765.10`) показывается
     * без перевода в `Double`: так в разы быстрее на листах из миллионов чисел.
     */
    fun formatNumberText(raw: String, formatIndex: Int, formatCode: String?, date1904: Boolean = false): String? {
        if (formatIndex == 0 && formatCode.isNullOrBlank()) generalText(raw)?.let { return it }
        // parseDouble с исключением дешевле toDoubleOrNull: тот сначала проверяет строку регулярным выражением.
        val value = try {
            java.lang.Double.parseDouble(raw)
        } catch (e: NumberFormatException) {
            return null
        }
        return formatNumber(value, formatIndex, formatCode, date1904)
    }

    /** «Общий» формат Excel: целые без дробной части, остальное — до 10 значащих цифр. */
    fun general(value: Double): String {
        val magnitude = abs(value)
        if (value == round(value) && magnitude < MAX_PLAIN_INTEGER) return value.toLong().toString()
        if (magnitude >= MIN_PLAIN_FRACTION && magnitude < MAX_PLAIN_FRACTION) {
            // Кратчайшая запись Double обычно уже не длиннее 10 значащих цифр — тогда округлять нечего.
            generalText(value.toString())?.let { return it }
            val text = BigDecimal.valueOf(value).round(GENERAL_PRECISION).stripTrailingZeros().toPlainString()
            return if (decimalSeparator == '.') text else text.replace('.', decimalSeparator)
        }
        // Очень большие и очень маленькие числа — в экспоненциальной записи.
        return formatter.formatRawCellContents(value, 0, GENERAL)
    }

    /**
     * «Общий» формат для десятичной записи без экспоненты (`-1234.50`), если округлять не нужно:
     * не больше 10 значащих цифр и число в диапазоне, где Excel не переходит на экспоненту.
     * Иначе `null` — тогда считать через `Double`.
     */
    internal fun generalText(raw: String): String? {
        val length = raw.length
        var i = if (length > 0 && raw[0] == '-') 1 else 0
        val integerStart = i
        while (i < length && raw[i] in '0'..'9') i++
        val integerEnd = i
        val integerLength = integerEnd - integerStart
        if (integerLength == 0 || (integerLength > 1 && raw[integerStart] == '0')) return null
        var fractionEnd = integerEnd
        if (i < length) {
            if (raw[i] != '.') return null
            i++
            while (i < length && raw[i] in '0'..'9') i++
            if (i != length || i == integerEnd + 1) return null
            fractionEnd = length
            while (fractionEnd > integerEnd + 1 && raw[fractionEnd - 1] == '0') fractionEnd--
            if (fractionEnd == integerEnd + 1) fractionEnd = integerEnd
        }
        val zeroInteger = integerLength == 1 && raw[integerStart] == '0'
        if (fractionEnd == integerEnd) {
            // Целое: −0 показывается как 0, от 10^15 Excel переходит на экспоненту.
            if (zeroInteger) return "0"
            if (integerLength > MAX_PLAIN_INTEGER_DIGITS) return null
            return if (fractionEnd == length) raw else raw.substring(0, integerEnd)
        }
        val fractionStart = integerEnd + 1
        val significant = if (zeroInteger) {
            var leadingZeros = 0
            while (raw[fractionStart + leadingZeros] == '0') leadingZeros++
            // Меньше 10^-9 — экспонента.
            if (leadingZeros >= MAX_FRACTION_LEADING_ZEROS) return null
            fractionEnd - fractionStart - leadingZeros
        } else {
            integerLength + fractionEnd - fractionStart
        }
        if (significant > GENERAL_DIGITS) return null
        val text = if (fractionEnd == length) raw else raw.substring(0, fractionEnd)
        return if (decimalSeparator == '.') text else text.replace('.', decimalSeparator)
    }

    /** Код формата, разбитый на секции, с заранее вычисленными признаками каждой секции. */
    private class ParsedFormat(val sections: List<String>, private val hasCondition: Boolean) {
        val literals: List<String?> = sections.map(::literalText)
        val general: List<Boolean> = sections.map { it.equals(GENERAL, ignoreCase = true) || it == TEXT_FORMAT }

        /** Индекс секции для значения и значение для неё (в секции отрицательных — по модулю). */
        fun select(value: Double): Pair<Int, Double> = when {
            sections.size == 1 || hasCondition -> 0 to value
            value < 0 -> 1 to -value
            value == 0.0 && sections.size >= 3 -> 2 to value
            else -> 0 to value
        }

        companion object {
            fun of(code: String): ParsedFormat {
                val sections = splitSections(code)
                return ParsedFormat(sections, sections.size > 1 && sections.any { CONDITION.containsMatchIn(it) })
            }
        }
    }

    // Как в Excel — на языке интерфейса; при языке без перевода интерфейс русский, поэтому и значения русские.
    fun formatBoolean(value: Boolean): String = when (locale.language) {
        "en" -> if (value) "TRUE" else "FALSE"
        "it" -> if (value) "VERO" else "FALSO"
        else -> if (value) "ИСТИНА" else "ЛОЖЬ"
    }

    companion object {
        private const val GENERAL = "General"
        private const val TEXT_FORMAT = "@"
        private const val SHORT_DATE_FORMAT = 14
        private const val SHORT_DATE_TIME_FORMAT = 22
        private const val ERROR_NUM = "#NUM!"
        private const val MAX_PLAIN_INTEGER = 1e15
        private const val MIN_PLAIN_FRACTION = 1e-9
        private const val MAX_PLAIN_FRACTION = 1e11
        private const val GENERAL_DIGITS = 10
        private val GENERAL_PRECISION = MathContext(GENERAL_DIGITS)
        /** Целые до 10^15 (`MAX_PLAIN_INTEGER`) — не больше 15 цифр. */
        private const val MAX_PLAIN_INTEGER_DIGITS = 15
        /** Дроби от 10^-9 (`MIN_PLAIN_FRACTION`): 0.000000001 — восемь нулей после точки. */
        private const val MAX_FRACTION_LEADING_ZEROS = 9
        private val CONDITION = Regex("""\[[<>=]""")

        /**
         * Секция формата для значения: `плюс;минус;ноль[;текст]`. В секции для отрицательных
         * значение передаётся по модулю — знак, если нужен, записан в самой секции.
         * Форматы с условиями (`[>100]`) не поддерживаются — берётся первая секция.
         */
        internal fun selectSection(code: String, value: Double): Pair<String, Double> {
            val format = ParsedFormat.of(code)
            val (index, sectionValue) = format.select(value)
            return format.sections[index] to sectionValue
        }

        /**
         * Секция без знаков для цифр и дат (`"—"`, `"нет"`) показывает только свой текст;
         * `DataFormatter` такие секции не понимает. Для обычных секций — `null`.
         */
        internal fun literalText(section: String): String? {
            if (section.equals(GENERAL, ignoreCase = true) || section == TEXT_FORMAT) return null
            val text = StringBuilder()
            var quoted = false
            var bracketed = false
            var i = 0
            while (i < section.length) {
                val char = section[i]
                when {
                    quoted -> if (char == '"') quoted = false else text.append(char)
                    char == '"' -> quoted = true
                    bracketed -> if (char == ']') bracketed = false
                    char == '[' -> bracketed = true
                    char == '\\' -> section.getOrNull(++i)?.let(text::append)
                    // _x — отступ шириной символа x, *x — заполнение; в тексте не показываются.
                    char == '_' || char == '*' -> i++
                    char in PLACEHOLDERS || char.lowercaseChar() in DATE_TOKENS -> return null
                    else -> text.append(char)
                }
                i++
            }
            return text.toString()
        }

        private const val PLACEHOLDERS = "0#?@"
        private const val DATE_TOKENS = "dmyhse"

        /** Делит код формата по `;`, не считая разделители в кавычках, скобках и после `\`. */
        private fun splitSections(code: String): List<String> {
            val sections = ArrayList<String>(4)
            val current = StringBuilder()
            var quoted = false
            var bracketed = false
            var escaped = false
            for (char in code) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = !quoted
                    !quoted && char == '[' -> bracketed = true
                    !quoted && char == ']' -> bracketed = false
                    !quoted && !bracketed && char == ';' -> {
                        sections += current.toString()
                        current.clear()
                        continue
                    }
                }
                current.append(char)
            }
            sections += current.toString()
            return sections
        }

        /** Короткий формат даты локали в нотации Excel с четырёхзначным годом: `dd.mm.yyyy`, `m/d/yyyy`. */
        internal fun excelShortDatePattern(locale: Locale): String {
            val pattern = (DateFormat.getDateInstance(DateFormat.SHORT, locale) as? SimpleDateFormat)?.toPattern()
            if (pattern == null || pattern.any { it.isLetter() && it !in "dMy" }) return "dd.mm.yyyy"
            return pattern.replace('M', 'm').replace(Regex("y+"), "yyyy")
        }
    }
}

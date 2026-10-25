package io.doccraftstudio.onedoc.reader.sheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlin.random.Random

class CellFormatterTest {
    private val us = CellFormatter(Locale.US)
    private val ru = CellFormatter(Locale.forLanguageTag("ru-RU"))

    @Test
    fun builtinNumberFormats() {
        assertEquals("1,234.50", us.formatNumber(1234.5, 4, null))
        assertEquals("12.50%", us.formatNumber(0.125, 10, null))
        assertEquals("1235", us.formatNumber(1234.5, 1, null))
    }

    @Test
    fun general_dropsTrailingZerosAndFloatingNoise() {
        assertEquals("1234567", us.formatNumber(1234567.0, 0, null))
        assertEquals("0.3", us.formatNumber(0.1 + 0.2, 0, null))
        assertEquals("0,3", ru.formatNumber(0.1 + 0.2, 0, null))
    }

    @Test
    fun builtinShortDate_followsLocale() {
        // 45292 — 1 января 2024 в системе дат 1900.
        assertEquals("01.01.2024", ru.formatNumber(45292.0, 14, null))
        assertEquals("1/1/2024", us.formatNumber(45292.0, 14, null))
        assertEquals("dd.mm.yyyy", CellFormatter.excelShortDatePattern(Locale.forLanguageTag("ru-RU")))
    }

    @Test
    fun customDateAndTime() {
        assertEquals("01.01.2024 06:00", us.formatNumber(45292.25, 164, "dd.mm.yyyy hh:mm"))
        assertEquals("01.01.2024", us.formatNumber(45292.0, 164, "dd.mm.yyyy"))
    }

    @Test
    fun date1904() {
        // В системе 1904 тот же серийный номер на 1462 дня позже.
        assertEquals("01.01.2024", us.formatNumber(45292.0 - 1462, 164, "dd.mm.yyyy", date1904 = true))
    }

    @Test
    fun sections_positiveNegativeZero() {
        val code = "0.00;(0.00);\"—\""
        assertEquals("3.00", us.formatNumber(3.0, 165, code))
        assertEquals("(5.00)", us.formatNumber(-5.0, 165, code))
        assertEquals("—", us.formatNumber(0.0, 165, code))
        // Две секции: ноль показывается первой; пустая секция скрывает значение.
        assertEquals("0", us.formatNumber(0.0, 166, "0;-0"))
        assertEquals("", us.formatNumber(-1.0, 167, "0;"))
    }

    @Test
    fun selectSection_ignoresSemicolonsInQuotes() {
        assertEquals("\"a;b\"0" to 2.0, CellFormatter.selectSection("\"a;b\"0", 2.0))
        assertEquals("[Red]-0" to 2.0, CellFormatter.selectSection("0;[Red]-0", -2.0))
    }

    @Test
    fun textFormat_showsNumberAsGeneral() {
        assertEquals("42", us.formatNumber(42.0, 49, null))
    }

    @Test
    fun booleans_areLocalized() {
        assertEquals("ИСТИНА", ru.formatBoolean(true))
        assertEquals("FALSE", us.formatBoolean(false))
        assertEquals("VERO", CellFormatter(Locale.ITALY).formatBoolean(true))
        assertEquals("FALSO", CellFormatter(Locale.ITALY).formatBoolean(false))
        assertEquals("ИСТИНА", CellFormatter(Locale.GERMANY).formatBoolean(true))
    }

    @Test
    fun generalText_shortDecimalsWithoutDouble() {
        assertEquals("835765.1", us.generalText("835765.10"))
        assertEquals("-12.5", us.generalText("-12.500"))
        assertEquals("12", us.generalText("12.00"))
        assertEquals("0", us.generalText("-0.0"))
        assertEquals("0.000000001", us.generalText("0.000000001"))
        assertEquals("0,25", ru.generalText("0.25"))
        assertEquals("123456789012345", us.generalText("123456789012345"))
    }

    @Test
    fun generalText_leavesHardCasesToDouble() {
        // Больше 10 значащих цифр — нужно округление.
        assertNull(us.generalText("0.30000000000000004"))
        assertNull(us.generalText("12345.678901"))
        // Экспонента, мелкие и большие числа, не числа.
        assertNull(us.generalText("1E-3"))
        assertNull(us.generalText("0.0000000001"))
        assertNull(us.generalText("1234567890123456"))
        assertNull(us.generalText("007"))
        assertNull(us.generalText(".5"))
        assertNull(us.generalText("12."))
        assertNull(us.generalText("abc"))
        assertNull(us.generalText(""))
    }

    @Test
    fun formatNumberText_matchesDoubleFormatting() {
        val random = Random(42)
        repeat(20_000) {
            val value = when (it % 5) {
                0 -> random.nextDouble() * 1e6
                1 -> (random.nextDouble() - 0.5) * 1e12
                2 -> random.nextDouble() * 1e-6
                3 -> (random.nextInt(2_000_000) - 1_000_000) / 100.0
                else -> random.nextLong(-1_000_000_000_000_000, 1_000_000_000_000_000).toDouble()
            }
            for (raw in listOf(value.toString(), "%.2f".format(Locale.US, value), "%.4f".format(Locale.US, value))) {
                val expected = referenceGeneral(java.lang.Double.parseDouble(raw))
                assertEquals(raw, expected, ru.formatNumberText(raw, 0, null))
            }
        }
        assertNull(us.formatNumberText("текст", 0, null))
        assertEquals("1,234.50", us.formatNumberText("1234.5", 4, null))
    }

    /** «Общий» формат прежним путём — через BigDecimal, без быстрого пути. */
    private fun referenceGeneral(value: Double): String {
        val magnitude = abs(value)
        if (value == round(value) && magnitude < 1e15) return value.toLong().toString()
        if (magnitude >= 1e-9 && magnitude < 1e11) {
            return BigDecimal.valueOf(value).round(MathContext(10)).stripTrailingZeros().toPlainString().replace('.', ',')
        }
        return ru.formatNumber(value, 0, null)
    }
}

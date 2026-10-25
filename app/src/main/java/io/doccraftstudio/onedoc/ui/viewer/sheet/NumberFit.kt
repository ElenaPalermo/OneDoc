package io.doccraftstudio.onedoc.ui.viewer.sheet

import java.math.BigDecimal
import java.math.RoundingMode

private val PLAIN_DECIMAL = Regex("""^(-?)(\d+)([.,])(\d+)$""")

/**
 * Укорачивает десятичную дробь до [maxChars] символов округлением дробной части — так Excel показывает
 * числа в «Общем» формате, которые не помещаются в колонку (835765.12 → 835765.1).
 * `null`, если укоротить нельзя: не влезает целая часть или текст — не простая дробь (валюта, проценты, даты).
 */
internal fun fitDecimal(text: String, maxChars: Int): String? {
    val match = PLAIN_DECIMAL.matchEntire(text) ?: return null
    val (sign, integer, separator, fraction) = match.destructured
    val fractionDigits = minOf(maxChars - sign.length - integer.length - separator.length, fraction.length - 1)
    if (maxChars < sign.length + integer.length) return null
    val value = BigDecimal("$sign$integer.$fraction")
        .setScale(fractionDigits.coerceAtLeast(0), RoundingMode.HALF_UP)
        .stripTrailingZeros()
    val result = value.toPlainString().replace('.', separator.single())
    // Округление может добавить разряд (999.96 → 1000).
    return result.takeIf { it.length <= maxChars && it.length < text.length }
}

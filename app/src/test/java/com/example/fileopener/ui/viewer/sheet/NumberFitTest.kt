package com.example.fileopener.ui.viewer.sheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberFitTest {
    @Test
    fun roundsFractionToFitColumn() {
        assertEquals("835765.1", fitDecimal("835765.12", 8))
        assertEquals("835765", fitDecimal("835765.12", 7))
        assertEquals("-12,35", fitDecimal("-12,345", 6))
        assertEquals("1", fitDecimal("0.95", 3))
    }

    @Test
    fun givesUpWhenIntegerPartDoesNotFit() {
        assertNull(fitDecimal("835765.12", 5))
        // Округление добавило разряд и не влезло.
        assertNull(fitDecimal("999.96", 3))
    }

    @Test
    fun leavesFormattedNumbersAlone() {
        assertNull(fitDecimal("1 234,50 ₽", 6))
        assertNull(fitDecimal("12,5%", 3))
        assertNull(fitDecimal("123456", 4))
    }
}

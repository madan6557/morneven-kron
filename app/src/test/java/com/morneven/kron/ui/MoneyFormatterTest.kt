package com.morneven.kron.ui

import com.morneven.kron.ui.components.formatMoneyInput
import com.morneven.kron.ui.components.formatIdr
import com.morneven.kron.ui.components.parseMoneyInput
import com.morneven.kron.ui.components.sanitizeMoneyDigits
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyFormatterTest {
    @Test
    fun acceptsCommonPasteSeparators() {
        val expected = 1_234_567L
        assertEquals(expected, parseMoneyInput("1,234,567"))
        assertEquals(expected, parseMoneyInput("1.234.567"))
        assertEquals(expected, parseMoneyInput("Rp 1.234.567"))
    }

    @Test
    fun groupsDigitsAndRemovesLeadingZeroes() {
        assertEquals("1234567", sanitizeMoneyDigits("0001234567"))
        assertEquals("1.234.567", formatMoneyInput("0001234567"))
        assertEquals("", formatMoneyInput("0"))
        assertEquals("Rp 1.234.567", formatIdr(1_234_567))
    }

    @Test
    fun overflowIsNotFormattedAsAnotherAmount() {
        assertEquals(0L, parseMoneyInput("9223372036854775808"))
        assertEquals("", formatMoneyInput("9223372036854775808"))
    }
}

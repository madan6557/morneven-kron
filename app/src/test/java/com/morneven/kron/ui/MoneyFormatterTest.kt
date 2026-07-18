package com.morneven.kron.ui

import com.morneven.kron.ui.components.formatMoneyInput
import com.morneven.kron.ui.components.formatIdr
import com.morneven.kron.ui.components.displayPrimaryHomeMoney
import com.morneven.kron.ui.components.displaySecondaryHomeMoney
import com.morneven.kron.ui.components.wordIdr
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

    @Test
    fun primaryHomeUsesWordsFromOneMillion() {
        assertEquals("Rp 999.999", displayPrimaryHomeMoney(999_999, true))
        assertEquals("Rp 1 juta", displayPrimaryHomeMoney(1_000_000, true))
        assertEquals("Rp 1,25 juta", displayPrimaryHomeMoney(1_250_000, true))
        assertEquals("Rp -1,25 juta", displayPrimaryHomeMoney(-1_250_000, true))
    }

    @Test
    fun secondaryHomeKeepsNineDigitsBeforeUsingWords() {
        assertEquals("Rp 999.999.999", displaySecondaryHomeMoney(999_999_999, true))
        assertEquals("Rp 1 miliar", displaySecondaryHomeMoney(1_000_000_000, true))
        assertEquals("Rp 7,22 triliun", displaySecondaryHomeMoney(7_222_223_225_222, true))
    }

    @Test
    fun wordFormatterSupportsLargeIndonesianUnitsAndRounding() {
        assertEquals("Rp 1 miliar", wordIdr(1_000_000_000))
        assertEquals("Rp 1 triliun", wordIdr(1_000_000_000_000))
        assertEquals("Rp 1 kuadriliun", wordIdr(1_000_000_000_000_000))
        assertEquals("Rp 1 kuintiliun", wordIdr(1_000_000_000_000_000_000))
        assertEquals("Rp 1,01 miliar", wordIdr(1_005_000_000))
    }

    @Test
    fun hiddenHomeMoneyNeverContainsValue() {
        assertEquals("Rp ••••••", displayPrimaryHomeMoney(7_222_223_225_222, false))
        assertEquals("Rp ••••••", displaySecondaryHomeMoney(7_222_223_225_222, false))
    }
}

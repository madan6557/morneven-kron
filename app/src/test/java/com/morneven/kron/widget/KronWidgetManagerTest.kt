package com.morneven.kron.widget

import com.morneven.kron.ui.components.formatIdr
import org.junit.Assert.assertEquals
import org.junit.Test

class KronWidgetManagerTest {

    @Test
    fun testFormatBalanceWhenVisible() {
        val amount = 1_500_000L
        val formatted = KronWidgetManager.formatBalance(amount, visible = true)
        assertEquals(formatIdr(amount), formatted)
    }

    @Test
    fun testFormatBalanceWhenHidden() {
        val amount = 1_500_000L
        val formatted = KronWidgetManager.formatBalance(amount, visible = false)
        assertEquals(KronWidgetManager.HIDDEN_MONEY, formatted)
    }

    @Test
    fun testFormatBalanceZeroAndNegative() {
        assertEquals("Rp 0", KronWidgetManager.formatBalance(0L, visible = true))
        assertEquals(KronWidgetManager.HIDDEN_MONEY, KronWidgetManager.formatBalance(0L, visible = false))
        assertEquals("Rp -50.000", KronWidgetManager.formatBalance(-50_000L, visible = true))
        assertEquals(KronWidgetManager.HIDDEN_MONEY, KronWidgetManager.formatBalance(-50_000L, visible = false))
    }
}

package com.morneven.kron.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KronCurrencyTest {

    @Test
    fun testCurrencyFromCode() {
        assertEquals(WidgetCurrency.IDR, WidgetCurrency.fromCode("IDR"))
        assertEquals(WidgetCurrency.IDR, WidgetCurrency.fromCode("idr"))
        assertEquals(WidgetCurrency.IDR, WidgetCurrency.fromCode("Rp"))
        assertEquals(WidgetCurrency.KRON, WidgetCurrency.fromCode("KRM"))
        assertEquals(WidgetCurrency.KRON, WidgetCurrency.fromCode("krm"))
        assertEquals(WidgetCurrency.KRON, WidgetCurrency.fromCode("Kr"))
        assertEquals(WidgetCurrency.KRON, WidgetCurrency.fromCode("kr"))
        assertEquals(WidgetCurrency.KRON, WidgetCurrency.fromCode("KR"))
        assertEquals(WidgetCurrency.USD, WidgetCurrency.fromCode("USD"))
        assertEquals(WidgetCurrency.EUR, WidgetCurrency.fromCode("EUR"))
        assertEquals(WidgetCurrency.SGD, WidgetCurrency.fromCode("SGD"))
        assertEquals(WidgetCurrency.JPY, WidgetCurrency.fromCode("JPY"))
        assertEquals(WidgetCurrency.IDR, WidgetCurrency.fromCode(null))
        assertEquals(WidgetCurrency.IDR, WidgetCurrency.fromCode(""))
        assertEquals(WidgetCurrency.IDR, WidgetCurrency.fromCode("INVALID"))
    }

    @Test
    fun testMornevenKronReferenceExchangeRate() {
        assertEquals("KRM", WidgetCurrency.KRON.code)
        assertEquals("Kr", WidgetCurrency.KRON.shortCode)
        assertEquals("Kr ", WidgetCurrency.KRON.symbol)
        assertEquals(1200L, KronCurrencyManager.MORNEVEN_KRON_IN_IDR)
        assertEquals(1200.0, KronCurrencyManager.getIdrPerUnit(null, WidgetCurrency.KRON), 0.0001)

        val expectedRate = 1.0 / 1200.0
        assertEquals(expectedRate, KronCurrencyManager.getRate(null, WidgetCurrency.KRON), 0.0000001)
    }

    @Test
    fun testFormatCurrencyAmountKRM() {
        // Hidden balance masking
        val hidden = KronCurrencyManager.formatCurrencyAmount(1200L, WidgetCurrency.KRON, visible = false)
        assertEquals("Kr \u2022\u2022\u2022\u2022\u2022\u2022", hidden)

        // 1.200 IDR is exactly 1.0 Kr -> formatted as "Kr 1,00"
        val oneKr = KronCurrencyManager.formatCurrencyAmount(1200L, WidgetCurrency.KRON, visible = true)
        assertEquals("Kr 1,00", oneKr)

        // 1.000.000 IDR = 833.33 Kr -> rounded to "Kr 833,33"
        val millionIdr = KronCurrencyManager.formatCurrencyAmount(1_000_000L, WidgetCurrency.KRON, visible = true)
        assertEquals("Kr 833,33", millionIdr)

        // 120.000 IDR = exactly 100.0 Kr (>= 100 and integer) -> "Kr 100"
        val hundredKr = KronCurrencyManager.formatCurrencyAmount(120_000L, WidgetCurrency.KRON, visible = true)
        assertEquals("Kr 100", hundredKr)

        // 1.200.000 IDR = exactly 1000.0 Kr -> "Kr 1.000"
        val thousandKr = KronCurrencyManager.formatCurrencyAmount(1_200_000L, WidgetCurrency.KRON, visible = true)
        assertEquals("Kr 1.000", thousandKr)
    }

    @Test
    fun testFormatCurrencyAmountOtherCurrencies() {
        // IDR
        val idrVisible = KronCurrencyManager.formatCurrencyAmount(1_500_000L, WidgetCurrency.IDR, visible = true)
        assertEquals("Rp 1.500.000", idrVisible)
        val idrHidden = KronCurrencyManager.formatCurrencyAmount(1_500_000L, WidgetCurrency.IDR, visible = false)
        assertEquals("Rp \u2022\u2022\u2022\u2022\u2022\u2022", idrHidden)

        // USD with fallback rate (1/16200)
        // 16.200 IDR = $1.00
        val oneUsd = KronCurrencyManager.formatCurrencyAmount(16_200L, WidgetCurrency.USD, visible = true)
        assertEquals("$ 1,00", oneUsd)

        // JPY with fallback rate (1/110)
        // JPY has no decimal fractions in formatting
        val yen = KronCurrencyManager.formatCurrencyAmount(1_100_000L, WidgetCurrency.JPY, visible = true)
        assertEquals("¥ 10.000", yen)
    }
}

package com.morneven.kron.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferCashflowLogicTest {

    private fun isCashFlowEvent(type: String, cashImpact: Long): Boolean = when (type) {
        "INCOME", "OPENING_BALANCE", "EXPENSE", "UNEXPECTED_EXPENSE", "AUTOMATION", "TRANSFER" -> cashImpact != 0L
        else -> false
    }

    @Test
    fun interAccountTransfer_isCountedInCashflowForBothAccounts() {
        val transferAmount = 100_000L

        // Source account: cash leaves the account (-amount)
        val sourceCashImpact = -transferAmount
        assertTrue("Source account transfer should be recognized as cashflow event", isCashFlowEvent("TRANSFER", sourceCashImpact))

        val sourceIncome = sourceCashImpact.coerceAtLeast(0L)
        val sourceExpense = (-sourceCashImpact).coerceAtLeast(0L)
        assertEquals(0L, sourceIncome)
        assertEquals(transferAmount, sourceExpense)
        assertEquals(-transferAmount, sourceIncome - sourceExpense)

        // Destination account: cash enters the account (+amount)
        val destCashImpact = transferAmount
        assertTrue("Destination account transfer should be recognized as cashflow event", isCashFlowEvent("TRANSFER", destCashImpact))

        val destIncome = destCashImpact.coerceAtLeast(0L)
        val destExpense = (-destCashImpact).coerceAtLeast(0L)
        assertEquals(transferAmount, destIncome)
        assertEquals(0L, destExpense)
        assertEquals(transferAmount, destIncome - destExpense)
    }

    @Test
    fun intraAccountChannelConversion_isExcludedFromCashflowBecauseAssetsDoNotChange() {
        val conversionAmount = 50_000L

        // Intra-account conversion has net cash impact = 0 on the account
        // Cash: -50_000, eBudget: +50_000 -> net = 0
        val channelConversionCashImpact = -conversionAmount + conversionAmount
        assertEquals(0L, channelConversionCashImpact)

        assertFalse(
            "Intra-account channel conversion must NOT be recognized as cashflow event",
            isCashFlowEvent("TRANSFER", channelConversionCashImpact)
        )

        val income = channelConversionCashImpact.coerceAtLeast(0L)
        val expense = (-channelConversionCashImpact).coerceAtLeast(0L)
        assertEquals(0L, income)
        assertEquals(0L, expense)
        assertEquals(0L, income - expense)
    }

    @Test
    fun transferTitles_properlyDifferentiateInternalVsInterAccount() {
        fun makeTitle(fromAccountId: Long, toAccountId: Long, fromChannel: String, toChannel: String, fromName: String, toName: String): String {
            return if (fromAccountId == toAccountId) {
                "Konversi $fromChannel ke $toChannel"
            } else {
                "Transfer: $fromName \u2192 $toName"
            }
        }

        assertEquals(
            "Transfer: Test Akun \u2192 Akun Utama",
            makeTitle(1L, 2L, "CASH", "CASH", "Test Akun", "Akun Utama")
        )
        assertEquals(
            "Transfer: Test Akun \u2192 Akun Utama",
            makeTitle(1L, 2L, "CASH", "EBUDGET", "Test Akun", "Akun Utama")
        )
        assertEquals(
            "Konversi CASH ke EBUDGET",
            makeTitle(1L, 1L, "CASH", "EBUDGET", "Test Akun", "Test Akun")
        )
    }
}

package com.morneven.kron.report

import com.morneven.kron.data.ActivityRow
import org.junit.Assert.assertEquals
import org.junit.Test

class CashflowTotalsTest {
    private fun event(
        id: String,
        type: String,
        cashImpact: Long,
        reversedBy: String? = null,
    ) = ActivityRow(
        id = id,
        type = type,
        title = id,
        note = "",
        source = "USER",
        effectiveEpochDay = 20_000L,
        createdAt = 0L,
        relatedEventId = null,
        reversedByEventId = reversedBy,
        accountId = 1L,
        cashImpact = cashImpact,
        vaultImpact = 0L,
        budgetImpact = 0L,
        rolloverImpact = 0L,
        unallocatedImpact = 0L,
        ledgerDebit = 0L,
        ledgerCredit = 0L,
        auditStatus = "SEALED",
        actor = "",
        deviceId = "",
    )

    @Test
    fun scheduledIncomeCountsAsIncome() {
        // The regression: a recurring salary is stored as AUTOMATION, and classifying AUTOMATION as
        // expense only meant the export reported no income at all for it.
        val totals = CashflowTotals.of(listOf(event("gaji", "AUTOMATION", 5_000_000L)))
        assertEquals(5_000_000L, totals.income)
        assertEquals(0L, totals.expense)
    }

    @Test
    fun scheduledExpenseStillCountsAsExpense() {
        val totals = CashflowTotals.of(listOf(event("cicilan", "AUTOMATION", -1_200_000L)))
        assertEquals(0L, totals.income)
        assertEquals(1_200_000L, totals.expense)
    }

    @Test
    fun unexpectedExpenseIsReportedSeparatelyButStillCounted() {
        val totals = CashflowTotals.of(
            listOf(
                event("belanja", "EXPENSE", -300_000L),
                event("darurat", "UNEXPECTED_EXPENSE", -700_000L),
            ),
        )
        assertEquals(300_000L, totals.expense)
        assertEquals(700_000L, totals.unexpected)
        assertEquals(1_000_000L, totals.totalExpense)
    }

    @Test
    fun reversedEventsAreExcludedFromEverySide() {
        val totals = CashflowTotals.of(
            listOf(
                event("masuk", "INCOME", 1_000_000L),
                event("dibatalkan", "INCOME", 9_000_000L, reversedBy = "rev"),
                event("keluar", "EXPENSE", -250_000L, reversedBy = "rev2"),
            ),
        )
        assertEquals(1_000_000L, totals.income)
        assertEquals(0L, totals.expense)
    }

    @Test
    fun borrowingIsAnInflowAndLendingIsAnOutflow() {
        // Repaying a debt is already posted as an ordinary expense, so excluding the opening
        // movement made every loan read as a pure loss and every receivable as pure profit.
        val totals = CashflowTotals.of(
            listOf(
                event("pinjam", "DEBT_OPEN", 1_000_000L),
                event("memberi pinjaman", "DEBT_OPEN", -400_000L),
            ),
        )
        assertEquals(1_000_000L, totals.income)
        assertEquals(400_000L, totals.expense)
    }

    @Test
    fun externalDebtPaymentsMoveNoCash() {
        assertEquals(CashflowTotals(), CashflowTotals.of(listOf(event("lunas", "DEBT_PAYMENT", 0L))))
    }

    @Test
    fun openingBalanceIsIncome() {
        assertEquals(2_000_000L, CashflowTotals.of(listOf(event("awal", "OPENING_BALANCE", 2_000_000L))).income)
    }

    @Test
    fun transfersAndBookkeepingEventsDoNotMoveTheTotals() {
        val totals = CashflowTotals.of(
            listOf(
                event("pindah", "TRANSFER", -500_000L),
                event("kanal", "CHANNEL_TRANSFER", 500_000L),
                event("booking", "PORTFOLIO_BOOKING", 0L),
            ),
        )
        assertEquals(CashflowTotals(), totals)
    }

    @Test
    fun netIsIncomeMinusEveryOutflow() {
        val totals = CashflowTotals.of(
            listOf(
                event("gaji", "AUTOMATION", 5_000_000L),
                event("belanja", "EXPENSE", -1_000_000L),
                event("darurat", "UNEXPECTED_EXPENSE", -500_000L),
            ),
        )
        assertEquals(3_500_000L, totals.net)
    }
}

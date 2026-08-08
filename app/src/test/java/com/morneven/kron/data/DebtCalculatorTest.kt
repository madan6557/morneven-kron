package com.morneven.kron.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtCalculatorTest {
    private fun debt(
        principal: Long = 1_000_000L,
        storedInterest: Long = 0L,
        rateBps: Int = 150,
        intervalMonths: Int = 1,
        intervalUnit: String = InterestInterval.MONTHS,
        anchor: LocalDate = LocalDate.of(2026, 1, 31),
        due: LocalDate? = null,
    ) = DebtEntity(
        accountId = 1,
        role = DebtRole.DEBTOR,
        counterparty = "Pihak",
        title = "Uji",
        principalOriginal = principal,
        principalOutstanding = principal,
        interestOutstanding = storedInterest,
        interestRateBps = rateBps,
        interestIntervalMonths = intervalMonths,
        interestIntervalUnit = intervalUnit,
        interestAnchorEpochDay = anchor.toEpochDay(),
        dueEpochDay = due?.toEpochDay(),
        syncId = "debt-test",
    )

    @Test
    fun interestAccruesOnlyAfterCompleteIntervalsAndKeepsStoredInterest() {
        val value = debt(storedInterest = 500L)
        assertEquals(500L, DebtCalculator.currentInterest(value, LocalDate.of(2026, 2, 27)))
        assertEquals(15_500L, DebtCalculator.currentInterest(value, LocalDate.of(2026, 2, 28)))
        assertEquals(30_500L, DebtCalculator.currentInterest(value, LocalDate.of(2026, 3, 31)))
    }

    @Test
    fun overdueRequiresOpenDebtAndPastDueDate() {
        val value = debt(due = LocalDate.of(2026, 2, 1))
        assertFalse(DebtCalculator.isOverdue(value, LocalDate.of(2026, 2, 1)))
        assertTrue(DebtCalculator.isOverdue(value, LocalDate.of(2026, 2, 2)))
        assertFalse(DebtCalculator.isOverdue(value.copy(status = DebtStatus.SETTLED), LocalDate.of(2026, 2, 2)))
    }

    @Test
    fun interestOverflowFailsClosed() {
        val error = runCatching {
            DebtCalculator.currentInterest(
                debt(principal = Long.MAX_VALUE, rateBps = 100_000),
                LocalDate.of(2026, 2, 28),
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun dailyIntervalAccruesAfterCompleteDays() {
        val value = debt(intervalMonths = 7, intervalUnit = InterestInterval.DAYS, anchor = LocalDate.of(2026, 1, 1))
        assertEquals(0L, DebtCalculator.currentInterest(value, LocalDate.of(2026, 1, 7)))
        assertEquals(15_000L, DebtCalculator.currentInterest(value, LocalDate.of(2026, 1, 8)))
        assertEquals(30_000L, DebtCalculator.currentInterest(value, LocalDate.of(2026, 1, 15)))
    }
}

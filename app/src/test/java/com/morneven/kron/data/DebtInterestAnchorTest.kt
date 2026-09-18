package com.morneven.kron.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Interest is charged at anchor plus whole intervals. These tests pin the two properties that make
 * that schedule trustworthy: elapsed time is never erased by a payment, and the charging day does
 * not slide when a short month is crossed.
 */
class DebtInterestAnchorTest {
    private val start = LocalDate.of(2026, 1, 10)

    private fun debt(
        principal: Long = 1_000_000L,
        rateBps: Int = 1_000, // 10% per interval
        interval: Int = 1,
        unit: String = InterestInterval.MONTHS,
        anchor: LocalDate = start,
        interestOutstanding: Long = 0L,
    ) = DebtEntity(
        id = "debt",
        accountId = 1L,
        role = DebtRole.DEBTOR,
        counterparty = "Budi",
        title = "Pinjaman",
        principalOriginal = principal,
        principalOutstanding = principal,
        interestOutstanding = interestOutstanding,
        interestRateBps = rateBps,
        interestIntervalMonths = interval,
        interestIntervalUnit = unit,
        interestAnchorEpochDay = anchor.toEpochDay(),
    )

    @Test
    fun interestAccruesOncePerCompletedInterval() {
        val debt = debt()
        assertEquals(0L, DebtCalculator.currentInterest(debt, start.plusDays(20)))
        assertEquals(100_000L, DebtCalculator.currentInterest(debt, LocalDate.of(2026, 2, 10)))
        assertEquals(300_000L, DebtCalculator.currentInterest(debt, LocalDate.of(2026, 4, 10)))
    }

    @Test
    fun anchorOnlyAdvancesToABoundaryThatWasActuallyReached() {
        val debt = debt()
        // Paying 25 days in has not completed the first month, so the schedule must not move.
        assertEquals(
            start.toEpochDay(),
            DebtCalculator.settledAnchorEpochDay(debt, start.plusDays(25)),
        )
        // Paying after two full months settles up to the second boundary, not to the payment date.
        assertEquals(
            LocalDate.of(2026, 3, 10).toEpochDay(),
            DebtCalculator.settledAnchorEpochDay(debt, LocalDate.of(2026, 3, 27)),
        )
    }

    @Test
    fun repeatedTokenPaymentsCannotDeferInterestForever() {
        // The regression this guards: an anchor reset to the payment date let a user pay a token
        // amount every few weeks and never complete an interval, so interest never accrued.
        var current = debt()
        var day = start
        repeat(6) {
            day = day.plusDays(25)
            val settled = DebtCalculator.settledAnchorEpochDay(current, day)
            current = current.copy(interestAnchorEpochDay = settled)
        }
        // Six payments 25 days apart span 150 days, which is four completed months.
        assertEquals(LocalDate.of(2026, 5, 10).toEpochDay(), current.interestAnchorEpochDay)
        assertEquals(400_000L, DebtCalculator.currentInterest(debt(), day))
    }

    @Test
    fun settlingAtABoundaryLeavesNoGapBeforeTheNextOne() {
        val debt = debt()
        val paymentDay = LocalDate.of(2026, 2, 20)
        val settled = DebtCalculator.settledAnchorEpochDay(debt, paymentDay)
        // One interval was charged at the payment, so the remainder starts from that boundary and
        // the next charge still lands on 10 March rather than 20 March.
        val afterPayment = debt.copy(interestAnchorEpochDay = settled, interestOutstanding = 0L)
        assertEquals(0L, DebtCalculator.currentInterest(afterPayment, LocalDate.of(2026, 3, 9)))
        assertEquals(100_000L, DebtCalculator.currentInterest(afterPayment, LocalDate.of(2026, 3, 10)))
    }

    @Test
    fun monthEndAnchorKeepsChargingAtMonthEnd() {
        // Chaining plusMonths from each boundary would pin a 31 January debt to the 28th forever.
        val debt = debt(anchor = LocalDate.of(2026, 1, 31))
        assertEquals(100_000L, DebtCalculator.currentInterest(debt, LocalDate.of(2026, 2, 28)))
        assertEquals(100_000L, DebtCalculator.currentInterest(debt, LocalDate.of(2026, 3, 30)))
        assertEquals(200_000L, DebtCalculator.currentInterest(debt, LocalDate.of(2026, 3, 31)))
        assertEquals(
            LocalDate.of(2026, 3, 31).toEpochDay(),
            DebtCalculator.settledAnchorEpochDay(debt, LocalDate.of(2026, 3, 31)),
        )
    }

    @Test
    fun dailyIntervalsAccrueOnWholeDaysOnly() {
        val debt = debt(rateBps = 100, interval = 7, unit = InterestInterval.DAYS)
        assertEquals(0L, DebtCalculator.currentInterest(debt, start.plusDays(6)))
        assertEquals(10_000L, DebtCalculator.currentInterest(debt, start.plusDays(7)))
        assertEquals(20_000L, DebtCalculator.currentInterest(debt, start.plusDays(14)))
        assertEquals(start.plusDays(14).toEpochDay(), DebtCalculator.settledAnchorEpochDay(debt, start.plusDays(20)))
    }

    @Test
    fun unpaidInterestIsCarriedRatherThanRecomputed() {
        val debt = debt(interestOutstanding = 25_000L)
        assertEquals(25_000L, DebtCalculator.currentInterest(debt, start.plusDays(5)))
        assertEquals(125_000L, DebtCalculator.currentInterest(debt, LocalDate.of(2026, 2, 10)))
    }

    @Test
    fun aDebtWithoutInterestNeverMovesItsAnchor() {
        val debt = debt(rateBps = 0)
        assertEquals(0L, DebtCalculator.currentInterest(debt, start.plusYears(3)))
        assertEquals(start.toEpochDay(), DebtCalculator.settledAnchorEpochDay(debt, start.plusYears(3)))
    }

    @Test
    fun settledDebtStopsAccruing() {
        val debt = debt(interestOutstanding = 5_000L).copy(status = DebtStatus.SETTLED)
        assertEquals(5_000L, DebtCalculator.currentInterest(debt, start.plusYears(2)))
    }

    @Test
    fun anchorInTheFutureAccruesNothing() {
        val debt = debt(anchor = start.plusMonths(6))
        assertEquals(0L, DebtCalculator.currentInterest(debt, start))
        assertEquals(start.plusMonths(6).toEpochDay(), DebtCalculator.settledAnchorEpochDay(debt, start))
    }
}

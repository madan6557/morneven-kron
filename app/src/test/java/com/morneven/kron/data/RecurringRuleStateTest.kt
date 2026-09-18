package com.morneven.kron.data

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home attention centre and the settings schedule card both read these, so a rule can never be
 * "tertunda" on one screen and "selesai" on the other.
 */
class RecurringRuleStateTest {
    private val today = LocalDate.of(2026, 6, 15).toEpochDay()

    private fun rule(
        next: LocalDate,
        paused: Boolean = false,
        end: LocalDate? = null,
        remaining: Int? = null,
    ) = RecurringRuleEntity(
        id = "rule",
        title = "Langganan",
        direction = TransactionDirection.EXPENSE,
        amount = 100_000L,
        accountId = 1L,
        fundingChannel = FundingChannel.CASH,
        categoryId = null,
        allocationId = null,
        cadence = "MONTHLY",
        anchorMonth = 1,
        anchorDay = 1,
        startEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
        nextEpochDay = next.toEpochDay(),
        endEpochDay = end?.toEpochDay(),
        remainingOccurrences = remaining,
        isPaused = paused,
    )

    @Test
    fun aScheduleDueInTheFutureIsNotStalled() {
        assertFalse(rule(LocalDate.of(2026, 7, 1)).isStalled(today))
    }

    @Test
    fun aScheduleDueTodayIsNotYetStalled() {
        assertFalse(rule(LocalDate.of(2026, 6, 15)).isStalled(today))
    }

    @Test
    fun anActiveScheduleWithAPastDueDateIsStalled() {
        assertTrue(rule(LocalDate.of(2026, 6, 1)).isStalled(today))
    }

    @Test
    fun aPausedScheduleIsNotStalled() {
        assertFalse(rule(LocalDate.of(2026, 6, 1), paused = true).isStalled(today))
    }

    @Test
    fun aScheduleThatRanOutOfOccurrencesIsFinishedNotStalled() {
        val ended = rule(LocalDate.of(2026, 6, 1), remaining = 0)
        assertTrue(ended.isFinished())
        assertFalse(ended.isStalled(today))
    }

    @Test
    fun aSchedulePastItsEndDateIsFinishedNotStalled() {
        val ended = rule(LocalDate.of(2026, 6, 1), end = LocalDate.of(2026, 5, 1))
        assertTrue(ended.isFinished())
        assertFalse(ended.isStalled(today))
    }

    @Test
    fun aScheduleStillWithinItsEndDateCanStall() {
        val late = rule(LocalDate.of(2026, 6, 1), end = LocalDate.of(2026, 12, 1))
        assertFalse(late.isFinished())
        assertTrue(late.isStalled(today))
    }
}

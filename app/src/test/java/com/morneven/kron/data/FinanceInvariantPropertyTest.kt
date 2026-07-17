package com.morneven.kron.data

import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class FinanceInvariantPropertyTest {
    @Test
    fun randomizedOperations_preserveOverallAndPerChannelInvariant() {
        repeat(40) { seed ->
            val random = Random(seed)
            val cash = longArrayOf(0, 0)
            val vault = longArrayOf(0, 0)
            val booked = longArrayOf(0, 0)
            val unallocated = longArrayOf(0, 0)
            repeat(500) {
                val channel = random.nextInt(2)
                val other = 1 - channel
                val amount = random.nextLong(1, 50_000)
                when (random.nextInt(6)) {
                    0 -> {
                        cash[channel] += amount
                        vault[channel] += amount
                    }
                    1 -> {
                        val value = minOf(amount, vault[channel])
                        vault[channel] -= value
                        booked[channel] += value
                    }
                    2 -> {
                        cash[channel] -= amount
                        if (booked[channel] > 0) booked[channel] -= amount else unallocated[channel] -= amount
                    }
                    3 -> {
                        val value = minOf(amount, booked[channel].coerceAtLeast(0))
                        booked[channel] -= value
                        vault[channel] += value
                    }
                    4 -> {
                        val value = minOf(amount, vault[channel].coerceAtLeast(0))
                        cash[channel] -= value
                        cash[other] += value
                        vault[channel] -= value
                        vault[other] += value
                    }
                    else -> {
                        val value = minOf(amount, booked[channel].coerceAtLeast(0))
                        cash[channel] -= value
                        cash[other] += value
                        booked[channel] -= value
                        booked[other] += value
                    }
                }
                for (index in 0..1) assertEquals(cash[index], vault[index] + booked[index] + unallocated[index])
                assertEquals(cash.sum(), vault.sum() + booked.sum() + unallocated.sum())
            }
        }
    }

    @Test
    fun scheduleKeepsOriginalAnchorAfterShortMonth() {
        assertEquals(LocalDate.of(2027, 2, 28), ScheduleCalculator.next(LocalDate.of(2026, 2, 28), "YEARLY", 2, 29))
        assertEquals(LocalDate.of(2028, 2, 29), ScheduleCalculator.next(LocalDate.of(2027, 2, 28), "YEARLY", 2, 29))
        assertEquals(LocalDate.of(2026, 2, 28), ScheduleCalculator.next(LocalDate.of(2026, 1, 31), "MONTHLY", 1, 31))
        assertEquals(LocalDate.of(2026, 3, 31), ScheduleCalculator.next(LocalDate.of(2026, 2, 28), "MONTHLY", 1, 31))
    }

    @Test
    fun scheduleSupportsThreeMonthAndTwoYearIntervals() {
        assertEquals(LocalDate.of(2026, 4, 15), ScheduleCalculator.next(LocalDate.of(2026, 1, 15), "MONTHLY", 1, 15, 3))
        assertEquals(LocalDate.of(2028, 1, 15), ScheduleCalculator.next(LocalDate.of(2026, 1, 15), "YEARLY", 1, 15, 2))
        assertEquals(LocalDate.of(2026, 10, 15), ScheduleCalculator.firstAfter(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 7, 17), "MONTHLY", 3))
    }

    @Test
    fun endDateIsInclusiveForOccurrence() {
        val end = LocalDate.of(2026, 7, 15)
        assertEquals(end, ScheduleCalculator.firstAfter(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 4, 16), "MONTHLY", 3))
    }
}

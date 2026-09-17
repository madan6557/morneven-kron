package com.morneven.kron.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetSplitCalculationTest {

    private fun calculateSplit(total: Long, cashPct: Int): Pair<Long, Long> {
        val cash = total * cashPct / 100
        val eBudget = total - cash
        return Pair(cash, eBudget)
    }

    @Test
    fun splitCalculation_exactSumGuaranteed() {
        val testTotals = listOf(0L, 1L, 100L, 500_000L, 1_234_567L, 10_000_000L)
        for (total in testTotals) {
            for (pct in 0..100) {
                val (cash, eBudget) = calculateSplit(total, pct)
                assertEquals("Cash + eBudget must equal total for pct=$pct", total, cash + eBudget)
                assertTrue("Cash must be non-negative", cash >= 0L)
                assertTrue("eBudget must be non-negative", eBudget >= 0L)
            }
        }
    }

    @Test
    fun splitCalculation_percentageExtremes() {
        val total = 500_000L

        // 100% Cash
        val (allCash, noEBudget) = calculateSplit(total, 100)
        assertEquals(500_000L, allCash)
        assertEquals(0L, noEBudget)

        // 0% Cash (100% eBudget)
        val (noCash, allEBudget) = calculateSplit(total, 0)
        assertEquals(0L, noCash)
        assertEquals(500_000L, allEBudget)

        // 50% split
        val (halfCash, halfEBudget) = calculateSplit(total, 50)
        assertEquals(250_000L, halfCash)
        assertEquals(250_000L, halfEBudget)
    }

    @Test
    fun splitDeltaAndSpentValidation() {
        val oldCash = 250_000L
        val oldEBudget = 250_000L
        val cashSpent = 100_000L
        val eBudgetSpent = 150_000L

        // Case 1: Rebalance to 70% Cash / 30% eBudget with total 500k
        val (newCash1, newEBudget1) = calculateSplit(500_000L, 70)
        assertEquals(350_000L, newCash1)
        assertEquals(150_000L, newEBudget1)
        assertTrue(newCash1 >= cashSpent)
        assertTrue(newEBudget1 >= eBudgetSpent)

        val deltaCash1 = newCash1 - oldCash
        val deltaEBudget1 = newEBudget1 - oldEBudget
        assertEquals(100_000L, deltaCash1)
        assertEquals(-100_000L, deltaEBudget1)

        // Case 2: Attempting to reduce eBudget below spent
        val (newCash2, newEBudget2) = calculateSplit(500_000L, 80)
        assertEquals(400_000L, newCash2)
        assertEquals(100_000L, newEBudget2)
        assertTrue(newCash2 >= cashSpent)
        assertFalse("Cannot reduce eBudget below already spent amount", newEBudget2 >= eBudgetSpent)
    }

    @Test
    fun vaultBalanceRequirementCheck() {
        val vaultCash = 200_000L
        val vaultEBudget = 50_000L

        val deltaCash = 150_000L
        val deltaEBudget = 80_000L

        val cashVaultOk = deltaCash <= 0 || deltaCash <= vaultCash
        val eBudgetVaultOk = deltaEBudget <= 0 || deltaEBudget <= vaultEBudget

        assertTrue("Cash delta 150k is within vault cash 200k", cashVaultOk)
        assertFalse("eBudget delta 80k exceeds vault eBudget 50k", eBudgetVaultOk)
    }
}

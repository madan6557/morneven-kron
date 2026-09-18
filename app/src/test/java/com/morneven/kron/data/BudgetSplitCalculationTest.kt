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

    @Test
    fun tokenListrik_53kCorrectionFrom5050ToZeroCash() {
        // Initial state: 53k at 50:50
        val total = 53_000L
        val (initialCash, initialEBudget) = calculateSplit(total, 50)
        assertEquals(26_500L, initialCash)
        assertEquals(26_500L, initialEBudget)

        // User corrects to 100% eBudget (0% Cash)
        val (targetCash, targetEBudget) = calculateSplit(total, 0)
        assertEquals(0L, targetCash)
        assertEquals(53_000L, targetEBudget)

        // Delta calculation against current booked amounts
        val currentCashBooked = initialCash
        val currentEBudgetBooked = initialEBudget

        val deltaCash = targetCash - currentCashBooked
        val deltaEBudget = targetEBudget - currentEBudgetBooked

        assertEquals(-26_500L, deltaCash) // Cash returns 26.500 to Vault
        assertEquals(26_500L, deltaEBudget) // eBudget takes 26.500 from Vault
        assertEquals(53_000L, targetCash + targetEBudget) // Total remains exactly 53.000
    }

    @Test
    fun unbookedCashCorrection_avoidsDeficitOrDoubleCounting() {
        // Scenario where Cash was already de-booked (available = 0, spent = 0, planned = 26.500)
        // while eBudget is booked at 53.000
        val targetTotal = 53_000L
        val (targetCash, targetEBudget) = calculateSplit(targetTotal, 0)
        assertEquals(0L, targetCash)
        assertEquals(53_000L, targetEBudget)

        val currentCashAvailable = 0L
        val currentCashSpent = 0L
        val currentCashBooked = currentCashAvailable + currentCashSpent

        val currentEBudgetAvailable = 53_000L
        val currentEBudgetSpent = 0L
        val currentEBudgetBooked = currentEBudgetAvailable + currentEBudgetSpent

        // Delta must be against current booked, NOT against old planned
        val deltaCash = targetCash - currentCashBooked
        val deltaEBudget = targetEBudget - currentEBudgetBooked

        assertEquals(0L, deltaCash) // No funds to return, avoids negative cash deficit
        assertEquals(0L, deltaEBudget) // Already fully funded at 53k, no extra vault needed
    }

    @Test
    fun zeroChannelFilter_hidesEmptyChannelsInActivePeriod() {
        // Active period: channel with 0 booked, 0 available, 0 spent should be hidden
        val emptyActiveRow = AllocationBalanceRow(
            id = 1L,
            periodId = 10L,
            portfolioId = 100L,
            portfolioName = "Pribadi",
            portfolioArchived = false,
            categoryId = 200L,
            categoryName = "Token Listrik",
            color = 0xFF0000L,
            categoryArchived = false,
            fundingChannel = "CASH",
            plannedAmount = 26_500L,
            bookedAmount = 0L,
            availableAmount = 0L,
            spentAmount = 0L,
            periodStatus = "ACTIVE",
            startEpochDay = 20000L,
            endEpochDay = 20030L,
        )

        val isVisibleInActivePeriod = emptyActiveRow.isActive && !(
            emptyActiveRow.periodStatus != "DRAFT" &&
                emptyActiveRow.bookedAmount == 0L &&
                emptyActiveRow.availableAmount == 0L &&
                emptyActiveRow.spentAmount == 0L
        )
        assertFalse("0-fund channel in ACTIVE period must be hidden", isVisibleInActivePeriod)

        // DRAFT period: planned channels should remain visible so user sees what is planned
        val draftRow = emptyActiveRow.copy(periodStatus = "DRAFT")
        val isVisibleInDraft = draftRow.isActive && !(
            draftRow.periodStatus != "DRAFT" &&
                draftRow.bookedAmount == 0L &&
                draftRow.availableAmount == 0L &&
                draftRow.spentAmount == 0L
        )
        assertTrue("Planned channel in DRAFT period must stay visible", isVisibleInDraft)
    }

    @Test
    fun effectivePlannedCalculation_preventsInflatedCategoryTotal() {
        // Cash has planned 26.500 from old bug, but 0 booked/available/spent
        val cashRow = AllocationBalanceRow(
            id = 1L,
            periodId = 10L,
            portfolioId = 100L,
            portfolioName = "Pribadi",
            portfolioArchived = false,
            categoryId = 200L,
            categoryName = "Token Listrik",
            color = 0xFF0000L,
            categoryArchived = false,
            fundingChannel = "CASH",
            plannedAmount = 26_500L,
            bookedAmount = 0L,
            availableAmount = 0L,
            spentAmount = 0L,
            periodStatus = "ACTIVE",
            startEpochDay = 20000L,
            endEpochDay = 20030L,
        )
        // eBudget has planned 79.500, booked 53.000, available 53.000
        val ebRow = AllocationBalanceRow(
            id = 2L,
            periodId = 10L,
            portfolioId = 100L,
            portfolioName = "Pribadi",
            portfolioArchived = false,
            categoryId = 200L,
            categoryName = "Token Listrik",
            color = 0xFF0000L,
            categoryArchived = false,
            fundingChannel = "EBUDGET",
            plannedAmount = 53_000L,
            bookedAmount = 53_000L,
            availableAmount = 53_000L,
            spentAmount = 0L,
            periodStatus = "ACTIVE",
            startEpochDay = 20000L,
            endEpochDay = 20030L,
        )

        fun allocEffectivePlanned(row: AllocationBalanceRow): Long {
            return if (row.periodStatus != "DRAFT" && row.bookedAmount == 0L && row.availableAmount == 0L && row.spentAmount == 0L) 0L
            else row.plannedAmount
        }

        val effCash = allocEffectivePlanned(cashRow)
        val effEb = allocEffectivePlanned(ebRow)
        val totalEffective = effCash + effEb

        assertEquals("Cash with 0 funds should have 0 effective planned", 0L, effCash)
        assertEquals("eBudget should have 53.000 effective planned", 53_000L, effEb)
        assertEquals("Total category planned should be 53.000, not 79.500 or 106.000", 53_000L, totalEffective)
    }
}

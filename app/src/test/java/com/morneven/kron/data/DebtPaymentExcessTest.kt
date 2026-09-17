package com.morneven.kron.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class DebtPaymentExcessTest {

    private data class PaymentSplitResult(
        val debtPaymentAmount: Long,
        val interestPaid: Long,
        val principalPaid: Long,
        val excessAmount: Long,
        val nextPrincipal: Long,
        val nextInterest: Long,
        val isSettled: Boolean,
    )

    private fun calculatePaymentSplit(
        principalOutstanding: Long,
        currentInterest: Long,
        amount: Long,
    ): PaymentSplitResult {
        require(amount > 0)
        val due = Math.addExact(principalOutstanding, currentInterest)
        val excessAmount = if (amount > due) amount - due else 0L
        val debtPaymentAmount = if (amount > due) due else amount
        val interestPaid = min(debtPaymentAmount, currentInterest)
        val principalPaid = debtPaymentAmount - interestPaid
        val nextPrincipal = principalOutstanding - principalPaid
        val nextInterest = currentInterest - interestPaid
        val isSettled = nextPrincipal == 0L && nextInterest == 0L
        return PaymentSplitResult(
            debtPaymentAmount = debtPaymentAmount,
            interestPaid = interestPaid,
            principalPaid = principalPaid,
            excessAmount = excessAmount,
            nextPrincipal = nextPrincipal,
            nextInterest = nextInterest,
            isSettled = isSettled,
        )
    }

    @Test
    fun exactSettlement_clearsDebtWithZeroExcess() {
        val result = calculatePaymentSplit(
            principalOutstanding = 500_000L,
            currentInterest = 50_000L,
            amount = 550_000L,
        )
        assertEquals(550_000L, result.debtPaymentAmount)
        assertEquals(50_000L, result.interestPaid)
        assertEquals(500_000L, result.principalPaid)
        assertEquals(0L, result.excessAmount)
        assertEquals(0L, result.nextPrincipal)
        assertEquals(0L, result.nextInterest)
        assertTrue(result.isSettled)
    }

    @Test
    fun excessPayment_clearsDebtAndSeparatesBonus() {
        // Debt of 100.000 + 20.000 interest, paid 150.000 (bonus 30.000)
        val result = calculatePaymentSplit(
            principalOutstanding = 100_000L,
            currentInterest = 20_000L,
            amount = 150_000L,
        )
        assertEquals(120_000L, result.debtPaymentAmount)
        assertEquals(20_000L, result.interestPaid)
        assertEquals(100_000L, result.principalPaid)
        assertEquals(30_000L, result.excessAmount)
        assertEquals(0L, result.nextPrincipal)
        assertEquals(0L, result.nextInterest)
        assertTrue(result.isSettled)

        // Mathematical invariant: principal + interest + excess == total amount
        assertEquals(150_000L, result.principalPaid + result.interestPaid + result.excessAmount)
    }

    @Test
    fun partialPayment_reducesInterestThenPrincipalWithoutSettlement() {
        val result = calculatePaymentSplit(
            principalOutstanding = 200_000L,
            currentInterest = 30_000L,
            amount = 80_000L,
        )
        assertEquals(80_000L, result.debtPaymentAmount)
        assertEquals(30_000L, result.interestPaid)
        assertEquals(50_000L, result.principalPaid)
        assertEquals(0L, result.excessAmount)
        assertEquals(150_000L, result.nextPrincipal)
        assertEquals(0L, result.nextInterest)
        assertFalse(result.isSettled)
    }

    @Test
    fun multiValueFuzzInvariants() {
        val testPrincipals = listOf(1L, 1000L, 100_000L, 5_000_000L)
        val testInterests = listOf(0L, 500L, 25_000L, 1_000_000L)
        val testExcesses = listOf(0L, 1L, 10_000L, 250_000L)

        for (p in testPrincipals) {
            for (i in testInterests) {
                val due = p + i
                for (excess in testExcesses) {
                    val amount = due + excess
                    val res = calculatePaymentSplit(p, i, amount)
                    assertEquals(due, res.debtPaymentAmount)
                    assertEquals(i, res.interestPaid)
                    assertEquals(p, res.principalPaid)
                    assertEquals(excess, res.excessAmount)
                    assertEquals(0L, res.nextPrincipal)
                    assertEquals(0L, res.nextInterest)
                    assertTrue(res.isSettled)
                    assertEquals(amount, res.principalPaid + res.interestPaid + res.excessAmount)
                }
            }
        }
    }
}

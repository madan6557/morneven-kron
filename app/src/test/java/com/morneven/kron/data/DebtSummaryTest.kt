package com.morneven.kron.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtSummaryTest {
    private val today = LocalDate.of(2026, 4, 10)

    private fun debt(
        id: String,
        role: String,
        principal: Long,
        rateBps: Int = 0,
        anchor: LocalDate = LocalDate.of(2026, 1, 10),
        due: LocalDate? = null,
        status: String = DebtStatus.OPEN,
    ) = DebtEntity(
        id = id,
        accountId = 1L,
        role = role,
        counterparty = "Pihak $id",
        title = "Hutang $id",
        principalOriginal = principal,
        principalOutstanding = principal,
        interestRateBps = rateBps,
        interestAnchorEpochDay = anchor.toEpochDay(),
        dueEpochDay = due?.toEpochDay(),
        status = status,
    )

    @Test
    fun emptyWhenNothingIsOpen() {
        val summary = DebtSummary.of(
            listOf(debt("a", DebtRole.DEBTOR, 500_000L, status = DebtStatus.SETTLED)),
            today,
        )
        assertTrue(summary.active.isEmpty())
        assertEquals(0L, summary.payable)
        assertEquals(0L, summary.receivable)
        assertNull(summary.nearest)
    }

    @Test
    fun separatesWhatIsOwedFromWhatIsDueBack() {
        val summary = DebtSummary.of(
            listOf(
                debt("a", DebtRole.DEBTOR, 500_000L),
                debt("b", DebtRole.CREDITOR, 200_000L),
                debt("c", DebtRole.DEBTOR, 100_000L),
            ),
            today,
        )
        assertEquals(600_000L, summary.payable)
        assertEquals(200_000L, summary.receivable)
        assertEquals(0L, summary.interest)
    }

    @Test
    fun totalsIncludeAccruedInterest() {
        // 10% per month, anchored three months back.
        val summary = DebtSummary.of(listOf(debt("a", DebtRole.DEBTOR, 1_000_000L, rateBps = 1_000)), today)
        assertEquals(300_000L, summary.interest)
        assertEquals(1_300_000L, summary.payable)
    }

    @Test
    fun nearestDueIgnoresDebtsWithoutADeadline() {
        val summary = DebtSummary.of(
            listOf(
                debt("a", DebtRole.DEBTOR, 1L),
                debt("b", DebtRole.DEBTOR, 1L, due = LocalDate.of(2026, 6, 1)),
                debt("c", DebtRole.DEBTOR, 1L, due = LocalDate.of(2026, 5, 1)),
            ),
            today,
        )
        assertEquals("c", summary.nearest?.id)
    }

    @Test
    fun countsDebtsPastTheirDeadline() {
        val summary = DebtSummary.of(
            listOf(
                debt("a", DebtRole.DEBTOR, 1L, due = LocalDate.of(2026, 4, 9)),
                debt("b", DebtRole.CREDITOR, 1L, due = LocalDate.of(2026, 4, 10)),
                debt("c", DebtRole.DEBTOR, 1L, due = LocalDate.of(2026, 4, 11)),
            ),
            today,
        )
        assertEquals(1, summary.overdue)
    }
}

package com.morneven.kron.data

import java.time.LocalDate

/**
 * Aggregate view of the open debts of an account on a given day.
 *
 * Every figure here walks the interest schedule, so it is built once per debt list rather than per
 * read: the home screen shows four of these values side by side and would otherwise recompute the
 * accrual of every debt on each recomposition.
 */
data class DebtSummary(
    val active: List<DebtEntity> = emptyList(),
    /** Principal plus accrued interest still owed to other people. */
    val payable: Long = 0L,
    /** Principal plus accrued interest still owed by other people. */
    val receivable: Long = 0L,
    /** Accrued but unpaid interest across every open debt, in both directions. */
    val interest: Long = 0L,
    val nearest: DebtEntity? = null,
    val overdue: Int = 0,
) {
    companion object {
        fun of(debts: List<DebtEntity>, today: LocalDate): DebtSummary {
            val active = debts.filter { it.status == DebtStatus.OPEN }
            if (active.isEmpty()) return DebtSummary()
            var payable = 0L
            var receivable = 0L
            var interest = 0L
            var overdue = 0
            active.forEach { debt ->
                val accrued = DebtCalculator.currentInterest(debt, today)
                val total = debt.principalOutstanding + accrued
                interest += accrued
                if (debt.role == DebtRole.DEBTOR) payable += total else receivable += total
                if (DebtCalculator.isOverdue(debt, today)) overdue++
            }
            return DebtSummary(
                active = active,
                payable = payable,
                receivable = receivable,
                interest = interest,
                nearest = active.filter { it.dueEpochDay != null }.minByOrNull { it.dueEpochDay!! },
                overdue = overdue,
            )
        }
    }
}

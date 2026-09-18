package com.morneven.kron.report

import com.morneven.kron.data.ActivityRow

/**
 * Cash flow totals for an exported set of events.
 *
 * The classification deliberately mirrors the `observeCashflow` query in KronDao: a scheduled
 * transaction is stored with type `AUTOMATION` in both directions, so it has to be split by the
 * sign of its cash impact rather than by its type. Treating `AUTOMATION` as expense only, which is
 * what the export used to do, dropped every automated salary or allowance from the reported income
 * while the same figure showed up correctly on the home screen.
 */
data class CashflowTotals(
    val income: Long = 0L,
    val expense: Long = 0L,
    val unexpected: Long = 0L,
) {
    val totalExpense: Long get() = expense + unexpected
    val net: Long get() = income - totalExpense

    companion object {
        // `DEBT_OPEN` moves real cash: borrowing is an inflow, lending is an outflow. Both sides
        // are keyed off the sign of the impact, exactly as the KronDao query does.
        private val INCOME_TYPES = setOf("INCOME", "OPENING_BALANCE", "AUTOMATION", "DEBT_OPEN")
        private val EXPENSE_TYPES = setOf("EXPENSE", "AUTOMATION", "DEBT_OPEN")
        private const val UNEXPECTED_TYPE = "UNEXPECTED_EXPENSE"

        fun of(activities: List<ActivityRow>): CashflowTotals {
            var income = 0L
            var expense = 0L
            var unexpected = 0L
            activities.forEach { event ->
                if (event.reversedByEventId != null) return@forEach
                val impact = event.cashImpact
                when {
                    impact > 0L && event.type in INCOME_TYPES -> income += impact
                    impact < 0L && event.type == UNEXPECTED_TYPE -> unexpected += -impact
                    impact < 0L && event.type in EXPENSE_TYPES -> expense += -impact
                }
            }
            return CashflowTotals(income = income, expense = expense, unexpected = unexpected)
        }
    }
}

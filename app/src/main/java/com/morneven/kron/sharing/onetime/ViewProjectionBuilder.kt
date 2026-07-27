package com.morneven.kron.sharing.onetime

import com.morneven.kron.data.ActivityRow
import com.morneven.kron.ui.KronUiState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

object ViewProjectionBuilder {

    fun buildProjection(state: KronUiState, scope: String): ViewProjection {
        val now = Instant.now()
        val (periodStart, periodEnd) = periodBounds(scope, now, state)

        val filteredActivities = state.activities.filter { a ->
            val dayMs = a.effectiveEpochDay * 24L * 60 * 60 * 1000
            dayMs in periodStart..periodEnd
        }

        val income = filteredActivities.filter { it.ledgerCredit > 0 }.sumOf { it.ledgerCredit }
        val expense = filteredActivities.filter { it.ledgerDebit > 0 }.sumOf { it.ledgerDebit }

        val summaryJson = JSONObject().apply {
            put("totalIncome", income)
            put("totalExpense", expense)
            put("balance", income - expense)
            put("currency", "IDR")
        }.toString()

        val txArray = JSONArray()
        for (tx in filteredActivities) {
            val date = LocalDate.ofEpochDay(tx.effectiveEpochDay).toString()
            txArray.put(JSONObject().apply {
                put("date", date)
                put("description", tx.title)
                put("amount", if (tx.ledgerCredit > 0) tx.ledgerCredit else tx.ledgerDebit)
                put("type", if (tx.ledgerCredit > 0) "INCOME" else "EXPENSE")
            })
        }
        val transactionsJson = txArray.toString()

        val budgetArray = JSONArray()
        for (a in state.allocations) {
            if (a.periodStatus == "CLOSED") continue
            budgetArray.put(JSONObject().apply {
                put("category", a.categoryName)
                put("budget", a.plannedAmount)
                put("spent", a.spentAmount)
                put("available", a.availableAmount)
            })
        }
        val budgetsJson = budgetArray.toString()

        return ViewProjection(
            generatedAt = now,
            periodStart = periodStart,
            periodEnd = periodEnd,
            summaryJson = summaryJson,
            transactionsJson = transactionsJson,
            budgetsJson = budgetsJson,
        )
    }

    private fun periodBounds(scope: String, now: Instant, state: KronUiState): Pair<Long, Long> {
        val end = now.toEpochMilli()
        val today = LocalDate.now(ZoneId.systemDefault())
        return when (scope) {
            "CurrentSummary" -> {
                val ms = state.activities.minOfOrNull { it.effectiveEpochDay }?.let {
                    it * 24L * 60 * 60 * 1000
                } ?: end
                ms to end
            }
            "CurrentMonthReport" -> {
                val ms = today.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                ms to end
            }
            "CurrentFullSnapshot" -> 0L to end
            else -> (end - 30L * 24 * 60 * 60 * 1000) to end // Last30Days default
        }
    }
}

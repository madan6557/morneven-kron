package com.morneven.kron.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationReportTest {
    private fun skipped(id: String, title: String) =
        SkippedAutomation(ruleId = id, title = title, dueEpochDay = 20_000L, reason = "Saldo Cash tidak mencukupi.")

    @Test
    fun cleanPassHasNothingToReport() {
        assertNull(AutomationReport(posted = 4).userMessage())
    }

    @Test
    fun singleSkippedRuleNamesTheSchedule() {
        val message = AutomationReport(posted = 1, skipped = listOf(skipped("a", "Cicilan motor"))).userMessage()
        assertEquals(
            "Jadwal \"Cicilan motor\" belum dapat dijalankan: Saldo Cash tidak mencukupi. KRON akan mencoba lagi nanti.",
            message,
        )
    }

    @Test
    fun severalSkippedRulesReportTheCount() {
        val report = AutomationReport(skipped = listOf(skipped("a", "Cicilan motor"), skipped("b", "Langganan")))
        assertTrue(report.userMessage()!!.contains("melewati 2 jadwal"))
    }

    @Test
    fun reportsCombineAcrossPasses() {
        val income = AutomationReport(posted = 2)
        val expense = AutomationReport(posted = 1, skipped = listOf(skipped("a", "Cicilan motor")))
        val combined = income + expense
        assertEquals(3, combined.posted)
        assertEquals(1, combined.skipped.size)
    }

    @Test
    fun oneUnrunnableRuleDoesNotStopThePass() {
        // The whole point of the fix: a business rule failure parks one schedule, it does not abort
        // the remaining schedules behind it.
        assertFalse(AutomationFailurePolicy.isFatal(IllegalArgumentException("Saldo Cash tidak mencukupi")))
        assertFalse(AutomationFailurePolicy.isFatal(IllegalStateException("Tidak ada akun aktif")))
        assertFalse(AutomationFailurePolicy.isFatal(TeamAccessDeniedException("Viewer tidak dapat menulis")))
    }

    @Test
    fun brokenLedgerInvariantStopsEverything() {
        // A ledger that no longer balances must fail closed instead of posting more rows onto it.
        assertTrue(AutomationFailurePolicy.isFatal(LedgerInvariantException("Jurnal tidak seimbang")))
    }

    @Test
    fun cancellationIsNeverSwallowed() {
        assertTrue(AutomationFailurePolicy.isFatal(CancellationException("cancelled")))
    }
}

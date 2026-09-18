package com.morneven.kron.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These pin the rules the self check applies. They deliberately mirror the conditions
 * `assertInvariant` enforces on every write, so a change to one without the other shows up here.
 */
class LedgerIntegrityTest {
    private fun healthyInput() = LedgerIntegrityInput(
        unbalancedLedgerEventCount = 0,
        unbalancedBudgetEventId = null,
        accountCount = 1,
        activeAccountCount = 1,
        totalCash = 1_000_000L,
        totalAvailable = 1_000_000L,
        channels = listOf(
            ChannelIntegrityRow(FundingChannel.CASH, 600_000L, 600_000L),
            ChannelIntegrityRow(FundingChannel.EBUDGET, 400_000L, 400_000L),
        ),
        accounts = listOf(
            AccountIntegrityRow(
                name = "Akun Utama",
                cash = 1_000_000L,
                available = 1_000_000L,
                channels = listOf(
                    ChannelIntegrityRow(FundingChannel.CASH, 600_000L, 600_000L),
                    ChannelIntegrityRow(FundingChannel.EBUDGET, 400_000L, 400_000L),
                ),
            ),
        ),
        eventCount = 42L,
        unsealedEventCount = 0L,
    )

    @Test
    fun aBalancedLedgerPassesEveryCheck() {
        val report = healthyInput().toReport(checkedAtEpochMillis = 1_000L)
        assertTrue(report.healthy)
        assertTrue(report.failed.isEmpty())
        assertEquals(1_000L, report.checkedAtEpochMillis)
        assertEquals(42L, report.eventCount)
    }

    @Test
    fun everyScopeIsChecked() {
        val report = healthyInput().toReport(0L)
        // ledger, single active account, grand total, 2 channels, 1 account, its 2 channels,
        // budget events, seals.
        assertEquals(1 + 1 + 1 + 2 + 1 + 2 + 1 + 1, report.checks.size)
    }

    @Test
    fun aChannelMismatchIsReportedWithBothFigures() {
        val input = healthyInput().copy(
            channels = listOf(
                ChannelIntegrityRow(FundingChannel.CASH, 600_000L, 550_000L),
                ChannelIntegrityRow(FundingChannel.EBUDGET, 400_000L, 400_000L),
            ),
        )
        val report = input.toReport(0L)
        assertFalse(report.healthy)
        val failure = report.failed.single()
        assertEquals("Kanal Cash seimbang", failure.label)
        assertEquals(600_000L, failure.left)
        assertEquals(550_000L, failure.right)
    }

    @Test
    fun aPerAccountMismatchIsReportedSeparatelyFromTheGrandTotal() {
        // Two accounts can cancel out, so the grand total passing does not mean each account does.
        val input = healthyInput().copy(
            accounts = listOf(
                AccountIntegrityRow("Utama", 700_000L, 600_000L, emptyList()),
                AccountIntegrityRow("Kedua", 300_000L, 400_000L, emptyList()),
            ),
        )
        val report = input.toReport(0L)
        assertEquals(2, report.failed.size)
        assertTrue(report.checks.first { it.label == "Total kas sama dengan total dana" }.passed)
    }

    @Test
    fun anUnbalancedBudgetEventIsNamed() {
        val report = healthyInput().copy(unbalancedBudgetEventId = "abc-123").toReport(0L)
        val failure = report.failed.single()
        assertEquals("Setiap event budget seimbang", failure.label)
        assertTrue(failure.note!!.contains("abc-123"))
    }

    @Test
    fun anUnbalancedGeneralLedgerFails() {
        val report = healthyInput().copy(unbalancedLedgerEventCount = 3).toReport(0L)
        assertEquals("General ledger seimbang", report.failed.single().label)
    }

    @Test
    fun exactlyOneAccountMustBeActive() {
        assertFalse(healthyInput().copy(activeAccountCount = 0).toReport(0L).healthy)
        assertFalse(healthyInput().copy(activeAccountCount = 2).toReport(0L).healthy)
    }

    @Test
    fun aFreshInstallWithNoAccountsIsStillHealthy() {
        val report = LedgerIntegrityInput(accountCount = 0, activeAccountCount = 0).toReport(0L)
        assertTrue(report.healthy)
    }

    @Test
    fun unsealedEventsAreReported() {
        val report = healthyInput().copy(unsealedEventCount = 2L).toReport(0L)
        val failure = report.failed.single()
        assertEquals("Seluruh event tersegel", failure.label)
        assertNotNull(failure.note)
    }

    @Test
    fun anEmptyReportIsNotConsideredHealthy() {
        assertFalse(LedgerIntegrityReport().healthy)
    }
}

package com.morneven.kron.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolutionAuditTest {
    @Test
    fun capturesSourceAndTargetBalancesForAuditDetail() {
        val snapshots = ResolutionAudit.snapshots(
            account = "Akun Utama",
            channel = FundingChannel.CASH,
            amount = 25_000L,
            sourceLabel = "Rollover",
            sourceDetail = "Portfolio Bulanan",
            sourceBefore = 100_000L,
            sourceAfter = 75_000L,
            targetLabel = "Kategori Makan",
            targetDetail = "Budget Bulanan",
            targetBefore = 50_000L,
            targetAfter = 75_000L,
        )
        val detail = requireNotNull(ResolutionAudit.parse(snapshots.first, snapshots.second))
        assertEquals("Akun Utama", detail.account)
        assertEquals(25_000L, detail.amount)
        assertEquals(100_000L, detail.source.before)
        assertEquals(75_000L, detail.source.after)
        assertEquals(50_000L, detail.target.before)
        assertEquals(75_000L, detail.target.after)
    }

    @Test
    fun rejectsNonVersionedAuditPayload() {
        assertNull(ResolutionAudit.parse("{}", "{}"))
    }
}

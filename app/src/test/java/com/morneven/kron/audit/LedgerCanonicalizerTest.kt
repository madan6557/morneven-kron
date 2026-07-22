package com.morneven.kron.audit

import com.morneven.kron.data.ActivityEventEntity
import com.morneven.kron.data.LedgerLineEntity
import com.morneven.kron.data.LedgerSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerCanonicalizerTest {
    @Test
    fun canonicalPayloadIsStableAndKeepsLongAsString() {
        val amount = Long.MAX_VALUE
        val event = ActivityEventEntity(
            id = "event-1",
            type = "INCOME",
            title = "Pemasukan",
            note = "Catatan",
            source = "USER",
            effectiveEpochDay = 20,
            createdAt = 30,
            accountId = 1,
        )
        val lines = listOf(
            LedgerLineEntity(id = 2, eventId = event.id, ledgerAccountId = "income:general", side = LedgerSide.CREDIT, amount = amount),
            LedgerLineEntity(id = 1, eventId = event.id, ledgerAccountId = "asset:1:CASH", side = LedgerSide.DEBIT, amount = amount),
        )
        val first = LedgerCanonicalizer.eventPayload(event, emptyList(), emptyList(), emptyList(), lines, emptyList(), emptyList())
        val second = LedgerCanonicalizer.eventPayload(event, emptyList(), emptyList(), emptyList(), lines.reversed(), emptyList(), emptyList())
        assertEquals(first, second)
        assertTrue(first.contains("\"amount\":\"$amount\""))
    }

    @Test
    fun oneByteEquivalentChangeChangesCanonicalPayload() {
        val original = ActivityEventEntity("event-1", "INCOME", "A", "", "USER", 1, createdAt = 2, accountId = 1)
        val changed = original.copy(title = "B")
        val first = LedgerCanonicalizer.eventPayload(original, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val second = LedgerCanonicalizer.eventPayload(changed, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertNotEquals(first, second)
    }
}

package com.morneven.kron.audit

import com.morneven.kron.data.ActivityEventEntity
import com.morneven.kron.data.AuditSnapshotEntity
import com.morneven.kron.data.BudgetJournalLineEntity
import com.morneven.kron.data.CashJournalLineEntity
import com.morneven.kron.data.LedgerLineEntity
import com.morneven.kron.data.ReceiptEntity
import com.morneven.kron.data.TransactionSplitEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamLedgerCanonicalizerTest {
    @Test
    fun hashUsesStableReferencesAndRejectsAnotherAccount() {
        val first = graph(accountId = 1, allocationId = 10, categoryId = 20, rowId = 30)
        val second = graph(accountId = 9, allocationId = 80, categoryId = 70, rowId = 60)

        assertEquals(hash(first), hash(second))
        assertTrue(
            runCatching {
                hash(first.copy(cash = first.cash.map { it.copy(accountId = 2) }))
            }.isFailure,
        )
    }

    private fun hash(graph: Graph): String = TeamLedgerCanonicalizer.payloadHash(
        event = graph.event,
        teamId = "team-1",
        cash = graph.cash,
        budget = graph.budget,
        splits = graph.splits,
        ledger = graph.ledger,
        audits = graph.audits,
        receipts = graph.receipts,
        allocationSyncIds = mapOf(graph.allocationId to "allocation-stable"),
        categorySyncIds = mapOf(graph.categoryId to "category-stable"),
    )

    private fun graph(accountId: Long, allocationId: Long, categoryId: Long, rowId: Long): Graph {
        val event = ActivityEventEntity(
            id = "event-1",
            type = "EXPENSE",
            title = "Belanja",
            note = "Catatan",
            source = "USER",
            effectiveEpochDay = 2,
            createdAt = 3,
            targetAllocationId = allocationId,
            accountId = accountId,
        )
        return Graph(
            event = event,
            allocationId = allocationId,
            categoryId = categoryId,
            cash = listOf(CashJournalLineEntity(rowId, event.id, accountId, "CASH", -100)),
            budget = listOf(BudgetJournalLineEntity(rowId, event.id, allocationId, null, "CASH", -100, accountId)),
            splits = listOf(TransactionSplitEntity(rowId, event.id, categoryId, allocationId, 100)),
            ledger = listOf(
                LedgerLineEntity(
                    id = rowId,
                    eventId = event.id,
                    ledgerAccountId = "expense:category:$categoryId",
                    side = "DEBIT",
                    amount = 100,
                    accountId = accountId,
                    fundingChannel = "CASH",
                    categoryId = categoryId,
                ),
            ),
            audits = listOf(AuditSnapshotEntity(rowId, event.id, "Uji", "{}", "{}")),
            receipts = listOf(
                ReceiptEntity(
                    id = rowId,
                    eventId = event.id,
                    localPath = null,
                    storageId = "receipt-1",
                    displayName = "Nota",
                    mimeType = "image/jpeg",
                    byteSize = 4,
                    sha256 = "a".repeat(64),
                    createdAt = 5,
                ),
            ),
        )
    }

    private data class Graph(
        val event: ActivityEventEntity,
        val allocationId: Long,
        val categoryId: Long,
        val cash: List<CashJournalLineEntity>,
        val budget: List<BudgetJournalLineEntity>,
        val splits: List<TransactionSplitEntity>,
        val ledger: List<LedgerLineEntity>,
        val audits: List<AuditSnapshotEntity>,
        val receipts: List<ReceiptEntity>,
    )
}

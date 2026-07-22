package com.morneven.kron.audit

import com.morneven.kron.data.ActivityEventEntity
import com.morneven.kron.data.AuditSnapshotEntity
import com.morneven.kron.data.BudgetJournalLineEntity
import com.morneven.kron.data.CashJournalLineEntity
import com.morneven.kron.data.LedgerLineEntity
import com.morneven.kron.data.ReceiptEntity
import com.morneven.kron.data.TransactionSplitEntity

object LedgerCanonicalizer {
    fun eventPayload(
        event: ActivityEventEntity,
        cash: List<CashJournalLineEntity>,
        budget: List<BudgetJournalLineEntity>,
        splits: List<TransactionSplitEntity>,
        ledger: List<LedgerLineEntity>,
        audits: List<AuditSnapshotEntity>,
        receipts: List<ReceiptEntity>,
    ): String = obj(
        "accountId" to quoted(event.accountId.toString()),
        "audits" to array(audits.sortedBy { it.id }.map(::auditJson)),
        "budget" to array(budget.sortedBy { it.id }.map(::budgetJson)),
        "cash" to array(cash.sortedBy { it.id }.map(::cashJson)),
        "createdAt" to quoted(event.createdAt.toString()),
        "effectiveEpochDay" to quoted(event.effectiveEpochDay.toString()),
        "eventId" to quoted(event.id),
        "ledger" to array(ledger.sortedBy { it.id }.map(::ledgerJson)),
        "note" to quoted(event.note),
        "receipts" to array(receipts.sortedBy { it.id }.map(::receiptJson)),
        "relatedEventId" to nullableQuoted(event.relatedEventId),
        "source" to quoted(event.source),
        "splits" to array(splits.sortedBy { it.id }.map(::splitJson)),
        "targetAllocationId" to nullableQuoted(event.targetAllocationId?.toString()),
        "title" to quoted(event.title),
        "type" to quoted(event.type),
    )

    private fun cashJson(line: CashJournalLineEntity) = obj(
        "accountId" to quoted(line.accountId.toString()),
        "amount" to quoted(line.amount.toString()),
        "channel" to quoted(line.fundingChannel),
        "id" to quoted(line.id.toString()),
    )

    private fun budgetJson(line: BudgetJournalLineEntity) = obj(
        "accountId" to quoted(line.accountId.toString()),
        "allocationId" to nullableQuoted(line.allocationId?.toString()),
        "amount" to quoted(line.amount.toString()),
        "bucket" to nullableQuoted(line.bucket),
        "channel" to quoted(line.fundingChannel),
        "id" to quoted(line.id.toString()),
    )

    private fun splitJson(line: TransactionSplitEntity) = obj(
        "allocationId" to nullableQuoted(line.allocationId?.toString()),
        "amount" to quoted(line.amount.toString()),
        "categoryId" to nullableQuoted(line.categoryId?.toString()),
        "id" to quoted(line.id.toString()),
    )

    private fun ledgerJson(line: LedgerLineEntity) = obj(
        "accountId" to nullableQuoted(line.accountId?.toString()),
        "amount" to quoted(line.amount.toString()),
        "categoryId" to nullableQuoted(line.categoryId?.toString()),
        "channel" to nullableQuoted(line.fundingChannel),
        "correlationId" to nullableQuoted(line.correlationId),
        "id" to quoted(line.id.toString()),
        "ledgerAccountId" to quoted(line.ledgerAccountId),
        "legacyBackfill" to line.legacyBackfill.toString(),
        "side" to quoted(line.side),
    )

    private fun auditJson(value: AuditSnapshotEntity) = obj(
        "after" to quoted(value.afterJson),
        "before" to quoted(value.beforeJson),
        "id" to quoted(value.id.toString()),
        "reason" to quoted(value.reason),
    )

    private fun receiptJson(value: ReceiptEntity) = obj(
        "byteSize" to quoted(value.byteSize.toString()),
        "capturedAt" to nullableQuoted(value.capturedAt?.toString()),
        "displayName" to quoted(value.displayName),
        "id" to quoted(value.id.toString()),
        "mimeType" to quoted(value.mimeType),
        "origin" to quoted(value.origin),
        "sha256" to quoted(value.sha256),
        "storageId" to quoted(value.storageId),
    )

    private fun obj(vararg values: Pair<String, String>): String = values.sortedBy { it.first }
        .joinToString(prefix = "{", postfix = "}") { (key, value) -> "${quoted(key)}:$value" }

    private fun array(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]")

    private fun nullableQuoted(value: String?): String = value?.let(::quoted) ?: "null"

    private fun quoted(value: String): String = buildString {
        append('"')
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char.isHighSurrogate()) {
                require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                    "Teks jurnal mengandung Unicode surrogate yang tidak valid"
                }
                append(char).append(value[index + 1])
                index += 2
                continue
            }
            require(!char.isLowSurrogate()) { "Teks jurnal mengandung Unicode surrogate yang tidak valid" }
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
            index++
        }
        append('"')
    }
}

package com.morneven.kron.audit

import com.morneven.kron.data.ActivityEventEntity
import com.morneven.kron.data.AuditSnapshotEntity
import com.morneven.kron.data.BudgetJournalLineEntity
import com.morneven.kron.data.CashJournalLineEntity
import com.morneven.kron.data.LedgerLineEntity
import com.morneven.kron.data.ReceiptEntity
import com.morneven.kron.data.TransactionSplitEntity
import java.security.MessageDigest

object TeamLedgerCanonicalizer {
    const val VERSION = 1

    fun payloadHash(
        event: ActivityEventEntity,
        teamId: String,
        cash: List<CashJournalLineEntity>,
        budget: List<BudgetJournalLineEntity>,
        splits: List<TransactionSplitEntity>,
        ledger: List<LedgerLineEntity>,
        audits: List<AuditSnapshotEntity>,
        receipts: List<ReceiptEntity>,
        allocationSyncIds: Map<Long, String>,
        categorySyncIds: Map<Long, String>,
    ): String {
        require(teamId.isNotBlank()) { "Team ID tidak valid" }
        require(cash.all { it.accountId == event.accountId }) { "Event Team menyentuh akun lain" }
        require(budget.all { it.accountId == event.accountId }) { "Budget Team menyentuh akun lain" }
        require(ledger.all { it.accountId == null || it.accountId == event.accountId }) { "Ledger Team menyentuh akun lain" }

        val payload = record(
            "KRON_TEAM_EVENT_$VERSION",
            teamId,
            event.id,
            event.type,
            event.title,
            event.note,
            event.source,
            event.effectiveEpochDay.toString(),
            event.createdAt.toString(),
            event.relatedEventId,
            event.reversedByEventId,
            event.targetAllocationId?.let { stable(allocationSyncIds, it, "allocation") },
            list("cash", cash.map {
                record("cash", it.fundingChannel, it.amount.toString())
            }),
            list("budget", budget.map {
                record(
                    "budget",
                    it.allocationId?.let { id -> stable(allocationSyncIds, id, "allocation") },
                    it.bucket,
                    it.fundingChannel,
                    it.amount.toString(),
                )
            }),
            list("splits", splits.map {
                record(
                    "split",
                    it.categoryId?.let { id -> stable(categorySyncIds, id, "category") },
                    it.allocationId?.let { id -> stable(allocationSyncIds, id, "allocation") },
                    it.amount.toString(),
                )
            }),
            list("ledger", ledger.map {
                record(
                    "ledger",
                    stableLedgerAccount(it, teamId, categorySyncIds),
                    it.side,
                    it.amount.toString(),
                    it.fundingChannel,
                    it.categoryId?.let { id -> stable(categorySyncIds, id, "category") },
                    it.correlationId,
                    it.legacyBackfill.toString(),
                )
            }),
            list("audits", audits.map {
                record("audit", it.reason, it.beforeJson, it.afterJson)
            }),
            list("receipts", receipts.map {
                record(
                    "receipt",
                    it.storageId,
                    it.displayName,
                    it.mimeType,
                    it.byteSize.toString(),
                    it.sha256,
                    it.encryptionNonce,
                    it.encryptionVersion.toString(),
                    it.createdAt.toString(),
                    it.capturedAt?.toString(),
                    it.latitude?.toString(),
                    it.longitude?.toString(),
                    it.origin,
                    it.evidenceEventId,
                )
            }),
        )
        return sha256(payload.toByteArray(Charsets.UTF_8))
    }

    fun chainHash(
        teamId: String,
        chainId: String,
        previousChainHash: String,
        payloadHash: String,
        sequence: Long,
        recordedAtUtc: Long,
        deviceId: String,
        actor: String,
        appVersion: String,
        keyId: String,
    ): String = sha256(
        record(
            "KRON_TEAM_PROOF_$VERSION",
            teamId,
            chainId,
            previousChainHash,
            payloadHash,
            sequence.toString(),
            recordedAtUtc.toString(),
            deviceId,
            actor,
            appVersion,
            keyId,
        ).toByteArray(Charsets.UTF_8),
    )

    fun chainId(teamId: String, deviceId: String): String = sha256(
        record("KRON_TEAM_CHAIN_$VERSION", teamId, deviceId).toByteArray(Charsets.UTF_8),
    )

    private fun stable(values: Map<Long, String>, id: Long, label: String): String =
        requireNotNull(values[id]?.takeIf(String::isNotBlank)) { "Referensi $label Team tidak stabil" }

    private fun stableLedgerAccount(
        line: LedgerLineEntity,
        teamId: String,
        categorySyncIds: Map<Long, String>,
    ): String = when {
        line.ledgerAccountId.startsWith("asset:") -> record(
            "asset",
            teamId,
            requireNotNull(line.fundingChannel) { "Kanal aset Team tidak tersedia" },
        )
        line.categoryId != null -> record(
            line.ledgerAccountId.substringBefore(":category:").also {
                require(it == "income" || it == "expense") { "Akun ledger kategori tidak valid" }
            },
            stable(categorySyncIds, line.categoryId, "category"),
        )
        else -> line.ledgerAccountId
    }

    private fun list(name: String, values: List<String>): String = buildString {
        append(encoded(name))
        values.sorted().forEach { append(encoded(it)) }
    }

    private fun record(name: String, vararg values: String?): String = buildString {
        append(encoded(name))
        values.forEach { append(encoded(it)) }
    }

    private fun encoded(value: String?): String = value?.let {
        "${it.toByteArray(Charsets.UTF_8).size}:$it"
    } ?: "-1:"

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

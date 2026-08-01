package com.morneven.kron.capsule

data class CapsuleManifest(
    val formatVersion: Int = 2,
    val capsuleId: String,
    val fileId: String,
    val sourceAccountId: Long,
    val sourceAccountName: String,
    val sourceSharingMode: String,
    val ownerGoogleSubjectId: String,
    val ownerFingerprint: String,
    val targetEmail: String,
    val createdAt: Long,
    val claimExpiresAt: Long,
    val dataScope: String,
    val payloadSha256: String,
    val envelopeChecksum: String,
)

data class CapsuleSnapshot(
    val accountName: String,
    val sharingMode: String,
    val currency: String = "IDR",
    val snapshotEpochMillis: Long,
    val cashBalance: Long = 0,
    val vaultBalance: Long = 0,
    val unallocatedBalance: Long = 0,
    val categories: List<CapsuleCategory> = emptyList(),
    val portfolios: List<CapsulePortfolio> = emptyList(),
    val periods: List<CapsulePeriod> = emptyList(),
    val allocations: List<CapsuleAllocation> = emptyList(),
    val transactions: List<CapsuleTransaction> = emptyList(),
    val journals: List<CapsuleJournalLine> = emptyList(),
    val auditEntries: List<CapsuleAuditEntry> = emptyList(),
    val receiptMetadata: List<CapsuleReceiptMeta> = emptyList(),
)

data class CapsuleCategory(val name: String, val direction: String, val color: Long, val icon: String)
data class CapsulePortfolio(val name: String, val cadence: String, val plannedIncome: Long)
data class CapsulePeriod(val portfolioName: String, val startEpochDay: Long, val endEpochDay: Long, val status: String)
data class CapsuleAllocation(
    val categoryName: String, val portfolioName: String, val periodStart: Long,
    val fundingChannel: String, val plannedAmount: Long, val spentAmount: Long, val availableAmount: Long,
)
data class CapsuleTransaction(
    val id: Long, val type: String, val title: String, val effectiveEpochDay: Long,
    val amount: Long, val direction: String, val categoryName: String?,
    val ledgerDebit: Long, val ledgerCredit: Long,
)
data class CapsuleJournalLine(val entryId: String, val side: String, val amount: Long, val description: String)
data class CapsuleAuditEntry(
    val eventId: Long, val type: String, val actor: String?, val deviceId: String?,
    val sealedAt: Long, val sealHash: String?,
)
data class CapsuleReceiptMeta(
    val eventId: Long, val fileName: String?, val mimeType: String?, val fileSize: Long, val fileHash: String,
)

enum class CapsuleLocalStatus { SENT, RECEIVED_ACTIVE, RECEIVED_EXPIRED, RECEIVED_REVOKED }

data class CapsuleRecord(
    val capsuleId: String,
    val fileId: String,
    val sourceAccountName: String,
    val targetEmail: String,
    val status: CapsuleLocalStatus,
    val createdAt: Long,
    val claimExpiresAt: Long,
    val openedAt: Long? = null,
    val expiresAt: Long? = null,
    val dataScope: String = "CurrentFullSnapshot",
)

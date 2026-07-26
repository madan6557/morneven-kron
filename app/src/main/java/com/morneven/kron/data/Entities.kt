package com.morneven.kron.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

object LedgerType {
    const val OPENING_BALANCE = "OPENING_BALANCE"
    const val INCOME = "INCOME"
    const val EXPENSE = "EXPENSE"
    const val TRANSFER = "TRANSFER"
    const val CHANNEL_TRANSFER = "CHANNEL_TRANSFER"
    const val PORTFOLIO_BOOKING = "PORTFOLIO_BOOKING"
    const val REALLOCATION = "REALLOCATION"
    const val OVERBUDGET_COVERAGE = "OVERBUDGET_COVERAGE"
    const val RELEASE = "RELEASE"
    const val ROLLOVER = "ROLLOVER"
    const val REVERSAL = "REVERSAL"
    const val AUTOMATION = "AUTOMATION"
    const val IMPORT = "IMPORT"
    const val SYSTEM = "SYSTEM"
    const val UNEXPECTED_EXPENSE = "UNEXPECTED_EXPENSE"
    const val ARCHIVE = "ARCHIVE"
    const val RESTORE = "RESTORE"
    const val ATTACH_EVIDENCE = "ATTACH_EVIDENCE"
    const val CORRECTION = "CORRECTION"
    const val RESTORE_REVERSAL = "RESTORE_REVERSAL"
    const val EVIDENCE_KEY_ROTATION = "EVIDENCE_KEY_ROTATION"
}

object LedgerSide {
    const val DEBIT = "DEBIT"
    const val CREDIT = "CREDIT"
}

object LedgerAccountKind {
    const val ASSET = "ASSET"
    const val EQUITY = "EQUITY"
    const val INCOME = "INCOME"
    const val EXPENSE = "EXPENSE"
    const val CLEARING = "CLEARING"
}

object EvidenceOrigin {
    const val CAMERA = "CAMERA"
    const val GALLERY = "GALLERY"
    const val LEGACY = "LEGACY"
}

object BudgetBucket {
    const val VAULT = "VAULT"
    const val UNALLOCATED = "UNALLOCATED"
    const val UNEXPECTED = "UNEXPECTED"
    const val ROLLOVER = "ROLLOVER"
    const val EXTERNAL = "EXTERNAL"
}

object FundingChannel {
    const val CASH = "CASH"
    const val EBUDGET = "EBUDGET"
}

object PeriodStatus {
    const val DRAFT = "DRAFT"
    const val UNDERFUNDED = "UNDERFUNDED"
    const val ACTIVE = "ACTIVE"
    const val RESOLUTION_REQUIRED = "RESOLUTION_REQUIRED"
    const val CLOSED = "CLOSED"
}

object TransactionDirection {
    const val INCOME = "INCOME"
    const val EXPENSE = "EXPENSE"
}

object AccountSharingMode {
    const val PRIVATE = "PRIVATE"
    const val TEAM = "TEAM"
}

object TeamRole {
    const val OWNER = "OWNER"
    const val EDITOR = "EDITOR"
    const val VIEWER = "VIEWER"
}

object TeamWorkspaceStatus {
    const val LOCAL_ONLY = "LOCAL_ONLY"
    const val SYNCED = "SYNCED"
    const val CONFLICT = "CONFLICT"
    const val REVOKED = "REVOKED"
    const val ARCHIVED = "ARCHIVED"
}

@Entity(tableName = "accounts", indices = [Index(value = ["teamId"], unique = true)])
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isActive: Boolean = false,
    val isArchived: Boolean = false,
    val archivedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "'PRIVATE'") val sharingMode: String = AccountSharingMode.PRIVATE,
    val teamId: String? = null,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    val lastWriterId: String? = null,
)

@Entity(
    tableName = "categories",
    indices = [Index("accountId"), Index(value = ["syncId"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val direction: String,
    val color: Long,
    val icon: String,
    val isArchived: Boolean = false,
    val accountId: Long? = null,
    @ColumnInfo(defaultValue = "''") val syncId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    val lastWriterId: String? = null,
)

@Entity(
    tableName = "portfolios",
    indices = [Index("accountId"), Index(value = ["syncId"], unique = true)],
)
data class PortfolioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val cadence: String,
    val intervalCount: Int = 1,
    val plannedIncome: Long,
    val rolloverEnabled: Boolean,
    val fundingPriority: Int,
    val startEpochDay: Long,
    val endMode: String,
    val endValue: Long? = null,
    val isPaused: Boolean = false,
    val isArchived: Boolean = false,
    val archivedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val accountId: Long,
    @ColumnInfo(defaultValue = "''") val syncId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    val lastWriterId: String? = null,
)

@Entity(
    tableName = "budget_periods",
    foreignKeys = [ForeignKey(
        entity = PortfolioEntity::class,
        parentColumns = ["id"],
        childColumns = ["portfolioId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [
        Index("portfolioId"),
        Index(value = ["portfolioId", "startEpochDay"], unique = true),
        Index(value = ["syncId"], unique = true),
    ],
)
data class BudgetPeriodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val portfolioId: Long,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val status: String,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val syncId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    val lastWriterId: String? = null,
)

@Entity(
    tableName = "allocations",
    foreignKeys = [
        ForeignKey(
            entity = BudgetPeriodEntity::class,
            parentColumns = ["id"],
            childColumns = ["periodId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("periodId"),
        Index("categoryId"),
        Index(value = ["periodId", "categoryId", "fundingChannel"], unique = true),
        Index(value = ["syncId"], unique = true),
    ],
)
data class AllocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val periodId: Long,
    val categoryId: Long,
    val fundingChannel: String,
    val plannedAmount: Long,
    @ColumnInfo(defaultValue = "''") val syncId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    val lastWriterId: String? = null,
)

@Entity(
    tableName = "portfolio_allocation_templates",
    foreignKeys = [
        ForeignKey(
            entity = PortfolioEntity::class,
            parentColumns = ["id"],
            childColumns = ["portfolioId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("portfolioId"),
        Index("categoryId"),
        Index(value = ["portfolioId", "categoryId"], unique = true),
        Index(value = ["syncId"], unique = true),
    ],
)
data class PortfolioAllocationTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val portfolioId: Long,
    val categoryId: Long,
    val plannedAmount: Long,
    val cashPercentage: Int,
    @ColumnInfo(defaultValue = "''") val syncId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    val lastWriterId: String? = null,
)

@Entity(
    tableName = "activity_events",
    indices = [
        Index("effectiveEpochDay"),
        Index("relatedEventId"),
        Index("accountId"),
        Index(value = ["accountId", "effectiveEpochDay"]),
    ],
)
data class ActivityEventEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    val note: String,
    val source: String,
    val effectiveEpochDay: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val relatedEventId: String? = null,
    val reversedByEventId: String? = null,
    val targetAllocationId: Long? = null,
    val accountId: Long,
)

@Entity(
    tableName = "ledger_accounts",
    indices = [
        Index(value = ["code"], unique = true),
        Index("accountId"),
        Index("categoryId"),
    ],
)
data class LedgerAccountEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val kind: String,
    val accountId: Long? = null,
    val fundingChannel: String? = null,
    val categoryId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ledger_lines",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = LedgerAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["ledgerAccountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("eventId"),
        Index("ledgerAccountId"),
        Index("accountId"),
        Index("correlationId"),
    ],
)
data class LedgerLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val ledgerAccountId: String,
    val side: String,
    val amount: Long,
    val accountId: Long? = null,
    val fundingChannel: String? = null,
    val categoryId: Long? = null,
    val correlationId: String? = null,
    val legacyBackfill: Boolean = false,
)

@Entity(
    tableName = "evidence_keys",
    indices = [Index(value = ["fingerprint"], unique = true)],
)
data class EvidenceKeyEntity(
    @PrimaryKey val id: String,
    val alias: String,
    val algorithm: String,
    val publicKeyBase64: String,
    val certificateBase64: String,
    val fingerprint: String,
    val securityLevel: String,
    val createdAt: Long = System.currentTimeMillis(),
    val retiredAt: Long? = null,
)

@Entity(
    tableName = "journal_seals",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = EvidenceKeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["keyId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["sequence"], unique = true),
        Index("keyId"),
    ],
)
data class JournalSealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val sequence: Long,
    val previousChainHash: String,
    val payloadHash: String,
    val chainHash: String,
    val signatureBase64: String,
    val recordedAtUtc: Long,
    val timezoneId: String,
    val deviceId: String,
    val actor: String,
    val appVersion: String,
    val keyId: String,
    val legacyBackfill: Boolean = false,
)

@Entity(tableName = "actor_profiles")
data class ActorProfileEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val displayName: String = "Pengguna lokal",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(
    tableName = "cash_journal_lines",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("eventId"), Index("accountId")],
)
data class CashJournalLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val accountId: Long,
    val fundingChannel: String,
    val amount: Long,
)

@Entity(
    tableName = "budget_journal_lines",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AllocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["allocationId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("eventId"), Index("allocationId"), Index("bucket"), Index(value = ["accountId", "fundingChannel"])],
)
data class BudgetJournalLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val allocationId: Long? = null,
    val bucket: String? = null,
    val fundingChannel: String,
    val amount: Long,
    val accountId: Long,
)

@Entity(
    tableName = "transaction_splits",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AllocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["allocationId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("eventId"), Index("categoryId"), Index("allocationId")],
)
data class TransactionSplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val categoryId: Long?,
    val allocationId: Long?,
    val amount: Long,
)

@Entity(
    tableName = "recurring_rules",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AllocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["allocationId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("accountId"),
        Index("categoryId"),
        Index("allocationId"),
        Index(value = ["syncId"], unique = true),
    ],
)
data class RecurringRuleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val direction: String,
    val amount: Long,
    val accountId: Long,
    val fundingChannel: String,
    val categoryId: Long?,
    val allocationId: Long?,
    val cadence: String,
    val intervalCount: Int = 1,
    val anchorMonth: Int,
    val anchorDay: Int,
    val startEpochDay: Long,
    val nextEpochDay: Long,
    val endEpochDay: Long? = null,
    val remainingOccurrences: Int? = null,
    val isPaused: Boolean = false,
    val pausedByArchive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val syncId: String = id,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    val lastWriterId: String? = null,
)

@Entity(
    tableName = "team_workspaces",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["teamId"], unique = true), Index(value = ["folderId"], unique = true)],
)
data class TeamWorkspaceEntity(
    @PrimaryKey val accountId: Long,
    val teamId: String,
    val folderId: String,
    val localRole: String,
    val ownerSubjectHash: String,
    val headSnapshotId: String? = null,
    @ColumnInfo(defaultValue = "0") val generation: Long = 0,
    @ColumnInfo(defaultValue = "'LOCAL_ONLY'") val status: String = TeamWorkspaceStatus.LOCAL_ONLY,
    @ColumnInfo(defaultValue = "0") val canRead: Boolean = true,
    @ColumnInfo(defaultValue = "0") val canWrite: Boolean = false,
    @ColumnInfo(defaultValue = "0") val canShare: Boolean = false,
    val capabilitiesVerifiedAt: Long? = null,
    val archivedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "team_members",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("accountId")],
)
data class TeamMemberEntity(
    @PrimaryKey val permissionId: String,
    val accountId: Long,
    val email: String,
    val displayName: String? = null,
    val role: String,
    val status: String,
    val refreshedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "team_invitation_uses", indices = [Index("teamId")])
data class TeamInvitationUseEntity(
    @PrimaryKey val inviteIdHash: String,
    val teamId: String,
    val usedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "recurring_occurrences",
    foreignKeys = [ForeignKey(
        entity = RecurringRuleEntity::class,
        parentColumns = ["id"],
        childColumns = ["ruleId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("ruleId"), Index(value = ["ruleId", "dueEpochDay"], unique = true)],
)
data class RecurringOccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: String,
    val dueEpochDay: Long,
    val eventId: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "audit_snapshots",
    foreignKeys = [ForeignKey(
        entity = ActivityEventEntity::class,
        parentColumns = ["id"],
        childColumns = ["eventId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("eventId")],
)
data class AuditSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val reason: String,
    val beforeJson: String,
    val afterJson: String,
)

@Entity(
    tableName = "receipts",
    foreignKeys = [ForeignKey(
        entity = ActivityEventEntity::class,
        parentColumns = ["id"],
        childColumns = ["eventId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("eventId"), Index(value = ["storageId"], unique = true), Index("evidenceEventId")],
)
data class ReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val localPath: String?,
    val storageId: String,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
    val sha256: String,
    val encryptionNonce: String? = null,
    val encryptionVersion: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val capturedAt: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val origin: String = EvidenceOrigin.LEGACY,
    val evidenceEventId: String? = null,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val datasetId: String,
    val deviceId: String,
    val accountSubject: String? = null,
    val accountEmail: String? = null,
    val localGeneration: Long = 0,
    val lastSyncedGeneration: Long = 0,
    val parentSnapshotId: String? = null,
    val lastSnapshotId: String? = null,
    val conflictRemoteFileId: String? = null,
    val lastSyncedAt: Long? = null,
    val status: String = "DISCONNECTED",
    val lastError: String? = null,
    val disabledDueToBilling: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

data class AccountBalanceRow(
    val id: Long,
    val name: String,
    val isActive: Boolean,
    val cashBalance: Long,
    val eBudgetBalance: Long,
    val totalBalance: Long,
)

data class AllocationBalanceRow(
    val id: Long,
    val periodId: Long,
    val portfolioId: Long,
    val portfolioName: String,
    val portfolioArchived: Boolean,
    val categoryId: Long,
    val categoryName: String,
    val color: Long,
    val fundingChannel: String,
    val plannedAmount: Long,
    val bookedAmount: Long,
    val availableAmount: Long,
    val spentAmount: Long,
    val periodStatus: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
)

data class ActivityRow(
    val id: String,
    val type: String,
    val title: String,
    val note: String,
    val source: String,
    val effectiveEpochDay: Long,
    val createdAt: Long,
    val relatedEventId: String?,
    val reversedByEventId: String?,
    val accountId: Long,
    val cashImpact: Long,
    val vaultImpact: Long,
    val budgetImpact: Long,
    val ledgerDebit: Long,
    val ledgerCredit: Long,
    val auditStatus: String,
)

data class EventChannelRow(
    val eventId: String,
    val fundingChannel: String,
)

data class CashflowRow(
    val income: Long,
    val expense: Long,
)

data class ChannelBalanceRow(
    val fundingChannel: String,
    val balance: Long,
)

data class LedgerEventBalanceRow(
    val eventId: String,
    val debit: Long,
    val credit: Long,
)

data class EvidenceHealthRow(
    val receiptId: Long,
    val eventId: String,
    val displayName: String,
    val sha256: String,
    val byteSize: Long,
    val origin: String,
    val evidenceEventId: String?,
)

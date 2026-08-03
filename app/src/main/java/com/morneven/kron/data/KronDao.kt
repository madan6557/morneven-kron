package com.morneven.kron.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface KronDao {
    @Insert suspend fun insertAccount(value: AccountEntity): Long
    @Update suspend fun updateAccount(value: AccountEntity)
    @Insert suspend fun insertCategory(value: CategoryEntity): Long
    @Insert suspend fun insertPortfolio(value: PortfolioEntity): Long
    @Update suspend fun updatePortfolio(value: PortfolioEntity)
    @Insert suspend fun insertPeriod(value: BudgetPeriodEntity): Long
    @Insert suspend fun insertAllocation(value: AllocationEntity): Long
    @Insert suspend fun insertAllocationTemplate(value: PortfolioAllocationTemplateEntity): Long
    @Insert suspend fun insertEvent(value: ActivityEventEntity)
    @Insert suspend fun insertCashLines(values: List<CashJournalLineEntity>)
    @Insert suspend fun insertBudgetLines(values: List<BudgetJournalLineEntity>)
    @Insert suspend fun insertSplits(values: List<TransactionSplitEntity>)
    @Insert suspend fun insertAudit(value: AuditSnapshotEntity)
    @Insert suspend fun insertRule(value: RecurringRuleEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertOccurrence(value: RecurringOccurrenceEntity): Long
    @Insert suspend fun insertReceipt(value: ReceiptEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertLedgerAccount(value: LedgerAccountEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertLedgerAccounts(values: List<LedgerAccountEntity>): List<Long>
    @Insert suspend fun insertLedgerLines(values: List<LedgerLineEntity>)
    @Insert suspend fun insertJournalSeal(value: JournalSealEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEvidenceKey(value: EvidenceKeyEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertActorProfile(value: ActorProfileEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSyncState(value: SyncStateEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTeamWorkspace(value: TeamWorkspaceEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTeamMembers(values: List<TeamMemberEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTeamInvitationUse(value: TeamInvitationUseEntity): Long
    @Insert suspend fun insertTeamEventProof(value: TeamEventProofEntity)
    @RawQuery suspend fun executeRaw(query: SupportSQLiteQuery): Int

    @Update suspend fun updatePeriod(value: BudgetPeriodEntity)
    @Update suspend fun updateAllocation(value: AllocationEntity)
    @Update suspend fun updateRule(value: RecurringRuleEntity)
    @Update suspend fun updateSyncState(value: SyncStateEntity)
    @Update suspend fun updateCategory(value: CategoryEntity)
    @Update suspend fun updateAllocationTemplate(value: PortfolioAllocationTemplateEntity)

    @Query("SELECT COUNT(*) FROM accounts") suspend fun accountCount(): Int
    @Query("SELECT COUNT(*) FROM accounts WHERE sharingMode = 'TEAM'") suspend fun teamAccountCount(): Int
    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY createdAt") fun observeAccounts(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM accounts WHERE isArchived = 1 ORDER BY archivedAt DESC, createdAt") fun observeArchivedAccounts(): Flow<List<AccountEntity>>
    @Query("""SELECT a.* FROM accounts a JOIN team_workspaces w ON w.accountId=a.id
        WHERE a.isArchived=0 AND a.sharingMode='TEAM' AND w.status='LOCAL_ONLY' ORDER BY a.createdAt""")
    fun observeRecoveredTeamAccounts(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY direction, name") fun observeCategories(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories WHERE isArchived = 0 AND (accountId IS NULL OR accountId = :accountId) ORDER BY direction, name")
    fun observeCategoriesForAccount(accountId: Long): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM portfolios WHERE isArchived = 0 ORDER BY fundingPriority, createdAt") fun observePortfolios(): Flow<List<PortfolioEntity>>
    @Query("SELECT * FROM portfolios WHERE isArchived = 1 ORDER BY archivedAt DESC, createdAt") fun observeArchivedPortfolios(): Flow<List<PortfolioEntity>>
    @Query("SELECT * FROM budget_periods ORDER BY startEpochDay DESC") fun observePeriods(): Flow<List<BudgetPeriodEntity>>
    @Query("SELECT * FROM recurring_rules ORDER BY nextEpochDay, createdAt") fun observeRules(): Flow<List<RecurringRuleEntity>>
    @Query("SELECT * FROM receipts ORDER BY createdAt, id") fun observeReceipts(): Flow<List<ReceiptEntity>>
    @Query("SELECT * FROM sync_state WHERE id = 1") fun observeSyncState(): Flow<SyncStateEntity?>
    @Query("SELECT * FROM team_workspaces WHERE accountId = :accountId LIMIT 1")
    fun observeTeamWorkspace(accountId: Long): Flow<TeamWorkspaceEntity?>
    @Query("""SELECT w.* FROM team_workspaces w JOIN accounts a ON a.id=w.accountId
        WHERE a.isArchived=0 AND a.sharingMode='TEAM'
          AND w.status NOT IN ('CONFLICT','REVOKED','ARCHIVED','APPLY_PENDING')
          AND w.canRead=1 AND w.capabilitiesVerifiedAt IS NOT NULL
        ORDER BY w.updatedAt,w.accountId""")
    fun observeAutoSyncTeamWorkspaces(): Flow<List<TeamWorkspaceEntity>>
    @Query("""SELECT w.* FROM team_workspaces w JOIN accounts a ON a.id=w.accountId
        WHERE a.isArchived=0 AND a.sharingMode='TEAM'
          AND w.status NOT IN ('CONFLICT','REVOKED','ARCHIVED','APPLY_PENDING')
          AND w.canRead=1 AND w.capabilitiesVerifiedAt IS NOT NULL
        ORDER BY w.updatedAt,w.accountId""")
    suspend fun autoSyncTeamWorkspaces(): List<TeamWorkspaceEntity>
    @Query("SELECT * FROM team_members WHERE accountId = :accountId ORDER BY role, displayName, email")
    fun observeTeamMembers(accountId: Long): Flow<List<TeamMemberEntity>>

    @Query("""
        UPDATE team_workspaces
        SET headSnapshotId = :snapshotId, status = :status, updatedAt = :updatedAt
        WHERE accountId = :accountId AND teamId = :teamId AND generation = :generation
          AND ((headSnapshotId IS NULL AND :expectedHead IS NULL) OR headSnapshotId = :expectedHead)
    """)
    suspend fun markTeamSnapshotPublished(
        accountId: Long,
        teamId: String,
        generation: Long,
        expectedHead: String?,
        snapshotId: String,
        status: String,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE team_workspaces SET status = :status, updatedAt = :updatedAt
        WHERE accountId = :accountId AND teamId = :teamId AND generation = :generation
          AND ((headSnapshotId IS NULL AND :expectedHead IS NULL) OR headSnapshotId = :expectedHead)
    """)
    suspend fun markTeamSnapshotStatus(
        accountId: Long,
        teamId: String,
        generation: Long,
        expectedHead: String?,
        status: String,
        updatedAt: Long,
    ): Int

    @Query("""UPDATE team_workspaces SET status=:status,updatedAt=:updatedAt
        WHERE accountId=:accountId AND teamId=:teamId""")
    suspend fun markTeamWorkspaceStatus(
        accountId: Long,
        teamId: String,
        status: String,
        updatedAt: Long,
    ): Int

    @Query("UPDATE team_workspaces SET status = 'FAILED', updatedAt = :updatedAt WHERE status = 'APPLY_PENDING'")
    suspend fun markFailedTeamActivations(updatedAt: Long): Int

    @Query("""UPDATE team_workspaces SET liveFileId=:fileId, updatedAt=:updatedAt
        WHERE accountId=:accountId AND teamId=:teamId AND liveFileId IS NULL""")
    suspend fun setTeamLiveFileId(
        accountId: Long,
        teamId: String,
        fileId: String,
        updatedAt: Long,
    ): Int

    @Query("""UPDATE team_workspaces SET localRole=:role,canRead=:canRead,canWrite=:canWrite,canShare=:canShare,
        capabilitiesVerifiedAt=:verifiedAt,updatedAt=:verifiedAt WHERE accountId=:accountId AND teamId=:teamId""")
    suspend fun updateTeamCapabilities(
        accountId: Long,
        teamId: String,
        role: String,
        canRead: Boolean,
        canWrite: Boolean,
        canShare: Boolean,
        verifiedAt: Long,
    ): Int

    @Query("SELECT * FROM portfolios WHERE isArchived = 0 AND accountId = :accountId ORDER BY fundingPriority, createdAt")
    fun observePortfoliosForAccount(accountId: Long): Flow<List<PortfolioEntity>>
    @Query("SELECT * FROM portfolios WHERE isArchived = 1 AND accountId = :accountId ORDER BY archivedAt DESC, createdAt")
    fun observeArchivedPortfoliosForAccount(accountId: Long): Flow<List<PortfolioEntity>>
    @Query("SELECT p.* FROM budget_periods p JOIN portfolios pf ON pf.id = p.portfolioId WHERE pf.accountId = :accountId ORDER BY p.startEpochDay DESC")
    fun observePeriodsForAccount(accountId: Long): Flow<List<BudgetPeriodEntity>>
    @Query("SELECT * FROM recurring_rules WHERE accountId = :accountId ORDER BY nextEpochDay, createdAt")
    fun observeRulesForAccount(accountId: Long): Flow<List<RecurringRuleEntity>>
    @Query("SELECT r.* FROM receipts r JOIN activity_events e ON e.id = r.eventId WHERE e.accountId = :accountId ORDER BY r.createdAt, r.id")
    fun observeReceiptsForAccount(accountId: Long): Flow<List<ReceiptEntity>>

    @Query("""
        SELECT a.id, a.name, a.isActive,
               COALESCE(SUM(CASE WHEN c.fundingChannel = 'CASH' THEN c.amount ELSE 0 END), 0) AS cashBalance,
               COALESCE(SUM(CASE WHEN c.fundingChannel = 'EBUDGET' THEN c.amount ELSE 0 END), 0) AS eBudgetBalance,
               COALESCE(SUM(c.amount), 0) AS totalBalance
        FROM accounts a
        LEFT JOIN cash_journal_lines c ON c.accountId = a.id
        WHERE a.isArchived = 0
        GROUP BY a.id
        ORDER BY a.createdAt
    """)
    fun observeAccountBalances(): Flow<List<AccountBalanceRow>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'VAULT'")
    fun observeVaultBalance(): Flow<Long>
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'VAULT' AND accountId = :accountId")
    fun observeVaultBalance(accountId: Long): Flow<Long>

    @Query("SELECT fundingChannel, COALESCE(SUM(amount), 0) AS balance FROM budget_journal_lines WHERE bucket = 'VAULT' GROUP BY fundingChannel")
    fun observeVaultByChannel(): Flow<List<ChannelBalanceRow>>
    @Query("SELECT fundingChannel, COALESCE(SUM(amount), 0) AS balance FROM budget_journal_lines WHERE bucket = 'VAULT' AND accountId = :accountId GROUP BY fundingChannel")
    fun observeVaultByChannel(accountId: Long): Flow<List<ChannelBalanceRow>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'UNALLOCATED'")
    fun observeUnallocatedBalance(): Flow<Long>
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'UNALLOCATED' AND accountId = :accountId")
    fun observeUnallocatedBalance(accountId: Long): Flow<Long>

    @Query("SELECT fundingChannel, COALESCE(SUM(amount), 0) AS balance FROM budget_journal_lines WHERE bucket = 'UNALLOCATED' GROUP BY fundingChannel")
    fun observeUnallocatedByChannel(): Flow<List<ChannelBalanceRow>>
    @Query("SELECT fundingChannel, COALESCE(SUM(amount), 0) AS balance FROM budget_journal_lines WHERE bucket = 'UNALLOCATED' AND accountId = :accountId GROUP BY fundingChannel")
    fun observeUnallocatedByChannel(accountId: Long): Flow<List<ChannelBalanceRow>>

    @Query("""
        SELECT al.id, al.periodId, p.portfolioId, pf.name AS portfolioName, pf.isArchived AS portfolioArchived,
               al.categoryId, c.name AS categoryName, c.color, al.fundingChannel,
               al.plannedAmount,
               COALESCE(SUM(b.amount), 0) - COALESCE(SUM(CASE WHEN e.type IN ('EXPENSE','AUTOMATION') AND NOT EXISTS(SELECT 1 FROM activity_events rv WHERE rv.type = 'REVERSAL' AND rv.relatedEventId = e.id) AND b.amount < 0 THEN b.amount ELSE 0 END), 0) AS bookedAmount,
               COALESCE(SUM(b.amount), 0) AS availableAmount,
               -COALESCE(SUM(CASE WHEN e.type IN ('EXPENSE','AUTOMATION') AND NOT EXISTS(SELECT 1 FROM activity_events rv WHERE rv.type = 'REVERSAL' AND rv.relatedEventId = e.id) AND b.amount < 0 THEN b.amount ELSE 0 END), 0) AS spentAmount,
               p.status AS periodStatus, p.startEpochDay, p.endEpochDay
        FROM allocations al
        JOIN budget_periods p ON p.id = al.periodId
        JOIN portfolios pf ON pf.id = p.portfolioId
        JOIN categories c ON c.id = al.categoryId
        LEFT JOIN budget_journal_lines b ON b.allocationId = al.id
        LEFT JOIN activity_events e ON e.id = b.eventId
        GROUP BY al.id
        ORDER BY p.startEpochDay DESC, c.name
    """)
    fun observeAllocationBalances(): Flow<List<AllocationBalanceRow>>

    @Query("""
        SELECT al.id, al.periodId, p.portfolioId, pf.name AS portfolioName, pf.isArchived AS portfolioArchived,
               al.categoryId, c.name AS categoryName, c.color, al.fundingChannel,
               al.plannedAmount,
               COALESCE(SUM(b.amount), 0) - COALESCE(SUM(CASE WHEN e.type IN ('EXPENSE','AUTOMATION') AND NOT EXISTS(SELECT 1 FROM activity_events rv WHERE rv.type = 'REVERSAL' AND rv.relatedEventId = e.id) AND b.amount < 0 THEN b.amount ELSE 0 END), 0) AS bookedAmount,
               COALESCE(SUM(b.amount), 0) AS availableAmount,
               -COALESCE(SUM(CASE WHEN e.type IN ('EXPENSE','AUTOMATION') AND NOT EXISTS(SELECT 1 FROM activity_events rv WHERE rv.type = 'REVERSAL' AND rv.relatedEventId = e.id) AND b.amount < 0 THEN b.amount ELSE 0 END), 0) AS spentAmount,
               p.status AS periodStatus, p.startEpochDay, p.endEpochDay
        FROM allocations al
        JOIN budget_periods p ON p.id = al.periodId
        JOIN portfolios pf ON pf.id = p.portfolioId
        JOIN categories c ON c.id = al.categoryId
        LEFT JOIN budget_journal_lines b ON b.allocationId = al.id
        LEFT JOIN activity_events e ON e.id = b.eventId
        WHERE pf.accountId = :accountId
        GROUP BY al.id
        ORDER BY p.startEpochDay DESC, c.name
    """)
    fun observeAllocationBalancesForAccount(accountId: Long): Flow<List<AllocationBalanceRow>>

    @Query("""
        SELECT e.id, e.type, e.title, e.note, e.source, e.effectiveEpochDay, e.createdAt,
               e.relatedEventId,
               COALESCE(e.reversedByEventId, (SELECT rv.id FROM activity_events rv WHERE rv.type = 'REVERSAL' AND rv.relatedEventId = e.id ORDER BY rv.createdAt, rv.id LIMIT 1)) AS reversedByEventId,
               e.accountId,
               COALESCE((SELECT SUM(amount) FROM cash_journal_lines WHERE eventId = e.id), 0) AS cashImpact,
               COALESCE((SELECT SUM(amount) FROM budget_journal_lines WHERE eventId = e.id AND bucket = 'VAULT'), 0) AS vaultImpact,
               COALESCE((SELECT SUM(amount) FROM budget_journal_lines WHERE eventId = e.id AND allocationId IS NOT NULL), 0) AS budgetImpact,
               COALESCE((SELECT SUM(amount) FROM ledger_lines WHERE eventId = e.id AND side = 'DEBIT'), 0) AS ledgerDebit,
               COALESCE((SELECT SUM(amount) FROM ledger_lines WHERE eventId = e.id AND side = 'CREDIT'), 0) AS ledgerCredit,
               CASE
                   WHEN EXISTS(SELECT 1 FROM activity_events rv WHERE rv.type = 'REVERSAL' AND rv.relatedEventId = e.id) THEN 'Reversed'
                   WHEN e.type = 'CORRECTION' THEN 'Corrected'
                   WHEN NOT EXISTS(SELECT 1 FROM journal_seals s WHERE s.eventId = e.id) THEN 'Integrity problem'
                   WHEN EXISTS(SELECT 1 FROM ledger_lines l WHERE l.eventId = e.id AND l.legacyBackfill = 1)
                     OR EXISTS(SELECT 1 FROM journal_seals s WHERE s.eventId = e.id AND s.legacyBackfill = 1) THEN 'Legacy'
                   ELSE 'Valid'
               END AS auditStatus,
               COALESCE((SELECT actor FROM team_event_proofs p WHERE p.eventId=e.id),
                        (SELECT actor FROM journal_seals s WHERE s.eventId=e.id),'Tidak tersedia') AS actor,
               COALESCE((SELECT deviceId FROM team_event_proofs p WHERE p.eventId=e.id),
                        (SELECT deviceId FROM journal_seals s WHERE s.eventId=e.id),'Tidak tersedia') AS deviceId
        FROM activity_events e
        ORDER BY e.effectiveEpochDay DESC, e.createdAt DESC
    """)
    fun observeActivities(): Flow<List<ActivityRow>>

    @Query("""
        SELECT e.id, e.type, e.title, e.note, e.source, e.effectiveEpochDay, e.createdAt,
               e.relatedEventId,
               COALESCE(e.reversedByEventId, (SELECT rv.id FROM activity_events rv WHERE rv.type = 'REVERSAL' AND rv.relatedEventId = e.id ORDER BY rv.createdAt, rv.id LIMIT 1)) AS reversedByEventId,
               e.accountId,
               COALESCE((SELECT SUM(amount) FROM cash_journal_lines WHERE eventId = e.id), 0) AS cashImpact,
               COALESCE((SELECT SUM(amount) FROM budget_journal_lines WHERE eventId = e.id AND bucket = 'VAULT'), 0) AS vaultImpact,
               COALESCE((SELECT SUM(amount) FROM budget_journal_lines WHERE eventId = e.id AND allocationId IS NOT NULL), 0) AS budgetImpact,
               COALESCE((SELECT SUM(amount) FROM ledger_lines WHERE eventId = e.id AND side = 'DEBIT'), 0) AS ledgerDebit,
               COALESCE((SELECT SUM(amount) FROM ledger_lines WHERE eventId = e.id AND side = 'CREDIT'), 0) AS ledgerCredit,
               CASE
                   WHEN EXISTS(SELECT 1 FROM activity_events rv WHERE rv.type = 'REVERSAL' AND rv.relatedEventId = e.id) THEN 'Reversed'
                   WHEN e.type = 'CORRECTION' THEN 'Corrected'
                   WHEN NOT EXISTS(SELECT 1 FROM journal_seals s WHERE s.eventId = e.id) THEN 'Integrity problem'
                   WHEN EXISTS(SELECT 1 FROM ledger_lines l WHERE l.eventId = e.id AND l.legacyBackfill = 1)
                     OR EXISTS(SELECT 1 FROM journal_seals s WHERE s.eventId = e.id AND s.legacyBackfill = 1) THEN 'Legacy'
                   ELSE 'Valid'
               END AS auditStatus,
               COALESCE((SELECT actor FROM team_event_proofs p WHERE p.eventId=e.id),
                        (SELECT actor FROM journal_seals s WHERE s.eventId=e.id),'Tidak tersedia') AS actor,
               COALESCE((SELECT deviceId FROM team_event_proofs p WHERE p.eventId=e.id),
                        (SELECT deviceId FROM journal_seals s WHERE s.eventId=e.id),'Tidak tersedia') AS deviceId
        FROM activity_events e
        WHERE e.accountId = :accountId
        ORDER BY e.effectiveEpochDay DESC, e.createdAt DESC
    """)
    fun observeActivitiesForAccount(accountId: Long): Flow<List<ActivityRow>>

    @Query("SELECT DISTINCT eventId, fundingChannel FROM cash_journal_lines ORDER BY eventId, fundingChannel")
    fun observeEventChannels(): Flow<List<EventChannelRow>>
    @Query("SELECT DISTINCT c.eventId, c.fundingChannel FROM cash_journal_lines c JOIN activity_events e ON e.id = c.eventId WHERE e.accountId = :accountId ORDER BY c.eventId, c.fundingChannel")
    fun observeEventChannelsForAccount(accountId: Long): Flow<List<EventChannelRow>>

    @Query("""
        SELECT
            COALESCE(SUM(CASE WHEN e.type IN ('INCOME','OPENING_BALANCE','AUTOMATION') AND c.amount > 0 THEN c.amount ELSE 0 END), 0) AS income,
            -COALESCE(SUM(CASE WHEN e.type IN ('EXPENSE','UNEXPECTED_EXPENSE','AUTOMATION') AND c.amount < 0 THEN c.amount ELSE 0 END), 0) AS expense
        FROM activity_events e
        JOIN cash_journal_lines c ON c.eventId = e.id
        WHERE e.effectiveEpochDay BETWEEN :startDay AND :endDay
          AND NOT EXISTS(SELECT 1 FROM activity_events rv WHERE rv.type = 'REVERSAL' AND rv.relatedEventId = e.id)
    """)
    fun observeCashflow(startDay: Long, endDay: Long): Flow<CashflowRow>
    @Query("""
        SELECT
            COALESCE(SUM(CASE WHEN e.type IN ('INCOME','OPENING_BALANCE','AUTOMATION') AND c.amount > 0 THEN c.amount ELSE 0 END), 0) AS income,
            -COALESCE(SUM(CASE WHEN e.type IN ('EXPENSE','UNEXPECTED_EXPENSE','AUTOMATION') AND c.amount < 0 THEN c.amount ELSE 0 END), 0) AS expense
        FROM activity_events e
        JOIN cash_journal_lines c ON c.eventId = e.id
        WHERE e.effectiveEpochDay BETWEEN :startDay AND :endDay AND e.accountId = :accountId
          AND NOT EXISTS(SELECT 1 FROM activity_events rv WHERE rv.type = 'REVERSAL' AND rv.relatedEventId = e.id)
    """)
    fun observeCashflow(startDay: Long, endDay: Long, accountId: Long): Flow<CashflowRow>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM cash_journal_lines") suspend fun cashTotal(): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL") suspend fun budgetAvailableTotal(): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE eventId = :eventId") suspend fun budgetEventTotal(eventId: String): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'VAULT'") suspend fun vaultBalance(): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'VAULT' AND fundingChannel = :channel") suspend fun vaultBalance(channel: String): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'VAULT' AND fundingChannel = :channel AND accountId = :accountId") suspend fun vaultBalance(channel: String, accountId: Long): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'UNALLOCATED' AND fundingChannel = :channel") suspend fun unallocatedBalance(channel: String): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'UNALLOCATED' AND fundingChannel = :channel AND accountId = :accountId") suspend fun unallocatedBalance(channel: String, accountId: Long): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = :bucket AND fundingChannel = :channel") suspend fun budgetBucketBalance(bucket: String, channel: String): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE allocationId = :allocationId") suspend fun allocationAvailable(allocationId: Long): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE allocationId = :allocationId AND eventId IN (SELECT id FROM activity_events WHERE type IN ('PORTFOLIO_BOOKING','REALLOCATION','OVERBUDGET_COVERAGE','RELEASE','ROLLOVER'))") suspend fun allocationBooked(allocationId: Long): Long
    @Query("SELECT * FROM allocations WHERE id = :id") suspend fun allocationById(id: Long): AllocationEntity?
    @Query("SELECT pf.* FROM allocations al JOIN budget_periods p ON p.id = al.periodId JOIN portfolios pf ON pf.id = p.portfolioId WHERE al.id = :allocationId") suspend fun portfolioForAllocation(allocationId: Long): PortfolioEntity?
    @Query("SELECT * FROM allocations WHERE periodId = :periodId AND categoryId = :categoryId AND fundingChannel = :channel LIMIT 1") suspend fun allocationFor(periodId: Long, categoryId: Long, channel: String): AllocationEntity?
    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun accountById(id: Long): AccountEntity?
    @Query("SELECT * FROM team_workspaces WHERE accountId = :accountId LIMIT 1") suspend fun teamWorkspace(accountId: Long): TeamWorkspaceEntity?
    @Query("SELECT * FROM team_workspaces WHERE teamId = :teamId LIMIT 1") suspend fun teamWorkspaceByTeamId(teamId: String): TeamWorkspaceEntity?
    @Query("SELECT teamId FROM team_workspaces") suspend fun allTeamWorkspaceIds(): List<String>
    @Query("SELECT * FROM team_members WHERE accountId = :accountId ORDER BY role, displayName, email") suspend fun teamMembers(accountId: Long): List<TeamMemberEntity>
    @Query("DELETE FROM team_members WHERE accountId = :accountId") suspend fun clearTeamMembers(accountId: Long)
    @Query("DELETE FROM team_members WHERE accountId = :accountId AND permissionId = :permissionId")
    suspend fun deleteTeamMember(accountId: Long, permissionId: String)
    @Query("DELETE FROM team_workspaces WHERE teamId = :teamId") suspend fun deleteTeamWorkspace(teamId: String)
    @Query("SELECT EXISTS(SELECT 1 FROM team_invitation_uses WHERE inviteIdHash = :inviteIdHash)") suspend fun teamInvitationWasUsed(inviteIdHash: String): Boolean
    @Query("SELECT * FROM team_event_proofs WHERE eventId = :eventId LIMIT 1") suspend fun teamEventProof(eventId: String): TeamEventProofEntity?
    @Query("SELECT * FROM team_event_proofs WHERE chainId = :chainId ORDER BY sequence DESC LIMIT 1") suspend fun latestTeamEventProof(chainId: String): TeamEventProofEntity?
    @Query("SELECT * FROM team_event_proofs ORDER BY chainId, sequence") suspend fun allTeamEventProofs(): List<TeamEventProofEntity>
    @Query("""
        SELECT e.* FROM activity_events e
        JOIN accounts a ON a.id = e.accountId
        JOIN team_workspaces w ON w.accountId = a.id AND w.teamId = a.teamId
        WHERE a.sharingMode = 'TEAM'
          AND NOT EXISTS(SELECT 1 FROM team_event_proofs p WHERE p.eventId = e.id)
        ORDER BY e.createdAt, e.id
    """)
    suspend fun teamEventsWithoutProof(): List<ActivityEventEntity>
    @Query("SELECT * FROM accounts WHERE isActive = 1 AND isArchived = 0 LIMIT 1") suspend fun activeAccount(): AccountEntity?
    @Query("SELECT * FROM accounts WHERE isActive = 1 AND isArchived = 0 LIMIT 1") fun observeActiveAccount(): Flow<AccountEntity?>
    @Query("SELECT COUNT(*) FROM accounts WHERE isActive = 1 AND isArchived = 0") suspend fun activeAccountCount(): Int
    @Query("UPDATE accounts SET isActive = CASE WHEN id = :accountId THEN 1 ELSE 0 END WHERE isArchived = 0") suspend fun activateOnly(accountId: Long)
    @Query("SELECT COALESCE(SUM(amount), 0) FROM cash_journal_lines WHERE accountId = :accountId") suspend fun accountBalance(accountId: Long): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM cash_journal_lines WHERE accountId = :accountId AND fundingChannel = :channel") suspend fun accountBalance(accountId: Long, channel: String): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE accountId = :accountId AND (bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL)") suspend fun budgetAvailableTotal(accountId: Long): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE accountId = :accountId AND fundingChannel = :channel AND (bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL)") suspend fun budgetAvailableTotal(accountId: Long, channel: String): Long
    @Query("SELECT * FROM budget_periods WHERE id = :id") suspend fun periodById(id: Long): BudgetPeriodEntity?
    @Query("SELECT * FROM budget_periods WHERE portfolioId = :portfolioId ORDER BY startEpochDay") suspend fun periodsForPortfolio(portfolioId: Long): List<BudgetPeriodEntity>
    @Query("SELECT id FROM allocations WHERE periodId = :periodId") suspend fun allocationIdsForPeriod(periodId: Long): List<Long>
    @Query("SELECT * FROM allocations WHERE periodId = :periodId ORDER BY id") suspend fun allocationsForPeriod(periodId: Long): List<AllocationEntity>
    @Query("SELECT * FROM activity_events WHERE id = :id") suspend fun eventById(id: String): ActivityEventEntity?
    @Query("SELECT * FROM cash_journal_lines WHERE eventId = :eventId") suspend fun cashLinesForEvent(eventId: String): List<CashJournalLineEntity>
    @Query("SELECT * FROM budget_journal_lines WHERE eventId = :eventId") suspend fun budgetLinesForEvent(eventId: String): List<BudgetJournalLineEntity>
    @Query("SELECT * FROM transaction_splits WHERE eventId = :eventId") suspend fun splitsForEvent(eventId: String): List<TransactionSplitEntity>
    @Query("SELECT * FROM ledger_lines WHERE eventId = :eventId ORDER BY id") suspend fun ledgerLinesForEvent(eventId: String): List<LedgerLineEntity>
    @Query("SELECT * FROM journal_seals WHERE eventId = :eventId LIMIT 1") suspend fun sealForEvent(eventId: String): JournalSealEntity?
    @Query("SELECT * FROM journal_seals ORDER BY sequence DESC LIMIT 1") suspend fun latestSeal(): JournalSealEntity?
    @Query("SELECT * FROM journal_seals ORDER BY sequence") suspend fun allJournalSeals(): List<JournalSealEntity>
    @Query("SELECT * FROM ledger_accounts ORDER BY code") suspend fun allLedgerAccounts(): List<LedgerAccountEntity>
    @Query("SELECT * FROM ledger_lines ORDER BY id") suspend fun allLedgerLines(): List<LedgerLineEntity>
    @Query("SELECT * FROM evidence_keys ORDER BY createdAt") suspend fun allEvidenceKeys(): List<EvidenceKeyEntity>
    @Query("SELECT * FROM evidence_keys WHERE id = :id LIMIT 1") suspend fun evidenceKeyById(id: String): EvidenceKeyEntity?
    @Query("SELECT * FROM actor_profiles WHERE id = 1 LIMIT 1") suspend fun actorProfile(): ActorProfileEntity?
    @Query("SELECT COUNT(*) FROM activity_events") suspend fun eventCount(): Long
    @Query("SELECT COUNT(*) FROM journal_seals") suspend fun sealCount(): Long
    @Query("SELECT COUNT(*) FROM activity_events e WHERE NOT EXISTS(SELECT 1 FROM journal_seals s WHERE s.eventId = e.id)") suspend fun unsealedEventCount(): Long
    @Query("SELECT * FROM activity_events WHERE NOT EXISTS(SELECT 1 FROM journal_seals s WHERE s.eventId = activity_events.id) ORDER BY createdAt, id") suspend fun unsealedEvents(): List<ActivityEventEntity>
    @Query("SELECT eventId, COALESCE(SUM(CASE WHEN side = 'DEBIT' THEN amount ELSE 0 END),0) AS debit, COALESCE(SUM(CASE WHEN side = 'CREDIT' THEN amount ELSE 0 END),0) AS credit FROM ledger_lines GROUP BY eventId HAVING debit != credit") suspend fun unbalancedLedgerEvents(): List<LedgerEventBalanceRow>
    @Query("SELECT EXISTS(SELECT 1 FROM activity_events WHERE type = 'REVERSAL' AND relatedEventId = :eventId)") suspend fun isEventReversed(eventId: String): Boolean
    @Query("SELECT * FROM activity_events WHERE type = 'REVERSAL' AND relatedEventId = :eventId ORDER BY createdAt, id LIMIT 1") suspend fun reversalEventForEvent(eventId: String): ActivityEventEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM activity_events WHERE type = 'RESTORE_REVERSAL' AND relatedEventId = :originalEventId)") suspend fun isEventRestored(originalEventId: String): Boolean
    @Query("SELECT r.* FROM recurring_rules r JOIN accounts a ON a.id = r.accountId WHERE r.isPaused = 0 AND a.isActive = 1 AND a.isArchived = 0 AND r.nextEpochDay <= :today AND (:direction IS NULL OR r.direction = :direction) ORDER BY r.nextEpochDay") suspend fun dueRules(today: Long, direction: String?): List<RecurringRuleEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM recurring_occurrences WHERE ruleId = :ruleId AND dueEpochDay = :dueDay)") suspend fun occurrenceExists(ruleId: String, dueDay: Long): Boolean
    @Query("SELECT * FROM accounts ORDER BY createdAt") suspend fun allAccounts(): List<AccountEntity>
    @Query("SELECT * FROM receipts ORDER BY id") suspend fun allReceipts(): List<ReceiptEntity>
    @Query("SELECT r.* FROM receipts r JOIN activity_events e ON e.id = r.eventId WHERE e.accountId = :accountId ORDER BY r.id")
    suspend fun receiptsForAccount(accountId: Long): List<ReceiptEntity>
    @Query("SELECT r.* FROM receipts r JOIN activity_events e ON e.id = r.eventId JOIN accounts a ON a.id = e.accountId WHERE a.sharingMode = 'PRIVATE' ORDER BY r.id")
    suspend fun receiptsForPrivateAccounts(): List<ReceiptEntity>
    @Query("SELECT r.* FROM receipts r JOIN activity_events e ON e.id = r.eventId WHERE r.localPath IS NOT NULL AND EXISTS(SELECT 1 FROM activity_events rv WHERE rv.type = 'REVERSAL' AND rv.relatedEventId = e.id AND rv.createdAt <= :maxCreatedAt)") suspend fun receiptsForReversedEvents(maxCreatedAt: Long): List<ReceiptEntity>
    @Query("UPDATE receipts SET localPath = NULL WHERE id = :id") suspend fun clearReceiptLocalPath(id: Long)
    @Query("SELECT * FROM receipts WHERE evidenceEventId = :eventId OR (evidenceEventId IS NULL AND eventId = :eventId) ORDER BY id") suspend fun receiptsForEvent(eventId: String): List<ReceiptEntity>
    @Query("SELECT * FROM sync_state WHERE id = 1") suspend fun syncState(): SyncStateEntity?
    @Query("UPDATE sync_state SET localGeneration = localGeneration + 1, updatedAt = :updatedAt WHERE id = 1")
    suspend fun markPrivateRecoveryChanged(updatedAt: Long): Int
    @Query("SELECT * FROM categories ORDER BY id") suspend fun allCategories(): List<CategoryEntity>
    @Query("SELECT * FROM categories WHERE accountId IS NULL OR accountId = :accountId ORDER BY id") suspend fun categoriesForAccount(accountId: Long): List<CategoryEntity>
    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1") suspend fun categoryById(id: Long): CategoryEntity?
    @Query("SELECT * FROM portfolios ORDER BY id") suspend fun allPortfolios(): List<PortfolioEntity>
    @Query("SELECT * FROM budget_periods ORDER BY id") suspend fun allPeriods(): List<BudgetPeriodEntity>
    @Query("SELECT * FROM allocations ORDER BY id") suspend fun allAllocations(): List<AllocationEntity>
    @Query("SELECT * FROM portfolio_allocation_templates WHERE portfolioId = :portfolioId ORDER BY id") suspend fun templatesForPortfolio(portfolioId: Long): List<PortfolioAllocationTemplateEntity>
    @Query("SELECT * FROM portfolio_allocation_templates ORDER BY id") suspend fun allAllocationTemplates(): List<PortfolioAllocationTemplateEntity>
    @Query("SELECT * FROM activity_events ORDER BY createdAt") suspend fun allEvents(): List<ActivityEventEntity>
    @Query("SELECT * FROM activity_events WHERE effectiveEpochDay BETWEEN :startDay AND :endDay ORDER BY effectiveEpochDay, createdAt, id") suspend fun eventsBetween(startDay: Long, endDay: Long): List<ActivityEventEntity>
    @Query("SELECT * FROM cash_journal_lines ORDER BY id") suspend fun allCashLines(): List<CashJournalLineEntity>
    @Query("SELECT * FROM budget_journal_lines ORDER BY id") suspend fun allBudgetLines(): List<BudgetJournalLineEntity>
    @Query("SELECT * FROM transaction_splits ORDER BY id") suspend fun allSplits(): List<TransactionSplitEntity>
    @Query("SELECT * FROM transaction_splits ORDER BY id") fun observeSplits(): Flow<List<TransactionSplitEntity>>
    @Query("SELECT * FROM transaction_splits WHERE eventId IN (SELECT id FROM activity_events WHERE accountId = :accountId) ORDER BY id") fun observeSplitsForAccount(accountId: Long): Flow<List<TransactionSplitEntity>>
    @Query("SELECT * FROM audit_snapshots WHERE eventId = :eventId ORDER BY id") suspend fun auditsForEvent(eventId: String): List<AuditSnapshotEntity>
    @Query("SELECT * FROM recurring_rules ORDER BY createdAt") suspend fun allRules(): List<RecurringRuleEntity>
    @Query("SELECT COALESCE(SUM(amount), 0) FROM cash_journal_lines WHERE fundingChannel = :channel") suspend fun cashTotal(channel: String): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE fundingChannel = :channel AND (bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL)") suspend fun budgetAvailableTotal(channel: String): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'ROLLOVER' AND fundingChannel = :channel") suspend fun rolloverBalance(channel: String): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'ROLLOVER' AND fundingChannel = :channel AND accountId = :accountId") suspend fun rolloverBalance(channel: String, accountId: Long): Long
    @Query("SELECT fundingChannel, COALESCE(SUM(amount), 0) AS balance FROM budget_journal_lines WHERE bucket = 'ROLLOVER' GROUP BY fundingChannel") fun observeRolloverByChannel(): Flow<List<ChannelBalanceRow>>
    @Query("SELECT fundingChannel, COALESCE(SUM(amount), 0) AS balance FROM budget_journal_lines WHERE bucket = 'ROLLOVER' AND accountId = :accountId GROUP BY fundingChannel") fun observeRolloverByChannel(accountId: Long): Flow<List<ChannelBalanceRow>>

    @Query("SELECT * FROM portfolios WHERE isArchived = 0 AND accountId = :accountId ORDER BY fundingPriority, createdAt")
    suspend fun portfoliosForAccount(accountId: Long): List<PortfolioEntity>

    @Query("""
        SELECT al.id, al.periodId, p.portfolioId, pf.name AS portfolioName, pf.isArchived AS portfolioArchived,
               al.categoryId, c.name AS categoryName, c.color, al.fundingChannel,
               al.plannedAmount,
               COALESCE(SUM(b.amount), 0) - COALESCE(SUM(CASE WHEN e.type IN ('EXPENSE','AUTOMATION') AND NOT EXISTS(SELECT 1 FROM activity_events rv WHERE rv.type = 'REVERSAL' AND rv.relatedEventId = e.id) AND b.amount < 0 THEN b.amount ELSE 0 END), 0) AS bookedAmount,
               COALESCE(SUM(b.amount), 0) AS availableAmount,
               -COALESCE(SUM(CASE WHEN e.type IN ('EXPENSE','AUTOMATION') AND NOT EXISTS(SELECT 1 FROM activity_events rv WHERE rv.type = 'REVERSAL' AND rv.relatedEventId = e.id) AND b.amount < 0 THEN b.amount ELSE 0 END), 0) AS spentAmount,
               p.status AS periodStatus, p.startEpochDay, p.endEpochDay
        FROM allocations al
        JOIN budget_periods p ON p.id = al.periodId
        JOIN portfolios pf ON pf.id = p.portfolioId
        JOIN categories c ON c.id = al.categoryId
        LEFT JOIN budget_journal_lines b ON b.allocationId = al.id
        LEFT JOIN activity_events e ON e.id = b.eventId
        WHERE pf.accountId = :accountId
        GROUP BY al.id
        ORDER BY p.startEpochDay DESC, c.name
    """)
    suspend fun allocationBalancesForAccount(accountId: Long): List<AllocationBalanceRow>

    @Query("""
        SELECT e.id, e.type, e.title, e.note, e.source, e.effectiveEpochDay, e.createdAt,
               e.relatedEventId,
               COALESCE(e.reversedByEventId, (SELECT rv.id FROM activity_events rv WHERE rv.type = 'REVERSAL' AND rv.relatedEventId = e.id ORDER BY rv.createdAt, rv.id LIMIT 1)) AS reversedByEventId,
               e.accountId,
               COALESCE((SELECT SUM(amount) FROM cash_journal_lines WHERE eventId = e.id), 0) AS cashImpact,
               COALESCE((SELECT SUM(amount) FROM budget_journal_lines WHERE eventId = e.id AND bucket = 'VAULT'), 0) AS vaultImpact,
               COALESCE((SELECT SUM(amount) FROM budget_journal_lines WHERE eventId = e.id AND allocationId IS NOT NULL), 0) AS budgetImpact,
               COALESCE((SELECT SUM(amount) FROM ledger_lines WHERE eventId = e.id AND side = 'DEBIT'), 0) AS ledgerDebit,
               COALESCE((SELECT SUM(amount) FROM ledger_lines WHERE eventId = e.id AND side = 'CREDIT'), 0) AS ledgerCredit,
               CASE
                   WHEN EXISTS(SELECT 1 FROM activity_events rv WHERE rv.type = 'REVERSAL' AND rv.relatedEventId = e.id) THEN 'Reversed'
                   WHEN e.type = 'CORRECTION' THEN 'Corrected'
                   WHEN NOT EXISTS(SELECT 1 FROM journal_seals s WHERE s.eventId = e.id) THEN 'Integrity problem'
                   WHEN EXISTS(SELECT 1 FROM ledger_lines l WHERE l.eventId = e.id AND l.legacyBackfill = 1)
                     OR EXISTS(SELECT 1 FROM journal_seals s WHERE s.eventId = e.id AND s.legacyBackfill = 1) THEN 'Legacy'
                   ELSE 'Valid'
               END AS auditStatus,
               COALESCE((SELECT actor FROM team_event_proofs p WHERE p.eventId=e.id),
                        (SELECT actor FROM journal_seals s WHERE s.eventId=e.id),'Tidak tersedia') AS actor,
               COALESCE((SELECT deviceId FROM team_event_proofs p WHERE p.eventId=e.id),
                        (SELECT deviceId FROM journal_seals s WHERE s.eventId=e.id),'Tidak tersedia') AS deviceId
        FROM activity_events e
        WHERE e.accountId = :accountId
        ORDER BY e.effectiveEpochDay DESC, e.createdAt DESC
    """)
    suspend fun activitiesForAccount(accountId: Long): List<ActivityRow>

    @Query("SELECT * FROM cash_journal_lines WHERE accountId = :accountId ORDER BY id")
    suspend fun cashJournalLinesForAccount(accountId: Long): List<CashJournalLineEntity>

    @Query("SELECT * FROM budget_journal_lines WHERE accountId = :accountId ORDER BY id")
    suspend fun budgetJournalLinesForAccount(accountId: Long): List<BudgetJournalLineEntity>

    @Query("SELECT * FROM journal_seals WHERE eventId IN (SELECT id FROM activity_events WHERE accountId = :accountId) ORDER BY sequence")
    suspend fun journalSealsForAccount(accountId: Long): List<JournalSealEntity>

    @Query("SELECT s.* FROM journal_seals s WHERE s.eventId IN (SELECT id FROM activity_events WHERE accountId = :accountId) ORDER BY s.sequence")
    suspend fun sealsForAccount(accountId: Long): List<JournalSealEntity>

    @Query("SELECT * FROM budget_periods WHERE portfolioId IN (:portfolioIds) ORDER BY startEpochDay DESC")
    suspend fun periodsForPortfolios(portfolioIds: List<Long>): List<BudgetPeriodEntity>
}

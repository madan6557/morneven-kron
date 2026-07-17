package com.morneven.kron.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface KronDao {
    @Insert suspend fun insertAccount(value: AccountEntity): Long
    @Insert suspend fun insertCategory(value: CategoryEntity): Long
    @Insert suspend fun insertPortfolio(value: PortfolioEntity): Long
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
    @Insert suspend fun insertReceipt(value: ReceiptEntity)

    @Update suspend fun updatePeriod(value: BudgetPeriodEntity)
    @Update suspend fun updateEvent(value: ActivityEventEntity)
    @Update suspend fun updateRule(value: RecurringRuleEntity)

    @Query("SELECT COUNT(*) FROM accounts") suspend fun accountCount(): Int
    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY createdAt") fun observeAccounts(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY direction, name") fun observeCategories(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM portfolios ORDER BY fundingPriority, createdAt") fun observePortfolios(): Flow<List<PortfolioEntity>>
    @Query("SELECT * FROM budget_periods ORDER BY startEpochDay DESC") fun observePeriods(): Flow<List<BudgetPeriodEntity>>
    @Query("SELECT * FROM recurring_rules ORDER BY nextEpochDay, createdAt") fun observeRules(): Flow<List<RecurringRuleEntity>>

    @Query("""
        SELECT a.id, a.name, a.type, a.fundingChannel, COALESCE(SUM(c.amount), 0) AS balance
        FROM accounts a
        LEFT JOIN cash_journal_lines c ON c.accountId = a.id
        WHERE a.isArchived = 0
        GROUP BY a.id
        ORDER BY a.createdAt
    """)
    fun observeAccountBalances(): Flow<List<AccountBalanceRow>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'VAULT'")
    fun observeVaultBalance(): Flow<Long>

    @Query("SELECT fundingChannel, COALESCE(SUM(amount), 0) AS balance FROM budget_journal_lines WHERE bucket = 'VAULT' GROUP BY fundingChannel")
    fun observeVaultByChannel(): Flow<List<ChannelBalanceRow>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'UNALLOCATED'")
    fun observeUnallocatedBalance(): Flow<Long>

    @Query("SELECT fundingChannel, COALESCE(SUM(amount), 0) AS balance FROM budget_journal_lines WHERE bucket = 'UNALLOCATED' GROUP BY fundingChannel")
    fun observeUnallocatedByChannel(): Flow<List<ChannelBalanceRow>>

    @Query("""
        SELECT al.id, al.periodId, p.portfolioId, pf.name AS portfolioName,
               al.categoryId, c.name AS categoryName, c.color, al.fundingChannel,
               al.plannedAmount,
               COALESCE(SUM(b.amount), 0) - COALESCE(SUM(CASE WHEN e.type IN ('EXPENSE','AUTOMATION') AND e.reversedByEventId IS NULL AND b.amount < 0 THEN b.amount ELSE 0 END), 0) AS bookedAmount,
               COALESCE(SUM(b.amount), 0) AS availableAmount,
               -COALESCE(SUM(CASE WHEN e.type IN ('EXPENSE','AUTOMATION') AND e.reversedByEventId IS NULL AND b.amount < 0 THEN b.amount ELSE 0 END), 0) AS spentAmount,
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
        SELECT e.id, e.type, e.title, e.note, e.source, e.effectiveEpochDay, e.createdAt,
               e.relatedEventId, e.reversedByEventId,
               COALESCE((SELECT SUM(amount) FROM cash_journal_lines WHERE eventId = e.id), 0) AS cashImpact,
               COALESCE((SELECT SUM(amount) FROM budget_journal_lines WHERE eventId = e.id AND bucket = 'VAULT'), 0) AS vaultImpact,
               COALESCE((SELECT SUM(amount) FROM budget_journal_lines WHERE eventId = e.id AND allocationId IS NOT NULL), 0) AS budgetImpact
        FROM activity_events e
        ORDER BY e.effectiveEpochDay DESC, e.createdAt DESC
    """)
    fun observeActivities(): Flow<List<ActivityRow>>

    @Query("""
        SELECT
            COALESCE(SUM(CASE WHEN e.type IN ('INCOME','OPENING_BALANCE','AUTOMATION') AND c.amount > 0 THEN c.amount ELSE 0 END), 0) AS income,
            -COALESCE(SUM(CASE WHEN e.type IN ('EXPENSE','AUTOMATION') AND c.amount < 0 THEN c.amount ELSE 0 END), 0) AS expense
        FROM activity_events e
        JOIN cash_journal_lines c ON c.eventId = e.id
        WHERE e.effectiveEpochDay BETWEEN :startDay AND :endDay AND e.reversedByEventId IS NULL
    """)
    fun observeCashflow(startDay: Long, endDay: Long): Flow<CashflowRow>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM cash_journal_lines") suspend fun cashTotal(): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket IN ('VAULT','UNALLOCATED','ROLLOVER') OR allocationId IS NOT NULL") suspend fun budgetAvailableTotal(): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE eventId = :eventId") suspend fun budgetEventTotal(eventId: String): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'VAULT'") suspend fun vaultBalance(): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'VAULT' AND fundingChannel = :channel") suspend fun vaultBalance(channel: String): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'UNALLOCATED' AND fundingChannel = :channel") suspend fun unallocatedBalance(channel: String): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE allocationId = :allocationId") suspend fun allocationAvailable(allocationId: Long): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE allocationId = :allocationId AND eventId IN (SELECT id FROM activity_events WHERE type IN ('PORTFOLIO_BOOKING','REALLOCATION','OVERBUDGET_COVERAGE','RELEASE','ROLLOVER'))") suspend fun allocationBooked(allocationId: Long): Long
    @Query("SELECT * FROM allocations WHERE id = :id") suspend fun allocationById(id: Long): AllocationEntity?
    @Query("SELECT * FROM allocations WHERE periodId = :periodId AND categoryId = :categoryId AND fundingChannel = :channel LIMIT 1") suspend fun allocationFor(periodId: Long, categoryId: Long, channel: String): AllocationEntity?
    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun accountById(id: Long): AccountEntity?
    @Query("SELECT COALESCE(SUM(amount), 0) FROM cash_journal_lines WHERE accountId = :accountId") suspend fun accountBalance(accountId: Long): Long
    @Query("SELECT * FROM budget_periods WHERE id = :id") suspend fun periodById(id: Long): BudgetPeriodEntity?
    @Query("SELECT * FROM budget_periods WHERE portfolioId = :portfolioId ORDER BY startEpochDay") suspend fun periodsForPortfolio(portfolioId: Long): List<BudgetPeriodEntity>
    @Query("SELECT id FROM allocations WHERE periodId = :periodId") suspend fun allocationIdsForPeriod(periodId: Long): List<Long>
    @Query("SELECT * FROM allocations WHERE periodId = :periodId ORDER BY id") suspend fun allocationsForPeriod(periodId: Long): List<AllocationEntity>
    @Query("SELECT * FROM activity_events WHERE id = :id") suspend fun eventById(id: String): ActivityEventEntity?
    @Query("SELECT * FROM cash_journal_lines WHERE eventId = :eventId") suspend fun cashLinesForEvent(eventId: String): List<CashJournalLineEntity>
    @Query("SELECT * FROM budget_journal_lines WHERE eventId = :eventId") suspend fun budgetLinesForEvent(eventId: String): List<BudgetJournalLineEntity>
    @Query("SELECT * FROM transaction_splits WHERE eventId = :eventId") suspend fun splitsForEvent(eventId: String): List<TransactionSplitEntity>
    @Query("SELECT * FROM recurring_rules WHERE isPaused = 0 AND nextEpochDay <= :today AND (:direction IS NULL OR direction = :direction) ORDER BY nextEpochDay") suspend fun dueRules(today: Long, direction: String?): List<RecurringRuleEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM recurring_occurrences WHERE ruleId = :ruleId AND dueEpochDay = :dueDay)") suspend fun occurrenceExists(ruleId: String, dueDay: Long): Boolean
    @Query("SELECT * FROM accounts ORDER BY createdAt") suspend fun allAccounts(): List<AccountEntity>
    @Query("SELECT * FROM categories ORDER BY id") suspend fun allCategories(): List<CategoryEntity>
    @Query("SELECT * FROM portfolios ORDER BY id") suspend fun allPortfolios(): List<PortfolioEntity>
    @Query("SELECT * FROM budget_periods ORDER BY id") suspend fun allPeriods(): List<BudgetPeriodEntity>
    @Query("SELECT * FROM allocations ORDER BY id") suspend fun allAllocations(): List<AllocationEntity>
    @Query("SELECT * FROM portfolio_allocation_templates WHERE portfolioId = :portfolioId ORDER BY id") suspend fun templatesForPortfolio(portfolioId: Long): List<PortfolioAllocationTemplateEntity>
    @Query("SELECT * FROM portfolio_allocation_templates ORDER BY id") suspend fun allAllocationTemplates(): List<PortfolioAllocationTemplateEntity>
    @Query("SELECT * FROM activity_events ORDER BY createdAt") suspend fun allEvents(): List<ActivityEventEntity>
    @Query("SELECT * FROM cash_journal_lines ORDER BY id") suspend fun allCashLines(): List<CashJournalLineEntity>
    @Query("SELECT * FROM budget_journal_lines ORDER BY id") suspend fun allBudgetLines(): List<BudgetJournalLineEntity>
    @Query("SELECT * FROM transaction_splits ORDER BY id") suspend fun allSplits(): List<TransactionSplitEntity>
    @Query("SELECT * FROM audit_snapshots WHERE eventId = :eventId ORDER BY id") suspend fun auditsForEvent(eventId: String): List<AuditSnapshotEntity>
    @Query("SELECT * FROM recurring_rules ORDER BY createdAt") suspend fun allRules(): List<RecurringRuleEntity>
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM cash_journal_lines c JOIN accounts a ON a.id = c.accountId WHERE a.fundingChannel = :channel") suspend fun cashTotal(channel: String): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE fundingChannel = :channel AND (bucket IN ('VAULT','UNALLOCATED','ROLLOVER') OR allocationId IS NOT NULL)") suspend fun budgetAvailableTotal(channel: String): Long
    @Query("SELECT COALESCE(SUM(amount), 0) FROM budget_journal_lines WHERE bucket = 'ROLLOVER' AND fundingChannel = :channel") suspend fun rolloverBalance(channel: String): Long
    @Query("SELECT fundingChannel, COALESCE(SUM(amount), 0) AS balance FROM budget_journal_lines WHERE bucket = 'ROLLOVER' GROUP BY fundingChannel") fun observeRolloverByChannel(): Flow<List<ChannelBalanceRow>>
}

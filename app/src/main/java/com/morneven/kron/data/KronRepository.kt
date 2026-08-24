package com.morneven.kron.data

import androidx.room.withTransaction
import com.morneven.kron.security.SnapshotOperationLock
import com.morneven.kron.audit.LedgerPostingEngine
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import com.morneven.kron.sync.SyncStateBridge
import com.morneven.kron.data.AccountSharingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class ExpenseSplitInput(
    val categoryId: Long?,
    val allocationId: Long?,
    val amount: Long,
)

data class AllocationDraft(
    val categoryId: Long,
    val fundingChannel: String,
    val plannedAmount: Long,
    val categoryName: String? = null,
)

data class CategoryCompositionDraft(
    val categoryId: Long,
    val plannedAmount: Long,
    val cashPercentage: Int,
)

class LedgerInvariantException(message: String) : IllegalStateException(message)

object ScheduleCalculator {
    fun next(current: LocalDate, cadence: String, anchorMonth: Int, anchorDay: Int, intervalCount: Int = 1): LocalDate {
        require(cadence == "MONTHLY" || cadence == "YEARLY") { "Cadence tidak valid" }
        require(intervalCount > 0) { "Interval harus minimal 1" }
        return when (cadence) {
            "YEARLY" -> safeDate(current.year + intervalCount, anchorMonth, anchorDay)
            else -> {
                val month = YearMonth.from(current).plusMonths(intervalCount.toLong())
                LocalDate.of(month.year, month.month, min(anchorDay, month.lengthOfMonth()))
            }
        }
    }

    fun firstAfter(current: LocalDate, cadence: String, intervalCount: Int): LocalDate =
        next(current, cadence, current.monthValue, current.dayOfMonth, intervalCount)

    fun firstAfter(start: LocalDate, after: LocalDate, cadence: String, intervalCount: Int): LocalDate {
        require(intervalCount > 0) { "Interval harus minimal 1" }
        if (after.isBefore(start)) return start
        var candidate = start
        var guard = 0
        while (!candidate.isAfter(after)) {
            candidate = next(candidate, cadence, start.monthValue, start.dayOfMonth, intervalCount)
            guard++
            require(guard < 100_000) { "Jadwal terlalu panjang untuk dihitung" }
        }
        return candidate
    }

    fun firstOnOrAfter(start: LocalDate): LocalDate = start

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate {
        val yearMonth = YearMonth.of(year, month)
        return LocalDate.of(year, month, min(day, yearMonth.lengthOfMonth()))
    }
}

private fun AccountEntity.bumpRevision() = copy(revision = revision + 1, updatedAt = System.currentTimeMillis())
private fun PortfolioEntity.bumpRevision() = copy(revision = revision + 1, updatedAt = System.currentTimeMillis())
private fun BudgetPeriodEntity.bumpRevision() = copy(revision = revision + 1, updatedAt = System.currentTimeMillis())
private fun AllocationEntity.bumpRevision() = copy(revision = revision + 1, updatedAt = System.currentTimeMillis())
private fun RecurringRuleEntity.bumpRevision() = copy(revision = revision + 1, updatedAt = System.currentTimeMillis())
private fun DebtEntity.bumpRevision() = copy(revision = revision + 1, updatedAt = System.currentTimeMillis())

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class KronRepository private constructor(
    private val databaseProvider: () -> KronDatabase,
    private val databaseEpoch: Flow<Long>,
    private val ledgerPostingEngine: LedgerPostingEngine,
    private val teamAccessGuard: TeamAccessGuard,
) {
    @Inject
    constructor(
        databaseRuntime: com.morneven.kron.security.DatabaseRuntime,
        ledgerPostingEngine: LedgerPostingEngine,
        teamAccessGuard: TeamAccessGuard,
    ) : this(databaseRuntime::current, databaseRuntime.epoch, ledgerPostingEngine, teamAccessGuard)

    internal constructor(database: KronDatabase, ledgerPostingEngine: LedgerPostingEngine) :
        this({ database }, flowOf(0L), ledgerPostingEngine, TeamAccessGuard(database))

    private val database get() = databaseProvider()
    private val dao get() = database.kronDao()
    private val snapshotOperationLock = SnapshotOperationLock()
    private fun <T> observe(block: (KronDao) -> Flow<T>): Flow<T> =
        databaseEpoch.flatMapLatest { block(database.kronDao()) }

    val accounts = observe(KronDao::observeAccounts)
    val archivedAccounts = observe(KronDao::observeArchivedAccounts)
    val recoveredTeamAccounts = observe(KronDao::observeRecoveredTeamAccounts)
    val accountBalances = observe(KronDao::observeAccountBalances)
    val syncState = SyncStateBridge.syncState

    init {
        runBlocking {
            SyncStateBridge.emit(dao.syncState() ?: SyncStateEntity(
                datasetId = java.util.UUID.randomUUID().toString(),
                deviceId = java.util.UUID.randomUUID().toString(),
                lastSyncedGeneration = -1,
            ))
        }
    }

    private val activeAccountFlow: Flow<Long?> = observe(KronDao::observeActiveAccount)
        .map { it?.id }
        .distinctUntilChanged()

    val categories = activeAccountFlow.flatMapLatest { accountId ->
        accountId?.let { database.kronDao().observeCategoriesForAccount(it) } ?: flowOf(emptyList())
    }
    val rules = activeAccountFlow.flatMapLatest { it?.let { id -> database.kronDao().observeRulesForAccount(id) } ?: flowOf(emptyList()) }
    val portfolios = activeAccountFlow.flatMapLatest { it?.let { id -> database.kronDao().observePortfoliosForAccount(id) } ?: flowOf(emptyList()) }
    val archivedPortfolios = activeAccountFlow.flatMapLatest { it?.let { id -> database.kronDao().observeArchivedPortfoliosForAccount(id) } ?: flowOf(emptyList()) }
    val periods = activeAccountFlow.flatMapLatest { it?.let { id -> database.kronDao().observePeriodsForAccount(id) } ?: flowOf(emptyList()) }
    val allocations = activeAccountFlow.flatMapLatest { it?.let { id -> database.kronDao().observeAllocationBalancesForAccount(id) } ?: flowOf(emptyList()) }
    val activities = activeAccountFlow.flatMapLatest { it?.let { id -> database.kronDao().observeActivitiesForAccount(id) } ?: flowOf(emptyList()) }
    val eventChannels = activeAccountFlow.flatMapLatest { it?.let { id -> database.kronDao().observeEventChannelsForAccount(id) } ?: flowOf(emptyList()) }
    val receipts = activeAccountFlow.flatMapLatest { it?.let { id -> database.kronDao().observeReceiptsForAccount(id) } ?: flowOf(emptyList()) }
    val splits = activeAccountFlow.flatMapLatest { it?.let { id -> database.kronDao().observeSplitsForAccount(id) } ?: flowOf(emptyList()) }
    val teamWorkspace = activeAccountFlow.flatMapLatest { it?.let { id -> database.kronDao().observeTeamWorkspace(id) } ?: flowOf(null) }
    val teamMembers = activeAccountFlow.flatMapLatest { it?.let { id -> database.kronDao().observeTeamMembers(id) } ?: flowOf(emptyList()) }
    val debts = activeAccountFlow.flatMapLatest { it?.let { id -> database.kronDao().observeDebtsForAccount(id) } ?: flowOf(emptyList()) }
    val debtEntries = activeAccountFlow.flatMapLatest { it?.let { id -> database.kronDao().observeDebtEntriesForAccount(id) } ?: flowOf(emptyList()) }

    val vault = activeAccountFlow.flatMapLatest { accountId ->
        accountId?.let { database.kronDao().observeVaultBalance(it) } ?: flowOf(0L)
    }

    val vaultByChannel = activeAccountFlow.flatMapLatest { accountId ->
        accountId?.let { database.kronDao().observeVaultByChannel(it) } ?: flowOf(emptyList())
    }

    val unallocated = activeAccountFlow.flatMapLatest { accountId ->
        accountId?.let { database.kronDao().observeUnallocatedBalance(it) } ?: flowOf(0L)
    }

    val unallocatedByChannel = activeAccountFlow.flatMapLatest { accountId ->
        accountId?.let { database.kronDao().observeUnallocatedByChannel(it) } ?: flowOf(emptyList())
    }

    val rolloverByChannel = activeAccountFlow.flatMapLatest { accountId ->
        accountId?.let { database.kronDao().observeRolloverByChannel(it) } ?: flowOf(emptyList())
    }

    fun cashflow(start: LocalDate, end: LocalDate) = activeAccountFlow.flatMapLatest { accountId ->
        accountId?.let { database.kronDao().observeCashflow(start.toEpochDay(), end.toEpochDay(), it) }
            ?: flowOf(CashflowRow(0, 0))
    }

    private suspend fun activeAccountId(): Long = requireNotNull(dao.activeAccount()) { "Tidak ada akun aktif" }.id.also {
        teamAccessGuard.require(it, TeamCapability.WRITE)
    }

    private suspend fun requireActiveAccount(accountId: Long): Long = activeAccountId().also {
        require(it == accountId) { "Data bukan milik akun aktif" }
    }

    suspend fun isFirstInstall(): Boolean = dao.accountCount() == 0

    suspend fun seedIfNeeded() = database.withTransaction {
        ledgerPostingEngine.validateAll()
        if (dao.syncState() == null) {
            dao.upsertSyncState(
                SyncStateEntity(
                    datasetId = UUID.randomUUID().toString(),
                    deviceId = UUID.randomUUID().toString(),
                ),
            )
        }
        if (dao.accountCount() > 0) return@withTransaction
        dao.insertAccount(AccountEntity(name = "Akun Utama", isActive = true))
        listOf(
            CategoryEntity(name = "Gaji", direction = TransactionDirection.INCOME, color = 0xFF4CAF7DL, icon = "payments"),
            CategoryEntity(name = "Pendapatan Lain", direction = TransactionDirection.INCOME, color = 0xFF65A9EFL, icon = "add_card"),
            CategoryEntity(name = "Belanja", direction = TransactionDirection.EXPENSE, color = 0xFFE3943BL, icon = "shopping_bag"),
            CategoryEntity(name = "Makanan", direction = TransactionDirection.EXPENSE, color = 0xFFD0A72DL, icon = "restaurant"),
            CategoryEntity(name = "Transportasi", direction = TransactionDirection.EXPENSE, color = 0xFF7097D1L, icon = "directions_car"),
            CategoryEntity(name = "Tagihan", direction = TransactionDirection.EXPENSE, color = 0xFFB65A68L, icon = "receipt"),
            CategoryEntity(name = "Kesehatan", direction = TransactionDirection.EXPENSE, color = 0xFF58A889L, icon = "health"),
            CategoryEntity(name = "Hiburan", direction = TransactionDirection.EXPENSE, color = 0xFF8F6CC1L, icon = "movie"),
            CategoryEntity(name = "Lainnya", direction = TransactionDirection.EXPENSE, color = 0xFF8A8082L, icon = "category"),
        ).forEach { dao.insertCategory(it) }
    }

    suspend fun addAccount(name: String, openingCash: Long, openingEBudget: Long) = database.withTransaction {
        require(name.isNotBlank()) { "Nama akun wajib diisi" }
        require(openingCash >= 0 && openingEBudget >= 0) { "Saldo awal tidak boleh negatif" }
        val accountId = dao.insertAccount(AccountEntity(name = name.trim(), isActive = dao.activeAccount() == null))
        if (openingCash > 0) {
            postIncomeInternal(
                accountId = accountId,
                fundingChannel = FundingChannel.CASH,
                amount = openingCash,
                categoryId = null,
                title = "Saldo awal Cash $name",
                note = "Dana awal Cash masuk ke Main Vault",
                effectiveDate = LocalDate.now(),
                eventType = LedgerType.OPENING_BALANCE,
                source = "USER",
            )
        }
        if (openingEBudget > 0) {
            postIncomeInternal(
                accountId = accountId,
                fundingChannel = FundingChannel.EBUDGET,
                amount = openingEBudget,
                categoryId = null,
                title = "Saldo awal eBudget $name",
                note = "Dana awal eBudget masuk ke Main Vault",
                effectiveDate = LocalDate.now(),
                eventType = LedgerType.OPENING_BALANCE,
                source = "USER",
            )
        }
        assertInvariant()
        accountId
    }

    suspend fun updateAccount(accountId: Long, name: String) = database.withTransaction {
        require(name.isNotBlank()) { "Nama akun wajib diisi" }
        teamAccessGuard.require(accountId, TeamCapability.WRITE)
        val account = requireNotNull(dao.accountById(accountId)) { "Akun tidak ditemukan" }
        val eventId = UUID.randomUUID().toString()
        dao.updateAccount(account.copy(name = name.trim()).bumpRevision())
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.SYSTEM, "Akun diperbarui", "${account.name} → ${name.trim()}", "USER", LocalDate.now().toEpochDay(), accountId = accountId))
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = "Perubahan nama akun", beforeJson = "{\"name\":\"${account.name}\"}", afterJson = "{\"name\":\"${name.trim()}\"}"))
        assertInvariant()
    }

    suspend fun activateAccount(accountId: Long) = snapshotOperationLock.withLock {
        database.withTransaction {
            val account = requireNotNull(dao.accountById(accountId)) { "Akun tidak ditemukan" }
            teamAccessGuard.require(accountId, TeamCapability.READ)
            require(!account.isArchived) { "Akun sudah diarsipkan" }
            if (account.isActive) return@withTransaction
            // Account selection is UI state, not a financial event. Keep this
            // transaction limited to the active flag so it remains safe while
            // sync guards transition between states.
            dao.activateOnly(accountId)
        }
    }

    suspend fun auditsForEvent(eventId: String): List<AuditSnapshotEntity> = dao.auditsForEvent(eventId)

    suspend fun archiveAccount(accountId: Long, reason: String) = database.withTransaction {
        require(reason.isNotBlank()) { "Alasan wajib diisi" }
        teamAccessGuard.require(accountId, TeamCapability.WRITE)
        val account = requireNotNull(dao.accountById(accountId)) { "Akun tidak ditemukan" }
        require(!account.isArchived) { "Akun sudah diarsipkan" }
        require(account.sharingMode == AccountSharingMode.PRIVATE) {
            "Kembalikan Team menjadi Private sebelum menghapus akun"
        }
        require(!account.isActive) { "Aktifkan akun lain sebelum mengarsipkan akun ini" }
        val eventId = UUID.randomUUID().toString()
        val archivedAt = System.currentTimeMillis()
        dao.updateAccount(account.copy(isArchived = true, archivedAt = archivedAt).bumpRevision())
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.ARCHIVE, "Akun diarsipkan", reason, "USER", LocalDate.now().toEpochDay(), accountId = accountId))
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = reason, beforeJson = "{\"accountId\":$accountId,\"archived\":false}", afterJson = "{\"accountId\":$accountId,\"archived\":true,\"archivedAt\":$archivedAt}"))
        assertInvariant()
    }

    suspend fun restoreAccount(accountId: Long, reason: String) = database.withTransaction {
        require(reason.isNotBlank()) { "Alasan wajib diisi" }
        teamAccessGuard.require(accountId, TeamCapability.RESTORE)
        val account = requireNotNull(dao.accountById(accountId)) { "Akun tidak ditemukan" }
        require(account.isArchived) { "Akun tidak berada di arsip" }
        val eventId = UUID.randomUUID().toString()
        dao.updateAccount(account.copy(isArchived = false, isActive = false, archivedAt = null).bumpRevision())
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.RESTORE, "Akun dipulihkan", reason, "USER", LocalDate.now().toEpochDay(), accountId = accountId))
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = reason, beforeJson = "{\"accountId\":$accountId,\"archived\":true}", afterJson = "{\"accountId\":$accountId,\"archived\":false,\"active\":false}"))
        assertInvariant()
    }

    suspend fun teamWorkspaceFor(accountId: Long): TeamWorkspaceEntity? = dao.teamWorkspace(accountId)

    suspend fun addIncome(
        accountId: Long,
        fundingChannel: String,
        amount: Long,
        categoryId: Long?,
        title: String,
        note: String,
        effectiveDate: LocalDate = LocalDate.now(),
        targetAllocationId: Long? = null,
    ) = database.withTransaction {
        val eventId = postIncomeInternal(accountId, fundingChannel, amount, categoryId, title, note, effectiveDate, LedgerType.INCOME, "USER", targetAllocationId)
        assertInvariant()
        eventId
    }

    private suspend fun postIncomeInternal(
        accountId: Long,
        fundingChannel: String,
        amount: Long,
        categoryId: Long?,
        title: String,
        note: String,
        effectiveDate: LocalDate,
        eventType: String,
        source: String,
        targetAllocationId: Long? = null,
    ): String {
        require(amount > 0) { "Nominal harus lebih dari nol" }
        require(fundingChannel in setOf(FundingChannel.CASH, FundingChannel.EBUDGET)) { "Kanal dana tidak valid" }
        teamAccessGuard.require(accountId, TeamCapability.WRITE)
        val account = requireNotNull(dao.accountById(accountId))
        require(account.isActive || eventType == LedgerType.OPENING_BALANCE) { "Pilih akun ini sebagai akun aktif terlebih dahulu" }
        targetAllocationId?.let { allocationId ->
            require(dao.portfolioForAllocation(allocationId)?.accountId == accountId) {
                "Tujuan budget bukan milik akun aktif"
            }
        }
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(
            id = eventId,
            type = eventType,
            title = title.ifBlank { "Pemasukan" },
            note = note,
            source = source,
            effectiveEpochDay = effectiveDate.toEpochDay(),
            targetAllocationId = targetAllocationId,
            accountId = accountId,
        ))
        dao.insertCashLines(listOf(CashJournalLineEntity(eventId = eventId, accountId = accountId, fundingChannel = fundingChannel, amount = amount)))
        dao.insertBudgetLines(listOf(
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = fundingChannel, amount = amount, accountId = accountId),
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.EXTERNAL, fundingChannel = fundingChannel, amount = -amount, accountId = accountId),
        ))
        dao.insertSplits(listOf(TransactionSplitEntity(eventId = eventId, categoryId = categoryId, allocationId = null, amount = amount)))
        dao.insertAudit(AuditSnapshotEntity(
            eventId = eventId,
            reason = "Pemasukan menambah saldo akun dan Main Vault",
            beforeJson = "{}",
            afterJson = "{\"accountDelta\":$amount,\"vaultDelta\":$amount}",
        ))
        return eventId
    }

    suspend fun addExpense(
        accountId: Long,
        fundingChannel: String,
        amount: Long,
        splits: List<ExpenseSplitInput>,
        title: String,
        note: String,
        unexpected: Boolean = false,
        effectiveDate: LocalDate = LocalDate.now(),
    ) = database.withTransaction {
        val eventId = postExpenseInternal(accountId, fundingChannel, amount, splits, title, note, effectiveDate, if (unexpected) LedgerType.UNEXPECTED_EXPENSE else LedgerType.EXPENSE, "USER", unexpected)
        assertInvariant()
        eventId
    }

    private suspend fun postExpenseInternal(
        accountId: Long,
        fundingChannel: String,
        amount: Long,
        splits: List<ExpenseSplitInput>,
        title: String,
        note: String,
        effectiveDate: LocalDate,
        eventType: String,
        source: String,
        unexpected: Boolean = false,
        reduceVault: Boolean = false,
    ): String {
        require(amount > 0) { "Nominal harus lebih dari nol" }
        require(splits.isNotEmpty() && splits.all { it.amount > 0 } && splits.sumOf { it.amount } == amount) {
            "Total split harus sama dengan nominal transaksi"
        }
        require(fundingChannel in setOf(FundingChannel.CASH, FundingChannel.EBUDGET)) { "Kanal dana tidak valid" }
        teamAccessGuard.require(accountId, TeamCapability.WRITE)
        val account = requireNotNull(dao.accountById(accountId))
        require(account.isActive) { "Pilih akun ini sebagai akun aktif terlebih dahulu" }
        require(dao.accountBalance(accountId, fundingChannel) >= amount) { "Saldo ${if (fundingChannel == FundingChannel.CASH) "Cash" else "eBudget"} tidak mencukupi" }
        val effectiveSplits = splits.map { split ->
            val allocation = split.allocationId?.let { dao.allocationById(it) }
            val period = allocation?.let { dao.periodById(it.periodId) }
            if (allocation != null && period?.status in setOf(PeriodStatus.ACTIVE, PeriodStatus.RESOLUTION_REQUIRED)) {
                require(dao.portfolioForAllocation(allocation.id)?.accountId == accountId) {
                    "Kategori budget bukan milik akun aktif"
                }
                split
            } else {
                split.copy(allocationId = null)
            }
        }
        require(effectiveSplits.all { split -> split.allocationId == null || dao.allocationById(split.allocationId)?.fundingChannel == fundingChannel }) {
            "Kanal budget harus sama dengan kanal akun pembayaran"
        }
        val unallocatedAmount = effectiveSplits.filter { it.allocationId == null }.sumOf { it.amount }
        val effectiveEventType = if (eventType == LedgerType.EXPENSE && !unexpected && unallocatedAmount > 0L) {
            LedgerType.UNEXPECTED_EXPENSE
        } else {
            eventType
        }
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(
            id = eventId,
            type = effectiveEventType,
            title = title.ifBlank { "Pengeluaran" },
            note = note,
            source = source,
            effectiveEpochDay = effectiveDate.toEpochDay(),
            accountId = accountId,
        ))
        dao.insertCashLines(listOf(CashJournalLineEntity(eventId = eventId, accountId = accountId, fundingChannel = fundingChannel, amount = -amount)))
        val budgetLines = if (unexpected || reduceVault) {
            listOf(
                BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = fundingChannel, amount = -amount, accountId = accountId),
                BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.EXTERNAL, fundingChannel = fundingChannel, amount = amount, accountId = accountId),
            )
        } else {
            effectiveSplits.filter { it.allocationId != null }.map { split ->
                BudgetJournalLineEntity(eventId = eventId, allocationId = split.allocationId, fundingChannel = fundingChannel, amount = -split.amount, accountId = accountId)
            } + listOfNotNull(
                if (unallocatedAmount > 0L) {
                    BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = fundingChannel, amount = -unallocatedAmount, accountId = accountId)
                } else null,
                BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.EXTERNAL, fundingChannel = fundingChannel, amount = amount, accountId = accountId),
            )
        }
        dao.insertBudgetLines(budgetLines)
        dao.insertSplits(effectiveSplits.map {
            TransactionSplitEntity(eventId = eventId, categoryId = it.categoryId, allocationId = it.allocationId, amount = it.amount)
        })
        val affectedPeriods = effectiveSplits.mapNotNull { it.allocationId }
            .mapNotNull { dao.allocationById(it)?.periodId }
            .distinct()
        for (periodId in affectedPeriods) refreshPeriodStatus(periodId)
        dao.insertAudit(AuditSnapshotEntity(
            eventId = eventId,
            reason = when {
                reduceVault -> "Pembayaran hutang mengurangi Cash/eBudget dan Vault"
                unexpected || unallocatedAmount > 0L -> "Pengeluaran tak terduga mengurangi akun dan Vault"
                else -> "Pengeluaran mengurangi akun dan alokasi budget"
            },
            beforeJson = "{}",
            afterJson = "{\"accountDelta\":${-amount},\"vaultDelta\":${-unallocatedAmount},\"splitCount\":${splits.size}}",
        ))
        return eventId
    }

    suspend fun createDebt(
        accountId: Long,
        role: String,
        fundingSource: String,
        counterparty: String,
        title: String,
        principal: Long,
        interestRateBps: Int,
        interestIntervalMonths: Int,
        interestIntervalUnit: String,
        startDate: LocalDate,
        dueDate: LocalDate?,
        note: String,
    ): String = database.withTransaction {
        requireActiveAccount(accountId)
        require(role in setOf(DebtRole.DEBTOR, DebtRole.CREDITOR)) { "Tipe hutang tidak valid" }
        require(fundingSource in setOf(DebtFundingSource.CASH, DebtFundingSource.EBUDGET, DebtFundingSource.EXTERNAL)) { "Sumber dana hutang tidak valid" }
        require(counterparty.isNotBlank()) { "Nama pihak wajib diisi" }
        require(title.isNotBlank()) { "Judul hutang wajib diisi" }
        require(principal > 0L) { "Pokok hutang harus lebih dari nol" }
        require(interestRateBps in 0..100_000) { "Bunga tidak valid" }
        require(interestIntervalMonths > 0) { "Interval bunga harus minimal satu" }
        require(interestIntervalUnit in setOf(InterestInterval.MONTHS, InterestInterval.DAYS)) { "Satuan interval bunga tidak valid" }
        require(dueDate == null || !dueDate.isBefore(startDate)) { "Tenggat tidak boleh sebelum tanggal mulai" }
        val debtId = UUID.randomUUID().toString()
        val eventId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val balanceBefore = if (fundingSource == DebtFundingSource.EXTERNAL) 0L else dao.accountBalance(accountId, fundingSource)
        val vaultBefore = if (fundingSource == DebtFundingSource.EXTERNAL) 0L else dao.vaultBalance(fundingSource, accountId)
        if (role == DebtRole.CREDITOR && fundingSource != DebtFundingSource.EXTERNAL) {
            require(vaultBefore >= principal) { "Vault ${if (fundingSource == DebtFundingSource.CASH) "Cash" else "eBudget"} tidak mencukupi" }
        }
        val debt = DebtEntity(
            id = debtId,
            accountId = accountId,
            role = role,
            counterparty = counterparty.trim(),
            title = title.trim(),
            principalOriginal = principal,
            principalOutstanding = principal,
            interestRateBps = interestRateBps,
            interestIntervalMonths = interestIntervalMonths,
            interestIntervalUnit = interestIntervalUnit,
            interestAnchorEpochDay = startDate.toEpochDay(),
            dueEpochDay = dueDate?.toEpochDay(),
            createdAt = createdAt,
            updatedAt = createdAt,
            syncId = debtId,
        )
        dao.insertEvent(ActivityEventEntity(
            id = eventId,
            type = LedgerType.DEBT_OPEN,
            title = if (role == DebtRole.DEBTOR) "Hutang dibuat: ${debt.title}" else "Piutang dibuat: ${debt.title}",
            note = note.trim(),
            source = "USER",
            effectiveEpochDay = startDate.toEpochDay(),
            accountId = accountId,
        ))
        if (fundingSource != DebtFundingSource.EXTERNAL) {
            val amount = if (role == DebtRole.DEBTOR) principal else -principal
            dao.insertCashLines(listOf(CashJournalLineEntity(eventId = eventId, accountId = accountId, fundingChannel = fundingSource, amount = amount)))
            dao.insertBudgetLines(listOf(
                BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = fundingSource, amount = amount, accountId = accountId),
                BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.EXTERNAL, fundingChannel = fundingSource, amount = -amount, accountId = accountId),
            ))
        }
        dao.insertDebt(debt)
        dao.insertDebtEntry(DebtEntryEntity(
            debtId = debtId,
            accountId = accountId,
            eventId = eventId,
            type = DebtEntryType.OPEN,
            principalAmount = principal,
            fundingSource = fundingSource,
            effectiveEpochDay = startDate.toEpochDay(),
            note = note.trim(),
        ))
        dao.insertAudit(AuditSnapshotEntity(
            eventId = eventId,
            reason = if (role == DebtRole.DEBTOR) "Dana pinjaman masuk ke saldo akun" else "Dana piutang keluar dari Vault akun",
            beforeJson = JSONObject()
                .put("debt", JSONObject.NULL)
                .put("fundingSource", fundingSource)
                .put("balance", balanceBefore)
                .put("vault", vaultBefore)
                .toString(),
            afterJson = JSONObject()
                .put("debtId", debtId)
                .put("role", role)
                .put("principal", principal)
                .put("interestRateBps", interestRateBps)
                .put("interestIntervalMonths", interestIntervalMonths)
                .put("interestIntervalUnit", interestIntervalUnit)
                .put("dueEpochDay", dueDate?.toEpochDay())
                .put("fundingSource", fundingSource)
                .put("flow", if (role == DebtRole.DEBTOR) "external->$fundingSource" else "$fundingSource->external")
                .put("balance", if (fundingSource == DebtFundingSource.EXTERNAL) balanceBefore else balanceBefore + if (role == DebtRole.DEBTOR) principal else -principal)
                .put("vault", if (fundingSource == DebtFundingSource.EXTERNAL) vaultBefore else vaultBefore + if (role == DebtRole.DEBTOR) principal else -principal)
                .toString(),
        ))
        assertInvariant()
        debtId
    }

    suspend fun recordDebtPayment(
        debtId: String,
        fundingSource: String,
        amount: Long,
        categoryId: Long?,
        note: String,
        effectiveDate: LocalDate = LocalDate.now(),
    ): String = database.withTransaction {
        val debt = requireNotNull(dao.debtById(debtId)) { "Hutang tidak ditemukan" }
        requireActiveAccount(debt.accountId)
        require(debt.status == DebtStatus.OPEN) { "Hutang sudah tidak aktif" }
        require(fundingSource in setOf(DebtFundingSource.CASH, DebtFundingSource.EBUDGET, DebtFundingSource.EXTERNAL)) { "Sumber dana hutang tidak valid" }
        require(amount > 0L) { "Nominal pembayaran harus lebih dari nol" }
        require(!effectiveDate.isBefore(LocalDate.ofEpochDay(debt.interestAnchorEpochDay))) { "Tanggal pembayaran tidak boleh sebelum pembayaran terakhir" }
        val interestBefore = DebtCalculator.currentInterest(debt, effectiveDate)
        val due = Math.addExact(debt.principalOutstanding, interestBefore)
        require(amount <= due) { "Nominal melebihi total hutang berjalan" }
        val interestPaid = min(amount, interestBefore)
        val principalPaid = amount - interestPaid
        val title = if (debt.role == DebtRole.DEBTOR) "Bayar hutang: ${debt.title}" else "Terima piutang: ${debt.title}"
        val balanceBefore = if (fundingSource == DebtFundingSource.EXTERNAL) 0L else dao.accountBalance(debt.accountId, fundingSource)
        val vaultBefore = if (fundingSource == DebtFundingSource.EXTERNAL) 0L else dao.vaultBalance(fundingSource, debt.accountId)
        val eventId = if (fundingSource == DebtFundingSource.EXTERNAL) {
            val externalEventId = UUID.randomUUID().toString()
            dao.insertEvent(ActivityEventEntity(
                id = externalEventId,
                type = LedgerType.DEBT_PAYMENT,
                title = title,
                note = note.trim(),
                source = "USER",
                effectiveEpochDay = effectiveDate.toEpochDay(),
                accountId = debt.accountId,
            ))
            externalEventId
        } else if (debt.role == DebtRole.DEBTOR) {
            postExpenseInternal(
                accountId = debt.accountId,
                fundingChannel = fundingSource,
                amount = amount,
                splits = listOf(ExpenseSplitInput(categoryId = categoryId, allocationId = null, amount = amount)),
                title = title,
                note = note.trim(),
                effectiveDate = effectiveDate,
                eventType = LedgerType.UNEXPECTED_EXPENSE,
                source = "USER",
                reduceVault = true,
            )
        } else {
            postIncomeInternal(
                accountId = debt.accountId,
                fundingChannel = fundingSource,
                amount = amount,
                categoryId = categoryId,
                title = title,
                note = note.trim(),
                effectiveDate = effectiveDate,
                eventType = LedgerType.INCOME,
                source = "USER",
            )
        }
        val nextPrincipal = debt.principalOutstanding - principalPaid
        val nextInterest = interestBefore - interestPaid
        val settled = nextPrincipal == 0L && nextInterest == 0L
        dao.updateDebt(debt.copy(
            principalOutstanding = nextPrincipal,
            interestOutstanding = nextInterest,
            interestAnchorEpochDay = effectiveDate.toEpochDay(),
            status = if (settled) DebtStatus.SETTLED else DebtStatus.OPEN,
        ).bumpRevision())
        dao.insertDebtEntry(DebtEntryEntity(
            debtId = debt.id,
            accountId = debt.accountId,
            eventId = eventId,
            type = if (settled) DebtEntryType.SETTLEMENT else DebtEntryType.PAYMENT,
            principalAmount = principalPaid,
            interestAmount = interestPaid,
            fundingSource = fundingSource,
            effectiveEpochDay = effectiveDate.toEpochDay(),
            note = note.trim(),
        ))
        dao.insertAudit(AuditSnapshotEntity(
            eventId = eventId,
            reason = if (fundingSource == DebtFundingSource.EXTERNAL) "Pembayaran hutang dicatat dari sumber external" else "Pembayaran hutang dicatat bersama transaksi saldo",
            beforeJson = JSONObject()
                .put("debtId", debt.id)
                .put("principal", debt.principalOutstanding)
                .put("interest", interestBefore)
                .put("fundingSource", fundingSource)
                .put("balance", balanceBefore)
                .put("vault", vaultBefore)
                .toString(),
            afterJson = JSONObject()
                .put("debtId", debt.id)
                .put("payment", amount)
                .put("principalPaid", principalPaid)
                .put("interestPaid", interestPaid)
                .put("principal", nextPrincipal)
                .put("interest", nextInterest)
                .put("status", if (settled) DebtStatus.SETTLED else DebtStatus.OPEN)
                .put("fundingSource", fundingSource)
                .put("flow", if (debt.role == DebtRole.DEBTOR) "$fundingSource->external" else "external->$fundingSource")
                .put("balance", if (fundingSource == DebtFundingSource.EXTERNAL) balanceBefore else balanceBefore + if (debt.role == DebtRole.DEBTOR) -amount else amount)
                .put("vault", if (fundingSource == DebtFundingSource.EXTERNAL) vaultBefore else vaultBefore + if (debt.role == DebtRole.DEBTOR) -amount else amount)
                .toString(),
        ))
        assertInvariant()
        eventId
    }

    suspend fun archiveDebt(debtId: String, note: String) = database.withTransaction {
        val debt = requireNotNull(dao.debtById(debtId)) { "Hutang tidak ditemukan" }
        requireActiveAccount(debt.accountId)
        require(debt.status != DebtStatus.ARCHIVED) { "Hutang sudah diarsipkan" }
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(
            id = eventId,
            type = LedgerType.DEBT_ARCHIVE,
            title = "Hutang diarsipkan: ${debt.title}",
            note = note.trim(),
            source = "USER",
            effectiveEpochDay = LocalDate.now().toEpochDay(),
            accountId = debt.accountId,
        ))
        dao.updateDebt(debt.copy(status = DebtStatus.ARCHIVED).bumpRevision())
        dao.insertDebtEntry(DebtEntryEntity(
            debtId = debt.id,
            accountId = debt.accountId,
            eventId = eventId,
            type = DebtEntryType.ARCHIVE,
            effectiveEpochDay = LocalDate.now().toEpochDay(),
            note = note.trim(),
        ))
        dao.insertAudit(AuditSnapshotEntity(
            eventId = eventId,
            reason = "Tracker hutang diarsipkan tanpa menghapus riwayat",
            beforeJson = JSONObject().put("status", debt.status).toString(),
            afterJson = JSONObject().put("status", DebtStatus.ARCHIVED).toString(),
        ))
        assertInvariant()
    }

    suspend fun transfer(fromAccountId: Long, fromChannel: String, toAccountId: Long, toChannel: String, amount: Long, note: String) = database.withTransaction {
        require(fromAccountId != toAccountId || fromChannel != toChannel) { "Sumber dan tujuan tidak boleh sama" }
        require(amount > 0) { "Nominal harus lebih dari nol" }
        val fromAccount = requireNotNull(dao.accountById(fromAccountId))
        val toAccount = requireNotNull(dao.accountById(toAccountId))
        teamAccessGuard.require(fromAccountId, TeamCapability.WRITE)
        teamAccessGuard.require(toAccountId, TeamCapability.WRITE)
        teamAccessGuard.requireTransfer(fromAccountId, toAccountId)
        require(fromAccount.isActive) { "Akun sumber harus menjadi akun aktif" }
        require(!toAccount.isArchived) { "Akun tujuan sudah diarsipkan" }
        require(fromChannel in setOf(FundingChannel.CASH, FundingChannel.EBUDGET) && toChannel in setOf(FundingChannel.CASH, FundingChannel.EBUDGET)) { "Kanal transfer tidak valid" }
        require(dao.accountBalance(fromAccountId, fromChannel) >= amount) { "Saldo kanal sumber tidak mencukupi" }
        require(dao.vaultBalance(fromChannel, fromAccountId) >= amount) {
            "Main Vault kanal sumber tidak mencukupi. Dana yang sudah terbooking tidak dapat ditransfer antar akun atau kanal."
        }
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(
            id = eventId,
            type = LedgerType.TRANSFER,
            title = if (fromChannel == toChannel) "Transfer antar akun" else "Konversi $fromChannel ke $toChannel",
            note = note,
            source = "USER",
            effectiveEpochDay = LocalDate.now().toEpochDay(),
            accountId = fromAccountId,
        ))
        dao.insertCashLines(listOf(
            CashJournalLineEntity(eventId = eventId, accountId = fromAccountId, fundingChannel = fromChannel, amount = -amount),
            CashJournalLineEntity(eventId = eventId, accountId = toAccountId, fundingChannel = toChannel, amount = amount),
        ))
        dao.insertBudgetLines(listOf(
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = fromChannel, amount = -amount, accountId = fromAccountId),
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.EXTERNAL, fundingChannel = fromChannel, amount = amount, accountId = fromAccountId),
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.EXTERNAL, fundingChannel = toChannel, amount = -amount, accountId = toAccountId),
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = toChannel, amount = amount, accountId = toAccountId),
        ))
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = if (fromAccountId == toAccountId) "Konversi komposisi kanal Main Vault" else "Transfer dana Main Vault antar akun", beforeJson = "{\"accountId\":$fromAccountId,\"channel\":\"$fromChannel\"}", afterJson = "{\"accountId\":$toAccountId,\"channel\":\"$toChannel\",\"amount\":$amount}"))
        assertInvariant()
        eventId
    }

    suspend fun createPortfolio(
        name: String,
        cadence: String,
        plannedIncome: Long,
        rolloverEnabled: Boolean,
        drafts: List<AllocationDraft>,
        startDate: LocalDate = LocalDate.now(),
        endDate: LocalDate? = null,
        intervalCount: Int = 1,
    ) = database.withTransaction {
        require(name.isNotBlank()) { "Nama portfolio wajib diisi" }
        require(drafts.isNotEmpty() && drafts.all { it.plannedAmount > 0 }) { "Tambahkan minimal satu alokasi" }
        require(cadence == "MONTHLY" || cadence == "YEARLY") { "Cadence portfolio tidak valid" }
        require(intervalCount > 0) { "Interval harus minimal 1" }
        require(endDate == null || !endDate.isBefore(startDate)) { "Tanggal akhir tidak boleh sebelum tanggal mulai" }
        val activeId = activeAccountId()
        val teamScopedCategoryAccountId = activeId.takeIf {
            dao.accountById(it)?.sharingMode == AccountSharingMode.TEAM
        }
        val resolvedDrafts = drafts.map { draft ->
            if (draft.categoryId > 0) draft else {
                val categoryName = requireNotNull(draft.categoryName?.trim()) { "Nama kategori wajib diisi" }
                require(categoryName.isNotBlank()) { "Nama kategori wajib diisi" }
                val existing = dao.categoriesForAccount(activeId).firstOrNull {
                    it.direction == TransactionDirection.EXPENSE && it.name.equals(categoryName, ignoreCase = true)
                }
                val categoryId = existing?.id ?: dao.insertCategory(CategoryEntity(
                    name = categoryName,
                    direction = TransactionDirection.EXPENSE,
                    color = 0xFF9E6BFF,
                    icon = "category",
                    accountId = teamScopedCategoryAccountId,
                ))
                draft.copy(categoryId = categoryId, categoryName = categoryName)
            }
        }
        val today = LocalDate.now()
        val (start, end) = periodBounds(startDate, cadence, intervalCount)
        val portfolioId = dao.insertPortfolio(PortfolioEntity(
            name = name.trim(),
            cadence = cadence,
            intervalCount = intervalCount,
            plannedIncome = plannedIncome,
            rolloverEnabled = rolloverEnabled,
            fundingPriority = 100,
            startEpochDay = start.toEpochDay(),
            endMode = if (endDate == null) "CONTINUOUS" else "DATE",
            endValue = endDate?.toEpochDay(),
            accountId = activeId,
        ))
        resolvedDrafts.groupBy { it.categoryId }.forEach { (categoryId, channelDrafts) ->
            val categoryTotal = channelDrafts.sumOf { it.plannedAmount }
            val cashTotal = channelDrafts.filter { it.fundingChannel == FundingChannel.CASH }.sumOf { it.plannedAmount }
            val cashPercentage = if (categoryTotal == 0L) 0 else ((cashTotal * 100) / categoryTotal).toInt()
            dao.insertAllocationTemplate(PortfolioAllocationTemplateEntity(
                portfolioId = portfolioId,
                categoryId = categoryId,
                plannedAmount = categoryTotal,
                cashPercentage = cashPercentage,
            ))
        }
        val total = resolvedDrafts.sumOf { it.plannedAmount }
        val requestedByChannel = resolvedDrafts.groupBy { it.fundingChannel }.mapValues { (_, values) -> values.sumOf { it.plannedAmount } }
        val withinPeriod = !today.isBefore(start) && !today.isAfter(end)
        val canFund = withinPeriod && requestedByChannel.all { (channel, value) -> dao.vaultBalance(channel, activeId) >= value }
        val periodId = dao.insertPeriod(BudgetPeriodEntity(
            portfolioId = portfolioId,
            startEpochDay = start.toEpochDay(),
            endEpochDay = end.toEpochDay(),
            status = when {
                today.isBefore(start) -> PeriodStatus.DRAFT
                today.isAfter(end) -> PeriodStatus.CLOSED
                canFund -> PeriodStatus.ACTIVE
                else -> PeriodStatus.UNDERFUNDED
            },
        ))
        val allocations = resolvedDrafts.map { it to dao.insertAllocation(AllocationEntity(periodId = periodId, categoryId = it.categoryId, fundingChannel = it.fundingChannel, plannedAmount = it.plannedAmount)) }
        if (canFund) {
            val eventId = UUID.randomUUID().toString()
            val activeId = activeAccountId()
            dao.insertEvent(ActivityEventEntity(
                id = eventId,
                type = LedgerType.PORTFOLIO_BOOKING,
                title = "Booking $name",
                note = "Dana portfolio dibooking dari Main Vault",
                source = "USER",
                effectiveEpochDay = today.toEpochDay(),
                accountId = activeId,
            ))
            dao.insertBudgetLines(
                requestedByChannel.map { (channel, value) -> BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = channel, amount = -value, accountId = activeId) } +
                    allocations.map { (draft, allocationId) -> BudgetJournalLineEntity(eventId = eventId, allocationId = allocationId, fundingChannel = draft.fundingChannel, amount = draft.plannedAmount, accountId = activeId) }
            )
            dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = "Booking portfolio", beforeJson = "{\"vault\":${dao.vaultBalance() + total}}", afterJson = "{\"vault\":${dao.vaultBalance()}}"))
        }
        assertInvariant()
        portfolioId
    }

    suspend fun fundUnderfundedPeriod(periodId: Long) = database.withTransaction {
        val period = requireNotNull(dao.periodById(periodId))
        val activeId = activeAccountId()
        require(requireNotNull(dao.allPortfolios().firstOrNull { it.id == period.portfolioId }).accountId == activeId) {
            "Periode budget bukan milik akun aktif"
        }
        require(period.status == PeriodStatus.UNDERFUNDED || period.status == PeriodStatus.DRAFT)
        val allocations = dao.allocationsForPeriod(periodId)
        val needs = allocations.associateWith { (it.plannedAmount - dao.allocationAvailable(it.id)).coerceAtLeast(0) }
        val requestedByChannel = needs.entries.groupBy { it.key.fundingChannel }.mapValues { (_, values) -> values.sumOf { it.value } }
        require(requestedByChannel.all { (channel, value) -> dao.vaultBalance(channel, activeId) >= value }) { "Main Vault belum mencukupi" }
        if (requestedByChannel.values.all { it == 0L }) {
            dao.updatePeriod(period.copy(status = PeriodStatus.ACTIVE).bumpRevision())
            return@withTransaction
        }
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.PORTFOLIO_BOOKING, "Aktivasi portfolio", "Booking dari Main Vault", "USER", LocalDate.now().toEpochDay(), accountId = activeId))
        dao.insertBudgetLines(requestedByChannel.filterValues { it > 0 }.map { (channel, value) -> BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = channel, amount = -value, accountId = activeId) } + needs.filterValues { it > 0 }.map { (allocation, value) ->
            BudgetJournalLineEntity(eventId = eventId, allocationId = allocation.id, fundingChannel = allocation.fundingChannel, amount = value, accountId = activeId)
        })
        dao.updatePeriod(period.copy(status = PeriodStatus.ACTIVE).bumpRevision())
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = "Pendanaan draft", beforeJson = "{\"status\":\"${period.status}\"}", afterJson = "{\"status\":\"ACTIVE\"}"))
        assertInvariant()
    }

    private suspend fun resolutionCategoryLabel(allocation: AllocationEntity): Pair<String, String> {
        val category = requireNotNull(dao.categoryById(allocation.categoryId)) { "Kategori resolusi tidak ditemukan" }
        val portfolio = requireNotNull(dao.portfolioForAllocation(allocation.id)) { "Portfolio resolusi tidak ditemukan" }
        val period = requireNotNull(dao.periodById(allocation.periodId)) { "Periode resolusi tidak ditemukan" }
        val channel = if (allocation.fundingChannel == FundingChannel.CASH) "Cash" else "eBudget"
        val start = LocalDate.ofEpochDay(period.startEpochDay)
        val end = LocalDate.ofEpochDay(period.endEpochDay)
        return category.name to "$portfolio.name | $start s.d. $end | $channel"
    }

    private fun systemResolutionLabel(bucket: String, channel: String): Pair<String, String> {
        val channelLabel = if (channel == FundingChannel.CASH) "Cash" else "eBudget"
        return when (bucket) {
            BudgetBucket.VAULT -> "Main Vault" to channelLabel
            BudgetBucket.ROLLOVER -> "Rollover" to "Reserve rollover • $channelLabel"
            BudgetBucket.UNALLOCATED -> "Belum dialokasikan" to channelLabel
            else -> bucket to channelLabel
        }
    }

    suspend fun resolveFromAllocation(sourceAllocationId: Long, targetAllocationId: Long, amount: Long, note: String) = database.withTransaction {
        require(amount > 0)
        val source = requireNotNull(dao.allocationById(sourceAllocationId))
        val target = requireNotNull(dao.allocationById(targetAllocationId))
        val activeId = activeAccountId()
        require(requireNotNull(dao.portfolioForAllocation(sourceAllocationId)).accountId == activeId) { "Kategori sumber bukan milik akun aktif" }
        require(requireNotNull(dao.portfolioForAllocation(targetAllocationId)).accountId == activeId) { "Kategori tujuan bukan milik akun aktif" }
        require(source.periodId == target.periodId) { "Resolusi langsung hanya tersedia dalam periode budget yang sama" }
        require(source.fundingChannel == target.fundingChannel) { "Resolusi harus memakai kanal dana yang sama" }
        require(dao.allocationAvailable(sourceAllocationId) >= amount) { "Sisa sumber tidak mencukupi" }
        require(dao.allocationAvailable(targetAllocationId) < 0) { "Target tidak sedang minus" }
        val actualAmount = min(amount, -dao.allocationAvailable(targetAllocationId))
        val sourceBefore = dao.allocationAvailable(sourceAllocationId)
        val targetBefore = dao.allocationAvailable(targetAllocationId)
        val account = requireNotNull(dao.accountById(activeId))
        val (sourceLabel, sourceDetail) = resolutionCategoryLabel(source)
        val (targetLabel, targetDetail) = resolutionCategoryLabel(target)
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.REALLOCATION, "Resolusi antar kategori", note, "USER", LocalDate.now().toEpochDay(), accountId = activeId))
        dao.insertBudgetLines(listOf(
            BudgetJournalLineEntity(eventId = eventId, allocationId = sourceAllocationId, fundingChannel = source.fundingChannel, amount = -actualAmount, accountId = activeId),
            BudgetJournalLineEntity(eventId = eventId, allocationId = targetAllocationId, fundingChannel = target.fundingChannel, amount = actualAmount, accountId = activeId),
        ))
        refreshPeriodStatus(source.periodId)
        refreshPeriodStatus(target.periodId)
        val (before, after) = ResolutionAudit.snapshots(
            account = account.name,
            channel = source.fundingChannel,
            amount = actualAmount,
            sourceLabel = sourceLabel,
            sourceDetail = sourceDetail,
            sourceBefore = sourceBefore,
            sourceAfter = sourceBefore - actualAmount,
            targetLabel = targetLabel,
            targetDetail = targetDetail,
            targetBefore = targetBefore,
            targetAfter = targetBefore + actualAmount,
        )
        dao.insertAudit(AuditSnapshotEntity(
            eventId = eventId,
            reason = "Resolusi kategori minus",
            beforeJson = before,
            afterJson = after,
        ))
        assertInvariant()
        eventId
    }

    suspend fun allocateUnallocated(targetAllocationId: Long, amount: Long, note: String) = database.withTransaction {
        require(amount > 0) { "Nominal harus lebih dari nol" }
        val target = requireNotNull(dao.allocationById(targetAllocationId))
        val activeId = activeAccountId()
        require(requireNotNull(dao.portfolioForAllocation(targetAllocationId)).accountId == activeId) { "Kategori target bukan milik akun aktif" }
        val unresolved = dao.unallocatedBalance(target.fundingChannel, activeId)
        require(unresolved < 0) { "Tidak ada pengeluaran belum teralokasi pada kanal ini" }
        val actualAmount = min(amount, -unresolved)
        val targetBefore = dao.allocationAvailable(targetAllocationId)
        val account = requireNotNull(dao.accountById(activeId))
        val (sourceLabel, sourceDetail) = systemResolutionLabel(BudgetBucket.UNALLOCATED, target.fundingChannel)
        val (targetLabel, targetDetail) = resolutionCategoryLabel(target)
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.REALLOCATION, "Alokasi pengeluaran tertunda", note, "USER", LocalDate.now().toEpochDay(), accountId = activeId))
        dao.insertBudgetLines(listOf(
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.UNALLOCATED, fundingChannel = target.fundingChannel, amount = actualAmount, accountId = activeId),
            BudgetJournalLineEntity(eventId = eventId, allocationId = targetAllocationId, fundingChannel = target.fundingChannel, amount = -actualAmount, accountId = activeId),
        ))
        refreshPeriodStatus(target.periodId)
        val (before, after) = ResolutionAudit.snapshots(
            account = account.name,
            channel = target.fundingChannel,
            amount = actualAmount,
            sourceLabel = sourceLabel,
            sourceDetail = sourceDetail,
            sourceBefore = unresolved,
            sourceAfter = unresolved + actualAmount,
            targetLabel = targetLabel,
            targetDetail = targetDetail,
            targetBefore = targetBefore,
            targetAfter = targetBefore - actualAmount,
        )
        dao.insertAudit(AuditSnapshotEntity(
            eventId = eventId,
            reason = "Pengeluaran belum teralokasi dipindahkan ke kategori",
            beforeJson = before,
            afterJson = after,
        ))
        assertInvariant()
        eventId
    }

    suspend fun resolveFromVault(targetAllocationId: Long, amount: Long, note: String) = database.withTransaction {
        require(amount > 0)
        val target = requireNotNull(dao.allocationById(targetAllocationId))
        val activeId = activeAccountId()
        require(requireNotNull(dao.portfolioForAllocation(targetAllocationId)).accountId == activeId) { "Kategori target bukan milik akun aktif" }
        val targetBefore = dao.allocationAvailable(targetAllocationId)
        require(targetBefore < 0) { "Target tidak sedang minus" }
        val actualAmount = min(amount, -targetBefore)
        val vaultBefore = dao.vaultBalance(target.fundingChannel, activeId)
        require(vaultBefore >= actualAmount) { "Main Vault tidak mencukupi" }
        val account = requireNotNull(dao.accountById(activeId))
        val (sourceLabel, sourceDetail) = systemResolutionLabel(BudgetBucket.VAULT, target.fundingChannel)
        val (targetLabel, targetDetail) = resolutionCategoryLabel(target)
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.OVERBUDGET_COVERAGE, "Overbudget dari Main Vault", note, "USER", LocalDate.now().toEpochDay(), accountId = activeId))
        dao.insertBudgetLines(listOf(
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = target.fundingChannel, amount = -actualAmount, accountId = activeId),
            BudgetJournalLineEntity(eventId = eventId, allocationId = targetAllocationId, fundingChannel = target.fundingChannel, amount = actualAmount, accountId = activeId),
        ))
        refreshPeriodStatus(target.periodId)
        val (before, after) = ResolutionAudit.snapshots(
            account = account.name,
            channel = target.fundingChannel,
            amount = actualAmount,
            sourceLabel = sourceLabel,
            sourceDetail = sourceDetail,
            sourceBefore = vaultBefore,
            sourceAfter = vaultBefore - actualAmount,
            targetLabel = targetLabel,
            targetDetail = targetDetail,
            targetBefore = targetBefore,
            targetAfter = targetBefore + actualAmount,
        )
        dao.insertAudit(AuditSnapshotEntity(
            eventId = eventId,
            reason = "Defisit ditutup dari dana nyata Main Vault",
            beforeJson = before,
            afterJson = after,
        ))
        assertInvariant()
        eventId
    }

    suspend fun resolveFromRollover(targetAllocationId: Long, amount: Long, note: String) = database.withTransaction {
        require(amount > 0)
        val target = requireNotNull(dao.allocationById(targetAllocationId))
        val activeId = activeAccountId()
        require(requireNotNull(dao.portfolioForAllocation(targetAllocationId)).accountId == activeId) { "Kategori target bukan milik akun aktif" }
        val targetBefore = dao.allocationAvailable(targetAllocationId)
        require(targetBefore < 0) { "Target tidak sedang minus" }
        val reserveBefore = dao.rolloverBalance(target.fundingChannel, activeId)
        val actualAmount = min(amount, min(-targetBefore, reserveBefore))
        require(actualAmount > 0) { "Reserve rollover tidak mencukupi" }
        val account = requireNotNull(dao.accountById(activeId))
        val (sourceLabel, sourceDetail) = systemResolutionLabel(BudgetBucket.ROLLOVER, target.fundingChannel)
        val (targetLabel, targetDetail) = resolutionCategoryLabel(target)
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.OVERBUDGET_COVERAGE, "Overbudget dari Reserve rollover", note, "USER", LocalDate.now().toEpochDay(), accountId = activeId))
        dao.insertBudgetLines(listOf(
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.ROLLOVER, fundingChannel = target.fundingChannel, amount = -actualAmount, accountId = activeId),
            BudgetJournalLineEntity(eventId = eventId, allocationId = targetAllocationId, fundingChannel = target.fundingChannel, amount = actualAmount, accountId = activeId),
        ))
        refreshPeriodStatus(target.periodId)
        val (before, after) = ResolutionAudit.snapshots(
            account = account.name,
            channel = target.fundingChannel,
            amount = actualAmount,
            sourceLabel = sourceLabel,
            sourceDetail = sourceDetail,
            sourceBefore = reserveBefore,
            sourceAfter = reserveBefore - actualAmount,
            targetLabel = targetLabel,
            targetDetail = targetDetail,
            targetBefore = targetBefore,
            targetAfter = targetBefore + actualAmount,
        )
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = "Defisit ditutup dari Reserve rollover", beforeJson = before, afterJson = after))
        assertInvariant()
        eventId
    }

    suspend fun correctAllocation(allocationId: Long, newPlannedAmount: Long, note: String) = database.withTransaction {
        require(newPlannedAmount >= 0) { "Nominal budget tidak boleh negatif" }
        val allocation = requireNotNull(dao.allocationById(allocationId))
        val activeId = activeAccountId()
        require(requireNotNull(dao.portfolioForAllocation(allocationId)).accountId == activeId) { "Kategori budget bukan milik akun aktif" }
        val oldPlanned = allocation.plannedAmount
        val delta = newPlannedAmount - oldPlanned
        require(delta != 0L) { "Tidak ada perubahan nominal" }
        val spent = oldPlanned - dao.allocationAvailable(allocationId)
        require(newPlannedAmount >= spent) { "Budget baru lebih kecil dari pengeluaran yang sudah tercatat" }
        if (delta > 0) require(dao.vaultBalance(allocation.fundingChannel, activeId) >= delta) { "Main Vault tidak mencukupi untuk menambah budget" }
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.REALLOCATION, "Koreksi budget", note, "USER", LocalDate.now().toEpochDay(), accountId = activeId))
        dao.insertBudgetLines(listOf(
            BudgetJournalLineEntity(eventId = eventId, allocationId = allocationId, fundingChannel = allocation.fundingChannel, amount = delta, accountId = activeId),
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = allocation.fundingChannel, amount = -delta, accountId = activeId),
        ))
        dao.updateAllocation(allocation.copy(plannedAmount = newPlannedAmount).bumpRevision())
        val period = requireNotNull(dao.periodById(allocation.periodId))
        val template = dao.templatesForPortfolio(period.portfolioId).firstOrNull { it.categoryId == allocation.categoryId }
        if (template != null) {
            val oldCash = template.plannedAmount * template.cashPercentage / 100
            val newCash = oldCash + (if (allocation.fundingChannel == FundingChannel.CASH) delta else 0)
            val newTotal = template.plannedAmount + delta
            val newPercentage = if (newTotal == 0L) 0 else ((newCash * 100) / newTotal).toInt()
            dao.updateAllocationTemplate(template.copy(
                plannedAmount = newTotal,
                cashPercentage = newPercentage,
                revision = template.revision + 1,
                updatedAt = System.currentTimeMillis(),
            ))
        }
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = note, beforeJson = "{\"planned\":$oldPlanned,\"template\":${template?.plannedAmount ?: 0}}", afterJson = "{\"planned\":$newPlannedAmount,\"template\":${if (template != null) template.plannedAmount + delta else 0}}"))
        refreshPeriodStatus(allocation.periodId)
        assertInvariant()
        eventId
    }

    // ponytail: CRUD kategori budget — reuse Category/Allocation/Template, no new table
    suspend fun addBudgetCategoryToPeriod(
        periodId: Long,
        categoryName: String,
        plannedAmount: Long,
        cashPercentage: Int,
        note: String,
    ) = database.withTransaction {
        val trimmedName = categoryName.trim()
        require(trimmedName.isNotBlank()) { "Nama kategori wajib diisi" }
        require(plannedAmount > 0) { "Nominal budget harus lebih dari nol" }
        require(cashPercentage in 0..100) { "Persentase cash tidak valid" }
        require(note.isNotBlank()) { "Alasan wajib diisi" }
        val period = requireNotNull(dao.periodById(periodId)) { "Periode tidak ditemukan" }
        val portfolio = requireNotNull(dao.allPortfolios().firstOrNull { it.id == period.portfolioId }) { "Portfolio tidak ditemukan" }
        val activeId = activeAccountId()
        require(portfolio.accountId == activeId) { "Portfolio bukan milik akun aktif" }
        require(!portfolio.isArchived) { "Portfolio sudah diarsip" }
        require(period.status != PeriodStatus.CLOSED) { "Periode sudah tertutup" }
        val cashAmount = plannedAmount * cashPercentage / 100
        val eBudgetAmount = plannedAmount - cashAmount
        require(cashAmount >= 0 && eBudgetAmount >= 0) { "Komposisi kanal tidak valid" }
        require(cashAmount > 0 || eBudgetAmount > 0) { "Nominal budget tidak valid" }

        val teamScopedAccountId = activeId.takeIf { dao.accountById(it)?.sharingMode == AccountSharingMode.TEAM }
        val existingCategory = dao.categoriesForAccount(activeId).firstOrNull {
            it.direction == TransactionDirection.EXPENSE && it.name.equals(trimmedName, ignoreCase = true)
        }
        val categoryId = existingCategory?.id ?: dao.insertCategory(
            CategoryEntity(
                name = trimmedName,
                direction = TransactionDirection.EXPENSE,
                color = 0xFF9E6BFF,
                icon = "category",
                accountId = teamScopedAccountId,
            )
        )
        // check duplicate in this period
        require(dao.allocationsForCategory(periodId, categoryId).isEmpty()) { "Kategori sudah ada di periode ini" }
        if (cashAmount > 0) require(dao.vaultBalance(FundingChannel.CASH, activeId) >= cashAmount) { "Main Vault Cash tidak mencukupi" }
        if (eBudgetAmount > 0) require(dao.vaultBalance(FundingChannel.EBUDGET, activeId) >= eBudgetAmount) { "Main Vault eBudget tidak mencukupi" }

        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.REALLOCATION, "Tambah kategori $trimmedName", note, "USER", LocalDate.now().toEpochDay(), accountId = activeId))
        val insertedIds = mutableListOf<Long>()
        val budgetLines = mutableListOf<BudgetJournalLineEntity>()
        if (cashAmount > 0) {
            val id = dao.insertAllocation(AllocationEntity(periodId = periodId, categoryId = categoryId, fundingChannel = FundingChannel.CASH, plannedAmount = cashAmount))
            insertedIds.add(id)
            budgetLines.add(BudgetJournalLineEntity(eventId = eventId, allocationId = id, fundingChannel = FundingChannel.CASH, amount = cashAmount, accountId = activeId))
            budgetLines.add(BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = FundingChannel.CASH, amount = -cashAmount, accountId = activeId))
        }
        if (eBudgetAmount > 0) {
            val id = dao.insertAllocation(AllocationEntity(periodId = periodId, categoryId = categoryId, fundingChannel = FundingChannel.EBUDGET, plannedAmount = eBudgetAmount))
            insertedIds.add(id)
            budgetLines.add(BudgetJournalLineEntity(eventId = eventId, allocationId = id, fundingChannel = FundingChannel.EBUDGET, amount = eBudgetAmount, accountId = activeId))
            budgetLines.add(BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = FundingChannel.EBUDGET, amount = -eBudgetAmount, accountId = activeId))
        }
        if (budgetLines.isNotEmpty()) dao.insertBudgetLines(budgetLines)
        // template for future periods
        if (dao.templateForCategory(portfolio.id, categoryId) == null) {
            dao.insertAllocationTemplate(PortfolioAllocationTemplateEntity(portfolioId = portfolio.id, categoryId = categoryId, plannedAmount = plannedAmount, cashPercentage = cashPercentage))
        }
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = note, beforeJson = "{\"periodId\":$periodId,\"categoryId\":$categoryId}", afterJson = "{\"periodId\":$periodId,\"categoryId\":$categoryId,\"planned\":$plannedAmount,\"cashPercentage\":$cashPercentage}"))
        refreshPeriodStatus(periodId)
        assertInvariant()
        insertedIds
    }

    suspend fun renameBudgetCategory(categoryId: Long, newName: String) = database.withTransaction {
        val trimmed = newName.trim()
        require(trimmed.isNotBlank()) { "Nama kategori wajib diisi" }
        val category = requireNotNull(dao.categoryById(categoryId)) { "Kategori tidak ditemukan" }
        require(category.direction == TransactionDirection.EXPENSE) { "Hanya kategori budget (EXPENSE) yang dapat diubah" }
        val activeId = activeAccountId()
        if (category.accountId != null) require(category.accountId == activeId) { "Kategori bukan milik akun aktif" }
        require(!category.isArchived) { "Kategori sudah diarsipkan" }
        require(category.name != trimmed) { "Tidak ada perubahan nama" }
        val duplicate = dao.categoriesForAccount(activeId).firstOrNull { it.direction == TransactionDirection.EXPENSE && it.name.equals(trimmed, ignoreCase = true) && it.id != categoryId }
        require(duplicate == null) { "Nama kategori sudah dipakai" }
        val updated = category.copy(name = trimmed, revision = category.revision + 1, updatedAt = System.currentTimeMillis())
        dao.updateCategory(updated)
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.SYSTEM, "Kategori diubah", "${category.name} → $trimmed", "USER", LocalDate.now().toEpochDay(), accountId = activeId))
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = "Rename kategori budget", beforeJson = "{\"categoryId\":$categoryId,\"name\":\"${category.name}\"}", afterJson = "{\"categoryId\":$categoryId,\"name\":\"$trimmed\"}"))
        assertInvariant()
        eventId
    }

    // ponytail: periode-isolasi rename — history (periode < current) tetap pakai kategori lama
    suspend fun renameBudgetCategoryInPeriod(periodId: Long, categoryId: Long, newName: String) = database.withTransaction {
        val trimmed = newName.trim()
        require(trimmed.isNotBlank()) { "Nama kategori wajib diisi" }
        val period = requireNotNull(dao.periodById(periodId)) { "Periode tidak ditemukan" }
        val portfolio = requireNotNull(dao.allPortfolios().firstOrNull { it.id == period.portfolioId }) { "Portfolio tidak ditemukan" }
        val activeId = activeAccountId()
        require(portfolio.accountId == activeId) { "Portfolio bukan milik akun aktif" }
        require(!portfolio.isArchived) { "Portfolio sudah diarsip" }
        require(period.status != PeriodStatus.CLOSED) { "Periode sudah tertutup" }
        val oldCategory = requireNotNull(dao.categoryById(categoryId)) { "Kategori tidak ditemukan" }
        require(oldCategory.direction == TransactionDirection.EXPENSE) { "Hanya kategori budget yang dapat diubah" }
        if (oldCategory.accountId != null) require(oldCategory.accountId == activeId) { "Kategori bukan milik akun aktif" }
        require(!oldCategory.isArchived) { "Kategori sudah diarsipkan" }
        require(!oldCategory.name.equals(trimmed, ignoreCase = true)) { "Tidak ada perubahan nama" }
        val duplicate = dao.categoriesForAccount(activeId).firstOrNull { it.direction == TransactionDirection.EXPENSE && it.name.equals(trimmed, ignoreCase = true) }
        require(duplicate == null) { "Nama kategori sudah dipakai" }
        // buat kategori baru — history tetap pakai kategori lama
        val newCategoryId = dao.insertCategory(CategoryEntity(name = trimmed, direction = TransactionDirection.EXPENSE, color = oldCategory.color, icon = oldCategory.icon, accountId = oldCategory.accountId))
        val allocs = dao.allocationsForCategory(periodId, categoryId)
        require(allocs.isNotEmpty()) { "Kategori tidak ada di periode ini" }
        allocs.forEach { alloc -> dao.updateAllocation(alloc.copy(categoryId = newCategoryId).bumpRevision()) }
        // masa depan: pindahkan template ke kategori baru, history template lama tetap untuk audit tapi tidak dipakai lagi
        val oldTemplate = dao.templateForCategory(portfolio.id, categoryId)
        if (oldTemplate != null) {
            dao.deleteTemplateForCategory(portfolio.id, categoryId)
            dao.insertAllocationTemplate(PortfolioAllocationTemplateEntity(portfolioId = portfolio.id, categoryId = newCategoryId, plannedAmount = oldTemplate.plannedAmount, cashPercentage = oldTemplate.cashPercentage))
            // periode future yang sudah terlanjur dibuat (>= current) ikut pindah ke kategori baru agar konsisten
            val allPeriods = dao.periodsForPortfolio(portfolio.id)
            for (p in allPeriods) {
                if (p.startEpochDay >= period.startEpochDay && p.id != periodId) {
                    dao.allocationsForCategory(p.id, categoryId).forEach { alloc -> dao.updateAllocation(alloc.copy(categoryId = newCategoryId).bumpRevision()) }
                }
            }
        }
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.SYSTEM, "Kategori diubah (periode)", "${oldCategory.name} → $trimmed • periode $periodId", "USER", LocalDate.now().toEpochDay(), accountId = activeId))
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = "Rename kategori isolasi periode", beforeJson = "{\"periodId\":$periodId,\"oldCategoryId\":$categoryId,\"name\":\"${oldCategory.name}\"}", afterJson = "{\"periodId\":$periodId,\"newCategoryId\":$newCategoryId,\"name\":\"$trimmed\"}"))
        assertInvariant()
        newCategoryId
    }

    suspend fun removeBudgetCategoryFromPeriod(periodId: Long, categoryId: Long, note: String) = database.withTransaction {
        require(note.isNotBlank()) { "Alasan wajib diisi" }
        val period = requireNotNull(dao.periodById(periodId)) { "Periode tidak ditemukan" }
        val portfolio = requireNotNull(dao.allPortfolios().firstOrNull { it.id == period.portfolioId }) { "Portfolio tidak ditemukan" }
        val activeId = activeAccountId()
        require(portfolio.accountId == activeId) { "Portfolio bukan milik akun aktif" }
        require(!portfolio.isArchived) { "Portfolio sudah diarsip" }
        require(period.status != PeriodStatus.CLOSED) { "Periode sudah tertutup" }
        val allocations = dao.allocationsForCategory(periodId, categoryId)
        require(allocations.isNotEmpty()) { "Kategori tidak ada di periode ini" }
        // block if already spent (has splits) or overbudget
        for (allocation in allocations) {
            val available = dao.allocationAvailable(allocation.id)
            require(available >= 0) { "Kategori minus tidak dapat dihapus. Selesaikan terlebih dahulu" }
            val splits = dao.splitCountForAllocation(allocation.id)
            require(splits == 0) { "Kategori sudah terpakai untuk transaksi. Nol-kan sisa via koreksi sebelum hapus" }
        }
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.REALLOCATION, "Hapus kategori", note, "USER", LocalDate.now().toEpochDay(), accountId = activeId))
        val budgetLines = mutableListOf<BudgetJournalLineEntity>()
        for (allocation in allocations) {
            val available = dao.allocationAvailable(allocation.id)
            if (available > 0) {
                budgetLines.add(BudgetJournalLineEntity(eventId = eventId, allocationId = allocation.id, fundingChannel = allocation.fundingChannel, amount = -available, accountId = activeId))
                budgetLines.add(BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = allocation.fundingChannel, amount = available, accountId = activeId))
            }
        }
        if (budgetLines.isNotEmpty()) dao.insertBudgetLines(budgetLines)
        // delete or zero allocations: keep row if has journal lines (FK), else physical delete
        for (allocation in allocations) {
            val lineCount = dao.budgetLineCountForAllocation(allocation.id)
            if (lineCount == 0) {
                dao.deleteAllocationById(allocation.id)
            } else {
                // ponytail: logical delete — keep row with planned 0 to preserve FK history, UI hides planned==0
                dao.updateAllocation(allocation.copy(plannedAmount = 0).bumpRevision())
            }
        }
        dao.deleteTemplateForCategory(portfolio.id, categoryId)
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = note, beforeJson = "{\"periodId\":$periodId,\"categoryId\":$categoryId,\"allocations\":${allocations.size}}", afterJson = "{\"periodId\":$periodId,\"categoryId\":$categoryId,\"deleted\":true}"))
        refreshPeriodStatus(periodId)
        assertInvariant()
        eventId
    }

    suspend fun transferBookedChannel(
        sourceAllocationId: Long,
        accountId: Long,
        amount: Long,
        note: String,
    ) = database.withTransaction {
        require(amount > 0) { "Nominal harus lebih dari nol" }
        val source = requireNotNull(dao.allocationById(sourceAllocationId))
        val account = requireNotNull(dao.accountById(accountId))
        require(account.isActive) { "Akun sumber harus menjadi akun aktif" }
        val sourcePeriod = requireNotNull(dao.periodById(source.periodId))
        val sourcePortfolio = requireNotNull(dao.allPortfolios().firstOrNull { it.id == sourcePeriod.portfolioId })
        require(sourcePortfolio.accountId == accountId) { "Kategori budget tidak berada pada akun aktif" }
        require(dao.accountBalance(accountId, source.fundingChannel) >= amount) { "Saldo kanal sumber tidak mencukupi" }
        require(dao.allocationAvailable(sourceAllocationId) >= amount) { "Sisa kategori tidak mencukupi" }
        val targetChannel = if (source.fundingChannel == FundingChannel.CASH) FundingChannel.EBUDGET else FundingChannel.CASH
        val target = dao.allocationFor(source.periodId, source.categoryId, targetChannel)
            ?: AllocationEntity(
                id = dao.insertAllocation(AllocationEntity(
                    periodId = source.periodId,
                    categoryId = source.categoryId,
                    fundingChannel = targetChannel,
                    plannedAmount = 0,
                )),
                periodId = source.periodId,
                categoryId = source.categoryId,
                fundingChannel = targetChannel,
                plannedAmount = 0,
            )
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(
            id = eventId,
            type = LedgerType.CHANNEL_TRANSFER,
            title = if (source.fundingChannel == FundingChannel.EBUDGET) "Penarikan eBudget ke Cash" else "Setor Cash ke eBudget",
            note = note,
            source = "USER",
            effectiveEpochDay = LocalDate.now().toEpochDay(),
            accountId = accountId,
        ))
        dao.insertCashLines(listOf(
            CashJournalLineEntity(eventId = eventId, accountId = accountId, fundingChannel = source.fundingChannel, amount = -amount),
            CashJournalLineEntity(eventId = eventId, accountId = accountId, fundingChannel = targetChannel, amount = amount),
        ))
        dao.insertBudgetLines(listOf(
            BudgetJournalLineEntity(eventId = eventId, allocationId = source.id, fundingChannel = source.fundingChannel, amount = -amount, accountId = accountId),
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.EXTERNAL, fundingChannel = source.fundingChannel, amount = amount, accountId = accountId),
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.EXTERNAL, fundingChannel = target.fundingChannel, amount = -amount, accountId = accountId),
            BudgetJournalLineEntity(eventId = eventId, allocationId = target.id, fundingChannel = target.fundingChannel, amount = amount, accountId = accountId),
        ))
        dao.insertAudit(AuditSnapshotEntity(
            eventId = eventId,
            reason = "Konversi dana terbooking antar kanal",
            beforeJson = "{\"sourceAllocation\":${source.id},\"sourceAvailable\":${dao.allocationAvailable(source.id) + amount}}",
            afterJson = "{\"targetAllocation\":${target.id},\"amount\":$amount}",
        ))
        refreshPeriodStatus(source.periodId)
        assertInvariant()
        eventId
    }

    suspend fun reverseEvent(originalEventId: String, reason: String) = database.withTransaction {
        val original = requireNotNull(dao.eventById(originalEventId))
        val debtPayment = dao.debtEntryForEvent(originalEventId)
        debtPayment?.let { payment ->
            require(payment.type in setOf(DebtEntryType.PAYMENT, DebtEntryType.SETTLEMENT)) {
                "Riwayat hutang tidak dapat direversal dari event ini"
            }
            require(dao.debtEntries(payment.debtId).lastOrNull()?.eventId == originalEventId) {
                "Hanya pembayaran hutang terakhir yang dapat dibatalkan"
            }
        }
        requireActiveAccount(original.accountId)
        require(!dao.isEventReversed(originalEventId)) { "Event sudah dibalik" }
        require(original.type != LedgerType.REVERSAL) { "Reversal tidak dapat dibalik langsung" }
        require(original.type !in setOf(LedgerType.ARCHIVE, LedgerType.RESTORE)) { "Gunakan tindakan Pulihkan atau Arsipkan dari halaman terkait" }
        val eventId = UUID.randomUUID().toString()
        val reversalAccountId = original.accountId
        dao.insertEvent(ActivityEventEntity(
            id = eventId,
            type = LedgerType.REVERSAL,
            title = original.title,
            note = reason,
            source = "USER",
            effectiveEpochDay = LocalDate.now().toEpochDay(),
            relatedEventId = originalEventId,
            accountId = reversalAccountId,
        ))
        val cash = dao.cashLinesForEvent(originalEventId).map { CashJournalLineEntity(eventId = eventId, accountId = it.accountId, fundingChannel = it.fundingChannel, amount = -it.amount) }
        val budget = dao.budgetLinesForEvent(originalEventId).map { BudgetJournalLineEntity(eventId = eventId, allocationId = it.allocationId, bucket = it.bucket, fundingChannel = it.fundingChannel, amount = -it.amount, accountId = it.accountId) }
        if (cash.isNotEmpty()) dao.insertCashLines(cash)
        if (budget.isNotEmpty()) dao.insertBudgetLines(budget)
        val affectedPeriods = budget.mapNotNull { it.allocationId }.mapNotNull { dao.allocationById(it)?.periodId }.distinct()
        for (periodId in affectedPeriods) refreshPeriodStatus(periodId)
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = reason, beforeJson = "{\"event\":\"$originalEventId\"}", afterJson = "{\"reversedBy\":\"$eventId\"}"))
        debtPayment?.let { payment ->
            val debt = requireNotNull(dao.debtById(payment.debtId)) { "Hutang pembayaran tidak ditemukan" }
            dao.updateDebt(debt.copy(
                principalOutstanding = Math.addExact(debt.principalOutstanding, payment.principalAmount),
                interestOutstanding = Math.addExact(debt.interestOutstanding, payment.interestAmount),
            interestAnchorEpochDay = LocalDate.now().toEpochDay(),
                status = DebtStatus.OPEN,
            ).bumpRevision())
            dao.insertDebtEntry(DebtEntryEntity(
                debtId = debt.id,
                accountId = debt.accountId,
                eventId = eventId,
                type = DebtEntryType.REVERSAL,
                principalAmount = -payment.principalAmount,
                interestAmount = -payment.interestAmount,
                fundingSource = payment.fundingSource,
                effectiveEpochDay = LocalDate.now().toEpochDay(),
                note = reason,
            ))
        }
        assertInvariant()
        eventId
    }

    suspend fun restoreReversedEvent(reversedEventId: String) = database.withTransaction {
        val original = requireNotNull(dao.eventById(reversedEventId)) { "Event tidak ditemukan" }
        requireActiveAccount(original.accountId)
        require(dao.isEventReversed(reversedEventId)) { "Event belum dibalik" }
        require(original.type != LedgerType.REVERSAL) { "Reversal tidak dapat dipulihkan" }
        require(original.type !in setOf(LedgerType.ARCHIVE, LedgerType.RESTORE)) { "Gunakan tindakan Pulihkan atau Arsipkan dari halaman terkait" }
        require(!dao.isEventRestored(reversedEventId)) { "Event sudah dipulihkan sebelumnya" }
        val reversalEvent = requireNotNull(dao.reversalEventForEvent(reversedEventId)) { "Event reversal tidak ditemukan" }
        val sevenDays = 7L * 24 * 60 * 60 * 1000
        require(System.currentTimeMillis() - reversalEvent.createdAt <= sevenDays) { "Periode pemulihan 7 hari telah berakhir" }
        val restoreId = UUID.randomUUID().toString()
        dao.insertEvent(
            ActivityEventEntity(
                id = restoreId,
                type = LedgerType.RESTORE_REVERSAL,
                title = original.title,
                note = original.note,
                source = "USER",
                effectiveEpochDay = LocalDate.now().toEpochDay(),
                relatedEventId = reversedEventId,
                accountId = original.accountId,
            ),
        )
        val cash = dao.cashLinesForEvent(reversedEventId)
        val budget = dao.budgetLinesForEvent(reversedEventId)
        val splits = dao.splitsForEvent(reversedEventId)
        if (cash.isNotEmpty()) dao.insertCashLines(cash.map { it.copy(id = 0, eventId = restoreId) })
        if (budget.isNotEmpty()) dao.insertBudgetLines(budget.map { it.copy(id = 0, eventId = restoreId) })
        if (splits.isNotEmpty()) dao.insertSplits(splits.map { it.copy(id = 0, eventId = restoreId) })
        dao.insertAudit(AuditSnapshotEntity(eventId = restoreId, reason = "Pemulihan reversal", beforeJson = "{\"eventId\":\"$reversedEventId\",\"reversed\":true}", afterJson = "{\"eventId\":\"${restoreId}\",\"restored\":true}"))
        dao.debtEntryForEvent(reversedEventId)?.let { payment ->
            val debt = requireNotNull(dao.debtById(payment.debtId)) { "Hutang pembayaran tidak ditemukan" }
            require(dao.debtEntries(debt.id).lastOrNull()?.eventId == reversalEvent.id) {
                "Pembayaran hutang telah berubah setelah reversal"
            }
            val currentInterest = DebtCalculator.currentInterest(debt, LocalDate.now())
            require(debt.principalOutstanding >= payment.principalAmount && currentInterest >= payment.interestAmount) {
                "Saldo hutang tidak cukup untuk memulihkan pembayaran"
            }
            val nextPrincipal = debt.principalOutstanding - payment.principalAmount
            val nextInterest = currentInterest - payment.interestAmount
            dao.updateDebt(debt.copy(
                principalOutstanding = nextPrincipal,
                interestOutstanding = nextInterest,
                interestAnchorEpochDay = LocalDate.now().toEpochDay(),
                status = if (nextPrincipal == 0L && nextInterest == 0L) DebtStatus.SETTLED else DebtStatus.OPEN,
            ).bumpRevision())
            dao.insertDebtEntry(DebtEntryEntity(
                debtId = debt.id,
                accountId = debt.accountId,
                eventId = restoreId,
                type = payment.type,
                principalAmount = payment.principalAmount,
                interestAmount = payment.interestAmount,
                fundingSource = payment.fundingSource,
                effectiveEpochDay = LocalDate.now().toEpochDay(),
                note = "Pemulihan reversal pembayaran",
            ))
        }
        budget.mapNotNull { it.allocationId }.mapNotNull { dao.allocationById(it)?.periodId }.distinct()
            .forEach { refreshPeriodStatus(it) }
        assertInvariant()
        restoreId
    }

    suspend fun purgeExpiredReversalReceipts() {
        val deadline = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val expired = dao.receiptsForReversedEvents(deadline)
        for (receipt in expired) {
            val accountId = dao.eventById(receipt.eventId)?.accountId ?: continue
            if (!teamAccessGuard.allows(accountId, TeamCapability.WRITE)) continue
            val file = receipt.localPath?.let { java.io.File(it) }
            if (file == null || !file.exists() || file.delete()) dao.clearReceiptLocalPath(receipt.id)
        }
    }

    suspend fun correctEvent(
        originalEventId: String,
        correctedTitle: String,
        correctedNote: String,
        reason: String,
    ) = database.withTransaction {
        require(correctedTitle.isNotBlank()) { "Judul koreksi wajib diisi" }
        require(reason.isNotBlank()) { "Alasan koreksi wajib diisi" }
        val original = requireNotNull(dao.eventById(originalEventId)) { "Event asli tidak ditemukan" }
        require(dao.debtEntryForEvent(originalEventId) == null) { "Pembayaran hutang tidak dapat dikoreksi. Gunakan reversal lalu catat ulang." }
        requireActiveAccount(original.accountId)
        require(original.type in setOf(LedgerType.INCOME, LedgerType.EXPENSE, LedgerType.UNEXPECTED_EXPENSE, LedgerType.OPENING_BALANCE)) {
            "Jenis event ini tidak dapat dikoreksi dari form transaksi"
        }
        require(!dao.isEventReversed(originalEventId)) { "Event sudah dibalik" }
        val correlationId = UUID.randomUUID().toString()
        val reversalId = UUID.randomUUID().toString()
        val replacementId = UUID.randomUUID().toString()
        dao.insertEvent(
            ActivityEventEntity(
                id = correlationId,
                type = LedgerType.CORRECTION,
                title = "Koreksi: ${original.title}",
                note = reason,
                source = "USER",
                effectiveEpochDay = LocalDate.now().toEpochDay(),
                relatedEventId = originalEventId,
                accountId = original.accountId,
            ),
        )
        dao.insertEvent(
            ActivityEventEntity(
                id = reversalId,
                type = LedgerType.REVERSAL,
                title = "Pembalikan untuk koreksi: ${original.title}",
                note = reason,
                source = "USER",
                effectiveEpochDay = LocalDate.now().toEpochDay(),
                relatedEventId = originalEventId,
                accountId = original.accountId,
            ),
        )
        dao.insertEvent(
            original.copy(
                id = replacementId,
                title = correctedTitle.trim(),
                note = correctedNote.trim(),
                createdAt = System.currentTimeMillis(),
                relatedEventId = correlationId,
                reversedByEventId = null,
            ),
        )
        val originalCash = dao.cashLinesForEvent(originalEventId)
        val originalBudget = dao.budgetLinesForEvent(originalEventId)
        val originalSplits = dao.splitsForEvent(originalEventId)
        if (originalCash.isNotEmpty()) {
            dao.insertCashLines(originalCash.map { it.copy(id = 0, eventId = reversalId, amount = -it.amount) })
            dao.insertCashLines(originalCash.map { it.copy(id = 0, eventId = replacementId) })
        }
        if (originalBudget.isNotEmpty()) {
            dao.insertBudgetLines(originalBudget.map { it.copy(id = 0, eventId = reversalId, amount = -it.amount) })
            dao.insertBudgetLines(originalBudget.map { it.copy(id = 0, eventId = replacementId) })
        }
        if (originalSplits.isNotEmpty()) {
            dao.insertSplits(originalSplits.map { it.copy(id = 0, eventId = replacementId) })
        }
        dao.insertAudit(
            AuditSnapshotEntity(
                eventId = correlationId,
                reason = reason,
                beforeJson = "{\"eventId\":\"$originalEventId\",\"title\":\"${original.title}\"}",
                afterJson = "{\"eventId\":\"$replacementId\",\"title\":\"${correctedTitle.trim()}\"}",
            ),
        )
        dao.insertAudit(AuditSnapshotEntity(eventId = reversalId, reason = reason, beforeJson = "{\"eventId\":\"$originalEventId\"}", afterJson = "{\"reversedBy\":\"$reversalId\"}"))
        dao.insertAudit(AuditSnapshotEntity(eventId = replacementId, reason = reason, beforeJson = "{\"eventId\":\"$originalEventId\"}", afterJson = "{\"replacementEventId\":\"$replacementId\"}"))
        originalBudget.mapNotNull { it.allocationId }.mapNotNull { dao.allocationById(it)?.periodId }.distinct()
            .forEach { refreshPeriodStatus(it) }
        assertInvariant()
        replacementId
    }

    suspend fun addRecurringRule(rule: RecurringRuleEntity) = database.withTransaction {
        require(rule.amount > 0)
        requireActiveAccount(rule.accountId)
        rule.allocationId?.let { allocationId ->
            require(dao.portfolioForAllocation(allocationId)?.accountId == rule.accountId) {
                "Kategori jadwal bukan milik akun aktif"
            }
        }
        dao.insertRule(rule)
    }

    suspend fun pauseRecurringRule(ruleId: String, reason: String = "Jadwal transaksi dihentikan pengguna") = database.withTransaction {
        require(reason.isNotBlank()) { "Alasan wajib diisi" }
        val rule = requireNotNull(dao.allRules().firstOrNull { it.id == ruleId })
        requireActiveAccount(rule.accountId)
        if (rule.isPaused) return@withTransaction
        dao.updateRule(rule.copy(isPaused = true).bumpRevision())
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.SYSTEM, "Jadwal transaksi dihentikan", reason, "USER", LocalDate.now().toEpochDay(), accountId = rule.accountId))
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = reason, beforeJson = "{\"ruleId\":\"$ruleId\",\"paused\":false}", afterJson = "{\"ruleId\":\"$ruleId\",\"paused\":true}"))
        assertInvariant()
    }

    suspend fun resumeRecurringRule(
        ruleId: String,
        fromToday: Boolean,
        reason: String,
    ) = database.withTransaction {
        require(reason.isNotBlank()) { "Alasan wajib diisi" }
        val rule = requireNotNull(dao.allRules().firstOrNull { it.id == ruleId })
        requireActiveAccount(rule.accountId)
        require(rule.isPaused) { "Jadwal sudah aktif" }
        require(!rule.pausedByArchive) { "Pulihkan portfolio terkait sebelum melanjutkan jadwal ini" }
        val today = LocalDate.now()
        val start = LocalDate.ofEpochDay(rule.startEpochDay)
        val next = if (fromToday) {
            ScheduleCalculator.firstAfter(start, today.minusDays(1), rule.cadence, rule.intervalCount)
        } else {
            LocalDate.ofEpochDay(rule.nextEpochDay)
        }
        val end = rule.endEpochDay?.let(LocalDate::ofEpochDay)
        require(end == null || !next.isAfter(end)) { "Jadwal sudah melewati tanggal akhir" }
        dao.updateRule(rule.copy(nextEpochDay = next.toEpochDay(), isPaused = false).bumpRevision())
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(
            ActivityEventEntity(
                eventId,
                LedgerType.SYSTEM,
                "Jadwal transaksi dilanjutkan",
                reason,
                "USER",
                today.toEpochDay(),
                accountId = rule.accountId,
            ),
        )
        dao.insertAudit(
            AuditSnapshotEntity(
                eventId = eventId,
                reason = reason,
                beforeJson = "{\"ruleId\":\"$ruleId\",\"paused\":true,\"nextEpochDay\":${rule.nextEpochDay}}",
                afterJson = "{\"ruleId\":\"$ruleId\",\"paused\":false,\"nextEpochDay\":${next.toEpochDay()},\"fromToday\":$fromToday}",
            ),
        )
        assertInvariant()
    }

    suspend fun pausePortfolio(portfolioId: Long, reason: String = "Portfolio dijeda pengguna") = database.withTransaction {
        val portfolio = requireNotNull(dao.allPortfolios().firstOrNull { it.id == portfolioId })
        requireActiveAccount(portfolio.accountId)
        require(!portfolio.isArchived) { "Pulihkan portfolio sebelum menjedanya" }
        if (portfolio.isPaused) return@withTransaction
        dao.updatePortfolio(portfolio.copy(isPaused = true).bumpRevision())
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.SYSTEM, "Portfolio dijeda", reason, "USER", LocalDate.now().toEpochDay(), accountId = portfolio.accountId))
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = reason, beforeJson = "{\"portfolioId\":$portfolioId,\"paused\":false}", afterJson = "{\"portfolioId\":$portfolioId,\"paused\":true}"))
        assertInvariant()
    }

    suspend fun resumePortfolio(portfolioId: Long, reason: String) {
        database.withTransaction {
            require(reason.isNotBlank()) { "Alasan wajib diisi" }
            val portfolio = requireNotNull(dao.allPortfolios().firstOrNull { it.id == portfolioId })
            requireActiveAccount(portfolio.accountId)
            require(!portfolio.isArchived) { "Pulihkan portfolio terlebih dahulu" }
            if (!portfolio.isPaused) return@withTransaction
            val eventId = UUID.randomUUID().toString()
            dao.updatePortfolio(portfolio.copy(isPaused = false).bumpRevision())
            val resumedRuleCount = resumeRulesPausedByArchive(portfolioId, LocalDate.now())
            dao.insertEvent(ActivityEventEntity(eventId, LedgerType.RESTORE, "Portfolio dilanjutkan", reason, "USER", LocalDate.now().toEpochDay(), accountId = portfolio.accountId))
            dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = reason, beforeJson = "{\"portfolioId\":$portfolioId,\"paused\":true}", afterJson = "{\"portfolioId\":$portfolioId,\"paused\":false,\"resumedRules\":$resumedRuleCount}"))
            assertInvariant()
        }
        reconcilePortfolios()
    }

    suspend fun setPortfolioRollover(portfolioId: Long, enabled: Boolean, reason: String = "Pengaturan rollover diubah pengguna") = database.withTransaction {
        val portfolio = requireNotNull(dao.allPortfolios().firstOrNull { it.id == portfolioId })
        requireActiveAccount(portfolio.accountId)
        require(!portfolio.isArchived) { "Pulihkan portfolio terlebih dahulu" }
        if (portfolio.rolloverEnabled == enabled) return@withTransaction
        dao.updatePortfolio(portfolio.copy(rolloverEnabled = enabled).bumpRevision())
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.SYSTEM, "Rollover ${if (enabled) "diaktifkan" else "dinonaktifkan"}: ${portfolio.name}", reason, "USER", LocalDate.now().toEpochDay(), accountId = portfolio.accountId))
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = reason, beforeJson = "{\"portfolioId\":$portfolioId,\"rollover\":${!enabled}}", afterJson = "{\"portfolioId\":$portfolioId,\"rollover\":$enabled}"))
        assertInvariant()
    }

    suspend fun archivePortfolio(portfolioId: Long, reason: String) = database.withTransaction {
        require(reason.isNotBlank()) { "Alasan wajib diisi" }
        val portfolio = requireNotNull(dao.allPortfolios().firstOrNull { it.id == portfolioId })
        requireActiveAccount(portfolio.accountId)
        require(!portfolio.isArchived) { "Portfolio sudah diarsipkan" }
        val periods = dao.periodsForPortfolio(portfolioId)
        val allocations = periods.flatMap { dao.allocationsForPeriod(it.id) }
        val available = allocations.map { it to dao.allocationAvailable(it.id) }
        require(available.none { it.second < 0 }) { "Selesaikan seluruh kategori minus sebelum mengarsipkan" }

        val activeId = portfolio.accountId
        val eventId = UUID.randomUUID().toString()
        val positive = available.filter { it.second > 0 }
        val releasedByChannel = positive.groupBy { it.first.fundingChannel }
            .mapValues { (_, rows) -> rows.sumOf { it.second } }
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.ARCHIVE, "Portfolio diarsipkan", reason, "USER", LocalDate.now().toEpochDay(), accountId = activeId))
        if (positive.isNotEmpty()) {
            dao.insertBudgetLines(
                positive.map { (allocation, amount) ->
                    BudgetJournalLineEntity(eventId = eventId, allocationId = allocation.id, fundingChannel = allocation.fundingChannel, amount = -amount, accountId = activeId)
                } + releasedByChannel.map { (channel, amount) ->
                    BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = channel, amount = amount, accountId = activeId)
                },
            )
        }
        periods.filter { it.status != PeriodStatus.CLOSED }.forEach { dao.updatePeriod(it.copy(status = PeriodStatus.CLOSED).bumpRevision()) }

        val allocationIds = allocations.map { it.id }.toSet()
        var pausedRuleCount = 0
        dao.allRules().filter { it.allocationId in allocationIds }.forEach { rule ->
            if (!rule.isPaused) {
                dao.updateRule(rule.copy(isPaused = true, pausedByArchive = true).bumpRevision())
                pausedRuleCount++
            }
        }
        val archivedAt = System.currentTimeMillis()
        dao.updatePortfolio(portfolio.copy(isPaused = true, isArchived = true, archivedAt = archivedAt).bumpRevision())
        dao.insertAudit(AuditSnapshotEntity(
            eventId = eventId,
            reason = reason,
            beforeJson = "{\"portfolioId\":$portfolioId,\"archived\":false}",
            afterJson = "{\"portfolioId\":$portfolioId,\"archived\":true,\"archivedAt\":$archivedAt,\"releasedCash\":${releasedByChannel[FundingChannel.CASH] ?: 0},\"releasedEBudget\":${releasedByChannel[FundingChannel.EBUDGET] ?: 0},\"pausedRules\":$pausedRuleCount}",
        ))
        assertInvariant()
    }

    suspend fun restorePortfolio(portfolioId: Long, activate: Boolean, reason: String) {
        database.withTransaction {
            require(reason.isNotBlank()) { "Alasan wajib diisi" }
            val portfolio = requireNotNull(dao.allPortfolios().firstOrNull { it.id == portfolioId })
            requireActiveAccount(portfolio.accountId)
            require(portfolio.isArchived) { "Portfolio tidak berada di arsip" }
            val eventId = UUID.randomUUID().toString()
            dao.updatePortfolio(portfolio.copy(isArchived = false, archivedAt = null, isPaused = !activate).bumpRevision())
            val resumedRuleCount = if (activate) resumeRulesPausedByArchive(portfolioId, LocalDate.now()) else 0
            dao.insertEvent(ActivityEventEntity(eventId, LedgerType.RESTORE, if (activate) "Portfolio dipulihkan dan diaktifkan" else "Portfolio dipulihkan", reason, "USER", LocalDate.now().toEpochDay(), accountId = portfolio.accountId))
            dao.insertAudit(AuditSnapshotEntity(
                eventId = eventId,
                reason = reason,
                beforeJson = "{\"portfolioId\":$portfolioId,\"archived\":true}",
                afterJson = "{\"portfolioId\":$portfolioId,\"archived\":false,\"paused\":${!activate},\"resumedRules\":$resumedRuleCount}",
            ))
            assertInvariant()
        }
        if (activate) reconcilePortfolios()
    }

    private suspend fun resumeRulesPausedByArchive(portfolioId: Long, today: LocalDate): Int {
        var resumedRuleCount = 0
        dao.allRules().filter { it.pausedByArchive }.forEach { rule ->
            val allocation = rule.allocationId?.let { dao.allocationById(it) }
            val period = allocation?.let { dao.periodById(it.periodId) }
            if (period?.portfolioId == portfolioId) {
                val next = ScheduleCalculator.firstAfter(LocalDate.ofEpochDay(rule.startEpochDay), today, rule.cadence, rule.intervalCount)
                val ended = rule.endEpochDay != null && next.toEpochDay() > rule.endEpochDay
                dao.updateRule(rule.copy(nextEpochDay = next.toEpochDay(), isPaused = ended, pausedByArchive = false).bumpRevision())
                if (!ended) resumedRuleCount++
            }
        }
        return resumedRuleCount
    }

    suspend fun processDueRules(today: LocalDate = LocalDate.now(), direction: String? = null) {
        database.withTransaction {
            dao.allRules()
                .filter { !it.isPaused && (direction == null || it.direction == direction) && it.endEpochDay != null && it.nextEpochDay > it.endEpochDay }
                .filter { teamAccessGuard.allows(it.accountId, TeamCapability.WRITE) }
                .forEach { dao.updateRule(it.copy(isPaused = true).bumpRevision()) }
        }
        repeat(500) {
            val next = dao.dueRules(today.toEpochDay(), direction)
                .firstOrNull { teamAccessGuard.allows(it.accountId, TeamCapability.WRITE) } ?: return
            database.withTransaction {
                if (next.endEpochDay != null && next.nextEpochDay > next.endEpochDay) {
                    dao.updateRule(next.copy(isPaused = true).bumpRevision())
                    return@withTransaction
                }
                if (dao.occurrenceExists(next.id, next.nextEpochDay)) {
                    dao.updateRule(advanceRule(next).bumpRevision())
                    return@withTransaction
                }
                val dueDate = LocalDate.ofEpochDay(next.nextEpochDay)
                val eventId = if (next.direction == TransactionDirection.INCOME) {
                    postIncomeInternal(next.accountId, next.fundingChannel, next.amount, next.categoryId, next.title, "Dibuat otomatis", dueDate, LedgerType.AUTOMATION, "SYSTEM", next.allocationId)
                } else {
                    postExpenseInternal(next.accountId, next.fundingChannel, next.amount, listOf(ExpenseSplitInput(next.categoryId, next.allocationId, next.amount)), next.title, "Dibuat otomatis", dueDate, LedgerType.AUTOMATION, "SYSTEM")
                }
                dao.insertOccurrence(RecurringOccurrenceEntity(ruleId = next.id, dueEpochDay = next.nextEpochDay, eventId = eventId))
                dao.updateRule(advanceRule(next).bumpRevision())
                assertInvariant()
            }
        }
    }

    suspend fun reconcilePortfolios(today: LocalDate = LocalDate.now()) = database.withTransaction {
        val activeId = activeAccountId()
        dao.allPortfolios().filter { it.accountId == activeId && !it.isPaused && !it.isArchived }.forEach { portfolio ->
            val periods = dao.periodsForPortfolio(portfolio.id)
            val current = periods.firstOrNull { it.status != PeriodStatus.CLOSED && today.toEpochDay() in it.startEpochDay..it.endEpochDay }
            val firstPeriod = periods.minByOrNull { it.startEpochDay }
            if (firstPeriod != null && today.isBefore(LocalDate.ofEpochDay(firstPeriod.startEpochDay))) return@forEach
            closeStalePeriods(periods, portfolio, today, activeId)
            if (current != null) {
                val previous = periods.filter { it.endEpochDay < current.startEpochDay }.maxByOrNull { it.endEpochDay }
                val previousHasDeficit = previous?.let { period -> dao.allocationIdsForPeriod(period.id).any { dao.allocationAvailable(it) < 0 } } == true
                if ((current.status == PeriodStatus.DRAFT || current.status == PeriodStatus.UNDERFUNDED) && !previousHasDeficit && canFundPeriod(current.id)) {
                    fundUnderfundedPeriod(current.id)
                }
                return@forEach
            }
            val previous = periods.maxByOrNull { it.endEpochDay }
            if (previous != null) refreshPeriodStatus(previous.id)
            val unresolved = previous?.let { period -> dao.allocationIdsForPeriod(period.id).any { dao.allocationAvailable(it) < 0 } } == true
            val nextAnchor = previous?.let { LocalDate.ofEpochDay(it.endEpochDay).plusDays(1) }
                ?: LocalDate.ofEpochDay(portfolio.startEpochDay)
            val (start, end) = periodBounds(nextAnchor, portfolio.cadence, portfolio.intervalCount)
            if (portfolio.endValue != null && start.toEpochDay() > portfolio.endValue) {
                previous?.let { dao.updatePeriod(it.copy(status = PeriodStatus.CLOSED).bumpRevision()) }
                return@forEach
            }
            val nextId = dao.insertPeriod(BudgetPeriodEntity(
                portfolioId = portfolio.id,
                startEpochDay = start.toEpochDay(),
                endEpochDay = end.toEpochDay(),
                status = if (unresolved) PeriodStatus.DRAFT else PeriodStatus.UNDERFUNDED,
            ))
            val nextAllocations = mutableMapOf<Pair<Long, String>, Long>()
            dao.templatesForPortfolio(portfolio.id).forEach { template ->
                val cash = template.plannedAmount * template.cashPercentage / 100
                val eBudget = template.plannedAmount - cash
                if (cash > 0) nextAllocations[template.categoryId to FundingChannel.CASH] = dao.insertAllocation(AllocationEntity(periodId = nextId, categoryId = template.categoryId, fundingChannel = FundingChannel.CASH, plannedAmount = cash))
                if (eBudget > 0) nextAllocations[template.categoryId to FundingChannel.EBUDGET] = dao.insertAllocation(AllocationEntity(periodId = nextId, categoryId = template.categoryId, fundingChannel = FundingChannel.EBUDGET, plannedAmount = eBudget))
            }
            if (previous != null) {
                dao.allRules().filter { it.allocationId != null }.forEach { rule ->
                    val oldAllocation = rule.allocationId?.let { dao.allocationById(it) }
                    if (oldAllocation?.periodId == previous.id) {
                        val nextAllocationId = nextAllocations[oldAllocation.categoryId to oldAllocation.fundingChannel]
                        if (nextAllocationId != null) dao.updateRule(rule.copy(allocationId = nextAllocationId).bumpRevision())
                    }
                }
            }
            if (unresolved) return@forEach
            if (canFundPeriod(nextId)) fundUnderfundedPeriod(nextId)
        }
        assertInvariant()
    }

    private suspend fun closePreviousPeriod(previous: BudgetPeriodEntity?, portfolio: PortfolioEntity, today: LocalDate, activeId: Long) {
        if (previous == null || previous.status == PeriodStatus.CLOSED) return
        val hasDeficit = dao.allocationIdsForPeriod(previous.id).any { dao.allocationAvailable(it) < 0 }
        if (hasDeficit) return
        val remaining = dao.allocationsForPeriod(previous.id).mapNotNull { allocation ->
            dao.allocationAvailable(allocation.id).takeIf { it > 0 }?.let { allocation to it }
        }
        if (remaining.isNotEmpty()) {
            val eventId = UUID.randomUUID().toString()
            val rollover = portfolio.rolloverEnabled
            dao.insertEvent(ActivityEventEntity(
                eventId,
                if (rollover) LedgerType.ROLLOVER else LedgerType.RELEASE,
                if (rollover) "Rollover ${portfolio.name}" else "Pelepasan sisa ${portfolio.name}",
                "Penutupan periode",
                "SYSTEM",
                today.toEpochDay(),
                accountId = activeId,
            ))
            dao.insertBudgetLines(remaining.flatMap { (allocation, value) ->
                listOf(
                    BudgetJournalLineEntity(eventId = eventId, allocationId = allocation.id, fundingChannel = allocation.fundingChannel, amount = -value, accountId = activeId),
                    if (rollover) BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.ROLLOVER, fundingChannel = allocation.fundingChannel, amount = value, accountId = activeId)
                    else BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = allocation.fundingChannel, amount = value, accountId = activeId),
                )
            })
            dao.insertAudit(AuditSnapshotEntity(
                eventId = eventId,
                reason = if (rollover) "Sisa dibawa ke periode baru" else "Sisa kembali ke Main Vault",
                beforeJson = "{\"periodId\":${previous.id}}",
                afterJson = "{\"periodId\":${previous.id},\"status\":\"CLOSED\"}",
            ))
        }
        dao.updatePeriod(previous.copy(status = PeriodStatus.CLOSED).bumpRevision())
    }

    private suspend fun closeStalePeriods(periods: List<BudgetPeriodEntity>, portfolio: PortfolioEntity, today: LocalDate, activeId: Long) {
        periods.filter { it.status != PeriodStatus.CLOSED && it.endEpochDay < today.toEpochDay() }
            .sortedBy { it.startEpochDay }
            .forEach { closePreviousPeriod(it, portfolio, today, activeId) }
    }

    private suspend fun canFundPeriod(periodId: Long): Boolean {
        val period = requireNotNull(dao.periodById(periodId))
        val portfolio = requireNotNull(dao.allPortfolios().firstOrNull { it.id == period.portfolioId })
        val needs = dao.allocationsForPeriod(periodId).groupBy { it.fundingChannel }.mapValues { (_, allocations) ->
            allocations.sumOf { (it.plannedAmount - dao.allocationAvailable(it.id)).coerceAtLeast(0) }
        }
        return needs.all { (channel, amount) -> dao.vaultBalance(channel, portfolio.accountId) >= amount }
    }

    private fun advanceRule(rule: RecurringRuleEntity): RecurringRuleEntity {
        val current = LocalDate.ofEpochDay(rule.nextEpochDay)
        val nextDate = ScheduleCalculator.next(current, rule.cadence, rule.anchorMonth, rule.anchorDay, rule.intervalCount)
        val remaining = rule.remainingOccurrences?.minus(1)
        val ended = remaining == 0 || (rule.endEpochDay != null && nextDate.toEpochDay() > rule.endEpochDay)
        return rule.copy(nextEpochDay = nextDate.toEpochDay(), remainingOccurrences = remaining, isPaused = ended)
    }

    private fun periodBounds(anchor: LocalDate, cadence: String, intervalCount: Int): Pair<LocalDate, LocalDate> = if (cadence == "YEARLY") {
        val start = LocalDate.of(anchor.year, 1, 1)
        start to LocalDate.of(anchor.year + intervalCount - 1, 12, 31)
    } else {
        val startMonth = YearMonth.from(anchor).atDay(1)
        val endMonth = YearMonth.from(startMonth).plusMonths(intervalCount.toLong() - 1)
        startMonth to endMonth.atEndOfMonth()
    }

    private suspend fun refreshPeriodStatus(periodId: Long) {
        val period = dao.periodById(periodId) ?: return
        if (period.status == PeriodStatus.CLOSED || period.status == PeriodStatus.UNDERFUNDED) return
        val hasNegative = dao.allocationIdsForPeriod(periodId).any { dao.allocationAvailable(it) < 0 }
        dao.updatePeriod(period.copy(status = if (hasNegative) PeriodStatus.RESOLUTION_REQUIRED else PeriodStatus.ACTIVE).bumpRevision())
    }

    private suspend fun assertInvariant() {
        ledgerPostingEngine.finalizeUnsealedEvents()
        if (dao.unbalancedLedgerEvents().isNotEmpty()) {
            throw LedgerInvariantException("General ledger memiliki event tidak seimbang")
        }
        if (dao.accountCount() > 0 && dao.activeAccountCount() != 1) {
            throw LedgerInvariantException("Harus ada tepat satu akun aktif")
        }
        val cash = dao.cashTotal()
        val available = dao.budgetAvailableTotal()
        if (cash != available) throw LedgerInvariantException("Jurnal tidak seimbang: kas=$cash, dana=$available")
        listOf(FundingChannel.CASH, FundingChannel.EBUDGET).forEach { channel ->
            val channelCash = dao.cashTotal(channel)
            val channelAvailable = dao.budgetAvailableTotal(channel)
            if (channelCash != channelAvailable) throw LedgerInvariantException("Kanal $channel tidak seimbang: kas=$channelCash, dana=$channelAvailable")
        }
        dao.allAccounts().forEach { account ->
            val accountCash = dao.accountBalance(account.id)
            val accountAvailable = dao.budgetAvailableTotal(account.id)
            if (accountCash != accountAvailable) {
                throw LedgerInvariantException("Akun ${account.id} tidak seimbang: kas=$accountCash, dana=$accountAvailable")
            }
            listOf(FundingChannel.CASH, FundingChannel.EBUDGET).forEach { channel ->
                val channelCash = dao.accountBalance(account.id, channel)
                val channelAvailable = dao.budgetAvailableTotal(account.id, channel)
                if (channelCash != channelAvailable) {
                    throw LedgerInvariantException("Akun ${account.id} kanal $channel tidak seimbang: kas=$channelCash, dana=$channelAvailable")
                }
            }
        }
        dao.allEvents().forEach { event ->
            if (dao.budgetEventTotal(event.id) != 0L) {
                throw LedgerInvariantException("Budget event ${event.id} tidak seimbang")
            }
        }
    }
}

package com.morneven.kron.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.morneven.kron.backup.BackupManager
import com.morneven.kron.automation.BudgetNotifier
import com.morneven.kron.data.AccountBalanceRow
import com.morneven.kron.data.AccountEntity
import com.morneven.kron.data.ActivityRow
import com.morneven.kron.data.AllocationBalanceRow
import com.morneven.kron.data.AllocationDraft
import com.morneven.kron.data.BudgetPeriodEntity
import com.morneven.kron.data.CashflowRow
import com.morneven.kron.data.CategoryEntity
import com.morneven.kron.data.ExpenseSplitInput
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.data.KronRepository
import com.morneven.kron.data.PortfolioEntity
import com.morneven.kron.data.RecurringRuleEntity
import com.morneven.kron.data.ScheduleCalculator
import com.morneven.kron.data.TransactionDirection
import com.morneven.kron.preferences.PrivacyPreferences
import com.morneven.kron.report.CsvExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class KronUiState(
    val accounts: List<AccountEntity> = emptyList(),
    val accountBalances: List<AccountBalanceRow> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val portfolios: List<PortfolioEntity> = emptyList(),
    val periods: List<BudgetPeriodEntity> = emptyList(),
    val allocations: List<AllocationBalanceRow> = emptyList(),
    val activities: List<ActivityRow> = emptyList(),
    val rules: List<RecurringRuleEntity> = emptyList(),
    val cashflow: CashflowRow = CashflowRow(0, 0),
    val vaultCash: Long = 0,
    val vaultEBudget: Long = 0,
    val unallocatedCash: Long = 0,
    val unallocatedEBudget: Long = 0,
    val rolloverCash: Long = 0,
    val rolloverEBudget: Long = 0,
    val valuesVisible: Boolean = false,
    val rememberVisibility: Boolean = false,
    val appLockEnabled: Boolean = false,
    val theme: String = "DARK",
    val onboardingComplete: Boolean = false,
    val authFailures: Int = 0,
    val authLockedUntil: Long = 0L,
    val message: String? = null,
) {
    val totalAssets: Long get() = accountBalances.sumOf { it.balance }
    val totalVault: Long get() = vaultCash + vaultEBudget
    val bookedCash: Long get() = allocations.filter { it.fundingChannel == FundingChannel.CASH }.sumOf { it.bookedAmount }
    val bookedEBudget: Long get() = allocations.filter { it.fundingChannel == FundingChannel.EBUDGET }.sumOf { it.bookedAmount }
    val unresolvedTotal: Long get() = unallocatedCash + unallocatedEBudget
}

    private data class LedgerSlice(
    val balances: List<AccountBalanceRow>,
    val allocations: List<AllocationBalanceRow>,
    val activities: List<ActivityRow>,
    val rules: List<RecurringRuleEntity>,
    val vaults: Map<String, Long>,
    val rollover: Map<String, Long>,
)

private data class MetadataSlice(
    val accounts: List<AccountEntity>,
    val categories: List<CategoryEntity>,
    val portfolios: List<PortfolioEntity>,
    val periods: List<BudgetPeriodEntity>,
    val unallocated: Map<String, Long>,
)

private data class PreferenceSlice(
    val visible: Boolean,
    val remember: Boolean,
    val appLock: Boolean,
    val theme: String,
    val onboarding: Boolean,
    val authFailures: Int = 0,
    val authLockedUntil: Long = 0L,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: KronRepository,
    private val preferences: PrivacyPreferences,
    private val backupManager: BackupManager,
    private val budgetNotifier: BudgetNotifier,
    private val csvExporter: CsvExporter,
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)
    private val sessionVisibility = MutableStateFlow<Boolean?>(null)

    private val month = YearMonth.now()
    private val cashflow = repository.cashflow(month.atDay(1), month.atEndOfMonth())

    private val ledger = combine(
        repository.accountBalances,
        repository.allocations,
        repository.activities,
        repository.rules,
        repository.vaultByChannel,
    ) { balances, allocations, activities, rules, vaults ->
        LedgerSlice(balances, allocations, activities, rules, vaults.associate { it.fundingChannel to it.balance }, emptyMap())
    }.combine(repository.rolloverByChannel) { slice, rollover ->
        slice.copy(rollover = rollover.associate { it.fundingChannel to it.balance })
    }

    private val metadata = combine(
        repository.accounts,
        repository.categories,
        repository.portfolios,
        repository.periods,
        repository.unallocatedByChannel,
    ) { accounts, categories, portfolios, periods, unallocated ->
        MetadataSlice(accounts, categories, portfolios, periods, unallocated.associate { it.fundingChannel to it.balance })
    }

    private val visibilityPreference = combine(sessionVisibility, preferences.rememberVisibility, preferences.rememberedVisibility) { session, remember, remembered -> Triple(session, remember, remembered) }
    private val authPreference = combine(preferences.authFailures, preferences.authLockedUntil) { failures, lockedUntil -> failures to lockedUntil }
    private val preferenceState = combine(
        visibilityPreference,
        preferences.appLockEnabled,
        preferences.theme,
        authPreference,
        preferences.onboardingComplete,
    ) { visibility, appLock, theme, auth, onboarding ->
        PreferenceSlice(
            visible = visibility.first ?: visibility.third,
            remember = visibility.second,
            appLock = appLock,
            theme = theme,
            onboarding = onboarding,
            authFailures = auth.first,
            authLockedUntil = auth.second,
        )
    }

    val uiState: StateFlow<KronUiState> = combine(ledger, metadata, cashflow, preferenceState, message) { ledger, metadata, cashflow, prefs, message ->
        KronUiState(
            accounts = metadata.accounts,
            accountBalances = ledger.balances,
            categories = metadata.categories,
            portfolios = metadata.portfolios,
            periods = metadata.periods,
            allocations = ledger.allocations,
            activities = ledger.activities,
            rules = ledger.rules,
            cashflow = cashflow,
            vaultCash = ledger.vaults[FundingChannel.CASH] ?: 0,
            vaultEBudget = ledger.vaults[FundingChannel.EBUDGET] ?: 0,
            unallocatedCash = metadata.unallocated[FundingChannel.CASH] ?: 0,
            unallocatedEBudget = metadata.unallocated[FundingChannel.EBUDGET] ?: 0,
            rolloverCash = ledger.rollover[FundingChannel.CASH] ?: 0,
            rolloverEBudget = ledger.rollover[FundingChannel.EBUDGET] ?: 0,
            valuesVisible = prefs.visible,
            rememberVisibility = prefs.remember,
            appLockEnabled = prefs.appLock,
            theme = prefs.theme,
            onboardingComplete = prefs.onboarding,
            message = message,
            authFailures = prefs.authFailures,
            authLockedUntil = prefs.authLockedUntil,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KronUiState())

    init {
        viewModelScope.launch { repository.allocations.collect(budgetNotifier::sync) }
        viewModelScope.launch {
            runCatching {
                repository.seedIfNeeded()
                repository.processDueRules(direction = TransactionDirection.INCOME)
                repository.reconcilePortfolios()
                repository.processDueRules(direction = TransactionDirection.EXPENSE)
            }.onFailure { message.value = it.message ?: "Gagal menyiapkan data" }
        }
    }

    fun toggleValues() = viewModelScope.launch {
        val next = !uiState.value.valuesVisible
        sessionVisibility.value = next
        if (uiState.value.rememberVisibility) preferences.setLastVisibility(next)
    }

    fun hideValuesForLock() {
        sessionVisibility.value = false
    }

    fun recordAuthFailure() = viewModelScope.launch { preferences.recordAuthFailure() }
    fun resetAuthFailures() = viewModelScope.launch { preferences.resetAuthFailures() }

    fun restoreRememberedVisibility() = viewModelScope.launch {
        sessionVisibility.value = preferences.rememberedVisibility.first()
    }

    fun setRememberVisibility(value: Boolean) = viewModelScope.launch {
        preferences.setRememberVisibility(value)
        preferences.setLastVisibility(if (value) uiState.value.valuesVisible else false)
    }

    fun setTheme(value: String) = viewModelScope.launch { preferences.setTheme(value) }
    fun setAppLock(value: Boolean) = viewModelScope.launch { preferences.setAppLockEnabled(value) }
    fun completeOnboarding() = viewModelScope.launch { preferences.completeOnboarding() }
    fun clearMessage() { message.value = null }
    fun showMessage(value: String) { message.value = value }

    fun exportBackup(uri: Uri, password: CharArray) = runAction("Backup terenkripsi berhasil dibuat") {
        backupManager.export(uri, password)
    }

    fun stageRestore(uri: Uri, password: CharArray) = runAction("Backup tervalidasi. Tutup lalu buka kembali KRON untuk menerapkan restore") {
        backupManager.stageRestore(uri, password)
    }

    fun exportCsv(uri: Uri) = runAction("Laporan CSV berhasil dibuat") {
        val current = uiState.value
        csvExporter.export(uri, current.activities, current.allocations)
    }

    fun addAccount(name: String, type: String, channel: String, openingBalance: Long) = runAction("Akun berhasil ditambahkan") {
        repository.addAccount(name, type, channel, openingBalance)
    }

    fun addIncome(
        accountId: Long,
        amount: Long,
        categoryId: Long?,
        targetAllocationId: Long? = null,
        title: String,
        note: String,
        recurring: String? = null,
        startDate: LocalDate = LocalDate.now(),
        endDate: LocalDate? = null,
        intervalCount: Int = 1,
        recordNow: Boolean = true,
    ) = runAction("Pemasukan tercatat") {
        require(intervalCount > 0) { "Interval harus minimal 1" }
        require(endDate == null || !endDate.isBefore(startDate)) { "Tanggal akhir tidak boleh sebelum tanggal mulai" }
        if (recurring == null || recordNow) {
            repository.addIncome(accountId, amount, categoryId, title, note, targetAllocationId = targetAllocationId)
        }
        if (recurring != null) {
            val today = LocalDate.now()
            val first = if (recordNow) {
                ScheduleCalculator.firstAfter(startDate, today, recurring, intervalCount)
            } else ScheduleCalculator.firstOnOrAfter(startDate)
            if (endDate == null || !first.isAfter(endDate)) {
            repository.addRecurringRule(RecurringRuleEntity(
                id = UUID.randomUUID().toString(),
                title = title.ifBlank { "Pemasukan rutin" },
                direction = "INCOME",
                amount = amount,
                accountId = accountId,
                categoryId = categoryId,
                allocationId = targetAllocationId,
                cadence = recurring,
                intervalCount = intervalCount,
                anchorMonth = startDate.monthValue,
                anchorDay = startDate.dayOfMonth,
                startEpochDay = startDate.toEpochDay(),
                nextEpochDay = first.toEpochDay(),
                endEpochDay = endDate?.toEpochDay(),
            ))
            }
        }
    }

    fun addExpense(
        accountId: Long,
        amount: Long,
        splits: List<ExpenseSplitInput>,
        title: String,
        note: String,
        unexpected: Boolean = false,
        recurring: String? = null,
        startDate: LocalDate = LocalDate.now(),
        endDate: LocalDate? = null,
        intervalCount: Int = 1,
        recordNow: Boolean = true,
    ) = runAction("Pengeluaran tercatat") {
        require(intervalCount > 0) { "Interval harus minimal 1" }
        require(endDate == null || !endDate.isBefore(startDate)) { "Tanggal akhir tidak boleh sebelum tanggal mulai" }
        if (recurring == null || recordNow) repository.addExpense(accountId, amount, splits, title, note, unexpected)
        if (recurring != null && splits.size == 1 && !unexpected) {
            val today = LocalDate.now()
            val first = if (recordNow) {
                ScheduleCalculator.firstAfter(startDate, today, recurring, intervalCount)
            } else ScheduleCalculator.firstOnOrAfter(startDate)
            if (endDate == null || !first.isAfter(endDate)) {
            repository.addRecurringRule(RecurringRuleEntity(
                id = UUID.randomUUID().toString(),
                title = title.ifBlank { "Pengeluaran rutin" },
                direction = "EXPENSE",
                amount = amount,
                accountId = accountId,
                categoryId = splits.first().categoryId,
                allocationId = splits.first().allocationId,
                cadence = recurring,
                intervalCount = intervalCount,
                anchorMonth = startDate.monthValue,
                anchorDay = startDate.dayOfMonth,
                startEpochDay = startDate.toEpochDay(),
                nextEpochDay = first.toEpochDay(),
                endEpochDay = endDate?.toEpochDay(),
            ))
            }
        }
    }

    fun transfer(from: Long, to: Long, amount: Long, note: String) = runAction("Transfer tercatat") {
        repository.transfer(from, to, amount, note)
    }

    fun transferBookedChannel(sourceAllocationId: Long, from: Long, to: Long, amount: Long, note: String) = runAction("Komposisi dana berhasil dipindahkan") {
        repository.transferBookedChannel(sourceAllocationId, from, to, amount, note)
    }

    fun createPortfolio(name: String, cadence: String, plannedIncome: Long, rollover: Boolean, drafts: List<AllocationDraft>, startDate: LocalDate = LocalDate.now(), endDate: LocalDate? = null, intervalCount: Int = 1) = runAction("Portfolio dibuat") {
        repository.createPortfolio(name, cadence, plannedIncome, rollover, drafts, startDate, endDate, intervalCount)
    }

    fun fundPeriod(periodId: Long) = runAction("Portfolio aktif") { repository.fundUnderfundedPeriod(periodId) }
    fun pausePortfolio(portfolioId: Long, reason: String) = runAction("Portfolio dihentikan") { repository.pausePortfolio(portfolioId, reason) }
    fun pauseRecurringRule(ruleId: String, reason: String) = runAction("Jadwal transaksi dihentikan") { repository.pauseRecurringRule(ruleId, reason) }

    fun resolveFromAllocation(sourceId: Long, targetId: Long, amount: Long, note: String) = runAction("Budget minus berhasil diselesaikan") {
        repository.resolveFromAllocation(sourceId, targetId, amount, note)
    }

    fun resolveFromVault(targetId: Long, amount: Long, note: String) = runAction("Main Vault menutup overbudget") {
        repository.resolveFromVault(targetId, amount, note)
    }

    fun resolveFromRollover(targetId: Long, amount: Long, note: String) = runAction("Reserve rollover menutup overbudget") {
        repository.resolveFromRollover(targetId, amount, note)
    }

    fun releaseRolloverToVault(channel: String) = runAction("Reserve rollover dikembalikan ke Main Vault") {
        repository.releaseRolloverToVault(channel)
    }

    fun correctAllocation(allocationId: Long, newPlannedAmount: Long, note: String) = runAction("Koreksi budget tercatat") {
        repository.correctAllocation(allocationId, newPlannedAmount, note)
    }

    fun allocateUnallocated(targetId: Long, amount: Long, note: String) = runAction("Pengeluaran berhasil dialokasikan") {
        repository.allocateUnallocated(targetId, amount, note)
    }

    fun reverseEvent(eventId: String, reason: String) = runAction("Event berhasil direvert") {
        repository.reverseEvent(eventId, reason)
    }

    private fun runAction(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { message.value = success }
            .onFailure { message.value = it.message ?: "Operasi gagal" }
    }

}

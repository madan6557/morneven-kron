package com.morneven.kron.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.retry
import androidx.lifecycle.SavedStateHandle
import android.net.Uri
import android.provider.OpenableColumns
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
import com.morneven.kron.data.EventChannelRow
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.data.KronRepository
import com.morneven.kron.data.PortfolioEntity
import com.morneven.kron.data.RecurringRuleEntity
import com.morneven.kron.data.ReceiptEntity
import com.morneven.kron.data.ScheduleCalculator
import com.morneven.kron.data.SyncStateEntity
import com.morneven.kron.data.TransactionDirection
import com.morneven.kron.data.TransactionSplitEntity
import com.morneven.kron.preferences.PrivacyPreferences
import com.morneven.kron.report.CsvExporter
import com.morneven.kron.security.ImageCompressor
import com.morneven.kron.security.ReceiptManager
import com.morneven.kron.evidence.EvidenceHealth
import com.morneven.kron.evidence.EvidencePackageManager
import com.morneven.kron.evidence.EvidenceVerificationResult
import com.morneven.kron.sync.GoogleAccountIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import com.morneven.kron.sync.DataRefreshBridge
import kotlinx.coroutines.launch

data class KronUiState(
    val accounts: List<AccountEntity> = emptyList(),
    val archivedAccounts: List<AccountEntity> = emptyList(),
    val accountBalances: List<AccountBalanceRow> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val portfolios: List<PortfolioEntity> = emptyList(),
    val archivedPortfolios: List<PortfolioEntity> = emptyList(),
    val periods: List<BudgetPeriodEntity> = emptyList(),
    val allocations: List<AllocationBalanceRow> = emptyList(),
    val activities: List<ActivityRow> = emptyList(),
    val eventChannels: Map<String, Set<String>> = emptyMap(),
    val receipts: List<ReceiptEntity> = emptyList(),
    val syncState: SyncStateEntity? = null,
    val splits: List<TransactionSplitEntity> = emptyList(),
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
    val budgetAlertsEnabled: Boolean = false,
    val screenshotAllowed: Boolean = false,
    val message: String? = null,
) {
    val activeAccount: AccountEntity? get() = accounts.firstOrNull { it.isActive }
    val activeAccountBalance: AccountBalanceRow? get() = accountBalances.firstOrNull { it.isActive }
    val totalAssets: Long get() = activeAccountBalance?.totalBalance ?: 0
    val totalCashAssets: Long get() = activeAccountBalance?.cashBalance ?: 0
    val totalEBudgetAssets: Long get() = activeAccountBalance?.eBudgetBalance ?: 0
    val totalVault: Long get() = vaultCash + vaultEBudget
    val bookedCash: Long get() = allocations.filter { !it.portfolioArchived && it.fundingChannel == FundingChannel.CASH }.sumOf { it.bookedAmount }
    val bookedEBudget: Long get() = allocations.filter { !it.portfolioArchived && it.fundingChannel == FundingChannel.EBUDGET }.sumOf { it.bookedAmount }
    val unresolvedTotal: Long get() = unallocatedCash + unallocatedEBudget
}

private data class LedgerSlice(
    val balances: List<AccountBalanceRow>,
    val allocations: List<AllocationBalanceRow>,
    val activities: List<ActivityRow>,
    val eventChannels: Map<String, Set<String>> = emptyMap(),
    val rules: List<RecurringRuleEntity>,
    val vaults: Map<String, Long>,
    val rollover: Map<String, Long>,
    val receipts: List<ReceiptEntity> = emptyList(),
    val syncState: SyncStateEntity? = null,
    val splits: List<TransactionSplitEntity> = emptyList(),
)

private data class MetadataSlice(
    val accounts: List<AccountEntity>,
    val archivedAccounts: List<AccountEntity> = emptyList(),
    val categories: List<CategoryEntity>,
    val portfolios: List<PortfolioEntity>,
    val archivedPortfolios: List<PortfolioEntity> = emptyList(),
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
    val budgetAlertsEnabled: Boolean = false,
    val screenshotAllowed: Boolean = false,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val repository: KronRepository,
    private val preferences: PrivacyPreferences,
    private val backupManager: BackupManager,
    private val budgetNotifier: BudgetNotifier,
    private val csvExporter: CsvExporter,
    private val receiptManager: ReceiptManager,
    private val imageCompressor: ImageCompressor,
    private val evidencePackageManager: EvidencePackageManager,
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)
    private val sessionVisibility = MutableStateFlow<Boolean?>(null)
    private val manualRestoreReady = MutableStateFlow(false)
    private val evidenceHealthState = MutableStateFlow<EvidenceHealth?>(null)
    val evidenceHealth: StateFlow<EvidenceHealth?> = evidenceHealthState
    private val evidenceVerificationState = MutableStateFlow<EvidenceVerificationResult?>(null)
    val evidenceVerification: StateFlow<EvidenceVerificationResult?> = evidenceVerificationState
    val isManualRestoreReady: StateFlow<Boolean> = manualRestoreReady
    val pendingDriveSubjectId: StateFlow<String?> = savedStateHandle.getStateFlow(PENDING_DRIVE_SUBJECT, null)
    val pendingDriveEmail: StateFlow<String?> = savedStateHandle.getStateFlow(PENDING_DRIVE_EMAIL, null)
    val pendingDriveDisplayName: StateFlow<String?> = savedStateHandle.getStateFlow(PENDING_DRIVE_NAME, null)
    val pendingDriveResolutionId: StateFlow<String?> = savedStateHandle.getStateFlow(PENDING_DRIVE_RESOLUTION, null)

    private val month = YearMonth.now()

    private val dataRefresh = DataRefreshBridge.refresh.onStart { emit(Unit) }

    private val ledger = dataRefresh.flatMapLatest {
        combine(
            repository.accountBalances,
            repository.allocations,
            repository.activities,
            repository.rules,
            repository.vaultByChannel,
        ) { balances, allocations, activities, rules, vaults ->
            LedgerSlice(
                balances = balances,
                allocations = allocations,
                activities = activities,
                rules = rules,
                vaults = vaults.associate { it.fundingChannel to it.balance },
                rollover = emptyMap(),
            )
        }.combine(repository.rolloverByChannel) { slice, rollover ->
            slice.copy(rollover = rollover.associate { it.fundingChannel to it.balance })
        }.combine(repository.receipts) { slice, receipts ->
            slice.copy(receipts = receipts)
        }.combine(repository.eventChannels) { slice, channels ->
            slice.copy(
                eventChannels = channels
                    .groupBy(EventChannelRow::eventId, EventChannelRow::fundingChannel)
                    .mapValues { (_, values) -> values.toSet() },
            )
        }.combine(repository.syncState) { slice, syncState ->
            slice.copy(syncState = syncState)
        }.combine(repository.splits) { slice, splits ->
            slice.copy(splits = splits)
        }
    }

    private val metadata = dataRefresh.flatMapLatest {
        combine(
            repository.accounts,
            repository.categories,
            repository.portfolios,
            repository.periods,
            repository.unallocatedByChannel,
        ) { accounts, categories, portfolios, periods, unallocated ->
            MetadataSlice(
                accounts = accounts,
                categories = categories,
                portfolios = portfolios,
                periods = periods,
                unallocated = unallocated.associate { it.fundingChannel to it.balance },
            )
        }.combine(repository.archivedAccounts) { metadata, archivedAccounts ->
            metadata.copy(archivedAccounts = archivedAccounts)
        }.combine(repository.archivedPortfolios) { metadata, archivedPortfolios ->
            metadata.copy(archivedPortfolios = archivedPortfolios)
        }
    }

    private val cashflow = dataRefresh.flatMapLatest {
        repository.cashflow(month.atDay(1), month.atEndOfMonth())
    }

    private val visibilityPreference = combine(sessionVisibility, preferences.rememberVisibility, preferences.rememberedVisibility) { session, remember, remembered -> Triple(session, remember, remembered) }
    private val authPreference = combine(preferences.authFailures, preferences.authLockedUntil) { failures, lockedUntil -> failures to lockedUntil }
    private val basePreferenceState = combine(
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
    private val preferenceState = basePreferenceState.combine(preferences.budgetAlertsEnabled) { prefs, enabled ->
        prefs.copy(budgetAlertsEnabled = enabled)
    }.combine(preferences.screenshotAllowed) { prefs, allowed ->
        prefs.copy(screenshotAllowed = allowed)
    }

    val uiState: StateFlow<KronUiState> = combine(ledger, metadata, cashflow, preferenceState, message) { ledger, metadata, cashflow, prefs, message ->
        KronUiState(
            accounts = metadata.accounts,
            archivedAccounts = metadata.archivedAccounts,
            accountBalances = ledger.balances,
            categories = metadata.categories,
            portfolios = metadata.portfolios,
            archivedPortfolios = metadata.archivedPortfolios,
            periods = metadata.periods,
            allocations = ledger.allocations,
            activities = ledger.activities,
            eventChannels = ledger.eventChannels,
            receipts = ledger.receipts,
            syncState = ledger.syncState,
            splits = ledger.splits,
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
            budgetAlertsEnabled = prefs.budgetAlertsEnabled,
            screenshotAllowed = prefs.screenshotAllowed,
        )
    }.retry(Long.MAX_VALUE) {
        Log.e("KRON_UI", "Aliran data UI dimulai ulang")
        true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KronUiState())

    init {
        viewModelScope.launch {
            combine(repository.allocations, preferences.budgetAlertsEnabled) { allocations, enabled -> allocations to enabled }
                .collect { (allocations, enabled) ->
                    if (enabled) budgetNotifier.sync(allocations.filterNot(AllocationBalanceRow::portfolioArchived))
                }
        }
        viewModelScope.launch {
            runCatching {
                val firstInstall = repository.isFirstInstall()
                repository.seedIfNeeded()
                if (!preferences.onboardingComplete.first() && !firstInstall) {
                    preferences.completeOnboarding()
                }
                repository.processDueRules(direction = TransactionDirection.INCOME)
                repository.reconcilePortfolios()
                repository.processDueRules(direction = TransactionDirection.EXPENSE)
                repository.purgeExpiredReversalReceipts()
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
    fun setBudgetAlertsEnabled(value: Boolean) = viewModelScope.launch { preferences.setBudgetAlertsEnabled(value) }
    fun setScreenshotAllowed(value: Boolean) = viewModelScope.launch { preferences.setScreenshotAllowed(value) }
    fun completeOnboarding() = viewModelScope.launch { preferences.completeOnboarding() }
    fun clearMessage() { message.value = null }
    fun showMessage(value: String) { message.value = value }

    fun setPendingDriveAuthorization(account: GoogleAccountIdentity?, resolutionId: String) {
        savedStateHandle[PENDING_DRIVE_SUBJECT] = account?.subjectId
        savedStateHandle[PENDING_DRIVE_EMAIL] = account?.email
        savedStateHandle[PENDING_DRIVE_NAME] = account?.displayName
        savedStateHandle[PENDING_DRIVE_RESOLUTION] = resolutionId
    }

    fun clearPendingDriveAuthorization() {
        savedStateHandle[PENDING_DRIVE_SUBJECT] = null
        savedStateHandle[PENDING_DRIVE_EMAIL] = null
        savedStateHandle[PENDING_DRIVE_NAME] = null
        savedStateHandle[PENDING_DRIVE_RESOLUTION] = null
    }

    fun exportBackup(uri: Uri, password: CharArray) = runAction("Backup terenkripsi berhasil dibuat") {
        backupManager.export(uri, password)
    }

    fun stageRestore(uri: Uri, password: CharArray) = viewModelScope.launch {
        runCatching { backupManager.stageRestore(uri, password) }
            .onSuccess {
                manualRestoreReady.value = true
                message.value = "Backup tervalidasi. Tutup lalu buka kembali KRON untuk menerapkan restore"
            }
            .onFailure { message.value = it.message ?: "Restore tidak dapat disiapkan" }
    }

    fun exportCsv(uri: Uri) = runAction("Laporan CSV berhasil dibuat") {
        val current = uiState.value
        csvExporter.export(uri, current.activities, current.allocations)
    }

    fun refreshEvidenceHealth() = viewModelScope.launch {
        evidenceHealthState.value = evidencePackageManager.health()
    }

    fun exportEvidencePackage(uri: Uri, startDay: Long, endDay: Long, passphrase: CharArray) =
        runAction("Paket bukti terenkripsi berhasil dibuat") {
            evidencePackageManager.exportPackage(uri, startDay, endDay, passphrase)
            evidenceHealthState.value = evidencePackageManager.health()
        }

    fun exportEventEvidencePackage(uri: Uri, eventId: String, passphrase: CharArray) =
        runAction("Paket bukti transaksi berhasil dibuat") {
            evidencePackageManager.exportEventPackage(uri, eventId, passphrase)
            evidenceHealthState.value = evidencePackageManager.health()
        }

    fun exportEvidencePdf(uri: Uri, startDay: Long, endDay: Long) = runAction("PDF pertanggungjawaban berhasil dibuat") {
        evidencePackageManager.exportPdf(uri, startDay, endDay)
    }

    fun verifyEvidencePackage(uri: Uri, passphrase: CharArray) = viewModelScope.launch {
        runCatching { evidencePackageManager.verifyPackage(uri, passphrase) }
            .onSuccess {
                evidenceVerificationState.value = it
                message.value = it.message
            }
            .onFailure { message.value = it.message ?: "Paket bukti tidak valid" }
    }

    private suspend fun saveReceipt(eventId: String, uri: Uri) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri).orEmpty()
        require(mimeType.startsWith("image/")) { "Pilih file gambar yang valid" }
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: uri.lastPathSegment ?: "Bukti transaksi"
        resolver.openInputStream(uri)?.use { input ->
            receiptManager.importReceipt(eventId, displayName, mimeType, input, com.morneven.kron.data.EvidenceOrigin.GALLERY)
        } ?: error("Foto bukti tidak dapat dibuka")
    }

    private suspend fun saveCameraReceipt(eventId: String, file: java.io.File) {
        val metadata = imageCompressor.extractMetadata(file)
        val displayName = "Foto kamera ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.forLanguageTag("id-ID")).format(Date())}"
        java.io.FileInputStream(file).use { input ->
            receiptManager.importReceipt(
                eventId = eventId,
                displayName = displayName,
                mimeType = "image/jpeg",
                input = input,
                origin = com.morneven.kron.data.EvidenceOrigin.CAMERA,
                capturedAt = metadata.capturedAt,
                latitude = metadata.latitude,
                longitude = metadata.longitude,
            )
        }
        check(file.delete() || !file.exists()) { "Salinan foto kamera sementara tidak dapat dibersihkan" }
    }

    fun attachReceipt(eventId: String, uri: Uri) = runAction("Foto bukti terenkripsi dan disimpan") {
        saveReceipt(eventId, uri)
    }

    fun attachCameraReceipt(eventId: String, file: java.io.File) = runAction("Foto bukti terenkripsi dan disimpan") {
        saveCameraReceipt(eventId, file)
    }

    fun addAccount(name: String, openingCash: Long, openingEBudget: Long) = runAction("Akun berhasil ditambahkan") {
        repository.addAccount(name, openingCash, openingEBudget)
    }

    fun updateAccount(accountId: Long, name: String) = runAction("Akun diperbarui") {
        repository.updateAccount(accountId, name)
    }

    fun activateAccount(accountId: Long) = runAction("Akun aktif diganti") { repository.activateAccount(accountId) }

    fun archiveAccount(accountId: Long, reason: String) = runAction("Akun diarsipkan") {
        repository.archiveAccount(accountId, reason)
    }

    fun restoreAccount(accountId: Long, reason: String) = runAction("Akun dipulihkan") {
        repository.restoreAccount(accountId, reason)
    }

    fun addIncome(
        accountId: Long,
        fundingChannel: String,
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
        receiptUri: Uri? = null,
        cameraFile: java.io.File? = null,
    ) = runAction("Pemasukan tercatat") {
        require(intervalCount > 0) { "Interval harus minimal 1" }
        require(endDate == null || !endDate.isBefore(startDate)) { "Tanggal akhir tidak boleh sebelum tanggal mulai" }
        val eventId = if (recurring == null || recordNow) {
            repository.addIncome(accountId, fundingChannel, amount, categoryId, title, note, targetAllocationId = targetAllocationId)
        } else null
        if (eventId != null) {
            if (receiptUri != null) saveReceipt(eventId, receiptUri)
            if (cameraFile != null) saveCameraReceipt(eventId, cameraFile)
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
                fundingChannel = fundingChannel,
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
        fundingChannel: String,
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
        receiptUri: Uri? = null,
        cameraFile: java.io.File? = null,
    ) = runAction("Pengeluaran tercatat") {
        require(intervalCount > 0) { "Interval harus minimal 1" }
        require(endDate == null || !endDate.isBefore(startDate)) { "Tanggal akhir tidak boleh sebelum tanggal mulai" }
        val eventId = if (recurring == null || recordNow) {
            repository.addExpense(accountId, fundingChannel, amount, splits, title, note, unexpected)
        } else null
        if (eventId != null) {
            if (receiptUri != null) saveReceipt(eventId, receiptUri)
            if (cameraFile != null) saveCameraReceipt(eventId, cameraFile)
        }
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
                fundingChannel = fundingChannel,
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

    fun transfer(fromAccount: Long, fromChannel: String, toAccount: Long, toChannel: String, amount: Long, note: String) = runAction("Transfer tercatat") {
        repository.transfer(fromAccount, fromChannel, toAccount, toChannel, amount, note)
    }

    fun transferBookedChannel(sourceAllocationId: Long, accountId: Long, amount: Long, note: String) = runAction("Komposisi dana berhasil dipindahkan") {
        repository.transferBookedChannel(sourceAllocationId, accountId, amount, note)
    }

    fun createPortfolio(name: String, cadence: String, plannedIncome: Long, rollover: Boolean, drafts: List<AllocationDraft>, startDate: LocalDate = LocalDate.now(), endDate: LocalDate? = null, intervalCount: Int = 1) = runAction("Portfolio dibuat") {
        repository.createPortfolio(name, cadence, plannedIncome, rollover, drafts, startDate, endDate, intervalCount)
    }

    fun fundPeriod(periodId: Long) = runAction("Portfolio aktif") { repository.fundUnderfundedPeriod(periodId) }
    fun pausePortfolio(portfolioId: Long, reason: String) = runAction("Portfolio dijeda") { repository.pausePortfolio(portfolioId, reason) }
    fun resumePortfolio(portfolioId: Long, reason: String) = runAction("Portfolio dilanjutkan") { repository.resumePortfolio(portfolioId, reason) }
    fun archivePortfolio(portfolioId: Long, reason: String) = runAction("Portfolio diarsipkan") { repository.archivePortfolio(portfolioId, reason) }
    fun restorePortfolio(portfolioId: Long, activate: Boolean, reason: String) = runAction(if (activate) "Portfolio dipulihkan dan diaktifkan" else "Portfolio dipulihkan") {
        repository.restorePortfolio(portfolioId, activate, reason)
    }
    fun pauseRecurringRule(ruleId: String, reason: String) = runAction("Jadwal transaksi dihentikan") { repository.pauseRecurringRule(ruleId, reason) }
    fun resumeRecurringRule(ruleId: String, fromToday: Boolean, reason: String) = runAction("Jadwal transaksi dilanjutkan") {
        repository.resumeRecurringRule(ruleId, fromToday, reason)
    }

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

    fun restoreReversedEvent(eventId: String) = runAction("Event berhasil dipulihkan") {
        repository.restoreReversedEvent(eventId)
    }

    fun correctEvent(eventId: String, title: String, note: String, reason: String) = runAction("Koreksi transaksi tercatat") {
        repository.correctEvent(eventId, title, note, reason)
    }

    private fun runAction(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { message.value = success }
            .onFailure { message.value = it.message ?: "Operasi gagal" }
    }

    private companion object {
        const val PENDING_DRIVE_SUBJECT = "pending_drive_subject"
        const val PENDING_DRIVE_EMAIL = "pending_drive_email"
        const val PENDING_DRIVE_NAME = "pending_drive_name"
        const val PENDING_DRIVE_RESOLUTION = "pending_drive_resolution"
    }

}

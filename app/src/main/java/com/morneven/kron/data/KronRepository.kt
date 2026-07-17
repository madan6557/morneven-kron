package com.morneven.kron.data

import androidx.room.withTransaction
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

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

@Singleton
class KronRepository @Inject constructor(
    private val database: KronDatabase,
) {
    private val dao = database.kronDao()

    val accounts = dao.observeAccounts()
    val accountBalances = dao.observeAccountBalances()
    val categories = dao.observeCategories()
    val portfolios = dao.observePortfolios()
    val periods = dao.observePeriods()
    val allocations = dao.observeAllocationBalances()
    val activities = dao.observeActivities()
    val rules = dao.observeRules()
    val vault = dao.observeVaultBalance()
    val vaultByChannel = dao.observeVaultByChannel()
    val unallocated = dao.observeUnallocatedBalance()
    val unallocatedByChannel = dao.observeUnallocatedByChannel()
    val rolloverByChannel = dao.observeRolloverByChannel()

    fun cashflow(start: LocalDate, end: LocalDate) = dao.observeCashflow(start.toEpochDay(), end.toEpochDay())

    suspend fun seedIfNeeded() = database.withTransaction {
        if (dao.accountCount() > 0) return@withTransaction
        dao.insertAccount(AccountEntity(name = "Kas Utama", type = "CASH", fundingChannel = FundingChannel.CASH))
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

    suspend fun addAccount(name: String, type: String, fundingChannel: String, openingBalance: Long) = database.withTransaction {
        require(name.isNotBlank()) { "Nama akun wajib diisi" }
        require(openingBalance >= 0) { "Saldo awal tidak boleh negatif" }
        require(fundingChannel == FundingChannel.CASH || fundingChannel == FundingChannel.EBUDGET)
        val accountId = dao.insertAccount(AccountEntity(name = name.trim(), type = type, fundingChannel = fundingChannel))
        if (openingBalance > 0) {
            postIncomeInternal(
                accountId = accountId,
                amount = openingBalance,
                categoryId = null,
                title = "Saldo awal $name",
                note = "Dana awal masuk ke Main Vault",
                effectiveDate = LocalDate.now(),
                eventType = LedgerType.OPENING_BALANCE,
                source = "USER",
            )
        }
        assertInvariant()
        accountId
    }

    suspend fun addIncome(
        accountId: Long,
        amount: Long,
        categoryId: Long?,
        title: String,
        note: String,
        effectiveDate: LocalDate = LocalDate.now(),
        targetAllocationId: Long? = null,
    ) = database.withTransaction {
        val eventId = postIncomeInternal(accountId, amount, categoryId, title, note, effectiveDate, LedgerType.INCOME, "USER", targetAllocationId)
        assertInvariant()
        eventId
    }

    private suspend fun postIncomeInternal(
        accountId: Long,
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
        val channel = requireNotNull(dao.accountById(accountId)).fundingChannel
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(
            id = eventId,
            type = eventType,
            title = title.ifBlank { "Pemasukan" },
            note = note,
            source = source,
            effectiveEpochDay = effectiveDate.toEpochDay(),
            targetAllocationId = targetAllocationId,
        ))
        dao.insertCashLines(listOf(CashJournalLineEntity(eventId = eventId, accountId = accountId, amount = amount)))
        dao.insertBudgetLines(listOf(
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = channel, amount = amount),
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.EXTERNAL, fundingChannel = channel, amount = -amount),
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
        amount: Long,
        splits: List<ExpenseSplitInput>,
        title: String,
        note: String,
        unexpected: Boolean = false,
        effectiveDate: LocalDate = LocalDate.now(),
    ) = database.withTransaction {
        val eventId = postExpenseInternal(accountId, amount, splits, title, note, effectiveDate, if (unexpected) LedgerType.UNEXPECTED_EXPENSE else LedgerType.EXPENSE, "USER", unexpected)
        assertInvariant()
        eventId
    }

    private suspend fun postExpenseInternal(
        accountId: Long,
        amount: Long,
        splits: List<ExpenseSplitInput>,
        title: String,
        note: String,
        effectiveDate: LocalDate,
        eventType: String,
        source: String,
        unexpected: Boolean = false,
    ): String {
        require(amount > 0) { "Nominal harus lebih dari nol" }
        require(splits.isNotEmpty() && splits.all { it.amount > 0 } && splits.sumOf { it.amount } == amount) {
            "Total split harus sama dengan nominal transaksi"
        }
        val channel = requireNotNull(dao.accountById(accountId)).fundingChannel
        if (unexpected) require(channel == FundingChannel.CASH) { "Pengeluaran tak terduga hanya memakai Cash" }
        val effectiveSplits = splits.map { split ->
            val allocation = split.allocationId?.let { dao.allocationById(it) }
            val period = allocation?.let { dao.periodById(it.periodId) }
            if (allocation != null && period?.status in setOf(PeriodStatus.ACTIVE, PeriodStatus.RESOLUTION_REQUIRED)) split else split.copy(allocationId = null)
        }
        require(effectiveSplits.all { split -> split.allocationId == null || dao.allocationById(split.allocationId)?.fundingChannel == channel }) {
            "Kanal budget harus sama dengan kanal akun pembayaran"
        }
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(
            id = eventId,
            type = eventType,
            title = title.ifBlank { "Pengeluaran" },
            note = note,
            source = source,
            effectiveEpochDay = effectiveDate.toEpochDay(),
        ))
        dao.insertCashLines(listOf(CashJournalLineEntity(eventId = eventId, accountId = accountId, amount = -amount)))
        val budgetLines = if (unexpected) emptyList() else effectiveSplits.map { split ->
            if (split.allocationId != null) {
                BudgetJournalLineEntity(eventId = eventId, allocationId = split.allocationId, fundingChannel = channel, amount = -split.amount)
            } else {
                BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.UNALLOCATED, fundingChannel = channel, amount = -split.amount)
            }
        } + BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.EXTERNAL, fundingChannel = channel, amount = amount)
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
            reason = "Pengeluaran mengurangi akun dan alokasi budget",
            beforeJson = "{}",
            afterJson = "{\"accountDelta\":${-amount},\"splitCount\":${splits.size}}",
        ))
        return eventId
    }

    suspend fun transfer(fromAccountId: Long, toAccountId: Long, amount: Long, note: String) = database.withTransaction {
        require(fromAccountId != toAccountId) { "Akun asal dan tujuan harus berbeda" }
        require(amount > 0) { "Nominal harus lebih dari nol" }
        val fromAccount = requireNotNull(dao.accountById(fromAccountId))
        val toAccount = requireNotNull(dao.accountById(toAccountId))
        if (fromAccount.fundingChannel != toAccount.fundingChannel) {
            require(dao.vaultBalance(fromAccount.fundingChannel) >= amount) { "Vault ${fromAccount.fundingChannel} tidak mencukupi untuk konversi" }
        }
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(
            id = eventId,
            type = LedgerType.TRANSFER,
            title = "Transfer antar akun",
            note = note,
            source = "USER",
            effectiveEpochDay = LocalDate.now().toEpochDay(),
        ))
        dao.insertCashLines(listOf(
            CashJournalLineEntity(eventId = eventId, accountId = fromAccountId, amount = -amount),
            CashJournalLineEntity(eventId = eventId, accountId = toAccountId, amount = amount),
        ))
        if (fromAccount.fundingChannel != toAccount.fundingChannel) {
            dao.insertBudgetLines(listOf(
                BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = fromAccount.fundingChannel, amount = -amount),
                BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = toAccount.fundingChannel, amount = amount),
            ))
        }
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = if (fromAccount.fundingChannel == toAccount.fundingChannel) "Transfer tidak mengubah Main Vault" else "Transfer mengubah komposisi kanal Main Vault", beforeJson = "{}", afterJson = "{\"amount\":$amount}"))
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
        val resolvedDrafts = drafts.map { draft ->
            if (draft.categoryId > 0) draft else {
                val categoryName = requireNotNull(draft.categoryName?.trim()) { "Nama kategori wajib diisi" }
                require(categoryName.isNotBlank()) { "Nama kategori wajib diisi" }
                val existing = dao.allCategories().firstOrNull { it.direction == TransactionDirection.EXPENSE && it.name.equals(categoryName, ignoreCase = true) }
                val categoryId = existing?.id ?: dao.insertCategory(CategoryEntity(
                    name = categoryName,
                    direction = TransactionDirection.EXPENSE,
                    color = 0xFF9E6BFF,
                    icon = "category",
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
        val canFund = withinPeriod && requestedByChannel.all { (channel, value) -> dao.vaultBalance(channel) >= value }
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
            dao.insertEvent(ActivityEventEntity(
                id = eventId,
                type = LedgerType.PORTFOLIO_BOOKING,
                title = "Booking $name",
                note = "Dana portfolio dibooking dari Main Vault",
                source = "USER",
                effectiveEpochDay = today.toEpochDay(),
            ))
            dao.insertBudgetLines(
                requestedByChannel.map { (channel, value) -> BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = channel, amount = -value) } +
                    allocations.map { (draft, allocationId) -> BudgetJournalLineEntity(eventId = eventId, allocationId = allocationId, fundingChannel = draft.fundingChannel, amount = draft.plannedAmount) }
            )
            dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = "Booking portfolio", beforeJson = "{\"vault\":${dao.vaultBalance() + total}}", afterJson = "{\"vault\":${dao.vaultBalance()}}"))
        }
        assertInvariant()
        portfolioId
    }

    suspend fun fundUnderfundedPeriod(periodId: Long) = database.withTransaction {
        val period = requireNotNull(dao.periodById(periodId))
        require(period.status == PeriodStatus.UNDERFUNDED || period.status == PeriodStatus.DRAFT)
        val allocations = dao.allocationsForPeriod(periodId)
        val needs = allocations.associateWith { (it.plannedAmount - dao.allocationAvailable(it.id)).coerceAtLeast(0) }
        val requestedByChannel = needs.entries.groupBy { it.key.fundingChannel }.mapValues { (_, values) -> values.sumOf { it.value } }
        require(requestedByChannel.all { (channel, value) -> dao.vaultBalance(channel) >= value }) { "Main Vault belum mencukupi" }
        if (requestedByChannel.values.all { it == 0L }) {
            dao.updatePeriod(period.copy(status = PeriodStatus.ACTIVE))
            return@withTransaction
        }
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.PORTFOLIO_BOOKING, "Aktivasi portfolio", "Booking dari Main Vault", "USER", LocalDate.now().toEpochDay()))
        dao.insertBudgetLines(requestedByChannel.filterValues { it > 0 }.map { (channel, value) -> BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = channel, amount = -value) } + needs.filterValues { it > 0 }.map { (allocation, value) ->
            BudgetJournalLineEntity(eventId = eventId, allocationId = allocation.id, fundingChannel = allocation.fundingChannel, amount = value)
        })
        dao.updatePeriod(period.copy(status = PeriodStatus.ACTIVE))
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = "Pendanaan draft", beforeJson = "{\"status\":\"${period.status}\"}", afterJson = "{\"status\":\"ACTIVE\"}"))
        assertInvariant()
    }

    suspend fun resolveFromAllocation(sourceAllocationId: Long, targetAllocationId: Long, amount: Long, note: String) = database.withTransaction {
        require(amount > 0)
        val source = requireNotNull(dao.allocationById(sourceAllocationId))
        val target = requireNotNull(dao.allocationById(targetAllocationId))
        require(source.fundingChannel == target.fundingChannel) { "Resolusi harus memakai kanal dana yang sama" }
        require(dao.allocationAvailable(sourceAllocationId) >= amount) { "Sisa sumber tidak mencukupi" }
        require(dao.allocationAvailable(targetAllocationId) < 0) { "Target tidak sedang minus" }
        val actualAmount = min(amount, -dao.allocationAvailable(targetAllocationId))
        val sourceBefore = dao.allocationAvailable(sourceAllocationId)
        val targetBefore = dao.allocationAvailable(targetAllocationId)
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.REALLOCATION, "Resolusi antar kategori", note, "USER", LocalDate.now().toEpochDay()))
        val routing = if (source.periodId == target.periodId) emptyList() else listOf(
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = source.fundingChannel, amount = actualAmount),
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = target.fundingChannel, amount = -actualAmount),
        )
        dao.insertBudgetLines(listOf(
            BudgetJournalLineEntity(eventId = eventId, allocationId = sourceAllocationId, fundingChannel = source.fundingChannel, amount = -actualAmount),
            BudgetJournalLineEntity(eventId = eventId, allocationId = targetAllocationId, fundingChannel = target.fundingChannel, amount = actualAmount),
        ) + routing)
        refreshPeriodStatus(source.periodId)
        refreshPeriodStatus(target.periodId)
        dao.insertAudit(AuditSnapshotEntity(
            eventId = eventId,
            reason = if (source.periodId == target.periodId) "Resolusi kategori minus" else "Resolusi lintas portfolio melalui Main Vault",
            beforeJson = "{\"source\":$sourceBefore,\"target\":$targetBefore}",
            afterJson = "{\"source\":${sourceBefore - actualAmount},\"target\":${targetBefore + actualAmount}}",
        ))
        assertInvariant()
        eventId
    }

    suspend fun allocateUnallocated(targetAllocationId: Long, amount: Long, note: String) = database.withTransaction {
        require(amount > 0) { "Nominal harus lebih dari nol" }
        val target = requireNotNull(dao.allocationById(targetAllocationId))
        val unresolved = dao.unallocatedBalance(target.fundingChannel)
        require(unresolved < 0) { "Tidak ada pengeluaran belum teralokasi pada kanal ini" }
        val actualAmount = min(amount, -unresolved)
        val targetBefore = dao.allocationAvailable(targetAllocationId)
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.REALLOCATION, "Alokasi pengeluaran tertunda", note, "USER", LocalDate.now().toEpochDay()))
        dao.insertBudgetLines(listOf(
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.UNALLOCATED, fundingChannel = target.fundingChannel, amount = actualAmount),
            BudgetJournalLineEntity(eventId = eventId, allocationId = targetAllocationId, fundingChannel = target.fundingChannel, amount = -actualAmount),
        ))
        refreshPeriodStatus(target.periodId)
        dao.insertAudit(AuditSnapshotEntity(
            eventId = eventId,
            reason = "Pengeluaran belum teralokasi dipindahkan ke kategori",
            beforeJson = "{\"unallocated\":$unresolved,\"target\":$targetBefore}",
            afterJson = "{\"unallocated\":${unresolved + actualAmount},\"target\":${targetBefore - actualAmount}}",
        ))
        assertInvariant()
        eventId
    }

    suspend fun resolveFromVault(targetAllocationId: Long, amount: Long, note: String) = database.withTransaction {
        require(amount > 0)
        val target = requireNotNull(dao.allocationById(targetAllocationId))
        val targetBefore = dao.allocationAvailable(targetAllocationId)
        require(targetBefore < 0) { "Target tidak sedang minus" }
        val actualAmount = min(amount, -targetBefore)
        val vaultBefore = dao.vaultBalance(target.fundingChannel)
        require(vaultBefore >= actualAmount) { "Main Vault tidak mencukupi" }
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.OVERBUDGET_COVERAGE, "Overbudget dari Main Vault", note, "USER", LocalDate.now().toEpochDay()))
        dao.insertBudgetLines(listOf(
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = target.fundingChannel, amount = -actualAmount),
            BudgetJournalLineEntity(eventId = eventId, allocationId = targetAllocationId, fundingChannel = target.fundingChannel, amount = actualAmount),
        ))
        refreshPeriodStatus(target.periodId)
        dao.insertAudit(AuditSnapshotEntity(
            eventId = eventId,
            reason = "Defisit ditutup dari dana nyata Main Vault",
            beforeJson = "{\"vault\":$vaultBefore,\"target\":$targetBefore}",
            afterJson = "{\"vault\":${vaultBefore - actualAmount},\"target\":${targetBefore + actualAmount}}",
        ))
        assertInvariant()
        eventId
    }

    suspend fun resolveFromRollover(targetAllocationId: Long, amount: Long, note: String) = database.withTransaction {
        require(amount > 0)
        val target = requireNotNull(dao.allocationById(targetAllocationId))
        val targetBefore = dao.allocationAvailable(targetAllocationId)
        require(targetBefore < 0) { "Target tidak sedang minus" }
        val reserveBefore = dao.rolloverBalance(target.fundingChannel)
        val actualAmount = min(amount, min(-targetBefore, reserveBefore))
        require(actualAmount > 0) { "Reserve rollover tidak mencukupi" }
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.OVERBUDGET_COVERAGE, "Overbudget dari Reserve rollover", note, "USER", LocalDate.now().toEpochDay()))
        dao.insertBudgetLines(listOf(
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.ROLLOVER, fundingChannel = target.fundingChannel, amount = -actualAmount),
            BudgetJournalLineEntity(eventId = eventId, allocationId = targetAllocationId, fundingChannel = target.fundingChannel, amount = actualAmount),
        ))
        refreshPeriodStatus(target.periodId)
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = "Defisit ditutup dari Reserve rollover", beforeJson = "{\"reserve\":$reserveBefore,\"target\":$targetBefore}", afterJson = "{\"reserve\":${reserveBefore - actualAmount},\"target\":${targetBefore + actualAmount}}"))
        assertInvariant()
        eventId
    }

    suspend fun releaseRolloverToVault(channel: String) = database.withTransaction {
        val amount = dao.rolloverBalance(channel)
        require(amount > 0) { "Reserve rollover kosong" }
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.RELEASE, "Reserve rollover kembali ke Main Vault", "Pelepasan dana berlebih", "USER", LocalDate.now().toEpochDay()))
        dao.insertBudgetLines(listOf(
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.ROLLOVER, fundingChannel = channel, amount = -amount),
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = channel, amount = amount),
        ))
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = "Dana berlebih rollover dikembalikan ke Main Vault", beforeJson = "{\"rollover\":$amount}", afterJson = "{\"vault\":$amount}"))
        assertInvariant()
        eventId
    }

    suspend fun correctAllocation(allocationId: Long, newPlannedAmount: Long, note: String) = database.withTransaction {
        require(newPlannedAmount >= 0) { "Nominal budget tidak boleh negatif" }
        val allocation = requireNotNull(dao.allocationById(allocationId))
        val oldPlanned = allocation.plannedAmount
        val delta = newPlannedAmount - oldPlanned
        require(delta != 0L) { "Tidak ada perubahan nominal" }
        val spent = oldPlanned - dao.allocationAvailable(allocationId)
        require(newPlannedAmount >= spent) { "Budget baru lebih kecil dari pengeluaran yang sudah tercatat" }
        if (delta > 0) require(dao.vaultBalance(allocation.fundingChannel) >= delta) { "Main Vault tidak mencukupi untuk menambah budget" }
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.REALLOCATION, "Koreksi budget", note, "USER", LocalDate.now().toEpochDay()))
        dao.insertBudgetLines(listOf(
            BudgetJournalLineEntity(eventId = eventId, allocationId = allocationId, fundingChannel = allocation.fundingChannel, amount = delta),
            BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = allocation.fundingChannel, amount = -delta),
        ))
        dao.updateAllocation(allocation.copy(plannedAmount = newPlannedAmount))
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = note, beforeJson = "{\"planned\":$oldPlanned}", afterJson = "{\"planned\":$newPlannedAmount}"))
        refreshPeriodStatus(allocation.periodId)
        assertInvariant()
        eventId
    }

    suspend fun transferBookedChannel(
        sourceAllocationId: Long,
        fromAccountId: Long,
        toAccountId: Long,
        amount: Long,
        note: String,
    ) = database.withTransaction {
        require(amount > 0) { "Nominal harus lebih dari nol" }
        val source = requireNotNull(dao.allocationById(sourceAllocationId))
        val fromAccount = requireNotNull(dao.accountById(fromAccountId))
        val toAccount = requireNotNull(dao.accountById(toAccountId))
        require(fromAccount.fundingChannel == source.fundingChannel) { "Akun asal tidak sesuai kanal kategori" }
        require(toAccount.fundingChannel != source.fundingChannel) { "Pilih akun tujuan dengan kanal berbeda" }
        require(dao.accountBalance(fromAccountId) >= amount) { "Saldo akun asal tidak mencukupi" }
        require(dao.allocationAvailable(sourceAllocationId) >= amount) { "Sisa kategori tidak mencukupi" }
        val targetChannel = toAccount.fundingChannel
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
        ))
        dao.insertCashLines(listOf(
            CashJournalLineEntity(eventId = eventId, accountId = fromAccountId, amount = -amount),
            CashJournalLineEntity(eventId = eventId, accountId = toAccountId, amount = amount),
        ))
        dao.insertBudgetLines(listOf(
            BudgetJournalLineEntity(eventId = eventId, allocationId = source.id, fundingChannel = source.fundingChannel, amount = -amount),
            BudgetJournalLineEntity(eventId = eventId, allocationId = target.id, fundingChannel = target.fundingChannel, amount = amount),
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
        require(original.reversedByEventId == null) { "Event sudah dibalik" }
        require(original.type != LedgerType.REVERSAL) { "Reversal tidak dapat dibalik langsung" }
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(
            id = eventId,
            type = LedgerType.REVERSAL,
            title = "Revert: ${original.title}",
            note = reason,
            source = "USER",
            effectiveEpochDay = LocalDate.now().toEpochDay(),
            relatedEventId = originalEventId,
        ))
        val cash = dao.cashLinesForEvent(originalEventId).map { CashJournalLineEntity(eventId = eventId, accountId = it.accountId, amount = -it.amount) }
        val budget = dao.budgetLinesForEvent(originalEventId).map { BudgetJournalLineEntity(eventId = eventId, allocationId = it.allocationId, bucket = it.bucket, fundingChannel = it.fundingChannel, amount = -it.amount) }
        if (cash.isNotEmpty()) dao.insertCashLines(cash)
        if (budget.isNotEmpty()) dao.insertBudgetLines(budget)
        dao.updateEvent(original.copy(reversedByEventId = eventId))
        val affectedPeriods = budget.mapNotNull { it.allocationId }.mapNotNull { dao.allocationById(it)?.periodId }.distinct()
        for (periodId in affectedPeriods) refreshPeriodStatus(periodId)
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = reason, beforeJson = "{\"event\":\"$originalEventId\"}", afterJson = "{\"reversedBy\":\"$eventId\"}"))
        assertInvariant()
        eventId
    }

    suspend fun addRecurringRule(rule: RecurringRuleEntity) = database.withTransaction {
        require(rule.amount > 0)
        dao.insertRule(rule)
    }

    suspend fun pauseRecurringRule(ruleId: String, reason: String = "Jadwal transaksi dihentikan pengguna") = database.withTransaction {
        val rule = requireNotNull(dao.allRules().firstOrNull { it.id == ruleId })
        if (rule.isPaused) return@withTransaction
        dao.updateRule(rule.copy(isPaused = true))
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.SYSTEM, "Jadwal transaksi dihentikan", reason, "USER", LocalDate.now().toEpochDay()))
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = reason, beforeJson = "{\"ruleId\":\"$ruleId\",\"paused\":false}", afterJson = "{\"ruleId\":\"$ruleId\",\"paused\":true}"))
    }

    suspend fun pausePortfolio(portfolioId: Long, reason: String = "Portfolio dihentikan pengguna") = database.withTransaction {
        val portfolio = requireNotNull(dao.allPortfolios().firstOrNull { it.id == portfolioId })
        if (portfolio.isPaused) return@withTransaction
        dao.updatePortfolio(portfolio.copy(isPaused = true))
        val eventId = UUID.randomUUID().toString()
        dao.insertEvent(ActivityEventEntity(eventId, LedgerType.SYSTEM, "Portfolio dihentikan", reason, "USER", LocalDate.now().toEpochDay()))
        dao.insertAudit(AuditSnapshotEntity(eventId = eventId, reason = reason, beforeJson = "{\"portfolioId\":$portfolioId,\"paused\":false}", afterJson = "{\"portfolioId\":$portfolioId,\"paused\":true}"))
        assertInvariant()
    }

    suspend fun processDueRules(today: LocalDate = LocalDate.now(), direction: String? = null) {
        database.withTransaction {
            dao.allRules()
                .filter { !it.isPaused && (direction == null || it.direction == direction) && it.endEpochDay != null && it.nextEpochDay > it.endEpochDay }
                .forEach { dao.updateRule(it.copy(isPaused = true)) }
        }
        repeat(500) {
            val next = dao.dueRules(today.toEpochDay(), direction).firstOrNull() ?: return
            database.withTransaction {
                if (next.endEpochDay != null && next.nextEpochDay > next.endEpochDay) {
                    dao.updateRule(next.copy(isPaused = true))
                    return@withTransaction
                }
                if (dao.occurrenceExists(next.id, next.nextEpochDay)) {
                    dao.updateRule(advanceRule(next))
                    return@withTransaction
                }
                val dueDate = LocalDate.ofEpochDay(next.nextEpochDay)
                val eventId = if (next.direction == TransactionDirection.INCOME) {
                    postIncomeInternal(next.accountId, next.amount, next.categoryId, next.title, "Dibuat otomatis", dueDate, LedgerType.AUTOMATION, "SYSTEM", next.allocationId)
                } else {
                    postExpenseInternal(next.accountId, next.amount, listOf(ExpenseSplitInput(next.categoryId, next.allocationId, next.amount)), next.title, "Dibuat otomatis", dueDate, LedgerType.AUTOMATION, "SYSTEM")
                }
                dao.insertOccurrence(RecurringOccurrenceEntity(ruleId = next.id, dueEpochDay = next.nextEpochDay, eventId = eventId))
                dao.updateRule(advanceRule(next))
                assertInvariant()
            }
        }
    }

    suspend fun reconcilePortfolios(today: LocalDate = LocalDate.now()) = database.withTransaction {
        dao.allPortfolios().filterNot { it.isPaused }.forEach { portfolio ->
            val periods = dao.periodsForPortfolio(portfolio.id)
            val current = periods.firstOrNull { today.toEpochDay() in it.startEpochDay..it.endEpochDay }
            val firstPeriod = periods.minByOrNull { it.startEpochDay }
            if (firstPeriod != null && today.isBefore(LocalDate.ofEpochDay(firstPeriod.startEpochDay))) return@forEach
            if (current != null) {
                if (current.status == PeriodStatus.DRAFT) {
                    val previous = periods.filter { it.endEpochDay < current.startEpochDay }.maxByOrNull { it.endEpochDay }
                    val previousHasDeficit = previous?.let { period -> dao.allocationIdsForPeriod(period.id).any { dao.allocationAvailable(it) < 0 } } == true
                    if (!previousHasDeficit && canFundPeriod(current.id)) fundUnderfundedPeriod(current.id)
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
                previous?.let { dao.updatePeriod(it.copy(status = PeriodStatus.CLOSED)) }
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
                        if (nextAllocationId != null) dao.updateRule(rule.copy(allocationId = nextAllocationId))
                    }
                }
            }
            if (unresolved) return@forEach
            if (previous != null) {
                val remaining = dao.allocationsForPeriod(previous.id).mapNotNull { allocation ->
                    dao.allocationAvailable(allocation.id).takeIf { it > 0 }?.let { Triple(allocation, it, nextAllocations[allocation.categoryId to allocation.fundingChannel]) }
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
                    ))
                    dao.insertBudgetLines(remaining.flatMap { (allocation, value, targetId) ->
                        listOf(
                            BudgetJournalLineEntity(eventId = eventId, allocationId = allocation.id, fundingChannel = allocation.fundingChannel, amount = -value),
                            if (rollover) BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.ROLLOVER, fundingChannel = allocation.fundingChannel, amount = value)
                            else BudgetJournalLineEntity(eventId = eventId, bucket = BudgetBucket.VAULT, fundingChannel = allocation.fundingChannel, amount = value),
                        )
                    })
                    dao.insertAudit(AuditSnapshotEntity(
                        eventId = eventId,
                        reason = if (rollover) "Sisa dibawa ke periode baru" else "Sisa kembali ke Main Vault",
                        beforeJson = "{\"periodId\":${previous.id}}",
                        afterJson = "{\"periodId\":$nextId}",
                    ))
                }
                dao.updatePeriod(previous.copy(status = PeriodStatus.CLOSED))
            }
            if (canFundPeriod(nextId)) fundUnderfundedPeriod(nextId)
        }
        assertInvariant()
    }

    private suspend fun canFundPeriod(periodId: Long): Boolean {
        val needs = dao.allocationsForPeriod(periodId).groupBy { it.fundingChannel }.mapValues { (_, allocations) ->
            allocations.sumOf { (it.plannedAmount - dao.allocationAvailable(it.id)).coerceAtLeast(0) }
        }
        return needs.all { (channel, amount) -> dao.vaultBalance(channel) >= amount }
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
        dao.updatePeriod(period.copy(status = if (hasNegative) PeriodStatus.RESOLUTION_REQUIRED else PeriodStatus.ACTIVE))
    }

    private suspend fun assertInvariant() {
        val cash = dao.cashTotal()
        val available = dao.budgetAvailableTotal()
        if (cash != available) throw LedgerInvariantException("Jurnal tidak seimbang: kas=$cash, dana=$available")
        listOf(FundingChannel.CASH, FundingChannel.EBUDGET).forEach { channel ->
            val channelCash = dao.cashTotal(channel)
            val channelAvailable = dao.budgetAvailableTotal(channel)
            if (channelCash != channelAvailable) throw LedgerInvariantException("Kanal $channel tidak seimbang: kas=$channelCash, dana=$channelAvailable")
        }
        val newest = dao.allEvents().lastOrNull()
        if (newest != null && dao.budgetEventTotal(newest.id) != 0L && dao.budgetLinesForEvent(newest.id).isNotEmpty()) {
            throw LedgerInvariantException("Budget event ${newest.id} tidak seimbang")
        }
    }
}

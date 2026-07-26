package com.morneven.kron.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.morneven.kron.audit.EvidenceSigningKeyManager
import com.morneven.kron.audit.LedgerPostingEngine
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KronRepositoryTest {
    private lateinit var database: KronDatabase
    private lateinit var dao: KronDao
    private lateinit var repository: KronRepository
    private lateinit var postingEngine: LedgerPostingEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KronDatabase::class.java).allowMainThreadQueries().build()
        dao = database.kronDao()
        postingEngine = LedgerPostingEngine(context, database, EvidenceSigningKeyManager())
        repository = KronRepository(database, postingEngine)
        runBlocking { repository.seedIfNeeded() }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun resolvingExample_movesTenFromBToA_andKeepsAuditTrail() = runBlocking {
        val account = dao.allAccounts().first()
        val expenseCategories = dao.allCategories().filter { it.direction == TransactionDirection.EXPENSE }
        val categoryA = expenseCategories[0]
        val categoryB = expenseCategories[1]
        repository.addIncome(account.id, FundingChannel.CASH, 100, null, "Modal", "")
        repository.createPortfolio(
            name = "RAB Uji",
            cadence = "MONTHLY",
            plannedIncome = 100,
            rolloverEnabled = false,
            drafts = listOf(
                AllocationDraft(categoryA.id, FundingChannel.CASH, 40),
                AllocationDraft(categoryB.id, FundingChannel.CASH, 40),
            ),
        )
        val allocationA = dao.allAllocations().first { it.categoryId == categoryA.id }
        val allocationB = dao.allAllocations().first { it.categoryId == categoryB.id }
        repository.addExpense(account.id, FundingChannel.CASH, 50, listOf(ExpenseSplitInput(categoryA.id, allocationA.id, 50)), "A", "")
        repository.addExpense(account.id, FundingChannel.CASH, 20, listOf(ExpenseSplitInput(categoryB.id, allocationB.id, 20)), "B", "")

        assertEquals(-10L, dao.allocationAvailable(allocationA.id))
        assertEquals(20L, dao.allocationAvailable(allocationB.id))

        val eventId = repository.resolveFromAllocation(allocationB.id, allocationA.id, 10, "Ambil sisa B")

        assertEquals(0L, dao.allocationAvailable(allocationA.id))
        assertEquals(10L, dao.allocationAvailable(allocationB.id))
        assertEquals(50L, dao.allocationBooked(allocationA.id))
        assertEquals(30L, dao.allocationBooked(allocationB.id))
        assertEquals(LedgerType.REALLOCATION, dao.eventById(eventId)?.type)
        assertTrue(dao.auditsForEvent(eventId).single().beforeJson.contains("\"source\":20"))
        assertEquals(dao.cashTotal(), dao.budgetAvailableTotal())
        assertEquals(dao.cashTotal(FundingChannel.CASH), dao.budgetAvailableTotal(FundingChannel.CASH))
    }

    @Test
    fun financialPostingReversalAndCorrectionRemainBalancedAndSealed() = runBlocking {
        val account = dao.activeAccount() ?: error("Akun aktif tidak ditemukan")
        val category = dao.allCategories().first { it.direction == TransactionDirection.EXPENSE }
        val incomeId = repository.addIncome(account.id, FundingChannel.CASH, 200, null, "Modal", "")
        val expenseId = repository.addExpense(
            account.id,
            FundingChannel.CASH,
            50,
            listOf(ExpenseSplitInput(category.id, null, 50)),
            "Belanja",
            "Bukti awal",
        )
        repository.transfer(account.id, FundingChannel.CASH, account.id, FundingChannel.EBUDGET, 30, "Ubah kanal")
        repository.reverseEvent(expenseId, "Transaksi dibatalkan")
        val replacementId = repository.correctEvent(incomeId, "Modal diperbaiki", "Catatan baru", "Perbaikan deskripsi")

        postingEngine.validateAll()
        assertTrue(dao.unbalancedLedgerEvents().isEmpty())
        assertEquals(dao.eventCount(), dao.sealCount())
        assertTrue(dao.isEventReversed(incomeId))
        assertEquals("Modal", dao.eventById(incomeId)?.title)
        assertEquals("Modal diperbaiki", dao.eventById(replacementId)?.title)
        dao.allEvents().forEach { event ->
            assertEquals(0L, dao.budgetEventTotal(event.id))
            val ledger = dao.ledgerLinesForEvent(event.id)
            assertEquals(
                ledger.filter { it.side == LedgerSide.DEBIT }.sumOf { it.amount },
                ledger.filter { it.side == LedgerSide.CREDIT }.sumOf { it.amount },
            )
        }
    }

    @Test
    fun channelTransfer_movesRealAccountAndBookedCompositionAtomically() = runBlocking {
        val account = dao.allAccounts().first()
        val category = dao.allCategories().first { it.direction == TransactionDirection.EXPENSE }
        repository.addIncome(account.id, FundingChannel.CASH, 50, null, "Cash", "")
        repository.addIncome(account.id, FundingChannel.EBUDGET, 50, null, "eBudget", "")
        repository.createPortfolio(
            "Campuran",
            "MONTHLY",
            100,
            false,
            listOf(
                AllocationDraft(category.id, FundingChannel.CASH, 20),
                AllocationDraft(category.id, FundingChannel.EBUDGET, 20),
            ),
        )
        val cashAllocation = dao.allAllocations().first { it.fundingChannel == FundingChannel.CASH }
        val eBudgetAllocation = dao.allAllocations().first { it.fundingChannel == FundingChannel.EBUDGET }

        repository.transferBookedChannel(cashAllocation.id, account.id, 10, "Ubah komposisi")

        assertEquals(10L, dao.allocationAvailable(cashAllocation.id))
        assertEquals(30L, dao.allocationAvailable(eBudgetAllocation.id))
        assertEquals(40L, dao.accountBalance(account.id, FundingChannel.CASH))
        assertEquals(60L, dao.accountBalance(account.id, FundingChannel.EBUDGET))
        assertEquals(dao.cashTotal(FundingChannel.CASH), dao.budgetAvailableTotal(FundingChannel.CASH))
        assertEquals(dao.cashTotal(FundingChannel.EBUDGET), dao.budgetAvailableTotal(FundingChannel.EBUDGET))
    }

    @Test
    fun accountHasTwoChannels_andManualTransferKeepsTotalAsset() = runBlocking {
        val account = dao.allAccounts().first()
        repository.addIncome(account.id, FundingChannel.CASH, 100, null, "Modal Cash", "")

        repository.transfer(
            fromAccountId = account.id,
            fromChannel = FundingChannel.CASH,
            toAccountId = account.id,
            toChannel = FundingChannel.EBUDGET,
            amount = 35,
            note = "Alokasi manual",
        )

        assertEquals(65L, dao.accountBalance(account.id, FundingChannel.CASH))
        assertEquals(35L, dao.accountBalance(account.id, FundingChannel.EBUDGET))
        assertEquals(100L, dao.accountBalance(account.id))
        assertEquals(dao.cashTotal(FundingChannel.CASH), dao.budgetAvailableTotal(FundingChannel.CASH))
        assertEquals(dao.cashTotal(FundingChannel.EBUDGET), dao.budgetAvailableTotal(FundingChannel.EBUDGET))
    }

    @Test
    fun activatingAccountLeavesExactlyOneActiveAccount() = runBlocking {
        val first = dao.activeAccount() ?: error("Akun aktif tidak ditemukan")
        val secondId = repository.addAccount("Akun Kedua", 0, 0)

        repository.activateAccount(secondId)

        assertEquals(1, dao.activeAccountCount())
        assertEquals(secondId, dao.activeAccount()?.id)
        assertTrue(dao.accountById(first.id)?.isActive == false)
    }

    @Test
    fun staleObjectsFromAnotherAccountCannotBeChanged() = runBlocking {
        val first = dao.activeAccount() ?: error("Akun aktif tidak ditemukan")
        val category = dao.allCategories().first { it.direction == TransactionDirection.EXPENSE }
        val incomeId = repository.addIncome(first.id, FundingChannel.CASH, 100, null, "Dana", "")
        val portfolioId = repository.createPortfolio(
            "Milik akun pertama",
            "MONTHLY",
            0,
            false,
            listOf(AllocationDraft(category.id, FundingChannel.CASH, 20)),
        )
        val allocation = dao.allAllocations().single()
        val today = LocalDate.now()
        repository.addRecurringRule(
            RecurringRuleEntity(
                "rule-first-account",
                "Jadwal akun pertama",
                TransactionDirection.EXPENSE,
                5,
                first.id,
                FundingChannel.CASH,
                category.id,
                allocation.id,
                "MONTHLY",
                1,
                today.monthValue,
                today.dayOfMonth,
                today.toEpochDay(),
                today.plusMonths(1).toEpochDay(),
            ),
        )
        val secondId = repository.addAccount("Akun kedua", 50, 0)
        repository.activateAccount(secondId)
        val eventCount = dao.eventCount()

        assertTrue(runCatching { repository.reverseEvent(incomeId, "Akun sudah berganti") }.isFailure)
        assertTrue(runCatching { repository.pauseRecurringRule("rule-first-account") }.isFailure)
        assertTrue(runCatching { repository.archivePortfolio(portfolioId, "Akun sudah berganti") }.isFailure)
        assertTrue(runCatching {
            repository.addIncome(secondId, FundingChannel.CASH, 5, null, "Dana", "", targetAllocationId = allocation.id)
        }.isFailure)
        assertTrue(runCatching {
            repository.addExpense(
                secondId,
                FundingChannel.CASH,
                5,
                listOf(ExpenseSplitInput(category.id, allocation.id, 5)),
                "Belanja",
                "",
            )
        }.isFailure)

        assertFalse(dao.isEventReversed(incomeId))
        assertFalse(dao.allRules().single { it.id == "rule-first-account" }.isPaused)
        assertFalse(dao.allPortfolios().single { it.id == portfolioId }.isArchived)
        assertEquals(eventCount, dao.eventCount())
    }

    @Test
    fun transferRejectsArchivedDestination() = runBlocking {
        val source = dao.activeAccount() ?: error("Akun aktif tidak ditemukan")
        val destinationId = repository.addAccount("Tujuan arsip", 0, 0)
        repository.archiveAccount(destinationId, "Tidak digunakan")
        repository.addIncome(source.id, FundingChannel.CASH, 50, null, "Dana", "")

        val result = runCatching {
            repository.transfer(source.id, FundingChannel.CASH, destinationId, FundingChannel.CASH, 10, "Transfer")
        }

        assertTrue(result.isFailure)
        assertEquals(50L, dao.accountBalance(source.id, FundingChannel.CASH))
        assertEquals(0L, dao.accountBalance(destinationId, FundingChannel.CASH))
    }

    @Test
    fun unexpectedExpenseReducesVaultAndKeepsBudgetInSync() = runBlocking {
        val account = dao.activeAccount() ?: error("Akun aktif tidak ditemukan")
        val category = dao.allCategories().first { it.direction == TransactionDirection.EXPENSE }
        repository.addIncome(account.id, FundingChannel.CASH, 100, null, "Dana", "")

        repository.addExpense(
            accountId = account.id,
            fundingChannel = FundingChannel.CASH,
            amount = 25,
            splits = listOf(ExpenseSplitInput(category.id, null, 25)),
            title = "Insidental",
            note = "Tidak direncanakan",
            unexpected = true,
        )

        assertEquals(75L, dao.accountBalance(account.id, FundingChannel.CASH))
        assertEquals(75L, dao.vaultBalance(FundingChannel.CASH))
        assertEquals(0L, dao.budgetBucketBalance(BudgetBucket.UNEXPECTED, FundingChannel.CASH))
        assertEquals(dao.cashTotal(), dao.budgetAvailableTotal())
    }

    @Test
    fun archivePortfolioReleasesBothChannelsAndClosesOpenPeriod() = runBlocking {
        val account = dao.activeAccount() ?: error("Akun aktif tidak ditemukan")
        val category = dao.allCategories().first { it.direction == TransactionDirection.EXPENSE }
        repository.addIncome(account.id, FundingChannel.CASH, 100, null, "Cash", "")
        repository.addIncome(account.id, FundingChannel.EBUDGET, 100, null, "eBudget", "")
        val portfolioId = repository.createPortfolio(
            "Arsip Uji",
            "MONTHLY",
            0,
            false,
            listOf(
                AllocationDraft(category.id, FundingChannel.CASH, 40),
                AllocationDraft(category.id, FundingChannel.EBUDGET, 30),
            ),
        )

        repository.archivePortfolio(portfolioId, "Tidak digunakan lagi")

        assertEquals(100L, dao.vaultBalance(FundingChannel.CASH))
        assertEquals(100L, dao.vaultBalance(FundingChannel.EBUDGET))
        assertTrue(dao.allocationsForPeriod(dao.periodsForPortfolio(portfolioId).first().id).all { dao.allocationAvailable(it.id) == 0L })
        assertTrue(dao.periodsForPortfolio(portfolioId).all { it.status == PeriodStatus.CLOSED })
        assertTrue(dao.allPortfolios().single { it.id == portfolioId }.isArchived)
        assertEquals(LedgerType.ARCHIVE, dao.allEvents().last { it.title == "Portfolio diarsipkan" }.type)
    }

    @Test
    fun archivePortfolioRejectsAnyUnresolvedDeficit() = runBlocking {
        val account = dao.activeAccount() ?: error("Akun aktif tidak ditemukan")
        val category = dao.allCategories().first { it.direction == TransactionDirection.EXPENSE }
        repository.addIncome(account.id, FundingChannel.CASH, 100, null, "Dana", "")
        val portfolioId = repository.createPortfolio("Minus", "MONTHLY", 0, false, listOf(AllocationDraft(category.id, FundingChannel.CASH, 40)))
        val allocation = dao.allAllocations().single()
        repository.addExpense(account.id, FundingChannel.CASH, 50, listOf(ExpenseSplitInput(category.id, allocation.id, 50)), "Lebih", "")

        val result = runCatching { repository.archivePortfolio(portfolioId, "Arsip") }

        assertTrue(result.isFailure)
        assertFalse(dao.allPortfolios().single { it.id == portfolioId }.isArchived)
    }

    @Test
    fun restorePortfolioOnlyResumesRulesPausedByArchive() = runBlocking {
        val account = dao.activeAccount() ?: error("Akun aktif tidak ditemukan")
        val category = dao.allCategories().first { it.direction == TransactionDirection.EXPENSE }
        repository.addIncome(account.id, FundingChannel.CASH, 200, null, "Dana", "")
        val portfolioId = repository.createPortfolio("Rutin", "MONTHLY", 0, false, listOf(AllocationDraft(category.id, FundingChannel.CASH, 50)))
        val allocation = dao.allAllocations().single()
        val today = LocalDate.now()
        repository.addRecurringRule(RecurringRuleEntity("archive-rule", "Aktif", TransactionDirection.EXPENSE, 10, account.id, FundingChannel.CASH, category.id, allocation.id, "MONTHLY", 1, today.monthValue, today.dayOfMonth, today.toEpochDay(), today.plusMonths(1).toEpochDay()))
        repository.addRecurringRule(RecurringRuleEntity("manual-rule", "Manual", TransactionDirection.EXPENSE, 10, account.id, FundingChannel.CASH, category.id, allocation.id, "MONTHLY", 1, today.monthValue, today.dayOfMonth, today.toEpochDay(), today.plusMonths(1).toEpochDay(), isPaused = true))

        repository.archivePortfolio(portfolioId, "Simpan")
        assertTrue(dao.allRules().single { it.id == "archive-rule" }.pausedByArchive)
        assertFalse(dao.allRules().single { it.id == "manual-rule" }.pausedByArchive)

        repository.restorePortfolio(portfolioId, activate = true, reason = "Gunakan lagi")

        assertFalse(dao.allPortfolios().single { it.id == portfolioId }.isArchived)
        assertFalse(dao.allRules().single { it.id == "archive-rule" }.isPaused)
        assertTrue(dao.allRules().single { it.id == "manual-rule" }.isPaused)
    }

    @Test
    fun archivedAccountRestoresAsInactiveWithAuditEvents() = runBlocking {
        val secondId = repository.addAccount("Cadangan", 0, 0)

        repository.archiveAccount(secondId, "Tidak dipakai")
        assertTrue(dao.accountById(secondId)?.isArchived == true)
        assertTrue(dao.accountById(secondId)?.archivedAt != null)

        repository.restoreAccount(secondId, "Dipakai kembali")

        val restored = dao.accountById(secondId) ?: error("Akun hilang")
        assertFalse(restored.isArchived)
        assertFalse(restored.isActive)
        assertEquals(null, restored.archivedAt)
        assertTrue(dao.allEvents().any { it.type == LedgerType.ARCHIVE && it.title == "Akun diarsipkan" })
        assertTrue(dao.allEvents().any { it.type == LedgerType.RESTORE && it.title == "Akun dipulihkan" })
    }

    @Test
    fun februarySchedule_clampsAndReturnsToAnchorDay() {
        assertEquals(LocalDate.of(2027, 2, 28), ScheduleCalculator.next(LocalDate.of(2026, 2, 28), "YEARLY", 2, 29))
        assertEquals(LocalDate.of(2028, 2, 29), ScheduleCalculator.next(LocalDate.of(2027, 2, 28), "YEARLY", 2, 29))
        assertEquals(LocalDate.of(2026, 2, 28), ScheduleCalculator.next(LocalDate.of(2026, 1, 31), "MONTHLY", 1, 31))
        assertEquals(LocalDate.of(2026, 3, 31), ScheduleCalculator.next(LocalDate.of(2026, 2, 28), "MONTHLY", 1, 31))
    }
}

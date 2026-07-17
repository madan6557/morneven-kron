package com.morneven.kron.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KronRepositoryTest {
    private lateinit var database: KronDatabase
    private lateinit var dao: KronDao
    private lateinit var repository: KronRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KronDatabase::class.java).allowMainThreadQueries().build()
        dao = database.kronDao()
        repository = KronRepository(database)
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

        repository.transferBookedChannel(cashAllocation.id, account.id, account.id, 10, "Ubah komposisi")

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
    fun unexpectedExpenseUsesCashVaultWithoutReducingBudget() = runBlocking {
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
        assertEquals(dao.cashTotal(), dao.budgetAvailableTotal())
    }

    @Test
    fun februarySchedule_clampsAndReturnsToAnchorDay() {
        assertEquals(LocalDate.of(2027, 2, 28), ScheduleCalculator.next(LocalDate.of(2026, 2, 28), "YEARLY", 2, 29))
        assertEquals(LocalDate.of(2028, 2, 29), ScheduleCalculator.next(LocalDate.of(2027, 2, 28), "YEARLY", 2, 29))
        assertEquals(LocalDate.of(2026, 2, 28), ScheduleCalculator.next(LocalDate.of(2026, 1, 31), "MONTHLY", 1, 31))
        assertEquals(LocalDate.of(2026, 3, 31), ScheduleCalculator.next(LocalDate.of(2026, 2, 28), "MONTHLY", 1, 31))
    }
}

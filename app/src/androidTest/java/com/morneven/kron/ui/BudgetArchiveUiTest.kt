package com.morneven.kron.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.morneven.kron.data.AllocationBalanceRow
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.data.PeriodStatus
import com.morneven.kron.data.PortfolioEntity
import com.morneven.kron.ui.screens.BudgetScreen
import com.morneven.kron.ui.theme.KronTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BudgetArchiveUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun archiveTabShowsReadOnlyDetailAndBothRestoreActions() {
        var detailReadOnly: Boolean? = null
        val archived = PortfolioEntity(
            id = 7,
            name = "RAB Arsip",
            cadence = "MONTHLY",
            plannedIncome = 0,
            rolloverEnabled = false,
            fundingPriority = 100,
            startEpochDay = 1,
            endMode = "CONTINUOUS",
            isPaused = true,
            isArchived = true,
            archivedAt = System.currentTimeMillis(),
            accountId = 1,
        )
        val allocation = AllocationBalanceRow(
            id = 1,
            periodId = 10,
            portfolioId = archived.id,
            portfolioName = archived.name,
            portfolioArchived = true,
            categoryId = 2,
            categoryName = "Belanja",
            color = 0,
            fundingChannel = FundingChannel.CASH,
            plannedAmount = 100,
            bookedAmount = 0,
            availableAmount = 0,
            spentAmount = 20,
            periodStatus = PeriodStatus.CLOSED,
            startEpochDay = 1,
            endEpochDay = 30,
        )
        composeRule.setContent {
            KronTheme("DARK") {
                BudgetScreen(
                    state = KronUiState(archivedPortfolios = listOf(archived), allocations = listOf(allocation), valuesVisible = true),
                    onCreate = {},
                    onResolve = {},
                    onFund = {},
                    onChannelTransfer = {},
                    onToggleRollover = { _, _ -> },
                    onDetail = { _, readOnly -> detailReadOnly = readOnly },
                    onHistory = {},
                    onPause = {},
                    onResume = {},
                    onArchive = {},
                    onRestore = { _, _ -> },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Arsip (1)").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("RAB Arsip").assertExists()
        composeRule.onNodeWithText("Pulihkan").assertExists()
        composeRule.onNodeWithText("Pulihkan & Aktifkan").assertExists()
        composeRule.onNodeWithText("Lihat detail read-only").performClick()
        assertEquals(true, detailReadOnly)
    }
}

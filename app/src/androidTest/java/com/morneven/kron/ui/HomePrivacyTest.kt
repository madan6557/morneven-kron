package com.morneven.kron.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.morneven.kron.data.AccountBalanceRow
import com.morneven.kron.ui.screens.HomeScreen
import com.morneven.kron.ui.theme.KronTheme
import org.junit.Rule
import org.junit.Test

class HomePrivacyTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun hiddenState_neverRendersRealTotal() {
        composeRule.setContent {
            KronTheme("DARK") {
                HomeScreen(
                    state = KronUiState(valuesVisible = false),
                    onToggleValues = {},
                    onIncome = {},
                    onExpense = {},
                    onTransfer = {},
                    onResolve = {},
                    onAllActivities = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Tampilkan nilai").assertExists()
        composeRule.onAllNodesWithText("Rp ••••••", substring = false)[0].assertExists()
    }

    @Test
    fun visibleState_hasAccessibleHideAction() {
        composeRule.setContent {
            KronTheme("DARK") {
                HomeScreen(
                    state = KronUiState(valuesVisible = true),
                    onToggleValues = {},
                    onIncome = {},
                    onExpense = {},
                    onTransfer = {},
                    onResolve = {},
                    onAllActivities = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Sembunyikan nilai").assertExists()
    }

    @Test
    fun abbreviatedValueOpensExactAmountDialog() {
        composeRule.setContent {
            KronTheme("DARK") {
                HomeScreen(
                    state = KronUiState(
                        valuesVisible = true,
                        accountBalances = listOf(AccountBalanceRow(1, "Utama", true, 7_222_223_225_222, 0, 7_222_223_225_222)),
                    ),
                    onToggleValues = {},
                    onIncome = {},
                    onExpense = {},
                    onTransfer = {},
                    onResolve = {},
                    onAllActivities = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Rp 7,22 triliun")[0].performClick()
        composeRule.onNodeWithText("Nominal lengkap").assertExists()
        composeRule.onNodeWithText("Rp 7.222.223.225.222").assertExists()
    }
}

package com.morneven.kron.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
}

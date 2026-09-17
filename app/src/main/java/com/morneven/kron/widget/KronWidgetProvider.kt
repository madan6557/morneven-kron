package com.morneven.kron.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.morneven.kron.MainActivity
import com.morneven.kron.R

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KronWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_VISIBILITY) {
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                KronWidgetManager.toggleWidgetVisibilitySilent(context, appWidgetId)
                val appWidgetManager = AppWidgetManager.getInstance(context)
                if (appWidgetManager != null) {
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                }
            } else {
                KronWidgetManager.toggleVisibility(context)
            }
            return
        }
        if (intent.action == ACTION_CYCLE_CURRENCY) {
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                KronWidgetManager.cycleWidgetCurrencySilent(context, appWidgetId)
                val appWidgetManager = AppWidgetManager.getInstance(context)
                if (appWidgetManager != null) {
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                }
            }
            return
        }
        if (intent.action == ACTION_REQUEST_PIN) {
            KronWidgetManager.pinWidgetToHomeScreen(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        KronWidgetManager.removeWidgetPreferences(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                KronCurrencyManager.checkAndAutoSync(context)
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main) {
                    for (appWidgetId in appWidgetIds) {
                        updateAppWidget(context, appWidgetManager, appWidgetId)
                    }
                    pendingResult.finish()
                }
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }

    companion object {
        const val ACTION_TOGGLE_VISIBILITY = "com.morneven.kron.action.TOGGLE_VISIBILITY"
        const val ACTION_CYCLE_CURRENCY = "com.morneven.kron.action.CYCLE_CURRENCY"
        const val ACTION_REQUEST_PIN = "com.morneven.kron.action.REQUEST_PIN"
        const val EXTRA_QUICK_ACTION = "com.morneven.kron.extra.QUICK_ACTION"
        const val ACTION_EXPENSE = "EXPENSE"
        const val ACTION_INCOME = "INCOME"
        const val ACTION_TRANSFER = "TRANSFER"

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val snapshot = KronWidgetManager.getSnapshot(context, appWidgetId)
            val currency = snapshot.currency
            val views = RemoteViews(context.packageName, R.layout.kron_app_widget)

            // Adapt action buttons for Nx2 sizes (e.g. 2x2, 3x2, 4x2)
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
            val showLabels = minWidth == 0 || minWidth >= 240
            val labelVisibility = if (showLabels) View.VISIBLE else View.GONE
            views.setViewVisibility(R.id.tv_widget_action_expense_label, labelVisibility)
            views.setViewVisibility(R.id.tv_widget_action_income_label, labelVisibility)
            views.setViewVisibility(R.id.tv_widget_action_transfer_label, labelVisibility)

            // Currency toggle button (displays 3-char code: IDR, KRM, USD, EUR, SGD, JPY)
            val currencyBadgeText = currency.code
            views.setTextViewText(R.id.btn_widget_currency, currencyBadgeText)

            val cycleIntent = Intent(context, KronWidgetProvider::class.java).apply {
                action = ACTION_CYCLE_CURRENCY
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = android.net.Uri.parse("kron://widget/currency/$appWidgetId")
            }
            val pendingCycle = PendingIntent.getBroadcast(
                context,
                2000 + appWidgetId,
                cycleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.btn_widget_currency, pendingCycle)

            // Account and balances
            views.setTextViewText(R.id.tv_widget_account_name, snapshot.accountName)
            views.setTextViewText(
                R.id.tv_widget_total_balance,
                KronWidgetManager.formatBalance(snapshot.totalBalance, snapshot.valuesVisible, currency, context),
            )
            views.setTextViewText(
                R.id.tv_widget_cash_balance,
                KronWidgetManager.formatBalance(snapshot.cashBalance, snapshot.valuesVisible, currency, context),
            )
            views.setTextViewText(
                R.id.tv_widget_ebudget_balance,
                KronWidgetManager.formatBalance(snapshot.ebudgetBalance, snapshot.valuesVisible, currency, context),
            )

            // Visibility toggle icon
            val visibilityIcon = if (snapshot.valuesVisible) {
                R.drawable.ic_widget_visibility
            } else {
                R.drawable.ic_widget_visibility_off
            }
            views.setImageViewResource(R.id.btn_widget_toggle_visibility, visibilityIcon)

            // Intent: Open Main App
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingOpen = PendingIntent.getActivity(
                context,
                100,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.layout_widget_balance, pendingOpen)

            // Intent: Toggle Visibility (per widget ID)
            val toggleIntent = Intent(context, KronWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_VISIBILITY
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = android.net.Uri.parse("kron://widget/toggle/$appWidgetId")
            }
            val pendingToggle = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.btn_widget_toggle_visibility, pendingToggle)

            // Intent: Quick Action - Expense
            val expenseIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_QUICK_ACTION, ACTION_EXPENSE)
            }
            val pendingExpense = PendingIntent.getActivity(
                context,
                102,
                expenseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.btn_widget_action_expense, pendingExpense)

            // Intent: Quick Action - Income
            val incomeIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_QUICK_ACTION, ACTION_INCOME)
            }
            val pendingIncome = PendingIntent.getActivity(
                context,
                103,
                incomeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.btn_widget_action_income, pendingIncome)

            // Intent: Quick Action - Transfer
            val transferIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_QUICK_ACTION, ACTION_TRANSFER)
            }
            val pendingTransfer = PendingIntent.getActivity(
                context,
                104,
                transferIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.btn_widget_action_transfer, pendingTransfer)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

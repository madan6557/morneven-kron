package com.morneven.kron.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.morneven.kron.ui.components.formatIdr

data class KronWidgetSnapshot(
    val accountName: String,
    val totalBalance: Long,
    val cashBalance: Long,
    val ebudgetBalance: Long,
    val valuesVisible: Boolean,
    val lastUpdated: Long,
)

object KronWidgetManager {
    private const val PREFS_NAME = "kron_widget_cache"
    private const val KEY_ACCOUNT_NAME = "account_name"
    private const val KEY_TOTAL_BALANCE = "total_balance"
    private const val KEY_CASH_BALANCE = "cash_balance"
    private const val KEY_EBUDGET_BALANCE = "ebudget_balance"
    private const val KEY_VALUES_VISIBLE = "values_visible"
    private const val KEY_LAST_UPDATED = "last_updated"

    const val HIDDEN_MONEY = "Rp \u2022\u2022\u2022\u2022\u2022\u2022"

    fun getSnapshot(context: Context): KronWidgetSnapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return KronWidgetSnapshot(
            accountName = prefs.getString(KEY_ACCOUNT_NAME, "Akun Utama") ?: "Akun Utama",
            totalBalance = prefs.getLong(KEY_TOTAL_BALANCE, 0L),
            cashBalance = prefs.getLong(KEY_CASH_BALANCE, 0L),
            ebudgetBalance = prefs.getLong(KEY_EBUDGET_BALANCE, 0L),
            valuesVisible = prefs.getBoolean(KEY_VALUES_VISIBLE, true),
            lastUpdated = prefs.getLong(KEY_LAST_UPDATED, 0L),
        )
    }

    fun updateCache(
        context: Context,
        accountName: String,
        totalBalance: Long,
        cashBalance: Long,
        ebudgetBalance: Long,
        valuesVisible: Boolean,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_ACCOUNT_NAME, accountName)
            .putLong(KEY_TOTAL_BALANCE, totalBalance)
            .putLong(KEY_CASH_BALANCE, cashBalance)
            .putLong(KEY_EBUDGET_BALANCE, ebudgetBalance)
            .putBoolean(KEY_VALUES_VISIBLE, valuesVisible)
            .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
            .apply()

        notifyWidgetUpdate(context)
    }

    fun toggleVisibility(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getBoolean(KEY_VALUES_VISIBLE, true)
        val next = !current
        prefs.edit().putBoolean(KEY_VALUES_VISIBLE, next).apply()
        notifyWidgetUpdate(context)
        return next
    }

    fun notifyWidgetUpdate(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
        val componentName = ComponentName(context, KronWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
        if (appWidgetIds != null && appWidgetIds.isNotEmpty()) {
            val intent = Intent(context, KronWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
            }
            context.sendBroadcast(intent)
        }
    }

    fun formatBalance(amount: Long, visible: Boolean): String {
        return if (!visible) {
            HIDDEN_MONEY
        } else {
            formatIdr(amount)
        }
    }

    fun pinWidgetToHomeScreen(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return false
            val provider = ComponentName(context, KronWidgetProvider::class.java)
            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                return appWidgetManager.requestPinAppWidget(provider, null, null)
            }
        }
        return false
    }
}

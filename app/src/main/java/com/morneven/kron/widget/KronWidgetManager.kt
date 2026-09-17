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
    val currency: WidgetCurrency = WidgetCurrency.IDR,
)

object KronWidgetManager {
    private const val PREFS_NAME = "kron_widget_cache"
    private const val KEY_ACCOUNT_NAME = "account_name"
    private const val KEY_TOTAL_BALANCE = "total_balance"
    private const val KEY_CASH_BALANCE = "cash_balance"
    private const val KEY_EBUDGET_BALANCE = "ebudget_balance"
    private const val KEY_VALUES_VISIBLE = "values_visible"
    private const val KEY_DEFAULT_CURRENCY = "default_currency"
    private const val KEY_WIDGET_CURRENCY = "widget_currency"
    private const val KEY_LAST_UPDATED = "last_updated"

    const val HIDDEN_MONEY = "Rp \u2022\u2022\u2022\u2022\u2022\u2022"

    fun getSnapshot(context: Context, appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID): KronWidgetSnapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val visible = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            isWidgetVisible(context, appWidgetId)
        } else {
            prefs.getBoolean(KEY_VALUES_VISIBLE, true)
        }
        val currency = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            getWidgetCurrency(context, appWidgetId)
        } else {
            getDefaultCurrency(context)
        }
        return KronWidgetSnapshot(
            accountName = prefs.getString(KEY_ACCOUNT_NAME, "Akun Utama") ?: "Akun Utama",
            totalBalance = prefs.getLong(KEY_TOTAL_BALANCE, 0L),
            cashBalance = prefs.getLong(KEY_CASH_BALANCE, 0L),
            ebudgetBalance = prefs.getLong(KEY_EBUDGET_BALANCE, 0L),
            valuesVisible = visible,
            lastUpdated = prefs.getLong(KEY_LAST_UPDATED, 0L),
            currency = currency,
        )
    }

    fun isWidgetVisible(context: Context, appWidgetId: Int): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val perWidgetKey = "${KEY_VALUES_VISIBLE}_$appWidgetId"
        return if (prefs.contains(perWidgetKey)) {
            prefs.getBoolean(perWidgetKey, true)
        } else {
            prefs.getBoolean(KEY_VALUES_VISIBLE, true)
        }
    }

    fun toggleWidgetVisibility(context: Context, appWidgetId: Int): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val perWidgetKey = "${KEY_VALUES_VISIBLE}_$appWidgetId"
        val current = isWidgetVisible(context, appWidgetId)
        val next = !current
        prefs.edit().putBoolean(perWidgetKey, next).apply()
        notifySingleWidgetUpdate(context, appWidgetId)
        return next
    }

    fun toggleWidgetVisibilitySilent(context: Context, appWidgetId: Int): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val perWidgetKey = "${KEY_VALUES_VISIBLE}_$appWidgetId"
        val current = isWidgetVisible(context, appWidgetId)
        val next = !current
        prefs.edit().putBoolean(perWidgetKey, next).apply()
        return next
    }

    fun removeWidgetPreferences(context: Context, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (id in appWidgetIds) {
            editor.remove("${KEY_VALUES_VISIBLE}_$id")
            editor.remove("${KEY_WIDGET_CURRENCY}_$id")
        }
        editor.apply()
    }

    fun updateCache(
        context: Context,
        accountName: String,
        totalBalance: Long,
        cashBalance: Long,
        ebudgetBalance: Long,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_ACCOUNT_NAME, accountName)
            .putLong(KEY_TOTAL_BALANCE, totalBalance)
            .putLong(KEY_CASH_BALANCE, cashBalance)
            .putLong(KEY_EBUDGET_BALANCE, ebudgetBalance)
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

    fun notifySingleWidgetUpdate(context: Context, appWidgetId: Int) {
        val intent = Intent(context, KronWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
        }
        context.sendBroadcast(intent)
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

    fun getDefaultCurrency(context: Context): WidgetCurrency {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_DEFAULT_CURRENCY, WidgetCurrency.IDR.code)
        return WidgetCurrency.fromCode(code)
    }

    fun setDefaultCurrency(context: Context, currency: WidgetCurrency) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DEFAULT_CURRENCY, currency.code).apply()
        notifyWidgetUpdate(context)
    }

    fun getWidgetCurrency(context: Context, appWidgetId: Int): WidgetCurrency {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val perWidgetKey = "${KEY_WIDGET_CURRENCY}_$appWidgetId"
        val code = if (prefs.contains(perWidgetKey)) {
            prefs.getString(perWidgetKey, WidgetCurrency.IDR.code)
        } else {
            prefs.getString(KEY_DEFAULT_CURRENCY, WidgetCurrency.IDR.code)
        }
        return WidgetCurrency.fromCode(code)
    }

    fun setWidgetCurrency(context: Context, appWidgetId: Int, currency: WidgetCurrency) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val perWidgetKey = "${KEY_WIDGET_CURRENCY}_$appWidgetId"
        prefs.edit().putString(perWidgetKey, currency.code).apply()
        notifySingleWidgetUpdate(context, appWidgetId)
    }

    fun cycleWidgetCurrency(context: Context, appWidgetId: Int): WidgetCurrency {
        val current = getWidgetCurrency(context, appWidgetId)
        val all = WidgetCurrency.ALL
        val currentIndex = all.indexOf(current).coerceAtLeast(0)
        val nextIndex = (currentIndex + 1) % all.size
        val nextCurrency = all[nextIndex]
        setWidgetCurrency(context, appWidgetId, nextCurrency)
        return nextCurrency
    }

    /**
     * Cycles the per-widget currency and persists it without broadcasting an update.
     * Use this when the caller will immediately call [KronWidgetProvider.updateAppWidget]
     * to avoid the extra broadcast round-trip that causes visible lag.
     */
    fun cycleWidgetCurrencySilent(context: Context, appWidgetId: Int): WidgetCurrency {
        val current = getWidgetCurrency(context, appWidgetId)
        val all = WidgetCurrency.ALL
        val nextIndex = (all.indexOf(current).coerceAtLeast(0) + 1) % all.size
        val next = all[nextIndex]
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("${KEY_WIDGET_CURRENCY}_$appWidgetId", next.code).apply()
        return next
    }


    fun formatBalance(
        amount: Long,
        visible: Boolean,
        currency: WidgetCurrency = WidgetCurrency.IDR,
        context: Context? = null,
    ): String {
        return if (context != null) {
            KronCurrencyManager.formatCurrencyAmount(amount, currency, visible, context)
        } else {
            if (!visible) {
                "${currency.symbol}\u2022\u2022\u2022\u2022\u2022\u2022"
            } else if (currency == WidgetCurrency.IDR) {
                formatIdr(amount)
            } else {
                "${currency.symbol}$amount"
            }
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

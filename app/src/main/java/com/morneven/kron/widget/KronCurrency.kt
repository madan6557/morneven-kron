package com.morneven.kron.widget

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.morneven.kron.ui.components.formatIdr
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Supported currencies for KRON Android Widget and displays.
 *
 * KRM (Kron Morneven) is the official currency of the fantasy world Morneven.
 * Starting reference rate: 1 Kr = Rp 1.200. Fluctuates once per successful rate sync.
 */
enum class WidgetCurrency(
    val code: String,        // 3-character ISO-style code shown as widget badge
    val shortCode: String,   // Short symbol for compact contexts
    val symbol: String,      // Prefix for monetary formatting
    val displayName: String, // Full descriptive name shown in settings
    val fixedRateFromIdr: Double?, // Fixed rate if not market-driven; null = dynamic
    val fallbackRateFromIdr: Double, // Offline fallback (units of this currency per 1 IDR)
) {
    IDR(
        code = "IDR",
        shortCode = "Rp",
        symbol = "Rp ",
        displayName = "Rupiah Indonesia",
        fixedRateFromIdr = 1.0,
        fallbackRateFromIdr = 1.0,
    ),
    KRON(
        code = "KRM",
        shortCode = "Kr",
        symbol = "Kr ",
        displayName = "Kron Morneven (Kr)",
        fixedRateFromIdr = null, // Dynamic synthetic rate; use KronCurrencyManager.getRate()
        fallbackRateFromIdr = 1.0 / 1200.0,
    ),
    USD(
        code = "USD",
        shortCode = "$",
        symbol = "$ ",
        displayName = "US Dollar (\$)",
        fixedRateFromIdr = null,
        fallbackRateFromIdr = 1.0 / 16200.0,
    ),
    EUR(
        code = "EUR",
        shortCode = "\u20ac",
        symbol = "\u20ac ",
        displayName = "Euro (\u20ac)",
        fixedRateFromIdr = null,
        fallbackRateFromIdr = 1.0 / 17500.0,
    ),
    SGD(
        code = "SGD",
        shortCode = "S\$",
        symbol = "S\$ ",
        displayName = "Singapore Dollar (S\$)",
        fixedRateFromIdr = null,
        fallbackRateFromIdr = 1.0 / 12500.0,
    ),
    JPY(
        code = "JPY",
        shortCode = "\u00a5",
        symbol = "\u00a5 ",
        displayName = "Japanese Yen (\u00a5)",
        fixedRateFromIdr = null,
        fallbackRateFromIdr = 1.0 / 110.0,
    );

    companion object {
        val ALL = entries.toTypedArray()

        fun fromCode(code: String?): WidgetCurrency {
            if (code == null) return IDR
            return entries.find {
                it.code.equals(code, ignoreCase = true) ||
                    it.shortCode.equals(code, ignoreCase = true) ||
                    it.name.equals(code, ignoreCase = true)
            } ?: IDR
        }
    }
}

object KronCurrencyManager {
    private const val PREFS_RATES = "kron_currency_rates"
    private const val KEY_PREFIX_RATE = "rate_"
    private const val KEY_LAST_SYNCED = "rates_last_synced_at"
    private const val KEY_KRM_IDR_RATE = "krm_synthetic_idr_rate"
    private const val API_URL = "https://open.er-api.com/v6/latest/IDR"

    /** 30 minutes in milliseconds -- matches widget update period. */
    private const val STALE_THRESHOLD_MS = 30L * 60L * 1000L

    /** Starting reference rate for Kron Morneven: 1 Kr = Rp 1.200. */
    const val KRM_BASE_IDR = 1200.0

    /** Hard floor: KRM cannot drop below this IDR value to prevent nonsensical rates. */
    private const val KRM_FLOOR_IDR = 100.0

    private val syncMutex = Mutex()

    /** Backwards-compatible alias for code that still reads the integer form. */
    val MORNEVEN_KRON_IN_IDR: Long get() = KRM_BASE_IDR.toLong()

    // ------------------------------------------------------------------
    // KRM synthetic fluctuation
    // ------------------------------------------------------------------

    /**
     * Returns the current synthetic KRM-to-IDR rate (IDR per 1 Kr).
     * Defaults to [KRM_BASE_IDR] if no synthetic value has been generated yet.
     */
    fun getKrmIdrRate(context: Context?): Double {
        if (context == null) return KRM_BASE_IDR
        val prefs = context.getSharedPreferences(PREFS_RATES, Context.MODE_PRIVATE)
        return prefs.getString(KEY_KRM_IDR_RATE, null)?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 } ?: KRM_BASE_IDR
    }

    /**
     * Applies one synthetic market-fluctuation tick to the KRM rate and persists it.
     *
     * Behavior:
     * - 30% chance: large swing of 5-15% from the current rate.
     * - 70% chance: small movement of 0.1-2% from the current rate.
     * - Mean reversion of 8% per tick pulls rate back toward [KRM_BASE_IDR].
     * - The floor prevents invalid rates; there is no arbitrary upper cap.
     *
     * @return the new IDR-per-Kr rate after fluctuation.
     */
    fun refreshKronFluctuation(context: Context): Double {
        val prefs = context.getSharedPreferences(PREFS_RATES, Context.MODE_PRIVATE)
        val current = getKrmIdrRate(context)

        val isLarge = Random.nextDouble() < 0.30
        val magnitude = if (isLarge) {
            Random.nextDouble(0.05, 0.15)   // 5-15% for large swings
        } else {
            Random.nextDouble(0.001, 0.02)  // 0.1-2% for small movements
        }
        val newRate = nextKrmIdrRate(current, magnitude, Random.nextBoolean())

        prefs.edit().putString(KEY_KRM_IDR_RATE, newRate.toString()).apply()
        return newRate
    }

    internal fun nextKrmIdrRate(current: Double, magnitude: Double, increase: Boolean): Double {
        val startingRate = current.takeIf { it.isFinite() && it > 0.0 } ?: KRM_BASE_IDR
        val reversion = (KRM_BASE_IDR - startingRate) * 0.08
        val direction = if (increase) 1.0 else -1.0
        val next = startingRate + startingRate * magnitude * direction + reversion
        return if (next.isFinite()) next.coerceAtLeast(KRM_FLOOR_IDR) else startingRate
    }

    // ------------------------------------------------------------------
    // Staleness and auto-sync
    // ------------------------------------------------------------------

    /** Returns true if exchange rates have not been synced within [STALE_THRESHOLD_MS]. */
    fun isRatesStale(context: Context?): Boolean {
        if (context == null) return false
        return System.currentTimeMillis() - getLastSyncedTime(context) > STALE_THRESHOLD_MS
    }

    /**
     * Syncs only when stale. A unique network-constrained worker calls this after connectivity returns.
     */
    suspend fun checkAndAutoSync(context: Context): Result<Map<String, Double>> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            if (isRatesStale(context)) performRateSync(context) else Result.success(emptyMap())
        }
    }

    // ------------------------------------------------------------------
    // Rate retrieval
    // ------------------------------------------------------------------

    /**
     * Returns how many units of [currency] equal 1 IDR.
     *
     * For KRM the synthetic rate is used; for IDR returns 1.0; for market currencies
     * returns the cached API rate or [WidgetCurrency.fallbackRateFromIdr].
     */
    fun getRate(context: Context?, currency: WidgetCurrency): Double {
        if (currency == WidgetCurrency.IDR) return 1.0
        if (currency == WidgetCurrency.KRON) {
            val idrPerKrm = getKrmIdrRate(context)
            return if (idrPerKrm > 0.0) 1.0 / idrPerKrm else 1.0 / KRM_BASE_IDR
        }
        if (currency.fixedRateFromIdr != null) return currency.fixedRateFromIdr
        if (context == null) return currency.fallbackRateFromIdr
        val prefs = context.getSharedPreferences(PREFS_RATES, Context.MODE_PRIVATE)
        val saved = prefs.getString("$KEY_PREFIX_RATE${currency.code}", null)
        return saved?.toDoubleOrNull() ?: currency.fallbackRateFromIdr
    }

    /**
     * Returns how many IDR equal 1 unit of [currency].
     *
     * This is the human-readable "1 KRM = Rp X.XXX" direction.
     */
    fun getIdrPerUnit(context: Context?, currency: WidgetCurrency): Double {
        if (currency == WidgetCurrency.IDR) return 1.0
        if (currency == WidgetCurrency.KRON) return getKrmIdrRate(context)
        val rate = getRate(context, currency)
        return if (rate > 0.0) 1.0 / rate else 1.0
    }

    fun getLastSyncedTime(context: Context?): Long {
        if (context == null) return 0L
        val prefs = context.getSharedPreferences(PREFS_RATES, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_SYNCED, 0L)
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    /**
     * Formats an IDR amount into the display string for [currency].
     * If [visible] is false, returns a masked placeholder.
     */
    fun formatCurrencyAmount(
        amountIdr: Long,
        currency: WidgetCurrency,
        visible: Boolean,
        context: Context? = null,
    ): String {
        if (!visible) return "${currency.symbol}\u2022\u2022\u2022\u2022\u2022\u2022"
        if (currency == WidgetCurrency.IDR) return formatIdr(amountIdr)

        val rate = getRate(context, currency)
        val converted = amountIdr.toDouble() * rate

        val symbols = DecimalFormatSymbols(Locale.forLanguageTag("id-ID")).apply {
            groupingSeparator = '.'
            decimalSeparator = ','
        }

        return if (currency == WidgetCurrency.JPY) {
            "${currency.symbol}${DecimalFormat("#,##0", symbols).format(converted)}"
        } else {
            val isExactInteger = Math.abs(converted - Math.round(converted)) < 0.0001
            val pattern = if (isExactInteger && Math.abs(converted) >= 100) "#,##0" else "#,##0.00"
            "${currency.symbol}${DecimalFormat(pattern, symbols).format(converted)}"
        }
    }

    // ------------------------------------------------------------------
    // Online sync
    // ------------------------------------------------------------------

    /** Fetches market rates. KRM changes once only after a successful fetch, never while offline. */
    suspend fun syncRatesOnline(context: Context): Result<Map<String, Double>> = withContext(Dispatchers.IO) {
        syncMutex.withLock { performRateSync(context) }
    }

    private fun performRateSync(context: Context): Result<Map<String, Double>> {
        if (!hasInternetAccess(context)) {
            return Result.failure(IllegalStateException("Tidak ada koneksi internet"))
        }
        return try {
            val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "KRON-App/1.0")
                setRequestProperty("Accept", "application/json")
            }
            val responseText = try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    return Result.failure(Exception("HTTP ${conn.responseCode} dari server kurs"))
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

            val json = JSONObject(responseText)
            if (json.optString("result", "") != "success") {
                return Result.failure(Exception("Format response kurs tidak valid"))
            }

            val ratesObj = json.getJSONObject("rates")
            val prefs = context.getSharedPreferences(PREFS_RATES, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            val savedRates = mutableMapOf<String, Double>()

            for (curr in WidgetCurrency.entries) {
                if (curr == WidgetCurrency.IDR || curr == WidgetCurrency.KRON) continue
                if (curr.fixedRateFromIdr != null) {
                    savedRates[curr.code] = curr.fixedRateFromIdr
                    continue
                }
                if (ratesObj.has(curr.code)) {
                    val rate = ratesObj.getDouble(curr.code)
                    editor.putString("$KEY_PREFIX_RATE${curr.code}", rate.toString())
                    savedRates[curr.code] = rate
                }
            }

            editor.putLong(KEY_LAST_SYNCED, System.currentTimeMillis())
            editor.apply()

            val newKrmIdr = refreshKronFluctuation(context)
            savedRates["KRM"] = 1.0 / newKrmIdr
            runCatching { KronWidgetManager.notifyWidgetUpdate(context) }
            Result.success(savedRates)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun hasInternetAccess(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

package com.morneven.kron.widget

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.morneven.kron.ui.components.formatIdr
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Historical exchange rate record (rate in IDR per 1 unit of currency).
 */
data class KrmRateHistoryEntry(
    val timestampMs: Long,
    val rate: Double,
)

/**
 * Supported currencies for KRON Android Widget and displays.
 *
 * KRM (Kron Morneven) is the currency of Morneven.
 * Starting reference rate: 1 Kr = Rp 1.200. Fluctuates once per successful rate sync.
 */
enum class WidgetCurrency(
    val code: String,
    val shortCode: String,
    val symbol: String,
    val displayName: String,
    val fixedRateFromIdr: Double?,
    val fallbackRateFromIdr: Double,
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
        fixedRateFromIdr = null,
        fallbackRateFromIdr = 1.0 / 1200.0,
    ),
    USD(
        code = "USD",
        shortCode = "$",
        symbol = "$ ",
        displayName = "US Dollar ($)",
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
        shortCode = "S$",
        symbol = "S$ ",
        displayName = "Singapore Dollar (S$)",
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

internal data class KrmFluctuationRoll(
    val tier: Int,
    val upChance: Double,
    val increase: Boolean,
    val magnitude: Double,
    val nextWaveTicks: Int,
    val nextWaveDirection: Boolean,
    val nextSentimentTicks: Int,
    val nextSentimentUpChance: Double,
    val nextTensionTicks: Int,
    val nextMarketTension: Double,
)

object KronCurrencyManager {
    private const val PREFS_RATES = "kron_currency_rates"
    private const val KEY_PREFIX_RATE = "rate_"
    private const val KEY_PREFIX_HISTORY = "currency_rate_history_"
    private const val KEY_LAST_SYNCED = "rates_last_synced_at"
    private const val KEY_KRM_IDR_RATE = "krm_synthetic_idr_rate"
    private const val KEY_KRM_HISTORY = "krm_synthetic_rate_history"
    private const val KEY_KRM_LAST_TICK_AT = "krm_last_tick_at"
    private const val KEY_KRM_WAVE_TICKS = "krm_wave_ticks_remaining"
    private const val KEY_KRM_WAVE_DIR = "krm_wave_direction"
    private const val KEY_KRM_LAST_DIR = "krm_last_direction"
    private const val KEY_KRM_SENTIMENT_TICKS = "krm_sentiment_ticks_remaining"
    private const val KEY_KRM_SENTIMENT_UP_CHANCE = "krm_sentiment_up_chance"
    private const val KEY_KRM_TENSION_TICKS = "krm_tension_ticks_remaining"
    private const val KEY_KRM_MARKET_TENSION = "krm_market_tension"
    private const val KEY_FIAT_BACKFILL_EPOCH_DAY = "fiat_history_backfill_epoch_day"

    private const val TRADINGVIEW_SCAN_URL = "https://scanner.tradingview.com/forex/scan"
    private const val COINGECKO_RATES_URL = "https://api.coingecko.com/api/v3/exchange_rates"
    private const val FRANKFURTER_BASE_URL = "https://api.frankfurter.dev/v1"

    internal val API_URLS = listOf(
        "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.json",
        "https://latest.currency-api.pages.dev/v1/currencies/usd.json",
        "https://open.er-api.com/v6/latest/USD",
        "https://api.exchangerate-api.com/v4/latest/USD",
    )

    /** 30 minutes in milliseconds. */
    private const val STALE_THRESHOLD_MS = 30L * 60L * 1000L

    /** Starting reference rate for Kron Morneven: 1 Kr = Rp 1.200. */
    const val KRM_BASE_IDR = 1200.0

    /** Hard floor: KRM cannot drop below this IDR value. */
    private const val KRM_FLOOR_IDR = 100.0

    private const val MAX_HISTORY_POINTS = 500

    private val syncMutex = Mutex()
    private val historyMutex = Mutex()

    val MORNEVEN_KRON_IN_IDR: Long get() = KRM_BASE_IDR.toLong()

    // ------------------------------------------------------------------
    // KRM synthetic fluctuation (Continuous Dynamic Tension + Sentiment + Wave)
    // ------------------------------------------------------------------

    fun getKrmIdrRate(context: Context?): Double {
        if (context == null) return KRM_BASE_IDR
        val prefs = context.getSharedPreferences(PREFS_RATES, Context.MODE_PRIVATE)
        return prefs.getString(KEY_KRM_IDR_RATE, null)?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 } ?: KRM_BASE_IDR
    }

    fun isKrmTickDue(context: Context?): Boolean {
        if (context == null) return false
        val prefs = context.getSharedPreferences(PREFS_RATES, Context.MODE_PRIVATE)
        val lastTickAt = prefs.getLong(KEY_KRM_LAST_TICK_AT, 0L)
        return System.currentTimeMillis() - lastTickAt >= STALE_THRESHOLD_MS
    }

    /**
     * Applies one 30-minute market-fluctuation tick to the KRM rate and persists it.
     *
     * Unpredictable Continuous Dynamic Tension + 4-step pipeline:
     * - Dynamic Market Tension (0.0 .. 1.0, u^4 curve) with non-linear duration (2..2160 ticks / 1h..45d)
     *   and 0.5% per-tick sudden regime break.
     * - Step 1: Gacha Tier using dynamic probabilities derived from current tension
     *   (Tier 4: 0.002%..1.20%, Tier 3: 0.03%..4.50%, Tier 2: 3.00%..30.00%, Tier 1: remainder).
     * - Step 2: Gacha / Persist Bimodal Sentiment Ratio (25%-40% bearish or 60%-75% bullish for 6-16 ticks).
     * - Step 3: Gacha Direction (Up / Down) using the active sentiment ratio.
     * - Step 4: Gacha Magnitude within the selected tier's percentage range, plus 50/50 post-incident
     *   reaction (Exhaustion Cooldown vs Contagion Domino) on Tier 3/4.
     */
    fun refreshKronFluctuation(context: Context): Double {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_RATES, Context.MODE_PRIVATE)
        val current = getKrmIdrRate(context)
        val waveTicks = prefs.getInt(KEY_KRM_WAVE_TICKS, 0).coerceAtLeast(0)
        val waveDir = prefs.getBoolean(KEY_KRM_WAVE_DIR, true)
        val sentimentTicks = prefs.getInt(KEY_KRM_SENTIMENT_TICKS, 0).coerceAtLeast(0)
        val sentimentUpChance = prefs.getString(KEY_KRM_SENTIMENT_UP_CHANCE, null)?.toDoubleOrNull() ?: 0.0
        val tensionTicks = prefs.getInt(KEY_KRM_TENSION_TICKS, 0).coerceAtLeast(0)
        val marketTension = prefs.getString(KEY_KRM_MARKET_TENSION, null)?.toDoubleOrNull() ?: -1.0

        val roll = rollKrmFluctuation(
            waveTicksRemaining = waveTicks,
            waveDirection = waveDir,
            sentimentTicksRemaining = sentimentTicks,
            sentimentUpChance = sentimentUpChance,
            tensionTicksRemaining = tensionTicks,
            marketTension = marketTension,
            tierRoll = Random.nextDouble(),
            sentimentRoll = Random.nextDouble(),
            directionRoll = Random.nextDouble(),
            magnitudeRoll = Random.nextDouble(),
            tensionRoll = Random.nextDouble(),
            durationRoll = Random.nextDouble(),
            breakRoll = Random.nextDouble(),
        )

        val newRate = nextKrmIdrRate(current, roll.magnitude, roll.increase)

        prefs.edit()
            .putString(KEY_KRM_IDR_RATE, newRate.toString())
            .putLong(KEY_KRM_LAST_TICK_AT, now)
            .putInt(KEY_KRM_WAVE_TICKS, roll.nextWaveTicks)
            .putBoolean(KEY_KRM_WAVE_DIR, roll.nextWaveDirection)
            .putBoolean(KEY_KRM_LAST_DIR, roll.increase)
            .putInt(KEY_KRM_SENTIMENT_TICKS, roll.nextSentimentTicks)
            .putString(KEY_KRM_SENTIMENT_UP_CHANCE, roll.nextSentimentUpChance.toString())
            .putInt(KEY_KRM_TENSION_TICKS, roll.nextTensionTicks)
            .putString(KEY_KRM_MARKET_TENSION, roll.nextMarketTension.toString())
            .apply()

        recordKrmRate(context, newRate, now)
        return newRate
    }

    internal fun rollMarketTension(tensionRoll: Double = Random.nextDouble()): Double {
        val u = tensionRoll.coerceIn(0.0, 1.0)
        return u * u * u * u
    }

    internal fun rollTensionDurationTicks(
        durationRoll: Double = Random.nextDouble(),
        tension: Double = 0.0,
    ): Int {
        val u = durationRoll.coerceIn(0.0, 1.0)
        val curved = u * u
        val maxTicks = if (tension > 0.50) 384 else 2160
        return (2 + (curved * (maxTicks - 2)).toInt()).coerceIn(2, maxTicks)
    }

    internal fun isValidMarketTension(value: Double): Boolean {
        return value.isFinite() && value in 0.0..1.0
    }

    internal fun rollBimodalSentimentUpChance(
        bullish: Boolean = Random.nextBoolean(),
        rangeRoll: Double = Random.nextDouble(),
    ): Double {
        val clamped = rangeRoll.coerceIn(0.0, 1.0)
        return if (bullish) {
            0.60 + clamped * 0.15
        } else {
            0.25 + clamped * 0.15
        }
    }

    internal fun isValidSentimentUpChance(value: Double): Boolean {
        return value.isFinite() && ((value in 0.25..0.40) || (value in 0.60..0.75))
    }

    internal fun rollKrmFluctuation(
        waveTicksRemaining: Int,
        waveDirection: Boolean,
        sentimentTicksRemaining: Int = 0,
        sentimentUpChance: Double = 0.0,
        tensionTicksRemaining: Int = 0,
        marketTension: Double = -1.0,
        tierRoll: Double = Random.nextDouble(),
        sentimentRoll: Double = Random.nextDouble(),
        directionRoll: Double = Random.nextDouble(),
        magnitudeRoll: Double = Random.nextDouble(),
        tensionRoll: Double = Random.nextDouble(),
        durationRoll: Double = Random.nextDouble(),
        breakRoll: Double = Random.nextDouble(),
    ): KrmFluctuationRoll {
        val clampedMag = magnitudeRoll.coerceIn(0.0, 1.0)
        val hasActiveTension = tensionTicksRemaining > 0 && isValidMarketTension(marketTension) && breakRoll >= 0.005
        val activeTension = if (hasActiveTension) {
            marketTension.coerceIn(0.0, 1.0)
        } else {
            rollMarketTension(tensionRoll)
        }
        val baseNextTensionTicks = if (hasActiveTension) {
            tensionTicksRemaining - 1
        } else {
            rollTensionDurationTicks(durationRoll, activeTension) - 1
        }

        if (waveTicksRemaining > 0) {
            val waveBias = 0.65 + (sentimentRoll.coerceIn(0.0, 1.0) * 0.10)
            val activeUpChance = if (waveDirection) waveBias else (1.0 - waveBias)
            val increase = directionRoll < activeUpChance
            val magnitude = 0.012 + clampedMag * (0.048 - 0.012)
            val preservedSentimentUp = if (isValidSentimentUpChance(sentimentUpChance)) {
                sentimentUpChance
            } else {
                rollBimodalSentimentUpChance(bullish = waveDirection, rangeRoll = sentimentRoll)
            }
            val preservedSentimentTicks = if (sentimentTicksRemaining > 0) sentimentTicksRemaining else Random.nextInt(6, 17)
            val preservedTension = if (isValidMarketTension(marketTension)) marketTension else activeTension
            val preservedTensionTicks = if (tensionTicksRemaining > 0) tensionTicksRemaining else baseNextTensionTicks.coerceAtLeast(1)
            return KrmFluctuationRoll(
                tier = 0,
                upChance = activeUpChance,
                increase = increase,
                magnitude = magnitude,
                nextWaveTicks = waveTicksRemaining - 1,
                nextWaveDirection = waveDirection,
                nextSentimentTicks = preservedSentimentTicks,
                nextSentimentUpChance = preservedSentimentUp,
                nextTensionTicks = preservedTensionTicks,
                nextMarketTension = preservedTension,
            )
        }

        // Step 1: Dynamic Tier Chances derived from Continuous Market Tension (0.0 .. 1.0)
        val tSq = activeTension * activeTension
        val tier4Chance = 0.00002 + tSq * (0.0120 - 0.00002) // 0.002% .. 1.20%
        val tier3Chance = 0.00030 + tSq * (0.0450 - 0.00030) // 0.030% .. 4.50%
        val tier2Chance = 0.03000 + activeTension * (0.3000 - 0.03000) // 3.00% .. 30.00%
        val tier1Chance = (1.0 - (tier2Chance + tier3Chance + tier4Chance)).coerceAtLeast(0.50)

        val tier = when {
            tierRoll < tier1Chance -> 1
            tierRoll < tier1Chance + tier2Chance -> 2
            tierRoll < tier1Chance + tier2Chance + tier3Chance -> 3
            else -> 4
        }

        // Step 2: Gacha / Check Persistent Sentiment Ratio (25%-40% bearish or 60%-75% bullish)
        val hasActiveSentiment = sentimentTicksRemaining > 0 && isValidSentimentUpChance(sentimentUpChance)
        val activeUpChance = if (hasActiveSentiment) {
            sentimentUpChance
        } else {
            val bullish = sentimentRoll >= 0.5
            val subRoll = if (bullish) (sentimentRoll - 0.5) * 2.0 else sentimentRoll * 2.0
            rollBimodalSentimentUpChance(bullish = bullish, rangeRoll = subRoll)
        }
        val baseNextSentimentTicks = if (hasActiveSentiment) {
            sentimentTicksRemaining - 1
        } else {
            Random.nextInt(6, 17) - 1
        }

        // Step 3: Gacha Direction (Up / Down)
        val increase = directionRoll < activeUpChance

        // Step 4: Gacha Magnitude + Post-Incident Reaction (50% Exhaustion Cooldown vs 50% Contagion Domino)
        return when (tier) {
            1 -> {
                val magnitude = 0.0005 + clampedMag * (0.015 - 0.0005)
                KrmFluctuationRoll(
                    tier = 1,
                    upChance = activeUpChance,
                    increase = increase,
                    magnitude = magnitude,
                    nextWaveTicks = 0,
                    nextWaveDirection = waveDirection,
                    nextSentimentTicks = baseNextSentimentTicks,
                    nextSentimentUpChance = activeUpChance,
                    nextTensionTicks = baseNextTensionTicks,
                    nextMarketTension = activeTension,
                )
            }
            2 -> {
                val magnitude = 0.015 + clampedMag * (0.045 - 0.015)
                KrmFluctuationRoll(
                    tier = 2,
                    upChance = activeUpChance,
                    increase = increase,
                    magnitude = magnitude,
                    nextWaveTicks = 0,
                    nextWaveDirection = waveDirection,
                    nextSentimentTicks = baseNextSentimentTicks,
                    nextSentimentUpChance = activeUpChance,
                    nextTensionTicks = baseNextTensionTicks,
                    nextMarketTension = activeTension,
                )
            }
            3, 4 -> {
                val magnitude = if (tier == 3) {
                    0.045 + clampedMag * (0.15 - 0.045)
                } else {
                    0.15 + clampedMag * (0.28 - 0.15)
                }
                val waveTicks = Random.nextInt(2, 6)
                val alignedSentiment = rollBimodalSentimentUpChance(bullish = increase, rangeRoll = sentimentRoll)
                val isContagion = breakRoll >= 0.50
                val postShockTension = if (isContagion) {
                    (0.60 + 0.40 * tensionRoll.coerceIn(0.0, 1.0)).coerceIn(0.60, 1.0)
                } else {
                    (0.05 * tensionRoll.coerceIn(0.0, 1.0)).coerceIn(0.0, 0.05)
                }
                val postShockTensionTicks = if (isContagion) {
                    (48 + (durationRoll.coerceIn(0.0, 1.0) * (384 - 48)).toInt()).coerceIn(48, 384)
                } else {
                    rollTensionDurationTicks(
                        durationRoll = 0.35 + 0.65 * durationRoll.coerceIn(0.0, 1.0),
                        tension = postShockTension,
                    )
                }
                KrmFluctuationRoll(
                    tier = tier,
                    upChance = activeUpChance,
                    increase = increase,
                    magnitude = magnitude,
                    nextWaveTicks = waveTicks,
                    nextWaveDirection = increase,
                    nextSentimentTicks = Random.nextInt(6, 17),
                    nextSentimentUpChance = alignedSentiment,
                    nextTensionTicks = postShockTensionTicks,
                    nextMarketTension = postShockTension,
                )
            }
            else -> error("Unreachable tier $tier")
        }
    }

    internal fun nextKrmIdrRate(current: Double, magnitude: Double, increase: Boolean): Double {
        val startingRate = current.takeIf { it.isFinite() && it > 0.0 } ?: KRM_BASE_IDR
        val direction = if (increase) 1.0 else -1.0
        val next = startingRate * (1.0 + magnitude * direction)
        return if (next.isFinite()) next.coerceAtLeast(KRM_FLOOR_IDR) else startingRate
    }

    // ------------------------------------------------------------------
    // Rate History (KRM & Foreign Currencies)
    // ------------------------------------------------------------------

    fun getKrmRateHistory(context: Context?): List<KrmRateHistoryEntry> {
        return getCurrencyRateHistory(context, WidgetCurrency.KRON)
    }

    fun getCurrencyRateHistory(context: Context?, currency: WidgetCurrency): List<KrmRateHistoryEntry> {
        if (context == null || currency == WidgetCurrency.IDR) return emptyList()
        val prefs = context.getSharedPreferences(PREFS_RATES, Context.MODE_PRIVATE)
        val key = historyKeyFor(currency)
        val raw = prefs.getString(key, null)
        val currentIdrRate = getIdrPerUnit(context, currency)
        val baseIdrRate = if (currency == WidgetCurrency.KRON) {
            KRM_BASE_IDR
        } else {
            1.0 / currency.fallbackRateFromIdr
        }

        if (raw.isNullOrBlank()) {
            val now = System.currentTimeMillis()
            val initial = if (Math.abs(currentIdrRate - baseIdrRate) > 0.01) {
                listOf(
                    KrmRateHistoryEntry(now - 3600_000L, baseIdrRate),
                    KrmRateHistoryEntry(now, currentIdrRate),
                )
            } else {
                listOf(
                    KrmRateHistoryEntry(now - 3600_000L, currentIdrRate),
                    KrmRateHistoryEntry(now, currentIdrRate),
                )
            }
            if (currency == WidgetCurrency.KRON) {
                saveCurrencyRateHistory(context, currency, initial)
            }
            return initial
        }

        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<KrmRateHistoryEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val time = obj.optLong("time", 0L)
                val rate = obj.optDouble("rate", 0.0)
                if (time > 0L && rate > 0.0 && rate.isFinite()) {
                    list.add(KrmRateHistoryEntry(time, rate))
                }
            }
            if (list.isEmpty()) {
                val now = System.currentTimeMillis()
                listOf(
                    KrmRateHistoryEntry(now - 3600_000L, currentIdrRate),
                    KrmRateHistoryEntry(now, currentIdrRate),
                )
            } else {
                list.sortedBy { it.timestampMs }
            }
        } catch (_: Exception) {
            val now = System.currentTimeMillis()
            listOf(
                KrmRateHistoryEntry(now - 3600_000L, currentIdrRate),
                KrmRateHistoryEntry(now, currentIdrRate),
            )
        }
    }

    fun recordKrmRate(context: Context, rate: Double, timestampMs: Long = System.currentTimeMillis()) {
        recordCurrencyRate(context, WidgetCurrency.KRON, rate, timestampMs)
    }

    fun recordCurrencyRate(
        context: Context,
        currency: WidgetCurrency,
        idrPerUnit: Double,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        if (currency == WidgetCurrency.IDR || !idrPerUnit.isFinite() || idrPerUnit <= 0.0) return
        val currentHistory = getCurrencyRateHistory(context, currency).toMutableList()
        currentHistory.add(KrmRateHistoryEntry(timestampMs, idrPerUnit))
        val trimmed = currentHistory
            .sortedBy { it.timestampMs }
            .let { if (it.size > MAX_HISTORY_POINTS) it.takeLast(MAX_HISTORY_POINTS) else it }
        saveCurrencyRateHistory(context, currency, trimmed)
    }

    private fun historyKeyFor(currency: WidgetCurrency): String {
        return if (currency == WidgetCurrency.KRON) KEY_KRM_HISTORY else "$KEY_PREFIX_HISTORY${currency.code}"
    }

    private fun saveCurrencyRateHistory(
        context: Context,
        currency: WidgetCurrency,
        list: List<KrmRateHistoryEntry>,
    ) {
        val prefs = context.getSharedPreferences(PREFS_RATES, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (entry in list) {
            val obj = JSONObject()
            obj.put("time", entry.timestampMs)
            obj.put("rate", entry.rate)
            array.put(obj)
        }
        prefs.edit().putString(historyKeyFor(currency), array.toString()).apply()
    }

    fun clearKrmRateHistory(context: Context) {
        val now = System.currentTimeMillis()
        val currentRate = getKrmIdrRate(context)
        val initial = listOf(
            KrmRateHistoryEntry(now - 1800_000L, KRM_BASE_IDR),
            KrmRateHistoryEntry(now, currentRate),
        )
        saveCurrencyRateHistory(context, WidgetCurrency.KRON, initial)
    }

    /**
     * Loads up to 1 year of daily historical market rates from Frankfurter (ECB)
     * and merges them with local intraday points. Cached per calendar day.
     */
    suspend fun ensureHistoricalRatesLoaded(context: Context): Boolean = withContext(Dispatchers.IO) {
        historyMutex.withLock {
            val prefs = context.getSharedPreferences(PREFS_RATES, Context.MODE_PRIVATE)
            val todayEpochDay = LocalDate.now().toEpochDay()
            val lastBackfillDay = prefs.getLong(KEY_FIAT_BACKFILL_EPOCH_DAY, -1L)
            val hasSufficientPoints = getCurrencyRateHistory(context, WidgetCurrency.USD).size >= 30
            if (lastBackfillDay == todayEpochDay && hasSufficientPoints) {
                return@withLock true
            }
            if (!hasInternetAccess(context)) {
                return@withLock false
            }
            fetchAndMergeFiatHistory(context, todayEpochDay)
        }
    }

    private fun fetchAndMergeFiatHistory(context: Context, todayEpochDay: Long): Boolean {
        return try {
            val endDate = LocalDate.ofEpochDay(todayEpochDay)
            val startDate = endDate.minusDays(365)
            val url = "$FRANKFURTER_BASE_URL/$startDate..$endDate?base=USD&symbols=IDR,EUR,SGD,JPY"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "KRON-App/1.0")
                setRequestProperty("Accept", "application/json")
            }
            val responseText = try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return false
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

            val root = JSONObject(responseText)
            val ratesByDate = root.optJSONObject("rates") ?: return false
            val dateKeys = ratesByDate.keys().asSequence().toList().sorted()
            if (dateKeys.isEmpty()) return false

            val zone = ZoneId.systemDefault()
            val historicalPerCurrency = mutableMapOf<WidgetCurrency, MutableList<KrmRateHistoryEntry>>(
                WidgetCurrency.USD to mutableListOf(),
                WidgetCurrency.EUR to mutableListOf(),
                WidgetCurrency.SGD to mutableListOf(),
                WidgetCurrency.JPY to mutableListOf(),
            )

            var maxHistoricalMs = 0L
            for (dateStr in dateKeys) {
                val dayObj = ratesByDate.optJSONObject(dateStr) ?: continue
                val idrPerUsd = dayObj.optDouble("IDR", 0.0)
                if (idrPerUsd <= 0.0 || !idrPerUsd.isFinite()) continue

                val timestampMs = runCatching {
                    LocalDate.parse(dateStr).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                }.getOrDefault(0L)
                if (timestampMs <= 0L) continue
                if (timestampMs > maxHistoricalMs) maxHistoricalMs = timestampMs

                historicalPerCurrency[WidgetCurrency.USD]?.add(KrmRateHistoryEntry(timestampMs, idrPerUsd))

                for (curr in listOf(WidgetCurrency.EUR, WidgetCurrency.SGD, WidgetCurrency.JPY)) {
                    val currPerUsd = dayObj.optDouble(curr.code, 0.0)
                    if (currPerUsd > 0.0 && currPerUsd.isFinite()) {
                        val idrPerCurr = idrPerUsd / currPerUsd
                        historicalPerCurrency[curr]?.add(KrmRateHistoryEntry(timestampMs, idrPerCurr))
                    }
                }
            }

            for ((curr, histList) in historicalPerCurrency) {
                if (histList.isEmpty()) continue
                val existing = getCurrencyRateHistory(context, curr)
                val recentLocalTicks = existing.filter { it.timestampMs > maxHistoricalMs }
                val merged = (histList + recentLocalTicks).sortedBy { it.timestampMs }.toMutableList()
                val currentLive = getIdrPerUnit(context, curr)
                val now = System.currentTimeMillis()
                if (merged.isEmpty() || now - merged.last().timestampMs > 15 * 60 * 1000L) {
                    merged.add(KrmRateHistoryEntry(now, currentLive))
                }
                val trimmed = if (merged.size > MAX_HISTORY_POINTS) merged.takeLast(MAX_HISTORY_POINTS) else merged
                saveCurrencyRateHistory(context, curr, trimmed)
            }

            context.getSharedPreferences(PREFS_RATES, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_FIAT_BACKFILL_EPOCH_DAY, todayEpochDay)
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Backup & Restore
    // ------------------------------------------------------------------

    fun exportBackupData(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_RATES, Context.MODE_PRIVATE)
        val rate = getKrmIdrRate(context)
        val history = getKrmRateHistory(context)
        val historyArray = JSONArray()
        for (entry in history) {
            val obj = JSONObject()
            obj.put("time", entry.timestampMs)
            obj.put("rate", entry.rate)
            historyArray.put(obj)
        }

        val fiatRatesObj = JSONObject()
        val fiatHistoryObj = JSONObject()
        for (curr in WidgetCurrency.entries) {
            if (curr == WidgetCurrency.IDR || curr == WidgetCurrency.KRON) continue
            prefs.getString("$KEY_PREFIX_RATE${curr.code}", null)?.let { saved ->
                fiatRatesObj.put(curr.code, saved)
            }
            prefs.getString(historyKeyFor(curr), null)?.let { rawHist ->
                runCatching { JSONArray(rawHist) }.getOrNull()?.let { arr ->
                    fiatHistoryObj.put(curr.code, arr)
                }
            }
        }

        return JSONObject()
            .put("krmIdrRate", rate)
            .put("krmRateHistory", historyArray)
            .put("krmLastTickAt", prefs.getLong(KEY_KRM_LAST_TICK_AT, 0L))
            .put("krmWaveTicks", prefs.getInt(KEY_KRM_WAVE_TICKS, 0))
            .put("krmWaveDir", prefs.getBoolean(KEY_KRM_WAVE_DIR, true))
            .put("krmLastDir", prefs.getBoolean(KEY_KRM_LAST_DIR, true))
            .put("krmSentimentTicks", prefs.getInt(KEY_KRM_SENTIMENT_TICKS, 0))
            .put("krmSentimentUpChance", prefs.getString(KEY_KRM_SENTIMENT_UP_CHANCE, null)?.toDoubleOrNull() ?: 0.0)
            .put("krmTensionTicks", prefs.getInt(KEY_KRM_TENSION_TICKS, 0))
            .put("krmMarketTension", prefs.getString(KEY_KRM_MARKET_TENSION, null)?.toDoubleOrNull() ?: -1.0)
            .put("fiatRates", fiatRatesObj)
            .put("fiatRateHistory", fiatHistoryObj)
    }

    fun restoreBackupData(context: Context, json: JSONObject) {
        val rate = json.optDouble("krmIdrRate", -1.0)
        val prefs = context.getSharedPreferences(PREFS_RATES, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (rate > 0.0 && rate.isFinite()) {
            editor.putString(KEY_KRM_IDR_RATE, rate.toString())
        }
        val historyArray = json.optJSONArray("krmRateHistory")
        if (historyArray != null && historyArray.length() > 0) {
            editor.putString(KEY_KRM_HISTORY, historyArray.toString())
        }
        if (json.has("krmLastTickAt")) {
            val lastTickAt = json.optLong("krmLastTickAt", 0L).coerceAtLeast(0L)
            editor.putLong(KEY_KRM_LAST_TICK_AT, lastTickAt)
        }
        if (json.has("krmWaveTicks")) {
            editor.putInt(KEY_KRM_WAVE_TICKS, json.optInt("krmWaveTicks", 0).coerceAtLeast(0))
        }
        if (json.has("krmWaveDir")) {
            editor.putBoolean(KEY_KRM_WAVE_DIR, json.optBoolean("krmWaveDir", true))
        }
        if (json.has("krmLastDir")) {
            editor.putBoolean(KEY_KRM_LAST_DIR, json.optBoolean("krmLastDir", true))
        }
        if (json.has("krmSentimentTicks")) {
            editor.putInt(KEY_KRM_SENTIMENT_TICKS, json.optInt("krmSentimentTicks", 0).coerceAtLeast(0))
        }
        if (json.has("krmSentimentUpChance")) {
            val upChance = json.optDouble("krmSentimentUpChance", 0.0)
            if (isValidSentimentUpChance(upChance)) {
                editor.putString(KEY_KRM_SENTIMENT_UP_CHANCE, upChance.toString())
            }
        }
        if (json.has("krmTensionTicks")) {
            editor.putInt(KEY_KRM_TENSION_TICKS, json.optInt("krmTensionTicks", 0).coerceAtLeast(0))
        }
        if (json.has("krmMarketTension")) {
            val tension = json.optDouble("krmMarketTension", -1.0)
            if (isValidMarketTension(tension)) {
                editor.putString(KEY_KRM_MARKET_TENSION, tension.toString())
            }
        }
        json.optJSONObject("fiatRates")?.let { fiatRates ->
            for (curr in WidgetCurrency.entries) {
                if (curr == WidgetCurrency.IDR || curr == WidgetCurrency.KRON) continue
                val saved = fiatRates.optString(curr.code, "")
                if (saved.isNotBlank() && saved.toDoubleOrNull() != null) {
                    editor.putString("$KEY_PREFIX_RATE${curr.code}", saved)
                }
            }
        }
        json.optJSONObject("fiatRateHistory")?.let { fiatHist ->
            for (curr in WidgetCurrency.entries) {
                if (curr == WidgetCurrency.IDR || curr == WidgetCurrency.KRON) continue
                val arr = fiatHist.optJSONArray(curr.code)
                if (arr != null && arr.length() > 0) {
                    editor.putString(historyKeyFor(curr), arr.toString())
                }
            }
        }
        editor.apply()
    }

    // ------------------------------------------------------------------
    // Staleness and auto-sync
    // ------------------------------------------------------------------

    fun isRatesStale(context: Context?): Boolean {
        if (context == null) return false
        return System.currentTimeMillis() - getLastSyncedTime(context) > STALE_THRESHOLD_MS
    }

    suspend fun checkAndAutoSync(context: Context): Result<Map<String, Double>> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            if (isRatesStale(context)) {
                performRateSync(context, isManualRefresh = false)
            } else if (isKrmTickDue(context) && hasInternetAccess(context)) {
                val newKrmIdr = refreshKronFluctuation(context)
                runCatching { KronWidgetManager.notifyWidgetUpdate(context) }
                Result.success(mapOf("KRM" to (1.0 / newKrmIdr)))
            } else {
                Result.success(emptyMap())
            }
        }
    }

    // ------------------------------------------------------------------
    // Rate retrieval
    // ------------------------------------------------------------------

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
        return saved?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: currency.fallbackRateFromIdr
    }

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
    // Online sync (Multi-tier live market waterfall)
    // ------------------------------------------------------------------

    /**
     * Manual refresh of foreign exchange rates.
     * Updates USD, EUR, SGD, and JPY only; KRM only advances on automatic 30-minute cycles.
     */
    suspend fun syncRatesOnline(context: Context): Result<Map<String, Double>> = withContext(Dispatchers.IO) {
        syncMutex.withLock { performRateSync(context, isManualRefresh = true) }
    }

    private fun isOfflineNetworkException(t: Throwable?): Boolean {
        return t is java.net.UnknownHostException ||
            t is java.net.NoRouteToHostException ||
            t is java.net.ConnectException ||
            t is java.net.SocketException ||
            t is java.net.SocketTimeoutException
    }

    private fun performRateSync(
        context: Context,
        isManualRefresh: Boolean = false,
    ): Result<Map<String, Double>> {
        if (!hasInternetAccess(context)) {
            return Result.failure(IllegalStateException("Tidak ada koneksi internet. Kurs tersimpan tetap digunakan."))
        }

        var lastException: Exception? = null
        var consecutiveDnsOrConnectFailures = 0

        // Tier 1: TradingView Live Forex Spot Scanner
        val tvRates = runCatching { fetchTradingViewSpotRates() }
            .onFailure {
                lastException = it as? Exception ?: Exception(it.message)
                if (it is java.net.UnknownHostException || it is java.net.NoRouteToHostException) {
                    consecutiveDnsOrConnectFailures++
                }
            }
            .getOrNull()
        if (tvRates != null && tvRates.isNotEmpty()) {
            return Result.success(persistSyncedFiatRates(context, tvRates, isManualRefresh))
        }

        // Tier 2: CoinGecko 5-Minute Global Cross-Rates
        val cgRates = runCatching { fetchCoinGeckoCrossRates() }
            .onFailure {
                lastException = it as? Exception ?: Exception(it.message)
                if (it is java.net.UnknownHostException || it is java.net.NoRouteToHostException) {
                    consecutiveDnsOrConnectFailures++
                }
            }
            .getOrNull()
        if (cgRates != null && cgRates.isNotEmpty()) {
            return Result.success(persistSyncedFiatRates(context, cgRates, isManualRefresh))
        }

        // If two independent global domains failed DNS/route resolution, the device has no working internet.
        // Fail fast instead of blocking on 4 more fallback URLs.
        if (consecutiveDnsOrConnectFailures >= 2 || !hasInternetAccess(context)) {
            return Result.failure(IllegalStateException("Tidak ada koneksi internet. Kurs tersimpan tetap digunakan."))
        }

        // Tier 3: USD-Pivot JSON Endpoints (jsDelivr CDN, Cloudflare Pages, open.er-api, exchangerate-api)
        for (apiUrl in API_URLS) {
            if (!hasInternetAccess(context)) break
            try {
                val pivotRates = fetchUsdPivotRates(apiUrl)
                if (pivotRates.isNotEmpty()) {
                    return Result.success(persistSyncedFiatRates(context, pivotRates, isManualRefresh))
                }
            } catch (e: Exception) {
                lastException = e
                if (e is java.net.UnknownHostException || e is java.net.NoRouteToHostException) {
                    consecutiveDnsOrConnectFailures++
                    if (consecutiveDnsOrConnectFailures >= 2) break
                }
            }
        }

        val friendlyMessage = if (isOfflineNetworkException(lastException)) {
            "Tidak ada koneksi internet. Kurs tersimpan tetap digunakan."
        } else {
            lastException?.message ?: "Gagal menghubungi server kurs"
        }
        return Result.failure(IllegalStateException(friendlyMessage))
    }

    private fun persistSyncedFiatRates(
        context: Context,
        idrPerUnitMap: Map<WidgetCurrency, Double>,
        isManualRefresh: Boolean,
    ): Map<String, Double> {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_RATES, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val savedRates = mutableMapOf<String, Double>()

        for ((curr, idrPerUnit) in idrPerUnitMap) {
            if (idrPerUnit > 0.0 && idrPerUnit.isFinite()) {
                val rateFromIdr = 1.0 / idrPerUnit
                editor.putString("$KEY_PREFIX_RATE${curr.code}", rateFromIdr.toString())
                savedRates[curr.code] = rateFromIdr
            }
        }

        editor.putLong(KEY_LAST_SYNCED, now)
        editor.apply()

        for ((curr, idrPerUnit) in idrPerUnitMap) {
            recordCurrencyRate(context, curr, idrPerUnit, now)
        }

        // Pre-cache 1-year historical chart data while online so charts remain available offline.
        val todayEpochDay = LocalDate.now().toEpochDay()
        if (prefs.getLong(KEY_FIAT_BACKFILL_EPOCH_DAY, -1L) != todayEpochDay) {
            runCatching { fetchAndMergeFiatHistory(context, todayEpochDay) }
        }

        // KRM only ticks on automatic 30-minute intervals, never on manual refresh.
        val krmIdr = if (!isManualRefresh && isKrmTickDue(context)) {
            refreshKronFluctuation(context)
        } else {
            getKrmIdrRate(context)
        }
        savedRates["KRM"] = 1.0 / krmIdr
        runCatching { KronWidgetManager.notifyWidgetUpdate(context) }
        return savedRates
    }

    private fun fetchTradingViewSpotRates(): Map<WidgetCurrency, Double> {
        val payload = """{"symbols":{"tickers":["FX_IDC:USDIDR","FX_IDC:EURIDR","FX_IDC:SGDIDR","FX_IDC:JPYIDR"],"query":{"types":[]}},"columns":["close"]}"""
        val conn = (URL(TRADINGVIEW_SCAN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("User-Agent", "KRON-App/1.0")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        val responseText = try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${conn.responseCode} dari TradingView")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        return parseTradingViewResponse(responseText)
    }

    internal fun parseTradingViewResponse(responseText: String): Map<WidgetCurrency, Double> {
        val root = runCatching { Json.parseToJsonElement(responseText).jsonObject }.getOrNull() ?: return emptyMap()
        val dataArr = root["data"]?.jsonArray ?: return emptyMap()
        val result = mutableMapOf<WidgetCurrency, Double>()
        for (elem in dataArr) {
            val item = elem.jsonObject
            val symbol = item["s"]?.jsonPrimitive?.contentOrNull ?: continue
            val dArr = item["d"]?.jsonArray ?: continue
            if (dArr.isEmpty()) continue
            val closeIdr = dArr[0].jsonPrimitive.doubleOrNull ?: continue
            if (closeIdr <= 0.0 || !closeIdr.isFinite()) continue
            when (symbol) {
                "FX_IDC:USDIDR" -> result[WidgetCurrency.USD] = closeIdr
                "FX_IDC:EURIDR" -> result[WidgetCurrency.EUR] = closeIdr
                "FX_IDC:SGDIDR" -> result[WidgetCurrency.SGD] = closeIdr
                "FX_IDC:JPYIDR" -> result[WidgetCurrency.JPY] = closeIdr
            }
        }
        return result
    }

    private fun fetchCoinGeckoCrossRates(): Map<WidgetCurrency, Double> {
        val conn = (URL(COINGECKO_RATES_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("User-Agent", "KRON-App/1.0")
            setRequestProperty("Accept", "application/json")
        }
        val responseText = try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${conn.responseCode} dari CoinGecko")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        return parseCoinGeckoResponse(responseText)
    }

    internal fun parseCoinGeckoResponse(responseText: String): Map<WidgetCurrency, Double> {
        val root = runCatching { Json.parseToJsonElement(responseText).jsonObject }.getOrNull() ?: return emptyMap()
        val ratesObj = root["rates"]?.jsonObject ?: return emptyMap()
        val idrPerBtc = ratesObj["idr"]?.jsonObject?.get("value")?.jsonPrimitive?.doubleOrNull ?: 0.0
        if (idrPerBtc <= 0.0 || !idrPerBtc.isFinite()) return emptyMap()

        val result = mutableMapOf<WidgetCurrency, Double>()
        for (curr in listOf(WidgetCurrency.USD, WidgetCurrency.EUR, WidgetCurrency.SGD, WidgetCurrency.JPY)) {
            val currPerBtc = ratesObj[curr.code.lowercase(Locale.ROOT)]
                ?.jsonObject?.get("value")?.jsonPrimitive?.doubleOrNull ?: 0.0
            if (currPerBtc > 0.0 && currPerBtc.isFinite()) {
                result[curr] = idrPerBtc / currPerBtc
            }
        }
        return result
    }

    private fun fetchUsdPivotRates(apiUrl: String): Map<WidgetCurrency, Double> {
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("User-Agent", "KRON-App/1.0")
            setRequestProperty("Accept", "application/json")
        }
        val responseText = try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${conn.responseCode} dari $apiUrl")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        return parseUsdPivotResponse(responseText)
    }

    internal fun parseUsdPivotResponse(responseText: String): Map<WidgetCurrency, Double> {
        val root = runCatching { Json.parseToJsonElement(responseText).jsonObject }.getOrNull() ?: return emptyMap()
        val ratesObj = root["rates"]?.jsonObject ?: root["usd"]?.jsonObject ?: return emptyMap()

        fun readRate(code: String): Double {
            val upper = ratesObj[code.uppercase(Locale.ROOT)]?.jsonPrimitive?.doubleOrNull ?: -1.0
            if (upper > 0.0 && upper.isFinite()) return upper
            val lower = ratesObj[code.lowercase(Locale.ROOT)]?.jsonPrimitive?.doubleOrNull ?: -1.0
            if (lower > 0.0 && lower.isFinite()) return lower
            return 0.0
        }

        val idrPerUsd = readRate("IDR")
        if (idrPerUsd <= 0.0) return emptyMap()

        val result = mutableMapOf<WidgetCurrency, Double>()
        result[WidgetCurrency.USD] = idrPerUsd
        for (curr in listOf(WidgetCurrency.EUR, WidgetCurrency.SGD, WidgetCurrency.JPY)) {
            val currPerUsd = readRate(curr.code)
            if (currPerUsd > 0.0) {
                result[curr] = idrPerUsd / currPerUsd
            }
        }
        return result
    }

    private fun hasInternetAccess(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

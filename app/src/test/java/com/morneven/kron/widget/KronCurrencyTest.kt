package com.morneven.kron.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class KronCurrencyTest {

    @Test
    fun testCurrencyFromCode() {
        assertEquals(WidgetCurrency.IDR, WidgetCurrency.fromCode("IDR"))
        assertEquals(WidgetCurrency.IDR, WidgetCurrency.fromCode("idr"))
        assertEquals(WidgetCurrency.IDR, WidgetCurrency.fromCode("Rp"))
        assertEquals(WidgetCurrency.KRON, WidgetCurrency.fromCode("KRM"))
        assertEquals(WidgetCurrency.KRON, WidgetCurrency.fromCode("krm"))
        assertEquals(WidgetCurrency.KRON, WidgetCurrency.fromCode("Kr"))
        assertEquals(WidgetCurrency.KRON, WidgetCurrency.fromCode("kr"))
        assertEquals(WidgetCurrency.KRON, WidgetCurrency.fromCode("KR"))
        assertEquals(WidgetCurrency.USD, WidgetCurrency.fromCode("USD"))
        assertEquals(WidgetCurrency.EUR, WidgetCurrency.fromCode("EUR"))
        assertEquals(WidgetCurrency.SGD, WidgetCurrency.fromCode("SGD"))
        assertEquals(WidgetCurrency.JPY, WidgetCurrency.fromCode("JPY"))
        assertEquals(WidgetCurrency.IDR, WidgetCurrency.fromCode(null))
        assertEquals(WidgetCurrency.IDR, WidgetCurrency.fromCode(""))
        assertEquals(WidgetCurrency.IDR, WidgetCurrency.fromCode("INVALID"))
    }

    @Test
    fun testMornevenKronReferenceExchangeRate() {
        assertEquals("KRM", WidgetCurrency.KRON.code)
        assertEquals("Kr", WidgetCurrency.KRON.shortCode)
        assertEquals("Kr ", WidgetCurrency.KRON.symbol)
        assertEquals(1200L, KronCurrencyManager.MORNEVEN_KRON_IN_IDR)
        assertEquals(1200.0, KronCurrencyManager.getIdrPerUnit(null, WidgetCurrency.KRON), 0.0001)

        val expectedRate = 1.0 / 1200.0
        assertEquals(expectedRate, KronCurrencyManager.getRate(null, WidgetCurrency.KRON), 0.0000001)
    }

    @Test
    fun syntheticKrmRateHasNoArbitraryUpperCap() {
        val next = KronCurrencyManager.nextKrmIdrRate(100_000.0, 0.15, increase = true)
        assertTrue(next > 100_000.0)
        assertTrue(next > 50_000.0)
    }

    @Test
    fun syntheticKrmRateCanFreelyExploreBeyondBaseRangeWithoutMeanReversionPull() {
        val next = KronCurrencyManager.nextKrmIdrRate(2500.0, 0.10, increase = true)
        assertEquals(2750.0, next, 0.0001)
        assertTrue(next > 2500.0)

        val nextSmall = KronCurrencyManager.nextKrmIdrRate(5000.0, 0.01, increase = true)
        assertEquals(5050.0, nextSmall, 0.0001)
    }

    @Test
    fun syntheticKrmRateKeepsItsLowerFloor() {
        assertEquals(100.0, KronCurrencyManager.nextKrmIdrRate(100.0, 0.99, increase = false), 0.0001)
    }

    @Test
    fun testRollKrmFluctuationFourTiersAndWaveContinuation() {
        val rng = Random(42L)
        var rate = 1200.0
        var waveDir = true
        var waveTicks = 0
        var sentimentTicks = 0
        var sentimentUpChance = 0.0
        var sawWaveTrigger = false
        var sawWaveStep = false
        var sawBullishSentiment = false
        var sawBearishSentiment = false

        repeat(250) {
            val wasInWave = waveTicks > 0
            val roll = KronCurrencyManager.rollKrmFluctuation(
                waveTicksRemaining = waveTicks,
                waveDirection = waveDir,
                sentimentTicksRemaining = sentimentTicks,
                sentimentUpChance = sentimentUpChance,
                tierRoll = rng.nextDouble(),
                sentimentRoll = rng.nextDouble(),
                directionRoll = rng.nextDouble(),
                magnitudeRoll = rng.nextDouble(),
            )
            rate = KronCurrencyManager.nextKrmIdrRate(rate, roll.magnitude, roll.increase)
            assertTrue(rate >= 100.0)
            assertTrue("upChance must stay within 25%..75%", roll.upChance in 0.25..0.75)
            assertTrue(
                "nextSentimentUpChance must be bimodal (25%..40% or 60%..75%)",
                KronCurrencyManager.isValidSentimentUpChance(roll.nextSentimentUpChance),
            )
            if (roll.nextSentimentUpChance >= 0.60) sawBullishSentiment = true
            if (roll.nextSentimentUpChance <= 0.40) sawBearishSentiment = true

            if (!wasInWave && roll.nextWaveTicks > 0) {
                sawWaveTrigger = true
                assertTrue(roll.tier == 3 || roll.tier == 4)
                assertTrue(roll.nextWaveTicks in 2..5)
            }
            if (wasInWave) {
                sawWaveStep = true
                assertEquals(0, roll.tier)
                assertEquals(waveTicks - 1, roll.nextWaveTicks)
            }
            waveDir = roll.nextWaveDirection
            waveTicks = roll.nextWaveTicks
            sentimentTicks = roll.nextSentimentTicks
            sentimentUpChance = roll.nextSentimentUpChance
        }

        assertTrue("Expected at least one incident wave trigger over 250 ticks", sawWaveTrigger)
        assertTrue("Expected wave continuation step to execute", sawWaveStep)
        assertTrue("Expected bullish sentiment phase", sawBullishSentiment)
        assertTrue("Expected bearish sentiment phase", sawBearishSentiment)
    }

    @Test
    fun testPersistentBimodalSentimentDoesNotResetEveryTick() {
        val initialRoll = KronCurrencyManager.rollKrmFluctuation(
            waveTicksRemaining = 0,
            waveDirection = true,
            sentimentTicksRemaining = 0,
            sentimentUpChance = 0.0,
            tierRoll = 0.20, // Tier 1
            sentimentRoll = 0.80, // Bullish (60%..75%)
            directionRoll = 0.30,
            magnitudeRoll = 0.50,
        )
        assertEquals(1, initialRoll.tier)
        assertTrue(initialRoll.upChance in 0.60..0.75)
        assertTrue(initialRoll.increase)
        assertTrue(initialRoll.nextSentimentTicks in 5..15)

        // Next tick with opposite sentimentRoll must keep the active persistent sentiment
        val secondRoll = KronCurrencyManager.rollKrmFluctuation(
            waveTicksRemaining = 0,
            waveDirection = true,
            sentimentTicksRemaining = initialRoll.nextSentimentTicks,
            sentimentUpChance = initialRoll.nextSentimentUpChance,
            tierRoll = 0.82, // Tier 2
            sentimentRoll = 0.10, // Would be bearish if not locked
            directionRoll = 0.55, // < initialRoll.upChance, so still increases
            magnitudeRoll = 0.50,
        )
        assertEquals(2, secondRoll.tier)
        assertEquals(initialRoll.nextSentimentUpChance, secondRoll.upChance, 0.00001)
        assertEquals(initialRoll.nextSentimentTicks - 1, secondRoll.nextSentimentTicks)
        assertTrue(secondRoll.increase)
    }

    @Test
    fun testParseTradingViewSpotRates() {
        val payload = """
            {"totalCount":4,"data":[
                {"s":"FX_IDC:USDIDR","d":[17168.4]},
                {"s":"FX_IDC:EURIDR","d":[20219.9]},
                {"s":"FX_IDC:SGDIDR","d":[13468.9]},
                {"s":"FX_IDC:JPYIDR","d":[107.88]}
            ]}
        """.trimIndent()
        val parsed = KronCurrencyManager.parseTradingViewResponse(payload)
        assertEquals(4, parsed.size)
        assertEquals(17168.4, parsed[WidgetCurrency.USD]!!, 0.01)
        assertEquals(20219.9, parsed[WidgetCurrency.EUR]!!, 0.01)
        assertEquals(13468.9, parsed[WidgetCurrency.SGD]!!, 0.01)
        assertEquals(107.88, parsed[WidgetCurrency.JPY]!!, 0.01)
    }

    @Test
    fun testParseCoinGeckoCrossRates() {
        val payload = """
            {"rates":{
                "idr":{"name":"Indonesian Rupiah","unit":"Rp","value":1280000000.0,"type":"fiat"},
                "usd":{"name":"US Dollar","unit":"$","value":74555.6,"type":"fiat"},
                "eur":{"name":"Euro","unit":"€","value":63304.0,"type":"fiat"},
                "sgd":{"name":"Singapore Dollar","unit":"S$","value":95033.7,"type":"fiat"},
                "jpy":{"name":"Japanese Yen","unit":"¥","value":11865035.0,"type":"fiat"}
            }}
        """.trimIndent()
        val parsed = KronCurrencyManager.parseCoinGeckoResponse(payload)
        assertEquals(4, parsed.size)
        assertEquals(17168.4, parsed[WidgetCurrency.USD]!!, 0.5)
        assertEquals(20219.9, parsed[WidgetCurrency.EUR]!!, 0.5)
        assertEquals(13468.9, parsed[WidgetCurrency.SGD]!!, 0.5)
        assertEquals(107.88, parsed[WidgetCurrency.JPY]!!, 0.05)
    }

    @Test
    fun testParseUsdPivotRatesPrecision() {
        val cdnPayload = """
            {"date":"2026-10-16","usd":{"idr":17155.3489,"eur":0.84955,"sgd":1.2738,"jpy":159.1066}}
        """.trimIndent()
        val parsedCdn = KronCurrencyManager.parseUsdPivotResponse(cdnPayload)
        assertEquals(4, parsedCdn.size)
        assertEquals(17155.35, parsedCdn[WidgetCurrency.USD]!!, 0.05)
        assertEquals(17155.3489 / 0.84955, parsedCdn[WidgetCurrency.EUR]!!, 0.05)
        assertEquals(17155.3489 / 1.2738, parsedCdn[WidgetCurrency.SGD]!!, 0.05)
        assertEquals(17155.3489 / 159.1066, parsedCdn[WidgetCurrency.JPY]!!, 0.05)

        val erApiPayload = """
            {"result":"success","rates":{"IDR":17148.29,"EUR":0.8496,"SGD":1.2733,"JPY":159.09}}
        """.trimIndent()
        val parsedEr = KronCurrencyManager.parseUsdPivotResponse(erApiPayload)
        assertEquals(4, parsedEr.size)
        assertEquals(17148.29, parsedEr[WidgetCurrency.USD]!!, 0.05)
        assertEquals(17148.29 / 0.8496, parsedEr[WidgetCurrency.EUR]!!, 0.05)
    }

    @Test
    fun testFormatCurrencyAmountKRM() {
        // Hidden balance masking
        val hidden = KronCurrencyManager.formatCurrencyAmount(1200L, WidgetCurrency.KRON, visible = false)
        assertEquals("Kr \u2022\u2022\u2022\u2022\u2022\u2022", hidden)

        // 1.200 IDR is exactly 1.0 Kr -> formatted as "Kr 1,00"
        val oneKr = KronCurrencyManager.formatCurrencyAmount(1200L, WidgetCurrency.KRON, visible = true)
        assertEquals("Kr 1,00", oneKr)

        // 1.000.000 IDR = 833.33 Kr -> rounded to "Kr 833,33"
        val millionIdr = KronCurrencyManager.formatCurrencyAmount(1_000_000L, WidgetCurrency.KRON, visible = true)
        assertEquals("Kr 833,33", millionIdr)

        // 120.000 IDR = exactly 100.0 Kr (>= 100 and integer) -> "Kr 100"
        val hundredKr = KronCurrencyManager.formatCurrencyAmount(120_000L, WidgetCurrency.KRON, visible = true)
        assertEquals("Kr 100", hundredKr)

        // 1.200.000 IDR = exactly 1000.0 Kr -> "Kr 1.000"
        val thousandKr = KronCurrencyManager.formatCurrencyAmount(1_200_000L, WidgetCurrency.KRON, visible = true)
        assertEquals("Kr 1.000", thousandKr)
    }

    @Test
    fun testFormatCurrencyAmountOtherCurrencies() {
        // IDR
        val idrVisible = KronCurrencyManager.formatCurrencyAmount(1_500_000L, WidgetCurrency.IDR, visible = true)
        assertEquals("Rp 1.500.000", idrVisible)
        val idrHidden = KronCurrencyManager.formatCurrencyAmount(1_500_000L, WidgetCurrency.IDR, visible = false)
        assertEquals("Rp \u2022\u2022\u2022\u2022\u2022\u2022", idrHidden)

        // USD with fallback rate (1/16200)
        // 16.200 IDR = $1.00
        val oneUsd = KronCurrencyManager.formatCurrencyAmount(16_200L, WidgetCurrency.USD, visible = true)
        assertEquals("$ 1,00", oneUsd)

        // JPY with fallback rate (1/110)
        // JPY has no decimal fractions in formatting
        val yen = KronCurrencyManager.formatCurrencyAmount(1_100_000L, WidgetCurrency.JPY, visible = true)
        assertEquals("¥ 10.000", yen)
    }

    @Test
    fun testCurrencyApiEndpointsConfigured() {
        assertEquals(4, KronCurrencyManager.API_URLS.size)
        assertTrue(KronCurrencyManager.API_URLS.all { it.startsWith("https://") })
        assertTrue(KronCurrencyManager.API_URLS.any { it.contains("jsdelivr.net") })
        assertTrue(KronCurrencyManager.API_URLS.any { it.contains("open.er-api.com") })
    }

    @Test
    fun testKrmRateHistoryEntry() {
        val entry = KrmRateHistoryEntry(1700000000L, 1450.0)
        assertEquals(1700000000L, entry.timestampMs)
        assertEquals(1450.0, entry.rate, 0.0001)
    }

    @Test
    fun testGetKrmRateHistoryNullContextReturnsEmpty() {
        val list = KronCurrencyManager.getKrmRateHistory(null)
        assertTrue(list.isEmpty())
    }
}

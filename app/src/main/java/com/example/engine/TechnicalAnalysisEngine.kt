package com.example.engine

import com.example.model.CandleStick
import com.example.model.OrderBookDepth
import com.example.model.TechnicalAnalysis5m
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Quantitative Algorithmic Analysis Core
 * Strictly enforces:
 * - Single-source Binance OHLCV
 * - Z-Score < -2.5 dip filter
 * - Volume Shock (3x dump) knife catch protection
 * - Order book internal sentiment (< 60% buyer wall fear filter)
 * - Dynamic ATR-scaled 1:2:4 DCA Tiers
 */
object TechnicalAnalysisEngine {

    fun analyzeCandles(
        symbol: String,
        candles5m: List<CandleStick>,
        candles3m: List<CandleStick> = emptyList(),
        orderBook: OrderBookDepth? = null
    ): TechnicalAnalysis5m? {
        if (candles5m.size < 20) return null

        val closes = candles5m.map { it.close }
        val highs = candles5m.map { it.high }
        val lows = candles5m.map { it.low }
        val volumes = candles5m.map { it.volume }

        val latestCandle = candles5m.last()
        val currentPrice = latestCandle.close

        // 1. EMAs (9, 21, 50)
        val ema9 = calculateEMA(closes, 9)
        val ema21 = calculateEMA(closes, 21)
        val ema50 = calculateEMA(closes, 50)

        // 2. Bollinger Bands (20 periods, 2.0 std dev)
        val period = 20
        val last20Closes = closes.takeLast(period)
        val sma20 = last20Closes.average()
        val variance = last20Closes.map { (it - sma20).pow(2.0) }.average()
        val stdDev = sqrt(variance).coerceAtLeast(0.0000001)

        val bbUpper = sma20 + (2.0 * stdDev)
        val bbLower = sma20 - (2.0 * stdDev)
        val bbMiddle = sma20
        val bbBandwidthPercent = if (bbMiddle > 0) ((bbUpper - bbLower) / bbMiddle) * 100.0 else 0.0

        // 3. Z-Score Deviation (Statistical quant indicator)
        val zScore = (currentPrice - sma20) / stdDev
        val isZScoreDip = zScore < -2.5
        val isPriceBelowBollingerLower = currentPrice <= bbLower

        // 4. ATR (14 Periods - Dynamic Volatility Engine)
        val atr14 = calculateATR(candles5m, 14).coerceAtLeast(currentPrice * 0.003)

        // 5. RSI 14 (5-minute and 3-minute)
        val rsi14 = calculateRSI(closes, 14)
        val rsi3m = if (candles3m.size >= 14) {
            calculateRSI(candles3m.map { it.close }, 14)
        } else rsi14

        // 6. Volume Shock Filter (3x average sell dump protection)
        val avgVolume = volumes.takeLast(20).average().coerceAtLeast(1.0)
        val volumeRatio = latestCandle.volume / avgVolume
        val isRedCandle = latestCandle.close < latestCandle.open
        val isVolumeShock = volumeRatio >= 3.0 && isRedCandle
        val volumeShockMessage = if (isVolumeShock) {
            "⚠️ Düşen bıçağı tutma, satış hacmi ortalamanın ${String.format(Locale.US, "%.1f", volumeRatio)}x katı! Hacmin sakinleşmesi bekleniyor."
        } else ""

        // 7. Dynamic Support and Resistance
        val swingLow = lows.takeLast(15).minOrNull() ?: currentPrice
        val dynamicSupport = min(bbLower, min(ema50, swingLow))
        val swingHigh = highs.takeLast(15).maxOrNull() ?: currentPrice
        val dynamicResistance = max(bbUpper, max(ema9, swingHigh))

        val distanceToSupport = if (currentPrice > 0) ((currentPrice - dynamicSupport) / currentPrice) * 100.0 else 0.0

        // 8. Order Book Depth & Internal Sentiment Filter
        val currentOrderBook = orderBook ?: OrderBookDepth(symbol = symbol)
        val isOrderBookFear = currentOrderBook.bidRatio < 0.60

        // 9. Institutional Volume Profile Node (Point of Control)
        val recentCandles = candles5m.takeLast(min(20, candles5m.size))
        val highestVolumeCandle = recentCandles.maxByOrNull { it.volume } ?: latestCandle
        val volumeNodePrice = (highestVolumeCandle.open + highestVolumeCandle.close + highestVolumeCandle.low + highestVolumeCandle.high) / 4.0
        val volNodeRatio = if (avgVolume > 0) (highestVolumeCandle.volume / avgVolume) * 100.0 else 100.0
        val volumeClusterDescription = "En yoğun alım kümelenmesi $${String.format(Locale.US, if (volumeNodePrice < 1.0) "%.4f" else "%.2f", volumeNodePrice)} (Hacim: %${String.format(Locale.US, "%.0f", volNodeRatio)})"

        // 10. Dynamic ATR-Scaled 1:2:4 DCA Plan
        // Tier 1: Entry limit near dynamic support
        // Tier 2: 1.5 * ATR below Tier 1
        // Tier 3: 3.0 * ATR below Tier 1
        val dcaTier1Price = dynamicSupport
        val dcaTier2Price = (dynamicSupport - (1.5 * atr14)).coerceAtLeast(dynamicSupport * 0.90)
        val dcaTier3Price = (dynamicSupport - (3.0 * atr14)).coerceAtLeast(dynamicSupport * 0.85)

        // 11. Candlestick Pattern Recognition on 5m
        val bodySize = abs(latestCandle.close - latestCandle.open)
        val lowerWick = min(latestCandle.open, latestCandle.close) - latestCandle.low
        val upperWick = latestCandle.high - max(latestCandle.open, latestCandle.close)

        val candlePattern = when {
            lowerWick >= 2.0 * bodySize && bodySize > 0 -> "Boğa Çekiç (Bullish Hammer - Dip Alıcı Girişi)"
            latestCandle.close > latestCandle.open && latestCandle.close > ema21 -> "EMA21 Üzeri Boğa İtme Mumu"
            distanceToSupport <= 0.35 -> "Destek Testi & Taban Kümelenmesi"
            isZScoreDip -> "Ekstrem Z-Score İstatistiki Dip (-2.5σ)"
            else -> "Konsolidasyon / Nötr Akış"
        }

        // 12. Multi-Factor Institutional Confluence Score (0 - 100)
        var score = 50

        // RSI conditions (< 30 is strong oversold)
        if (rsi14 <= 30.0) score += 20 else if (rsi14 <= 38.0) score += 10
        if (rsi3m <= 30.0) score += 5

        // Bollinger & Z-Score conditions
        if (isZScoreDip) score += 15
        if (isPriceBelowBollingerLower) score += 10

        // Order Book conditions (> 60% buyer wall)
        if (currentOrderBook.bidRatio >= 0.65) score += 10
        else if (isOrderBookFear) score -= 25 // Penalize if seller pressure dominates

        // Volume shock penalty
        if (isVolumeShock) score -= 35 // Hold off knife catch

        // Distance to support
        if (distanceToSupport <= 0.40) score += 10

        val finalScore = score.coerceIn(0, 100)
        val isSupportBounceValid = (rsi14 <= 35.0 || isZScoreDip || distanceToSupport <= 0.40) && !isVolumeShock && !isOrderBookFear
        val isOverboughtRisk = rsi14 >= 68.0 || currentPrice >= bbUpper

        val recommendation = when {
            isVolumeShock -> "⚠️ BEKLE (HACİMLİ SATIŞ ŞOKU - BIÇAĞI TUTMA)"
            isOrderBookFear -> "⚠️ BEKLE (TAHTADA ALICI DUVARI <%60 - BASKI VAR)"
            finalScore >= 75 -> "🎯 GÜÇLÜ PUSU GİRİŞİ (RSI+BB+Z-SCORE ONAYLI)"
            finalScore >= 60 -> "🟢 KADEMELİ PUSU ALIMI UYGUN"
            isOverboughtRisk -> "🔴 DİRENÇ / KÂR AL BÖLGESİ"
            else -> "⏳ BEKLE (PİSAYADA NET DİP HENÜZ OLUŞMADI)"
        }

        return TechnicalAnalysis5m(
            symbol = symbol,
            timeframe = "5m",
            currentPrice = currentPrice,
            supportLevel = dynamicSupport,
            resistanceLevel = dynamicResistance,
            distanceToSupportPercent = distanceToSupport,
            rsi14 = rsi14,
            rsi3m = rsi3m,
            ema9 = ema9,
            ema21 = ema21,
            ema50 = ema50,
            bollingerLower = bbLower,
            bollingerUpper = bbUpper,
            bollingerMiddle = bbMiddle,
            bollingerBandwidthPercent = bbBandwidthPercent,
            atr14 = atr14,
            zScore = zScore,
            isZScoreDip = isZScoreDip,
            isPriceBelowBollingerLower = isPriceBelowBollingerLower,
            isVolumeShock = isVolumeShock,
            volumeShockMessage = volumeShockMessage,
            orderBookDepth = currentOrderBook,
            candlePattern = candlePattern,
            volumeRatioToAvg = volumeRatio,
            volumeNodePrice = volumeNodePrice,
            volumeClusterDescription = volumeClusterDescription,
            ambushTimeoutMinutes = 45,
            confluenceScore = finalScore,
            isSupportBounceValid = isSupportBounceValid,
            isOverboughtRisk = isOverboughtRisk,
            recommendation = recommendation,
            dcaTier1Price = dcaTier1Price,
            dcaTier2Price = dcaTier2Price,
            dcaTier3Price = dcaTier3Price,
            dcaTier1Weight = 1.0, // $15
            dcaTier2Weight = 2.0, // $30
            dcaTier3Weight = 4.0, // $60
            lastUpdated = System.currentTimeMillis()
        )
    }

    private fun calculateEMA(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val k = 2.0 / (period + 1.0)
        var ema = values.take(period).average()
        for (i in period until values.size) {
            ema = (values[i] * k) + (ema * (1 - k))
        }
        return ema
    }

    private fun calculateRSI(closes: List<Double>, period: Int = 14): Double {
        if (closes.size < period + 1) return 50.0
        var gains = 0.0
        var losses = 0.0

        for (i in 1..period) {
            val diff = closes[i] - closes[i - 1]
            if (diff >= 0) gains += diff else losses += -diff
        }

        var avgGain = gains / period
        var avgLoss = losses / period

        for (i in (period + 1) until closes.size) {
            val diff = closes[i] - closes[i - 1]
            val gain = if (diff > 0) diff else 0.0
            val loss = if (diff < 0) -diff else 0.0

            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
        }

        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    private fun calculateATR(candles: List<CandleStick>, period: Int = 14): Double {
        if (candles.size < period + 1) return 0.0
        val trList = mutableListOf<Double>()

        for (i in 1 until candles.size) {
            val high = candles[i].high
            val low = candles[i].low
            val prevClose = candles[i - 1].close
            val tr = max(high - low, max(abs(high - prevClose), abs(low - prevClose)))
            trList.add(tr)
        }

        if (trList.isEmpty()) return 0.0
        var atr = trList.take(period).average()
        for (i in period until trList.size) {
            atr = (atr * (period - 1) + trList[i]) / period
        }
        return atr
    }
}

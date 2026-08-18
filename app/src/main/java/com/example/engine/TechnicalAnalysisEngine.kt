package com.example.engine

import com.example.model.CandleStick
import com.example.model.TechnicalAnalysis5m
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

object TechnicalAnalysisEngine {

    /**
     * Performs institutional grade 5-minute technical indicator & support/resistance analysis.
     */
    fun analyze5mCandles(
        symbol: String,
        candles: List<CandleStick>,
        leadLagSpreadPercent: Double = 0.0
    ): TechnicalAnalysis5m {
        if (candles.size < 15) {
            val fallbackPrice = candles.lastOrNull()?.close ?: 100.0
            return TechnicalAnalysis5m(
                symbol = symbol,
                currentPrice = fallbackPrice,
                supportLevel = fallbackPrice * 0.985,
                resistanceLevel = fallbackPrice * 1.015,
                distanceToSupportPercent = 1.5,
                rsi14 = 50.0,
                ema9 = fallbackPrice,
                ema21 = fallbackPrice,
                ema50 = fallbackPrice,
                bollingerLower = fallbackPrice * 0.98,
                bollingerUpper = fallbackPrice * 1.02,
                bollingerMiddle = fallbackPrice,
                candlePattern = "Yükleniyor...",
                volumeRatioToAvg = 1.0,
                confluenceScore = 50,
                isSupportBounceValid = false,
                isOverboughtRisk = false,
                recommendation = "Analiz Bekleniyor"
            )
        }

        val closes = candles.map { it.close }
        val currentPrice = closes.last()
        val latestCandle = candles.last()
        val previousCandle = candles[candles.size - 2]

        // 1. Support & Resistance (Pivot Price Action on 5m)
        val recentLows = candles.takeLast(15).map { it.low }
        val recentHighs = candles.takeLast(15).map { it.high }
        val swingLowSupport = recentLows.minOrNull() ?: (currentPrice * 0.985)
        val swingHighResistance = recentHighs.maxOrNull() ?: (currentPrice * 1.015)

        // 2. Exponential Moving Averages (EMA 9, EMA 21, EMA 50)
        val ema9 = calculateEMA(closes, 9)
        val ema21 = calculateEMA(closes, 21)
        val ema50 = calculateEMA(closes, min(50, closes.size))

        // 3. Bollinger Bands (20 periods, 2.0 stddev)
        val bbPeriod = min(20, closes.size)
        val bbSlice = closes.takeLast(bbPeriod)
        val bbMiddle = bbSlice.average()
        val variance = bbSlice.map { (it - bbMiddle).pow(2) }.average()
        val stdDev = sqrt(variance)
        val bbUpper = bbMiddle + (2.0 * stdDev)
        val bbLower = bbMiddle - (2.0 * stdDev)

        // Refined dynamic support is the higher of swing low or Bollinger Lower Band
        val dynamicSupport = max(swingLowSupport, bbLower * 0.998)
        val dynamicResistance = min(swingHighResistance, bbUpper * 1.002)

        val distanceToSupportPct = if (currentPrice > dynamicSupport) {
            ((currentPrice - dynamicSupport) / currentPrice) * 100.0
        } else {
            0.0
        }

        // 4. RSI (14 periods on 5m)
        val rsi14 = calculateRSI(closes, 14)

        // 5. Volume Spike Detection
        val volumes = candles.map { it.volume }
        val avgVolume = volumes.takeLast(15).average().coerceAtLeast(1.0)
        val volumeRatio = latestCandle.volume / avgVolume

        // 6. Candlestick Pattern Recognition on 5m
        val bodySize = Math.abs(latestCandle.close - latestCandle.open)
        val lowerWick = min(latestCandle.open, latestCandle.close) - latestCandle.low
        val upperWick = latestCandle.high - max(latestCandle.open, latestCandle.close)
        val isBullish = latestCandle.close >= latestCandle.open

        var candlePattern = "Nötr Mum"
        if (lowerWick >= 2.0 * bodySize && lowerWick > upperWick) {
            candlePattern = "Boğa Çekiç (Dip Sekmesi 🔨)"
        } else if (isBullish && previousCandle.close < previousCandle.open &&
            latestCandle.close > previousCandle.open && latestCandle.open < previousCandle.close
        ) {
            candlePattern = "Yutan Boğa (Bullish Engulfing 🚀)"
        } else if (distanceToSupportPct <= 0.45) {
            candlePattern = "Destek Seviyesi Test Ediliyor 🛡️"
        }

        // 7. Institutional Confluence Score (0 - 100)
        var score = 0

        // Factor A: Proximity to 5m Technical Support (0 - 30 pts)
        if (distanceToSupportPct <= 0.50) {
            score += 30 // Right at support!
        } else if (distanceToSupportPct <= 1.0) {
            score += 20
        } else if (distanceToSupportPct <= 1.8) {
            score += 10
        }

        // Factor B: RSI Condition (0 - 25 pts)
        if (rsi14 in 25.0..45.0) {
            score += 25 // Prime accumulation zone
        } else if (rsi14 in 45.0..55.0) {
            score += 15
        } else if (rsi14 > 65.0) {
            score -= 15 // Overbought penalty!
        }

        // Factor C: Binance Lead-Lag Arbitrage Spread (0 - 25 pts)
        if (leadLagSpreadPercent >= 0.60) {
            score += 25
        } else if (leadLagSpreadPercent >= 0.35) {
            score += 18
        } else if (leadLagSpreadPercent >= 0.15) {
            score += 10
        }

        // Factor D: EMA & Volume Confirmation (0 - 20 pts)
        if (currentPrice >= ema9 && ema9 >= ema21) {
            score += 10 // Bullish micro-trend
        } else if (currentPrice >= dynamicSupport && volumeRatio > 1.1) {
            score += 10 // Support with volume
        }
        if (volumeRatio >= 1.25) {
            score += 10
        }

        val finalScore = score.coerceIn(0, 100)
        val isSupportBounceValid = distanceToSupportPct <= 0.85 && rsi14 <= 58.0 && finalScore >= 70
        val isOverboughtRisk = rsi14 >= 65.0 || (currentPrice >= dynamicResistance * 0.996)

        val recommendation = when {
            finalScore >= 80 -> "🔥 GÜÇLÜ 5DK DESTEK ALIMI (%$finalScore)"
            finalScore >= 68 -> "⚡ UYGUN MİKRO GİRİŞ (%$finalScore)"
            isOverboughtRisk -> "⚠️ DİRENÇ / AŞIRI ALIM (BEKLE)"
            else -> "⏳ 5DK DESTEĞE YAKLAŞMASI BEKLENİYOR"
        }

        return TechnicalAnalysis5m(
            symbol = symbol,
            timeframe = "5m",
            currentPrice = currentPrice,
            supportLevel = dynamicSupport,
            resistanceLevel = dynamicResistance,
            distanceToSupportPercent = distanceToSupportPct,
            rsi14 = rsi14,
            ema9 = ema9,
            ema21 = ema21,
            ema50 = ema50,
            bollingerLower = bbLower,
            bollingerUpper = bbUpper,
            bollingerMiddle = bbMiddle,
            candlePattern = candlePattern,
            volumeRatioToAvg = volumeRatio,
            confluenceScore = finalScore,
            isSupportBounceValid = isSupportBounceValid,
            isOverboughtRisk = isOverboughtRisk,
            recommendation = recommendation
        )
    }

    private fun calculateEMA(prices: List<Double>, period: Int): Double {
        if (prices.isEmpty()) return 0.0
        if (prices.size < period) return prices.average()

        val multiplier = 2.0 / (period + 1)
        var ema = prices.take(period).average()

        for (i in period until prices.size) {
            ema = (prices[i] - ema) * multiplier + ema
        }
        return ema
    }

    private fun calculateRSI(prices: List<Double>, period: Int = 14): Double {
        if (prices.size <= period) return 50.0

        var gains = 0.0
        var losses = 0.0

        for (i in 1..period) {
            val change = prices[i] - prices[i - 1]
            if (change > 0) gains += change else losses += Math.abs(change)
        }

        var avgGain = gains / period
        var avgLoss = losses / period

        for (i in (period + 1) until prices.size) {
            val change = prices[i] - prices[i - 1]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) Math.abs(change) else 0.0

            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
        }

        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return (100.0 - (100.0 / (1.0 + rs))).coerceIn(0.0, 100.0)
    }
}

package com.example.model

data class CandleStick(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long
)

data class TechnicalAnalysis5m(
    val symbol: String,
    val timeframe: String = "5m",
    val currentPrice: Double,
    val supportLevel: Double,
    val resistanceLevel: Double,
    val distanceToSupportPercent: Double,
    val rsi14: Double,
    val ema9: Double,
    val ema21: Double,
    val ema50: Double,
    val bollingerLower: Double,
    val bollingerUpper: Double,
    val bollingerMiddle: Double,
    val candlePattern: String, // e.g. "Boğa Çekiç (Bullish Hammer)", "Destek Sekmesi", "Nötr"
    val volumeRatioToAvg: Double, // e.g. 1.35x
    val confluenceScore: Int, // 0 to 100 institutional quality score
    val isSupportBounceValid: Boolean,
    val isOverboughtRisk: Boolean,
    val recommendation: String, // "MÜKEMMEL DESTEK ALIMI", "BEKLE (DİRENÇ YAKIN)", "NÖTR"
    val lastUpdated: Long = System.currentTimeMillis()
)

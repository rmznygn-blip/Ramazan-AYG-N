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

data class OrderBookLevel(
    val price: Double,
    val quantity: Double
)

data class OrderBookDepth(
    val symbol: String,
    val bids: List<OrderBookLevel> = emptyList(),
    val asks: List<OrderBookLevel> = emptyList(),
    val totalBidVolume: Double = 0.0,
    val totalAskVolume: Double = 0.0,
    val bidRatio: Double = 0.50, // e.g. 0.65 = %65 buyers
    val isBuyerDominant: Boolean = true,
    val isOrderBookFear: Boolean = false, // If bidRatio < 0.60
    val sentimentText: String = "Dengeli Tahta",
    val timestamp: Long = System.currentTimeMillis()
)

data class TechnicalAnalysis5m(
    val symbol: String,
    val timeframe: String = "5m",
    val currentPrice: Double,
    val supportLevel: Double,
    val resistanceLevel: Double,
    val distanceToSupportPercent: Double,
    val rsi14: Double,
    val rsi3m: Double = 50.0, // 3m RSI for multi-timeframe precision
    val ema9: Double,
    val ema21: Double,
    val ema50: Double,
    val bollingerLower: Double,
    val bollingerUpper: Double,
    val bollingerMiddle: Double,
    val bollingerBandwidthPercent: Double = 0.0,
    val atr14: Double = 0.0, // Average True Range for dynamic volatility scaling
    val zScore: Double = 0.0, // Z-Score statistical deviation
    val isZScoreDip: Boolean = false, // Z-Score < -2.5
    val isPriceBelowBollingerLower: Boolean = false,
    val isVolumeShock: Boolean = false, // Volume > 3x average on sell dump
    val volumeShockMessage: String = "", // "Düşen bıçağı tutma, hacim sakinleşiyor"
    val orderBookDepth: OrderBookDepth = OrderBookDepth(symbol = symbol),
    val candlePattern: String, // e.g. "Boğa Çekiç (Bullish Hammer)", "Destek Sekmesi", "Nötr"
    val volumeRatioToAvg: Double, // e.g. 1.35x
    val volumeNodePrice: Double = 0.0, // High Volume Node / En çok alım olan kümelenme fiyatı
    val volumeClusterDescription: String = "", // Hacim kümelenmesi ve emir akışı açıklaması
    val ambushTimeoutMinutes: Int = 45, // Bekleme süresi (dolmazsa iptal & yeni pusu)
    val confluenceScore: Int, // 0 to 100 institutional quality score
    val isSupportBounceValid: Boolean,
    val isOverboughtRisk: Boolean,
    val recommendation: String, // "MÜKEMMEL DESTEK ALIMI", "BEKLE (DİRENÇ YAKIN)", "NÖTR"
    val dcaTier1Price: Double = 0.0, // Entry Limit
    val dcaTier2Price: Double = 0.0, // Dynamic ATR Dip
    val dcaTier3Price: Double = 0.0, // Dynamic ATR Final Defense
    val dcaTier1Weight: Double = 1.0, // 1x (e.g. $15)
    val dcaTier2Weight: Double = 2.0, // 2x (e.g. $30)
    val dcaTier3Weight: Double = 4.0, // 4x (e.g. $60)
    val lastUpdated: Long = System.currentTimeMillis()
)


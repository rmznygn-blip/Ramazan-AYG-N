package com.example.model

data class CryptoAsset(
    val id: String,
    val symbol: String,
    val name: String,
    val priceFormatted: String,
    val rawPrice: Double,
    val currencySymbol: String = "$",
    val changePercent: Double,
    val changeFormatted: String,
    val isPositive: Boolean,
    val sparklinePoints: List<Float> = listOf(50f, 55f, 53f, 60f, 65f, 62f, 70f, 68f, 75f),
    val detectedAt: Long = System.currentTimeMillis(),
    val sourceApp: String = "Midas Kripto",
    val binanceReferencePrice: Double = 0.0,
    val leadLagDiffPercent: Double = 0.0 // Difference between Binance truth and Midas price
)

data class MidasAccountState(
    val availableCash: Double = 500.0, // Default or parsed cash from Midas screen
    val currencySymbol: String = "$", // "$" or "₺"
    val isCashDetectedFromScreen: Boolean = false,
    val currentViewedSymbol: String = "SOL",
    val lastDetectedTimestamp: Long = System.currentTimeMillis()
)

data class BinanceOracleData(
    val symbol: String,
    val binanceGlobalPrice: Double,
    val midasCurrentPrice: Double,
    val leadLagSpreadPercent: Double, // If positive, Binance is higher -> Midas is cheap!
    val signalRecommendation: String, // "GÜÇLÜ AL", "BEKLE", "KÂR AL"
    val confidence: Double = 0.95
)

data class OverlayConfig(
    val isOledTrueBlack: Boolean = true,
    val opacity: Float = 0.95f,
    val isCompactMode: Boolean = false,
    val autoSnapToEdge: Boolean = true,
    val showSparklines: Boolean = true,
    val targetAppPackage: String = "com.getmidas.app",
    val soundAlertsEnabled: Boolean = false,
    val updateIntervalMs: Long = 1000L,
    val cashAllocationPerTradePercent: Double = 25.0 // %25 of available cash for Tier 1 entry
)

data class ScreenReaderLog(
    val id: Long = System.currentTimeMillis(),
    val timestamp: String,
    val sourcePackage: String,
    val rawTextExtracted: String,
    val detectedSymbols: List<String>,
    val parsedPriceCount: Int,
    val detectedCash: Double? = null
)

data class ServicePermissionsState(
    val overlayPermissionGranted: Boolean = false,
    val accessibilityServiceEnabled: Boolean = false,
    val isOverlayServiceRunning: Boolean = false
)

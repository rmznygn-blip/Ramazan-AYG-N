package com.example.repository

import com.example.model.BinanceOracleData
import com.example.model.CryptoAsset
import com.example.model.MidasAccountState
import com.example.model.OverlayConfig
import com.example.model.ScreenReaderLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CryptoOverlayRepository {

    private val repositoryScope = CoroutineScope(Dispatchers.Default)
    private var livePriceJob: Job? = null

    // Tracked Major Crypto Pairs on Midas & Binance
    val MONITORED_SYMBOLS = listOf("SOL", "BTC", "ETH", "AVAX", "XRP", "DOGE", "PEPE", "SUI")

    private val initialAssets = listOf(
        CryptoAsset(
            id = "SOL",
            symbol = "SOL",
            name = "Solana",
            priceFormatted = "$0.00",
            rawPrice = 0.0,
            currencySymbol = "$",
            changePercent = 0.0,
            changeFormatted = "0.00%",
            isPositive = true,
            sparklinePoints = listOf(50f, 50f, 50f, 50f, 50f),
            sourceApp = "Midas Kripto",
            binanceReferencePrice = 0.0,
            leadLagDiffPercent = 0.0
        ),
        CryptoAsset(
            id = "BTC",
            symbol = "BTC",
            name = "Bitcoin",
            priceFormatted = "$0.00",
            rawPrice = 0.0,
            currencySymbol = "$",
            changePercent = 0.0,
            changeFormatted = "0.00%",
            isPositive = true,
            sparklinePoints = listOf(50f, 50f, 50f, 50f, 50f),
            sourceApp = "Midas Kripto",
            binanceReferencePrice = 0.0,
            leadLagDiffPercent = 0.0
        ),
        CryptoAsset(
            id = "ETH",
            symbol = "ETH",
            name = "Ethereum",
            priceFormatted = "$0.00",
            rawPrice = 0.0,
            currencySymbol = "$",
            changePercent = 0.0,
            changeFormatted = "0.00%",
            isPositive = true,
            sparklinePoints = listOf(50f, 50f, 50f, 50f, 50f),
            sourceApp = "Midas Kripto",
            binanceReferencePrice = 0.0,
            leadLagDiffPercent = 0.0
        ),
        CryptoAsset(
            id = "AVAX",
            symbol = "AVAX",
            name = "Avalanche",
            priceFormatted = "$0.00",
            rawPrice = 0.0,
            currencySymbol = "$",
            changePercent = 0.0,
            changeFormatted = "0.00%",
            isPositive = true,
            sparklinePoints = listOf(50f, 50f, 50f, 50f, 50f),
            sourceApp = "Midas Kripto",
            binanceReferencePrice = 0.0,
            leadLagDiffPercent = 0.0
        )
    )

    private val _cryptoAssets = MutableStateFlow<List<CryptoAsset>>(initialAssets)
    val cryptoAssets: StateFlow<List<CryptoAsset>> = _cryptoAssets.asStateFlow()

    private val _midasAccountState = MutableStateFlow(
        MidasAccountState(
            availableCash = 0.00,
            currencySymbol = "$",
            isCashDetectedFromScreen = false,
            currentViewedSymbol = "SOL"
        )
    )
    val midasAccountState: StateFlow<MidasAccountState> = _midasAccountState.asStateFlow()

    private val _binanceOracleMap = MutableStateFlow<Map<String, BinanceOracleData>>(emptyMap())
    val binanceOracleMap: StateFlow<Map<String, BinanceOracleData>> = _binanceOracleMap.asStateFlow()

    private val _technicalAnalysisMap = MutableStateFlow<Map<String, com.example.model.TechnicalAnalysis5m>>(emptyMap())
    val technicalAnalysisMap: StateFlow<Map<String, com.example.model.TechnicalAnalysis5m>> = _technicalAnalysisMap.asStateFlow()

    private val _overlayConfig = MutableStateFlow(OverlayConfig())
    val overlayConfig: StateFlow<OverlayConfig> = _overlayConfig.asStateFlow()

    private val _screenLogs = MutableStateFlow<List<ScreenReaderLog>>(emptyList())
    val screenLogs: StateFlow<List<ScreenReaderLog>> = _screenLogs.asStateFlow()

    private val _isOverlayRunning = MutableStateFlow(false)
    val isOverlayRunning: StateFlow<Boolean> = _isOverlayRunning.asStateFlow()

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    // 100% Real Trading Mode (Simulation completely disabled for pure production)
    private val _isSimulationActive = MutableStateFlow(false)
    val isSimulationActive: StateFlow<Boolean> = _isSimulationActive.asStateFlow()

    init {
        startRealMarketDataEngine()
    }

    /**
     * Continuous Real-time Binance Public API & Lead-Lag Polling Engine
     */
    fun startRealMarketDataEngine() {
        livePriceJob?.cancel()
        livePriceJob = repositoryScope.launch {
            while (isActive) {
                try {
                    fetchRealBinancePrices()
                    fetchTechnicalCandles()
                } catch (e: Exception) {
                    // Fail gracefully on transient network interruptions
                }
                delay(2000) // Fast 2-second real-time market refresh
            }
        }
    }

    private suspend fun fetchRealBinancePrices() = withContext(Dispatchers.IO) {
        val pairs = listOf("SOLUSDT", "BTCUSDT", "ETHUSDT", "AVAXUSDT", "XRPUSDT", "DOGEUSDT", "PEPEUSDT", "SUIUSDT")
        val pairsParam = pairs.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
        val urlString = "https://api.binance.com/api/v3/ticker/24hr?symbols=$pairsParam"

        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()

                val jsonArray = JSONArray(response)
                val newOracleMap = mutableMapOf<String, BinanceOracleData>()
                val updatedAssets = _cryptoAssets.value.toMutableList()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val symbolPair = obj.getString("symbol")
                    val rawSymbol = symbolPair.replace("USDT", "")
                    val lastPrice = obj.getString("lastPrice").toDoubleOrNull() ?: continue
                    val priceChangePercent = obj.getString("priceChangePercent").toDoubleOrNull() ?: 0.0
                    val highPrice = obj.getString("highPrice").toDoubleOrNull() ?: lastPrice
                    val lowPrice = obj.getString("lowPrice").toDoubleOrNull() ?: lastPrice
                    val volume = obj.getString("volume").toDoubleOrNull() ?: 0.0

                    val existingAsset = updatedAssets.firstOrNull { it.symbol == rawSymbol }
                    val midasPrice = if (existingAsset != null && existingAsset.rawPrice > 0) existingAsset.rawPrice else lastPrice

                    val leadLagSpread = if (midasPrice > 0) {
                        ((lastPrice - midasPrice) / midasPrice) * 100.0
                    } else 0.0

                    val netProfitIfArb = if (leadLagSpread > 0.40) leadLagSpread - 0.40 else 0.0

                    newOracleMap[rawSymbol] = BinanceOracleData(
                        symbol = rawSymbol,
                        binanceGlobalPrice = lastPrice,
                        midasCurrentPrice = midasPrice,
                        leadLagSpreadPercent = leadLagSpread,
                        signalRecommendation = if (leadLagSpread > 0.85) "GÜÇLÜ AL" else if (leadLagSpread > 0.35) "AL" else "BEKLE",
                        binancePrice = lastPrice,
                        midasObservedPrice = midasPrice,
                        isBinanceLeadingHigher = lastPrice > midasPrice,
                        estimatedNetArbProfitPercent = netProfitIfArb,
                        volume24h = volume,
                        high24h = highPrice,
                        low24h = lowPrice,
                        lastUpdated = System.currentTimeMillis()
                    )

                    // Update asset list
                    val assetIndex = updatedAssets.indexOfFirst { it.symbol == rawSymbol }
                    if (assetIndex != -1) {
                        val curr = updatedAssets[assetIndex]
                        val displayPrice = if (curr.rawPrice > 0) curr.rawPrice else lastPrice
                        updatedAssets[assetIndex] = curr.copy(
                            priceFormatted = "$${String.format(Locale.US, if (displayPrice < 1.0) "%.4f" else "%.2f", displayPrice)}",
                            rawPrice = displayPrice,
                            changePercent = priceChangePercent,
                            changeFormatted = "${if (priceChangePercent >= 0) "+" else ""}${String.format(Locale.US, "%.2f", priceChangePercent)}%",
                            isPositive = priceChangePercent >= 0,
                            binanceReferencePrice = lastPrice,
                            leadLagDiffPercent = leadLagSpread
                        )
                    }
                }

                _binanceOracleMap.value = newOracleMap
                _cryptoAssets.value = updatedAssets
            }
            conn.disconnect()
        } catch (e: Exception) {
            // Keep existing prices on connection drop
        }
    }

    private suspend fun fetchTechnicalCandles() = withContext(Dispatchers.IO) {
        val analysisResults = mutableMapOf<String, com.example.model.TechnicalAnalysis5m>()
        val symbolsToAnalyze = listOf("SOL", "BTC", "ETH", "AVAX")

        for (symbol in symbolsToAnalyze) {
            try {
                val url = URL("https://api.binance.com/api/v3/klines?symbol=${symbol}USDT&interval=5m&limit=30")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()

                    val rawArray = JSONArray(response)
                    val candleList = mutableListOf<com.example.model.CandleStick>()

                    for (i in 0 until rawArray.length()) {
                        val c = rawArray.getJSONArray(i)
                        candleList.add(
                            com.example.model.CandleStick(
                                openTime = c.getLong(0),
                                open = c.getString(1).toDouble(),
                                high = c.getString(2).toDouble(),
                                low = c.getString(3).toDouble(),
                                close = c.getString(4).toDouble(),
                                volume = c.getString(5).toDouble(),
                                closeTime = c.getLong(6)
                            )
                        )
                    }

                    val oracle = _binanceOracleMap.value[symbol]
                    val spread = oracle?.leadLagSpreadPercent ?: 0.0

                    val analysis = com.example.engine.TechnicalAnalysisEngine.analyze5mCandles(
                        symbol = symbol,
                        candles = candleList,
                        leadLagSpreadPercent = spread
                    )
                    analysisResults[symbol] = analysis
                }
                conn.disconnect()
            } catch (e: Exception) {
                // Ignore individual symbol network errors
            }
        }

        _technicalAnalysisMap.value = analysisResults
    }

    fun updateOverlayRunning(running: Boolean) {
        _isOverlayRunning.value = running
    }

    fun updateAccessibilityConnected(connected: Boolean) {
        _isAccessibilityConnected.value = connected
    }

    fun updateConfig(config: OverlayConfig) {
        _overlayConfig.value = config
    }

    fun updateConfig(transform: (OverlayConfig) -> OverlayConfig) {
        _overlayConfig.update(transform)
    }

    fun updateMidasCash(cash: Double, currency: String = "$", fromScreen: Boolean = true) {
        if (cash < 0) return
        _midasAccountState.update { current ->
            current.copy(
                availableCash = cash,
                currencySymbol = currency,
                isCashDetectedFromScreen = fromScreen,
                lastDetectedTimestamp = System.currentTimeMillis()
            )
        }
    }

    fun updateViewedSymbol(symbol: String) {
        _midasAccountState.update { it.copy(currentViewedSymbol = symbol) }
    }

    fun addExtractedAssets(
        assets: List<CryptoAsset>,
        rawText: String,
        sourcePackage: String,
        detectedCash: Double? = null
    ) {
        if (assets.isNotEmpty()) {
            val currentMap = _cryptoAssets.value.associateBy { it.symbol }.toMutableMap()
            assets.forEach { incoming ->
                currentMap[incoming.symbol] = incoming
            }
            _cryptoAssets.value = currentMap.values.toList()
            updateViewedSymbol(assets.first().symbol)
        }

        if (detectedCash != null && detectedCash > 0) {
            updateMidasCash(detectedCash, fromScreen = true)
        }

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val log = ScreenReaderLog(
            timestamp = timeFormat.format(Date()),
            sourcePackage = sourcePackage,
            rawTextExtracted = rawText.take(120),
            detectedSymbols = assets.map { it.symbol },
            parsedPriceCount = assets.size,
            detectedCash = detectedCash
        )
        _screenLogs.update { current ->
            (listOf(log) + current).take(25)
        }
    }
}

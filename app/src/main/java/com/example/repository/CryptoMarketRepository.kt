package com.example.repository

import com.example.engine.TechnicalAnalysisEngine
import com.example.model.BinanceOracleData
import com.example.model.CandleStick
import com.example.model.CryptoAsset
import com.example.model.OrderBookDepth
import com.example.model.TechnicalAnalysis5m
import com.example.service.BinanceWebSocketService
import com.example.service.GeminiMarketAnalystService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Single-Source Market Data Repository
 * Powered by Binance WebSockets (Stream) & High-Speed REST fallback.
 */
object CryptoMarketRepository {

    private val repositoryScope = CoroutineScope(Dispatchers.Default)
    private var restPollingJob: Job? = null
    private var wsSyncJob: Job? = null

    // Tracked Trading Pairs on Midas & Binance (Elite 3 Scalping Majors: ETH, AVAX, LINK)
    val TRADING_SYMBOLS = listOf("ETH", "AVAX", "LINK")
    // All Monitored symbols including BTC for Market Direction / Trend Filtering
    val MONITORED_SYMBOLS = listOf("BTC", "ETH", "AVAX", "LINK")

    private val initialAssets = listOf(
        CryptoAsset(id = "ETH", symbol = "ETH", name = "Ethereum", priceFormatted = "$0.00", rawPrice = 0.0, currencySymbol = "$", changePercent = 0.0, changeFormatted = "0.00%", isPositive = true, sparklinePoints = listOf(50f, 50f, 50f, 50f, 50f), sourceApp = "Midas Kripto", binanceReferencePrice = 0.0, leadLagDiffPercent = 0.0),
        CryptoAsset(id = "AVAX", symbol = "AVAX", name = "Avalanche", priceFormatted = "$0.00", rawPrice = 0.0, currencySymbol = "$", changePercent = 0.0, changeFormatted = "0.00%", isPositive = true, sparklinePoints = listOf(50f, 50f, 50f, 50f, 50f), sourceApp = "Midas Kripto", binanceReferencePrice = 0.0, leadLagDiffPercent = 0.0),
        CryptoAsset(id = "LINK", symbol = "LINK", name = "Chainlink", priceFormatted = "$0.00", rawPrice = 0.0, currencySymbol = "$", changePercent = 0.0, changeFormatted = "0.00%", isPositive = true, sparklinePoints = listOf(50f, 50f, 50f, 50f, 50f), sourceApp = "Midas Kripto", binanceReferencePrice = 0.0, leadLagDiffPercent = 0.0)
    )

    private val _cryptoAssets = MutableStateFlow<List<CryptoAsset>>(initialAssets)
    val cryptoAssets: StateFlow<List<CryptoAsset>> = _cryptoAssets.asStateFlow()

    private val _binanceOracleMap = MutableStateFlow<Map<String, BinanceOracleData>>(emptyMap())
    val binanceOracleMap: StateFlow<Map<String, BinanceOracleData>> = _binanceOracleMap.asStateFlow()

    private val _technicalAnalysisMap = MutableStateFlow<Map<String, TechnicalAnalysis5m>>(emptyMap())
    val technicalAnalysisMap: StateFlow<Map<String, TechnicalAnalysis5m>> = _technicalAnalysisMap.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastRefreshTime = MutableStateFlow(System.currentTimeMillis())
    val lastRefreshTime: StateFlow<Long> = _lastRefreshTime.asStateFlow()

    val isWebSocketConnected: StateFlow<Boolean> = BinanceWebSocketService.isConnected

    init {
        startMarketDataEngine()
    }

    fun startMarketDataEngine() {
        // 1. Start Binance WebSocket combined streaming
        BinanceWebSocketService.startStreaming(MONITORED_SYMBOLS)

        // 2. Launch reactive WS sync job
        wsSyncJob?.cancel()
        wsSyncJob = repositoryScope.launch {
            launch {
                BinanceWebSocketService.livePrices.collectLatest { prices ->
                    if (prices.isNotEmpty()) {
                        updateAssetsWithPrices(prices)
                    }
                }
            }
            launch {
                BinanceWebSocketService.candles5mMap.collectLatest { candlesMap ->
                    if (candlesMap.isNotEmpty()) {
                        recomputeTechnicalAnalyses(candlesMap)
                    }
                }
            }
        }

        // 3. Launch REST Polling engine for initial bootstrap and backup synchronization
        restPollingJob?.cancel()
        restPollingJob = repositoryScope.launch {
            while (isActive) {
                try {
                    fetchRealBinancePrices()
                    fetchTechnicalCandles()
                    _lastRefreshTime.value = System.currentTimeMillis()
                } catch (e: Exception) {
                    // Graceful error handling
                }
                delay(3000) // 3-second heartbeat backup
            }
        }
    }

    private fun updateAssetsWithPrices(prices: Map<String, Double>) {
        val updated = _cryptoAssets.value.map { asset ->
            val wsPrice = prices[asset.symbol]
            if (wsPrice != null && wsPrice > 0) {
                asset.copy(
                    rawPrice = wsPrice,
                    priceFormatted = "$${String.format(Locale.US, if (wsPrice < 1.0) "%.4f" else "%.2f", wsPrice)}",
                    binanceReferencePrice = wsPrice
                )
            } else asset
        }
        _cryptoAssets.value = updated
    }

    private fun recomputeTechnicalAnalyses(candlesMap: Map<String, List<CandleStick>>) {
        val candles3mMap = BinanceWebSocketService.candles3mMap.value
        val orderBooks = BinanceWebSocketService.orderBookMap.value
        val newTechMap = _technicalAnalysisMap.value.toMutableMap()
        val updatedAssets = _cryptoAssets.value.toMutableList()
        var assetsChanged = false

        candlesMap.forEach { (symbol, candles5m) ->
            val candles3m = candles3mMap[symbol] ?: emptyList()
            val orderBook = orderBooks[symbol]
            val analysis = TechnicalAnalysisEngine.analyzeCandles(
                symbol = symbol,
                candles5m = candles5m,
                candles3m = candles3m,
                orderBook = orderBook
            )
            if (analysis != null) {
                newTechMap[symbol] = analysis
            }

            // Extract last 20 candles for live Candlestick & MiniSparkline visualization
            val recent20Candles = candles5m.takeLast(20)
            if (recent20Candles.isNotEmpty()) {
                val assetIndex = updatedAssets.indexOfFirst { it.symbol == symbol }
                if (assetIndex != -1) {
                    val curr = updatedAssets[assetIndex]
                    val calculatedVwap = analysis?.vwap ?: TechnicalAnalysisEngine.calculateVWAP(candles5m)
                    val calculatedVolMomentum = analysis?.volumeMomentum ?: TechnicalAnalysisEngine.calculateVolumeMomentum(candles5m)
                    updatedAssets[assetIndex] = curr.copy(
                        sparklinePoints = recent20Candles.map { it.close.toFloat() },
                        recentCandles = recent20Candles,
                        vwap = calculatedVwap,
                        volumeMomentum = calculatedVolMomentum
                    )
                    assetsChanged = true
                }
            }
        }
        _technicalAnalysisMap.value = newTechMap

        if (assetsChanged) {
            _cryptoAssets.value = updatedAssets
        }
    }

    fun refreshManually() {
        repositoryScope.launch {
            _isRefreshing.value = true
            try {
                fetchRealBinancePrices()
                fetchTechnicalCandles()
                _lastRefreshTime.value = System.currentTimeMillis()
            } catch (e: Exception) {
                // ignore
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun fetchRealBinancePrices() = withContext(Dispatchers.IO) {
        val pairs = MONITORED_SYMBOLS.map { "${it}USDT" }
        val pairsParam = pairs.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
        val urlString = "https://api.binance.com/api/v3/ticker/24hr?symbols=$pairsParam"

        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3500
            conn.readTimeout = 3500

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
            // Keep existing state on transient drops
        }
    }

    private suspend fun fetchTechnicalCandles() = withContext(Dispatchers.IO) {
        val analysisResults = mutableMapOf<String, TechnicalAnalysis5m>()
        val symbolsToAnalyze = listOf("BTC", "ETH", "AVAX", "LINK")
        val orderBooks = BinanceWebSocketService.orderBookMap.value

        for (symbol in symbolsToAnalyze) {
            try {
                // Fetch 5m candles
                val url5m = URL("https://api.binance.com/api/v3/klines?symbol=${symbol}USDT&interval=5m&limit=30")
                val conn5m = url5m.openConnection() as HttpURLConnection
                conn5m.connectTimeout = 3000
                conn5m.readTimeout = 3000

                val candleList5m = mutableListOf<CandleStick>()
                if (conn5m.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn5m.inputStream))
                    val response = reader.readText()
                    reader.close()

                    val rawArray = JSONArray(response)
                    for (i in 0 until rawArray.length()) {
                        val c = rawArray.getJSONArray(i)
                        candleList5m.add(
                            CandleStick(
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
                }
                conn5m.disconnect()

                if (candleList5m.isNotEmpty()) {
                    val analysis = TechnicalAnalysisEngine.analyzeCandles(
                        symbol = symbol,
                        candles5m = candleList5m,
                        candles3m = emptyList(),
                        orderBook = orderBooks[symbol]
                    )
                    if (analysis != null) {
                        analysisResults[symbol] = analysis
                    }

                    // Update asset list with VWAP and volume momentum
                    val assetList = _cryptoAssets.value.toMutableList()
                    val idx = assetList.indexOfFirst { it.symbol == symbol }
                    if (idx != -1) {
                        val recent20 = candleList5m.takeLast(20)
                        val curr = assetList[idx]
                        assetList[idx] = curr.copy(
                            recentCandles = recent20,
                            sparklinePoints = recent20.map { it.close.toFloat() },
                            vwap = analysis?.vwap ?: TechnicalAnalysisEngine.calculateVWAP(candleList5m),
                            volumeMomentum = analysis?.volumeMomentum ?: TechnicalAnalysisEngine.calculateVolumeMomentum(candleList5m)
                        )
                        _cryptoAssets.value = assetList
                    }
                }
            } catch (e: Exception) {
                // Ignore individual symbol network error
            }
        }

        if (analysisResults.isNotEmpty()) {
            _technicalAnalysisMap.value = analysisResults
        }
    }

    suspend fun getAiMarketAnalysis(symbol: String, tierName: String = "1. Kademe"): Pair<String, GeminiMarketAnalystService.EngineStatus> {
        val asset = _cryptoAssets.value.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }
        val price = asset?.rawPrice ?: 0.0
        val tech = _technicalAnalysisMap.value[symbol]

        return GeminiMarketAnalystService.analyzeTradeOpportunity(
            symbol = symbol,
            currentPrice = price,
            tech = tech,
            targetNetProfitPercent = 2.0,
            tierName = tierName
        )
    }
}

package com.example.repository

import com.example.engine.TechnicalAnalysisEngine
import com.example.model.BinanceOracleData
import com.example.model.CandleStick
import com.example.model.CryptoAsset
import com.example.model.TechnicalAnalysis5m
import com.example.service.GeminiMarketAnalystService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object CryptoMarketRepository {

    private val repositoryScope = CoroutineScope(Dispatchers.Default)
    private var livePriceJob: Job? = null

    // Tracked Major Crypto Pairs on Midas & Binance
    val MONITORED_SYMBOLS = listOf("SOL", "BTC", "ETH", "AVAX", "XRP", "DOGE", "PEPE", "SUI")

    private val initialAssets = listOf(
        CryptoAsset(id = "SOL", symbol = "SOL", name = "Solana", priceFormatted = "$0.00", rawPrice = 0.0, currencySymbol = "$", changePercent = 0.0, changeFormatted = "0.00%", isPositive = true, sparklinePoints = listOf(50f, 50f, 50f, 50f, 50f), sourceApp = "Midas Kripto", binanceReferencePrice = 0.0, leadLagDiffPercent = 0.0),
        CryptoAsset(id = "BTC", symbol = "BTC", name = "Bitcoin", priceFormatted = "$0.00", rawPrice = 0.0, currencySymbol = "$", changePercent = 0.0, changeFormatted = "0.00%", isPositive = true, sparklinePoints = listOf(50f, 50f, 50f, 50f, 50f), sourceApp = "Midas Kripto", binanceReferencePrice = 0.0, leadLagDiffPercent = 0.0),
        CryptoAsset(id = "ETH", symbol = "ETH", name = "Ethereum", priceFormatted = "$0.00", rawPrice = 0.0, currencySymbol = "$", changePercent = 0.0, changeFormatted = "0.00%", isPositive = true, sparklinePoints = listOf(50f, 50f, 50f, 50f, 50f), sourceApp = "Midas Kripto", binanceReferencePrice = 0.0, leadLagDiffPercent = 0.0),
        CryptoAsset(id = "AVAX", symbol = "AVAX", name = "Avalanche", priceFormatted = "$0.00", rawPrice = 0.0, currencySymbol = "$", changePercent = 0.0, changeFormatted = "0.00%", isPositive = true, sparklinePoints = listOf(50f, 50f, 50f, 50f, 50f), sourceApp = "Midas Kripto", binanceReferencePrice = 0.0, leadLagDiffPercent = 0.0),
        CryptoAsset(id = "XRP", symbol = "XRP", name = "Ripple", priceFormatted = "$0.00", rawPrice = 0.0, currencySymbol = "$", changePercent = 0.0, changeFormatted = "0.00%", isPositive = true, sparklinePoints = listOf(50f, 50f, 50f, 50f, 50f), sourceApp = "Midas Kripto", binanceReferencePrice = 0.0, leadLagDiffPercent = 0.0),
        CryptoAsset(id = "DOGE", symbol = "DOGE", name = "Dogecoin", priceFormatted = "$0.00", rawPrice = 0.0, currencySymbol = "$", changePercent = 0.0, changeFormatted = "0.00%", isPositive = true, sparklinePoints = listOf(50f, 50f, 50f, 50f, 50f), sourceApp = "Midas Kripto", binanceReferencePrice = 0.0, leadLagDiffPercent = 0.0),
        CryptoAsset(id = "PEPE", symbol = "PEPE", name = "Pepe", priceFormatted = "$0.00", rawPrice = 0.0, currencySymbol = "$", changePercent = 0.0, changeFormatted = "0.00%", isPositive = true, sparklinePoints = listOf(50f, 50f, 50f, 50f, 50f), sourceApp = "Midas Kripto", binanceReferencePrice = 0.0, leadLagDiffPercent = 0.0),
        CryptoAsset(id = "SUI", symbol = "SUI", name = "Sui", priceFormatted = "$0.00", rawPrice = 0.0, currencySymbol = "$", changePercent = 0.0, changeFormatted = "0.00%", isPositive = true, sparklinePoints = listOf(50f, 50f, 50f, 50f, 50f), sourceApp = "Midas Kripto", binanceReferencePrice = 0.0, leadLagDiffPercent = 0.0)
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

    init {
        startRealMarketDataEngine()
    }

    fun startRealMarketDataEngine() {
        livePriceJob?.cancel()
        livePriceJob = repositoryScope.launch {
            while (isActive) {
                try {
                    fetchRealBinancePrices()
                    fetchTechnicalCandles()
                    _lastRefreshTime.value = System.currentTimeMillis()
                } catch (e: Exception) {
                    // Graceful error handling
                }
                delay(3000) // Continuous 3-second market update
            }
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
        val symbolsToAnalyze = listOf("SOL", "BTC", "ETH", "AVAX", "XRP", "DOGE", "SUI")

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
                    val candleList = mutableListOf<CandleStick>()

                    for (i in 0 until rawArray.length()) {
                        val c = rawArray.getJSONArray(i)
                        candleList.add(
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

                    val oracle = _binanceOracleMap.value[symbol]
                    val spread = oracle?.leadLagSpreadPercent ?: 0.0

                    val analysis = TechnicalAnalysisEngine.analyze5mCandles(
                        symbol = symbol,
                        candles = candleList,
                        leadLagSpreadPercent = spread
                    )
                    analysisResults[symbol] = analysis
                }
                conn.disconnect()
            } catch (e: Exception) {
                // Ignore individual symbol network error
            }
        }

        _technicalAnalysisMap.value = analysisResults
    }

    suspend fun getAiMarketAnalysis(symbol: String): String {
        val asset = _cryptoAssets.value.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }
        val price = asset?.rawPrice ?: 0.0
        val tech = _technicalAnalysisMap.value[symbol]
        val oracle = _binanceOracleMap.value[symbol]

        return GeminiMarketAnalystService.analyzeTradeOpportunity(
            symbol = symbol,
            currentPrice = price,
            supportLevel = tech?.supportLevel ?: (price * 0.98),
            resistanceLevel = tech?.resistanceLevel ?: (price * 1.02),
            rsi14 = tech?.rsi14 ?: 50.0,
            binanceLeadPercent = oracle?.leadLagSpreadPercent ?: 0.0,
            targetNetProfitPercent = 2.0
        )
    }
}

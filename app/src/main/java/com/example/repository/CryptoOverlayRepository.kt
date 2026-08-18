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
import kotlin.random.Random

object CryptoOverlayRepository {

    private val repositoryScope = CoroutineScope(Dispatchers.Default)
    private var simulationJob: Job? = null
    private var livePriceJob: Job? = null

    private val defaultAssets = listOf(
        CryptoAsset(
            id = "SOL",
            symbol = "SOL",
            name = "Solana",
            priceFormatted = "$78.40",
            rawPrice = 78.40,
            currencySymbol = "$",
            changePercent = 2.15,
            changeFormatted = "+2.15%",
            isPositive = true,
            sparklinePoints = listOf(30f, 35f, 40f, 38f, 45f, 50f, 55f, 60f, 68f),
            sourceApp = "Midas Kripto",
            binanceReferencePrice = 78.90,
            leadLagDiffPercent = 0.64
        ),
        CryptoAsset(
            id = "BTC",
            symbol = "BTC",
            name = "Bitcoin",
            priceFormatted = "$96,480.50",
            rawPrice = 96480.50,
            currencySymbol = "$",
            changePercent = 2.42,
            changeFormatted = "+2.42%",
            isPositive = true,
            sparklinePoints = listOf(40f, 42f, 45f, 44f, 48f, 52f, 50f, 55f, 60f),
            sourceApp = "Midas Kripto",
            binanceReferencePrice = 96720.00,
            leadLagDiffPercent = 0.25
        ),
        CryptoAsset(
            id = "ETH",
            symbol = "ETH",
            name = "Ethereum",
            priceFormatted = "$2,745.20",
            rawPrice = 2745.20,
            currencySymbol = "$",
            changePercent = -0.65,
            changeFormatted = "-0.65%",
            isPositive = false,
            sparklinePoints = listOf(60f, 58f, 55f, 54f, 52f, 53f, 50f, 48f, 47f),
            sourceApp = "Midas Kripto",
            binanceReferencePrice = 2758.40,
            leadLagDiffPercent = 0.48
        ),
        CryptoAsset(
            id = "AVAX",
            symbol = "AVAX",
            name = "Avalanche",
            priceFormatted = "$28.35",
            rawPrice = 28.35,
            currencySymbol = "$",
            changePercent = 3.10,
            changeFormatted = "+3.10%",
            isPositive = true,
            sparklinePoints = listOf(45f, 46f, 44f, 47f, 48f, 50f, 52f, 51f, 53f),
            sourceApp = "Midas Kripto",
            binanceReferencePrice = 28.60,
            leadLagDiffPercent = 0.88
        )
    )

    private val _cryptoAssets = MutableStateFlow<List<CryptoAsset>>(defaultAssets)
    val cryptoAssets: StateFlow<List<CryptoAsset>> = _cryptoAssets.asStateFlow()

    private val _midasAccountState = MutableStateFlow(
        MidasAccountState(
            availableCash = 50.00,
            currencySymbol = "$",
            isCashDetectedFromScreen = false,
            currentViewedSymbol = "SOL"
        )
    )
    val midasAccountState: StateFlow<MidasAccountState> = _midasAccountState.asStateFlow()

    private val _binanceOracleMap = MutableStateFlow<Map<String, BinanceOracleData>>(emptyMap())
    val binanceOracleMap: StateFlow<Map<String, BinanceOracleData>> = _binanceOracleMap.asStateFlow()

    private val _overlayConfig = MutableStateFlow(OverlayConfig())
    val overlayConfig: StateFlow<OverlayConfig> = _overlayConfig.asStateFlow()

    private val _screenLogs = MutableStateFlow<List<ScreenReaderLog>>(emptyList())
    val screenLogs: StateFlow<List<ScreenReaderLog>> = _screenLogs.asStateFlow()

    private val _isOverlayRunning = MutableStateFlow(false)
    val isOverlayRunning: StateFlow<Boolean> = _isOverlayRunning.asStateFlow()

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private val _isSimulationActive = MutableStateFlow(false)
    val isSimulationActive: StateFlow<Boolean> = _isSimulationActive.asStateFlow()

    init {
        updateOracleState(_cryptoAssets.value)
        startLiveBinancePolling()
    }

    private fun startLiveBinancePolling() {
        livePriceJob?.cancel()
        livePriceJob = repositoryScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    fetchRealBinancePrices()
                } catch (e: Exception) {
                    // Ignore network errors in background
                }
                delay(3000L) // Refresh every 3 seconds for live rates
            }
        }
    }

    private suspend fun fetchRealBinancePrices() = withContext(Dispatchers.IO) {
        val symbolsToTrack = listOf("SOLUSDT", "BTCUSDT", "ETHUSDT", "AVAXUSDT", "XRPUSDT", "DOGEUSDT", "SUIUSDT")
        val symbolsJson = symbolsToTrack.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
        val urlStr = "https://api.binance.com/api/v3/ticker/24hr?symbols=$symbolsJson"

        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("Accept", "application/json")
        }

        if (conn.responseCode == 200) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val response = reader.readText()
            reader.close()

            val jsonArray = JSONArray(response)
            val updatedList = _cryptoAssets.value.toMutableList()

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val pair = item.getString("symbol")
                val symbol = pair.removeSuffix("USDT")
                val lastPrice = item.getDouble("lastPrice")
                val priceChangePercent = item.getDouble("priceChangePercent")

                val existingIdx = updatedList.indexOfFirst { it.symbol.equals(symbol, ignoreCase = true) }
                if (existingIdx >= 0) {
                    val existing = updatedList[existingIdx]
                    // If user is not running simulation, update to real live price
                    if (!_isSimulationActive.value) {
                        val midasPrice = existing.rawPrice.takeIf { it > 0 && it != defaultAssets.find { d -> d.symbol == symbol }?.rawPrice } ?: (lastPrice * 0.994) // Default Midas is slightly lagging spot
                        val spread = ((lastPrice - midasPrice) / midasPrice) * 100.0
                        val prefix = if (priceChangePercent >= 0) "+" else ""

                        updatedList[existingIdx] = existing.copy(
                            rawPrice = lastPrice,
                            priceFormatted = formatPrice(lastPrice, "$"),
                            changePercent = priceChangePercent,
                            changeFormatted = "$prefix${String.format(Locale.US, "%.2f", priceChangePercent)}%",
                            isPositive = priceChangePercent >= 0,
                            binanceReferencePrice = lastPrice,
                            leadLagDiffPercent = spread,
                            detectedAt = System.currentTimeMillis()
                        )
                    }
                } else {
                    val prefix = if (priceChangePercent >= 0) "+" else ""
                    updatedList.add(
                        CryptoAsset(
                            id = symbol,
                            symbol = symbol,
                            name = symbol,
                            priceFormatted = formatPrice(lastPrice, "$"),
                            rawPrice = lastPrice,
                            currencySymbol = "$",
                            changePercent = priceChangePercent,
                            changeFormatted = "$prefix${String.format(Locale.US, "%.2f", priceChangePercent)}%",
                            isPositive = priceChangePercent >= 0,
                            binanceReferencePrice = lastPrice,
                            leadLagDiffPercent = 0.50,
                            sourceApp = "Binance Canlı"
                        )
                    )
                }
            }

            _cryptoAssets.value = updatedList
            updateOracleState(updatedList)
        }
        conn.disconnect()
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
            _cryptoAssets.value = assets
            updateOracleState(assets)
            updateViewedSymbol(assets.first().symbol)
        }
        if (detectedCash != null && detectedCash > 0) {
            updateMidasCash(detectedCash, fromScreen = true)
        }

        val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val log = ScreenReaderLog(
            timestamp = timeFormat.format(Date()),
            sourcePackage = sourcePackage,
            rawTextExtracted = rawText.take(150),
            detectedSymbols = assets.map { it.symbol },
            parsedPriceCount = assets.size,
            detectedCash = detectedCash
        )
        _screenLogs.update { current ->
            (listOf(log) + current).take(50)
        }
    }

    private fun updateOracleState(assets: List<CryptoAsset>) {
        val map = mutableMapOf<String, BinanceOracleData>()
        assets.forEach { asset ->
            val binancePrice = if (asset.binanceReferencePrice > 0) {
                asset.binanceReferencePrice
            } else {
                asset.rawPrice * 1.0065 // Binance leads by +0.65%
            }
            val spreadPercent = ((binancePrice - asset.rawPrice) / asset.rawPrice) * 100.0
            val recommendation = if (spreadPercent > 0.40) "MİDAS'TA UCUZ (AL)" else if (spreadPercent < -0.40) "ZİRVEDE (KÂR AL)" else "NÖTR"

            map[asset.symbol] = BinanceOracleData(
                symbol = asset.symbol,
                binanceGlobalPrice = binancePrice,
                midasCurrentPrice = asset.rawPrice,
                leadLagSpreadPercent = spreadPercent,
                signalRecommendation = recommendation,
                confidence = 0.94
            )
        }
        _binanceOracleMap.value = map
    }

    fun toggleSimulation(active: Boolean) {
        _isSimulationActive.value = active
        if (active) {
            startSimulation()
        } else {
            simulationJob?.cancel()
            simulationJob = null
        }
    }

    private fun startSimulation() {
        simulationJob?.cancel()
        simulationJob = repositoryScope.launch {
            while (isActive) {
                delay(2500L)
                val current = _cryptoAssets.value
                val updated = current.map { asset ->
                    val binanceLeadDelta = Random.nextDouble(-0.8, 1.2)
                    val binancePrice = (asset.binanceReferencePrice.takeIf { it > 0 } ?: asset.rawPrice) * (1 + binanceLeadDelta / 100.0)

                    // Midas follows Binance with slight micro-lag (giving user the profit edge!)
                    val midasDelta = binanceLeadDelta * 0.7
                    val newMidasPrice = (asset.rawPrice * (1 + midasDelta / 100.0)).coerceAtLeast(0.01)

                    val spread = ((binancePrice - newMidasPrice) / newMidasPrice) * 100.0
                    val newPoints = (asset.sparklinePoints.drop(1) + (asset.sparklinePoints.last() + midasDelta.toFloat() * 1.5f)).takeLast(9)
                    val newChange = asset.changePercent + midasDelta
                    val prefix = if (newChange >= 0) "+" else ""

                    asset.copy(
                        rawPrice = newMidasPrice,
                        priceFormatted = formatPrice(newMidasPrice, asset.currencySymbol),
                        changePercent = newChange,
                        changeFormatted = "$prefix${String.format(Locale.US, "%.2f", newChange)}%",
                        isPositive = newChange >= 0,
                        sparklinePoints = newPoints,
                        binanceReferencePrice = binancePrice,
                        leadLagDiffPercent = spread,
                        detectedAt = System.currentTimeMillis()
                    )
                }
                _cryptoAssets.value = updated
                updateOracleState(updated)
            }
        }
    }

    private fun formatPrice(price: Double, currency: String): String {
        return if (price >= 1000) {
            String.format(Locale.US, "%s%,.2f", currency, price)
        } else if (price >= 1) {
            String.format(Locale.US, "%s%.2f", currency, price)
        } else {
            String.format(Locale.US, "%s%.4f", currency, price)
        }
    }
}

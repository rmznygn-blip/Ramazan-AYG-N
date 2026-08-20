package com.example.service

import android.util.Log
import com.example.model.CandleStick
import com.example.model.OrderBookDepth
import com.example.model.OrderBookLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * High-performance, single-source Binance WebSockets engine.
 * Streams real-time 3m & 5m Klines, Depth (Order Book 20), and Live Prices.
 */
object BinanceWebSocketService {

    private const val TAG = "BinanceWebSocketService"
    private const val WS_BASE_URL = "wss://stream.binance.com:9443/stream?streams="

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var connectionJob: Job? = null

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep-alive for WebSocket
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _lastMessageTime = MutableStateFlow(0L)
    val lastMessageTime: StateFlow<Long> = _lastMessageTime.asStateFlow()

    // Live Prices Map (Symbol -> Double)
    private val _livePrices = MutableStateFlow<Map<String, Double>>(emptyMap())
    val livePrices: StateFlow<Map<String, Double>> = _livePrices.asStateFlow()

    // Live 5m Candles (Symbol -> List<CandleStick>)
    private val _candles5mMap = MutableStateFlow<Map<String, List<CandleStick>>>(emptyMap())
    val candles5mMap: StateFlow<Map<String, List<CandleStick>>> = _candles5mMap.asStateFlow()

    // Live 3m Candles (Symbol -> List<CandleStick>)
    private val _candles3mMap = MutableStateFlow<Map<String, List<CandleStick>>>(emptyMap())
    val candles3mMap: StateFlow<Map<String, List<CandleStick>>> = _candles3mMap.asStateFlow()

    // Live Order Book Depths (Symbol -> OrderBookDepth)
    private val _orderBookMap = MutableStateFlow<Map<String, OrderBookDepth>>(emptyMap())
    val orderBookMap: StateFlow<Map<String, OrderBookDepth>> = _orderBookMap.asStateFlow()

    fun startStreaming(symbols: List<String>) {
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            connectWebSocket(symbols)
            
            // Watchdog to ensure continuous stream connection
            while (isActive) {
                delay(15_000)
                val now = System.currentTimeMillis()
                if (now - _lastMessageTime.value > 30_000L && _lastMessageTime.value > 0) {
                    Log.w(TAG, "WebSocket stream silent for >30s. Reconnecting...")
                    reconnect(symbols)
                }
            }
        }
    }

    private fun connectWebSocket(symbols: List<String>) {
        val streamList = mutableListOf<String>()
        symbols.forEach { sym ->
            val lower = sym.lowercase() + "usdt"
            streamList.add("${lower}@kline_5m")
            streamList.add("${lower}@kline_3m")
            streamList.add("${lower}@depth20@100ms")
            streamList.add("${lower}@ticker")
        }

        val url = WS_BASE_URL + streamList.joinToString("/")
        val request = Request.Builder().url(url).build()

        webSocket?.close(1000, "Reconnecting")
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _isConnected.value = true
                Log.d(TAG, "Binance WebSocket connected for ${symbols.size} symbols.")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                _lastMessageTime.value = System.currentTimeMillis()
                parseCombinedStreamMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _isConnected.value = false
                Log.w(TAG, "WebSocket Failure: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
                Log.d(TAG, "WebSocket Closed: $reason")
            }
        })
    }

    private fun reconnect(symbols: List<String>) {
        try {
            webSocket?.cancel()
        } catch (e: Exception) {
            // ignore
        }
        connectWebSocket(symbols)
    }

    private fun parseCombinedStreamMessage(jsonString: String) {
        try {
            val root = JSONObject(jsonString)
            val stream = root.optString("stream", "")
            val data = root.optJSONObject("data") ?: return

            when {
                stream.contains("@kline_5m") -> {
                    val kline = data.optJSONObject("k") ?: return
                    val symbol = kline.optString("s", "").replace("USDT", "")
                    val openTime = kline.optLong("t", 0L)
                    val open = kline.optString("o", "0").toDoubleOrNull() ?: 0.0
                    val high = kline.optString("h", "0").toDoubleOrNull() ?: 0.0
                    val low = kline.optString("l", "0").toDoubleOrNull() ?: 0.0
                    val close = kline.optString("c", "0").toDoubleOrNull() ?: 0.0
                    val volume = kline.optString("v", "0").toDoubleOrNull() ?: 0.0
                    val closeTime = kline.optLong("T", 0L)

                    val candle = CandleStick(openTime, open, high, low, close, volume, closeTime)
                    updateCandleList(_candles5mMap, symbol, candle)

                    // Also update live price
                    if (close > 0) {
                        val currentPrices = _livePrices.value.toMutableMap()
                        currentPrices[symbol] = close
                        _livePrices.value = currentPrices
                    }
                }

                stream.contains("@kline_3m") -> {
                    val kline = data.optJSONObject("k") ?: return
                    val symbol = kline.optString("s", "").replace("USDT", "")
                    val openTime = kline.optLong("t", 0L)
                    val open = kline.optString("o", "0").toDoubleOrNull() ?: 0.0
                    val high = kline.optString("h", "0").toDoubleOrNull() ?: 0.0
                    val low = kline.optString("l", "0").toDoubleOrNull() ?: 0.0
                    val close = kline.optString("c", "0").toDoubleOrNull() ?: 0.0
                    val volume = kline.optString("v", "0").toDoubleOrNull() ?: 0.0
                    val closeTime = kline.optLong("T", 0L)

                    val candle = CandleStick(openTime, open, high, low, close, volume, closeTime)
                    updateCandleList(_candles3mMap, symbol, candle)
                }

                stream.contains("@depth20") -> {
                    val rawSymbol = stream.substringBefore("@").replace("usdt", "").uppercase()
                    val bidsArray = data.optJSONArray("bids")
                    val asksArray = data.optJSONArray("asks")

                    val bids = mutableListOf<OrderBookLevel>()
                    var totalBidVol = 0.0
                    if (bidsArray != null) {
                        for (i in 0 until bidsArray.length()) {
                            val item = bidsArray.getJSONArray(i)
                            val p = item.getString(0).toDoubleOrNull() ?: 0.0
                            val q = item.getString(1).toDoubleOrNull() ?: 0.0
                            bids.add(OrderBookLevel(p, q))
                            totalBidVol += (p * q)
                        }
                    }

                    val asks = mutableListOf<OrderBookLevel>()
                    var totalAskVol = 0.0
                    if (asksArray != null) {
                        for (i in 0 until asksArray.length()) {
                            val item = asksArray.getJSONArray(i)
                            val p = item.getString(0).toDoubleOrNull() ?: 0.0
                            val q = item.getString(1).toDoubleOrNull() ?: 0.0
                            asks.add(OrderBookLevel(p, q))
                            totalAskVol += (p * q)
                        }
                    }

                    val totalVol = totalBidVol + totalAskVol
                    val bidRatio = if (totalVol > 0) totalBidVol / totalVol else 0.50
                    val isBuyerDominant = bidRatio >= 0.60
                    val isOrderBookFear = bidRatio < 0.60
                    val sentimentText = when {
                        bidRatio >= 0.65 -> "🟢 GÜÇLÜ ALICI DUVARI (%${String.format(java.util.Locale.US, "%.0f", bidRatio * 100)})"
                        bidRatio >= 0.55 -> "⚖️ DENGELİ ALIM AĞIRLIĞI (%${String.format(java.util.Locale.US, "%.0f", bidRatio * 100)})"
                        bidRatio >= 0.45 -> "⚠️ NÖTR / HAFİF BASKI"
                        else -> "🔴 SATIŞ BASKISI & KORKU (%${String.format(java.util.Locale.US, "%.0f", (1.0 - bidRatio) * 100)} Satıcı)"
                    }

                    val depth = OrderBookDepth(
                        symbol = rawSymbol,
                        bids = bids,
                        asks = asks,
                        totalBidVolume = totalBidVol,
                        totalAskVolume = totalAskVol,
                        bidRatio = bidRatio,
                        isBuyerDominant = isBuyerDominant,
                        isOrderBookFear = isOrderBookFear,
                        sentimentText = sentimentText,
                        timestamp = System.currentTimeMillis()
                    )

                    val currentDepths = _orderBookMap.value.toMutableMap()
                    currentDepths[rawSymbol] = depth
                    _orderBookMap.value = currentDepths
                }

                stream.contains("@ticker") -> {
                    val symbol = data.optString("s", "").replace("USDT", "")
                    val lastPrice = data.optString("c", "0").toDoubleOrNull() ?: 0.0
                    if (symbol.isNotBlank() && lastPrice > 0) {
                        val currentPrices = _livePrices.value.toMutableMap()
                        currentPrices[symbol] = lastPrice
                        _livePrices.value = currentPrices
                    }
                }
            }
        } catch (e: Exception) {
            // parse error ignore
        }
    }

    private fun updateCandleList(
        mapFlow: MutableStateFlow<Map<String, List<CandleStick>>>,
        symbol: String,
        newCandle: CandleStick
    ) {
        val currentMap = mapFlow.value.toMutableMap()
        val list = currentMap[symbol]?.toMutableList() ?: mutableListOf()

        if (list.isEmpty()) {
            list.add(newCandle)
        } else {
            val lastIdx = list.size - 1
            val lastCandle = list[lastIdx]
            if (lastCandle.openTime == newCandle.openTime) {
                // Update in-progress candle
                list[lastIdx] = newCandle
            } else if (newCandle.openTime > lastCandle.openTime) {
                list.add(newCandle)
                if (list.size > 50) {
                    list.removeAt(0)
                }
            }
        }
        currentMap[symbol] = list
        mapFlow.value = currentMap
    }

    fun stopStreaming() {
        connectionJob?.cancel()
        webSocket?.close(1000, "App stopped")
        _isConnected.value = false
    }
}

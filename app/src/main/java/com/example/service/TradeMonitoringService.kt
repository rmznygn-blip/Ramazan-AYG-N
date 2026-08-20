package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.local.AppTradeEntity
import com.example.repository.CryptoMarketRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground Service for 24/7 background crypto market monitoring.
 * Keeps Binance WebSocket connection alive and dispatches real-time local push notifications
 * and spoken voice alerts (TextToSpeech) when PENDING_BUY ambush entries hit or ACTIVE_OPEN profit exit targets are reached.
 */
class TradeMonitoringService : Service(), TextToSpeech.OnInitListener {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private val triggeredTradeIds = mutableSetOf<String>()
    private var monitorJob: Job? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    companion object {
        private const val TAG = "TradeMonitoringService"
        const val FOREGROUND_CHANNEL_ID = "crypto_analyst_foreground_channel"
        const val ALERTS_CHANNEL_ID = "crypto_analyst_alerts_channel"
        const val FOREGROUND_NOTIFICATION_ID = 1001

        fun startService(context: Context) {
            val intent = Intent(context, TradeMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, TradeMonitoringService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("tr", "TR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            isTtsReady = true
        } else {
            Log.e(TAG, "TextToSpeech initialization failed with status: $status")
            isTtsReady = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createForegroundNotification("Binance canlı verileri ve pusu emirleri izleniyor...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }

        startMonitoringEngine()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        monitorJob?.cancel()
        serviceScope.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
        Log.d(TAG, "TradeMonitoringService destroyed.")
    }

    private fun startMonitoringEngine() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            // Ensure WebSocket connection is actively streaming symbols
            BinanceWebSocketService.startStreaming(CryptoMarketRepository.MONITORED_SYMBOLS)

            val db = AppDatabase.getDatabase(applicationContext)
            val dao = db.appDao()

            // Combine active trades, pending trades, and live prices
            combine(
                dao.getActiveTradesFlow(),
                dao.getPendingTradesFlow(),
                BinanceWebSocketService.livePrices
            ) { activeList, pendingList, livePrices ->
                Triple(activeList, pendingList, livePrices)
            }.collectLatest { (activeList, pendingList, livePrices) ->
                evaluateTrades(activeList, pendingList, livePrices)
            }
        }
    }

    private fun evaluateTrades(
        activeTrades: List<AppTradeEntity>,
        pendingTrades: List<AppTradeEntity>,
        livePrices: Map<String, Double>
    ) {
        if (livePrices.isEmpty()) return

        // 1. Evaluate PENDING_BUY (Ambush limit orders)
        pendingTrades.forEach { trade ->
            val symbol = trade.symbol.uppercase()
            val currentPrice = livePrices[symbol] ?: 0.0

            if (currentPrice > 0.0 && trade.entryPrice > 0.0) {
                // If price dropped to or below the ambush entry level
                if (currentPrice <= trade.entryPrice) {
                    val triggerKey = "PENDING_${trade.id}_${trade.entryPrice}"
                    if (!triggeredTradeIds.contains(triggerKey)) {
                        triggeredTradeIds.add(triggerKey)
                        sendTradeAlertNotification(
                            symbol = trade.symbol,
                            notificationId = (2000 + trade.id).toInt(),
                            title = "🚨 ${trade.symbol} Pusu Seviyesine İndi!",
                            message = "Anlık Fiyat: $${formatPrice(currentPrice)} | Pusu: $${formatPrice(trade.entryPrice)}\nMidas'ta limit alışınız gerçekleşmiş olabilir! Hemen kontrol edin.",
                            isBuy = true
                        )
                    }
                }
            }
        }

        // 2. Evaluate ACTIVE_OPEN (Profit exit targets)
        activeTrades.forEach { trade ->
            val symbol = trade.symbol.uppercase()
            val currentPrice = livePrices[symbol] ?: 0.0

            if (currentPrice > 0.0 && trade.targetExitPrice > 0.0) {
                // If price reached or exceeded target exit level
                if (currentPrice >= trade.targetExitPrice) {
                    val triggerKey = "ACTIVE_${trade.id}_${trade.targetExitPrice}"
                    if (!triggeredTradeIds.contains(triggerKey)) {
                        triggeredTradeIds.add(triggerKey)
                        sendTradeAlertNotification(
                            symbol = trade.symbol,
                            notificationId = (3000 + trade.id).toInt(),
                            title = "✅ ${trade.symbol} Kâr Hedefine Ulaştı!",
                            message = "Anlık Fiyat: $${formatPrice(currentPrice)} | Hedef: $${formatPrice(trade.targetExitPrice)}\nMidas kârlı limit satışınızı onaylayın ve kasanıza kârı aktarın.",
                            isBuy = false
                        )
                    }
                }
            }
        }
    }

    private fun sendTradeAlertNotification(
        symbol: String,
        notificationId: Int,
        title: String,
        message: String,
        isBuy: Boolean
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(Notification.DEFAULT_ALL)

        notificationManager.notify(notificationId, builder.build())

        // Speak trade notification via Text-To-Speech
        val spokenText = if (isBuy) {
            "$symbol pusu seviyesine indi, lütfen Midas'tan alımı kontrol edin."
        } else {
            "$symbol kâr hedefini vurdu, lütfen Midas'tan satışı onaylayın."
        }

        try {
            tts?.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, "trade_alert_$notificationId")
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking text: ${e.message}")
        }
    }

    private fun createForegroundNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Kripto Teknik Analist")
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Silent persistent foreground channel
            val fgChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Kripto Analist Arka Plan Takibi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Uygulama arka plandayken Binance WebSocket bağlantısını canlı tutar."
                setShowBadge(false)
            }

            // 2. High-importance alert channel for trade signals
            val alertChannel = NotificationChannel(
                ALERTS_CHANNEL_ID,
                "Kripto Pusu & Kâr Bildirimleri",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Pusu limit alımları ve kâr satış hedefleri gerçekleştiğinde anlık sesli ve titreşimli bildirim gönderir."
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(fgChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    private fun formatPrice(price: Double): String {
        return String.format(Locale.US, if (price < 1.0) "%.4f" else "%.2f", price)
    }
}

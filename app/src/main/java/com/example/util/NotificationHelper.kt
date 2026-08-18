package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.TradeSignalEntity
import com.example.receiver.TradeActionReceiver
import java.util.Locale

object NotificationHelper {

    const val CHANNEL_ID_SIGNALS = "midas_trade_signals_channel"
    const val CHANNEL_NAME_SIGNALS = "Midas Trade Sinyalleri (Öncelikli)"
    const val NOTIFICATION_ID_SIGNAL_BASE = 8800

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build()

            val signalChannel = NotificationChannel(
                CHANNEL_ID_SIGNALS,
                CHANNEL_NAME_SIGNALS,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Midas anlık alım-satım ve kâr fırsatı acil bildirimleri"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 100, 250, 100, 300)
                setSound(soundUri, audioAttributes)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(signalChannel)
        }
    }

    fun showTradeSignalNotification(context: Context, signal: TradeSignalEntity) {
        createNotificationChannels(context)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Intent to open MainActivity when tapping notification body
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("OPEN_SIGNAL_ID", signal.id)
            putExtra("SYMBOL", signal.symbol)
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            signal.id.toInt().takeIf { it != 0 } ?: (System.currentTimeMillis() % 10000).toInt(),
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 1: Execute Signal (Direct Buy/Sell on Midas)
        val executeIntent = Intent(context, TradeActionReceiver::class.java).apply {
            action = TradeActionReceiver.ACTION_EXECUTE_SIGNAL
            putExtra("SIGNAL_ID", signal.id)
            putExtra("ACTION_TYPE", signal.actionType)
            putExtra("SYMBOL", signal.symbol)
            putExtra("INVEST_AMOUNT", signal.investmentAmount)
            putExtra("ENTRY_PRICE", signal.entryPrice)
            putExtra("TARGET_PRICE", signal.targetExitPrice)
        }
        val executePendingIntent = PendingIntent.getBroadcast(
            context,
            (signal.id.toInt() * 10) + 1,
            executeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 2: Reject / Dismiss Signal
        val dismissIntent = Intent(context, TradeActionReceiver::class.java).apply {
            action = TradeActionReceiver.ACTION_DISMISS_SIGNAL
            putExtra("SIGNAL_ID", signal.id)
            putExtra("SYMBOL", signal.symbol)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            (signal.id.toInt() * 10) + 2,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isSell = signal.actionType == "PROFIT_TAKE"
        val title = if (isSell) {
            "🔥 [MİDAS SATIŞ] ${signal.symbol} KÂR HEDEFİ GELDİ! (+%${String.format(Locale.US, "%.2f", signal.netProfitPercent)})"
        } else if (signal.actionType == "DCA_ADD") {
            "🛡️ [MİDAS KADEME] ${signal.symbol} DCA #${signal.dcaLevel} DİP ALIMI!"
        } else {
            "⚡ [MİDAS ALIM] ${signal.symbol} 5Dk DESTEK SEKME SİNYALİ!"
        }

        val actionBtnText = if (isSell) "💰 ŞİMDİ KÂR AL (MİDAS)" else "🚀 ŞİMDİ AL (MİDAS'A GİT)"

        val contentText = if (isSell) {
            "Fiyat: $${String.format(Locale.US, "%.2f", signal.entryPrice)} | Net Kâr: +$${String.format(Locale.US, "%.2f", signal.guaranteedNetProfit)} (%${String.format(Locale.US, "%.2f", signal.netProfitPercent)})"
        } else {
            "Fiyat: $${String.format(Locale.US, "%.2f", signal.entryPrice)} | Ayrılan: $${String.format(Locale.US, "%.0f", signal.investmentAmount)} USDT | Hedef: +$${String.format(Locale.US, "%.2f", signal.guaranteedNetProfit)}"
        }

        val bigText = buildString {
            append(contentText)
            if (signal.rationale.isNotBlank()) {
                append("\n\n📊 Analiz: ${signal.rationale}")
            }
            append("\n💡 Bildirime basarak tek dokunuşla Midas'a geçip emri girebilirsiniz!")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SIGNALS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(mainPendingIntent)
            .addAction(R.mipmap.ic_launcher, actionBtnText, executePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "❌ Reddet", dismissPendingIntent)
            .build()

        val notificationId = NOTIFICATION_ID_SIGNAL_BASE + (signal.id.toInt() % 100)
        notificationManager.notify(notificationId, notification)
    }

    fun cancelSignalNotification(context: Context, signalId: Long) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = NOTIFICATION_ID_SIGNAL_BASE + (signalId.toInt() % 100)
        notificationManager.cancel(notificationId)
    }
}

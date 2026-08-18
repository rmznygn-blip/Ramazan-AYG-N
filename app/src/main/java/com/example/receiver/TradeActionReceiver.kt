package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.example.repository.CryptoTraderRepository
import com.example.service.CryptoAccessibilityService
import com.example.service.FloatingOverlayService
import com.example.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TradeActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_EXECUTE_SIGNAL = "com.example.action.EXECUTE_TRADE_SIGNAL"
        const val ACTION_DISMISS_SIGNAL = "com.example.action.DISMISS_TRADE_SIGNAL"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val signalId = intent.getLongExtra("SIGNAL_ID", -1L)
        val symbol = intent.getStringExtra("SYMBOL") ?: ""
        val actionType = intent.getStringExtra("ACTION_TYPE") ?: "BUY"
        val investAmount = intent.getDoubleExtra("INVEST_AMOUNT", 10.0)
        val entryPrice = intent.getDoubleExtra("ENTRY_PRICE", 0.0)

        val repository = CryptoTraderRepository(context)

        when (intent.action) {
            ACTION_EXECUTE_SIGNAL -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val pending = repository.tradeDao.getActivePendingSignalOnce()
                    if (pending != null && pending.id == signalId) {
                        repository.confirmSignal(pending)
                    } else if (signalId != -1L) {
                        val sig = repository.tradeDao.getSignalById(signalId)
                        if (sig != null) repository.confirmSignal(sig)
                    }
                }

                // 1. Launch Midas App immediately
                com.example.util.AppLauncherHelper.launchMidasApp(context)

                // 2. Trigger Accessibility Assistant for Midas Order
                CryptoAccessibilityService.executeMidasAssistOrder(actionType, investAmount, entryPrice)

                // 3. Ensure Floating Overlay Service is visible
                try {
                    val overlayIntent = Intent(context, FloatingOverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(overlayIntent)
                    } else {
                        context.startService(overlayIntent)
                    }
                } catch (e: Exception) {
                    // Ignore if background service start restricted
                }

                // 4. Cancel notification
                if (signalId != -1L) {
                    NotificationHelper.cancelSignalNotification(context, signalId)
                }

                val msg = if (actionType == "PROFIT_TAKE") {
                    "💰 $symbol KÂR SATIŞI ONAYLANDI! Midas Açılıyor..."
                } else {
                    "🚀 $symbol ALIM EMRİ ONAYLANDI! Midas Açılıyor..."
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }

            ACTION_DISMISS_SIGNAL -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val pending = repository.tradeDao.getActivePendingSignalOnce()
                    if (pending != null && pending.id == signalId) {
                        repository.rejectSignal(pending)
                    } else if (signalId != -1L) {
                        val sig = repository.tradeDao.getSignalById(signalId)
                        if (sig != null) repository.rejectSignal(sig)
                    }
                }

                if (signalId != -1L) {
                    NotificationHelper.cancelSignalNotification(context, signalId)
                }
                Toast.makeText(context, "$symbol emri iptal edildi.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

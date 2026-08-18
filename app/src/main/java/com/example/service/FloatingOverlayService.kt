package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.data.local.DcaPositionEntity
import com.example.data.local.TradeSignalEntity
import com.example.model.BinanceOracleData
import com.example.model.CryptoAsset
import com.example.model.MidasAccountState
import com.example.model.OverlayConfig
import com.example.repository.CryptoOverlayRepository
import com.example.repository.CryptoTraderRepository
import com.example.ui.theme.*
import java.util.Locale

class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayComposeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private lateinit var traderRepository: CryptoTraderRepository

    private val serviceLifecycleOwner = OverlayServiceLifecycleOwner()

    companion object {
        const val CHANNEL_ID = "crypto_overlay_service_channel"
        const val NOTIFICATION_ID = 1010
        const val ACTION_START = "ACTION_START_OVERLAY"
        const val ACTION_STOP = "ACTION_STOP_OVERLAY"
    }

    override fun onCreate() {
        super.onCreate()
        traderRepository = CryptoTraderRepository.getInstance(this)

        serviceLifecycleOwner.performRestore(null)
        serviceLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        serviceLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        serviceLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        CryptoOverlayRepository.updateOverlayRunning(true)

        initOverlayWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun updateOverlayPosition(dx: Float, dy: Float) {
        val params = layoutParams ?: return
        val view = overlayComposeView ?: return
        params.x = (params.x + dx.toInt()).coerceAtLeast(0)
        params.y = (params.y + dy.toInt()).coerceAtLeast(0)
        try {
            windowManager?.updateViewLayout(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initOverlayWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 140
        }

        overlayComposeView = ComposeView(this).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(serviceLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
            setViewTreeViewModelStoreOwner(serviceLifecycleOwner)

            setContent {
                val assets by CryptoOverlayRepository.cryptoAssets.collectAsState()
                val config by CryptoOverlayRepository.overlayConfig.collectAsState()
                val isAccessActive by CryptoOverlayRepository.isAccessibilityConnected.collectAsState()
                val midasAccount by traderRepository.midasAccountState.collectAsState()
                val oracleMap by traderRepository.binanceOracleMap.collectAsState()

                val pendingSignal by traderRepository.pendingSignal.collectAsState()
                val openPositions by traderRepository.openPositions.collectAsState(initial = emptyList())

                FloatingOverlayContent(
                    assets = assets,
                    config = config,
                    isAccessibilityActive = isAccessActive,
                    midasAccount = midasAccount,
                    oracleMap = oracleMap,
                    pendingSignal = pendingSignal,
                    openPositions = openPositions,
                    onConfirmSignal = { signal ->
                        traderRepository.confirmSignal(signal)
                    },
                    onRejectSignal = { signal ->
                        traderRepository.rejectSignal(signal)
                    },
                    onDragDelta = { dx, dy ->
                        updateOverlayPosition(dx, dy)
                    },
                    onClose = {
                        stopSelf()
                    }
                )
            }
        }

        try {
            windowManager?.addView(overlayComposeView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Midas Trader Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Midas Kripto üzerinde canlı nakit analizi, Binance Oracle verisi ve komisyon garantili emirler sunar."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val appIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            appIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, FloatingOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Midas Kripto Smart HUD")
            .setContentText("Binance Oracle & Nakit Yönetimli Trader Aktif")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kapat", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        CryptoOverlayRepository.updateOverlayRunning(false)
        serviceLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        serviceLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        serviceLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        if (overlayComposeView != null) {
            try {
                windowManager?.removeView(overlayComposeView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayComposeView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * Custom LifecycleOwner for hosting ComposeView inside an Android Service
 */
private class OverlayServiceLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun performRestore(savedState: Bundle?) {
        savedStateRegistryController.performRestore(savedState)
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}

/**
 * Main Floating Overlay UI Composable with Midas Cash & Binance Oracle Engine
 */
@Composable
fun FloatingOverlayContent(
    assets: List<CryptoAsset>,
    config: OverlayConfig,
    isAccessibilityActive: Boolean,
    midasAccount: MidasAccountState = MidasAccountState(),
    oracleMap: Map<String, BinanceOracleData> = emptyMap(),
    pendingSignal: TradeSignalEntity? = null,
    openPositions: List<DcaPositionEntity> = emptyList(),
    onConfirmSignal: (TradeSignalEntity) -> Unit = {},
    onRejectSignal: (TradeSignalEntity) -> Unit = {},
    onDragDelta: (Float, Float) -> Unit,
    onClose: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(!config.isCompactMode) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Midas Fiyat, 1: Pozisyonlar (DCA), 2: Nakit & Oracle

    val primaryAsset = assets.firstOrNull { it.symbol == midasAccount.currentViewedSymbol } ?: assets.firstOrNull()
    val oracleData = primaryAsset?.let { oracleMap[it.symbol] }

    val surfaceColor = if (config.isOledTrueBlack) {
        Color.Black.copy(alpha = config.opacity)
    } else {
        Color(0xFF0C1017).copy(alpha = config.opacity)
    }

    Box(
        modifier = Modifier
            .wrapContentSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        if (!isExpanded) {
            // MINIMIZED PILL (Compact OLED Badge with Live Signal & Cash Status)
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .border(
                        1.2.dp,
                        if (pendingSignal != null) CyberAmber else CyberEmerald.copy(alpha = 0.7f),
                        RoundedCornerShape(24.dp)
                    )
                    .clickable { isExpanded = true },
                color = surfaceColor,
                shadowElevation = 10.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(
                                if (pendingSignal != null) CyberAmber
                                else if (isAccessibilityActive) CyberEmerald
                                else Color(0xFF5A6E85)
                            )
                    )

                    if (pendingSignal != null) {
                        Text(
                            text = "⚡ MİDAS EMRİ HAZIR: ${pendingSignal.symbol}",
                            color = CyberAmber,
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            text = "MİDAS ${primaryAsset?.symbol ?: "KRİPTO"}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        Text(
                            text = primaryAsset?.priceFormatted ?: "$0.00",
                            color = CyberEmerald,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        Text(
                            text = "Nakit: $${String.format(Locale.US, "%.0f", midasAccount.availableCash)}",
                            color = CyberCyan,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = "Genişlet",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        } else {
            // EXPANDED OLED SMART MIDAS TRADER HUD
            Surface(
                modifier = Modifier
                    .width(285.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        1.5.dp,
                        if (pendingSignal != null) CyberAmber else CyberEmerald.copy(alpha = 0.6f),
                        RoundedCornerShape(16.dp)
                    ),
                color = surfaceColor,
                shadowElevation = 14.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {

                    // 1. TOP HEADER & STATUS
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isAccessibilityActive) CyberEmerald else CyberAmber)
                            )
                            Column {
                                Text(
                                    text = "MİDAS TRADER HUD",
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.8.sp
                                )
                                Text(
                                    text = "Nakit: $${String.format(Locale.US, "%.2f", midasAccount.availableCash)}",
                                    color = CyberCyan,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { isExpanded = false },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ExpandLess,
                                    contentDescription = "Küçült",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Kapat",
                                    tint = CyberCrimson,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // 2. CRITICAL USER APPROVAL BANNER (WHEN EMIR IS GENERATED)
                    if (pendingSignal != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF0F140A),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.2.dp, CyberAmber)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(CyberAmber)
                                        )
                                        Text(
                                            text = when (pendingSignal.actionType) {
                                                "DCA_ADD" -> "MİDAS KADEME EKLE (DCA #${pendingSignal.dcaLevel})"
                                                "PROFIT_TAKE" -> "MİDAS KÂR SATIŞI (KAPAT)"
                                                else -> "MİDAS LİMİT ALIŞ EMRİ"
                                            },
                                            color = CyberAmber,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Text(
                                        text = "Onay Bekliyor",
                                        color = CyberAmber,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "${pendingSignal.symbol} @ $${String.format(Locale.US, "%.2f", pendingSignal.entryPrice)}",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "Ayrılan Nakit: $${String.format(Locale.US, "%.2f", pendingSignal.investmentAmount)} (Kom: $${String.format(Locale.US, "%.3f", pendingSignal.totalFeeAmount)})",
                                            color = TextTertiary,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "+$${String.format(Locale.US, "%.2f", pendingSignal.guaranteedNetProfit)} NET",
                                            color = CyberEmerald,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "+${String.format(Locale.US, "%.2f", pendingSignal.netProfitPercent)}% Net Kâr",
                                            color = CyberEmerald,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // CONFIRM / REJECT BUTTONS
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Button(
                                        onClick = { onConfirmSignal(pendingSignal) },
                                        modifier = Modifier.weight(1.3f).height(34.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = CyberEmerald,
                                            contentColor = Color.Black
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(
                                            text = "✓ EMRİ ONAYLA",
                                            fontWeight = FontWeight.Black,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = { onRejectSignal(pendingSignal) },
                                        modifier = Modifier.weight(0.7f).height(34.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = CyberCrimson
                                        ),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberCrimson),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(
                                            text = "✕ REDDET",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // 3. TAB CONTROLS (Midas Ekran / Pozisyonlar / Binance Oracle)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF090D13))
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        TabPill(
                            title = "Midas Ekran",
                            selected = selectedTab == 0,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedTab = 0 }
                        )
                        TabPill(
                            title = "DCA (${openPositions.size})",
                            selected = selectedTab == 1,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedTab = 1 }
                        )
                        TabPill(
                            title = "Oracle & Nakit",
                            selected = selectedTab == 2,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedTab = 2 }
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // 4. TAB CONTENTS
                    when (selectedTab) {
                        0 -> {
                            // MIDAS LIVE SCREEN ASSETS
                            if (primaryAsset != null) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFF070A0F),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(0.6.dp, OledCardBorder)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${primaryAsset.symbol} (Midas)",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                text = primaryAsset.priceFormatted,
                                                color = CyberEmerald,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 13.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }

                                        if (config.showSparklines && primaryAsset.sparklinePoints.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            SparklineGraph(
                                                points = primaryAsset.sparklinePoints,
                                                isPositive = primaryAsset.isPositive,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(20.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 90.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                items(assets) { asset ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = asset.symbol,
                                            color = TextPrimary,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold
                                        )

                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                text = asset.priceFormatted,
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                text = asset.changeFormatted,
                                                color = if (asset.isPositive) CyberEmerald else CyberCrimson,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        1 -> {
                            // ACTIVE DCA POSITIONS TAB (ZARARINA SATIŞ YOK)
                            if (openPositions.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(85.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Açık pozisyon yok.\nMidas alımı onaylandığında DCA kademeleri burada izlenir.",
                                        color = TextTertiary,
                                        fontSize = 10.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 120.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(openPositions) { pos ->
                                        Surface(
                                            color = Color(0xFF070A0F),
                                            shape = RoundedCornerShape(6.dp),
                                            border = androidx.compose.foundation.BorderStroke(0.6.dp, OledCardBorder)
                                        ) {
                                            Column(modifier = Modifier.padding(6.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "${pos.symbol} (DCA Kademe ${pos.currentDcaLevel}/${pos.maxDcaLevels})",
                                                        color = CyberCyan,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 11.sp,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                    Text(
                                                        text = "Hedef: $${String.format(Locale.US, "%.2f", pos.targetExitPriceWithProfit)}",
                                                        color = CyberEmerald,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 10.sp,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                }

                                                Spacer(modifier = Modifier.height(2.dp))

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "Maliyet: $${String.format(Locale.US, "%.2f", pos.averageEntryPrice)}",
                                                        color = TextSecondary,
                                                        fontSize = 10.sp,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                    Text(
                                                        text = "Garantili Kâr: +$${String.format(Locale.US, "%.2f", pos.guaranteedNetProfitOnExit)}",
                                                        color = CyberEmerald,
                                                        fontSize = 10.sp,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        2 -> {
                            // BINANCE ORACLE & CASH MANAGEMENT TAB
                            Surface(
                                color = Color(0xFF070A0F),
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(0.6.dp, OledCardBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Midas Kullanılabilir Nakit:",
                                            color = TextSecondary,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "$${String.format(Locale.US, "%.2f", midasAccount.availableCash)}",
                                            color = CyberCyan,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    if (oracleData != null) {
                                        Divider(color = DividerColor, thickness = 0.6.dp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Binance Global Referans:",
                                                color = TextSecondary,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                text = "$${String.format(Locale.US, "%.2f", oracleData.binanceGlobalPrice)}",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Binance Liderlik Farkı:",
                                                color = TextSecondary,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                text = "%+${String.format(Locale.US, "%.2f", oracleData.leadLagSpreadPercent)}",
                                                color = CyberEmerald,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 5. FOOTER
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🛡️ Zararına Satış: Kilitli",
                            color = CyberEmerald,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Midas Komisyon Garantili",
                            color = CyberCyan,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TabPill(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) CyberEmerald.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (selected) CyberEmerald else TextSecondary,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * Minimalist Real-Time Sparkline Vector Canvas
 */
@Composable
fun SparklineGraph(
    points: List<Float>,
    isPositive: Boolean,
    modifier: Modifier = Modifier
) {
    val lineColor = if (isPositive) CyberEmerald else CyberCrimson

    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas

        val minVal = points.minOrNull() ?: 0f
        val maxVal = points.maxOrNull() ?: 100f
        val range = if (maxVal - minVal == 0f) 1f else maxVal - minVal

        val stepX = size.width / (points.size - 1)
        val path = Path()

        points.forEachIndexed { index, value ->
            val normalizedY = size.height - ((value - minVal) / range * size.height)
            val x = index * stepX
            if (index == 0) {
                path.moveTo(x, normalizedY)
            } else {
                path.lineTo(x, normalizedY)
            }
        }

        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 1.8f)
        )

        val lastY = size.height - ((points.last() - minVal) / range * size.height)
        drawCircle(
            color = lineColor,
            radius = 3.2f,
            center = Offset(size.width, lastY)
        )
    }
}

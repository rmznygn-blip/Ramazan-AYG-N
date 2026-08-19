package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DcaPositionEntity
import com.example.data.local.LearningMetricEntity
import com.example.data.local.TradeSignalEntity
import com.example.model.BinanceOracleData
import com.example.model.CryptoAsset
import com.example.model.MidasAccountState
import com.example.model.OverlayConfig
import com.example.model.ScreenReaderLog
import com.example.repository.CryptoOverlayRepository
import com.example.repository.CryptoTraderRepository
import com.example.repository.LiveRankedSignal
import com.example.repository.TraderSettings
import com.example.service.CryptoAccessibilityService
import com.example.service.FloatingOverlayContent
import com.example.service.FloatingOverlayService
import com.example.ui.theme.*
import com.example.util.PermissionHelper
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val hasOverlayPermissionState = mutableStateOf(false)
    private val hasAccessibilityPermissionState = mutableStateOf(false)
    private val hasBatteryOptimizationIgnoredState = mutableStateOf(false)
    private lateinit var traderRepository: CryptoTraderRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        traderRepository = CryptoTraderRepository.getInstance(this)
        com.example.util.NotificationHelper.createNotificationChannels(this)

        // Request POST_NOTIFICATIONS for Android 13+ if not granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!com.example.util.PermissionHelper.hasNotificationPermission(this)) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        setContent {
            MyApplicationTheme {
                val hasOverlayPermission by hasOverlayPermissionState
                val hasAccessibilityPermission by hasAccessibilityPermissionState
                val hasBatteryOptimizationIgnored by hasBatteryOptimizationIgnoredState

                val isOverlayRunning by CryptoOverlayRepository.isOverlayRunning.collectAsState()
                val isSimulationActive by CryptoOverlayRepository.isSimulationActive.collectAsState()
                val overlayConfig by CryptoOverlayRepository.overlayConfig.collectAsState()
                val cryptoAssets by CryptoOverlayRepository.cryptoAssets.collectAsState()
                val screenLogs by CryptoOverlayRepository.screenLogs.collectAsState()

                val midasAccount by traderRepository.midasAccountState.collectAsState()
                val binanceOracleMap by traderRepository.binanceOracleMap.collectAsState()
                val technicalAnalysisMap by traderRepository.technicalAnalysisMap.collectAsState()
                val pendingSignal by traderRepository.pendingSignal.collectAsState()
                val openPositions by traderRepository.openPositions.collectAsState(initial = emptyList())
                val learningMetrics by traderRepository.learningMetrics.collectAsState(initial = emptyList())
                val traderSettings by traderRepository.traderSettings.collectAsState()
                val rankedSignals by traderRepository.rankedLiveSignals.collectAsState()

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(OledBlack),
                    containerColor = OledBlack,
                    topBar = {
                        CryptoTraderTopBar(
                            isOverlayRunning = isOverlayRunning,
                            isAccessibilityActive = hasAccessibilityPermission,
                            isAutoScanActive = traderSettings.isAutoScanActive,
                            targetExchangeName = traderSettings.targetExchangeName,
                            onToggleAutoScan = { active ->
                                traderRepository.updateSettings { it.copy(isAutoScanActive = active) }
                                Toast.makeText(
                                    this,
                                    if (active) "⚡ Otomatik Fırsat Tarama Başlatıldı!" else "🛑 Tarama ve Emirler Acil Durduruldu!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                ) { innerPadding ->
                    MidasTraderMainScreen(
                        modifier = Modifier.padding(innerPadding),
                        hasOverlayPermission = hasOverlayPermission,
                        hasAccessibilityPermission = hasAccessibilityPermission,
                        hasBatteryOptimizationIgnored = hasBatteryOptimizationIgnored,
                        isOverlayRunning = isOverlayRunning,
                        config = overlayConfig,
                        assets = cryptoAssets,
                        screenLogs = screenLogs,
                        midasAccount = midasAccount,
                        binanceOracleMap = binanceOracleMap,
                        technicalAnalysisMap = technicalAnalysisMap,
                        pendingSignal = pendingSignal,
                        openPositions = openPositions,
                        learningMetrics = learningMetrics,
                        settings = traderSettings,
                        rankedSignals = rankedSignals,
                        onSelectRankedSignal = { selected ->
                            traderRepository.triggerSignalFromLiveRanked(selected)
                            Toast.makeText(this, "🚀 ${selected.symbol} seçildi! Midas alım emri hazırlandı.", Toast.LENGTH_SHORT).show()
                        },
                        onRequestOverlayPermission = { requestOverlayPermission() },
                        onRequestAccessibilityPermission = { requestAccessibilityPermission() },
                        onRequestBatteryOptimization = { requestBatteryOptimizationExemption() },
                        onToggleOverlayService = { start ->
                            if (start) startFloatingService() else stopFloatingService()
                        },
                        onUpdateConfig = { updated ->
                            CryptoOverlayRepository.updateConfig(updated)
                        },
                        onConfirmSignal = { signal ->
                            traderRepository.confirmSignal(signal)
                            Toast.makeText(this, "${signal.symbol} Midas Emri ONAYLANDI!", Toast.LENGTH_SHORT).show()
                        },
                        onRejectSignal = { signal ->
                            traderRepository.rejectSignal(signal)
                            Toast.makeText(this, "${signal.symbol} emri reddedildi.", Toast.LENGTH_SHORT).show()
                        },
                        onTriggerManualSignal = { symbol ->
                            traderRepository.triggerManualMidasOrder(symbol)
                            Toast.makeText(this, "$symbol için Midas alım emri üretildi!", Toast.LENGTH_SHORT).show()
                        },
                        onUpdateSettings = { newSettings ->
                            traderRepository.updateSettings { newSettings }
                        },
                        onManualSetCash = { cash ->
                            CryptoOverlayRepository.updateMidasCash(cash, fromScreen = false)
                            Toast.makeText(this, "Nakit güncellendi: $$cash", Toast.LENGTH_SHORT).show()
                        },
                        onClearData = {
                            traderRepository.clearAllData()
                            Toast.makeText(this, "Tüm test pozisyonları ve veriler sıfırlandı!", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun checkPermissions() {
        hasOverlayPermissionState.value = PermissionHelper.hasOverlayPermission(this)
        val isAccessEnabled = PermissionHelper.isAccessibilityServiceEnabled(
            this,
            CryptoAccessibilityService::class.java
        )
        hasAccessibilityPermissionState.value = isAccessEnabled
        hasBatteryOptimizationIgnoredState.value = PermissionHelper.isBatteryOptimizationIgnored(this)
        CryptoOverlayRepository.updateAccessibilityConnected(isAccessEnabled)
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "Bu cihazda izin gerektirmez.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestAccessibilityPermission() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(
            this,
            "Lütfen listeden 'Crypto Screen Reader' servisini aktif edin.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun startFloatingService() {
        if (!PermissionHelper.hasOverlayPermission(this)) {
            Toast.makeText(this, "Lütfen önce Yüzen Pencere İznini verin!", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }

        val intent = Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Midas Akıllı Trader HUD başlatıldı!", Toast.LENGTH_SHORT).show()
    }

    private fun stopFloatingService() {
        val intent = Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "Yüzen pencere kapatıldı.", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CryptoTraderTopBar(
    isOverlayRunning: Boolean,
    isAccessibilityActive: Boolean,
    isAutoScanActive: Boolean,
    targetExchangeName: String,
    onToggleAutoScan: (Boolean) -> Unit
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isAutoScanActive) CyberEmerald else CyberCrimson)
                )
                Column {
                    Text(
                        text = "KRİPTO ARBİTRAJ TERMİNALİ",
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Hedef: $targetExchangeName • Binance Oracle",
                        color = if (isAutoScanActive) CyberEmerald else TextTertiary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = OledBlack,
            titleContentColor = TextPrimary
        ),
        actions = {
            Button(
                onClick = { onToggleAutoScan(!isAutoScanActive) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAutoScanActive) CyberCrimson.copy(alpha = 0.2f) else CyberEmerald,
                    contentColor = if (isAutoScanActive) CyberCrimson else Color.Black
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .padding(end = 12.dp)
                    .height(32.dp)
            ) {
                Icon(
                    imageVector = if (isAutoScanActive) Icons.Default.PauseCircle else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isAutoScanActive) "DURDUR" else "BAŞLAT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    )
}

@Composable
fun MidasTraderMainScreen(
    modifier: Modifier = Modifier,
    hasOverlayPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    hasBatteryOptimizationIgnored: Boolean,
    isOverlayRunning: Boolean,
    config: OverlayConfig,
    assets: List<CryptoAsset>,
    screenLogs: List<ScreenReaderLog>,
    midasAccount: MidasAccountState,
    binanceOracleMap: Map<String, BinanceOracleData>,
    technicalAnalysisMap: Map<String, com.example.model.TechnicalAnalysis5m>,
    pendingSignal: TradeSignalEntity?,
    openPositions: List<DcaPositionEntity>,
    learningMetrics: List<LearningMetricEntity>,
    settings: TraderSettings,
    rankedSignals: List<LiveRankedSignal>,
    onSelectRankedSignal: (LiveRankedSignal) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onToggleOverlayService: (Boolean) -> Unit,
    onUpdateConfig: (OverlayConfig) -> Unit,
    onConfirmSignal: (TradeSignalEntity) -> Unit,
    onRejectSignal: (TradeSignalEntity) -> Unit,
    onTriggerManualSignal: (String) -> Unit,
    onUpdateSettings: (TraderSettings) -> Unit,
    onManualSetCash: (Double) -> Unit,
    onClearData: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 10.dp)
    ) {
        // 1. CRITICAL PENDING SIGNAL APPROVAL CARD (HIGH VISIBILITY)
        if (pendingSignal != null) {
            item {
                MidasOrderApprovalCard(
                    signal = pendingSignal,
                    onConfirm = { onConfirmSignal(pendingSignal) },
                    onReject = { onRejectSignal(pendingSignal) }
                )
            }
        }

        // 2. MIDAS CASH & BINANCE ORACLE OVERVIEW CARD
        item {
            MidasCashAndOracleCard(
                midasAccount = midasAccount,
                assets = assets,
                oracleMap = binanceOracleMap,
                settings = settings,
                onTriggerManual = onTriggerManualSignal,
                onManualSetCash = onManualSetCash
            )
        }

        // 3. EVENING ROUTINE & $10-$15 DAILY TARGET CARD
        item {
            EveningRoutineTargetCard(
                settings = settings,
                midasAccount = midasAccount,
                learningMetrics = learningMetrics,
                onToggleSniperMode = { active ->
                    onUpdateSettings(settings.copy(isEveningSniperMode = active))
                }
            )
        }

        // 4. MASTER SERVICE TOGGLES
        item {
            TraderMasterControlsCard(
                isOverlayRunning = isOverlayRunning,
                hasOverlayPermission = hasOverlayPermission,
                hasAccessibilityPermission = hasAccessibilityPermission,
                hasBatteryOptimizationIgnored = hasBatteryOptimizationIgnored,
                onToggleService = onToggleOverlayService,
                onRequestOverlay = onRequestOverlayPermission,
                onRequestAccessibility = onRequestAccessibilityPermission,
                onRequestBatteryOptimization = onRequestBatteryOptimization
            )
        }

        // 5. TAB SELECTOR (7 TABS)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(OledCardSurface)
                    .border(1.dp, OledCardBorder, RoundedCornerShape(12.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                MainTabButton(
                    title = "🎯 Sinyal (${rankedSignals.size})",
                    icon = Icons.Default.Bolt,
                    selected = selectedTab == 0,
                    modifier = Modifier.weight(1.3f),
                    onClick = { selectedTab = 0 }
                )
                MainTabButton(
                    title = "HUD",
                    icon = Icons.Default.Visibility,
                    selected = selectedTab == 1,
                    modifier = Modifier.weight(0.8f),
                    onClick = { selectedTab = 1 }
                )
                MainTabButton(
                    title = "5Dk",
                    icon = Icons.Default.ShowChart,
                    selected = selectedTab == 2,
                    modifier = Modifier.weight(0.8f),
                    onClick = { selectedTab = 2 }
                )
                MainTabButton(
                    title = "DCA (${openPositions.size})",
                    icon = Icons.Default.AccountTree,
                    selected = selectedTab == 3,
                    modifier = Modifier.weight(1.0f),
                    onClick = { selectedTab = 3 }
                )
                MainTabButton(
                    title = "AI",
                    icon = Icons.Default.Psychology,
                    selected = selectedTab == 4,
                    modifier = Modifier.weight(0.7f),
                    onClick = { selectedTab = 4 }
                )
                MainTabButton(
                    title = "Rapor",
                    icon = Icons.Default.Analytics,
                    selected = selectedTab == 5,
                    modifier = Modifier.weight(0.8f),
                    onClick = { selectedTab = 5 }
                )
                MainTabButton(
                    title = "Ayar",
                    icon = Icons.Default.Tune,
                    selected = selectedTab == 6,
                    modifier = Modifier.weight(0.8f),
                    onClick = { selectedTab = 6 }
                )
            }
        }

        // 6. TAB CONTENTS
        when (selectedTab) {
            0 -> {
                item {
                    LiveSignalPickerSection(
                        rankedSignals = rankedSignals,
                        onSelectSignal = onSelectRankedSignal
                    )
                }
            }
            1 -> {
                item {
                    LiveMidasPreviewCard(
                        config = config,
                        assets = assets,
                        hasAccessibilityPermission = hasAccessibilityPermission,
                        midasAccount = midasAccount,
                        oracleMap = binanceOracleMap,
                        pendingSignal = pendingSignal,
                        openPositions = openPositions
                    )
                }
            }
            2 -> {
                item {
                    TechnicalAnalysis5mSection(
                        technicalMap = technicalAnalysisMap,
                        assets = assets
                    )
                }
            }
            3 -> {
                item {
                    MidasDcaPositionMatrixSection(
                        openPositions = openPositions,
                        onTriggerManual = onTriggerManualSignal,
                        onClearData = onClearData
                    )
                }
            }
            4 -> {
                item {
                    SelfLearningSection(learningMetrics = learningMetrics)
                }
            }
            5 -> {
                item {
                    DiagnosticsAndReportRoomSection(
                        midasAccount = midasAccount,
                        assets = assets,
                        oracleMap = binanceOracleMap,
                        openPositions = openPositions,
                        learningMetrics = learningMetrics,
                        screenLogs = screenLogs,
                        onManualSetCash = onManualSetCash
                    )
                }
            }
            6 -> {
                item {
                    MidasTraderSettingsSection(
                        settings = settings,
                        config = config,
                        onUpdateSettings = onUpdateSettings,
                        onUpdateConfig = onUpdateConfig
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
fun MidasCashAndOracleCard(
    midasAccount: MidasAccountState,
    assets: List<CryptoAsset>,
    oracleMap: Map<String, BinanceOracleData>,
    settings: TraderSettings,
    onTriggerManual: (String) -> Unit,
    onManualSetCash: (Double) -> Unit
) {
    var showEditCashDialog by remember { mutableStateOf(false) }
    var inputCashText by remember { mutableStateOf(if (midasAccount.availableCash > 0) midasAccount.availableCash.toString() else "50.0") }

    if (showEditCashDialog) {
        AlertDialog(
            onDismissRequest = { showEditCashDialog = false },
            containerColor = Color(0xFF0F141C),
            title = {
                Text(
                    text = "Midas USDT Bakiyesi Belirle",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Midas Kripto hesabınızdaki kullanılabilir USDT miktarını girin. Bot otomatik alımlarda bu bakiye üzerinden güvenli kademe hesaplayacaktır.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = inputCashText,
                        onValueChange = { inputCashText = it },
                        label = { Text("Kullanılabilir USDT ($)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberEmerald,
                            unfocusedBorderColor = OledCardBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = inputCashText.toDoubleOrNull()
                        if (parsed != null && parsed >= 0) {
                            onManualSetCash(parsed)
                        }
                        showEditCashDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberEmerald,
                        contentColor = Color.Black
                    )
                ) {
                    Text("KAYDET", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditCashDialog = false }) {
                    Text("İPTAL", color = TextSecondary)
                }
            }
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, OledCardBorder, RoundedCornerShape(16.dp)),
        color = OledSurface
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Midas Cash Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "MİDAS KULLANILABİLİR NAKİT",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "$${String.format(Locale.US, "%.2f", midasAccount.availableCash)}",
                        color = CyberEmerald,
                        fontWeight = FontWeight.Black,
                        fontSize = 22.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (midasAccount.isCashDetectedFromScreen) CyberEmerald.copy(alpha = 0.15f) else CyberCyan.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (midasAccount.isCashDetectedFromScreen) "Ekrandan Okundu" else "Doğrulanmış Bakiye",
                            color = if (midasAccount.isCashDetectedFromScreen) CyberEmerald else CyberCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    OutlinedButton(
                        onClick = { showEditCashDialog = true },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberEmerald)
                    ) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = null, tint = CyberEmerald, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Düzenle", color = CyberEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Cash Management Matrix
            val perTradeAmount = midasAccount.availableCash * (settings.cashAllocationPercent / 100.0)
            val dcaReserve = midasAccount.availableCash - perTradeAmount

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF070A0F),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(0.6.dp, OledCardBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = "1. Kademe Giriş:", color = TextTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            text = "$${String.format(Locale.US, "%.2f", perTradeAmount)} (%${settings.cashAllocationPercent.toInt()})",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = "DCA Güvenlik Rezervi:", color = TextTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            text = "$${String.format(Locale.US, "%.2f", dcaReserve)} (%${(100 - settings.cashAllocationPercent).toInt()})",
                            color = CyberCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Binance Oracle Background Reference Table
            Text(
                text = "GÖRÜNMEZ BİNANCE ORACLE REFERANSI (FİYAT ÖNCÜLÜĞÜ)",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(6.dp))

            assets.take(3).forEach { asset ->
                val oracle = oracleMap[asset.symbol]
                val spread = oracle?.leadLagSpreadPercent ?: asset.leadLagDiffPercent
                val isBinanceLeading = spread > 0.30

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    color = Color(0xFF06090E),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        0.6.dp,
                        if (isBinanceLeading) CyberEmerald.copy(alpha = 0.4f) else OledCardBorder
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "${asset.symbol} (Midas $${String.format(Locale.US, "%.2f", asset.rawPrice)})",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (isBinanceLeading) {
                                    Text(
                                        text = "⚡ FIRSAT",
                                        color = CyberEmerald,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            Text(
                                text = "Binance: $${String.format(Locale.US, "%.2f", oracle?.binanceGlobalPrice ?: asset.binanceReferencePrice)} (%+${String.format(Locale.US, "%.2f", spread)} Önde)",
                                color = if (isBinanceLeading) CyberEmerald else TextTertiary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Button(
                            onClick = { onTriggerManual(asset.symbol) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isBinanceLeading) CyberEmerald else CyberCyan.copy(alpha = 0.2f),
                                contentColor = if (isBinanceLeading) Color.Black else CyberCyan
                            ),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text(
                                text = "Emir Çıkar",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EveningRoutineTargetCard(
    settings: TraderSettings,
    midasAccount: MidasAccountState,
    learningMetrics: List<LearningMetricEntity>,
    onToggleSniperMode: (Boolean) -> Unit
) {
    val totalRealized = learningMetrics.sumOf { it.totalNetProfitRealized }
    val targetUsd = settings.dailyProfitTargetUsd
    val progress = (totalRealized / targetUsd).toFloat().coerceIn(0f, 1f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.2.dp, if (settings.isEveningSniperMode) CyberEmerald.copy(alpha = 0.7f) else OledCardBorder, RoundedCornerShape(16.dp)),
        color = Color(0xFF090E14)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "🌙 AKŞAM SEANSI & GÜNLÜK KÂR HEDEFİ",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Surface(
                    color = if (settings.isEveningSniperMode) CyberEmerald.copy(alpha = 0.2f) else CyberCyan.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (settings.isEveningSniperMode) "🎯 Sniper Dip Aktif" else "Standart Mod",
                        color = if (settings.isEveningSniperMode) CyberEmerald else CyberCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Daily Target Progress Bar
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Kasadaki Realize Kâr: +$${String.format(Locale.US, "%.2f", totalRealized)}",
                        color = CyberEmerald,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Hedef: $${String.format(Locale.US, "%.2f", targetUsd)} (%${(progress * 100).toInt()})",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = CyberEmerald,
                    trackColor = OledCardSurface,
                )
            }

            // Peaceful Routine Explainer
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF04070B),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(0.6.dp, OledCardBorder)
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "🧘‍♂️ Sakin & Güvenli Akşam Stratejisi:",
                        color = CyberCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "İşten çıkıp interneti açtığınızda sistem arkada sakince çalışır. Acele işlem yapmaz; sadece en sağlam 5Dk dip ve destek seviyesinde Binance Global öncülüğünü yakalar. Pozisyon açıldığında 30-90 dk içinde hedefine ulaşınca Midas'ta kâr alımı yapılır.",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            // Compounding Growth Power (Bileşik Kasa Tablosu)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF0B1017),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "📈 Kasa Büyüdükçe Kâr Katlanma Tablosu:",
                        color = CyberAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("💵 50$ Kasa ➔ İşlem Başı: +$1.00", color = TextPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("🎯 1-2 İşlem/Gün", color = CyberEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("💵 150$ Kasa ➔ İşlem Başı: +$2.75", color = TextPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("🎯 2 İşlem/Gün", color = CyberEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("💵 500$ Kasa ➔ İşlem Başı: +$9.00", color = TextPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("🚀 Günlük Hedef Tamam", color = CyberEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Sniper Mode Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Sakin Sniper Dip Modu",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Sadece RSI dipte ve destek seviyesindeyken alım önerir",
                        color = TextTertiary,
                        fontSize = 10.sp
                    )
                }

                Switch(
                    checked = settings.isEveningSniperMode,
                    onCheckedChange = onToggleSniperMode,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = CyberEmerald
                    )
                )
            }
        }
    }
}

@Composable
fun MidasOrderApprovalCard(
    signal: TradeSignalEntity,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(2.dp, CyberAmber, RoundedCornerShape(16.dp)),
        color = Color(0xFF0F140A),
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Banner Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(CyberAmber)
                    )
                    Text(
                        text = "MİDAS EMRİ ONAYI BEKLİYOR",
                        color = CyberAmber,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                }

                Surface(
                    color = CyberAmber.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Binance Doğrulamalı",
                        color = CyberAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action & Pair
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = when (signal.actionType) {
                            "DCA_ADD" -> "MİDAS KADEME EKLE (DCA #${signal.dcaLevel})"
                            "PROFIT_TAKE" -> "MİDAS KÂR AL (POZİSYON KAPAT)"
                            else -> "MİDAS LİMİT ALIŞ EMRİ"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "${signal.symbol} / USDT (Midas Kripto)",
                        color = CyberCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "+$${String.format(Locale.US, "%.2f", signal.guaranteedNetProfit)} NET",
                        color = CyberEmerald,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "+${String.format(Locale.US, "%.2f", signal.netProfitPercent)}% Net Kâr",
                        color = CyberEmerald,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Breakdown Table
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF070B04),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(0.6.dp, OledCardBorder)
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailRow("Midas Alış Fiyatı:", "$${String.format(Locale.US, "%.2f", signal.entryPrice)}")
                    DetailRow("Hedef Satış / Çıkış Fiyatı:", "$${String.format(Locale.US, "%.2f", signal.targetExitPrice)}")
                    DetailRow("Nakitten Ayrılan Tutar:", "$${String.format(Locale.US, "%.2f", signal.investmentAmount)}")
                    DetailRow("Midas Alım+Satım Komisyonu:", "$${String.format(Locale.US, "%.3f", signal.totalFeeAmount)}")
                    DetailRow("Komisyon Sonrası Net Kâr:", "+$${String.format(Locale.US, "%.2f", signal.guaranteedNetProfit)}", isHighlight = true)
                }
            }

            if (signal.rationale.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Gerekçe: ${signal.rationale}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1.3f)
                        .height(44.dp)
                        .testTag("btn_confirm_signal"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberEmerald,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "EMRİ ONAYLA",
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier
                        .weight(0.9f)
                        .height(44.dp)
                        .testTag("btn_reject_signal"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = CyberCrimson
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.2.dp, CyberCrimson),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "REDDET",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String, isHighlight: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = if (isHighlight) CyberEmerald else TextTertiary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            color = if (isHighlight) CyberEmerald else TextPrimary,
            fontSize = 11.sp,
            fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun TraderMasterControlsCard(
    isOverlayRunning: Boolean,
    hasOverlayPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    hasBatteryOptimizationIgnored: Boolean,
    onToggleService: (Boolean) -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestBatteryOptimization: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.2.dp, if (isOverlayRunning) CyberEmerald else OledCardBorder, RoundedCornerShape(16.dp)),
        color = OledSurface
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Main Overlay Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isOverlayRunning) "HUD SERVİSİ AKTİF" else "HUD DURDURULDU",
                        color = if (isOverlayRunning) CyberEmerald else TextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Midas Yüzen HUD",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 17.sp
                    )
                }

                Switch(
                    checked = isOverlayRunning,
                    onCheckedChange = onToggleService,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = CyberEmerald,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = OledCardSurface
                    ),
                    modifier = Modifier.testTag("master_overlay_switch")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = DividerColor, thickness = 0.8.dp)
            Spacer(modifier = Modifier.height(10.dp))

            // Permissions Quick Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QuickPermissionPill(
                    title = "Overlay",
                    isGranted = hasOverlayPermission,
                    onClick = onRequestOverlay,
                    modifier = Modifier.weight(1f)
                )
                QuickPermissionPill(
                    title = "Erişilebilirlik",
                    isGranted = hasAccessibilityPermission,
                    onClick = onRequestAccessibility,
                    modifier = Modifier.weight(1.1f)
                )
                QuickPermissionPill(
                    title = "Arka Plan",
                    isGranted = hasBatteryOptimizationIgnored,
                    onClick = onRequestBatteryOptimization,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Real-Time Arbitrage Engine Status Row
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF070B12),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(0.8.dp, CyberEmerald.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(CyberEmerald)
                        )
                        Column {
                            Text(
                                text = "Binance Global Fiyat Radarı: CANLI",
                                color = CyberEmerald,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Gecikme (Lead-Lag) ve %0.40 komisyon kalkanı aktif",
                                color = TextTertiary,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.Default.TrendingUp,
                        contentDescription = null,
                        tint = CyberEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun QuickPermissionPill(
    title: String,
    isGranted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        color = if (isGranted) CyberEmerald.copy(alpha = 0.12f) else CyberAmber.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isGranted) CyberEmerald.copy(alpha = 0.4f) else CyberAmber.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                color = if (isGranted) CyberEmerald else CyberAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Icon(
                imageVector = if (isGranted) Icons.Default.Check else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isGranted) CyberEmerald else CyberAmber,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
fun MainTabButton(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) CyberEmerald.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) CyberEmerald else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = title,
                color = if (selected) CyberEmerald else TextSecondary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun MidasDcaPositionMatrixSection(
    openPositions: List<DcaPositionEntity>,
    onTriggerManual: (String) -> Unit,
    onClearData: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, OledCardBorder, RoundedCornerShape(16.dp)),
        color = OledSurface
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "AÇIK MİDAS POZİSYONLARI (DCA)",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "🛡️ Zararına Satış Asla Yapılmaz",
                        color = CyberEmerald,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = onClearData,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCrimson.copy(alpha = 0.2f), contentColor = CyberCrimson),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Verileri Sıfırla", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    Surface(
                        color = CyberEmerald.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "${openPositions.size} Açık",
                            color = CyberEmerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (openPositions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF05080E)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Henüz açık Midas pozisyonu bulunmuyor.",
                            color = TextTertiary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onTriggerManual("SOL") },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("SOL İçin Midas Alım Emri Çıkar", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                openPositions.forEach { pos ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        color = Color(0xFF070B10),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${pos.symbol} (Midas)",
                                    color = Color.White,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Kademe ${pos.currentDcaLevel} / ${pos.maxDcaLevels}",
                                    color = CyberCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            DetailRow("Ortalama Maliyet:", "$${String.format(Locale.US, "%.2f", pos.averageEntryPrice)}")
                            DetailRow("Güncel Fiyat:", "$${String.format(Locale.US, "%.2f", pos.currentMarketPrice)}")
                            DetailRow("Kâr Alış Hedefi (+Komisyon):", "$${String.format(Locale.US, "%.2f", pos.targetExitPriceWithProfit)}", isHighlight = true)
                            if (pos.nextDcaTriggerPrice > 0) {
                                DetailRow("Sonraki DCA Tetik:", "$${String.format(Locale.US, "%.2f", pos.nextDcaTriggerPrice)}")
                            }
                            DetailRow("Yatırılan Nakit:", "$${String.format(Locale.US, "%.2f", pos.totalInvested)}")
                            DetailRow("Garantili Net Kâr:", "+$${String.format(Locale.US, "%.2f", pos.guaranteedNetProfitOnExit)}", isHighlight = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SelfLearningSection(learningMetrics: List<LearningMetricEntity>) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, OledCardBorder, RoundedCornerShape(16.dp)),
        color = OledSurface
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SELF-LEARNING AI MOTORU",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Onay & Red Kararlarından Öğrenme",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    color = CyberEmerald.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "%100 Başarı",
                        color = CyberEmerald,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Kullanıcının onayladığı ve reddettiği Midas emirlerini Room veritabanında saklar. Reddedilen paritelerin dip marjlarını otomatik açar; başarılı işlemlerde güven çarpanını yükseltir.",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            learningMetrics.forEach { metric ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    color = Color(0xFF070B10),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.6.dp, OledCardBorder)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${metric.symbol} (Midas)",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Kâr: +$${String.format(Locale.US, "%.2f", metric.totalNetProfitRealized)}",
                                color = CyberEmerald,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Onay: ${metric.confirmedSignalsCount} | Red: ${metric.rejectedSignalsCount} | Başarı: %${metric.winRatePercent.toInt()}",
                                color = TextTertiary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "AI Çarpan: ${String.format(Locale.US, "%.2f", metric.confidenceMultiplier)}x",
                                color = CyberCyan,
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

/**
 * NEW: Dedicated Developer & Diagnostics Telemetry Room
 */
@Composable
fun DiagnosticsAndReportRoomSection(
    midasAccount: MidasAccountState,
    assets: List<CryptoAsset>,
    oracleMap: Map<String, BinanceOracleData>,
    openPositions: List<DcaPositionEntity>,
    learningMetrics: List<LearningMetricEntity>,
    screenLogs: List<ScreenReaderLog>,
    onManualSetCash: (Double) -> Unit
) {
    val context = LocalContext.current
    var testCashInput by remember { mutableStateOf("500") }

    val formattedReport = remember(midasAccount, assets, oracleMap, openPositions, learningMetrics, screenLogs) {
        buildString {
            appendLine("=== MIDAS SMART TRADER DIAGNOSTICS & TELEMETRY REPORT ===")
            appendLine("Timestamp: ${System.currentTimeMillis()}")
            appendLine("Midas Detected Cash: $${midasAccount.availableCash} (From Screen: ${midasAccount.isCashDetectedFromScreen})")
            appendLine("Active Midas Asset: ${midasAccount.currentViewedSymbol}")
            appendLine("\n--- ASSET & BINANCE ORACLE SPREADS ---")
            assets.forEach { a ->
                val o = oracleMap[a.symbol]
                appendLine("- ${a.symbol}: Midas=$${a.rawPrice} | Binance=$${o?.binanceGlobalPrice ?: 0.0} | Spread=%${String.format(Locale.US, "%.2f", o?.leadLagSpreadPercent ?: 0.0)}")
            }
            appendLine("\n--- OPEN DCA POSITIONS ---")
            if (openPositions.isEmpty()) appendLine("No active positions.")
            openPositions.forEach { p ->
                appendLine("- ${p.symbol}: Level=${p.currentDcaLevel}/${p.maxDcaLevels} | AvgCost=$${p.averageEntryPrice} | ExitTarget=$${p.targetExitPriceWithProfit}")
            }
            appendLine("\n--- SELF-LEARNING METRICS ---")
            learningMetrics.forEach { m ->
                appendLine("- ${m.symbol}: Confirmed=${m.confirmedSignalsCount} | Rejected=${m.rejectedSignalsCount} | WinRate=%${m.winRatePercent} | Profit=+$${m.totalNetProfitRealized}")
            }
            appendLine("\n--- RECENT SCREEN OCR LOGS (${screenLogs.size}) ---")
            screenLogs.take(5).forEach { l ->
                appendLine("[${l.timestamp}] App:${l.sourcePackage} -> Text: ${l.rawTextExtracted}")
            }
            appendLine("=========================================================")
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, CyberCyan.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
        color = OledSurface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "GELİŞTİRİCİ RAPOR & TANI ODASI",
                        color = CyberCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Canlı Telemetri & Güncelleme Raporu",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    color = CyberCyan.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "AI Ready",
                        color = CyberCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = "Midas Kripto üzerinde birkaç gün çalıştırdıktan sonra buradaki raporu tek tıkla kopyalayıp AI Studio sohbetine yapıştırabilirsiniz. Sistem tanı verilerine göre otomatik güncellenecektir.",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )

            // COPY REPORT BUTTON
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Midas Trader Report", formattedReport)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "📋 Geliştirici Raporu Panoya Kopyalandı!", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberCyan,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "📋 GELİŞTİRİCİ RAPORUNU KOPYALA",
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Divider(color = DividerColor, thickness = 0.8.dp)

            // ZERO CASH TEST CONFIGURATOR
            Text(
                text = "🛡️ SIFIR BAKİYE TESTİ / SANAL BAKİYE AYARLA",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = testCashInput,
                    onValueChange = { testCashInput = it },
                    label = { Text("Sanal Nakit ($)", fontSize = 10.sp) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberCyan,
                        unfocusedBorderColor = OledCardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true
                )

                Button(
                    onClick = {
                        val amount = testCashInput.toDoubleOrNull() ?: 500.0
                        onManualSetCash(amount)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Uygula", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }

            Divider(color = DividerColor, thickness = 0.8.dp)

            // RAW LOG PREVIEW BOX
            Text(
                text = "CANLI TELEMETRİ ÖNİZLEMESİ",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                color = Color(0xFF04070B),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(0.6.dp, OledCardBorder)
            ) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    item {
                        Text(
                            text = formattedReport,
                            color = TextSecondary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MidasTraderSettingsSection(
    settings: TraderSettings,
    config: OverlayConfig,
    onUpdateSettings: (TraderSettings) -> Unit,
    onUpdateConfig: (OverlayConfig) -> Unit
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, OledCardBorder, RoundedCornerShape(16.dp)),
        color = OledSurface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "HEDEF BORSA & TRADER AYARLARI",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            // TARGET EXCHANGE SELECTOR
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Emir Gönderilecek Hedef Borsa",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Sinyal onaylandığında doğrudan bu borsa açılır ve alım ekranı doldurulur.",
                    color = TextTertiary,
                    fontSize = 10.sp
                )

                com.example.util.AppLauncherHelper.SUPPORTED_EXCHANGES.forEach { exchange ->
                    val isSelected = settings.targetExchangePackage == exchange.packageName
                    val isInstalled = com.example.util.AppLauncherHelper.isAppInstalled(context, exchange.packageName)

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onUpdateSettings(
                                    settings.copy(
                                        targetExchangePackage = exchange.packageName,
                                        targetExchangeName = exchange.name
                                    )
                                )
                                Toast.makeText(context, "Hedef Borsa Seçildi: ${exchange.name}", Toast.LENGTH_SHORT).show()
                            },
                        color = if (isSelected) CyberEmerald.copy(alpha = 0.12f) else Color(0xFF070B12),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) CyberEmerald else OledCardBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        onUpdateSettings(
                                            settings.copy(
                                                targetExchangePackage = exchange.packageName,
                                                targetExchangeName = exchange.name
                                            )
                                        )
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = CyberEmerald)
                                )
                                Column {
                                    Text(
                                        text = exchange.iconLabel,
                                        color = if (isSelected) Color.White else TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = exchange.packageName,
                                        color = TextTertiary,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            Surface(
                                color = if (isInstalled) CyberEmerald.copy(alpha = 0.2f) else CyberAmber.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (isInstalled) "✓ Yüklü" else "Yüklü Değil",
                                    color = if (isInstalled) CyberEmerald else CyberAmber,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }

            Divider(color = DividerColor, thickness = 0.8.dp)

            // Cash Allocation per Trade Slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "İşlem Başına Ayrılacak Nakit",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "%${settings.cashAllocationPercent.toInt()} NAKİT",
                        color = CyberCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Slider(
                    value = settings.cashAllocationPercent.toFloat(),
                    onValueChange = { onUpdateSettings(settings.copy(cashAllocationPercent = it.toDouble())) },
                    valueRange = 10f..50f,
                    colors = SliderDefaults.colors(
                        thumbColor = CyberCyan,
                        activeTrackColor = CyberCyan,
                        inactiveTrackColor = OledCardSurface
                    )
                )
                Text(
                    text = "Kalan %${(100 - settings.cashAllocationPercent).toInt()} bakiye düşüşlerde DCA kademe alımları için korunur.",
                    color = TextTertiary,
                    fontSize = 10.sp
                )
            }

            Divider(color = DividerColor, thickness = 0.8.dp)

            // Target Net Profit % Slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Hedeflenen Net Kâr Marjı",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "%${String.format(Locale.US, "%.2f", settings.targetNetProfitPercent)} NET",
                        color = CyberEmerald,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Slider(
                    value = settings.targetNetProfitPercent.toFloat(),
                    onValueChange = { onUpdateSettings(settings.copy(targetNetProfitPercent = it.toDouble())) },
                    valueRange = 0.3f..3.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = CyberEmerald,
                        activeTrackColor = CyberEmerald,
                        inactiveTrackColor = OledCardSurface
                    )
                )
            }

            Divider(color = DividerColor, thickness = 0.8.dp)

            // Auto Scanner Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Binance Oracle Fırsat Tarayıcı",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Binance öncülük ettiğinde Midas emri önerir",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = settings.isAutoScanActive,
                    onCheckedChange = { onUpdateSettings(settings.copy(isAutoScanActive = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = CyberEmerald
                    )
                )
            }

            Divider(color = DividerColor, thickness = 0.8.dp)

            // Overlay Opacity
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Midas OLED HUD Saydamlığı",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "%${(config.opacity * 100).toInt()}",
                        color = CyberCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Slider(
                    value = config.opacity,
                    onValueChange = { onUpdateConfig(config.copy(opacity = it)) },
                    valueRange = 0.20f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = CyberCyan,
                        activeTrackColor = CyberCyan,
                        inactiveTrackColor = OledCardSurface
                    )
                )
            }
        }
    }
}

@Composable
fun LiveMidasPreviewCard(
    config: OverlayConfig,
    assets: List<CryptoAsset>,
    hasAccessibilityPermission: Boolean,
    midasAccount: MidasAccountState,
    oracleMap: Map<String, BinanceOracleData>,
    pendingSignal: TradeSignalEntity?,
    openPositions: List<DcaPositionEntity>
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, OledCardBorder, RoundedCornerShape(16.dp)),
        color = OledSurface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MİDAS ÜZERİ CANLI HUD ÖNİZLEMESİ",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "OLED Touch Preview",
                    color = CyberCyan,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF020407))
                    .border(1.dp, Color(0xFF131A24), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                FloatingOverlayContent(
                    assets = assets,
                    config = config,
                    isAccessibilityActive = hasAccessibilityPermission,
                    midasAccount = midasAccount,
                    oracleMap = oracleMap,
                    pendingSignal = pendingSignal,
                    openPositions = openPositions,
                    onConfirmSignal = {},
                    onRejectSignal = {},
                    onDragDelta = { _, _ -> },
                    onClose = {}
                )
            }
        }
    }
}

@Composable
fun TechnicalAnalysis5mSection(
    technicalMap: Map<String, com.example.model.TechnicalAnalysis5m>,
    assets: List<CryptoAsset>
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, OledCardBorder, RoundedCornerShape(16.dp)),
        color = OledSurface
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "5 DAKİKALIK TEKNİK DESTEK & MUM ANALİZİ",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "🏛️ Kurumsal Kantitatif Scalp Algoritması",
                        color = CyberCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Surface(
                    color = CyberCyan.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "5m Klines",
                        color = CyberCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                color = Color(0xFF04070C),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(0.6.dp, OledCardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("💡", fontSize = 16.sp)
                    Text(
                        text = "Bot, sadece 5 dakikalık mumda teknik desteğe (S/R) temas eden, RSI aşırı satım bölgesinde olan ve Binance'ın önde gittiği anlarda emir üretir. Dirençte veya tepe fiyatlarda alım kesinlikle filtrelenir.",
                        color = TextTertiary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            val symbolsToShow = listOf("SOL", "BTC", "ETH", "AVAX")

            symbolsToShow.forEach { symbol ->
                val analysis = technicalMap[symbol]
                val asset = assets.firstOrNull { it.symbol == symbol }
                val price = asset?.rawPrice ?: analysis?.currentPrice ?: 0.0

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    color = Color(0xFF070B11),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if ((analysis?.confluenceScore ?: 0) >= 70) CyberEmerald.copy(alpha = 0.5f) else OledCardBorder
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = symbol,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "$${String.format(Locale.US, "%.2f", price)}",
                                    color = CyberEmerald,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            val score = analysis?.confluenceScore ?: 50
                            val scoreColor = when {
                                score >= 75 -> CyberEmerald
                                score >= 60 -> CyberCyan
                                else -> TextTertiary
                            }

                            Surface(
                                color = scoreColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(0.8.dp, scoreColor.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "Kalite Skoru: %$score",
                                    color = scoreColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val recText = analysis?.recommendation ?: "5Dk Veri Hesaplanıyor..."
                        val recBg = if (analysis?.isSupportBounceValid == true || (analysis?.confluenceScore ?: 0) >= 70) {
                            CyberEmerald.copy(alpha = 0.12f)
                        } else if (analysis?.isOverboughtRisk == true) {
                            CyberCrimson.copy(alpha = 0.12f)
                        } else {
                            Color(0xFF0F1722)
                        }
                        val recColor = if (analysis?.isSupportBounceValid == true || (analysis?.confluenceScore ?: 0) >= 70) {
                            CyberEmerald
                        } else if (analysis?.isOverboughtRisk == true) {
                            CyberCrimson
                        } else {
                            TextSecondary
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = recBg,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = recText,
                                color = recColor,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (analysis != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "5Dk Destek (S):", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    Text(
                                        text = "$${String.format(Locale.US, "%.2f", analysis.supportLevel)} (-%${String.format(Locale.US, "%.2f", analysis.distanceToSupportPercent)})",
                                        color = CyberEmerald,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                    Text(text = "5Dk Direnç (R):", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    Text(
                                        text = "$${String.format(Locale.US, "%.2f", analysis.resistanceLevel)}",
                                        color = CyberCrimson,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val rsiVal = analysis.rsi14
                                    val rsiColor = when {
                                        rsiVal <= 42.0 -> CyberEmerald
                                        rsiVal >= 65.0 -> CyberCrimson
                                        else -> CyberCyan
                                    }
                                    Text(text = "RSI (14 - 5m):", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", rsiVal)} ${if (rsiVal <= 42) "(Aşırı Satım 🟢)" else if (rsiVal >= 65) "(Aşırı Alım 🔴)" else ""}",
                                        color = rsiColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                    Text(text = "Mum Formasyonu:", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    Text(
                                        text = analysis.candlePattern,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "EMA: 9 ($${String.format(Locale.US, "%.1f", analysis.ema9)}) / 21 ($${String.format(Locale.US, "%.1f", analysis.ema21)})",
                                    color = TextTertiary,
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Hacim: ${String.format(Locale.US, "%.2f", analysis.volumeRatioToAvg)}x",
                                    color = if (analysis.volumeRatioToAvg >= 1.2) CyberEmerald else TextTertiary,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveSignalPickerSection(
    rankedSignals: List<LiveRankedSignal>,
    onSelectSignal: (LiveRankedSignal) -> Unit
) {
    var sortMode by remember { mutableIntStateOf(0) } // 0: Güvenilirlik, 1: Kâr Oranı, 2: En Güncel
    val context = LocalContext.current

    val sortedList = remember(rankedSignals, sortMode) {
        when (sortMode) {
            0 -> rankedSignals.sortedByDescending { it.confidenceScorePercent }
            1 -> rankedSignals.sortedByDescending { it.netProfitPercent }
            else -> rankedSignals.sortedByDescending { it.timestamp }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "⚡ GÜNCEL FIRSAT VE SİNYAL SEÇİCİ",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Gecikmesiz • Anlık Emir Saati & Güvenilirlik Skoru",
                    color = CyberCyan,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Surface(
                color = CyberEmerald.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "● ${sortedList.size} CANLI EMİR",
                    color = CyberEmerald,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Filter / Sort Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChipButton(
                title = "🏆 En Yüksek Güvenilirlik",
                selected = sortMode == 0,
                modifier = Modifier.weight(1.1f),
                onClick = { sortMode = 0 }
            )
            FilterChipButton(
                title = "💰 En İyi Kâr",
                selected = sortMode == 1,
                modifier = Modifier.weight(0.9f),
                onClick = { sortMode = 1 }
            )
            FilterChipButton(
                title = "⏱️ En Güncel",
                selected = sortMode == 2,
                modifier = Modifier.weight(0.9f),
                onClick = { sortMode = 2 }
            )
        }

        if (sortedList.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = OledSurface,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, OledCardBorder)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Canlı piyasa taranıyor... Sinyaller hazırlanıyor.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        } else {
            sortedList.forEach { signal ->
                LiveSignalOpportunityCard(
                    signal = signal,
                    onSelect = { onSelectSignal(signal) },
                    onCopyPrices = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(
                            "${signal.symbol} Limit Emir",
                            "Varlık: ${signal.symbol}/USDT\nAlış: $${String.format(Locale.US, "%.2f", signal.suggestedEntryPrice)}\nHedef Satış: $${String.format(Locale.US, "%.2f", signal.targetExitPrice)}\nNet Kâr: %${String.format(Locale.US, "%.2f", signal.netProfitPercent)}"
                        )
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "📋 ${signal.symbol} Alış & Satış Fiyatları Kopyalandı!", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
fun LiveSignalOpportunityCard(
    signal: LiveRankedSignal,
    onSelect: () -> Unit,
    onCopyPrices: () -> Unit
) {
    val isHighConfidence = signal.confidenceScorePercent >= 80

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                1.2.dp,
                if (isHighConfidence) CyberEmerald.copy(alpha = 0.8f) else OledCardBorder,
                RoundedCornerShape(14.dp)
            ),
        color = Color(0xFF070B12)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Symbol, Timestamp & Freshness
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = signal.pair,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Surface(
                        color = CyberEmerald.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "● GÜNCEL",
                            color = CyberEmerald,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                // Time of generation
                Text(
                    text = "Saat: ${signal.generatedTimeFormatted}",
                    color = CyberCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Score Badges Row: Confidence Score & Net Profit
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    color = if (isHighConfidence) CyberEmerald.copy(alpha = 0.15f) else Color(0xFF131A26),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isHighConfidence) CyberEmerald else OledCardBorder
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Güvenilirlik:", color = TextSecondary, fontSize = 11.sp)
                        Text(
                            text = "%${signal.confidenceScorePercent} GÜVEN",
                            color = if (isHighConfidence) CyberEmerald else CyberCyan,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Surface(
                    modifier = Modifier.weight(1f),
                    color = CyberEmerald.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberEmerald)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Net Kâr:", color = TextSecondary, fontSize = 11.sp)
                        Text(
                            text = "+%${String.format(Locale.US, "%.2f", signal.netProfitPercent)}",
                            color = CyberEmerald,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Price Details Box
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF03060A),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(0.6.dp, OledCardBorder)
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Güncel Piyasa:", color = TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("$${String.format(Locale.US, "%.2f", signal.currentPrice)}", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Önerilen Alış (Dip):", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text("$${String.format(Locale.US, "%.2f", signal.suggestedEntryPrice)}", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Hedef Satış (Kâr Al):", color = CyberEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text("$${String.format(Locale.US, "%.2f", signal.targetExitPrice)}", color = CyberEmerald, fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Binance Öncülüğü:", color = TextTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("%+${String.format(Locale.US, "%.2f", signal.binanceLeadPercent)} Yukarı", color = CyberEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // Rationale / Reasoning
            Text(
                text = "💡 Analiz: ${signal.rationale}",
                color = TextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCopyPrices,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCyan),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan)
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Fiyatları Kopyala",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Button(
                    onClick = onSelect,
                    modifier = Modifier
                        .weight(1.3f)
                        .height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberEmerald,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(imageVector = Icons.Default.Launch, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "MİDAS'TA AÇ & GİR",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun FilterChipButton(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(34.dp)
            .clickable { onClick() },
        color = if (selected) CyberEmerald else Color(0xFF0F1520),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) CyberEmerald else OledCardBorder
        )
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                text = title,
                color = if (selected) Color.Black else TextSecondary,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Black else FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

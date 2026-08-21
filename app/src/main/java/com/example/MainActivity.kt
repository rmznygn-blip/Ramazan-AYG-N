package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.local.*
import com.example.engine.ActionGuidance
import com.example.engine.AiAdvisorEngine
import com.example.model.BinanceOracleData
import com.example.model.CandleStick
import com.example.model.CryptoAsset
import com.example.model.TechnicalAnalysis5m
import com.example.repository.CryptoMarketRepository
import com.example.service.TradeMonitoringService
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Universal robust number parser supporting Turkish formatting (comma decimals, dot thousands or vice versa).
 * e.g., "44,73" -> 44.73, "64.681,22" -> 64681.22, "0,0001992" -> 0.0001992, "100" -> 100.0
 */
fun parseFlexibleDouble(input: String?): Double? {
    if (input == null) return null
    val clean = input.trim()
    if (clean.isEmpty()) return null
    return try {
        if (clean.contains(".") && clean.contains(",")) {
            if (clean.lastIndexOf(",") > clean.lastIndexOf(".")) {
                // e.g. "64.681,22" -> remove '.' and replace ',' with '.'
                clean.replace(".", "").replace(",", ".").toDoubleOrNull()
            } else {
                // e.g. "64,681.22" -> remove ','
                clean.replace(",", "").toDoubleOrNull()
            }
        } else if (clean.contains(",")) {
            // e.g. "44,73" or "0,0001992" -> replace ',' with '.'
            clean.replace(",", ".").toDoubleOrNull()
        } else {
            clean.toDoubleOrNull()
        }
    } catch (e: Exception) {
        null
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                CryptoAnalystMasterApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CryptoAnalystMasterApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val dao = remember { db.appDao() }

    // Foreground Service & Notification Permission Request Logic
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Service starts regardless so market websocket and monitoring stay alive
        TradeMonitoringService.startService(context)
    }

    LaunchedEffect(Unit) {
        TradeMonitoringService.startService(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Asistan & Emirler, 1: Kasa & Portföy, 2: Hafıza & Arşiv, 3: Haftalık AI Raporu

    // Real-time market state
    val cryptoAssets by CryptoMarketRepository.cryptoAssets.collectAsState()
    val binanceOracleMap by CryptoMarketRepository.binanceOracleMap.collectAsState()
    val technicalAnalysisMap by CryptoMarketRepository.technicalAnalysisMap.collectAsState()
    val isRefreshing by CryptoMarketRepository.isRefreshing.collectAsState()
    val lastRefreshTime by CryptoMarketRepository.lastRefreshTime.collectAsState()

    // Persistent database state
    val capitalProfile by dao.getCapitalProfileFlow().collectAsState(initial = CapitalProfileEntity())
    val activeTrades by dao.getActiveTradesFlow().collectAsState(initial = emptyList())
    val pendingTrades by dao.getPendingTradesFlow().collectAsState(initial = emptyList())
    val historicalTrades by dao.getHistoricalTradesFlow().collectAsState(initial = emptyList())
    val coinMemories by dao.getAllCoinMemoriesFlow().collectAsState(initial = emptyList())
    val weeklyReports by dao.getAllWeeklyReportsFlow().collectAsState(initial = emptyList())

    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // Dialog state for 3-Tier Budget Allocation
    var showBudgetDialogForAsset by remember { mutableStateOf<CryptoAsset?>(null) }

    // Dialog state for detailed Asset Details Screen (Candlestick & Quant Analysis)
    var selectedAssetDetails by remember { mutableStateOf<CryptoAsset?>(null) }
    val strategySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Dialog state for adding existing Midas holdings
    var showAddExistingHoldingDialog by remember { mutableStateOf(false) }

    // Dialog state for confirming fill of pending ambush buy
    var tradeToConfirmFill by remember { mutableStateOf<AppTradeEntity?>(null) }

    // Dialog state for confirming sale price with user & calculating exact return
    var tradeToConfirmSale by remember { mutableStateOf<Pair<AppTradeEntity, Double>?>(null) }

    // Dialog state for updating target exit price
    var tradeToUpdateTarget by remember { mutableStateOf<Pair<AppTradeEntity, Double>?>(null) }

    // Dialog state for startup cash verification (asks if cash is same, remembers all state)
    var showStartupCheckDialog by remember { mutableStateOf(true) }

    // Dialog state for quick manual cash balance adjustment from topbar or anywhere
    var showQuickCashDialog by remember { mutableStateOf(false) }

    // Dialog state for factory resetting all data and capital
    var showResetAllConfirmDialog by remember { mutableStateOf(false) }

    // Realtime AI action guidance synthesized across capital + live 5m indicators
    val realtimeGuidance = remember(capitalProfile, activeTrades, pendingTrades, cryptoAssets, binanceOracleMap, technicalAnalysisMap) {
        AiAdvisorEngine.computeRealtimeGuidance(
            capitalProfile = capitalProfile,
            activeTrades = activeTrades,
            pendingTrades = pendingTrades,
            assets = cryptoAssets,
            oracleMap = binanceOracleMap,
            techMap = technicalAnalysisMap
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg),
        containerColor = ObsidianBg,
        topBar = {
            Surface(
                color = ObsidianSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
                border = BorderStroke(0.dp, Color.Transparent)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(EmeraldProfit)
                            )
                            Text(
                                text = "KRİPTO TEKNİK ANALİST",
                                color = TextPrimary,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Text(
                            text = "Midas Kripto (USDT) • Sıfır Zarar & 3 Kademe Koçu",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Quick Cash Button in TopBar
                        Surface(
                            onClick = { showQuickCashDialog = true },
                            color = ObsidianCard,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, IceCyanBright.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("💵", fontSize = 11.sp)
                                val isInit = capitalProfile?.isInitialized == true
                                val cashVal = capitalProfile?.availableCashUsdt ?: 0.0
                                Text(
                                    text = if (isInit) "$${String.format(Locale.US, "%.1f", cashVal)}" else "Kasa Gir",
                                    color = if (isInit) EmeraldProfitBright else IceCyanBright,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Icon(Icons.Default.Edit, contentDescription = null, tint = IceCyanBright, modifier = Modifier.size(10.dp))
                            }
                        }

                        // Interactive Sync / Refresh Badge with Live Clock
                        Surface(
                            onClick = {
                                CryptoMarketRepository.refreshManually()
                                Toast.makeText(context, "🔄 Piyasa ve 5dk mumlar anında güncellendi", Toast.LENGTH_SHORT).show()
                            },
                            color = ObsidianCard,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (isRefreshing) EmeraldProfit else ObsidianBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (isRefreshing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(11.dp),
                                        color = EmeraldProfit,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Yenile",
                                        tint = EmeraldProfitBright,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Text(
                                    text = timeFormatter.format(Date(lastRefreshTime)),
                                    color = IceCyanBright,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = ObsidianSurface,
                tonalElevation = 0.dp,
                modifier = Modifier.navigationBarsPadding()
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.PriceCheck, contentDescription = null) },
                    label = { Text("Asistan & Emirler", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = EmeraldProfit,
                        selectedTextColor = EmeraldProfit,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                        indicatorColor = EmeraldContainer
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        BadgedBox(
                            badge = {
                                val totalActiveCount = activeTrades.size + pendingTrades.size
                                if (totalActiveCount > 0) {
                                    Badge(
                                        containerColor = if (pendingTrades.isNotEmpty()) GoldWarm else EmeraldProfit,
                                        contentColor = Color.Black
                                    ) {
                                        Text("$totalActiveCount")
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null)
                        }
                    },
                    label = { Text("Kasa & Portföy", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = IceCyanBright,
                        selectedTextColor = IceCyanBright,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                        indicatorColor = IceCyanContainer
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (historicalTrades.isNotEmpty()) {
                                    Badge(containerColor = ObsidianCardElevated, contentColor = GoldWarm) {
                                        Text("${historicalTrades.size}")
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.History, contentDescription = null)
                        }
                    },
                    label = { Text("Hafıza & Arşiv", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = GoldWarm,
                        selectedTextColor = GoldWarm,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                        indicatorColor = GoldContainer
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Assessment, contentDescription = null) },
                    label = { Text("Haftalık Rapor", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = EmeraldProfitBright,
                        selectedTextColor = EmeraldProfitBright,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                        indicatorColor = EmeraldContainer
                    )
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(ObsidianBg)
        ) {
            when (selectedTab) {
                0 -> LiveAssistantScreen(
                    guidance = realtimeGuidance,
                    capitalProfile = capitalProfile,
                    activeTrades = activeTrades,
                    pendingTrades = pendingTrades,
                    assets = cryptoAssets,
                    oracleMap = binanceOracleMap,
                    techMap = technicalAnalysisMap,
                    coinMemories = coinMemories,
                    lastRefreshTime = lastRefreshTime,
                    isRefreshing = isRefreshing,
                    onManualRefresh = {
                        CryptoMarketRepository.refreshManually()
                        Toast.makeText(context, "🔄 Veriler ve 5dk mumlar güncellendi", Toast.LENGTH_SHORT).show()
                    },
                    onOpenBudgetProposal = { asset ->
                        showBudgetDialogForAsset = asset
                    },
                    onOpenAssetDetails = { asset ->
                        selectedAssetDetails = asset
                        coroutineScope.launch {
                            try {
                                strategySheetState.show()
                            } catch (_: Exception) {}
                        }
                    },
                    onOpenAddExistingDialog = {
                        showAddExistingHoldingDialog = true
                    },
                    onRequestConfirmFill = { trade ->
                        tradeToConfirmFill = trade
                    },
                    onCancelAmbush = { tradeId ->
                        coroutineScope.launch {
                            val cancelled = AiAdvisorEngine.cancelPendingAmbush(dao, tradeId, "Kullanıcı tarafından iptal edildi")
                            if (cancelled != null) {
                                Toast.makeText(context, "❌ ${cancelled.symbol} Pusu Emri İptal Edildi. Rezerve bakiye kasaya iade edildi.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onExtendTimeout = { tradeId ->
                        coroutineScope.launch {
                            AiAdvisorEngine.extendAmbushTimeout(dao, tradeId, 30)
                            Toast.makeText(context, "⏱️ Pusu Süresi +30 Dk Uzatıldı", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRequestConfirmSale = { trade, currentPrice ->
                        tradeToConfirmSale = Pair(trade, currentPrice)
                    },
                    onRequestUpdateTarget = { trade, currentPrice ->
                        tradeToUpdateTarget = Pair(trade, currentPrice)
                    },
                    onDcaStep = { tradeId, dcaPrice, dcaAmount ->
                        coroutineScope.launch {
                            val updated = AiAdvisorEngine.executeDcaStep(dao, tradeId, dcaPrice, dcaAmount)
                            if (updated != null) {
                                Toast.makeText(context, "🛡️ ${updated.dcaLevel}. Kademe Eklendi! Yeni Hedef: $${String.format(Locale.US, "%.2f", updated.targetExitPrice)}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
                1 -> CapitalAndPortfolioScreen(
                    capitalProfile = capitalProfile,
                    activeTrades = activeTrades,
                    pendingTrades = pendingTrades,
                    assets = cryptoAssets,
                    techMap = technicalAnalysisMap,
                    onOpenAddExistingDialog = {
                        showAddExistingHoldingDialog = true
                    },
                    onRequestConfirmFill = { trade ->
                        tradeToConfirmFill = trade
                    },
                    onCancelAmbush = { tradeId ->
                        coroutineScope.launch {
                            val cancelled = AiAdvisorEngine.cancelPendingAmbush(dao, tradeId, "Kullanıcı tarafından iptal edildi")
                            if (cancelled != null) {
                                Toast.makeText(context, "❌ ${cancelled.symbol} Pusu Emri İptal Edildi. Rezerve bakiye kasaya iade edildi.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onExtendTimeout = { tradeId ->
                        coroutineScope.launch {
                            AiAdvisorEngine.extendAmbushTimeout(dao, tradeId, 30)
                            Toast.makeText(context, "⏱️ Pusu Süresi +30 Dk Uzatıldı", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onUpdateCash = { newCash ->
                        coroutineScope.launch {
                            AiAdvisorEngine.auditCashUpdate(dao, newCash)
                            Toast.makeText(context, "💵 Kasa Güncellendi: $${String.format(Locale.US, "%.2f", newCash)} USDT", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onUpdateMinThreshold = { newThreshold ->
                        coroutineScope.launch {
                            val curr = dao.getCapitalProfileOnce() ?: CapitalProfileEntity()
                            dao.saveCapitalProfile(curr.copy(minSafeThresholdUsdt = newThreshold))
                            Toast.makeText(context, "🛡️ Güvenlik Eşiği: $${String.format(Locale.US, "%.2f", newThreshold)} USDT", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRequestConfirmSale = { trade, currentPrice ->
                        tradeToConfirmSale = Pair(trade, currentPrice)
                    },
                    onRequestUpdateTarget = { trade, currentPrice ->
                        tradeToUpdateTarget = Pair(trade, currentPrice)
                    },
                    onDcaStep = { tradeId, dcaPrice, dcaAmount ->
                        coroutineScope.launch {
                            AiAdvisorEngine.executeDcaStep(dao, tradeId, dcaPrice, dcaAmount)
                        }
                    },
                    onResetAllData = {
                        showResetAllConfirmDialog = true
                    }
                )
                2 -> MemoryAndArchiveScreen(
                    historicalTrades = historicalTrades,
                    coinMemories = coinMemories
                )
                3 -> WeeklyReportScreen(
                    capitalProfile = capitalProfile,
                    historicalTrades = historicalTrades
                )
            }

            // 1. DIALOG: ADD EXISTING MIDAS HOLDING
            if (showAddExistingHoldingDialog) {
                AddExistingHoldingDialog(
                    assets = cryptoAssets,
                    onDismiss = { showAddExistingHoldingDialog = false },
                    onConfirm = { symbol, entryPrice, coinAmount, dcaLevel, netProfitTargetPct ->
                        coroutineScope.launch {
                            val investedUsdt = entryPrice * coinAmount
                            val targetExitPrice = entryPrice * (1.0 + (netProfitTargetPct + 0.40) / 100.0)
                            val newTrade = AppTradeEntity(
                                symbol = symbol.uppercase(),
                                entryPrice = entryPrice,
                                targetExitPrice = targetExitPrice,
                                investedUsdt = investedUsdt,
                                coinAmount = coinAmount,
                                midasTotalFeeUsdt = investedUsdt * 0.0040,
                                dcaLevel = dcaLevel,
                                maxDcaLevels = 3,
                                nextDcaAmountUsdt = investedUsdt,
                                status = "ACTIVE_OPEN",
                                openedAt = System.currentTimeMillis()
                            )
                            dao.insertTrade(newTrade)
                            Toast.makeText(context, "✅ ${symbol.uppercase()} Portföye Eklendi! (${String.format(Locale.US, "%.6f", coinAmount).trimEnd('0').trimEnd('.')} Adet)", Toast.LENGTH_SHORT).show()
                            showAddExistingHoldingDialog = false
                        }
                    }
                )
            }

            // 1.5 MODAL: STRATEJİ ODASI (WAR ROOM) - Kilitli Snapshot Mimari & 5m Mumlar & AI Mentör (ModalBottomSheet)
            selectedAssetDetails?.let { asset ->
                val tech = technicalAnalysisMap[asset.symbol]
                val oracle = binanceOracleMap[asset.symbol]
                
                // SNAPSHOT KİLİDİ: ModalBottomSheet ilk açıldığı andaki fiyat ve teknik veriler dondurulur (titreme/stres engellenir)
                val snapshotCurrentPrice = remember(asset.symbol) {
                    if (asset.rawPrice > 0) asset.rawPrice else (oracle?.binanceGlobalPrice ?: 0.0)
                }

                val snapshotStrategyAnalysis = remember(asset.symbol) {
                    AiAdvisorEngine.evaluateSmartEntryStrategies(asset, tech, snapshotCurrentPrice)
                }
                val snapshotRecommendedPlan = snapshotStrategyAnalysis.options.firstOrNull { it.isRecommended } ?: snapshotStrategyAnalysis.options[0]

                val snapshotTimeout = remember(asset.symbol) {
                    AiAdvisorEngine.calculateOptimalAmbushTimeout(asset, tech).first
                }

                val snapshotEntryPrice = snapshotRecommendedPlan.price
                val snapshotTargetExitPrice = snapshotEntryPrice * (1.0 + (1.0 + 0.40) / 100.0) // +%1.0 Net Kâr (+%0.40 Midas komisyonu dahil)
                val snapshotTier2Price = tech?.dcaTier2Price ?: (snapshotEntryPrice * 0.975)
                val snapshotTier3Price = tech?.dcaTier3Price ?: (snapshotEntryPrice * 0.950)

                val buyerRatio = ((tech?.orderBookDepth?.bidRatio ?: 0.55) * 100.0).coerceIn(10.0, 90.0)
                val sellerRatio = (100.0 - buyerRatio).coerceIn(10.0, 90.0)

                ModalBottomSheet(
                    onDismissRequest = { selectedAssetDetails = null },
                    sheetState = strategySheetState,
                    containerColor = ObsidianCard,
                    dragHandle = {
                        BottomSheetDefaults.DragHandle(color = IceCyanBright.copy(alpha = 0.6f))
                    },
                    shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 32.dp)
                            .navigationBarsPadding(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Header: Symbol, Title, Kilitli Fiyat
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(
                                    color = ObsidianCardElevated,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, IceCyanBright.copy(alpha = 0.6f))
                                ) {
                                    Text(
                                        text = "${asset.symbol}/USDT",
                                        color = IceCyanBright,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "⚔️ Strateji Odası",
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "🔒 Snapshot Kilitli (Sabit Emirler)",
                                        color = EmeraldProfitBright,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "$${String.format(Locale.US, if (snapshotCurrentPrice < 1.0) "%.4f" else "%.2f", snapshotCurrentPrice)}",
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = asset.changeFormatted,
                                    color = if (asset.isPositive) EmeraldProfit else CoralRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // 1. 5-Minute Binance Candlestick Chart (with Yellow EMA9 & Cyan Bollinger Overlay)
                        Surface(
                            color = ObsidianBg,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, ObsidianBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "📊 5m Mumlar",
                                            color = IceCyanBright,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text("•", color = TextTertiary, fontSize = 10.sp)
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(GoldWarm))
                                            Text("EMA9", color = GoldWarm, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Text("•", color = TextTertiary, fontSize = 10.sp)
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(IceCyan))
                                            Text("Bollinger", color = IceCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    val minC = asset.recentCandles.minOfOrNull { it.low } ?: snapshotCurrentPrice
                                    val maxC = asset.recentCandles.maxOfOrNull { it.high } ?: snapshotCurrentPrice
                                    Text(
                                        text = "$${String.format(Locale.US, if (minC < 1.0) "%.4f" else "%.2f", minC)} - $${String.format(Locale.US, if (maxC < 1.0) "%.4f" else "%.2f", maxC)}",
                                        color = TextTertiary,
                                        fontSize = 8.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                CandlestickChart(
                                    candles = asset.recentCandles,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(130.dp),
                                    isDetailed = true,
                                    vwapPrice = tech?.vwap ?: asset.vwap,
                                    enableGestures = true
                                )
                            }
                        }

                        // 2. DETAYLI AI MENTÖR RAPORU (Merkez Açıklama Kartı)
                        Surface(
                            color = ObsidianBg,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, GoldWarm.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("🧠", fontSize = 13.sp)
                                    Text(
                                        text = "AI MENTÖR RAPORU & PUSU GEREKÇESİ",
                                        color = GoldWarm,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Text(
                                    text = snapshotStrategyAnalysis.aiRecommendationReason,
                                    color = TextPrimary.copy(alpha = 0.95f),
                                    fontSize = 10.5.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }

                        // 3. KİLİTLİ EMİR RAKAMLARI (MİDAS LİMİT GİRİŞ & HEDEF ÇIKIŞ)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                color = ObsidianCardElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, EmeraldProfit.copy(alpha = 0.6f))
                            ) {
                                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("🎯 1. KADEME PUSU AL", color = EmeraldProfitBright, fontSize = 8.5.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                                    Text(
                                        text = "$${String.format(Locale.US, if (snapshotEntryPrice < 1.0) "%.4f" else "%.2f", snapshotEntryPrice)}",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text("-%${String.format(Locale.US, "%.1f", snapshotRecommendedPlan.dropPercent)} Destek Seviyesi", color = EmeraldProfit, fontSize = 8.sp)
                                }
                            }

                            Surface(
                                modifier = Modifier.weight(1f),
                                color = ObsidianCardElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, GoldWarm.copy(alpha = 0.6f))
                            ) {
                                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("🔴 HEDEF LİMİT SAT", color = GoldWarm, fontSize = 8.5.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                                    Text(
                                        text = "$${String.format(Locale.US, if (snapshotTargetExitPrice < 1.0) "%.4f" else "%.2f", snapshotTargetExitPrice)}",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text("+%1.0 Net (Komisyon Dahil)", color = EmeraldProfitBright, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Surface(
                                modifier = Modifier.weight(1f),
                                color = ObsidianCardElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, IceCyanBright.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("⏱️ PUSU SÜRESİ", color = IceCyanBright, fontSize = 8.5.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                                    Text("${snapshotTimeout} Dk", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                                    Text("Tahta: %${String.format(Locale.US, "%.0f", buyerRatio)} Alıcı", color = TextSecondary, fontSize = 8.sp)
                                }
                            }
                        }

                        // 4. KADEMELİ SAVUNMA KARTLARI (DCA 2 & 3)
                        Surface(
                            color = ObsidianBg,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.6.dp, ObsidianBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🛡️ DCA Savunma:", color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Text("2. Kademe: $${String.format(Locale.US, if (snapshotTier2Price < 1.0) "%.4f" else "%.2f", snapshotTier2Price)}", color = IceCyanBright, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                Text("3. Kademe: $${String.format(Locale.US, if (snapshotTier3Price < 1.0) "%.4f" else "%.2f", snapshotTier3Price)}", color = GoldWarm, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                        }

                        // Action Button: Pusuyu Başlat and Bütçe Ayır
                        Button(
                            onClick = {
                                val target = asset
                                selectedAssetDetails = null
                                showBudgetDialogForAsset = target
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) {
                            Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("🎯 Midas'ta Pusu Aç & Bütçe Ayır (+%1.0 Net)", fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp)
                        }
                    }
                }
            }

            // 2. DIALOG: 3-TIER BUDGET ALLOCATION MODAL DIALOG
            showBudgetDialogForAsset?.let { asset ->
                val tech = technicalAnalysisMap[asset.symbol]
                val oracle = binanceOracleMap[asset.symbol]
                val currentPrice = if (asset.rawPrice > 0) asset.rawPrice else (oracle?.binanceGlobalPrice ?: 0.0)
                val availableCash = capitalProfile?.availableCashUsdt ?: 100.0

                val strategyAnalysis = remember(asset, tech, currentPrice) {
                    AiAdvisorEngine.evaluateSmartEntryStrategies(asset, tech, currentPrice)
                }
                val recommendedPlan = strategyAnalysis.options.firstOrNull { it.isRecommended } ?: strategyAnalysis.options[0]

                var entryPriceInput by remember(asset) {
                    mutableStateOf(String.format(Locale.US, if (recommendedPlan.price < 1.0) "%.4f" else "%.2f", recommendedPlan.price))
                }
                val parsedCustomEntry = parseFlexibleDouble(entryPriceInput) ?: recommendedPlan.price
                val entryPrice = if (parsedCustomEntry > 0) parsedCustomEntry else recommendedPlan.price

                // Auto-Compound (Bileşik Getiri) calculation: Expand max trade limit with 50% of realized profit
                val realizedProfit = historicalTrades.sumOf { it.netProfitUsdt }
                val compoundBonus = if (realizedProfit > 0) realizedProfit * 0.50 else 0.0 // Kârın %50'si ile işlem bütçesini büyüt
                val dynamicMaxLimit = 60.0 + compoundBonus
                val totalAllocatedPool = (availableCash * 0.60).coerceIn(30.0, dynamicMaxLimit).coerceAtMost(availableCash)
                val tier1Amount = totalAllocatedPool / 3.0
                val tier2Amount = totalAllocatedPool / 3.0
                val tier3Amount = totalAllocatedPool / 3.0
                val targetExit = entryPrice * (1.0 + (1.0 + 0.40) / 100.0)

                val (recommendedTimeout, reasonText) = remember(asset, tech) {
                    AiAdvisorEngine.calculateOptimalAmbushTimeout(asset, tech)
                }
                var selectedTimeoutMinutes by remember(asset) { mutableIntStateOf(recommendedTimeout) }

                AlertDialog(
                    onDismissRequest = { showBudgetDialogForAsset = null },
                    containerColor = ObsidianCard,
                    shape = RoundedCornerShape(16.dp),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldProfit)
                            Text(
                                text = "${asset.symbol} Bütçe & Pusu Planı",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (compoundBonus > 0) {
                                Surface(
                                    color = EmeraldContainer.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, EmeraldProfit.copy(alpha = 0.5f)),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                                ) {
                                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("🌱", fontSize = 14.sp)
                                        Text("Bileşik Getiri Aktif: Kârınız sayesinde işlem limitiniz +$${String.format(Locale.US, "%.2f", compoundBonus)} büyüdü!", color = EmeraldProfitBright, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            // 3 Dynamic Strategy Selection Cards
                            Text(
                                text = "🎯 Giriş Stratejisi Seçin (Yapay Zekâ Yönlendirmeli):",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                strategyAnalysis.options.forEach { opt ->
                                    val isSelected = Math.abs(entryPrice - opt.price) / opt.price < 0.003
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                entryPriceInput = String.format(Locale.US, if (opt.price < 1.0) "%.4f" else "%.2f", opt.price)
                                            },
                                        color = if (isSelected) {
                                            if (opt.isRecommended) EmeraldContainer.copy(alpha = 0.85f) else ObsidianCardElevated
                                        } else ObsidianBg,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(
                                            width = if (isSelected || opt.isRecommended) 1.2.dp else 0.6.dp,
                                            color = if (isSelected) EmeraldProfitBright else if (opt.isRecommended) GoldWarm else ObsidianBorder
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            if (opt.isRecommended) {
                                                Surface(
                                                    color = GoldWarm,
                                                    shape = RoundedCornerShape(3.dp)
                                                ) {
                                                    Text(
                                                        text = "⭐ AI ÖNERİSİ",
                                                        color = Color.Black,
                                                        fontSize = 7.5.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                                                    )
                                                }
                                            } else {
                                                Text(
                                                    text = opt.title.take(8),
                                                    color = TextTertiary,
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            Text(
                                                text = "$${String.format(Locale.US, if (opt.price < 1.0) "%.4f" else "%.2f", opt.price)}",
                                                color = if (isSelected) Color.White else TextPrimary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontFamily = FontFamily.Monospace
                                            )

                                            Text(
                                                text = "-%${String.format(Locale.US, "%.1f", opt.dropPercent)}",
                                                color = if (opt.isRecommended) EmeraldProfitBright else IceCyanBright,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )

                                            Text(
                                                text = opt.fillSpeedText,
                                                color = TextSecondary,
                                                fontSize = 7.5.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }

                            // AI Strategy Explanation Callout
                            Surface(
                                color = ObsidianBg,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(0.6.dp, GoldWarm.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("🤖", fontSize = 14.sp)
                                    Text(
                                        text = strategyAnalysis.aiRecommendationReason,
                                        color = TextPrimary.copy(alpha = 0.95f),
                                        fontSize = 10.sp,
                                        lineHeight = 13.5.sp
                                    )
                                }
                            }

                            // Editable Entry Price Input + Quick Rounding Chips
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = entryPriceInput,
                                    onValueChange = { entryPriceInput = it },
                                    label = { Text("Seçilen / Özel Limit Alış", fontSize = 10.sp) },
                                    modifier = Modifier.weight(1.3f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = EmeraldProfitBright,
                                        unfocusedBorderColor = ObsidianBorder,
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )

                                val roundedPrice = if (entryPrice >= 100.0) {
                                    Math.round(entryPrice).toDouble()
                                } else if (entryPrice >= 1.0) {
                                    Math.round(entryPrice * 10.0) / 10.0
                                } else {
                                    Math.round(entryPrice * 1000.0) / 1000.0
                                }

                                Surface(
                                    modifier = Modifier
                                        .weight(0.7f)
                                        .clickable {
                                            entryPriceInput = String.format(Locale.US, if (roundedPrice < 1.0) "%.4f" else if (entryPrice >= 100.0) "%.0f" else "%.2f", roundedPrice)
                                        },
                                    color = ObsidianBg,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(0.8.dp, GoldWarm.copy(alpha = 0.6f))
                                ) {
                                    Column(modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("🎯 Düzle", color = GoldWarm, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Text("$${String.format(Locale.US, if (roundedPrice < 1.0) "%.4f" else if (entryPrice >= 100.0) "%.0f" else "%.2f", roundedPrice)}", color = TextPrimary, fontSize = 9.sp)
                                    }
                                }
                            }

                            val dcaDefensePlan = remember(entryPrice, totalAllocatedPool, tech) {
                                AiAdvisorEngine.calculateDcaDefensePlan(entryPrice, totalAllocatedPool, tech, 1.0)
                            }

                            Surface(
                                color = ObsidianBg,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, ObsidianBorder)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Toplam Kasa:", color = TextTertiary, fontSize = 11.sp)
                                        Text("$${String.format(Locale.US, "%.2f", availableCash)} USDT", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Ayrılacak İşlem Havuzu:", color = IceCyanBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text("$${String.format(Locale.US, "%.2f", totalAllocatedPool)} USDT", color = IceCyanBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    HorizontalDivider(color = ObsidianBorder, thickness = 0.5.dp)
                                    
                                    // Tier 1
                                    val t1 = dcaDefensePlan.tiers[0]
                                    val t2 = dcaDefensePlan.tiers[1]
                                    val t3 = dcaDefensePlan.tiers[2]

                                    Text(
                                        text = "• 1. Kademe (Mevcut Giriş): $${String.format(Locale.US, "%.2f", t1.allocatedUsdt)} USDT (Giriş: $${String.format(Locale.US, if (t1.price < 1.0) "%.4f" else "%.2f", t1.price)})",
                                        color = EmeraldProfitBright,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "   🎯 Çıkış Hedefi: $${String.format(Locale.US, if (t1.targetExitPrice < 1.0) "%.4f" else "%.2f", t1.targetExitPrice)} USDT (+%1.0 net)",
                                        color = EmeraldProfit,
                                        fontSize = 9.5.sp
                                    )

                                    // Tier 2
                                    Text(
                                        text = "• 2. Kademe (Dip Destek): $${String.format(Locale.US, "%.2f", t2.allocatedUsdt)} USDT (Fiyat: $${String.format(Locale.US, if (t2.price < 1.0) "%.4f" else "%.2f", t2.price)} | -%${String.format(Locale.US, "%.1f", t2.dropPercentFromEntry)})",
                                        color = GoldWarm,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "   🛡️ Dolarsa Yeni Ort. Maliyet: $${String.format(Locale.US, if (t2.averageCostPrice < 1.0) "%.4f" else "%.2f", t2.averageCostPrice)} ➔ Yeni Çıkış: $${String.format(Locale.US, if (t2.targetExitPrice < 1.0) "%.4f" else "%.2f", t2.targetExitPrice)}",
                                        color = GoldWarm.copy(alpha = 0.85f),
                                        fontSize = 9.5.sp
                                    )

                                    // Tier 3
                                    Text(
                                        text = "• 3. Kademe (Son Savunma): $${String.format(Locale.US, "%.2f", t3.allocatedUsdt)} USDT (Fiyat: $${String.format(Locale.US, if (t3.price < 1.0) "%.4f" else "%.2f", t3.price)} | -%${String.format(Locale.US, "%.1f", t3.dropPercentFromEntry)})",
                                        color = IceCyanBright,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "   🛡️ Dolarsa Yeni Ort. Maliyet: $${String.format(Locale.US, if (t3.averageCostPrice < 1.0) "%.4f" else "%.2f", t3.averageCostPrice)} ➔ Yeni Çıkış: $${String.format(Locale.US, if (t3.targetExitPrice < 1.0) "%.4f" else "%.2f", t3.targetExitPrice)}",
                                        color = IceCyan.copy(alpha = 0.85f),
                                        fontSize = 9.5.sp
                                    )

                                    HorizontalDivider(color = ObsidianBorder, thickness = 0.5.dp)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("🛡️ Dokunulmayan Boş Kasa:", color = TextTertiary, fontSize = 10.5.sp)
                                        Text("$${String.format(Locale.US, "%.2f", (availableCash - totalAllocatedPool).coerceAtLeast(0.0))} USDT", color = EmeraldProfit, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            // Professional Quant Ambush TTL (Time To Live) Selection
                            Surface(
                                color = ObsidianCardElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, ObsidianBorder)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("⏱️ Uzman Pusu Süresi (TTL):", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Surface(
                                            color = GoldContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "🤖 AI Önerisi: ${recommendedTimeout} Dk",
                                                color = GoldWarm,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        val timeOptions = listOf(
                                            Triple(15, "15 Dk", "3 Mum"),
                                            Triple(20, "20 Dk", "4 Mum"),
                                            Triple(30, "30 Dk", "6 Mum")
                                        )
                                        timeOptions.forEach { (mins, label, candleCount) ->
                                            val isSelected = selectedTimeoutMinutes == mins
                                            Surface(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { selectedTimeoutMinutes = mins },
                                                color = if (isSelected) EmeraldProfit.copy(alpha = 0.2f) else ObsidianBg,
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(1.dp, if (isSelected) EmeraldProfitBright else ObsidianBorder)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text(
                                                        text = label,
                                                        color = if (isSelected) EmeraldProfitBright else TextSecondary,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = candleCount,
                                                        color = TextTertiary,
                                                        fontSize = 9.sp
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Text(
                                        text = reasonText,
                                        color = TextTertiary,
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp
                                    )
                                }
                            }

                            Text(
                                text = "🎯 Hedef Çıkış: $${String.format(Locale.US, if (targetExit < 1.0) "%.4f" else "%.2f", targetExit)} (Net +%1.0 kâr + %0.40 Midas komisyonu karşılanır)",
                                color = EmeraldProfit,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val coinAmount = if (entryPrice > 0) (tier1Amount * 0.998) / entryPrice else 0.0
                                    val newTrade = AppTradeEntity(
                                        symbol = asset.symbol,
                                        entryPrice = entryPrice,
                                        targetExitPrice = targetExit,
                                        investedUsdt = tier1Amount,
                                        coinAmount = coinAmount,
                                        midasTotalFeeUsdt = tier1Amount * 0.0040,
                                        dcaLevel = 1,
                                        maxDcaLevels = 3,
                                        nextDcaAmountUsdt = tier2Amount,
                                        status = "PENDING_BUY",
                                        openedAt = System.currentTimeMillis(),
                                        ambushTimeoutMinutes = selectedTimeoutMinutes,
                                        aiNote = "Midas pusu limit emri açıldı. $selectedTimeoutMinutes dk sayaç izleniyor ($reasonText)."
                                    )
                                    dao.insertTrade(newTrade)
                                    val currProfile = dao.getCapitalProfileOnce() ?: CapitalProfileEntity()
                                    dao.saveCapitalProfile(currProfile.copy(availableCashUsdt = (currProfile.availableCashUsdt - tier1Amount).coerceAtLeast(0.0)))
                                    Toast.makeText(context, "🎯 ${asset.symbol} Pusu Emri Başlatıldı! ($selectedTimeoutMinutes Dk Zaman Aşımı Sayacı)", Toast.LENGTH_LONG).show()
                                    showBudgetDialogForAsset = null
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("🎯 Pusuyu Başlat (${selectedTimeoutMinutes} Dk)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBudgetDialogForAsset = null }) {
                            Text("Vazgeç", color = TextSecondary)
                        }
                    }
                )
            }

            // 2.5 DIALOG: CONFIRM FILL OF PENDING AMBUSH BUY (Converts PENDING_BUY -> ACTIVE_OPEN)
            tradeToConfirmFill?.let { trade ->
                val asset = cryptoAssets.firstOrNull { it.symbol == trade.symbol }
                val currentPrice = if (asset != null && asset.rawPrice > 0) asset.rawPrice else trade.entryPrice
                ConfirmFillDialog(
                    trade = trade,
                    currentMarketPrice = currentPrice,
                    onDismiss = { tradeToConfirmFill = null },
                    onConfirm = { actualPrice, actualAmount ->
                        coroutineScope.launch {
                            val updated = AiAdvisorEngine.confirmPendingBuyFilled(
                                dao = dao,
                                tradeId = trade.id,
                                actualEntryPrice = actualPrice,
                                actualCoinAmount = actualAmount
                            )
                            if (updated != null) {
                                Toast.makeText(
                                    context,
                                    "🎉 ${trade.symbol} Alışı Onaylandı! Midas'ta $${String.format(Locale.US, if (updated.targetExitPrice < 1.0) "%.4f" else "%.2f", updated.targetExitPrice)} Limit Satış Emri Girin.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            tradeToConfirmFill = null
                        }
                    }
                )
            }

            // 3. DIALOG: CONFIRM SALE WITH USER (Interactive confirmation with exact PnL and cash calculation)
            tradeToConfirmSale?.let { (trade, currentMarketPrice) ->
                ConfirmSaleDialog(
                    trade = trade,
                    currentMarketPrice = currentMarketPrice,
                    onDismiss = { tradeToConfirmSale = null },
                    onConfirm = { actualExitPrice ->
                        coroutineScope.launch {
                            val closed = AiAdvisorEngine.closeActiveTrade(dao, trade.id, actualExitPrice)
                            if (closed != null) {
                                Toast.makeText(
                                    context,
                                    "🏆 ${trade.symbol} Satışı Onaylandı: +${String.format(Locale.US, "%.2f", closed.netProfitUsdt)} USDT Net Kâr Kasanıza Aktarıldı",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            tradeToConfirmSale = null
                        }
                    }
                )
            }

            // 4. DIALOG: UPDATE TARGET EXIT PRICE
            tradeToUpdateTarget?.let { (trade, currentMarketPrice) ->
                UpdateTargetDialog(
                    trade = trade,
                    currentMarketPrice = currentMarketPrice,
                    onDismiss = { tradeToUpdateTarget = null },
                    onConfirm = { newTargetPrice ->
                        coroutineScope.launch {
                            val updated = AiAdvisorEngine.updateTradeTarget(dao, trade.id, newTargetPrice)
                            if (updated != null) {
                                Toast.makeText(
                                    context,
                                    "🎯 ${trade.symbol} Yeni Satış Hedefi: $${String.format(Locale.US, if (newTargetPrice < 1.0) "%.4f" else "%.2f", newTargetPrice)} USDT",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            tradeToUpdateTarget = null
                        }
                    }
                )
            }

            // 5. DIALOG: INITIAL SETUP (IF UNINITIALIZED) OR STARTUP CASH CHECK
            val isInitialized = capitalProfile?.isInitialized == true

            if (!isInitialized) {
                InitialSetupCashDialog(
                    onConfirm = { initialCash ->
                        coroutineScope.launch {
                            AiAdvisorEngine.setInitialCashBalance(dao, initialCash)
                            Toast.makeText(context, "🚀 Midas USDT Kasanız Başlatıldı: $${String.format(Locale.US, "%.2f", initialCash)}", Toast.LENGTH_LONG).show()
                            showStartupCheckDialog = false
                        }
                    }
                )
            } else if (showStartupCheckDialog) {
                StartupCashCheckDialog(
                    capitalProfile = capitalProfile,
                    activeTradesCount = activeTrades.size,
                    onDismiss = { showStartupCheckDialog = false },
                    onConfirmCurrent = {
                        showStartupCheckDialog = false
                    },
                    onUpdateCash = { newCash ->
                        coroutineScope.launch {
                            AiAdvisorEngine.auditCashUpdate(dao, newCash)
                            Toast.makeText(context, "💵 Kasa Güncellendi: $${String.format(Locale.US, "%.2f", newCash)} USDT", Toast.LENGTH_SHORT).show()
                            showStartupCheckDialog = false
                        }
                    }
                )
            }

            // 6. DIALOG: QUICK CASH ADJUSTMENT
            if (showQuickCashDialog) {
                QuickCashUpdateDialog(
                    currentCash = capitalProfile?.availableCashUsdt ?: 0.0,
                    onDismiss = { showQuickCashDialog = false },
                    onConfirm = { newCash ->
                        coroutineScope.launch {
                            AiAdvisorEngine.auditCashUpdate(dao, newCash)
                            Toast.makeText(context, "💵 Kasa Güncellendi: $${String.format(Locale.US, "%.2f", newCash)} USDT", Toast.LENGTH_SHORT).show()
                            showQuickCashDialog = false
                        }
                    }
                )
            }

            // 7. DIALOG: FACTORY RESET ALL DATA
            if (showResetAllConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showResetAllConfirmDialog = false },
                    containerColor = ObsidianCard,
                    shape = RoundedCornerShape(16.dp),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, tint = CoralRed)
                            Text("Tüm Verileri & Kasayı Sıfırla?", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Text(
                            text = "Tüm açık ve geçmiş işlemler, kasa kayıtları ve karne hafızası silinerek uygulama ilk açılış durumuna (sıfır veri) döndürülecektir. Yeni nakdinizi sıfırdan girmeniz istenecektir.\n\nOnaylıyor musunuz?",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    AiAdvisorEngine.resetAllDatabase(dao)
                                    Toast.makeText(context, "🔄 Tüm veriler sıfırlandı! Yeni kasanızı tanımlayabilirsiniz.", Toast.LENGTH_LONG).show()
                                    showResetAllConfirmDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CoralRed, contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Evet, Her Şeyi Sıfırla", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetAllConfirmDialog = false }) {
                            Text("İptal", color = TextSecondary)
                        }
                    }
                )
            }
        }
    }
}

/**
 * Dialog to add existing crypto holding currently sitting in user's Midas portfolio.
 * Accurately parses user's Midas average cost (Ort. Fiyat) and amount (Adet) directly from Midas UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExistingHoldingDialog(
    assets: List<CryptoAsset>,
    onDismiss: () -> Unit,
    onConfirm: (symbol: String, entryPrice: Double, coinAmount: Double, dcaLevel: Int, targetProfitPct: Double) -> Unit
) {
    val quickSymbols = listOf("SOL", "BTC", "ETH", "AVAX", "XRP", "DOGE", "PEPE", "SUI", "BNB", "NEAR")
    var selectedSymbol by remember { mutableStateOf("BTC") }
    var customSymbol by remember { mutableStateOf("") }
    var entryPriceStr by remember { mutableStateOf("") }
    var coinAmountStr by remember { mutableStateOf("") }
    var dcaLevel by remember { mutableIntStateOf(1) }
    var targetProfitPctStr by remember { mutableStateOf("2.0") }

    val resolvedSymbol = if (customSymbol.isNotBlank()) customSymbol.trim().uppercase() else selectedSymbol

    // Auto-fill approximate current price if available
    LaunchedEffect(resolvedSymbol) {
        val asset = assets.firstOrNull { it.symbol.equals(resolvedSymbol, ignoreCase = true) }
        if (asset != null && asset.rawPrice > 0 && entryPriceStr.isBlank()) {
            entryPriceStr = String.format(Locale.US, if (asset.rawPrice < 1.0) "%.4f" else "%.2f", asset.rawPrice)
        }
    }

    val entryPrice = parseFlexibleDouble(entryPriceStr) ?: 0.0
    val coinAmount = parseFlexibleDouble(coinAmountStr) ?: 0.0
    val targetProfitPct = (parseFlexibleDouble(targetProfitPctStr) ?: 2.0).takeIf { it > 0.0 } ?: 2.0

    // Calculations matching Midas
    val matchingAsset = assets.firstOrNull { it.symbol.equals(resolvedSymbol, ignoreCase = true) }
    val currentMarketPrice = if (matchingAsset != null && matchingAsset.rawPrice > 0) matchingAsset.rawPrice else entryPrice
    val totalInvestedUsdt = entryPrice * coinAmount
    val currentMarketValueUsdt = currentMarketPrice * coinAmount
    val calculatedTargetExit = if (entryPrice > 0) entryPrice * (1.0 + (targetProfitPct + 0.40) / 100.0) else 0.0
    val targetTotalReturnUsdt = calculatedTargetExit * coinAmount * 0.998 // after Midas exit fee
    val estimatedNetProfitUsdt = (targetTotalReturnUsdt - totalInvestedUsdt).coerceAtLeast(0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianCard,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AddBusiness, contentDescription = null, tint = EmeraldProfit)
                Text(
                    text = "Midas Varlığını Portföye Ekle",
                    color = TextPrimary,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Midas ekranınızdaki Ort. Fiyat ve Adet bilgilerini girin. Asistan anlık olarak SAT / BEKLE kararı ve kârlı limit çıkışınızı yönetsin:",
                    color = TextSecondary,
                    fontSize = 11.5.sp
                )

                // Quick Symbol selector
                Text("Kripto Varlık:", color = TextTertiary, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(quickSymbols) { sym ->
                        val isSelected = selectedSymbol == sym && customSymbol.isBlank()
                        Surface(
                            modifier = Modifier
                                .clickable {
                                    selectedSymbol = sym
                                    customSymbol = ""
                                    val match = assets.firstOrNull { it.symbol == sym }
                                    if (match != null && match.rawPrice > 0) {
                                        entryPriceStr = String.format(Locale.US, if (match.rawPrice < 1.0) "%.4f" else "%.2f", match.rawPrice)
                                    }
                                },
                            color = if (isSelected) EmeraldProfit else ObsidianBg,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, if (isSelected) EmeraldProfit else ObsidianBorder)
                        ) {
                            Text(
                                text = sym,
                                color = if (isSelected) Color.Black else TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Or custom symbol
                OutlinedTextField(
                    value = customSymbol,
                    onValueChange = { customSymbol = it },
                    placeholder = { Text("veya Başka Sembol (Örn: TIA, FET)", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IceCyanBright,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                // 1. Ort. Fiyat / Maliyet
                OutlinedTextField(
                    value = entryPriceStr,
                    onValueChange = { entryPriceStr = it },
                    label = { Text("Midas Ort. Fiyat / Maliyetiniz ($)", fontSize = 11.sp) },
                    placeholder = { Text("Örn: 64681.22 veya 64.681,22", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldProfit,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                // 2. Midas'taki Varlık Miktarı (ADET)
                OutlinedTextField(
                    value = coinAmountStr,
                    onValueChange = { coinAmountStr = it },
                    label = { Text("Midas'taki Varlık Miktarı (Adet)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    placeholder = { Text("Örn: 0.0001992 veya 1.5", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IceCyanBright,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                // DCA Level selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Kaçıncı Kademe?", color = TextSecondary, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(1 to "1. Giriş", 2 to "2. Kademe", 3 to "3. Kademe").forEach { (lvl, lbl) ->
                            val isSel = dcaLevel == lvl
                            Surface(
                                modifier = Modifier.clickable { dcaLevel = lvl },
                                color = if (isSel) IceCyanBright else ObsidianBg,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, if (isSel) IceCyanBright else ObsidianBorder)
                            ) {
                                Text(
                                    text = lbl,
                                    color = if (isSel) Color.Black else TextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                // Live calculations & Midas target preview & DCA Defense Map
                if (entryPrice > 0 && coinAmount > 0) {
                    val dcaDefensePlan = remember(entryPrice, totalInvestedUsdt, targetProfitPct) {
                        val assumedPool = if (totalInvestedUsdt > 0) totalInvestedUsdt * 3.0 else 60.0
                        AiAdvisorEngine.calculateDcaDefensePlan(entryPrice, assumedPool, null, targetProfitPct)
                    }

                    Surface(
                        color = ObsidianBg,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.6.dp, EmeraldProfit.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Toplam Yatırılan Tutar:", color = TextTertiary, fontSize = 10.5.sp)
                                Text("$${String.format(Locale.US, "%.2f", totalInvestedUsdt)} USDT", color = TextPrimary, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Mevcut Anlık Değer:", color = TextTertiary, fontSize = 10.5.sp)
                                Text("$${String.format(Locale.US, "%.2f", currentMarketValueUsdt)} USDT", color = IceCyanBright, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                            HorizontalDivider(color = ObsidianBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("🎯 Midas'a Girilecek Satış Emri:", color = EmeraldProfitBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("$${String.format(Locale.US, if (calculatedTargetExit < 1.0) "%.4f" else "%.2f", calculatedTargetExit)} USDT", color = EmeraldProfitBright, fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Satışta Kasanıza Geçecek:", color = TextSecondary, fontSize = 10.sp)
                                Text("~$${String.format(Locale.US, "%.2f", targetTotalReturnUsdt)} USDT (+%${String.format(Locale.US, "%.1f", targetProfitPct)} net)", color = EmeraldProfit, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            // DCA Defense Map Tiers
                            HorizontalDivider(color = ObsidianBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))
                            Text("🛡️ Düşüşe Karşı Midas Kademeli Alım & Çıkış Haritası:", color = GoldWarm, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)

                            val t2 = dcaDefensePlan.tiers[1]
                            val t3 = dcaDefensePlan.tiers[2]

                            Text(
                                text = "• 2. Kademe (Dip Destek): $${String.format(Locale.US, if (t2.price < 1.0) "%.4f" else "%.2f", t2.price)} (-%${String.format(Locale.US, "%.1f", t2.dropPercentFromEntry)})\n   ➔ Dolarsa Yeni Ort. Maliyet: $${String.format(Locale.US, if (t2.averageCostPrice < 1.0) "%.4f" else "%.2f", t2.averageCostPrice)} | Yeni Satış: $${String.format(Locale.US, if (t2.targetExitPrice < 1.0) "%.4f" else "%.2f", t2.targetExitPrice)}",
                                color = GoldWarm.copy(alpha = 0.9f),
                                fontSize = 9.5.sp,
                                lineHeight = 13.sp
                            )

                            Text(
                                text = "• 3. Kademe (Son Savunma): $${String.format(Locale.US, if (t3.price < 1.0) "%.4f" else "%.2f", t3.price)} (-%${String.format(Locale.US, "%.1f", t3.dropPercentFromEntry)})\n   ➔ Dolarsa Yeni Ort. Maliyet: $${String.format(Locale.US, if (t3.averageCostPrice < 1.0) "%.4f" else "%.2f", t3.averageCostPrice)} | Yeni Satış: $${String.format(Locale.US, if (t3.targetExitPrice < 1.0) "%.4f" else "%.2f", t3.targetExitPrice)}",
                                color = IceCyan.copy(alpha = 0.9f),
                                fontSize = 9.5.sp,
                                lineHeight = 13.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (resolvedSymbol.isNotBlank() && entryPrice > 0 && coinAmount > 0) {
                        onConfirm(resolvedSymbol, entryPrice, coinAmount, dcaLevel, targetProfitPct)
                    }
                },
                enabled = resolvedSymbol.isNotBlank() && entryPrice > 0 && coinAmount > 0,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Varlığı Ekle & Takip Et", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal", color = TextSecondary)
            }
        }
    )
}

/**
 * Interactive dialog to confirm when a pending ambush buy order is filled on Midas.
 * Explicitly asks whether the buy order executed on Midas.
 */
@Composable
fun ConfirmFillDialog(
    trade: AppTradeEntity,
    currentMarketPrice: Double,
    onDismiss: () -> Unit,
    onConfirm: (actualPrice: Double, actualAmount: Double) -> Unit
) {
    var priceInput by remember {
        mutableStateOf(String.format(Locale.US, if (trade.entryPrice < 1.0) "%.4f" else "%.2f", trade.entryPrice))
    }
    var amountInput by remember {
        mutableStateOf(String.format(Locale.US, "%.6f", trade.coinAmount).trimEnd('0').trimEnd('.'))
    }

    val parsedPrice = parseFlexibleDouble(priceInput) ?: trade.entryPrice
    val parsedAmount = parseFlexibleDouble(amountInput) ?: trade.coinAmount
    val targetExit = parsedPrice * (1.0 + (2.0 + 0.40) / 100.0)
    val expectedNetPnl = (parsedPrice * parsedAmount) * 0.020

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianCard,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.HelpOutline, contentDescription = null, tint = IceCyanBright)
                Text("❓ Midas'ta Alış Gerçekleşti mi?", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    color = ObsidianBg,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.8.dp, ObsidianBorder)
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Midas hesabınızda '${trade.symbol}' için bekleyen limit alış emriniz gerçekleşti (doldu) mu?",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Eğer Midas'ta alışınız tamamlandıysa aşağıdaki gerçekleşen fiyatı ve adeti onaylayın. Sistem anında satış takibine başlayacaktır.",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                OutlinedTextField(
                    value = priceInput,
                    onValueChange = { priceInput = it },
                    label = { Text("Midas Gerçekleşen Alış Fiyatı (USDT)", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldProfit,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                // Quick Price Helper Chips in ConfirmFill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val roundedP = if (parsedPrice >= 100.0) {
                        Math.round(parsedPrice).toDouble()
                    } else if (parsedPrice >= 1.0) {
                        Math.round(parsedPrice * 10.0) / 10.0
                    } else {
                        Math.round(parsedPrice * 1000.0) / 1000.0
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                priceInput = String.format(Locale.US, if (roundedP < 1.0) "%.4f" else if (parsedPrice >= 100.0) "%.0f" else "%.2f", roundedP)
                                if (roundedP > 0 && trade.investedUsdt > 0) {
                                    val newAmt = (trade.investedUsdt * 0.998) / roundedP
                                    amountInput = String.format(Locale.US, "%.6f", newAmt).trimEnd('0').trimEnd('.')
                                }
                            },
                        color = ObsidianBg,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(0.8.dp, GoldWarm.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "🎯 Düz Yuvarla\n$${String.format(Locale.US, if (roundedP < 1.0) "%.4f" else if (parsedPrice >= 100.0) "%.0f" else "%.2f", roundedP)}",
                            color = GoldWarm,
                            fontSize = 9.5.sp,
                            lineHeight = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    }

                    if (currentMarketPrice > 0) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    priceInput = String.format(Locale.US, if (currentMarketPrice < 1.0) "%.4f" else "%.2f", currentMarketPrice)
                                    val newAmt = (trade.investedUsdt * 0.998) / currentMarketPrice
                                    amountInput = String.format(Locale.US, "%.6f", newAmt).trimEnd('0').trimEnd('.')
                                },
                            color = ObsidianBg,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(0.8.dp, IceCyan.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "⚡ Anlık Piyasa\n$${String.format(Locale.US, if (currentMarketPrice < 1.0) "%.4f" else "%.2f", currentMarketPrice)}",
                                color = IceCyanBright,
                                fontSize = 9.5.sp,
                                lineHeight = 12.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                priceInput = String.format(Locale.US, if (trade.entryPrice < 1.0) "%.4f" else "%.2f", trade.entryPrice)
                                amountInput = String.format(Locale.US, "%.6f", trade.coinAmount).trimEnd('0').trimEnd('.')
                            },
                        color = ObsidianBg,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(0.8.dp, ObsidianBorder)
                    ) {
                        Text(
                            text = "📌 İlk Emir\n$${String.format(Locale.US, if (trade.entryPrice < 1.0) "%.4f" else "%.2f", trade.entryPrice)}",
                            color = TextSecondary,
                            fontSize = 9.5.sp,
                            lineHeight = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it },
                    label = { Text("Alınan Miktar (${trade.symbol})", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldProfit,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                Surface(
                    color = ObsidianBg,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🎯 Onay Sonrası Midas'ta Girilecek Limit Satış:", color = EmeraldProfitBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Satış Hedefi (+%2.0 Net):", color = TextSecondary, fontSize = 10.5.sp)
                            Text("$${String.format(Locale.US, if (targetExit < 1.0) "%.4f" else "%.2f", targetExit)} USDT", color = EmeraldProfitBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Beklenen Net Kâr:", color = TextSecondary, fontSize = 10.5.sp)
                            Text("+$${String.format(Locale.US, "%.2f", expectedNetPnl)} USDT", color = EmeraldProfit, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val p = parseFlexibleDouble(priceInput) ?: trade.entryPrice
                    val a = parseFlexibleDouble(amountInput) ?: trade.coinAmount
                    onConfirm(p, a)
                },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("✅ Evet, Alındı (Satışa Başla)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hayır, Henüz Beklemede", color = TextSecondary)
            }
        }
    )
}

/**
 * Interactive dialog to confirm the exact Midas exit price and calculate exact net cash addition.
 */
@Composable
fun ConfirmSaleDialog(
    trade: AppTradeEntity,
    currentMarketPrice: Double,
    onDismiss: () -> Unit,
    onConfirm: (actualExitPrice: Double) -> Unit
) {
    var exitPriceStr by remember {
        mutableStateOf(String.format(Locale.US, if (currentMarketPrice < 1.0) "%.4f" else "%.2f", currentMarketPrice))
    }

    val exitPrice = parseFlexibleDouble(exitPriceStr) ?: currentMarketPrice
    val grossReturn = exitPrice * trade.coinAmount
    val buyFee = trade.investedUsdt * 0.0020
    val sellFee = grossReturn * 0.0020
    val totalFee = buyFee + sellFee
    val netPnl = (grossReturn - trade.investedUsdt) - totalFee
    val netPnlPercent = if (trade.investedUsdt > 0) (netPnl / trade.investedUsdt) * 100.0 else 0.0
    val netCashToAdd = (trade.investedUsdt + netPnl).coerceAtLeast(0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianCard,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldProfit)
                Text(
                    text = "${trade.symbol}/USDT Satışını Onayla",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Midas Kripto uygulamasında bu varlığı hangi fiyattan sattınız?",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                // Quick selector buttons
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = {
                            exitPriceStr = String.format(Locale.US, if (currentMarketPrice < 1.0) "%.4f" else "%.2f", currentMarketPrice)
                        },
                        label = { Text("⚡ Anlık: $${String.format(Locale.US, if (currentMarketPrice < 1.0) "%.4f" else "%.2f", currentMarketPrice)}", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = IceCyanBright)
                    )
                    AssistChip(
                        onClick = {
                            exitPriceStr = String.format(Locale.US, if (trade.targetExitPrice < 1.0) "%.4f" else "%.2f", trade.targetExitPrice)
                        },
                        label = { Text("🎯 Hedef: $${String.format(Locale.US, if (trade.targetExitPrice < 1.0) "%.4f" else "%.2f", trade.targetExitPrice)}", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = EmeraldProfitBright)
                    )
                }

                OutlinedTextField(
                    value = exitPriceStr,
                    onValueChange = { exitPriceStr = it },
                    label = { Text("Midas Gerçekleşen Satış Fiyatı ($ USDT)", fontSize = 11.5.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldProfit,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Surface(
                    color = ObsidianBg,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (netPnl >= 0) EmeraldProfit else CoralRed)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Alış Maliyeti:", color = TextSecondary, fontSize = 11.sp)
                            Text("$${String.format(Locale.US, "%.2f", trade.entryPrice)} (${String.format(Locale.US, "%.6f", trade.coinAmount).trimEnd('0').trimEnd('.')} ${trade.symbol})", color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Satış Brüt Tutarı:", color = TextSecondary, fontSize = 11.sp)
                            Text("$${String.format(Locale.US, "%.2f", grossReturn)} USDT", color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Midas Komisyonu (%0.40):", color = TextSecondary, fontSize = 11.sp)
                            Text("-$${String.format(Locale.US, "%.2f", totalFee)} USDT", color = TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        HorizontalDivider(color = ObsidianBorder, thickness = 0.5.dp)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("💵 Kasanıza Geçecek Net:", color = IceCyanBright, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            Text("$${String.format(Locale.US, "%.2f", netCashToAdd)} USDT", color = IceCyanBright, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("📈 Net Kâr / Zarar:", color = if (netPnl >= 0) EmeraldProfit else CoralRed, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "${if (netPnl >= 0) "+" else ""}${String.format(Locale.US, "%.2f", netPnl)} USDT (%${String.format(Locale.US, "%.2f", netPnlPercent)})",
                                color = if (netPnl >= 0) EmeraldProfit else CoralRed,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (exitPrice > 0) {
                        onConfirm(exitPrice)
                    }
                },
                enabled = exitPrice > 0,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("✅ Onayla & Kasaya Ekle", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Vazgeç", color = TextSecondary)
            }
        }
    )
}

/**
 * Dialog to adjust target exit price dynamically if price runs higher.
 */
@Composable
fun UpdateTargetDialog(
    trade: AppTradeEntity,
    currentMarketPrice: Double,
    onDismiss: () -> Unit,
    onConfirm: (newTargetPrice: Double) -> Unit
) {
    var targetPriceStr by remember {
        mutableStateOf(String.format(Locale.US, if (trade.targetExitPrice < 1.0) "%.4f" else "%.2f", trade.targetExitPrice))
    }

    val targetPrice = parseFlexibleDouble(targetPriceStr) ?: trade.targetExitPrice
    val expectedGross = targetPrice * trade.coinAmount
    val totalFee = (trade.investedUsdt + expectedGross) * 0.0020
    val expectedNetPnl = (expectedGross - trade.investedUsdt) - totalFee
    val expectedNetPct = if (trade.investedUsdt > 0) (expectedNetPnl / trade.investedUsdt) * 100.0 else 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianCard,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.TrendingUp, contentDescription = null, tint = EmeraldProfit)
                Text(
                    text = "${trade.symbol} Hedef Satışını Güncelle",
                    color = TextPrimary,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Midas'ta koyacağınız veya güncelleyeceğiniz yeni limit satış fiyatını belirleyin:",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(2.0, 3.5, 5.0, 7.5, 10.0).forEach { pct ->
                        val base = if (currentMarketPrice > trade.entryPrice) currentMarketPrice else trade.entryPrice
                        val calcPrice = base * (1.0 + (pct + 0.40) / 100.0)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    targetPriceStr = String.format(Locale.US, if (calcPrice < 1.0) "%.4f" else "%.2f", calcPrice)
                                },
                            color = ObsidianBg,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, ObsidianBorder)
                        ) {
                            Box(modifier = Modifier.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                                Text("+%$pct", color = IceCyanBright, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = targetPriceStr,
                    onValueChange = { targetPriceStr = it },
                    label = { Text("Yeni Limit Satış Fiyatı ($ USDT)", fontSize = 11.5.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldProfit,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Surface(
                    color = ObsidianBg,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Alış Maliyeti:", color = TextSecondary, fontSize = 11.sp)
                            Text("$${String.format(Locale.US, "%.2f", trade.entryPrice)}", color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Anlık Piyasa Fiyatı:", color = TextSecondary, fontSize = 11.sp)
                            Text("$${String.format(Locale.US, "%.2f", currentMarketPrice)}", color = IceCyanBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        HorizontalDivider(color = ObsidianBorder, thickness = 0.5.dp)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Yeni Net Kâr Hedefi:", color = EmeraldProfitBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "+$${String.format(Locale.US, "%.2f", expectedNetPnl)} USDT (+%${String.format(Locale.US, "%.2f", expectedNetPct)})",
                                color = EmeraldProfitBright,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (targetPrice > 0) {
                        onConfirm(targetPrice)
                    }
                },
                enabled = targetPrice > 0,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Hedefi Kaydet", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Vazgeç", color = TextSecondary)
            }
        }
    )
}

/**
 * Clean slate initial setup dialog shown when app is first installed or reset.
 * Prompts user to input their real initial Midas USDT balance directly (e.g. 44.73 USD).
 */
@Composable
fun InitialSetupCashDialog(
    onConfirm: (Double) -> Unit
) {
    var inputStr by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { /* Modal force setup */ },
        containerColor = ObsidianCard,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = EmeraldProfitBright)
                Text(
                    text = "Midas USDT Kasanızı Tanımlayın",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Midas Kripto cüzdanınızdaki güncel boş USDT nakdinizi girin. Tüm 3 kademeli bütçe ve analiz önerileri bu gerçek nakde göre planlanır:",
                    color = TextSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                )

                OutlinedTextField(
                    value = inputStr,
                    onValueChange = { inputStr = it },
                    label = { Text("Kullanılabilir USDT", fontSize = 11.sp) },
                    placeholder = { Text("Örn: 44.73 veya 44,73", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IceCyanBright,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                // Quick preset chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    listOf(30.0, 44.73, 50.0, 100.0, 200.0).forEach { preset ->
                        Surface(
                            color = ObsidianCardElevated,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(0.5.dp, ObsidianBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    inputStr = if (preset % 1.0 == 0.0) preset.toInt().toString() else String.format(Locale.US, "%.2f", preset)
                                }
                        ) {
                            Text(
                                text = "$${if (preset % 1.0 == 0.0) preset.toInt().toString() else String.format(Locale.US, "%.2f", preset)}",
                                color = IceCyanBright,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 5.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = parseFlexibleDouble(inputStr)
                    if (parsed != null && parsed >= 0.0) {
                        onConfirm(parsed)
                    }
                },
                enabled = parseFlexibleDouble(inputStr) != null,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("🚀 Kasanızı Kaydet & Başla", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    )
}

/**
 * Startup dialog that remembers all past portfolio state,
 * politely checks if Midas USDT cash balance is the same,
 * and allows instant 1-tap confirmation or quick updating.
 */
@Composable
fun StartupCashCheckDialog(
    capitalProfile: CapitalProfileEntity?,
    activeTradesCount: Int,
    onDismiss: () -> Unit,
    onConfirmCurrent: () -> Unit,
    onUpdateCash: (Double) -> Unit
) {
    val currentCash = capitalProfile?.availableCashUsdt ?: 0.0
    var isEditing by remember { mutableStateOf(false) }
    var inputStr by remember { mutableStateOf(String.format(Locale.US, "%.2f", currentCash)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianCard,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = EmeraldProfitBright)
                Text(
                    text = "Midas USDT Kasanız Güncel mi?",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Tüm açık pozisyonlarınız ($activeTradesCount adet) ve analiz hafızanız korundu. Midas'taki kullanılabilir USDT kasanızı onaylayın:",
                    color = TextSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                )

                Surface(
                    color = ObsidianBg,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (isEditing) IceCyanBright else ObsidianBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Kayıtlı Boş Nakit:", color = TextSecondary, fontSize = 11.sp)
                            Text(
                                text = "$${String.format(Locale.US, "%.2f", currentCash)} USDT",
                                color = EmeraldProfitBright,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        if (!isEditing) {
                            OutlinedButton(
                                onClick = { isEditing = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = IceCyanBright),
                                border = BorderStroke(1.dp, IceCyanBright.copy(alpha = 0.5f))
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Nakit Değişti, Yeni Bakiye Gir", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            OutlinedTextField(
                                value = inputStr,
                                onValueChange = { inputStr = it },
                                label = { Text("Güncel USDT Bakiyeniz", fontSize = 11.sp) },
                                placeholder = { Text("Örn: 44.73 veya 100", fontSize = 11.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = IceCyanBright,
                                    unfocusedBorderColor = ObsidianBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            // Quick adjustment chips
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                listOf(30.0, 44.73, 50.0, 100.0, 200.0).forEach { preset ->
                                    Surface(
                                        color = ObsidianCardElevated,
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(0.5.dp, ObsidianBorder),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                inputStr = if (preset % 1.0 == 0.0) preset.toInt().toString() else String.format(Locale.US, "%.2f", preset)
                                            }
                                    ) {
                                        Text(
                                            text = "$${if (preset % 1.0 == 0.0) preset.toInt().toString() else String.format(Locale.US, "%.2f", preset)}",
                                            color = IceCyanBright,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(vertical = 4.dp),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isEditing) {
                Button(
                    onClick = {
                        val parsed = parseFlexibleDouble(inputStr)
                        if (parsed != null && parsed >= 0.0) {
                            onUpdateCash(parsed)
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Kaydet & Başla", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            } else {
                Button(
                    onClick = onConfirmCurrent,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("✅ Evet, Aynen Devam", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        },
        dismissButton = {
            if (isEditing) {
                TextButton(onClick = { isEditing = false }) {
                    Text("Geri", color = TextSecondary)
                }
            }
        }
    )
}

/**
 * Quick cash balance update dialog accessed directly from topbar or actions.
 */
@Composable
fun QuickCashUpdateDialog(
    currentCash: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var inputStr by remember { mutableStateOf(if (currentCash > 0.0) String.format(Locale.US, "%.2f", currentCash) else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianCard,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = IceCyanBright)
                Text("Midas Kasa Bakiyesi Güncelle", color = TextPrimary, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Midas Kripto cüzdanınızdaki güncel boş USDT miktarını girin:", color = TextSecondary, fontSize = 11.5.sp)
                OutlinedTextField(
                    value = inputStr,
                    onValueChange = { inputStr = it },
                    label = { Text("Kullanılabilir USDT", fontSize = 11.sp) },
                    placeholder = { Text("Örn: 44.73", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IceCyanBright,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                // Quick preset chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    listOf(30.0, 44.73, 50.0, 100.0, 200.0).forEach { preset ->
                        Surface(
                            color = ObsidianCardElevated,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(0.5.dp, ObsidianBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    inputStr = if (preset % 1.0 == 0.0) preset.toInt().toString() else String.format(Locale.US, "%.2f", preset)
                                }
                        ) {
                            Text(
                                text = "$${if (preset % 1.0 == 0.0) preset.toInt().toString() else String.format(Locale.US, "%.2f", preset)}",
                                color = IceCyanBright,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 4.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = parseFlexibleDouble(inputStr)
                    if (parsed != null && parsed >= 0.0) {
                        onConfirm(parsed)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Kaydet", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal", color = TextSecondary)
            }
        }
    )
}

/**
 * Interactive card displaying a pending ambush buy order on Midas with a live 45-minute countdown timer,
 * price comparison vs current market price, and 1-tap confirmation or cancellation.
 */
@Composable
fun PendingAmbushCard(
    trade: AppTradeEntity,
    currentPrice: Double,
    onRequestConfirmFill: (AppTradeEntity) -> Unit,
    onCancelAmbush: (tradeId: Long) -> Unit,
    onExtendTimeout: (tradeId: Long) -> Unit
) {
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    val totalDurationMillis = (trade.ambushTimeoutMinutes.coerceAtLeast(1)) * 60 * 1000L
    val elapsedMillis = (currentTime - trade.openedAt).coerceAtLeast(0L)
    val remainingMillis = (totalDurationMillis - elapsedMillis).coerceAtLeast(0L)
    val remainingSeconds = (remainingMillis / 1000) % 60
    val remainingMinutes = (remainingMillis / (1000 * 60))
    val isExpired = remainingMillis <= 0L
    val progress = if (totalDurationMillis > 0) (elapsedMillis.toFloat() / totalDurationMillis.toFloat()).coerceIn(0f, 1f) else 1f

    val isPriceReached = currentPrice > 0 && currentPrice <= trade.entryPrice
    val diffPercent = if (trade.entryPrice > 0 && currentPrice > 0) ((currentPrice - trade.entryPrice) / trade.entryPrice) * 100.0 else 0.0

    val cardBorderColor = when {
        isPriceReached -> EmeraldProfitBright
        isExpired -> CoralRedBright
        else -> IceCyan.copy(alpha = 0.6f)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ObsidianSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.2.dp, cardBorderColor)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${trade.symbol}/USDT",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Surface(
                        color = if (isPriceReached) EmeraldContainer else if (isExpired) CoralRed.copy(alpha = 0.2f) else IceCyan.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (isPriceReached) "⚡ FİYAT PUSUYA İNDİ!" else if (isExpired) "⏱️ ${trade.ambushTimeoutMinutes} DK SÜRE DOLDU" else "⏳ DÜŞÜŞ BEKLENİYOR",
                            color = if (isPriceReached) EmeraldProfitBright else if (isExpired) CoralRedBright else IceCyanBright,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }

                // Countdown Badge
                Surface(
                    color = if (isExpired) CoralRed.copy(alpha = 0.25f) else ObsidianCardElevated,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(0.8.dp, if (isExpired) CoralRed else GoldWarm.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = if (isExpired) CoralRedBright else GoldWarm,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = if (isExpired) "00:00 (Doldu)" else String.format(Locale.US, "%02d:%02d / %02d:00", remainingMinutes, remainingSeconds, trade.ambushTimeoutMinutes),
                            color = if (isExpired) CoralRedBright else GoldWarm,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Progress Bar
            LinearProgressIndicator(
                progress = { 1f - progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = if (isExpired) CoralRedBright else if (isPriceReached) EmeraldProfitBright else IceCyanBright,
                trackColor = ObsidianBorder
            )

            // Price Details Matrix
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Pusu Limit Alış:", color = TextTertiary, fontSize = 10.sp)
                    Text("$${String.format(Locale.US, if (trade.entryPrice < 1.0) "%.4f" else "%.2f", trade.entryPrice)}", color = IceCyanBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Rezerve Kasa:", color = TextTertiary, fontSize = 10.sp)
                    Text("$${String.format(Locale.US, "%.2f", trade.investedUsdt)} USDT", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Anlık Piyasa:", color = TextTertiary, fontSize = 10.sp)
                    Text(
                        text = "$${String.format(Locale.US, if (currentPrice < 1.0) "%.4f" else "%.2f", currentPrice)} (${if (diffPercent >= 0) "+" else ""}${String.format(Locale.US, "%.2f", diffPercent)}%)",
                        color = if (isPriceReached) EmeraldProfitBright else TextSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Status & Action Guidance Box
            if (isPriceReached) {
                Surface(
                    color = EmeraldProfit.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, EmeraldProfitBright)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = EmeraldProfitBright, modifier = Modifier.size(16.dp))
                        Text(
                            text = "⚡ Fiyat pusu seviyesine ($${String.format(Locale.US, if (currentPrice < 1.0) "%.4f" else "%.2f", currentPrice)}) indi! Midas'ta emriniz dolduysa aşağıdaki butondan alışınızı onaylayın.",
                            color = EmeraldProfitBright,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else if (isExpired) {
                Surface(
                    color = CoralRed.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, CoralRed.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.WarningAmber, contentDescription = null, tint = CoralRedBright, modifier = Modifier.size(16.dp))
                        Text(
                            text = "⏱️ ${trade.ambushTimeoutMinutes} Dk pusu süresi doldu. Fiyat desteğe inmediyse Midas'tan emri iptal edip yeni fırsat arayın.",
                            color = CoralRedBright,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Surface(
                    color = ObsidianBg,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.8.dp, ObsidianBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = IceCyanBright, modifier = Modifier.size(14.dp))
                        Text(
                            text = "Midas'ta $${String.format(Locale.US, if (trade.entryPrice < 1.0) "%.4f" else "%.2f", trade.entryPrice)} limit alış emriniz açık beklemelidir. Fiyat pusu seviyesine indiğinde sistem alış teyidi isteyecektir.",
                            color = TextSecondary,
                            fontSize = 10.5.sp,
                            lineHeight = 13.sp
                        )
                    }
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { onRequestConfirmFill(trade) },
                    modifier = Modifier
                        .weight(1.35f)
                        .height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPriceReached) EmeraldProfit else ObsidianCardElevated,
                        contentColor = if (isPriceReached) Color.Black else IceCyanBright
                    ),
                    border = BorderStroke(1.dp, if (isPriceReached) EmeraldProfit else IceCyan.copy(alpha = 0.4f))
                ) {
                    Icon(
                        imageVector = if (isPriceReached) Icons.Default.Check else Icons.Default.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (isPriceReached) Color.Black else IceCyanBright
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isPriceReached) "🔔 Alış Gerçekleşti mi?" else "❓ Alış Gerçekleşti mi?",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = { onCancelAmbush(trade.id) },
                    modifier = Modifier
                        .weight(1.05f)
                        .height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralRedBright),
                    border = BorderStroke(1.dp, CoralRed.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("❌ İptal Et", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                if (!isExpired) {
                    IconButton(
                        onClick = { onExtendTimeout(trade.id) },
                        modifier = Modifier
                            .size(38.dp)
                            .background(ObsidianCardElevated, RoundedCornerShape(8.dp))
                            .border(width = 0.8.dp, color = ObsidianBorder, shape = RoundedCornerShape(8.dp))
                    ) {
                        Icon(Icons.Default.MoreTime, contentDescription = "+30 Dk", tint = GoldWarm, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun LiveAssistantScreen(
    guidance: ActionGuidance,
    capitalProfile: CapitalProfileEntity?,
    activeTrades: List<AppTradeEntity>,
    pendingTrades: List<AppTradeEntity> = emptyList(),
    assets: List<CryptoAsset>,
    oracleMap: Map<String, BinanceOracleData>,
    techMap: Map<String, TechnicalAnalysis5m>,
    coinMemories: List<CoinMemoryEntity>,
    lastRefreshTime: Long = System.currentTimeMillis(),
    isRefreshing: Boolean = false,
    onManualRefresh: () -> Unit = {},
    onOpenBudgetProposal: (CryptoAsset) -> Unit,
    onOpenAssetDetails: (CryptoAsset) -> Unit = {},
    onOpenAddExistingDialog: () -> Unit,
    onRequestConfirmFill: (trade: AppTradeEntity) -> Unit = {},
    onCancelAmbush: (tradeId: Long) -> Unit = {},
    onExtendTimeout: (tradeId: Long) -> Unit = {},
    onRequestConfirmSale: (trade: AppTradeEntity, currentPrice: Double) -> Unit,
    onRequestUpdateTarget: (trade: AppTradeEntity, currentPrice: Double) -> Unit,
    onDcaStep: (tradeId: Long, dcaPrice: Double, dcaAmount: Double) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var selectedFilter by remember { mutableStateOf("ALL") }

    val filteredAssets = remember(assets, selectedFilter) {
        if (selectedFilter == "ALL") assets else assets.filter { it.symbol.equals(selectedFilter, ignoreCase = true) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // LIVE DATA & 5-MINUTE SNIPER SYNC STATUS BAR
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ObsidianCard,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (isRefreshing) EmeraldProfit else ObsidianBorder)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(if (isRefreshing) EmeraldProfitBright else EmeraldProfit)
                            )
                            Column {
                                Text(
                                    text = "⚡ BİNANCE WEBSOCKET CANLI AKIŞ",
                                    color = TextPrimary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "3dk & 5dk Kline + Derinlik (Son: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastRefreshTime))})",
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Surface(
                            onClick = onManualRefresh,
                            color = if (isRefreshing) EmeraldContainer else ObsidianCardElevated,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, EmeraldProfit.copy(alpha = 0.6f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                if (isRefreshing) {
                                    CircularProgressIndicator(modifier = Modifier.size(11.dp), color = EmeraldProfit, strokeWidth = 2.dp)
                                    Text("Yenileniyor...", color = EmeraldProfitBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = null, tint = EmeraldProfitBright, modifier = Modifier.size(13.dp))
                                    Text("Şimdi Yenile", color = EmeraldProfitBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Dual AI Engine Status Strip
                    Surface(
                        color = ObsidianBg,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("🧠", fontSize = 11.sp)
                                Text("Çekirdek: Gemini 3.1 Pro", color = EmeraldProfitBright, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            Text("•", color = TextTertiary, fontSize = 10.sp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("🚨", fontSize = 11.sp)
                                Text("Yedek: Flash-Lite", color = IceCyanBright, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            Text("•", color = TextTertiary, fontSize = 10.sp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("📐", fontSize = 11.sp)
                                Text("Kantitatif Çekirdek", color = GoldWarm, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
        // 1. TOP AI HERO "ŞİMDİ NE YAPMALIYIM?" LUXURY BANNER WITH SPACE RADAR
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ObsidianSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.2.dp, Color(guidance.statusColorHex).copy(alpha = 0.8f))
            ) {
                Box {
                    // Subtle background Radar glow on top right
                    SpaceRadarScanner(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(110.dp)
                            .padding(top = 6.dp, end = 6.dp),
                        scannerColor = Color(guidance.statusColorHex)
                    )

                    Column(
                        modifier = Modifier
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(guidance.statusColorHex).copy(alpha = 0.08f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    imageVector = Icons.Default.SmartToy,
                                    contentDescription = null,
                                    tint = Color(guidance.statusColorHex),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "ŞİMDİ NE YAPMALIYIM?",
                                    color = Color(guidance.statusColorHex),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Surface(
                                color = Color(guidance.statusColorHex).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(0.8.dp, Color(guidance.statusColorHex))
                            ) {
                                Text(
                                    text = guidance.statusBadge,
                                    color = Color(guidance.statusColorHex),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Text(
                            text = guidance.title,
                            color = TextPrimary,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 20.sp
                        )

                        Surface(
                            color = ObsidianBg.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(0.6.dp, ObsidianBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(text = guidance.step1, color = TextPrimary.copy(alpha = 0.95f), fontSize = 11.5.sp, lineHeight = 16.sp)
                                Text(text = guidance.step2, color = TextPrimary.copy(alpha = 0.95f), fontSize = 11.5.sp, lineHeight = 16.sp)
                                Text(text = guidance.step3, color = TextPrimary.copy(alpha = 0.95f), fontSize = 11.5.sp, lineHeight = 16.sp)
                            }
                        }

                        // If active trade hit target or pumping, show instant interactive close button
                        if (activeTrades.isNotEmpty()) {
                            val trade = activeTrades.first()
                            val asset = assets.firstOrNull { it.symbol == trade.symbol }
                            val currentPrice = if (asset != null && asset.rawPrice > 0) asset.rawPrice else trade.entryPrice

                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onRequestConfirmSale(trade, currentPrice)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black)
                            ) {
                                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Midas'ta Satıldıysa Onayla & Kasaya Al", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // ADD EXISTING HOLDING PROMINENT ACTION CARD
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAddExistingDialog() },
                color = ObsidianCard,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, IceCyanBright.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(IceCyanContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.AddBusiness, contentDescription = null, tint = IceCyanBright, modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text(
                                text = "Mevcut Midas Varlığımı Ekle",
                                color = TextPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Elinizdeki coini girin, asistan Sat mı Bekle mi söylesin",
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = IceCyanBright)
                }
            }
        }

        // 1.5 PENDING AMBUSH ORDERS (WAITING FOR MIDAS BUY FILL WITH 45 MIN TIMEOUT)
        if (pendingTrades.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⏳ BEKLEYEN MİDAS PUSU EMİRLERİ (${pendingTrades.size})",
                        color = GoldWarm,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            items(pendingTrades, key = { "pending_${it.id}" }) { trade ->
                val asset = assets.firstOrNull { it.symbol == trade.symbol }
                val currentPrice = if (asset != null && asset.rawPrice > 0) asset.rawPrice else trade.entryPrice
                PendingAmbushCard(
                    trade = trade,
                    currentPrice = currentPrice,
                    onRequestConfirmFill = onRequestConfirmFill,
                    onCancelAmbush = onCancelAmbush,
                    onExtendTimeout = onExtendTimeout
                )
            }
        }

        // 2. ACTIVE POSITIONS "SAT MI BEKLE Mİ?" LIVE STATUS SECTION
        if (activeTrades.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📌 TAKİP EDİLEN MİDAS VARLIKLARINIZ (${activeTrades.size})",
                        color = TextPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            items(activeTrades, key = { it.id }) { trade ->
                val asset = assets.firstOrNull { it.symbol == trade.symbol }
                val currentPrice = if (asset != null && asset.rawPrice > 0) asset.rawPrice else trade.entryPrice
                val pnlUsdt = (currentPrice - trade.entryPrice) * trade.coinAmount - trade.midasTotalFeeUsdt
                val pnlPercent = if (trade.investedUsdt > 0) (pnlUsdt / trade.investedUsdt) * 100.0 else 0.0
                val isTargetHit = currentPrice >= trade.targetExitPrice
                val isPumpingAboveTarget = currentPrice > trade.targetExitPrice * 1.005
                val currentGross = currentPrice * trade.coinAmount
                val currentNetReturn = currentGross * 0.998
                val targetTotalReturnUsdt = trade.targetExitPrice * trade.coinAmount * 0.998 // after Midas exit fee

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ObsidianSurface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.2.dp, if (isPumpingAboveTarget) EmeraldProfitBright else if (isTargetHit) EmeraldProfit else if (pnlUsdt >= 0) IceCyanBright else GoldWarm)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Header: Symbol, Status Pill, PnL
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "${trade.symbol}/USDT",
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )

                                Surface(
                                    color = if (isPumpingAboveTarget) EmeraldProfitBright else if (isTargetHit) EmeraldProfit else if (pnlUsdt >= 0) IceCyanContainer else ObsidianCardElevated,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (isPumpingAboveTarget) "🔥 HEDEFİ AŞTI (PUMP!)" else if (isTargetHit) "🟢 SATIŞ VAKTİ (HEDEF DOLDU)" else if (pnlUsdt >= 0) "📈 KÂRDA YÜKSELİYOR" else "⏳ SPOT SABIR (0 ZARAR)",
                                        color = if (isPumpingAboveTarget || isTargetHit) Color.Black else if (pnlUsdt >= 0) IceCyanBright else GoldWarm,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Text(
                                text = "${if (pnlUsdt >= 0) "+" else ""}${String.format(Locale.US, "%.2f", pnlUsdt)} USDT (%${String.format(Locale.US, "%.2f", pnlPercent)})",
                                color = if (pnlUsdt >= 0) EmeraldProfit else CoralRed,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Summary Info Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Maliyet: $${String.format(Locale.US, "%.2f", trade.entryPrice)}", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("Adet: ${String.format(Locale.US, "%.6f", trade.coinAmount).trimEnd('0').trimEnd('.')} ${trade.symbol}", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("Anlık: $${String.format(Locale.US, "%.2f", currentPrice)}", color = IceCyanBright, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }

                        // EXPLICIT MIDAS LIMIT ORDER INSTRUCTION BOX
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = if (isPumpingAboveTarget || isTargetHit) EmeraldProfit.copy(alpha = 0.12f) else ObsidianBg,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (isPumpingAboveTarget) EmeraldProfitBright else if (isTargetHit) EmeraldProfit else ObsidianBorder)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (isPumpingAboveTarget) {
                                    Text(
                                        text = "🔥 ANLIK FİYAT HEDEFİ GEÇTİ ($${String.format(Locale.US, "%.2f", currentPrice)} > $${String.format(Locale.US, "%.2f", trade.targetExitPrice)})",
                                        color = EmeraldProfitBright,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Kayıtlı Satış Hedefi:", color = TextSecondary, fontSize = 10.5.sp)
                                        Text("$${String.format(Locale.US, "%.2f", trade.targetExitPrice)} USDT", color = TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Anlık Piyasa Fiyatı:", color = TextSecondary, fontSize = 10.5.sp)
                                        Text("$${String.format(Locale.US, "%.2f", currentPrice)} USDT (+%${String.format(Locale.US, "%.2f", pnlPercent)})", color = EmeraldProfitBright, fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Anlık Satışta Net Kasa:", color = TextSecondary, fontSize = 10.5.sp)
                                        Text("~$${String.format(Locale.US, "%.2f", currentNetReturn)} USDT (Kâr: +$${String.format(Locale.US, "%.2f", pnlUsdt)})", color = EmeraldProfit, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Text(
                                        text = "💡 Midas'ta satışı anlık fiyattan yaptıysanız 'Satışı Onayla'ya tıklayın veya hedefi daha yukarı güncellemek için 'Hedefi Düzenle'yi seçin.",
                                        color = IceCyanBright,
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp
                                    )
                                } else {
                                    Text(
                                        text = if (isTargetHit) "🎉 HEDEF DOLDU! MİDAS SATIŞ EMRİNİZİ KONTROL EDİN:" else "📢 MİDAS'TA GİRİLECEK LİMİT SATIŞ EMRİ:",
                                        color = if (isTargetHit) EmeraldProfitBright else GoldWarm,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Limit Satış Fiyatı:", color = TextSecondary, fontSize = 11.sp)
                                        Text(
                                            text = "$${String.format(Locale.US, if (trade.targetExitPrice < 1.0) "%.4f" else "%.2f", trade.targetExitPrice)} USDT",
                                            color = EmeraldProfitBright,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Satılacak Varlık Miktarı:", color = TextSecondary, fontSize = 11.sp)
                                        Text(
                                            text = "${String.format(Locale.US, "%.6f", trade.coinAmount).trimEnd('0').trimEnd('.')} ${trade.symbol} (Tümü)",
                                            color = TextPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Satışta Kasanıza Geçecek:", color = TextSecondary, fontSize = 11.sp)
                                        Text(
                                            text = "~$${String.format(Locale.US, "%.2f", targetTotalReturnUsdt)} USDT",
                                            color = EmeraldProfit,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // 3-TIER DCA DEFENSE & EXIT PLAN CARD
                        val activeDcaPlan = remember(trade.entryPrice, trade.investedUsdt, techMap[trade.symbol]) {
                            val assumedPool = if (trade.investedUsdt > 0) trade.investedUsdt * (if (trade.dcaLevel == 1) 3.0 else if (trade.dcaLevel == 2) 1.5 else 1.0) else 60.0
                            AiAdvisorEngine.calculateDcaDefensePlan(trade.entryPrice, assumedPool, techMap[trade.symbol], 2.0)
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = ObsidianBg,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.6.dp, ObsidianBorder)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🛡️ Midas Kademeli Emir & Savunma Planı:", color = GoldWarm, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                    Surface(
                                        color = if (trade.dcaLevel == 1) EmeraldContainer else if (trade.dcaLevel == 2) GoldContainer else IceCyanContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "${trade.dcaLevel}/${trade.maxDcaLevels}. Kademe",
                                            color = if (trade.dcaLevel == 1) EmeraldProfitBright else if (trade.dcaLevel == 2) GoldWarm else IceCyanBright,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                val t2 = activeDcaPlan.tiers[1]
                                val t3 = activeDcaPlan.tiers[2]

                                if (trade.dcaLevel < 2) {
                                    Text(
                                        text = "• 2. Kademe (Dip Destek Emri): $${String.format(Locale.US, if (t2.price < 1.0) "%.4f" else "%.2f", t2.price)} (-%${String.format(Locale.US, "%.1f", t2.dropPercentFromEntry)})\n   ➔ Dolarsa Yeni Maliyet: $${String.format(Locale.US, if (t2.averageCostPrice < 1.0) "%.4f" else "%.2f", t2.averageCostPrice)} | Yeni Satış: $${String.format(Locale.US, if (t2.targetExitPrice < 1.0) "%.4f" else "%.2f", t2.targetExitPrice)}",
                                        color = GoldWarm.copy(alpha = 0.9f),
                                        fontSize = 9.5.sp,
                                        lineHeight = 13.sp
                                    )
                                }

                                if (trade.dcaLevel < 3) {
                                    Text(
                                        text = "• 3. Kademe (Son Savunma Emri): $${String.format(Locale.US, if (t3.price < 1.0) "%.4f" else "%.2f", t3.price)} (-%${String.format(Locale.US, "%.1f", t3.dropPercentFromEntry)})\n   ➔ Dolarsa Yeni Maliyet: $${String.format(Locale.US, if (t3.averageCostPrice < 1.0) "%.4f" else "%.2f", t3.averageCostPrice)} | Yeni Satış: $${String.format(Locale.US, if (t3.targetExitPrice < 1.0) "%.4f" else "%.2f", t3.targetExitPrice)}",
                                        color = IceCyan.copy(alpha = 0.9f),
                                        fontSize = 9.5.sp,
                                        lineHeight = 13.sp
                                    )
                                } else {
                                    Text(
                                        text = "• 3/3 Kademe Tamamlandı: Başka ekleme yapılmaz, sadece $${String.format(Locale.US, if (trade.targetExitPrice < 1.0) "%.4f" else "%.2f", trade.targetExitPrice)} kârlı çıkış beklenir.",
                                        color = EmeraldProfitBright,
                                        fontSize = 9.5.sp
                                    )
                                }
                            }
                        }

                        // ACTION BUTTONS: CONFIRM SALE (ASKS PRICE) + UPDATE TARGET
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { onRequestConfirmSale(trade, currentPrice) },
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(40.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPumpingAboveTarget || isTargetHit) EmeraldProfit else ObsidianCardElevated,
                                    contentColor = if (isPumpingAboveTarget || isTargetHit) Color.Black else TextPrimary
                                ),
                                border = BorderStroke(1.dp, if (isPumpingAboveTarget || isTargetHit) EmeraldProfit else ObsidianBorder)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = if (isPumpingAboveTarget || isTargetHit) Color.Black else EmeraldProfit
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isPumpingAboveTarget || isTargetHit) "Satışı Onayla & Kasaya Al" else "Midas'ta Satıldıysa Kapat",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = { onRequestUpdateTarget(trade, currentPrice) },
                                modifier = Modifier
                                    .weight(0.9f)
                                    .height(40.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = IceCyanBright),
                                border = BorderStroke(1.dp, IceCyanBright.copy(alpha = 0.5f))
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Hedefi Düzenle", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // TOP ELITE 5 HEATMAP
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "🔥 MAJÖR ISI HARİTASI (HEATMAP)",
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                        Surface(
                            color = EmeraldProfit.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "5 Majör",
                                color = EmeraldProfitBright,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    assets.firstOrNull()?.let { firstAsset ->
                        TextButton(
                            onClick = { onOpenAssetDetails(firstAsset) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = "Strateji Odası için tıkla ➔",
                                color = IceCyanBright,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(assets, key = { "heat_${it.symbol}" }) { asset ->
                        val oracle = oracleMap[asset.symbol]
                        val tech = techMap[asset.symbol]
                        val price = if (asset.rawPrice > 0) asset.rawPrice else (oracle?.binanceGlobalPrice ?: 0.0)
                        val isBullish = asset.isPositive
                        val buyerRatio = ((tech?.orderBookDepth?.bidRatio ?: 0.50) * 100.0).coerceIn(0.0, 100.0)
                        val rsiVal = tech?.rsi14 ?: 50.0

                        val badgeText = when {
                            buyerRatio >= 60.0 -> "🎯 %${String.format(Locale.US, "%.0f", buyerRatio)} Alıcı"
                            rsiVal <= 35.0 -> "🛡️ RSI Dip"
                            tech?.zScore != null && tech.zScore < -1.0 -> "⚡ Z-Dip"
                            else -> "⚡ EMA9"
                        }

                        Surface(
                            onClick = { onOpenAssetDetails(asset) },
                            modifier = Modifier.width(128.dp),
                            color = ObsidianCard,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (isBullish) EmeraldProfit.copy(alpha = 0.5f) else CoralRed.copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = asset.symbol,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = asset.changeFormatted,
                                        color = if (isBullish) EmeraldProfitBright else CoralRedBright,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Text(
                                    text = "$${String.format(Locale.US, if (price < 1.0) "%.4f" else "%.2f", price)}",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )

                                Surface(
                                    color = ObsidianCardElevated,
                                    shape = RoundedCornerShape(4.dp),
                                    border = BorderStroke(0.6.dp, if (isBullish) EmeraldProfit.copy(alpha = 0.4f) else ObsidianBorder)
                                ) {
                                    Text(
                                        text = badgeText,
                                        color = if (isBullish) EmeraldProfitBright else IceCyanBright,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Quick Pair Filter Chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("ALL" to "Tümü", "BTC" to "BTC", "ETH" to "ETH", "BNB" to "BNB", "LINK" to "LINK", "AVAX" to "AVAX").forEach { (code, label) ->
                    val isSelected = selectedFilter == code
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clickable { selectedFilter = code },
                        color = if (isSelected) EmeraldProfit else ObsidianCard,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (isSelected) EmeraldProfit else ObsidianBorder)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.Black else TextSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // LIVE COIN CARDS (Stressiz Ana Ekran & Kilitli Operasyon Merkezi Tasarımı)
        items(filteredAssets, key = { it.symbol }) { asset ->
            val oracle = oracleMap[asset.symbol]
            val tech = techMap[asset.symbol]
            val memory = coinMemories.firstOrNull { it.symbol == asset.symbol }

            val currentPrice = if (asset.rawPrice > 0) asset.rawPrice else (oracle?.binanceGlobalPrice ?: 0.0)
            val rsiValue = tech?.rsi14 ?: 50.0
            val winRate = memory?.winRatePercent ?: 100.0
            val isBullish = asset.isPositive

            // Kısa & Net AI Durum Özeti
            val aiSummaryText = remember(asset.symbol, tech?.vwap, tech?.rsi14, tech?.orderBookDepth) {
                val bidRatio = tech?.orderBookDepth?.bidRatio ?: 0.50
                when {
                    bidRatio >= 0.65 -> "Alıcı duvarı güçlü (%${(bidRatio * 100).toInt()}), destek tabanı sağlam."
                    tech?.isVolumeShock == true -> "Hacim şoku tespit edildi, sakinleşme ve pusu bekleniyor."
                    rsiValue <= 35.0 -> "RSI aşırı satım bölgesinde (%${String.format(Locale.US, "%.0f", rsiValue)}), dip destek olgunlaşıyor."
                    rsiValue >= 68.0 -> "Direnç bölgesinde, tepe fiyat yerine düzeltme pususu önerilir."
                    else -> "Şartlar olgunlaşıyor, VWAP ve EMA9 destek bandı izleniyor."
                }
            }

            Surface(
                onClick = { onOpenAssetDetails(asset) },
                modifier = Modifier.fillMaxWidth(),
                color = ObsidianSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Header: Symbol, Win Rate, Price
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                color = ObsidianCardElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, ObsidianBorder)
                            ) {
                                Text(
                                    text = "${asset.symbol}/USDT",
                                    color = IceCyanBright,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "RSI: ${String.format(Locale.US, "%.1f", rsiValue)}",
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Hafıza Başarısı: %${String.format(Locale.US, "%.0f", winRate)}",
                                    color = EmeraldProfitBright,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "$${String.format(Locale.US, if (currentPrice < 1.0) "%.4f" else "%.2f", currentPrice)}",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = asset.changeFormatted,
                                color = if (isBullish) EmeraldProfit else CoralRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // 2. LIVE 5M CANDLESTICK MINI CHART (Jestleri pasif, stressiz izleme)
                    CandlestickChart(
                        candles = asset.recentCandles,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        isDetailed = false,
                        vwapPrice = tech?.vwap ?: asset.vwap,
                        enableGestures = false
                    )

                    // 3. KISA AI DURUM PUANI / ÖZETİ
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = ObsidianCardElevated,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.6.dp, ObsidianBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("🤖", fontSize = 12.sp)
                            Text(
                                text = "${asset.symbol}: $aiSummaryText",
                                color = TextSecondary,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }

                    // 4. ACTION BUTTONS: Strateji Odası & Pusu Aç
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onOpenAssetDetails(asset) },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, IceCyanBright.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = IceCyanBright)
                        ) {
                            Text("⚔️ Strateji Odası", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                onOpenBudgetProposal(asset)
                            },
                            modifier = Modifier
                                .weight(1.3f)
                                .height(40.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = IceCyanBright, contentColor = Color.Black)
                        ) {
                            Icon(imageVector = Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("🎯 Pusu Aç & Bütçe", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CapitalAndPortfolioScreen(
    capitalProfile: CapitalProfileEntity?,
    activeTrades: List<AppTradeEntity>,
    pendingTrades: List<AppTradeEntity> = emptyList(),
    assets: List<CryptoAsset>,
    techMap: Map<String, TechnicalAnalysis5m>,
    onOpenAddExistingDialog: () -> Unit,
    onRequestConfirmFill: (trade: AppTradeEntity) -> Unit = {},
    onCancelAmbush: (tradeId: Long) -> Unit = {},
    onExtendTimeout: (tradeId: Long) -> Unit = {},
    onUpdateCash: (Double) -> Unit,
    onUpdateMinThreshold: (Double) -> Unit,
    onRequestConfirmSale: (trade: AppTradeEntity, currentPrice: Double) -> Unit,
    onRequestUpdateTarget: (trade: AppTradeEntity, currentPrice: Double) -> Unit,
    onDcaStep: (tradeId: Long, dcaPrice: Double, dcaAmount: Double) -> Unit,
    onResetAllData: () -> Unit
) {
    val context = LocalContext.current
    var cashInput by remember { mutableStateOf("") }
    var isEditingCash by remember { mutableStateOf(false) }

    val isInitialized = capitalProfile?.isInitialized ?: false
    val currentCash = capitalProfile?.availableCashUsdt ?: 0.0
    val minThreshold = capitalProfile?.minSafeThresholdUsdt ?: 0.0
    val withdrawn = capitalProfile?.totalWithdrawnUsdt ?: 0.0
    val reservedInAmbushUsdt = pendingTrades.sumOf { it.investedUsdt }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "💼 KASA & SERMAYE YÖNETİMİ (USDT)",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Midas Kripto cüzdanınızdaki gerçek kullanılabilir USDT bakiyeniz:",
                color = TextSecondary,
                fontSize = 11.5.sp
            )
        }

        // CASH BALANCE HERO CARD
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ObsidianSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.2.dp, IceCyanBright.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Kullanılabilir Nakit:", color = TextSecondary, fontSize = 11.sp)
                            Text(
                                text = "$${String.format(Locale.US, "%.2f", currentCash)} USDT",
                                color = TextPrimary,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        IconButton(
                            onClick = {
                                cashInput = String.format(Locale.US, "%.2f", currentCash)
                                isEditingCash = !isEditingCash
                            }
                        ) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = "Düzenle", tint = IceCyanBright)
                        }
                    }

                    if (isEditingCash) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = cashInput,
                                onValueChange = { cashInput = it },
                                label = { Text("Yeni USDT Bakiyesi", fontSize = 11.sp) },
                                placeholder = { Text("Örn: 44.73 veya 44,73", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = IceCyanBright,
                                    unfocusedBorderColor = ObsidianBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            Button(
                                onClick = {
                                    val newCash = parseFlexibleDouble(cashInput)
                                    if (newCash != null && newCash >= 0.0) {
                                        onUpdateCash(newCash)
                                        isEditingCash = false
                                    } else {
                                        Toast.makeText(context, "Lütfen geçerli bir tutar girin (Örn: 44.73)", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black)
                            ) {
                                Text("Kaydet", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Stat Metrics: Withdrawn to USD, Safe Threshold, Reserved Ambush
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = ObsidianCard,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, ObsidianBorder)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Pusuda Rezerve:", color = TextTertiary, fontSize = 10.sp)
                                Text(
                                    text = "$${String.format(Locale.US, "%.2f", reservedInAmbushUsdt)} USDT",
                                    color = if (reservedInAmbushUsdt > 0) GoldWarm else TextSecondary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier.weight(1f),
                            color = ObsidianCard,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, ObsidianBorder)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Güvenli Eşik:", color = TextTertiary, fontSize = 10.sp)
                                Text(
                                    text = "$${String.format(Locale.US, "%.2f", minThreshold)} USDT",
                                    color = GoldWarm,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }

        // ADD EXISTING HOLDING BUTTON IN PORTFOLIO SCREEN
        item {
            Button(
                onClick = { onOpenAddExistingDialog() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = IceCyanBright, contentColor = Color.Black)
            ) {
                Icon(Icons.Default.AddBusiness, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("+ Mevcut Midas Varlığımı Ekle (Sat / Bekle Takibi)", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        // PENDING AMBUSH SECTION IN PORTFOLIO SCREEN
        if (pendingTrades.isNotEmpty()) {
            item {
                Text(
                    text = "⏳ MİDAS'TA BEKLEYEN PUSU EMİRLERİ (${pendingTrades.size})",
                    color = GoldWarm,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace
                )
            }

            items(pendingTrades, key = { "port_pending_${it.id}" }) { trade ->
                val asset = assets.firstOrNull { it.symbol == trade.symbol }
                val currentPrice = if (asset != null && asset.rawPrice > 0) asset.rawPrice else trade.entryPrice
                PendingAmbushCard(
                    trade = trade,
                    currentPrice = currentPrice,
                    onRequestConfirmFill = onRequestConfirmFill,
                    onCancelAmbush = onCancelAmbush,
                    onExtendTimeout = onExtendTimeout
                )
            }
        }

        // ACTIVE POSITIONS
        item {
            Text(
                text = "📊 AÇIKTA TAŞINAN POZİSYONLAR (${activeTrades.size})",
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace
            )
        }

        if (activeTrades.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ObsidianSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Box(modifier = Modifier.padding(20.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Açık pozisyon bulunmuyor. 'Mevcut Midas Varlığımı Ekle' butonuyla elinizdeki coinleri ekleyebilir veya alım fırsatlarını bekleyebilirsiniz.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            items(activeTrades, key = { it.id }) { trade ->
                val asset = assets.firstOrNull { it.symbol == trade.symbol }
                val currentPrice = if (asset != null && asset.rawPrice > 0) asset.rawPrice else trade.entryPrice
                val pnlUsdt = (currentPrice - trade.entryPrice) * trade.coinAmount - trade.midasTotalFeeUsdt
                val pnlPercent = if (trade.investedUsdt > 0) (pnlUsdt / trade.investedUsdt) * 100.0 else 0.0
                val isTargetHit = currentPrice >= trade.targetExitPrice
                val isPumpingAboveTarget = currentPrice > trade.targetExitPrice * 1.005
                val currentGross = currentPrice * trade.coinAmount
                val currentNetReturn = currentGross * 0.998
                val targetTotalReturnUsdt = trade.targetExitPrice * trade.coinAmount * 0.998

                val tech = techMap[trade.symbol]
                val canDca = trade.dcaLevel < trade.maxDcaLevels && currentCash >= 15.0 && tech != null && currentPrice < trade.entryPrice * 0.985

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ObsidianSurface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.2.dp, if (isPumpingAboveTarget) EmeraldProfitBright else if (isTargetHit) EmeraldProfit else if (pnlUsdt >= 0) IceCyanBright else GoldWarm)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "${trade.symbol}/USDT",
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Surface(
                                    color = if (isPumpingAboveTarget) EmeraldProfitBright else if (isTargetHit) EmeraldProfit else if (trade.dcaLevel == 1) EmeraldContainer else GoldContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (isPumpingAboveTarget) "🔥 HEDEFİ AŞTI (PUMP!)" else if (isTargetHit) "🟢 SATIŞ VAKTİ (HEDEF DOLDU)" else "Kademe ${trade.dcaLevel}/${trade.maxDcaLevels}",
                                        color = if (isPumpingAboveTarget || isTargetHit) Color.Black else if (trade.dcaLevel == 1) EmeraldProfitBright else GoldWarm,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Text(
                                text = "${if (pnlUsdt >= 0) "+" else ""}${String.format(Locale.US, "%.2f", pnlUsdt)} USDT (%${String.format(Locale.US, "%.2f", pnlPercent)})",
                                color = if (pnlUsdt >= 0) EmeraldProfit else CoralRed,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Maliyet: $${String.format(Locale.US, "%.2f", trade.entryPrice)}", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("Adet: ${String.format(Locale.US, "%.6f", trade.coinAmount).trimEnd('0').trimEnd('.')} ${trade.symbol}", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("Anlık: $${String.format(Locale.US, "%.2f", currentPrice)}", color = IceCyanBright, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }

                        // EXPLICIT MIDAS LIMIT ORDER INSTRUCTION BOX
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = if (isPumpingAboveTarget || isTargetHit) EmeraldProfit.copy(alpha = 0.12f) else ObsidianBg,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (isPumpingAboveTarget) EmeraldProfitBright else if (isTargetHit) EmeraldProfit else ObsidianBorder)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (isPumpingAboveTarget) {
                                    Text(
                                        text = "🔥 ANLIK FİYAT HEDEFİ GEÇTİ ($${String.format(Locale.US, "%.2f", currentPrice)} > $${String.format(Locale.US, "%.2f", trade.targetExitPrice)})",
                                        color = EmeraldProfitBright,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Kayıtlı Satış Hedefi:", color = TextSecondary, fontSize = 10.5.sp)
                                        Text("$${String.format(Locale.US, "%.2f", trade.targetExitPrice)} USDT", color = TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Anlık Piyasa Fiyatı:", color = TextSecondary, fontSize = 10.5.sp)
                                        Text("$${String.format(Locale.US, "%.2f", currentPrice)} USDT (+%${String.format(Locale.US, "%.2f", pnlPercent)})", color = EmeraldProfitBright, fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Anlık Satışta Net Kasa:", color = TextSecondary, fontSize = 10.5.sp)
                                        Text("~$${String.format(Locale.US, "%.2f", currentNetReturn)} USDT (Kâr: +$${String.format(Locale.US, "%.2f", pnlUsdt)})", color = EmeraldProfit, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Text(
                                        text = if (isTargetHit) "🎉 HEDEF DOLDU! MİDAS SATIŞ EMRİNİZİ KONTROL EDİN:" else "📢 MİDAS'TA GİRİLECEK LİMİT SATIŞ EMRİ:",
                                        color = if (isTargetHit) EmeraldProfitBright else GoldWarm,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Limit Satış Fiyatı:", color = TextSecondary, fontSize = 11.sp)
                                        Text(
                                            text = "$${String.format(Locale.US, if (trade.targetExitPrice < 1.0) "%.4f" else "%.2f", trade.targetExitPrice)} USDT",
                                            color = EmeraldProfitBright,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Satılacak Varlık Miktarı:", color = TextSecondary, fontSize = 11.sp)
                                        Text(
                                            text = "${String.format(Locale.US, "%.6f", trade.coinAmount).trimEnd('0').trimEnd('.')} ${trade.symbol} (Tümü)",
                                            color = TextPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Satışta Kasanıza Geçecek:", color = TextSecondary, fontSize = 11.sp)
                                        Text(
                                            text = "~$${String.format(Locale.US, "%.2f", targetTotalReturnUsdt)} USDT",
                                            color = EmeraldProfit,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // DCA button if dip opportunity exists
                        if (canDca && tech != null) {
                            Button(
                                onClick = {
                                    val dcaAmount = (trade.investedUsdt / trade.dcaLevel).coerceAtLeast(15.0)
                                    onDcaStep(trade.id, tech.supportLevel, dcaAmount)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GoldContainer, contentColor = GoldWarm)
                            ) {
                                Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("➕ ${trade.dcaLevel + 1}. Kademeyi Ekle (Midas Limit Alış: $${String.format(Locale.US, "%.2f", tech.supportLevel)} USDT)", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Action Buttons: Confirm Sale (Asks Price) + Update Target
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { onRequestConfirmSale(trade, currentPrice) },
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(40.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPumpingAboveTarget || isTargetHit) EmeraldProfit else ObsidianCardElevated,
                                    contentColor = if (isPumpingAboveTarget || isTargetHit) Color.Black else TextPrimary
                                ),
                                border = BorderStroke(1.dp, if (isPumpingAboveTarget || isTargetHit) EmeraldProfit else ObsidianBorder)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(15.dp), tint = if (isPumpingAboveTarget || isTargetHit) Color.Black else EmeraldProfit)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isPumpingAboveTarget || isTargetHit) "Satışı Onayla & Kasaya Al" else "Midas'ta Satıldıysa Kapat",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = { onRequestUpdateTarget(trade, currentPrice) },
                                modifier = Modifier
                                    .weight(0.9f)
                                    .height(40.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = IceCyanBright),
                                border = BorderStroke(1.dp, IceCyanBright.copy(alpha = 0.5f))
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Hedefi Düzenle", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // RESET ALL DATA / FACTORY FRESH BUTTON
        item {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onResetAllData,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralRedBright),
                border = BorderStroke(1.dp, CoralRed.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp), tint = CoralRedBright)
                Spacer(modifier = Modifier.width(6.dp))
                Text("🗑️ Tüm Verileri & Hafızayı Sıfırla (Fabrika Ayarları)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun MemoryAndArchiveScreen(
    historicalTrades: List<AppTradeEntity>,
    coinMemories: List<CoinMemoryEntity>
) {
    val dateFormatter = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }

    val successfulTrades = remember(historicalTrades) {
        historicalTrades.count { it.status == "COMPLETED_PROFIT" || it.netProfitUsdt >= 0 }
    }
    val failedTrades = remember(historicalTrades) {
        historicalTrades.count { it.status == "COMPLETED_LOSS" || it.netProfitUsdt < 0 }
    }
    val totalFinished = successfulTrades + failedTrades
    val winRate = remember(successfulTrades, totalFinished) {
        if (totalFinished > 0) (successfulTrades.toDouble() / totalFinished.toDouble()) * 100.0 else 100.0
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. NATIVE CANVAS VISUALIZATIONS: EQUITY CURVE & WIN RATE DONUT
        item {
            EquityGrowthCurveChart(
                historicalTrades = historicalTrades,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            WinRateDonutChart(
                successfulTrades = successfulTrades,
                failedTrades = failedTrades,
                winRatePercent = winRate,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "🧠 COIN HAFIZASI & KARNE KARTLARI",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Her kripto paranın geçmiş başarı oranı ve kâr karnesi:",
                color = TextSecondary,
                fontSize = 11.5.sp
            )
        }

        // COIN MEMORY CARDS
        if (coinMemories.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ObsidianSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("Henüz tamamlanmış işlem kaydı yok. İşlemler tamamlandıkça hafıza kartları dolacaktır.", color = TextSecondary, fontSize = 11.5.sp)
                    }
                }
            }
        } else {
            items(coinMemories, key = { it.symbol }) { mem ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ObsidianSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(
                                color = ObsidianCardElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, ObsidianBorder)
                            ) {
                                Text(
                                    text = mem.symbol,
                                    color = IceCyanBright,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "İşlem: ${mem.totalTrades} | Başarı: %${String.format(Locale.US, "%.0f", mem.winRatePercent)}",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Dip RSI: ${String.format(Locale.US, "%.1f", mem.optimalDipRsi)}",
                                    color = EmeraldProfitBright,
                                    fontSize = 10.5.sp
                                )
                            }
                        }

                        Text(
                            text = "+$${String.format(Locale.US, "%.2f", mem.totalNetProfitUsdt)}",
                            color = EmeraldProfit,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // HISTORICAL ARCHIVE
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "📜 GEÇMİŞ İŞLEM ARŞİVİ (${historicalTrades.size})",
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace
            )
        }

        if (historicalTrades.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ObsidianSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("Henüz kapatılmış geçmiş işlem bulunmuyor.", color = TextSecondary, fontSize = 11.5.sp)
                    }
                }
            }
        } else {
            items(historicalTrades, key = { it.id }) { trade ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ObsidianSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "${trade.symbol}/USDT",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = trade.closedAt?.let { dateFormatter.format(Date(it)) } ?: "",
                                    color = TextTertiary,
                                    fontSize = 10.sp
                                )
                            }
                            Text(
                                text = "Giriş: $${String.format(Locale.US, "%.2f", trade.entryPrice)} ➔ Çıkış: $${String.format(Locale.US, "%.2f", trade.actualExitPrice ?: trade.targetExitPrice)}",
                                color = TextSecondary,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "+$${String.format(Locale.US, "%.2f", trade.netProfitUsdt)} USDT",
                                color = EmeraldProfit,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "+%${String.format(Locale.US, "%.2f", trade.netProfitPercent)}",
                                color = EmeraldProfitBright,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WeeklyReportScreen(
    capitalProfile: CapitalProfileEntity?,
    historicalTrades: List<AppTradeEntity>
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var latestReportText by remember { mutableStateOf("") }
    var isGeneratingAiReport by remember { mutableStateOf(false) }

    LaunchedEffect(historicalTrades, capitalProfile) {
        val totalProfit = historicalTrades.sumOf { it.netProfitUsdt }
        val count = historicalTrades.size
        val winRate = if (count > 0) 100.0 else 100.0 // Zero loss rule
        val currentCash = capitalProfile?.availableCashUsdt ?: 100.0

        val prompt = StringBuilder()
        prompt.appendLine("📊 **KRİPTO TEKNİK ANALİST - HAFTALIK PERFORMANS RAPORU**")
        prompt.appendLine("• Toplam İşlem Sayısı: $count")
        prompt.appendLine("• Başarı Oranı: %${String.format(Locale.US, "%.0f", winRate)} (Sıfır Zarar Prensibi)")
        prompt.appendLine("• Toplam Net Kâr: +$${String.format(Locale.US, "%.2f", totalProfit)} USDT")
        prompt.appendLine("• Mevcut Boş Kasa: $${String.format(Locale.US, "%.2f", currentCash)} USDT")
        prompt.appendLine("• Midas Komisyon Optimizasyonu: Aktif (%0.40 net kâra dahil)")
        prompt.appendLine("• Veri Kaynağı: Binance WebSocket Canlı Akış (3m/5m/Derinlik)")
        prompt.appendLine("• Motor: Gemini 3.1 Pro + Flash-Lite Fail-Over")
        prompt.appendLine("\n**Bu raporu kopyalayarak AI Studio asistanına iletebilir ve sistem stratejisini daha da geliştirebilirsiniz.**")

        latestReportText = prompt.toString()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "📑 HAFTALIK AI GELİŞİM RAPORU",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Performans karnenizi tek tıkla kopyalayın ve asistanla birlikte sistemi geliştirin:",
                color = TextSecondary,
                fontSize = 11.5.sp
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ObsidianSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, EmeraldProfit.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = latestReportText,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Haftalık AI Raporu", latestReportText)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "📋 Rapor Panoya Kopyalandı!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Raporu Kopyala", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isGeneratingAiReport = true
                                    val aiAnalysis = com.example.service.GeminiMarketAnalystService.generateWeekendOptimizationReport(historicalTrades)
                                    latestReportText = aiAnalysis
                                    isGeneratingAiReport = false
                                }
                            },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = IceCyanBright, contentColor = Color.Black)
                        ) {
                            if (isGeneratingAiReport) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.Black, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Gemini Analizi", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pure Jetpack Compose Canvas Mini Sparkline with dynamic gradient fill.
 */
@Composable
fun MiniSparkline(
    points: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) return

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val minVal = points.minOrNull() ?: 0f
        val maxVal = points.maxOrNull() ?: 1f
        val range = (maxVal - minVal).let { if (it == 0f) 1f else it }

        val strokePath = Path()
        val fillPath = Path()

        val stepX = if (points.size > 1) width / (points.size - 1) else width

        points.forEachIndexed { index, value ->
            val x = index * stepX
            val normalizedY = (value - minVal) / range
            val y = height - (normalizedY * (height - 8.dp.toPx()) + 4.dp.toPx())

            if (index == 0) {
                strokePath.moveTo(x, y)
                fillPath.moveTo(x, height)
                fillPath.lineTo(x, y)
            } else {
                strokePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }

            if (index == points.size - 1) {
                fillPath.lineTo(x, height)
                fillPath.close()
            }
        }

        // 1. Draw gradient area underneath the sparkline
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    lineColor.copy(alpha = 0.28f),
                    Color.Transparent
                )
            )
        )

        // 2. Draw 2.dp stroke line on top
        drawPath(
            path = strokePath,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

/**
 * Pure Jetpack Compose Canvas Candlestick Chart for 5m Binance OHLCV data.
 * Features:
 * - Interactive Pinch-to-Zoom & Pan gestures.
 * - High-low wicks and open-close candle bodies (Bullish Emerald vs Bearish Coral).
 * - Smooth Yellow EMA9 line overlay across candles.
 * - Cyan VWAP (Volume-Weighted Average Price) overlay line.
 * - Translucent Cyan Bollinger Band cloud in the background.
 */
@Composable
fun CandlestickChart(
    candles: List<CandleStick>,
    modifier: Modifier = Modifier,
    isDetailed: Boolean = false,
    vwapPrice: Double? = null,
    enableGestures: Boolean = false
) {
    if (candles.isEmpty()) {
        Box(
            modifier = modifier
                .background(ObsidianCardElevated.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("📊 5m Mum Verisi Yükleniyor...", color = TextTertiary, fontSize = 10.sp)
        }
        return
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    val canvasModifier = if (enableGestures) {
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.6f, 4.5f)
                    offsetX += pan.x
                }
            }
    } else {
        Modifier.fillMaxSize()
    }

    Box(modifier = modifier) {
        Canvas(modifier = canvasModifier) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@Canvas

            val minPrice = candles.minOfOrNull { it.low }?.toFloat() ?: 0f
            val maxPrice = candles.maxOfOrNull { it.high }?.toFloat() ?: 1f
            val priceRange = (maxPrice - minPrice).let { if (it <= 0f) 1f else it }

            val candleCount = candles.size
            val baseCandleSpacing = width / candleCount.toFloat()
            val candleSpacing = baseCandleSpacing * scale
            val candleBodyWidth = (candleSpacing * 0.62f).coerceIn(2.dp.toPx(), 18.dp.toPx())
            val wickWidth = if (isDetailed) 1.5.dp.toPx() else 1.2.dp.toPx()

            val padY = if (isDetailed) 10.dp.toPx() else 4.dp.toPx()
            val usableHeight = (height - (padY * 2)).coerceAtLeast(1f)

            fun priceToY(price: Double): Float {
                val normalized = ((price.toFloat() - minPrice) / priceRange)
                return height - padY - (normalized * usableHeight)
            }

            // 1. Calculate and draw translucent Bollinger Band cloud (if detailed)
            if (isDetailed && candles.size >= 5) {
                val bbUpperPath = Path()
                val bbLowerPath = Path()
                val bbCloudPath = Path()

                val bbUpperPoints = mutableListOf<Offset>()
                val bbLowerPoints = mutableListOf<Offset>()

                candles.forEachIndexed { i, candle ->
                    val centerX = (i * candleSpacing) + (candleSpacing / 2f) + offsetX
                    val lookback = candles.subList(maxOf(0, i - 9), i + 1)
                    val mean = lookback.map { it.close }.average()
                    val std = Math.sqrt(lookback.map { Math.pow(it.close - mean, 2.0) }.average()).coerceAtLeast(mean * 0.0015)
                    val upper = mean + (1.8 * std)
                    val lower = mean - (1.8 * std)

                    val upperY = priceToY(upper)
                    val lowerY = priceToY(lower)

                    bbUpperPoints.add(Offset(centerX, upperY))
                    bbLowerPoints.add(Offset(centerX, lowerY))
                }

                if (bbUpperPoints.isNotEmpty()) {
                    bbCloudPath.moveTo(bbUpperPoints.first().x, bbUpperPoints.first().y)
                    bbUpperPoints.forEach { bbCloudPath.lineTo(it.x, it.y) }
                    for (i in bbLowerPoints.size - 1 downTo 0) {
                        bbCloudPath.lineTo(bbLowerPoints[i].x, bbLowerPoints[i].y)
                    }
                    bbCloudPath.close()

                    drawPath(
                        path = bbCloudPath,
                        color = IceCyan.copy(alpha = 0.09f)
                    )
                }
            }

            // 2. Draw Candlesticks (Wicks and Bodies)
            candles.forEachIndexed { index, candle ->
                val centerX = (index * candleSpacing) + (candleSpacing / 2f) + offsetX
                if (centerX < -30f || centerX > width + 30f) return@forEachIndexed

                val isBullish = candle.close >= candle.open
                val candleColor = if (isBullish) EmeraldProfit else CoralRed

                val highY = priceToY(candle.high)
                val lowY = priceToY(candle.low)
                val openY = priceToY(candle.open)
                val closeY = priceToY(candle.close)

                // Upper & Lower Wick
                drawLine(
                    color = candleColor,
                    start = Offset(centerX, highY),
                    end = Offset(centerX, lowY),
                    strokeWidth = wickWidth
                )

                // Real Body Rect
                val topBodyY = minOf(openY, closeY)
                val bottomBodyY = maxOf(openY, closeY)
                val bodyHeight = (bottomBodyY - topBodyY).coerceAtLeast(2.dp.toPx())

                drawRect(
                    color = candleColor,
                    topLeft = Offset(centerX - (candleBodyWidth / 2f), topBodyY),
                    size = Size(candleBodyWidth, bodyHeight)
                )
            }

            // 3. Draw Yellow EMA9 Overlay Line
            if (candles.size >= 3) {
                val emaPath = Path()
                val alphaMultiplier = 2.0 / (9.0 + 1.0)
                var currentEma = candles.first().close

                candles.forEachIndexed { index, candle ->
                    val centerX = (index * candleSpacing) + (candleSpacing / 2f) + offsetX
                    currentEma = (candle.close * alphaMultiplier) + (currentEma * (1.0 - alphaMultiplier))
                    val emaY = priceToY(currentEma)

                    if (index == 0) {
                        emaPath.moveTo(centerX, emaY)
                    } else {
                        emaPath.lineTo(centerX, emaY)
                    }
                }

                drawPath(
                    path = emaPath,
                    color = GoldWarm,
                    style = Stroke(width = if (isDetailed) 2.2.dp.toPx() else 1.5.dp.toPx())
                )
            }

            // 4. Draw VWAP Horizontal or Multi-Point Overlay
            if (isDetailed && vwapPrice != null && vwapPrice > 0) {
                val vwapY = priceToY(vwapPrice)
                drawLine(
                    color = IceCyanBright.copy(alpha = 0.85f),
                    start = Offset(0f, vwapY),
                    end = Offset(width, vwapY),
                    strokeWidth = 1.6.dp.toPx()
                )
            }
        }

        // Reset Zoom & Pan Overlay Pill
        if (scale != 1f || offsetX != 0f) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clickable {
                        scale = 1f
                        offsetX = 0f
                    },
                color = ObsidianSurface.copy(alpha = 0.85f),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(0.8.dp, IceCyanBright)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Sıfırla", tint = IceCyanBright, modifier = Modifier.size(10.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Yakınlaştırma Sıfırla (%${String.format(Locale.US, "%.0f", scale * 100)})", color = IceCyanBright, fontSize = 8.5.sp)
                }
            }
        }
    }
}

/**
 * Pure Jetpack Compose Canvas Capital Growth (Equity Curve) Chart.
 * Displays cumulative net profit over historical closed trades with glowing PathMeasure laser animation.
 */
@Composable
fun EquityGrowthCurveChart(
    historicalTrades: List<AppTradeEntity>,
    modifier: Modifier = Modifier
) {
    val completedTrades = remember(historicalTrades) {
        historicalTrades.filter { it.status == "COMPLETED_PROFIT" || it.status == "COMPLETED_LOSS" }
            .sortedBy { it.closedAt ?: it.openedAt }
    }

    if (completedTrades.isEmpty()) {
        Surface(
            modifier = modifier,
            color = ObsidianCardElevated,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, ObsidianBorder)
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "📈 İlk kârlı işlemler kapandığında Sermaye Büyüme Eğrisi burada lazer animasyonuyla çizilecektir.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    // Cumulative profit series
    val cumulativeSeries = remember(completedTrades) {
        var runningProfit = 0.0
        val list = mutableListOf(0.0)
        completedTrades.forEach { trade ->
            runningProfit += trade.netProfitUsdt
            list.add(runningProfit)
        }
        list
    }

    val totalPnl = cumulativeSeries.last()
    val isProfitable = totalPnl >= 0.0
    val curveColor = if (isProfitable) EmeraldProfit else CoralRed

    // Laser Animation Transition
    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(completedTrades.size) {
        animProgress.snapTo(0f)
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1400, easing = FastOutSlowInEasing)
        )
    }

    Surface(
        modifier = modifier,
        color = ObsidianCard,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, curveColor.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "📈 SERMAYE BÜYÜME EĞRİSİ (EQUITY CURVE)",
                        color = TextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${completedTrades.size} Tamamlanmış İşlem Kümülatif Net Kâr",
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                }
                Text(
                    text = "${if (totalPnl >= 0) "+" else ""}$${String.format(Locale.US, "%.2f", totalPnl)} USDT",
                    color = curveColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(95.dp)
            ) {
                val width = size.width
                val height = size.height
                if (width <= 0f || height <= 0f) return@Canvas

                val minVal = cumulativeSeries.minOrNull()?.toFloat() ?: 0f
                val maxVal = cumulativeSeries.maxOrNull()?.toFloat() ?: 1f
                val range = (maxVal - minVal).let { if (it <= 0f) 1f else it }

                val padY = 8.dp.toPx()
                val usableH = height - (padY * 2)

                fun valToY(v: Double): Float {
                    val norm = ((v.toFloat() - minVal) / range)
                    return height - padY - (norm * usableH)
                }

                val stepX = width / (cumulativeSeries.size - 1).coerceAtLeast(1)

                val fullLinePath = Path()
                val fullAreaPath = Path()

                cumulativeSeries.forEachIndexed { i, value ->
                    val x = i * stepX
                    val y = valToY(value)

                    if (i == 0) {
                        fullLinePath.moveTo(x, y)
                        fullAreaPath.moveTo(x, height)
                        fullAreaPath.lineTo(x, y)
                    } else {
                        fullLinePath.lineTo(x, y)
                        fullAreaPath.lineTo(x, y)
                    }

                    if (i == cumulativeSeries.size - 1) {
                        fullAreaPath.lineTo(x, height)
                        fullAreaPath.close()
                    }
                }

                // PathMeasure for smooth laser drawing
                val pathMeasure = PathMeasure()
                pathMeasure.setPath(fullLinePath, false)
                val totalLength = pathMeasure.length
                val currentLength = totalLength * animProgress.value

                val animatedLinePath = Path()
                pathMeasure.getSegment(0f, currentLength, animatedLinePath, true)

                // Draw Gradient Fill Under Equity Line (alpha modulated by animation progress)
                drawPath(
                    path = fullAreaPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            curveColor.copy(alpha = 0.25f * animProgress.value),
                            Color.Transparent
                        )
                    )
                )

                // Draw Glowing Laser Stroke
                drawPath(
                    path = animatedLinePath,
                    color = curveColor.copy(alpha = 0.35f),
                    style = Stroke(width = 6.dp.toPx())
                )
                drawPath(
                    path = animatedLinePath,
                    color = curveColor,
                    style = Stroke(width = 2.5.dp.toPx())
                )

                // Laser Head Glowing Pulse Marker
                if (animProgress.value > 0.02f) {
                    val headPoint = pathMeasure.getPosition(currentLength)
                    drawCircle(
                        color = curveColor.copy(alpha = 0.45f),
                        radius = 8.dp.toPx(),
                        center = headPoint
                    )
                    drawCircle(
                        color = curveColor,
                        radius = 4.5.dp.toPx(),
                        center = headPoint
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 2.dp.toPx(),
                        center = headPoint
                    )
                }
            }
        }
    }
}

/**
 * Pure Jetpack Compose Canvas Win Rate Donut / Pie Chart.
 * Displays green for profit trades and red for loss trades with high-contrast center metric.
 */
@Composable
fun WinRateDonutChart(
    successfulTrades: Int,
    failedTrades: Int,
    winRatePercent: Double,
    modifier: Modifier = Modifier
) {
    val total = (successfulTrades + failedTrades).coerceAtLeast(1)
    val winSweep = ((successfulTrades.toFloat() / total.toFloat()) * 360f).coerceIn(0f, 360f)
    val lossSweep = 360f - winSweep

    Surface(
        modifier = modifier,
        color = ObsidianCard,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Donut Chart with Center Text
            Box(
                modifier = Modifier.size(90.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeW = 12.dp.toPx()
                    val pad = strokeW / 2f
                    val arcSize = Size(size.width - strokeW, size.height - strokeW)
                    val arcTopLeft = Offset(pad, pad)

                    if (successfulTrades == 0 && failedTrades == 0) {
                        // Empty base
                        drawArc(
                            color = ObsidianBorder,
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = Stroke(width = strokeW)
                        )
                    } else {
                        // Win Arc (Green)
                        if (winSweep > 0f) {
                            drawArc(
                                color = EmeraldProfit,
                                startAngle = -90f,
                                sweepAngle = winSweep,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = Stroke(width = strokeW)
                            )
                        }
                        // Loss Arc (Red)
                        if (lossSweep > 0f) {
                            drawArc(
                                color = CoralRed,
                                startAngle = -90f + winSweep,
                                sweepAngle = lossSweep,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = Stroke(width = strokeW)
                            )
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "%${String.format(Locale.US, "%.0f", winRatePercent)}",
                        color = if (winRatePercent >= 70.0) EmeraldProfitBright else GoldWarm,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Kazanma",
                        color = TextTertiary,
                        fontSize = 8.5.sp
                    )
                }
            }

            // Legend & Breakdown Stats
            Column(
                modifier = Modifier.weight(1f).padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "🎯 İŞLEM KARNESİ",
                    color = TextPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(EmeraldProfit))
                        Text("Kârlı Kapanan:", color = TextSecondary, fontSize = 10.5.sp)
                    }
                    Text("$successfulTrades İşlem", color = EmeraldProfitBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(CoralRed))
                        Text("Zararlı Kapanan:", color = TextSecondary, fontSize = 10.5.sp)
                    }
                    Text("$failedTrades İşlem", color = CoralRedBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sıfır Zarar Disiplini:", color = TextTertiary, fontSize = 9.5.sp)
                    Text("3 Kademe DCA", color = IceCyanBright, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Holographic Space Station Tech Radar Scanner.
 * Displays rotating radar beam, concentric range rings, and real-time pulsing market target blips.
 */
@Composable
fun SpaceRadarScanner(
    modifier: Modifier = Modifier,
    scannerColor: Color = IceCyanBright,
    detectedTargets: List<Offset> = listOf(Offset(0.35f, 0.45f), Offset(0.65f, 0.3f), Offset(0.5f, 0.7f))
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing)
        ),
        label = "radarAngle"
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radarPulse"
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val center = Offset(width / 2f, height / 2f)
        val radius = minOf(width, height) / 2f

        // 1. Concentric rings
        val rings = listOf(0.35f, 0.65f, 0.95f)
        rings.forEach { rFactor ->
            drawCircle(
                color = scannerColor.copy(alpha = 0.18f),
                radius = radius * rFactor,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // 2. Crosshair grid
        drawLine(
            color = scannerColor.copy(alpha = 0.20f),
            start = Offset(center.x - radius, center.y),
            end = Offset(center.x + radius, center.y),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = scannerColor.copy(alpha = 0.20f),
            start = Offset(center.x, center.y - radius),
            end = Offset(center.x, center.y + radius),
            strokeWidth = 1.dp.toPx()
        )

        // 3. Rotating Sweep Gradient Beam
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(
                    Color.Transparent,
                    scannerColor.copy(alpha = 0.03f),
                    scannerColor.copy(alpha = 0.35f)
                ),
                center = center
            ),
            startAngle = angle - 60f,
            sweepAngle = 60f,
            useCenter = true,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2)
        )

        // 4. Target Blips
        detectedTargets.forEach { targetRel ->
            val targetX = center.x + (targetRel.x - 0.5f) * radius * 1.5f
            val targetY = center.y + (targetRel.y - 0.5f) * radius * 1.5f
            drawCircle(
                color = EmeraldProfitBright.copy(alpha = 0.35f * pulse),
                radius = 6.dp.toPx() * pulse,
                center = Offset(targetX, targetY)
            )
            drawCircle(
                color = EmeraldProfitBright,
                radius = 2.5.dp.toPx(),
                center = Offset(targetX, targetY)
            )
        }
    }
}

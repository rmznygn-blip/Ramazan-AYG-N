package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.*
import com.example.engine.ActionGuidance
import com.example.engine.AiAdvisorEngine
import com.example.model.BinanceOracleData
import com.example.model.CryptoAsset
import com.example.model.TechnicalAnalysis5m
import com.example.repository.CryptoMarketRepository
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

@Composable
fun CryptoAnalystMasterApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val dao = remember { db.appDao() }

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
    val historicalTrades by dao.getHistoricalTradesFlow().collectAsState(initial = emptyList())
    val coinMemories by dao.getAllCoinMemoriesFlow().collectAsState(initial = emptyList())
    val weeklyReports by dao.getAllWeeklyReportsFlow().collectAsState(initial = emptyList())

    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // Dialog state for 3-Tier Budget Allocation
    var showBudgetDialogForAsset by remember { mutableStateOf<CryptoAsset?>(null) }

    // Realtime AI action guidance synthesized across capital + live 5m indicators
    val realtimeGuidance = remember(capitalProfile, activeTrades, cryptoAssets, binanceOracleMap, technicalAnalysisMap) {
        AiAdvisorEngine.computeRealtimeGuidance(
            capitalProfile = capitalProfile,
            activeTrades = activeTrades,
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
                        Surface(
                            color = ObsidianCard,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, ObsidianBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = timeFormatter.format(Date(lastRefreshTime)),
                                    color = IceCyanBright,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                CryptoMarketRepository.refreshManually()
                                Toast.makeText(context, "🔄 Piyasa ve 5dk mumlar yenilendi", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = EmeraldProfit,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Yenile",
                                    tint = IceCyanBright,
                                    modifier = Modifier.size(20.dp)
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
                                if (activeTrades.isNotEmpty()) {
                                    Badge(containerColor = EmeraldProfit, contentColor = Color.Black) {
                                        Text("${activeTrades.size}")
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
                    assets = cryptoAssets,
                    oracleMap = binanceOracleMap,
                    techMap = technicalAnalysisMap,
                    coinMemories = coinMemories,
                    onOpenBudgetProposal = { asset ->
                        showBudgetDialogForAsset = asset
                    },
                    onCloseTrade = { tradeId, actualExit ->
                        coroutineScope.launch {
                            val closed = AiAdvisorEngine.closeActiveTrade(dao, tradeId, actualExit)
                            if (closed != null) {
                                Toast.makeText(context, "🏆 İşlem Kapatıldı: +${String.format(Locale.US, "%.2f", closed.netProfitUsdt)} USDT Net Kâr", Toast.LENGTH_LONG).show()
                            }
                        }
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
                    assets = cryptoAssets,
                    techMap = technicalAnalysisMap,
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
                    onCloseTrade = { tradeId, exitPrice ->
                        coroutineScope.launch {
                            AiAdvisorEngine.closeActiveTrade(dao, tradeId, exitPrice)
                        }
                    },
                    onDcaStep = { tradeId, dcaPrice, dcaAmount ->
                        coroutineScope.launch {
                            AiAdvisorEngine.executeDcaStep(dao, tradeId, dcaPrice, dcaAmount)
                        }
                    }
                )
                2 -> MemoryAndArchiveScreen(
                    historicalTrades = historicalTrades,
                    coinMemories = coinMemories
                )
                3 -> WeeklyReportScreen(
                    dao = dao,
                    capitalProfile = capitalProfile,
                    historicalTrades = historicalTrades,
                    weeklyReports = weeklyReports
                )
            }

            // 3-TIER BUDGET ALLOCATION MODAL DIALOG
            showBudgetDialogForAsset?.let { asset ->
                val tech = technicalAnalysisMap[asset.symbol]
                val oracle = binanceOracleMap[asset.symbol]
                val currentPrice = if (asset.rawPrice > 0) asset.rawPrice else (oracle?.binanceGlobalPrice ?: 0.0)
                val entryPrice = if (tech != null && tech.supportLevel > 0) tech.supportLevel else currentPrice * 0.985
                val availableCash = capitalProfile?.availableCashUsdt ?: 100.0

                // Recommend 60% of cash as total allocated pool, max $60, min $30
                val totalAllocatedPool = (availableCash * 0.60).coerceIn(30.0, 60.0).coerceAtMost(availableCash)
                val tier1Amount = totalAllocatedPool / 3.0
                val tier2Amount = totalAllocatedPool / 3.0
                val tier3Amount = totalAllocatedPool / 3.0
                val targetExit = entryPrice * (1.0 + (2.0 + 0.40) / 100.0)

                AlertDialog(
                    onDismissRequest = { showBudgetDialogForAsset = null },
                    containerColor = ObsidianCard,
                    shape = RoundedCornerShape(16.dp),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldProfit)
                            Text(
                                text = "${asset.symbol} Bütçe & Kademe Planı",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Kasanızın tamamı riske atılmaz. Sadece onaylayacağınız bu havuz 3 kademeli olarak kullanılır:",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )

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
                                    Text("• 1. Kademe (İlk Alım): $${String.format(Locale.US, "%.2f", tier1Amount)} USDT (Fiyat: $${String.format(Locale.US, "%.2f", entryPrice)})", color = EmeraldProfitBright, fontSize = 10.5.sp)
                                    Text("• 2. Kademe (Dip Destek): $${String.format(Locale.US, "%.2f", tier2Amount)} USDT (Gerekirse)", color = GoldWarm, fontSize = 10.5.sp)
                                    Text("• 3. Kademe (Son Savunma): $${String.format(Locale.US, "%.2f", tier3Amount)} USDT (Gerekirse)", color = IceCyanBright, fontSize = 10.5.sp)
                                    HorizontalDivider(color = ObsidianBorder, thickness = 0.5.dp)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("🛡️ Dokunulmayan Boş Kasa:", color = TextTertiary, fontSize = 10.5.sp)
                                        Text("$${String.format(Locale.US, "%.2f", (availableCash - totalAllocatedPool).coerceAtLeast(0.0))} USDT", color = EmeraldProfit, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Text(
                                text = "🎯 Hedef Çıkış: $${String.format(Locale.US, "%.2f", targetExit)} (Net +%2.0 kâr + %0.40 Midas komisyonu karşılanır)",
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
                                        status = "ACTIVE_OPEN"
                                    )
                                    dao.insertTrade(newTrade)
                                    val currProfile = dao.getCapitalProfileOnce() ?: CapitalProfileEntity()
                                    dao.saveCapitalProfile(currProfile.copy(availableCashUsdt = (currProfile.availableCashUsdt - tier1Amount).coerceAtLeast(0.0)))
                                    Toast.makeText(context, "✅ 1. Kademe Başlatıldı ($${String.format(Locale.US, "%.2f", tier1Amount)} USDT)", Toast.LENGTH_SHORT).show()
                                    showBudgetDialogForAsset = null
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Bütçeyi Onayla & Başlat", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBudgetDialogForAsset = null }) {
                            Text("Vazgeç", color = TextSecondary)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun LiveAssistantScreen(
    guidance: ActionGuidance,
    capitalProfile: CapitalProfileEntity?,
    activeTrades: List<AppTradeEntity>,
    assets: List<CryptoAsset>,
    oracleMap: Map<String, BinanceOracleData>,
    techMap: Map<String, TechnicalAnalysis5m>,
    coinMemories: List<CoinMemoryEntity>,
    onOpenBudgetProposal: (CryptoAsset) -> Unit,
    onCloseTrade: (tradeId: Long, actualExit: Double) -> Unit,
    onDcaStep: (tradeId: Long, dcaPrice: Double, dcaAmount: Double) -> Unit
) {
    val context = LocalContext.current
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
        // 1. TOP AI HERO "ŞİMDİ NE YAPMALIYIM?" LUXURY BANNER
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ObsidianSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.2.dp, Color(guidance.statusColorHex).copy(alpha = 0.8f))
            ) {
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
                        color = ObsidianBg,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(0.6.dp, ObsidianBorder)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(text = guidance.step1, color = TextPrimary.copy(alpha = 0.95f), fontSize = 11.5.sp, lineHeight = 16.sp)
                            Text(text = guidance.step2, color = TextPrimary.copy(alpha = 0.95f), fontSize = 11.5.sp, lineHeight = 16.sp)
                            Text(text = guidance.step3, color = TextPrimary.copy(alpha = 0.95f), fontSize = 11.5.sp, lineHeight = 16.sp)
                        }
                    }

                    // If active trade hit target, show instant close button
                    if (activeTrades.isNotEmpty()) {
                        val trade = activeTrades.first()
                        val asset = assets.firstOrNull { it.symbol == trade.symbol }
                        val currentPrice = if (asset != null && asset.rawPrice > 0) asset.rawPrice else trade.entryPrice

                        Button(
                            onClick = { onCloseTrade(trade.id, currentPrice) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black)
                        ) {
                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pozisyonu Kapat & Kârı Kasaya Al", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                listOf("ALL" to "Tümü", "SOL" to "SOL", "BTC" to "BTC", "ETH" to "ETH", "AVAX" to "AVAX", "XRP" to "XRP").forEach { (code, label) ->
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

        // LIVE COIN CARDS
        items(filteredAssets, key = { it.symbol }) { asset ->
            val oracle = oracleMap[asset.symbol]
            val tech = techMap[asset.symbol]
            val memory = coinMemories.firstOrNull { it.symbol == asset.symbol }

            val currentPrice = if (asset.rawPrice > 0) asset.rawPrice else (oracle?.binanceGlobalPrice ?: 0.0)

            // Limit order calculations
            val entryLimitPrice = if (tech != null && tech.supportLevel > 0) tech.supportLevel else currentPrice * 0.985
            val targetNetPercent = 2.0
            val targetExitPrice = entryLimitPrice * (1.0 + (targetNetPercent + 0.40) / 100.0)
            val rsiValue = tech?.rsi14 ?: 50.0
            val spread = oracle?.leadLagSpreadPercent ?: 0.0
            val winRate = memory?.winRatePercent ?: 100.0

            val distanceToEntryPct = if (currentPrice > entryLimitPrice) {
                ((currentPrice - entryLimitPrice) / currentPrice) * 100.0
            } else 0.0

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ObsidianSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Header: Symbol, Win Rate, Price
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
                                    text = "RSI: ${String.format(Locale.US, "%.1f", rsiValue)} • Öncü: %+${String.format(Locale.US, "%.2f", spread)}%",
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
                                color = if (asset.isPositive) EmeraldProfit else CoralRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // PROMINENT ENTRY & EXIT TARGET CARDS
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. LIMIT ENTRY (BUY) PRICE
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = EmeraldContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, EmeraldProfit.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("🟢 GİRİŞ (LİMİT AL)", color = EmeraldProfitBright, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text(
                                    text = "$${String.format(Locale.US, if (entryLimitPrice < 1.0) "%.4f" else "%.2f", entryLimitPrice)}",
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = if (distanceToEntryPct <= 0.3) "⚡ Alım Eşiğinde!" else "%${String.format(Locale.US, "%.2f", distanceToEntryPct)} yukarıda",
                                    color = if (distanceToEntryPct <= 0.3) EmeraldProfitBright else TextSecondary,
                                    fontSize = 9.5.sp
                                )
                            }
                        }

                        // 2. LIMIT EXIT (SELL / TAKE PROFIT) PRICE
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = CoralRedContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, CoralRed.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("🔴 ÇIKIŞ (KÂR AL)", color = CoralRedBright, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text(
                                    text = "$${String.format(Locale.US, if (targetExitPrice < 1.0) "%.4f" else "%.2f", targetExitPrice)}",
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "+%${String.format(Locale.US, "%.1f", targetNetPercent)} Net (Komisyonsuz)",
                                    color = EmeraldProfitBright,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // ACTION BUTTONS (Copy Buy, Copy Sell, Track Trade with Budget Approval)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText(
                                    "${asset.symbol} Limit Alış",
                                    String.format(Locale.US, if (entryLimitPrice < 1.0) "%.4f" else "%.2f", entryLimitPrice)
                                )
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "🟢 Alış Fiyatı Kopyalandı: $${String.format(Locale.US, "%.2f", entryLimitPrice)}", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldProfit),
                            border = BorderStroke(1.dp, EmeraldProfit)
                        ) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Alışı Kopyala", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText(
                                    "${asset.symbol} Limit Satış",
                                    String.format(Locale.US, if (targetExitPrice < 1.0) "%.4f" else "%.2f", targetExitPrice)
                                )
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "🔴 Satış Fiyatı Kopyalandı: $${String.format(Locale.US, "%.2f", targetExitPrice)}", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralRedBright),
                            border = BorderStroke(1.dp, CoralRed)
                        ) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Satışı Kopyala", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                onOpenBudgetProposal(asset)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = IceCyanBright, contentColor = Color.Black)
                        ) {
                            Icon(imageVector = Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Bütçe Ayır", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
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
    assets: List<CryptoAsset>,
    techMap: Map<String, TechnicalAnalysis5m>,
    onUpdateCash: (Double) -> Unit,
    onUpdateMinThreshold: (Double) -> Unit,
    onCloseTrade: (tradeId: Long, exitPrice: Double) -> Unit,
    onDcaStep: (tradeId: Long, dcaPrice: Double, dcaAmount: Double) -> Unit
) {
    var cashInput by remember { mutableStateOf("") }
    var isEditingCash by remember { mutableStateOf(false) }

    val currentCash = capitalProfile?.availableCashUsdt ?: 100.0
    val minThreshold = capitalProfile?.minSafeThresholdUsdt ?: 50.0
    val withdrawn = capitalProfile?.totalWithdrawnUsdt ?: 0.0

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
                                label = { Text("Yeni USDT Bakiyesi") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = IceCyanBright,
                                    unfocusedBorderColor = ObsidianBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            Button(
                                onClick = {
                                    val newCash = cashInput.toDoubleOrNull()
                                    if (newCash != null) {
                                        onUpdateCash(newCash)
                                        isEditingCash = false
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black)
                            ) {
                                Text("Kaydet", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Stat Metrics: Withdrawn to USD, Safe Threshold
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
                                Text("Çekilen Nakit (USD):", color = TextTertiary, fontSize = 10.sp)
                                Text(
                                    text = "$${String.format(Locale.US, "%.2f", withdrawn)} USDT",
                                    color = IceCyanBright,
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

        // ACTIVE POSITIONS
        item {
            Text(
                text = "📊 AÇIKTA TAŞINAN POZİSYONLAR",
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
                            text = "Açık pozisyon bulunmuyor. Tüm kasanız USDT olarak güvende.",
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

                val tech = techMap[trade.symbol]
                val canDca = trade.dcaLevel < trade.maxDcaLevels && currentCash >= 15.0 && tech != null && currentPrice < trade.entryPrice * 0.985

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ObsidianSurface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, if (pnlUsdt >= 0) EmeraldProfit else CoralRed)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "${trade.symbol}/USDT",
                                    color = IceCyanBright,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Surface(
                                    color = if (trade.dcaLevel == 1) EmeraldContainer else GoldContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "Kademe ${trade.dcaLevel}/${trade.maxDcaLevels}",
                                        color = if (trade.dcaLevel == 1) EmeraldProfitBright else GoldWarm,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Text(
                                text = "${if (pnlUsdt >= 0) "+" else ""}${String.format(Locale.US, "%.2f", pnlUsdt)} USDT (%${String.format(Locale.US, "%.2f", pnlPercent)})",
                                color = if (pnlUsdt >= 0) EmeraldProfit else CoralRed,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Maliyet: $${String.format(Locale.US, "%.2f", trade.entryPrice)}", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("•", color = TextTertiary, fontSize = 11.sp)
                            Text("Hedef: $${String.format(Locale.US, "%.2f", trade.targetExitPrice)}", color = EmeraldProfitBright, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("•", color = TextTertiary, fontSize = 11.sp)
                            Text("Anlık: $${String.format(Locale.US, "%.2f", currentPrice)}", color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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
                                    .height(34.dp),
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GoldContainer, contentColor = GoldWarm)
                            ) {
                                Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${trade.dcaLevel + 1}. Kademeyi Ekle (Maliyet Düşür: $${String.format(Locale.US, "%.2f", tech.supportLevel)})", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = { onCloseTrade(trade.id, currentPrice) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianCardElevated, contentColor = IceCyanBright)
                        ) {
                            Text("Midas'ta Satıldı Olarak Kapat & Kârı Al", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MemoryAndArchiveScreen(
    historicalTrades: List<AppTradeEntity>,
    coinMemories: List<CoinMemoryEntity>
) {
    val totalRealizedNetProfit = historicalTrades.sumOf { it.netProfitUsdt }
    val totalTrades = historicalTrades.size
    val profitableTrades = historicalTrades.count { it.status == "COMPLETED_PROFIT" }
    val winRate = if (totalTrades > 0) (profitableTrades.toDouble() / totalTrades.toDouble()) * 100.0 else 100.0

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "📜 SİSTEM HAFIZASI & KALICI ARŞİV",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Yapay zekânın geçmiş işlemlerden öğrendiği başarı istatistikleri:",
                color = TextSecondary,
                fontSize = 11.5.sp
            )
        }

        // Overall Memory Stats
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ObsidianSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, GoldWarm.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("KÜMÜLATİF HAFIZA PERFORMANSI", color = GoldWarm, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Toplam Net Kâr:", color = TextTertiary, fontSize = 10.sp)
                            Text(
                                text = "${if (totalRealizedNetProfit >= 0) "+" else ""}$${String.format(Locale.US, "%.2f", totalRealizedNetProfit)} USDT",
                                color = if (totalRealizedNetProfit >= 0) EmeraldProfit else CoralRed,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("Kazanma Oranı:", color = TextTertiary, fontSize = 10.sp)
                            Text(
                                text = "%${String.format(Locale.US, "%.1f", winRate)} ($profitableTrades/$totalTrades)",
                                color = IceCyanBright,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // COIN-BY-COIN LEARNING CARDS
        item {
            Text("🪙 KRİPTO BAZLI ÖĞRENME KARNELERİ", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }

        if (coinMemories.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ObsidianSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("İşlemler tamamlandıkça hafıza kartları burada birikir.", color = TextSecondary, fontSize = 11.sp)
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("${mem.symbol}/USDT Hafızası", color = IceCyanBright, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                            Text("Toplam İşlem: ${mem.totalTrades} (${mem.successfulTrades} Başarılı)", color = TextSecondary, fontSize = 10.5.sp)
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("Kazanma: %${String.format(Locale.US, "%.0f", mem.winRatePercent)}", color = EmeraldProfitBright, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text("Net: +$${String.format(Locale.US, "%.2f", mem.totalNetProfitUsdt)} USDT", color = TextPrimary, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // PAST TRADES HISTORY LIST
        item {
            Text("📜 GEÇMİŞ TAMAMLANAN İŞLEMLER", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }

        items(historicalTrades, key = { it.id }) { trade ->
            val isProfit = trade.status == "COMPLETED_PROFIT"
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ObsidianSurface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(0.8.dp, if (isProfit) EmeraldProfit.copy(alpha = 0.4f) else CoralRed.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("${trade.symbol}/USDT", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text("Giriş: $${String.format(Locale.US, "%.2f", trade.entryPrice)} ➔ Çıkış: $${String.format(Locale.US, "%.2f", trade.actualExitPrice ?: trade.targetExitPrice)}", color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${if (isProfit) "+" else ""}$${String.format(Locale.US, "%.2f", trade.netProfitUsdt)} USDT",
                            color = if (isProfit) EmeraldProfit else CoralRed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${if (isProfit) "+" else ""}%${String.format(Locale.US, "%.2f", trade.netProfitPercent)} Net",
                            color = if (isProfit) EmeraldProfit else CoralRed,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WeeklyReportScreen(
    dao: AppDatabaseDao,
    capitalProfile: CapitalProfileEntity?,
    historicalTrades: List<AppTradeEntity>,
    weeklyReports: List<WeeklyReportEntity>
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var latestGeneratedReport by remember { mutableStateOf<WeeklyReportEntity?>(null) }
    var isGenerating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (latestGeneratedReport == null) {
            isGenerating = true
            latestGeneratedReport = AiAdvisorEngine.generateWeeklyReport(dao, context)
            isGenerating = false
        }
    }

    val reportToDisplay = latestGeneratedReport ?: weeklyReports.firstOrNull()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "📊 HAFTALIK YAPAY ZEKÂ PERFORMANS RAPORU",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Bu raporu kopyalayıp asistana ileterek sistemin gelişimini sağlayabilirsiniz:",
                color = TextSecondary,
                fontSize = 11.5.sp
            )
        }

        // REPORT CARD CONTAINER
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ObsidianSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.2.dp, EmeraldProfit.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = reportToDisplay?.weekLabel ?: "Haftalık Rapor",
                            color = IceCyanBright,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                        if (isGenerating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = EmeraldProfit, strokeWidth = 2.dp)
                        }
                    }

                    // Markdown Preview Box
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = ObsidianBg,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(0.8.dp, ObsidianBorder)
                    ) {
                        Text(
                            text = reportToDisplay?.fullExportMarkdown ?: "Rapor hazırlanıyor...",
                            color = TextPrimary.copy(alpha = 0.95f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    // 1-TAP COPY REPORT BUTTON
                    Button(
                        onClick = {
                            val textToCopy = reportToDisplay?.fullExportMarkdown ?: ""
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Haftalık Kripto Raporu", textToCopy)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "📋 Haftalık Rapor Panoya Kopyalandı!", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldProfit, contentColor = Color.Black)
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Haftalık Raporu Kopyala", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    }

                    // RE-CALCULATE BUTTON
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                isGenerating = true
                                latestGeneratedReport = AiAdvisorEngine.generateWeeklyReport(dao, context)
                                isGenerating = false
                                Toast.makeText(context, "🔄 Rapor Yenilendi", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = IceCyanBright),
                        border = BorderStroke(1.dp, IceCyanBright)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Raporu Tekrar Hesapla", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

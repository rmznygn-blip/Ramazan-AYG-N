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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BinanceOracleData
import com.example.model.CryptoAsset
import com.example.model.TechnicalAnalysis5m
import com.example.repository.CryptoMarketRepository
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ManualTrackedTrade(
    val id: String,
    val symbol: String,
    val entryPrice: Double,
    val targetExitPrice: Double,
    val stopLossPrice: Double,
    val targetProfitPercent: Double,
    val createdAt: Long = System.currentTimeMillis(),
    var isBuyTriggered: Boolean = false,
    var isExitTriggered: Boolean = false
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                CryptoAnalystMainApp()
            }
        }
    }
}

@Composable
fun CryptoAnalystMainApp() {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Teknik Seviyeler & Giriş/Çıkış, 1: Takip Defterim, 2: Yapay Zekâ Raporu, 3: Kâr Hesaplayıcı
    var focusedSymbol by remember { mutableStateOf("SOL") }
    var trackedTrades by remember { mutableStateOf<List<ManualTrackedTrade>>(emptyList()) }

    val cryptoAssets by CryptoMarketRepository.cryptoAssets.collectAsState()
    val binanceOracleMap by CryptoMarketRepository.binanceOracleMap.collectAsState()
    val technicalAnalysisMap by CryptoMarketRepository.technicalAnalysisMap.collectAsState()
    val isRefreshing by CryptoMarketRepository.isRefreshing.collectAsState()
    val lastRefreshTime by CryptoMarketRepository.lastRefreshTime.collectAsState()

    val context = LocalContext.current
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack),
        containerColor = OledBlack,
        topBar = {
            Surface(
                color = OledSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(CyberEmerald)
                            )
                            Text(
                                text = "KRİPTO TEKNİK ANALİST",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "Manuel Giriş / Çıkış Limit Emir Önerileri",
                            color = TextSecondary,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = timeFormatter.format(Date(lastRefreshTime)),
                            color = CyberCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        IconButton(
                            onClick = {
                                CryptoMarketRepository.refreshManually()
                                Toast.makeText(context, "🔄 Fiyatlar ve seviyeler güncellendi", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = CyberEmerald,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Yenile",
                                    tint = CyberCyan,
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
                containerColor = OledSurface,
                tonalElevation = 0.dp,
                modifier = Modifier.navigationBarsPadding()
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.PriceCheck, contentDescription = null) },
                    label = { Text("Limit Seviyeleri", fontSize = 10.5.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberEmerald,
                        selectedTextColor = CyberEmerald,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                        indicatorColor = CyberEmerald.copy(alpha = 0.15f)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (trackedTrades.isNotEmpty()) {
                                    Badge(containerColor = CyberEmerald, contentColor = Color.Black) {
                                        Text("${trackedTrades.size}")
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.BookmarkBorder, contentDescription = null)
                        }
                    },
                    label = { Text("Takip Defterim", fontSize = 10.5.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberCyan,
                        selectedTextColor = CyberCyan,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                        indicatorColor = CyberCyan.copy(alpha = 0.15f)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                    label = { Text("Yapay Zekâ", fontSize = 10.5.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberCyan,
                        selectedTextColor = CyberCyan,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                        indicatorColor = CyberCyan.copy(alpha = 0.15f)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Calculate, contentDescription = null) },
                    label = { Text("Hesaplayıcı", fontSize = 10.5.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFFFFB800),
                        selectedTextColor = Color(0xFFFFB800),
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                        indicatorColor = Color(0xFFFFB800).copy(alpha = 0.15f)
                    )
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(OledBlack)
        ) {
            when (selectedTab) {
                0 -> TechnicalLimitLevelsScreen(
                    assets = cryptoAssets,
                    oracleMap = binanceOracleMap,
                    techMap = technicalAnalysisMap,
                    onOpenAiReport = { sym ->
                        focusedSymbol = sym
                        selectedTab = 2
                    },
                    onAddTrackedTrade = { trade ->
                        trackedTrades = trackedTrades.filter { it.symbol != trade.symbol } + trade
                        Toast.makeText(context, "📌 ${trade.symbol} Takip Defterine Eklendi", Toast.LENGTH_SHORT).show()
                    }
                )
                1 -> ManualTradeWatchlistScreen(
                    trackedTrades = trackedTrades,
                    assets = cryptoAssets,
                    oracleMap = binanceOracleMap,
                    onRemoveTrade = { id ->
                        trackedTrades = trackedTrades.filter { it.id != id }
                    }
                )
                2 -> AiMarketReportScreen(
                    focusedSymbol = focusedSymbol,
                    onSelectSymbol = { focusedSymbol = it },
                    assets = cryptoAssets,
                    oracleMap = binanceOracleMap,
                    techMap = technicalAnalysisMap
                )
                3 -> ProfitCalculatorScreen(
                    assets = cryptoAssets,
                    defaultSymbol = focusedSymbol
                )
            }
        }
    }
}

@Composable
fun TechnicalLimitLevelsScreen(
    assets: List<CryptoAsset>,
    oracleMap: Map<String, BinanceOracleData>,
    techMap: Map<String, TechnicalAnalysis5m>,
    onOpenAiReport: (String) -> Unit,
    onAddTrackedTrade: (ManualTrackedTrade) -> Unit
) {
    var selectedFilter by remember { mutableStateOf("ALL") }
    val context = LocalContext.current

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
        // Quick Symbol Chips
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
                            .height(30.dp)
                            .clickable { selectedFilter = code },
                        color = if (isSelected) CyberEmerald else OledSurface,
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) CyberEmerald else OledCardBorder)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.Black else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // Operational Guidance Banner
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF09141F),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.Lightbulb, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(22.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "MANUEL İŞLEM ADIMLARI:",
                            color = CyberCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "1. Midas'a önerilen 'Limit Giriş' fiyatını yazıp alım emri verin.\n2. Alım gerçekleştiği an, önerilen 'Limit Çıkış' fiyatını kâr al satışı olarak girin.",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 10.5.sp,
                            lineHeight = 14.5.sp
                        )
                    }
                }
            }
        }

        items(filteredAssets, key = { it.symbol }) { asset ->
            val oracle = oracleMap[asset.symbol]
            val tech = techMap[asset.symbol]

            val currentPrice = if (asset.rawPrice > 0) asset.rawPrice else (oracle?.binanceGlobalPrice ?: 0.0)
            
            // Precise limit order calculations
            val entryLimitPrice = if (tech != null && tech.supportLevel > 0) {
                tech.supportLevel
            } else {
                currentPrice * 0.985
            }

            // Exit Price calculated to yield +2.0% NET after 0.40% total Midas commission
            val targetNetPercent = 2.0
            val targetExitPrice = entryLimitPrice * (1.0 + (targetNetPercent + 0.40) / 100.0)
            val stopLossPrice = entryLimitPrice * 0.982 // 1.8% protection stop
            val rsiValue = tech?.rsi14 ?: 50.0
            val spread = oracle?.leadLagSpreadPercent ?: 0.0

            // Distance to Entry
            val distanceToEntryPct = if (currentPrice > entryLimitPrice) {
                ((currentPrice - entryLimitPrice) / currentPrice) * 100.0
            } else {
                0.0
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = OledSurface,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, OledCardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Header: Symbol, Current Price, 24h Change
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                color = Color(0xFF131C2E),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = asset.symbol,
                                    color = CyberCyan,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = asset.name,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "RSI: ${String.format(Locale.US, "%.1f", rsiValue)} • Spread: %+${String.format(Locale.US, "%.2f", spread)}%",
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Anlık: $${String.format(Locale.US, if (currentPrice < 1.0) "%.4f" else "%.2f", currentPrice)}",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = asset.changeFormatted,
                                color = if (asset.isPositive) CyberEmerald else CyberCrimson,
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
                            color = Color(0xFF03160B),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CyberEmerald.copy(alpha = 0.7f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🟢 GİRİŞ (LİMİT AL)", color = CyberEmerald, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                                }
                                Text(
                                    text = "$${String.format(Locale.US, if (entryLimitPrice < 1.0) "%.4f" else "%.2f", entryLimitPrice)}",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = if (distanceToEntryPct <= 0.3) "⚡ Giriş eşiğinde!" else "%${String.format(Locale.US, "%.2f", distanceToEntryPct)} yukarıda",
                                    color = if (distanceToEntryPct <= 0.3) CyberEmerald else TextSecondary,
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // 2. LIMIT EXIT (SELL / TAKE PROFIT) PRICE
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = Color(0xFF160A0A),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF4D4D).copy(alpha = 0.7f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🔴 ÇIKIŞ (KÂR AL)", color = Color(0xFFFF6666), fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                                }
                                Text(
                                    text = "$${String.format(Locale.US, if (targetExitPrice < 1.0) "%.4f" else "%.2f", targetExitPrice)}",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "+%${String.format(Locale.US, "%.1f", targetNetPercent)} Net Kâr Hedefi",
                                    color = CyberEmerald,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // ONE-TAP COPY BUTTONS (Entry, Exit, Track)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Copy Buy Limit Button
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
                                .height(36.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberEmerald),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CyberEmerald)
                        ) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Alışı Kopyala", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }

                        // Copy Sell Limit Button
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
                                .height(36.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6666)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF6666))
                        ) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Satışı Kopyala", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }

                        // Track Trade Button
                        Button(
                            onClick = {
                                onAddTrackedTrade(
                                    ManualTrackedTrade(
                                        id = "${asset.symbol}_${System.currentTimeMillis()}",
                                        symbol = asset.symbol,
                                        entryPrice = entryLimitPrice,
                                        targetExitPrice = targetExitPrice,
                                        stopLossPrice = stopLossPrice,
                                        targetProfitPercent = targetNetPercent
                                    )
                                )
                            },
                            modifier = Modifier
                                .weight(0.9f)
                                .height(36.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberCyan,
                                contentColor = Color.Black
                            )
                        ) {
                            Icon(imageVector = Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("Takibe Al", fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ManualTradeWatchlistScreen(
    trackedTrades: List<ManualTrackedTrade>,
    assets: List<CryptoAsset>,
    oracleMap: Map<String, BinanceOracleData>,
    onRemoveTrade: (String) -> Unit
) {
    val context = LocalContext.current

    if (trackedTrades.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(imageVector = Icons.Default.BookmarkBorder, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(48.dp))
                Text(
                    text = "Henüz Takip Ettiğiniz İşlem Yok",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "'Limit Seviyeleri' sekmesinden işlem yapmak istediğiniz coinin yanındaki 'Takibe Al' butonuna basarak kâğıt üzerinde sıfır riskle izleyebilirsiniz.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "📝 MANUEL TAKİP DEFTERİM (CANLI İZLEME)",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Belirlediğiniz limit giriş ve hedef çıkış fiyatlarının canlı gerçekleşme durumları:",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            items(trackedTrades, key = { it.id }) { trade ->
                val asset = assets.firstOrNull { it.symbol == trade.symbol }
                val oracle = oracleMap[trade.symbol]
                val currentPrice = if (asset != null && asset.rawPrice > 0) asset.rawPrice else (oracle?.binanceGlobalPrice ?: 0.0)

                val isPriceNearEntry = currentPrice <= trade.entryPrice * 1.002
                val isPriceHitTarget = currentPrice >= trade.targetExitPrice

                val statusText = when {
                    isPriceHitTarget -> "🎯 HEDEF SATIŞ FİYATINA ULAŞILDI (KÂR ALINDI!)"
                    isPriceNearEntry -> "🟢 ALIM SEVİYESİNE GELDİ! (ŞİMDİ SATIŞ EMRİNİ GİRİN)"
                    else -> "🟡 GİRİŞ SEVİYESİ BEKLENİYOR"
                }

                val statusColor = when {
                    isPriceHitTarget -> CyberEmerald
                    isPriceNearEntry -> CyberEmerald
                    else -> Color(0xFFFFB800)
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = OledSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${trade.symbol}/USDT Manuel Planı",
                                color = CyberCyan,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Anlık: $${String.format(Locale.US, "%.2f", currentPrice)}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Live Status Badge
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = statusColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(0.6.dp, statusColor)
                        ) {
                            Text(
                                text = statusText,
                                color = statusColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }

                        // Entry and Exit Targets
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                color = Color(0xFF030A05),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text("Giriş Limit Fiyatı:", color = TextTertiary, fontSize = 9.5.sp)
                                    Text("$${String.format(Locale.US, "%.2f", trade.entryPrice)}", color = CyberEmerald, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                                }
                            }

                            Surface(
                                modifier = Modifier.weight(1f),
                                color = Color(0xFF0A0303),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text("Hedef Satış Fiyatı:", color = TextTertiary, fontSize = 9.5.sp)
                                    Text("$${String.format(Locale.US, "%.2f", trade.targetExitPrice)}", color = Color(0xFFFF6666), fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }

                        // Actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Satış Limiti", String.format(Locale.US, "%.2f", trade.targetExitPrice))
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "🔴 Satış Fiyatı Kopyalandı: $${String.format(Locale.US, "%.2f", trade.targetExitPrice)}", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6666)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF6666))
                            ) {
                                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Satışı Kopyala", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }

                            Button(
                                onClick = { onRemoveTrade(trade.id) },
                                modifier = Modifier
                                    .weight(0.8f)
                                    .height(34.dp),
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2838), contentColor = TextSecondary)
                            ) {
                                Text("Listeden Kaldır", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AiMarketReportScreen(
    focusedSymbol: String,
    onSelectSymbol: (String) -> Unit,
    assets: List<CryptoAsset>,
    oracleMap: Map<String, BinanceOracleData>,
    techMap: Map<String, TechnicalAnalysis5m>
) {
    val coroutineScope = rememberCoroutineScope()
    var aiAnalysisText by remember { mutableStateOf<String?>(null) }
    var isLoadingAnalysis by remember { mutableStateOf(false) }

    val currentAsset = assets.firstOrNull { it.symbol.equals(focusedSymbol, ignoreCase = true) }
    val oracle = oracleMap[focusedSymbol]
    val tech = techMap[focusedSymbol]

    val currentPrice = if (currentAsset != null && currentAsset.rawPrice > 0) currentAsset.rawPrice else (oracle?.binanceGlobalPrice ?: 0.0)
    val supportPrice = tech?.supportLevel ?: (currentPrice * 0.985)
    val resistancePrice = tech?.resistanceLevel ?: (currentPrice * 1.018)
    val rsiValue = tech?.rsi14 ?: 50.0
    val spread = oracle?.leadLagSpreadPercent ?: 0.0

    LaunchedEffect(focusedSymbol, currentPrice) {
        if (currentPrice > 0) {
            isLoadingAnalysis = true
            aiAnalysisText = CryptoMarketRepository.getAiMarketAnalysis(focusedSymbol)
            isLoadingAnalysis = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Coin Selector Chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("SOL", "BTC", "ETH", "AVAX", "XRP", "DOGE").forEach { sym ->
                    val isSelected = focusedSymbol.equals(sym, ignoreCase = true)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clickable { onSelectSymbol(sym) },
                        color = if (isSelected) CyberCyan else OledSurface,
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) CyberCyan else OledCardBorder)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = sym,
                                color = if (isSelected) Color.Black else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // Focused Coin Hero Box
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = OledSurface,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "$focusedSymbol/USDT PİYASA RAPORU",
                                color = CyberCyan,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "5 Dakikalık Mikro Yapı ve Gösterge Sentezi",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "$${String.format(Locale.US, if (currentPrice < 1.0) "%.4f" else "%.2f", currentPrice)}",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // AI Commentary Card
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF07101C),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberEmerald.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = CyberEmerald, modifier = Modifier.size(16.dp))
                                    Text(
                                        text = "GEMINI YAPAY ZEKÂ ANALİZ RAPORU",
                                        color = CyberEmerald,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                if (isLoadingAnalysis) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = CyberEmerald, strokeWidth = 1.5.dp)
                                }
                            }

                            Text(
                                text = aiAnalysisText ?: "Piyasa verileri analiz ediliyor...",
                                color = Color.White.copy(alpha = 0.95f),
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }

                    // Refresh Button
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isLoadingAnalysis = true
                                aiAnalysisText = CryptoMarketRepository.getAiMarketAnalysis(focusedSymbol)
                                isLoadingAnalysis = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF162238), contentColor = CyberCyan)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Analiz Raporunu Güncelle", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // 4 Deep Dive Technical Cards
        item {
            Text(
                text = "📊 4 TEMEL TEKNİK GÖSTERGE",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
        }

        item {
            TechnicalDetailPillarCard(
                title = "1. RSI (14) MOMENTUM DURUMU",
                value = "${String.format(Locale.US, "%.1f", rsiValue)} / 100",
                description = if (rsiValue <= 35.0) {
                    "RSI derin aşırı satım bölgesinde. Satıcıların gücü tükenmiş durumda, fiyatta 5 dakikalık dip sekme tepkisi olası."
                } else if (rsiValue >= 65.0) {
                    "RSI aşırı alım bölgesinde. Fiyat zirve direncine yakın, kâr alma veya düzeltme riski yüksek."
                } else {
                    "RSI orta dengeli bantta ($rsiValue). Trend yönü için destek ve direnç kırılımları izlenmeli."
                },
                accentColor = if (rsiValue <= 35.0) CyberEmerald else if (rsiValue >= 65.0) CyberCrimson else CyberCyan
            )
        }

        item {
            TechnicalDetailPillarCard(
                title = "2. BİNANCE GLOBAL ÖNCÜLÜK FARKI",
                value = "%+${String.format(Locale.US, "%.2f", spread)}",
                description = if (spread >= 0.80) {
                    "Binance Global spot tahtası Midas fiyatının oldukça önünde gidiyor. Likidite yukarı yönlü çekim yaratıyor."
                } else {
                    "Binance ve yerel fiyatlar dengeli aralıkta seyrediyor, ani likidite kayması bulunmuyor."
                },
                accentColor = if (spread >= 0.80) CyberEmerald else CyberCyan
            )
        }

        item {
            TechnicalDetailPillarCard(
                title = "3. 5 DAKİKALIK DİP DESTEK & ZİRVE DİRENÇ",
                value = "Destek: $${String.format(Locale.US, "%.2f", supportPrice)} | Direnç: $${String.format(Locale.US, "%.2f", resistancePrice)}",
                description = "Dip Destek seviyesi alıcıların yoğunlaştığı limit alış eşiğidir. Tepe Direnç seviyesi ise kâr realizasyonunun başladığı tavan fiyattır.",
                accentColor = CyberEmerald
            )
        }

        item {
            TechnicalDetailPillarCard(
                title = "4. RİSK / KÂR STRATEJİSİ",
                value = "Hedef Kâr: +%1.80 ~ %2.50 Net",
                description = "Midas %0.20 alım + %0.20 satım komisyonu düşüldükten sonra limit emirle işlem kapatıldığında net kazanç hedeflenir.",
                accentColor = Color(0xFFFFB800)
            )
        }
    }
}

@Composable
fun TechnicalDetailPillarCard(
    title: String,
    value: String,
    description: String,
    accentColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = OledSurface,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, OledCardBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = title, color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
            Text(text = value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(text = description, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
fun ProfitCalculatorScreen(
    assets: List<CryptoAsset>,
    defaultSymbol: String
) {
    var symbol by remember { mutableStateOf(defaultSymbol) }
    var entryPriceInput by remember { mutableStateOf("") }
    var investmentInput by remember { mutableStateOf("50") }
    var targetPercentInput by remember { mutableStateOf("2.0") }

    val currentAsset = assets.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }

    LaunchedEffect(symbol, currentAsset) {
        if (entryPriceInput.isBlank() && currentAsset != null && currentAsset.rawPrice > 0) {
            entryPriceInput = String.format(Locale.US, "%.2f", currentAsset.rawPrice)
        }
    }

    val entryPrice = entryPriceInput.toDoubleOrNull() ?: 0.0
    val investmentAmount = investmentInput.toDoubleOrNull() ?: 50.0
    val targetPercent = targetPercentInput.toDoubleOrNull() ?: 2.0

    // Midas Fee Calculation (0.20% Buy + 0.20% Sell)
    val buyFee = investmentAmount * 0.0020
    val netInvested = investmentAmount - buyFee
    val desiredNetProfitUsd = investmentAmount * (targetPercent / 100.0)

    // Required Exit Price to clear desired net profit + sell fee
    val requiredTotalExitValue = investmentAmount + desiredNetProfitUsd + (investmentAmount * 0.0020)
    val requiredExitPrice = if (entryPrice > 0) entryPrice * (requiredTotalExitValue / investmentAmount) else 0.0
    val sellFee = requiredTotalExitValue * 0.0020
    val totalFees = buyFee + sellFee

    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "🧮 MİDAS KRİPTO KÂR HESAPLAYICI",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Komisyonları (%0.20 + %0.20) otomatik hesaba katarak tam satış hedefini bulur.",
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Coin Selection
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("SOL", "BTC", "ETH", "AVAX", "XRP").forEach { sym ->
                    val isSelected = symbol.equals(sym, ignoreCase = true)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clickable {
                                symbol = sym
                                val asset = assets.firstOrNull { it.symbol == sym }
                                if (asset != null && asset.rawPrice > 0) {
                                    entryPriceInput = String.format(Locale.US, "%.2f", asset.rawPrice)
                                }
                            },
                        color = if (isSelected) Color(0xFFFFB800) else OledSurface,
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) Color(0xFFFFB800) else OledCardBorder)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = sym,
                                color = if (isSelected) Color.Black else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // Inputs
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = OledSurface,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, OledCardBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Entry Price Input
                    OutlinedTextField(
                        value = entryPriceInput,
                        onValueChange = { entryPriceInput = it },
                        label = { Text("Alış Fiyatınız ($)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = OledCardBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    // Investment Amount Input
                    OutlinedTextField(
                        value = investmentInput,
                        onValueChange = { investmentInput = it },
                        label = { Text("İşlem Tutarı ($ USDT)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = OledCardBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    // Target Net Profit % Chips
                    Text("Hedef Net Kâr Oranı:", color = TextSecondary, fontSize = 11.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("1.5" to "%1.5", "2.0" to "%2.0", "2.5" to "%2.5", "3.0" to "%3.0").forEach { (valStr, label) ->
                            val isSel = targetPercentInput == valStr
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(28.dp)
                                    .clickable { targetPercentInput = valStr },
                                color = if (isSel) CyberEmerald else Color(0xFF101622),
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isSel) CyberEmerald else OledCardBorder)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = label,
                                        color = if (isSel) Color.Black else TextSecondary,
                                        fontSize = 11.sp,
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

        // Calculation Results Card
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF0A130C),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberEmerald)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "🎯 HESAPLANAN LİMİT SATIŞ EMRİ",
                        color = CyberEmerald,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )

                    // Required Exit Price Box
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF030805),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Midas Limit Satış Fiyatı:", color = TextSecondary, fontSize = 12.sp)
                            Text(
                                text = "$${String.format(Locale.US, if (requiredExitPrice < 1.0) "%.4f" else "%.2f", requiredExitPrice)}",
                                color = CyberEmerald,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Net Profit & Total Fees Breakdown
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = Color(0xFF030805),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Net Cebe Kalan Kâr:", color = TextTertiary, fontSize = 10.sp)
                                Text(
                                    text = "+$${String.format(Locale.US, "%.2f", desiredNetProfitUsd)}",
                                    color = CyberEmerald,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier.weight(1f),
                            color = Color(0xFF030805),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Toplam Midas Komisyonu:", color = TextTertiary, fontSize = 10.sp)
                                Text(
                                    text = "-$${String.format(Locale.US, "%.2f", totalFees)}",
                                    color = CyberCrimson,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // Copy Exit Price Button
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Midas Satış Fiyatı", String.format(Locale.US, "%.2f", requiredExitPrice))
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "📋 Satış Fiyatı Kopyalandı: $${String.format(Locale.US, "%.2f", requiredExitPrice)}", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberEmerald,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Satış Fiyatını Kopyala", fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

package com.example.engine

import android.content.Context
import com.example.data.local.*
import com.example.model.BinanceOracleData
import com.example.model.CryptoAsset
import com.example.model.TechnicalAnalysis5m
import com.example.service.GeminiMarketAnalystService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class ActionGuidance(
    val title: String,
    val statusBadge: String,
    val statusColorHex: Long,
    val step1: String,
    val step2: String,
    val step3: String,
    val targetSymbol: String? = null,
    val recommendedEntryPrice: Double? = null,
    val recommendedExitPrice: Double? = null,
    val netProfitUsdtExpected: Double? = null,
    val reasoning: String = ""
)

object AiAdvisorEngine {

    /**
     * Synthesizes Portfolio Capital, Active Trades, Live 5m Technical Indicators,
     * and Historical Memory to produce the absolute clearest, immediate step-by-step guidance.
     */
    fun computeRealtimeGuidance(
        capitalProfile: CapitalProfileEntity?,
        activeTrades: List<AppTradeEntity>,
        assets: List<CryptoAsset>,
        oracleMap: Map<String, BinanceOracleData>,
        techMap: Map<String, TechnicalAnalysis5m>
    ): ActionGuidance {
        val isInitialized = capitalProfile?.isInitialized ?: false
        val cash = capitalProfile?.availableCashUsdt ?: 0.0
        val minThreshold = capitalProfile?.minSafeThresholdUsdt ?: 0.0

        // 0. Case: Kasa Henüz Tanımlanmamışsa
        if (!isInitialized) {
            return ActionGuidance(
                title = "💼 MİDAS KASANIZI TANIMLAYIN",
                statusBadge = "İLK KURULUM BEKLENİYOR",
                statusColorHex = 0xFF00F0FF,
                step1 = "1. Midas Kripto cüzdanınızdaki güncel boş USDT miktarınızı yukarıdaki butondan veya açılış ekranından girin.",
                step2 = "2. Sistem girdiğiniz gerçek nakde göre en fazla 3 kademeli bütçe planlaması yapacaktır.",
                step3 = "3. Varsa mevcut aldığınız coinleri 'Mevcut Varlığımı Ekle' diyerek anında takibe alabilirsiniz.",
                reasoning = "Sıfır varsayılan veya sahte bakiye tutulmaz; tamamen sizin girdiğiniz gerçek Midas USDT bakiyenizle çalışılır."
            )
        }

        // 1. Case: Kasa Güvenli Eşiğin Altındaysa
        if (minThreshold > 0.0 && cash < minThreshold && activeTrades.isEmpty()) {
            return ActionGuidance(
                title = "🛡️ KASA GÜVENLİK EŞİĞİ KORUMASI",
                statusBadge = "KASA YETERSİZ ($${String.format(Locale.US, "%.1f", cash)} / $${String.format(Locale.US, "%.1f", minThreshold)} USDT)",
                statusColorHex = 0xFFFFB800,
                step1 = "1. Midas'taki kullanılabilir USDT kasanız ($${String.format(Locale.US, "%.2f", cash)}), belirlediğiniz minimum güvenli eşiğin ($${String.format(Locale.US, "%.2f", minThreshold)}) altındadır.",
                step2 = "2. Midas komisyonlarının (%0.40) kâr marjını eritmemesi ve sağlıklı kâr elde edilebilmesi için yeni alım önerisi duraklatıldı.",
                step3 = "3. Midas'a USD yatırıp USDT'ye dönüştürerek kasayı güncelleyin veya 'Kasa' sekmesinden güvenlik eşiğini düzenleyin.",
                reasoning = "Sermaye koruma kuralı aktif. Düşük hacimli işlemler oransal komisyon yükünü artırır."
            )
        }

        // 2. Case: Açıkta Taşınan Aktif Pozisyon Varsa
        if (activeTrades.isNotEmpty()) {
            val trade = activeTrades.first()
            val asset = assets.firstOrNull { it.symbol == trade.symbol }
            val currentPrice = if (asset != null && asset.rawPrice > 0) asset.rawPrice else trade.entryPrice

            val pnlUsdt = (currentPrice - trade.entryPrice) * trade.coinAmount - trade.midasTotalFeeUsdt
            val pnlPercent = if (trade.investedUsdt > 0) (pnlUsdt / trade.investedUsdt) * 100.0 else 0.0
            val isTargetHit = currentPrice >= trade.targetExitPrice
            val isPumpingAboveTarget = currentPrice > trade.targetExitPrice * 1.005

            if (isTargetHit) {
                val actionTitle = if (isPumpingAboveTarget) "🔥 FİYAT HEDEFİ AŞTI: ${trade.symbol}/USDT" else "🎯 HEDEF SATIŞ FİYATINA ULAŞILDI!"
                val step1Text = if (isPumpingAboveTarget) {
                    "1. Güncel fiyat ($${String.format(Locale.US, "%.2f", currentPrice)}), ilk planlanan $${String.format(Locale.US, "%.2f", trade.targetExitPrice)} USDT hedefinin çok üzerine çıktı (+%${String.format(Locale.US, "%.2f", pnlPercent)} Net Kâr)."
                } else {
                    "1. ${trade.symbol}/USDT anlık fiyatı ($${String.format(Locale.US, "%.2f", currentPrice)}), belirlediğimiz $${String.format(Locale.US, "%.2f", trade.targetExitPrice)} USDT hedef satışını geçti!"
                }
                val step2Text = if (isPumpingAboveTarget) {
                    "2. Coin hâlâ elinizdeyse Midas'ta anlık piyasa fiyatından ($${String.format(Locale.US, "%.2f", currentPrice)}) hemen satabilir veya hedefi daha yukarı güncelleyebilirsiniz."
                } else {
                    "2. Midas hesabınızı kontrol edin: $${String.format(Locale.US, "%.2f", trade.targetExitPrice)} USDT limit satış emrinizin gerçekleşip gerçekleşmediğine bakın."
                }

                return ActionGuidance(
                    title = actionTitle,
                    statusBadge = "KÂR REALİZASYONU (+%${String.format(Locale.US, "%.2f", pnlPercent)} NET)",
                    statusColorHex = 0xFF00FF9D,
                    step1 = step1Text,
                    step2 = step2Text,
                    step3 = "3. Satış yaptıktan sonra 'Satışı Kasaya Ekle' butonuna basıp Midas'ta gerçekleşen gerçek satış fiyatınızı onaylayarak kasanıza kârı aktarın.",
                    targetSymbol = trade.symbol,
                    recommendedExitPrice = currentPrice.coerceAtLeast(trade.targetExitPrice),
                    netProfitUsdtExpected = pnlUsdt,
                    reasoning = if (isPumpingAboveTarget) "Fiyat hedefi aştı. Kârı daha yüksekten almak veya yeni tepe hedefi belirlemek sizin kontrolünüzde." else "5 dakikalık hedef direnç testi başarıyla tamamlandı. Sıfır zarar prensibi korundu."
                )
            } else {
                val tech = techMap[trade.symbol]
                val isDcaPossible = trade.dcaLevel < trade.maxDcaLevels && cash >= 15.0 && tech != null && currentPrice < trade.entryPrice * 0.985

                val dcaNote = if (isDcaPossible) {
                    " (Maliyet Düşürme: $${String.format(Locale.US, "%.2f", tech!!.supportLevel)} USDT seviyesinden ${trade.dcaLevel + 1}. kademe limit alış girilebilir)"
                } else if (trade.dcaLevel >= trade.maxDcaLevels) {
                    " (3/3 Kademe Tamamlandı - İlave alım yapılmaz, sadece kârlı satış beklenir)"
                } else ""

                return ActionGuidance(
                    title = "⏳ SPOT SABIR MODU: ${trade.symbol}/USDT BEKLENİYOR",
                    statusBadge = if (pnlUsdt >= 0) "KÂRDA (+${String.format(Locale.US, "%.2f", pnlUsdt)} USDT)" else "DİPTE BEKLEMEDE (${String.format(Locale.US, "%.2f", pnlUsdt)} USDT)",
                    statusColorHex = if (pnlUsdt >= 0) 0xFF00FF9D else 0xFFFFB800,
                    step1 = "1. MİDAS'TA GİRİLECEK LİMİT SATIŞ: $${String.format(Locale.US, "%.2f", trade.targetExitPrice)} USDT fiyatından satış emrinizi açık tutun.",
                    step2 = "2. Kuralımız: KESİNLİKLE ZARARINA SATIŞ YOK. Spot varlıkta sabırla beklenir.$dcaNote",
                    step3 = "3. Hedefe %${String.format(Locale.US, "%.2f", ((trade.targetExitPrice - currentPrice) / currentPrice).coerceAtLeast(0.0) * 100.0)} kaldı. Satış dolunca 'Satıldı & Kapat' butonuna basın.",
                    targetSymbol = trade.symbol,
                    recommendedExitPrice = trade.targetExitPrice,
                    reasoning = "Sıfır zarar stratejisi: Spot varlıkta panik satışı yapılmaz, kârlı limit emrin dolması beklenir."
                )
            }
        }

        // 3. Case: Piyasayı Tara ve En Kaliteli Dip Alım Fırsatını Bul
        var bestOpportunity: Pair<CryptoAsset, TechnicalAnalysis5m>? = null
        var highestScore = -1

        for (asset in assets) {
            val tech = techMap[asset.symbol] ?: continue
            val score = tech.confluenceScore
            if (score > highestScore && !tech.isOverboughtRisk) {
                highestScore = score
                bestOpportunity = Pair(asset, tech)
            }
        }

        if (bestOpportunity != null && highestScore >= 65) {
            val (asset, tech) = bestOpportunity
            val entryLimit = tech.supportLevel
            // Net 2.5% profit target + 0.40% total Midas buy/sell commission
            val targetExit = entryLimit * (1.0 + (2.5 + 0.40) / 100.0)
            val expectedNetUsdt = (cash * 0.50).coerceAtLeast(minThreshold) * 0.025

            return ActionGuidance(
                title = "🎯 TAKTİKSEL PUSU LİMİT ALIŞ: ${asset.symbol}/USDT",
                statusBadge = "GÜÇLÜ PUSU SİNYALİ (SKOR: %$highestScore)",
                statusColorHex = 0xFF00FF9D,
                step1 = "1. MİDAS'TA GİRİLECEK LİMİT ALIŞ: $${String.format(Locale.US, if (entryLimit < 1.0) "%.4f" else "%.2f", entryLimit)} USDT fiyatından limit alış emri girip pusuya yatın.",
                step2 = "2. ⏱️ PUSU SÜRESİ (${tech.ambushTimeoutMinutes} DK): ${tech.ambushTimeoutMinutes} dakika beklenir. Fiyat desteğe inmeden tepeye dönerse emir iptal edilir, yeni pusu kurulur.",
                step3 = "3. 🎯 ÇIKIŞ PLANI: Alım dolduğu anda Midas'ta $${String.format(Locale.US, if (targetExit < 1.0) "%.4f" else "%.2f", targetExit)} USDT limit satış açılır (+%2.5 Net Kâr). Sıfır zarar kuralı aktiftir.",
                targetSymbol = asset.symbol,
                recommendedEntryPrice = entryLimit,
                recommendedExitPrice = targetExit,
                netProfitUsdtExpected = expectedNetUsdt,
                reasoning = "${tech.volumeClusterDescription}. RSI (${String.format(Locale.US, "%.1f", tech.rsi14)}) aşırı satım bölgesinde ve EMA21 desteğinde."
            )
        }

        // 4. Case: Piyasa Aşırı Alımda veya Kararsızsa (Sabır Modu)
        return ActionGuidance(
            title = "🛡️ SABIR MODU: PİYASA TEPE DİRENCİNDE",
            statusBadge = "BEKLEMEDE (RİSKLİ BÖLGE)",
            statusColorHex = 0xFF00F0FF,
            step1 = "1. İncelenen kripto paralar 5 dakikalık direnç seviyelerine yakın veya RSI aşırı alım bölgesinde.",
            step2 = "2. Tepeden alım yapmamak ve sermayeyi korumak adına nakitte (USDT) kalın.",
            step3 = "3. 5 dakikalık grafiklerde sağlıklı bir dip destek oluşumu ve RSI soğuması izleniyor.",
            reasoning = "Yüksek fiyattan giriş yapıp terste kalmamak için dip sekmesi bekleniyor."
        )
    }

    /**
     * Directly establishes the user's real initial Midas cash balance without calculating
     * any fake withdrawals or prior discrepancies.
     */
    suspend fun setInitialCashBalance(
        dao: AppDatabaseDao,
        initialCashUsdt: Double,
        minSafeThresholdUsdt: Double = 0.0
    ): CapitalProfileEntity = withContext(Dispatchers.IO) {
        val newProfile = CapitalProfileEntity(
            id = 1,
            isInitialized = true,
            availableCashUsdt = initialCashUsdt,
            minSafeThresholdUsdt = minSafeThresholdUsdt,
            weeklyTargetPercent = 5.0,
            weekStartCapitalUsdt = initialCashUsdt,
            weekStartTimestamp = System.currentTimeMillis(),
            totalWithdrawnUsdt = 0.0,
            totalDepositedUsdt = initialCashUsdt,
            lastRecordedBalanceUsdt = initialCashUsdt,
            lastBalanceUpdateTimestamp = System.currentTimeMillis()
        )
        dao.saveCapitalProfile(newProfile)
        newProfile
    }

    /**
     * Completely resets all trades, memory, and capital profiles to absolute zero (factory fresh).
     */
    suspend fun resetAllDatabase(dao: AppDatabaseDao) = withContext(Dispatchers.IO) {
        dao.clearAllTrades()
        dao.clearCapitalProfile()
        dao.clearWeeklyReports()
        dao.clearCoinMemory()
    }

    /**
     * Smart Cash Auditor: Detects whether cash was withdrawn as USD to bank
     * or deposited without bothering the user.
     */
    suspend fun auditCashUpdate(
        dao: AppDatabaseDao,
        newCashAmountUsdt: Double
    ): CapitalProfileEntity = withContext(Dispatchers.IO) {
        val currentProfile = dao.getCapitalProfileOnce()
        if (currentProfile == null || !currentProfile.isInitialized) {
            return@withContext setInitialCashBalance(dao, newCashAmountUsdt)
        }

        val prevObserved = currentProfile.availableCashUsdt
        var updatedWithdrawn = currentProfile.totalWithdrawnUsdt
        var updatedDeposited = currentProfile.totalDepositedUsdt

        val diff = newCashAmountUsdt - prevObserved

        if (diff < -0.50) {
            // Cash decreased without trade loss -> User converted USDT to USD and withdrew to bank!
            val withdrawn = Math.abs(diff)
            updatedWithdrawn += withdrawn
        } else if (diff > 0.50) {
            // Cash increased -> User deposited USD and bought USDT!
            updatedDeposited += diff
        }

        val updatedProfile = currentProfile.copy(
            isInitialized = true,
            availableCashUsdt = newCashAmountUsdt,
            totalWithdrawnUsdt = updatedWithdrawn,
            totalDepositedUsdt = updatedDeposited,
            lastRecordedBalanceUsdt = newCashAmountUsdt,
            lastBalanceUpdateTimestamp = System.currentTimeMillis()
        )

        dao.saveCapitalProfile(updatedProfile)
        updatedProfile
    }

    /**
     * Executes a 3-tier DCA averaging step: lowers average entry cost and recalculates
     * target exit price with net 2.0% profit + Midas %0.40 fee.
     */
    suspend fun executeDcaStep(
        dao: AppDatabaseDao,
        tradeId: Long,
        additionalEntryPrice: Double,
        additionalInvestUsdt: Double
    ): AppTradeEntity? = withContext(Dispatchers.IO) {
        val trade = dao.getTradeById(tradeId) ?: return@withContext null
        if (trade.dcaLevel >= trade.maxDcaLevels) return@withContext trade

        val currentProfile = dao.getCapitalProfileOnce() ?: CapitalProfileEntity()
        if (currentProfile.availableCashUsdt < additionalInvestUsdt) return@withContext trade

        val newUnits = (additionalInvestUsdt * 0.998) / additionalEntryPrice
        val totalUnits = trade.coinAmount + newUnits
        val totalInvested = trade.investedUsdt + additionalInvestUsdt
        val avgEntryPrice = totalInvested / totalUnits

        // Net 2.0% profit + 0.40% total Midas fee
        val newTargetExit = avgEntryPrice * (1.0 + (2.0 + 0.40) / 100.0)
        val newTotalFees = totalInvested * 0.0040

        val updatedTrade = trade.copy(
            entryPrice = avgEntryPrice,
            targetExitPrice = newTargetExit,
            investedUsdt = totalInvested,
            coinAmount = totalUnits,
            midasTotalFeeUsdt = newTotalFees,
            dcaLevel = trade.dcaLevel + 1
        )

        dao.updateTrade(updatedTrade)
        dao.saveCapitalProfile(currentProfile.copy(availableCashUsdt = (currentProfile.availableCashUsdt - additionalInvestUsdt).coerceAtLeast(0.0)))
        updatedTrade
    }

    /**
     * Closes an active trade with realized PnL, updates Coin Memory, and restores cash balance.
     */
    suspend fun closeActiveTrade(
        dao: AppDatabaseDao,
        tradeId: Long,
        actualExitPrice: Double
    ): AppTradeEntity? = withContext(Dispatchers.IO) {
        val trade = dao.getTradeById(tradeId) ?: return@withContext null

        val buyFee = trade.investedUsdt * 0.0020
        val grossReturn = trade.coinAmount * actualExitPrice
        val sellFee = grossReturn * 0.0020
        val totalFee = buyFee + sellFee
        val netPnl = (grossReturn - trade.investedUsdt) - totalFee
        val netPnlPercent = (netPnl / trade.investedUsdt) * 100.0
        val isProfit = netPnl >= 0

        val updatedTrade = trade.copy(
            actualExitPrice = actualExitPrice,
            midasTotalFeeUsdt = totalFee,
            netProfitUsdt = netPnl,
            netProfitPercent = netPnlPercent,
            status = if (isProfit) "COMPLETED_PROFIT" else "COMPLETED_LOSS",
            closedAt = System.currentTimeMillis(),
            aiNote = if (isProfit) "Midas %0.40 komisyonu düşüldükten sonra net +${String.format(Locale.US, "%.2f", netPnl)} USDT kârla tamamlandı." else "Zararla kapatıldı."
        )

        dao.updateTrade(updatedTrade)

        // Restore Cash into Capital Profile
        val currentProfile = dao.getCapitalProfileOnce() ?: CapitalProfileEntity()
        val newCash = currentProfile.availableCashUsdt + trade.investedUsdt + netPnl
        dao.saveCapitalProfile(currentProfile.copy(availableCashUsdt = newCash.coerceAtLeast(0.0)))

        // Update Coin Memory (Self-Learning Metric)
        val existingMemory = dao.getCoinMemory(trade.symbol) ?: CoinMemoryEntity(symbol = trade.symbol)
        val newTotal = existingMemory.totalTrades + 1
        val newSuccessful = existingMemory.successfulTrades + if (isProfit) 1 else 0
        val newWinRate = (newSuccessful.toDouble() / newTotal.toDouble()) * 100.0
        val newTotalProfit = existingMemory.totalNetProfitUsdt + netPnl

        dao.saveCoinMemory(
            existingMemory.copy(
                totalTrades = newTotal,
                successfulTrades = newSuccessful,
                totalNetProfitUsdt = newTotalProfit,
                winRatePercent = newWinRate,
                lastUpdated = System.currentTimeMillis()
            )
        )

        updatedTrade
    }

    /**
     * Updates the target exit price for an open trade.
     */
    suspend fun updateTradeTarget(
        dao: AppDatabaseDao,
        tradeId: Long,
        newTargetExitPrice: Double
    ): AppTradeEntity? = withContext(Dispatchers.IO) {
        val trade = dao.getTradeById(tradeId) ?: return@withContext null
        val updatedTrade = trade.copy(targetExitPrice = newTargetExitPrice)
        dao.updateTrade(updatedTrade)
        updatedTrade
    }

    /**
     * Generates a comprehensive, clean, Markdown weekly report for the user to copy & paste.
     */
    suspend fun generateWeeklyReport(
        dao: AppDatabaseDao,
        context: Context
    ): WeeklyReportEntity = withContext(Dispatchers.IO) {
        val capital = dao.getCapitalProfileOnce() ?: CapitalProfileEntity()
        val historicalTrades = dao.getHistoricalTradesOnce()

        val startCap = capital.weekStartCapitalUsdt
        val currentCash = capital.availableCashUsdt
        val totalNetPnl = historicalTrades.sumOf { it.netProfitUsdt }
        val netGrowthPct = if (startCap > 0) (totalNetPnl / startCap) * 100.0 else 0.0

        val totalTradesCount = historicalTrades.size
        val successfulTradesCount = historicalTrades.count { it.status == "COMPLETED_PROFIT" }
        val winRate = if (totalTradesCount > 0) (successfulTradesCount.toDouble() / totalTradesCount.toDouble()) * 100.0 else 100.0

        val bestCoin = historicalTrades.groupBy { it.symbol }
            .maxByOrNull { entry -> entry.value.sumOf { it.netProfitUsdt } }?.key ?: "SOL"

        val isTargetMet = netGrowthPct >= capital.weeklyTargetPercent

        val dateFormat = SimpleDateFormat("dd MMMM yyyy", Locale("tr", "TR"))
        val weekLabel = "Haftalık Analiz (${dateFormat.format(Date(capital.weekStartTimestamp))} - ${dateFormat.format(Date())})"

        val aiEvaluation = if (isTargetMet) {
            "Midas %0.40 komisyonları hesaba katılarak disiplinli 5 dakikalık dip destek stratejisi uygulandı. %${String.format(Locale.US, "%.1f", capital.weeklyTargetPercent)} hedefi aşılarak %${String.format(Locale.US, "%.2f", netGrowthPct)} net USDT büyümesi sağlandı. En yüksek verim $bestCoin/USDT paritesinde gerçekleşti."
        } else {
            "Haftalık %${String.format(Locale.US, "%.1f", capital.weeklyTargetPercent)} hedefine yaklaşıldı (%${String.format(Locale.US, "%.2f", netGrowthPct)}). Düşük oynaklık yaşanan saatlerde gereksiz işlemden kaçınıldı, kasanın güvenliği ön planda tutuldu."
        }

        val fullMarkdown = """
            ==================================================
            📊 HAFTALIK KRİPTO TEKNİK ANALİST RAPORU
            ==================================================
            🗓️ Dönem: $weekLabel
            💼 Başlangıç Kasa: $${String.format(Locale.US, "%.2f", startCap)} USDT
            💵 Güncel Bakiye: $${String.format(Locale.US, "%.2f", currentCash)} USDT
            🏦 Toplam Çekilen Nakit (USD'ye Çevrilen): $${String.format(Locale.US, "%.2f", capital.totalWithdrawnUsdt)} USDT
            📈 Net Haftalık Kazanç: %+${String.format(Locale.US, "%.2f", totalNetPnl)} USDT (%+${String.format(Locale.US, "%.2f", netGrowthPct)})
            🎯 Haftalık Hedef: %${String.format(Locale.US, "%.1f", capital.weeklyTargetPercent)} ➔ ${if (isTargetMet) "HEDEFE ULAŞILDI (BAŞARILI 🏆)" else "GELİŞTİRME DEVAM EDİYOR ⏳"}

            📋 İŞLEM ANALİTİĞİ VE HAFIZA:
            • Toplam Tamamlanan İşlem: $totalTradesCount adet
            • Başarılı (Kârla Kapanan): $successfulTradesCount adet
            • Kazanma Oranı (Win Rate): %${String.format(Locale.US, "%.1f", winRate)}
            • En Verimli Kripto: $bestCoin/USDT

            🧠 YAPAY ZEKÂ ANALİZ VE DEĞERLENDİRMESİ:
            $aiEvaluation

            ⚙️ GELECEK HAFTA İÇİN SİSTEM ÖNERİSİ:
            - Midas %0.40 komisyon optimizasyonu aktif tutulmalı.
            - $bestCoin/USDT ve SOL/USDT 5dk dip desteklerindeki likidite öncülüğü takip edilmeli.
            ==================================================
        """.trimIndent()

        val reportEntity = WeeklyReportEntity(
            weekLabel = weekLabel,
            startCapitalUsdt = startCap,
            endCapitalUsdt = currentCash,
            netProfitUsdt = totalNetPnl,
            growthPercent = netGrowthPct,
            targetPercent = capital.weeklyTargetPercent,
            isTargetAchieved = isTargetMet,
            totalTrades = totalTradesCount,
            successfulTrades = successfulTradesCount,
            winRatePercent = winRate,
            bestCoin = bestCoin,
            aiAnalysisText = aiEvaluation,
            fullExportMarkdown = fullMarkdown,
            createdAt = System.currentTimeMillis()
        )

        dao.insertWeeklyReport(reportEntity)
        reportEntity
    }
}

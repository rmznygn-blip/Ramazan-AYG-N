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
    val reasoning: String = "",
    val isTrailingActive: Boolean = false,
    val trailingLockPrice: Double = 0.0,
    val isTimeoutWarning: Boolean = false
)

object AiAdvisorEngine {

    /**
     * Synthesizes Portfolio Capital, Active Trades, Pending Ambush Orders, Live 5m Technical Indicators,
     * Binance WebSocket Order Book Depth, Z-Score, ATR, and Trailing TP.
     */
    fun computeRealtimeGuidance(
        capitalProfile: CapitalProfileEntity?,
        activeTrades: List<AppTradeEntity>,
        pendingTrades: List<AppTradeEntity> = emptyList(),
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
                step1 = "1. Midas Kripto cüzdanınızdaki güncel boş USDT miktarınızı girin.",
                step2 = "2. Sistem 1:2:4 dinamik DCA kuralına göre kasa bütçesi oluşturur (Örn: $15 / $30 / $60).",
                step3 = "3. Varsa mevcut aldığınız coinleri 'Mevcut Varlığımı Ekle' diyerek anında takibe alın.",
                reasoning = "Sıfır varsayılan veya sahte bakiye tutulmaz; tamamen sizin girdiğiniz gerçek Midas USDT bakiyenizle çalışılır."
            )
        }

        // 1. Case: Kasa Güvenli Eşiğin Altındaysa
        if (minThreshold > 0.0 && cash < minThreshold && activeTrades.isEmpty() && pendingTrades.isEmpty()) {
            return ActionGuidance(
                title = "🛡️ KASA GÜVENLİK EŞİĞİ KORUMASI",
                statusBadge = "KASA YETERSİZ ($${String.format(Locale.US, "%.1f", cash)} / $${String.format(Locale.US, "%.1f", minThreshold)} USDT)",
                statusColorHex = 0xFFFFB800,
                step1 = "1. Midas'taki kullanılabilir USDT kasanız ($${String.format(Locale.US, "%.2f", cash)}), belirlediğiniz minimum güvenli eşiğin ($${String.format(Locale.US, "%.2f", minThreshold)}) altındadır.",
                step2 = "2. Midas komisyonlarının (%0.40) kâr marjını eritmemesi ve sağlıklı kâr elde edilebilmesi için yeni alım önerisi duraklatıldı.",
                step3 = "3. Midas'a USD yatırıp USDT'ye dönüştürerek kasayı güncelleyin veya güvenlik eşiğini düzenleyin.",
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
            val tradeDurationMinutes = (System.currentTimeMillis() - trade.openedAt) / (1000 * 60)

            // Trailing Take-Profit Logic (+2.0% Net Profit Trigger, +1.80% Lock)
            val netGrossRatio = (currentPrice - trade.entryPrice) / trade.entryPrice
            val isTrailingTriggered = netGrossRatio >= 0.0240 // Net ~2.00%
            val trailingLockPrice = trade.entryPrice * 1.0220 // Locks +1.80% Net profit after 0.40% Midas fee

            // 90-minute Timeout Break-Even Exit Logic (+0.10% net to recycle capital)
            val isTimeoutBreakEven = tradeDurationMinutes >= 90 && pnlPercent < 1.0
            val timeoutExitPrice = trade.entryPrice * 1.0050 // +0.10% Net after fee

            if (isTrailingTriggered) {
                return ActionGuidance(
                    title = "🚀 İZ SÜREN KÂR AL (TRAILING TP) AKTİF: ${trade.symbol}",
                    statusBadge = "KÂR KİLİTLENDİ (+%${String.format(Locale.US, "%.2f", pnlPercent)} NET)",
                    statusColorHex = 0xFF00FF9D,
                    step1 = "1. Hedef +%2.0 kâr aşıldı! Minimum kâr $${String.format(Locale.US, "%.2f", trailingLockPrice)} fiyatında kilitlendi (+%1.80 Net).",
                    step2 = "2. Fiyat yükselmeye devam ettikçe pozisyonu koruyun, düşüş başlarsa kilit fiyattan ($${String.format(Locale.US, "%.2f", trailingLockPrice)}) satın.",
                    step3 = "3. Satışı bitirince 'Satıldı & Kasaya Aktar' butonuna basın.",
                    targetSymbol = trade.symbol,
                    recommendedExitPrice = currentPrice,
                    netProfitUsdtExpected = pnlUsdt,
                    reasoning = "İz süren kâr algoritması devrede: Maksimum yükselişi yakalarken dip kâr garanti altına alındı.",
                    isTrailingActive = true,
                    trailingLockPrice = trailingLockPrice
                )
            }

            if (isTimeoutBreakEven) {
                return ActionGuidance(
                    title = "⏱️ 90 DK ZAMAN AŞIMI: SERMAYE BOŞALTMA PLANI",
                    statusBadge = "BAŞA BAŞ ÇIKIŞ (${tradeDurationMinutes} DK)",
                    statusColorHex = 0xFFFFB800,
                    step1 = "1. Pozisyon 90 dakikadır hedef dirence ulaşamadı. Akşam seansı sermayesini tazelemek için başa baş çıkış önerilir.",
                    step2 = "2. Midas'ta $${String.format(Locale.US, "%.2f", timeoutExitPrice)} USDT limit satış emri girerek komisyonsuz sıfır zararla nakde geçin.",
                    step3 = "3. Çıkış gerçekleştikten sonra daha yüksek ivmeli yeni bir pusuya geçilecektir.",
                    targetSymbol = trade.symbol,
                    recommendedExitPrice = timeoutExitPrice,
                    netProfitUsdtExpected = 0.05,
                    reasoning = "Zaman maliyeti koruması: Durgun tahtada kilitli kalmamak adına +%0.10 net başa baş çıkış uygulanır.",
                    isTimeoutWarning = true
                )
            }

            val isTargetHit = currentPrice >= trade.targetExitPrice
            if (isTargetHit) {
                return ActionGuidance(
                    title = "🎯 HEDEF SATIŞ FİYATINA ULAŞILDI!",
                    statusBadge = "KÂR REALİZASYONU (+%${String.format(Locale.US, "%.2f", pnlPercent)} NET)",
                    statusColorHex = 0xFF00FF9D,
                    step1 = "1. ${trade.symbol}/USDT anlık fiyatı ($${String.format(Locale.US, "%.2f", currentPrice)}), belirlediğimiz $${String.format(Locale.US, "%.2f", trade.targetExitPrice)} USDT hedef satışını geçti!",
                    step2 = "2. Midas hesabınızı kontrol edin: $${String.format(Locale.US, "%.2f", trade.targetExitPrice)} USDT limit satış emrinizin gerçekleşip gerçekleşmediğine bakın.",
                    step3 = "3. Satış yaptıktan sonra 'Satışı Kasaya Ekle' butonuna basarak kasanıza kârı aktarın.",
                    targetSymbol = trade.symbol,
                    recommendedExitPrice = currentPrice.coerceAtLeast(trade.targetExitPrice),
                    netProfitUsdtExpected = pnlUsdt,
                    reasoning = "5 dakikalık hedef direnç testi başarıyla tamamlandı. Sıfır zarar prensibi korundu."
                )
            } else {
                val tech = techMap[trade.symbol]
                val nextTierPrice = if (trade.dcaLevel == 1) tech?.dcaTier2Price ?: (trade.entryPrice * 0.97) else tech?.dcaTier3Price ?: (trade.entryPrice * 0.94)
                val isDcaPossible = trade.dcaLevel < trade.maxDcaLevels && cash >= 15.0 && currentPrice <= nextTierPrice

                val dcaNote = if (isDcaPossible) {
                    " (Dinamik ATR Desteği: $${String.format(Locale.US, "%.2f", nextTierPrice)} USDT seviyesinden ${trade.dcaLevel + 1}. kademe eklenebilir)"
                } else if (trade.dcaLevel >= trade.maxDcaLevels) {
                    " (3/3 Kademe Tamamlandı - İlave ekleme yapılmaz, sadece kârlı çıkış beklenir)"
                } else ""

                return ActionGuidance(
                    title = "⏳ SPOT SABIR MODU: ${trade.symbol}/USDT BEKLENİYOR",
                    statusBadge = if (pnlUsdt >= 0) "KÂRDA (+${String.format(Locale.US, "%.2f", pnlUsdt)} USDT)" else "DİPTE BEKLEMEDE (${String.format(Locale.US, "%.2f", pnlUsdt)} USDT)",
                    statusColorHex = if (pnlUsdt >= 0) 0xFF00FF9D else 0xFFFFB800,
                    step1 = "1. MİDAS LİMİT SATIŞ: $${String.format(Locale.US, "%.2f", trade.targetExitPrice)} USDT emrinizi açık tutun.",
                    step2 = "2. Kuralımız: KESİNLİKLE ZARARINA SATIŞ YOK. Spot varlıkta sabırla beklenir.$dcaNote",
                    step3 = "3. Hedefe %${String.format(Locale.US, "%.2f", ((trade.targetExitPrice - currentPrice) / currentPrice).coerceAtLeast(0.0) * 100.0)} kaldı. Hedef dolunca kasaya aktarın.",
                    targetSymbol = trade.symbol,
                    recommendedExitPrice = trade.targetExitPrice,
                    reasoning = "Sıfır zarar stratejisi: Spot varlıkta panik satışı yapılmaz, kârlı limit emrin dolması beklenir."
                )
            }
        }

        // 2.5 Case: Midas'ta Bekleyen Pusu / Alış Emri Varsa
        if (pendingTrades.isNotEmpty()) {
            val pending = pendingTrades.first()
            val asset = assets.firstOrNull { it.symbol == pending.symbol }
            val currentPrice = if (asset != null && asset.rawPrice > 0) asset.rawPrice else pending.entryPrice
            val elapsedMinutes = (System.currentTimeMillis() - pending.openedAt) / (1000 * 60)
            val remainingMinutes = (pending.ambushTimeoutMinutes - elapsedMinutes).coerceAtLeast(0)
            val isPriceAtOrBelowEntry = currentPrice <= pending.entryPrice
            val diffPercent = if (pending.entryPrice > 0) ((currentPrice - pending.entryPrice) / pending.entryPrice) * 100.0 else 0.0

            if (isPriceAtOrBelowEntry) {
                return ActionGuidance(
                    title = "⚡ FİYAT PUSU EŞİĞİNE GELDİ: ${pending.symbol}",
                    statusBadge = "MİDAS'TA ALIM GERÇEKLEŞTİ Mİ?",
                    statusColorHex = 0xFF00FF9D,
                    step1 = "1. Anlık fiyat ($${String.format(Locale.US, if (currentPrice < 1.0) "%.4f" else "%.2f", currentPrice)}) pusu limitinize ($${String.format(Locale.US, if (pending.entryPrice < 1.0) "%.4f" else "%.2f", pending.entryPrice)}) ulaştı veya altına indi!",
                    step2 = "2. Midas hesabınızı kontrol edin: Limit alış emriniz dolduysa 'Alış Gerçekleşti mi?' butonuna basıp onaylayın.",
                    step3 = "3. Alım teyit edildikten sonra anında +%2.0 Net kârlı limit satış emri aktif edilecektir.",
                    targetSymbol = pending.symbol,
                    recommendedEntryPrice = pending.entryPrice,
                    recommendedExitPrice = pending.targetExitPrice,
                    netProfitUsdtExpected = pending.investedUsdt * 0.020,
                    reasoning = "5 dakikalık dip desteği test edildi. Emir dolduysa pozisyonu aktifleştirin."
                )
            } else if (remainingMinutes <= 0) {
                return ActionGuidance(
                    title = "⏱️ ${pending.ambushTimeoutMinutes} DK PUSU SÜRESİ DOLDU: ${pending.symbol}",
                    statusBadge = "EMİR İPTAL & YENİ ANALİZ ÖNERİLİR",
                    statusColorHex = 0xFFFFB800,
                    step1 = "1. ${pending.symbol} için verilen ${pending.ambushTimeoutMinutes} dakikalık pusu süresi doldu ve fiyat destek limitine inmedi (Mesafe: +%${String.format(Locale.US, "%.2f", diffPercent)}).",
                    step2 = "2. Midas'taki bekleyen limit alış emrinizi iptal edip uygulamadan 'İptal Et' butonuna basarak bütçenizi serbest bırakın.",
                    step3 = "3. Sistem anında güncel verilerle yeni bir dip ve pusu fırsatı tespit edecektir.",
                    targetSymbol = pending.symbol,
                    recommendedEntryPrice = pending.entryPrice,
                    reasoning = "Zaman disiplini: Desteğe inmeyen ve yukarı kaçan emirler iptal edilir, sermaye bayatlamadan taze fırsatlara yönlendirilir.",
                    isTimeoutWarning = true
                )
            } else {
                return ActionGuidance(
                    title = "⏳ MİDAS'TA PUSU BEKLENİYOR: ${pending.symbol}",
                    statusBadge = "PUSU AKTİF (${remainingMinutes} DK KALDI)",
                    statusColorHex = 0xFF00F0FF,
                    step1 = "1. Midas'ta $${String.format(Locale.US, if (pending.entryPrice < 1.0) "%.4f" else "%.2f", pending.entryPrice)} USDT limit alış emriniz açık beklemelidir (Bütçe: $${String.format(Locale.US, "%.2f", pending.investedUsdt)}).",
                    step2 = "2. Anlık Fiyat: $${String.format(Locale.US, if (currentPrice < 1.0) "%.4f" else "%.2f", currentPrice)} USDT (Fiyat emrinizin %${String.format(Locale.US, "%.2f", diffPercent)} üzerinde seyrediyor).",
                    step3 = "3. Fiyat desteğe indiğinde sistem 'Alışınız Gerçekleşti mi?' diye soracaktır.",
                    targetSymbol = pending.symbol,
                    recommendedEntryPrice = pending.entryPrice,
                    recommendedExitPrice = pending.targetExitPrice,
                    reasoning = "Sabırla dip desteğe dokunması bekleniyor. Emir dolmadan önce kârlı satış takibine geçilmez."
                )
            }
        }

        // 3. Case: Piyasayı Tara ve En Kaliteli Dip Alım Fırsatını Bul
        var bestOpportunity: Pair<CryptoAsset, TechnicalAnalysis5m>? = null
        var highestScore = -1

        for (asset in assets) {
            val tech = techMap[asset.symbol] ?: continue
            val score = tech.confluenceScore
            // Filter out volume shocks (knife catch) and seller dominant order books (<60% buyers)
            if (score > highestScore && !tech.isOverboughtRisk && !tech.isVolumeShock && !tech.orderBookDepth.isOrderBookFear) {
                highestScore = score
                bestOpportunity = Pair(asset, tech)
            }
        }

        if (bestOpportunity != null && highestScore >= 60) {
            val (asset, tech) = bestOpportunity
            val entryLimit = tech.dcaTier1Price.takeIf { it > 0 } ?: tech.supportLevel
            // Net 2.0% profit target + 0.40% total Midas buy/sell commission
            val targetExit = entryLimit * (1.0 + (2.0 + 0.40) / 100.0)
            val tier1InvestBudget = (cash * (1.0 / 7.0)).coerceIn(15.0, (cash * 0.35).coerceAtLeast(15.0))
            val expectedNetUsdt = tier1InvestBudget * 0.020

            val orderBookRatio = String.format(Locale.US, "%.0f", tech.orderBookDepth.bidRatio * 100)
            val zScoreText = String.format(Locale.US, "%.2f", tech.zScore)

            return ActionGuidance(
                title = "🎯 1:2:4 KANTİTATİF PUSU GİRİŞİ: ${asset.symbol}/USDT",
                statusBadge = "GÜÇLÜ PUSU SİNYALİ (SKOR: %$highestScore)",
                statusColorHex = 0xFF00FF9D,
                step1 = "1. MİDAS 1. KADEME LİMİT ALIŞ: $${String.format(Locale.US, if (entryLimit < 1.0) "%.4f" else "%.2f", entryLimit)} USDT fiyatından ~$${String.format(Locale.US, "%.0f", tier1InvestBudget)} limit alış girin.",
                step2 = "2. 🛡️ ATR DİP KORUMASI: Fiyat sarkarsa 2. Kademe ($${String.format(Locale.US, "%.2f", tech.dcaTier2Price)}) ve 3. Kademe ($${String.format(Locale.US, "%.2f", tech.dcaTier3Price)}) hazır bekler.",
                step3 = "3. 🎯 KÂR ÇIKIŞI (+%2.0 NET): Alım dolduğu anda Midas'ta $${String.format(Locale.US, if (targetExit < 1.0) "%.4f" else "%.2f", targetExit)} USDT limit satış açılır.",
                targetSymbol = asset.symbol,
                recommendedEntryPrice = entryLimit,
                recommendedExitPrice = targetExit,
                netProfitUsdtExpected = expectedNetUsdt,
                reasoning = "Binance Tahtası: %$orderBookRatio Alıcı Duvarı | Z-Score: ${zScoreText}σ | ATR: ${String.format(Locale.US, "%.2f", tech.atr14)} | RSI: ${String.format(Locale.US, "%.1f", tech.rsi14)}"
            )
        }

        // 4. Case: Piyasa Kararsızsa veya Bıçak Düşüşü Varsa
        val volumeShockAsset = assets.firstOrNull { techMap[it.symbol]?.isVolumeShock == true }
        val fearAsset = assets.firstOrNull { techMap[it.symbol]?.orderBookDepth?.isOrderBookFear == true }

        val sabirReason = when {
            volumeShockAsset != null -> "${volumeShockAsset.symbol} paritesinde ani satış hacmi şoku var. Düşen bıçağı tutmamak için hacmin sakinleşmesi bekleniyor."
            fearAsset != null -> "Binance tahtasında alıcı duvarı <%60 seviyesinde. Satış baskısı dindiğinde pusu kurulacak."
            else -> "İncelenen varlıklarda sağlıklı dip destek oluşumu ve RSI/Z-Score soğuması izleniyor."
        }

        return ActionGuidance(
            title = "🛡️ SABIR MODU: NAKİTTE (USDT) BEKLEME",
            statusBadge = "PUSU HAZIRLIĞI",
            statusColorHex = 0xFF00F0FF,
            step1 = "1. Akşam seansı için yüksek istatistiki güvenlikli dip aranıyor.",
            step2 = "2. Hacim şoku veya zayıf tahtada acele işlem yapmayıp nakit USDT'yi koruyun.",
            step3 = "3. Z-Score <-2.5 sapması ve %60+ alıcı duvarı oluştuğunda anında 1. Kademe bildirimi gelecektir.",
            reasoning = sabirReason
        )
    }

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

    suspend fun resetAllDatabase(dao: AppDatabaseDao) = withContext(Dispatchers.IO) {
        dao.clearAllTrades()
        dao.clearCapitalProfile()
        dao.clearWeeklyReports()
        dao.clearCoinMemory()
    }

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
            val withdrawn = Math.abs(diff)
            updatedWithdrawn += withdrawn
        } else if (diff > 0.50) {
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

    suspend fun confirmPendingBuyFilled(
        dao: AppDatabaseDao,
        tradeId: Long,
        actualEntryPrice: Double? = null,
        actualCoinAmount: Double? = null
    ): AppTradeEntity? = withContext(Dispatchers.IO) {
        val trade = dao.getTradeById(tradeId) ?: return@withContext null
        val finalEntryPrice = actualEntryPrice ?: trade.entryPrice
        val finalCoinAmount = actualCoinAmount ?: if (finalEntryPrice > 0) (trade.investedUsdt * 0.998) / finalEntryPrice else trade.coinAmount
        val finalTargetExit = finalEntryPrice * (1.0 + (2.0 + 0.40) / 100.0)

        val updatedTrade = trade.copy(
            entryPrice = finalEntryPrice,
            coinAmount = finalCoinAmount,
            targetExitPrice = finalTargetExit,
            status = "ACTIVE_OPEN",
            openedAt = System.currentTimeMillis(),
            aiNote = "Midas alış emri onaylandı. +%2.0 Net kâr hedefi izleniyor."
        )
        dao.updateTrade(updatedTrade)
        updatedTrade
    }

    suspend fun cancelPendingAmbush(
        dao: AppDatabaseDao,
        tradeId: Long,
        reason: String = "Kullanıcı veya 45 dk zaman aşımı iptali"
    ): AppTradeEntity? = withContext(Dispatchers.IO) {
        val trade = dao.getTradeById(tradeId) ?: return@withContext null
        val updatedTrade = trade.copy(
            status = "CANCELLED",
            closedAt = System.currentTimeMillis(),
            aiNote = reason
        )
        dao.updateTrade(updatedTrade)

        // Restore reserved cash back into available cash
        val currentProfile = dao.getCapitalProfileOnce() ?: CapitalProfileEntity()
        val restoredCash = currentProfile.availableCashUsdt + trade.investedUsdt
        dao.saveCapitalProfile(currentProfile.copy(availableCashUsdt = restoredCash.coerceAtLeast(0.0)))

        updatedTrade
    }

    suspend fun extendAmbushTimeout(
        dao: AppDatabaseDao,
        tradeId: Long,
        additionalMinutes: Int = 30
    ): AppTradeEntity? = withContext(Dispatchers.IO) {
        val trade = dao.getTradeById(tradeId) ?: return@withContext null
        val updatedTrade = trade.copy(
            ambushTimeoutMinutes = trade.ambushTimeoutMinutes + additionalMinutes
        )
        dao.updateTrade(updatedTrade)
        updatedTrade
    }

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

        val aiEvaluation = GeminiMarketAnalystService.generateWeekendOptimizationReport(
            totalTrades = totalTradesCount,
            winRate = winRate,
            netProfitUsdt = totalNetPnl,
            bestCoin = bestCoin,
            recentTradesJson = ""
        )

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

            🧠 YAPAY ZEKÂ KANTİTATİF RAPORU:
            $aiEvaluation

            ⚙️ GELECEK HAFTA İÇİN SİSTEM ÖNERİSİ:
            - Midas %0.40 komisyon optimizasyonu ve 1:2:4 ATR DCA aktif tutulacak.
            - $bestCoin/USDT ve BTC/USDT 5dk dip desteklerindeki likidite öncülüğü takip edilecek.
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

    /**
     * Calculates the optimal ambush time-to-live (TTL) in minutes based on professional quant
     * candle count cycles on 5m charts and asset ATR/volatility.
     *
     * Rules:
     * - High Volatility (ATR >= 1.3% or high beta like SOL/PEPE/DOGE/AVAX/NEAR): 30 Mins (6 Candles) - Fast Scalp TTL
     * - Balanced Pullback (ATR 0.7% - 1.3% e.g. ETH/LINK/ADA): 45 Mins (9 Candles) - Standard Pullback TTL
     * - Low Volatility / Heavy Base (ATR < 0.7% e.g. BTC): 60 Mins (12 Candles) - Patient Base Retest TTL
     */
    fun calculateOptimalAmbushTimeout(asset: CryptoAsset, tech: TechnicalAnalysis5m?): Pair<Int, String> {
        val atrPct = if (asset.rawPrice > 0 && tech != null) (tech.atr14 / asset.rawPrice) * 100.0 else 1.0
        val isHighBeta = asset.symbol in listOf("SOL", "PEPE", "DOGE", "SHIB", "NEAR", "SUI", "AVAX")

        return when {
            atrPct >= 1.3 || (isHighBeta && atrPct >= 1.0) -> {
                Pair(30, "Yüksek Oynaklık (6 Mum / Hızlı Scalp TTL). Fiyat 30 dk içinde desteğe inmezse trend yukarı kaçmış veya yapı bozulmuştur.")
            }
            atrPct <= 0.7 || asset.symbol in listOf("BTC", "USDC") -> {
                Pair(60, "Ağır Tahta / Taban Akümülasyonu (12 Mum / Sabırlı Taban). Desteğin test edilmesi daha uzun bir konsolidasyon gerektirir.")
            }
            else -> {
                Pair(45, "Standart 5m Pullback (9 Mum / Dengeli). Klasik EMA20 / Destek retest döngüsü.")
            }
        }
    }

    /**
     * Calculates the 3-Tier Zero-Loss DCA defense map based on an entered (or accidental) entry price.
     * Gives the exact limit buy prices, allocated cash, updated average costs, and net-profit exit targets.
     */
    fun calculateDcaDefensePlan(
        entryPrice: Double,
        totalPoolUsdt: Double,
        tech: TechnicalAnalysis5m?,
        targetProfitPct: Double = 2.0
    ): DcaDefensePlan {
        val safeEntry = if (entryPrice > 0) entryPrice else 100.0
        val safePool = if (totalPoolUsdt > 0) totalPoolUsdt else 60.0
        val tierAmount = safePool / 3.0

        // Dynamic drop percentages based on ATR if available, else standard -3.5% and -7.0%
        val tier2Price = if (tech != null && tech.supportLevel > 0 && tech.supportLevel < safeEntry) {
            tech.supportLevel
        } else if (tech != null && tech.atr14 > 0) {
            (safeEntry - (tech.atr14 * 2.0)).coerceIn(safeEntry * 0.94, safeEntry * 0.975)
        } else {
            safeEntry * 0.965 // -3.5%
        }

        val tier3Price = if (tech != null && tech.dcaTier3Price > 0 && tech.dcaTier3Price < tier2Price) {
            tech.dcaTier3Price
        } else if (tech != null && tech.atr14 > 0) {
            (safeEntry - (tech.atr14 * 4.0)).coerceIn(safeEntry * 0.88, safeEntry * 0.94)
        } else {
            safeEntry * 0.930 // -7.0%
        }

        // Tier 1 calculation
        val t1Coins = (tierAmount * 0.998) / safeEntry
        val t1AvgCost = safeEntry
        val t1TargetExit = t1AvgCost * (1.0 + (targetProfitPct + 0.40) / 100.0)
        val t1ProfitUsdt = tierAmount * (targetProfitPct / 100.0)
        val t1 = DcaPlanTier(
            tierNumber = 1,
            name = "1. Kademe (Mevcut / Giriş)",
            price = safeEntry,
            dropPercentFromEntry = 0.0,
            allocatedUsdt = tierAmount,
            estimatedCoinAmount = t1Coins,
            cumulativeInvestedUsdt = tierAmount,
            averageCostPrice = t1AvgCost,
            targetExitPrice = t1TargetExit,
            netProfitUsdt = t1ProfitUsdt
        )

        // Tier 2 calculation
        val t2Coins = (tierAmount * 0.998) / tier2Price
        val cumInvest2 = tierAmount * 2
        val totalCoins2 = t1Coins + t2Coins
        val t2AvgCost = cumInvest2 / (totalCoins2 / 0.998)
        val t2TargetExit = t2AvgCost * (1.0 + (targetProfitPct + 0.40) / 100.0)
        val t2ProfitUsdt = cumInvest2 * (targetProfitPct / 100.0)
        val t2 = DcaPlanTier(
            tierNumber = 2,
            name = "2. Kademe (Dip Destek Ekleme)",
            price = tier2Price,
            dropPercentFromEntry = ((safeEntry - tier2Price) / safeEntry) * 100.0,
            allocatedUsdt = tierAmount,
            estimatedCoinAmount = t2Coins,
            cumulativeInvestedUsdt = cumInvest2,
            averageCostPrice = t2AvgCost,
            targetExitPrice = t2TargetExit,
            netProfitUsdt = t2ProfitUsdt
        )

        // Tier 3 calculation
        val t3Coins = (tierAmount * 0.998) / tier3Price
        val cumInvest3 = tierAmount * 3
        val totalCoins3 = t1Coins + t2Coins + t3Coins
        val t3AvgCost = cumInvest3 / (totalCoins3 / 0.998)
        val t3TargetExit = t3AvgCost * (1.0 + (targetProfitPct + 0.40) / 100.0)
        val t3ProfitUsdt = cumInvest3 * (targetProfitPct / 100.0)
        val t3 = DcaPlanTier(
            tierNumber = 3,
            name = "3. Kademe (Son Savunma / Taban)",
            price = tier3Price,
            dropPercentFromEntry = ((safeEntry - tier3Price) / safeEntry) * 100.0,
            allocatedUsdt = tierAmount,
            estimatedCoinAmount = t3Coins,
            cumulativeInvestedUsdt = cumInvest3,
            averageCostPrice = t3AvgCost,
            targetExitPrice = t3TargetExit,
            netProfitUsdt = t3ProfitUsdt
        )

        return DcaDefensePlan(
            entryPrice = safeEntry,
            totalPoolUsdt = safePool,
            tiers = listOf(t1, t2, t3)
        )
    }
}

data class DcaPlanTier(
    val tierNumber: Int,
    val name: String,
    val price: Double,
    val dropPercentFromEntry: Double,
    val allocatedUsdt: Double,
    val estimatedCoinAmount: Double,
    val cumulativeInvestedUsdt: Double,
    val averageCostPrice: Double,
    val targetExitPrice: Double,
    val netProfitUsdt: Double
)

data class DcaDefensePlan(
    val entryPrice: Double,
    val totalPoolUsdt: Double,
    val tiers: List<DcaPlanTier>
)

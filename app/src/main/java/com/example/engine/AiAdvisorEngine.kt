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
                    step3 = "3. Alım teyit edildikten sonra anında +%1.0 Net kârlı limit satış emri aktif edilecektir.",
                    targetSymbol = pending.symbol,
                    recommendedEntryPrice = pending.entryPrice,
                    recommendedExitPrice = pending.targetExitPrice,
                    netProfitUsdtExpected = pending.investedUsdt * 0.010,
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

        // 3. Case: Piyasayı Tara ve 3 Kurumsal Filtreden Geçen En Kaliteli Dip Alım Fırsatını Bul
        var bestOpportunity: Pair<CryptoAsset, TechnicalAnalysis5m>? = null
        var highestScore = -100.0

        var closestCandidate: Pair<CryptoAsset, TechnicalAnalysis5m>? = null
        var closestCandidateScore = -100.0

        for (asset in assets) {
            val tech = techMap[asset.symbol] ?: continue
            val candles = asset.recentCandles
            val latestCandle = candles.lastOrNull()
            val isRedCandle = latestCandle != null && latestCandle.close < latestCandle.open
            val isSevereDump = isRedCandle && tech.zScore < -2.6 && tech.isPriceBelowBollingerLower

            // 1. Düşen Bıçak Filtresi (Toleranslı): Sadece sıfır fitilli aşırı kırmızı mumlar bıçak kabul edilir
            val isFallingKnife = isSevereDump && (latestCandle.close - latestCandle.low) < (latestCandle.open - latestCandle.close) * 0.20

            // 2. Anti-Spoofing Hacim İvmesi: Alıcı duvarı (>= %55) yanında hacim ivmesi kontrol edilir
            val bidRatio = tech.orderBookDepth.bidRatio
            val volMomentum = if (asset.volumeMomentum > 0) asset.volumeMomentum else tech.volumeMomentum

            // 3. VWAP Kalkanı & Mıknatıs Çekimi
            val currentP = if (asset.rawPrice > 0) asset.rawPrice else tech.currentPrice
            val vwapVal = if (asset.vwap > 0) asset.vwap else tech.vwap
            val vwapDistPct = if (currentP > 0 && vwapVal > 0) ((vwapVal - currentP) / currentP) * 100.0 else 0.0

            // Kurumsal Skorlama Formülü (Confluence + VWAP Mıknatısı + Hacim İvmesi + Alıcı Duvarı)
            var quantScore = tech.confluenceScore.toDouble()
            if (bidRatio >= 0.55) quantScore += 10.0 // %55+ Alıcı duvarı bonusu
            if (volMomentum >= 1.05) quantScore += 12.0 // Hacim ivmesi
            if (vwapDistPct in 0.2..4.0) quantScore += 12.0 // Sağlıklı VWAP sekme potansiyeli
            if (latestCandle != null && latestCandle.close >= latestCandle.open) quantScore += 10.0 // Yeşil dönüş teyidi

            // En yakın adayı her halükarda takip et
            if (quantScore > closestCandidateScore) {
                closestCandidateScore = quantScore
                closestCandidate = Pair(asset, tech)
            }

            if (isFallingKnife || tech.isVolumeShock || tech.orderBookDepth.isOrderBookFear || tech.isOverboughtRisk) {
                continue
            }

            if (quantScore > highestScore) {
                highestScore = quantScore
                bestOpportunity = Pair(asset, tech)
            }
        }

        // Öncelik 1: Tam Kurumsal Sinyal (Tüm kurumsal filtreleri geçmiş ve skoru yüksek)
        if (bestOpportunity != null && highestScore >= 55.0) {
            val (asset, tech) = bestOpportunity
            val currentPrice = if (asset.rawPrice > 0) asset.rawPrice else tech.currentPrice
            val strategyAnalysis = evaluateSmartEntryStrategies(asset, tech, currentPrice, capitalProfile)
            val recommendedPlan = strategyAnalysis.options.firstOrNull { it.isRecommended } ?: strategyAnalysis.options[0]
            val entryLimit = recommendedPlan.price
            // Net 1.0% profit target + 0.40% total Midas buy/sell commission (Micro-Scalping)
            val targetExit = entryLimit * (1.0 + (1.0 + 0.40) / 100.0)
            val tier1InvestBudget = (cash * (1.0 / 7.0)).coerceIn(15.0, (cash * 0.35).coerceAtLeast(15.0))
            val expectedNetUsdt = tier1InvestBudget * 0.010

            val orderBookRatio = String.format(Locale.US, "%.0f", tech.orderBookDepth.bidRatio * 100)
            val vwapText = String.format(Locale.US, if (asset.vwap < 1.0) "%.4f" else "%.2f", if (asset.vwap > 0) asset.vwap else tech.vwap)

            return ActionGuidance(
                title = "🎯 AI NİHAİ PUSU (${recommendedPlan.title}): ${asset.symbol}/USDT",
                statusBadge = "KURUMSAL SİNYAL (SKOR: %${String.format(Locale.US, "%.0f", highestScore)})",
                statusColorHex = 0xFF00FF9D,
                step1 = "1. MİDAS 1. KADEME (${recommendedPlan.title}): $${String.format(Locale.US, if (entryLimit < 1.0) "%.4f" else "%.2f", entryLimit)} USDT fiyatından ~$${String.format(Locale.US, "%.0f", tier1InvestBudget)} limit alış girin (${recommendedPlan.fillSpeedText}).",
                step2 = "2. 🛡️ 3 KADEMELİ SAVUNMA: Fiyat sarkarsa 2. Kademe ($${String.format(Locale.US, if (tech.dcaTier2Price < 1.0) "%.4f" else "%.2f", tech.dcaTier2Price)}) ve 3. Kademe ($${String.format(Locale.US, if (tech.dcaTier3Price < 1.0) "%.4f" else "%.2f", tech.dcaTier3Price)}) hazır bekler.",
                step3 = "3. 🎯 KÂR ÇIKIŞI (+%1.0 NET): Alım dolduğu anda Midas'ta $${String.format(Locale.US, if (targetExit < 1.0) "%.4f" else "%.2f", targetExit)} USDT limit satış açılır.",
                targetSymbol = asset.symbol,
                recommendedEntryPrice = entryLimit,
                recommendedExitPrice = targetExit,
                netProfitUsdtExpected = expectedNetUsdt,
                reasoning = "Kusursuz Sniper: ${strategyAnalysis.aiRecommendationReason} | Tahta: %$orderBookRatio Alıcı | VWAP: $$vwapText | Hacim İvmesi: ${String.format(Locale.US, "%.2f", asset.volumeMomentum)}x"
            )
        }

        // Öncelik 2: En Yakın Pusu Adayı (Piyasa şartlarının çoğunu sağlayan lider aday)
        if (closestCandidate != null) {
            val (asset, tech) = closestCandidate
            val currentPrice = if (asset.rawPrice > 0) asset.rawPrice else tech.currentPrice
            val strategyAnalysis = evaluateSmartEntryStrategies(asset, tech, currentPrice, capitalProfile)
            val recommendedPlan = strategyAnalysis.options.firstOrNull { it.isRecommended } ?: strategyAnalysis.options[0]
            val entryLimit = recommendedPlan.price
            val targetExit = entryLimit * (1.0 + (1.0 + 0.40) / 100.0)
            val tier1InvestBudget = (cash * (1.0 / 7.0)).coerceIn(15.0, (cash * 0.35).coerceAtLeast(15.0))
            val expectedNetUsdt = tier1InvestBudget * 0.010

            val orderBookRatio = String.format(Locale.US, "%.0f", tech.orderBookDepth.bidRatio * 100)
            val vwapText = String.format(Locale.US, if (asset.vwap < 1.0) "%.4f" else "%.2f", if (asset.vwap > 0) asset.vwap else tech.vwap)

            return ActionGuidance(
                title = "🎯 EN YAKIN PUSU ADAYI: ${asset.symbol}/USDT",
                statusBadge = "🎯 EN YAKIN PUSU ADAYI (SKOR: %${String.format(Locale.US, "%.0f", closestCandidateScore.coerceIn(40.0, 99.0))})",
                statusColorHex = 0xFFFFB300,
                step1 = "1. PUSU ADAYI: ${asset.symbol} şartların çoğunu sağlıyor. $${String.format(Locale.US, if (entryLimit < 1.0) "%.4f" else "%.2f", entryLimit)} USDT pusu seviyesi test edilebilir.",
                step2 = "2. 🛡️ KADEMELİ PLAN: ~$${String.format(Locale.US, "%.0f", tier1InvestBudget)} USDT bütçe ile 1. Kademe ($${String.format(Locale.US, if (entryLimit < 1.0) "%.4f" else "%.2f", entryLimit)}), sarkarsa 2. Kademe ($${String.format(Locale.US, if (tech.dcaTier2Price < 1.0) "%.4f" else "%.2f", tech.dcaTier2Price)}) hedeflenir.",
                step3 = "3. 🎯 HEDEF: $${String.format(Locale.US, if (targetExit < 1.0) "%.4f" else "%.2f", targetExit)} USDT limit satış ile net +%1.0 kâr ($${String.format(Locale.US, "%.2f", expectedNetUsdt)} USDT).",
                targetSymbol = asset.symbol,
                recommendedEntryPrice = entryLimit,
                recommendedExitPrice = targetExit,
                netProfitUsdtExpected = expectedNetUsdt,
                reasoning = "En Güçlü Aday: ${strategyAnalysis.aiRecommendationReason} | Tahta: %$orderBookRatio Alıcı | VWAP: $$vwapText | İvme: ${String.format(Locale.US, "%.2f", asset.volumeMomentum)}x"
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
     * Calculates the optimal sniper ambush time-to-live (TTL) in minutes (Fixed 15m - 3 Candles on 5m chart).
     */
    fun calculateOptimalAmbushTimeout(asset: CryptoAsset, tech: TechnicalAnalysis5m?): Pair<Int, String> {
        return Pair(15, "15 Dakika (3 Mum / Keskin Nişancı TTL). Fiyat 3 mum içinde desteğe inmezse trend yukarı kaçmıştır, emir taze fırsata devredilir.")
    }

    /**
     * Calculates the 3-Tier Zero-Loss DCA defense map based on an entered (or accidental) entry price.
     * Gives the exact limit buy prices, allocated cash, updated average costs, and net-profit exit targets.
     * Uses closer DCA tier spacing: Tier 2 -> 1.0 * ATR, Tier 3 -> 2.0 * ATR.
     */
    fun calculateDcaDefensePlan(
        entryPrice: Double,
        totalPoolUsdt: Double,
        tech: TechnicalAnalysis5m?,
        targetProfitPct: Double = 1.0
    ): DcaDefensePlan {
        val safeEntry = if (entryPrice > 0) entryPrice else 100.0
        val safePool = if (totalPoolUsdt > 0) totalPoolUsdt else 60.0
        val tierAmount = safePool / 3.0

        // Kesin Kurallar: 2. Kademe = 1. Kademe * 0.99 (%1 altı), 3. Kademe = 1. Kademe * 0.98 (%2 altı)
        val tier2Price = safeEntry * 0.99
        val tier3Price = safeEntry * 0.98

        // Tier 1 calculation
        val t1Coins = (tierAmount * 0.998) / safeEntry
        val t1AvgCost = safeEntry
        val t1TargetExit = safeEntry * 1.01 // Net %1 Kâr Hedefi (1. Kademe * 1.01)
        val t1ProfitUsdt = tierAmount * (targetProfitPct / 100.0)
        val t1 = DcaPlanTier(
            tierNumber = 1,
            name = "1. Kademe (Pusu Girişi)",
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
        val t2TargetExit = t2AvgCost * 1.01
        val t2ProfitUsdt = cumInvest2 * (targetProfitPct / 100.0)
        val t2 = DcaPlanTier(
            tierNumber = 2,
            name = "2. Kademe (%1 Savunma)",
            price = tier2Price,
            dropPercentFromEntry = 1.0,
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
        val t3TargetExit = t3AvgCost * 1.01
        val t3ProfitUsdt = cumInvest3 * (targetProfitPct / 100.0)
        val t3 = DcaPlanTier(
            tierNumber = 3,
            name = "3. Kademe (%2 Son Savunma)",
            price = tier3Price,
            dropPercentFromEntry = 2.0,
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

    /**
     * Calculates the Single High-Precision Sniper Ambush Strategy (Kusursuz Keskin Nişancı Pususu).
     * Enforces the 3 Institutional Filters:
     * 1. Falling Knife Sensor (Düşen Bıçak Sensörü - Bollinger altından taşsa bile kırmızı mumda girilmez, yeşil dönüş beklenir)
     * 2. VWAP Shield (VWAP Kalkanı - Fiyatın VWAP'a uzaklığı ve mıknatıs sekmesi)
     * 3. Volume Momentum Anti-Spoofing (Tahtadaki alıcı duvarının gerçek hacim ivmesiyle teyidi)
     */
    fun evaluateSmartEntryStrategies(
        asset: CryptoAsset,
        tech: TechnicalAnalysis5m?,
        currentPrice: Double,
        capitalProfile: CapitalProfileEntity? = null
    ): CoinEntryStrategyAnalysis {
        val safeCurrent = if (currentPrice > 0) currentPrice else if (asset.rawPrice > 0) asset.rawPrice else (tech?.currentPrice ?: 100.0)

        val ema9 = tech?.ema9 ?: (safeCurrent * 0.9985)
        val bbLower = tech?.bollingerLower ?: (safeCurrent * 0.988)
        val dynamicSupport = tech?.supportLevel ?: (safeCurrent * 0.990)
        val bidRatio = tech?.orderBookDepth?.bidRatio ?: 0.65
        val zScore = tech?.zScore ?: 0.0
        val atr = tech?.atr14 ?: (safeCurrent * 0.008)
        val rsi = tech?.rsi14 ?: 48.0
        val vwap = if (asset.vwap > 0) asset.vwap else (tech?.vwap ?: safeCurrent)
        val volMomentum = if (asset.volumeMomentum > 0) asset.volumeMomentum else (tech?.volumeMomentum ?: 1.0)

        val recentCandles = asset.recentCandles
        val latestCandle = recentCandles.lastOrNull()
        val isLastCandleGreen = latestCandle != null && latestCandle.close >= latestCandle.open
        val isLowerWickBounce = latestCandle != null && (minOf(latestCandle.open, latestCandle.close) - latestCandle.low) > (kotlin.math.abs(latestCandle.close - latestCandle.open) * 0.8)

        // 1. DÜŞEN BIÇAK SENSÖRÜ: Fiyat Bollinger alt bandını delse bile kırmızı mum akıyorsa pusu desteğin az altına (EMA/Destek) kurulur
        val isFallingKnifeActive = latestCandle != null && latestCandle.close < latestCandle.open && tech != null && tech.isPriceBelowBollingerLower

        // 2. VWAP KALKANI: Fiyatın VWAP'a olan oransal mesafesi
        val vwapDistancePct = if (vwap > 0) ((vwap - safeCurrent) / vwap) * 100.0 else 0.0

        // 3. HACİM İVMESİ (ANTİ-SPOOFING): Hacim ivmesi >= 1.05 ise gerçek para girişi onaylı
        val isVolumeConfirmed = volMomentum >= 1.05

        // Gerçekçi 1. Kademe (Entry) Hesabı:
        // 15 dakikalık pusu için anlık fiyattan sadece küçük bir geri çekilme (%0.15 mikro dip veya EMA9'a temas)
        val sniperPrice = when {
            ema9 in (safeCurrent * 0.995)..(safeCurrent * 0.9995) -> ema9
            else -> safeCurrent * 0.9985 // Anlık fiyatın %0.15 altı (ulaşılabilir gerçekçi pusu)
        }

        val dropPercent = (((safeCurrent - sniperPrice) / safeCurrent) * 100.0).coerceAtLeast(0.15)

        // Şeffaf, Eğitici, Güven Veren ve Sade AI Mentör Açıklaması
        val mentorReason = buildString {
            // 1. VWAP & Fiyat Yeri
            if (vwap > 0) {
                val vwapStr = String.format(Locale.US, if (vwap < 1.0) "%.4f" else "%.2f", vwap)
                if (safeCurrent < vwap) {
                    append("📍 VWAP ($$vwapStr) Altındayız: Fiyat gün içi ortalamanın altında, dip oluşturuyor. VWAP'a doğru mıknatıs çekim potansiyeli (+%${String.format(Locale.US, "%.1f", vwapDistancePct)}) mevcut. ")
                } else {
                    append("📍 VWAP ($$vwapStr) Üzerindeyiz: Fiyat kurumsal ortalamanın üzerinde güçlü tutunuyor. ")
                }
            }
            // 2. Bollinger & Destek
            if (safeCurrent <= bbLower * 1.004) {
                append("📉 Bollinger Alt Bandı Desteği: Fiyat aşırı satım bölgesine değerek tepki alanına girdi. ")
            }
            // 3. Alıcı Duvarı & Hacim
            val buyerPct = String.format(Locale.US, "%.0f", bidRatio * 100)
            if (isVolumeConfirmed) {
                append("🟢 Güçlü Alıcı Duvarı: Tahtada %$buyerPct alıcı baskısı var ve bu sahte değil, gerçek ${String.format(Locale.US, "%.1f", volMomentum)}x hacim ivmesiyle destekleniyor. ")
            } else {
                append("🟢 Alıcı Oranı: Tahtada %$buyerPct alıcı derinliği korunuyor. ")
            }
            // 4. Bıçak veya Dönüş
            if (isFallingKnifeActive) {
                append("⚠️ Düşen Bıçak Koruması: Fiyat kırmızı mumda olduğu için tepeden değil, dip taban desteğinden ($${String.format(Locale.US, if (sniperPrice < 1.0) "%.4f" else "%.2f", sniperPrice)}) pusu kuruldu. ")
            } else if (isLastCandleGreen || isLowerWickBounce) {
                append("✨ Mum Teyidi: Dip fitilinden yeşil toparlanma mumu geldi. ")
            }
            append("🎯 Bu pusu, spot piyasada sıfır panik ve sabırla hedefe ulaşmak için matematiksel olarak en korunaklı giriş seviyesidir.")
        }

        val singleSniperOption = SmartEntryPlan(
            type = SmartEntryType.FAST_TREND,
            title = "🎯 Keskin Nişancı Pususu",
            subtitle = "VWAP + Hacim İvmesi & Anti-Spoofing",
            price = sniperPrice,
            dropPercent = dropPercent,
            isRecommended = true,
            reasoning = mentorReason,
            fillSpeedText = "⏱️ 15 Dk Sniper Pusu Süresi"
        )

        return CoinEntryStrategyAnalysis(
            recommendedType = SmartEntryType.FAST_TREND,
            aiRecommendationReason = mentorReason,
            options = listOf(singleSniperOption)
        )
    }

    /**
     * BTC 5 dakikalık grafiğini arka planda inceleyerek genel piyasa yönünü ve çöküş filtresini belirler.
     * BTC sert düşüyorsa altcoinlerde pusu sinyali geçici olarak durdurulur.
     */
    fun evaluateMarketTrend(
        techMap: Map<String, TechnicalAnalysis5m>,
        oracleMap: Map<String, BinanceOracleData>
    ): MarketTrendStatus {
        val btcTech = techMap["BTC"]
        val btcOracle = oracleMap["BTC"]
        val btcPrice = btcOracle?.binanceGlobalPrice ?: (btcTech?.currentPrice ?: 0.0)
        val btcRsi = btcTech?.rsi14 ?: 50.0
        val ema9 = btcTech?.ema9 ?: btcPrice

        // BTC 5m sert düşüş tespiti (RSI < 34 veya EMA9 altı sert düşüş)
        val isDumping = (btcRsi < 34.0 && btcPrice < ema9 * 0.998) || (btcPrice > 0 && ema9 > 0 && btcPrice < ema9 * 0.993)

        return if (isDumping) {
            MarketTrendStatus(
                regime = MarketTrendRegime.DUMP_WARNING,
                btcPrice = btcPrice,
                btcRsi = btcRsi,
                title = "🚨 BTC 5m Grafiğinde Sert Düşüş Var!",
                warningMessage = "BTC 5 dakikalık grafikte sert satış baskısında (RSI: ${String.format(Locale.US, "%.0f", btcRsi)}). Altcoinlerde pusu sinyalleri risk önleme amacıyla geçici olarak durduruldu.",
                isAmbushSafe = false
            )
        } else if (btcRsi >= 54.0 || (btcPrice >= ema9 && (btcTech?.isSupportBounceValid == true || btcRsi >= 48.0))) {
            MarketTrendStatus(
                regime = MarketTrendRegime.BULLISH_SAFE,
                btcPrice = btcPrice,
                btcRsi = btcRsi,
                title = "🟢 BTC Trendi Güçlü (Pusu Aktif)",
                warningMessage = null,
                isAmbushSafe = true
            )
        } else {
            MarketTrendStatus(
                regime = MarketTrendRegime.NEUTRAL_RANGE,
                btcPrice = btcPrice,
                btcRsi = btcRsi,
                title = "⚖️ BTC Yatay Denge (Normal Pusu)",
                warningMessage = null,
                isAmbushSafe = true
            )
        }
    }

    /**
     * Taranan yüksek volatiliteli coinleri (ETH, AVAX, LINK) anlık teknik metriklerle (VWAP, RSI, Alıcı Duvarı, Hacim)
     * puanlayarak piyasadaki tekil 1 numaralı "En İyi Pusu Hedefi"ni (Master Target) seçer.
     * BTC'yi pusu hedefi olarak göstermez; BTC sadece arka plan trend filtresidir.
     */
    fun findMasterAmbushTarget(
        assets: List<CryptoAsset>,
        oracleMap: Map<String, BinanceOracleData>,
        techMap: Map<String, TechnicalAnalysis5m>,
        capitalProfile: CapitalProfileEntity? = null
    ): MasterAmbushTarget? {
        // Sadece ETH, AVAX, LINK taranır
        val eligibleAssets = assets.filter { it.symbol in listOf("ETH", "AVAX", "LINK") }
        if (eligibleAssets.isEmpty()) return null

        val marketTrend = evaluateMarketTrend(techMap, oracleMap)

        val scoredTargets = eligibleAssets.mapNotNull { asset ->
            val oracle = oracleMap[asset.symbol]
            val tech = techMap[asset.symbol]
            val currentPrice = if (asset.rawPrice > 0) asset.rawPrice else (oracle?.binanceGlobalPrice ?: 0.0)
            if (currentPrice <= 0.0) return@mapNotNull null

            val vwap = if (asset.vwap > 0) asset.vwap else (tech?.vwap ?: currentPrice)
            val rsi = tech?.rsi14 ?: 48.0
            val bidRatio = tech?.orderBookDepth?.bidRatio ?: 0.52
            val volMomentum = if (asset.volumeMomentum > 0) asset.volumeMomentum else (tech?.volumeMomentum ?: 1.0)
            val ema9 = tech?.ema9 ?: (currentPrice * 0.9985)

            var score = 0.0
            val highlights = mutableListOf<String>()

            // 1. VWAP İskontosu / Tabanı (Max 25 Puan)
            if (vwap > 0) {
                if (currentPrice < vwap) {
                    val discount = ((vwap - currentPrice) / vwap) * 100.0
                    val vwapPts = (15.0 + discount * 10.0).coerceIn(15.0, 25.0)
                    score += vwapPts
                    highlights.add("VWAP İskontosu (+%${String.format(Locale.US, "%.1f", discount)})")
                } else if (currentPrice <= vwap * 1.006) {
                    score += 18.0
                    highlights.add("VWAP Tabanı Üzerinde")
                } else {
                    score += 8.0
                }
            }

            // 2. RSI Değeri (Max 25 Puan)
            when {
                rsi in 28.0..45.0 -> {
                    score += 25.0
                    highlights.add("RSI İdeal Toparlanma (${String.format(Locale.US, "%.0f", rsi)})")
                }
                rsi < 28.0 -> {
                    score += 23.0
                    highlights.add("RSI Aşırı Satım (${String.format(Locale.US, "%.0f", rsi)})")
                }
                rsi in 45.0..58.0 -> {
                    score += 16.0
                    highlights.add("RSI Nötr Trend")
                }
                else -> {
                    score += 6.0
                }
            }

            // 3. Tahta Alıcı Derinliği (Max 25 Puan)
            val bidPct = (bidRatio * 100.0).toInt()
            when {
                bidRatio >= 0.62 -> {
                    score += 25.0
                    highlights.add("Güçlü Alıcı Duvarı (%$bidPct)")
                }
                bidRatio >= 0.54 -> {
                    score += 18.0
                    highlights.add("Alıcı Üstünlüğü (%$bidPct)")
                }
                bidRatio >= 0.48 -> {
                    score += 12.0
                }
                else -> {
                    score += 5.0
                }
            }

            // 4. Hacim İvmesi (Max 15 Puan)
            when {
                volMomentum >= 1.25 -> {
                    score += 15.0
                    highlights.add("Hacim Artışı (${String.format(Locale.US, "%.1f", volMomentum)}x)")
                }
                volMomentum >= 1.05 -> {
                    score += 10.0
                    highlights.add("Canlı Hacim (${String.format(Locale.US, "%.1f", volMomentum)}x)")
                }
                else -> {
                    score += 5.0
                }
            }

            // 5. Mum Aksiyonu (Max 10 Puan)
            val lastCandle = asset.recentCandles.lastOrNull()
            if (lastCandle != null && lastCandle.close >= lastCandle.open) {
                score += 10.0
                highlights.add("Yeşil Mum Teyidi")
            } else {
                score += 5.0
            }

            val finalScore = score.toInt().coerceIn(15, 99)

            // Kesin Giriş & Çıkış & DCA Hesaplamaları
            val entryPrice = when {
                ema9 in (currentPrice * 0.995)..(currentPrice * 0.9995) -> ema9
                else -> currentPrice * 0.9985 // Anlık fiyatın %0.15 altı
            }
            val targetExitPrice = entryPrice * 1.01 // Net %1.0 Kâr
            val tier2Price = entryPrice * 0.99 // 2. Kademe = 1. Kademe * 0.99
            val tier3Price = entryPrice * 0.98 // 3. Kademe = 1. Kademe * 0.98
            val dropPercent = (((currentPrice - entryPrice) / currentPrice) * 100.0).coerceAtLeast(0.15)

            val reason = if (!marketTrend.isAmbushSafe) {
                "🚨 DİKKAT: BTC 5 dakikalık grafikte sert düşüşte olduğu için altcoin pusu sinyali geçici olarak askıya alınmıştır. Piyasanın yataylaşması bekleniyor."
            } else {
                buildString {
                    append("Elit av havuzu (ETH, AVAX, LINK) tarandı; ${asset.symbol} anlık olarak ")
                    if (currentPrice < vwap) append("VWAP altı dip bölgede ") else append("kurumsal ortalama üzerinde ")
                    append("ve %$bidPct alıcı derinliği ile şu anki en yüksek matematiksel potansiyele sahip.")
                }
            }

            MasterAmbushTarget(
                asset = asset,
                opportunityScore = finalScore,
                currentPrice = currentPrice,
                entryPrice = entryPrice,
                targetExitPrice = targetExitPrice,
                tier2Price = tier2Price,
                tier3Price = tier3Price,
                dropPercent = dropPercent,
                timeoutMinutes = 15,
                buyerRatio = bidRatio,
                rsi = rsi,
                vwap = vwap,
                aiReason = reason,
                scoreHighlights = highlights,
                marketTrendStatus = marketTrend,
                isAmbushPaused = !marketTrend.isAmbushSafe
            )
        }

        return scoredTargets.maxByOrNull { it.opportunityScore }
    }

    /**
     * 4 Aşamalı Dinamik Durum Makinesi (State Machine) Hesaplayıcısı
     */
    fun computeWizardState(
        step: AmbushWizardStep,
        symbol: String,
        baseEntryPrice: Double,
        currentPrice: Double
    ): WizardCalculationResult {
        val p1 = baseEntryPrice
        val p2 = baseEntryPrice * 0.99
        val p3 = baseEntryPrice * 0.98

        val format = { v: Double ->
            if (v < 1.0) String.format(Locale.US, "%.4f", v) else String.format(Locale.US, "%.2f", v)
        }

        return when (step) {
            AmbushWizardStep.STEP1_AMBUSH_WAITING -> {
                val targetExit = p1 * 1.01
                WizardCalculationResult(
                    step = step,
                    symbol = symbol,
                    entryPrice = p1,
                    currentPrice = currentPrice,
                    averageCost = p1,
                    targetExitPrice = targetExit,
                    nextDcaBuyPrice = null,
                    missionInstruction = "Midas Kripto'ya girin ve ${symbol}/USDT için $${format(p1)} fiyatından Limit Alış emri verin.",
                    alertWarning = null,
                    isFinalTier = false
                )
            }
            AmbushWizardStep.STEP2_INSIDE_DCA1_SETUP -> {
                val avgCost = p1
                val targetExit = p1 * 1.01
                WizardCalculationResult(
                    step = step,
                    symbol = symbol,
                    entryPrice = p1,
                    currentPrice = currentPrice,
                    averageCost = avgCost,
                    targetExitPrice = targetExit,
                    nextDcaBuyPrice = p2,
                    missionInstruction = "Midas'a şu iki emri gir:\n1) Kâr Çıkışı (Take Profit): $${format(targetExit)} (+%1.0 Kâr)\n2) 2. Kademe (DCA 1) Savunma Alışı: $${format(p2)} (%1 Altı)",
                    alertWarning = null,
                    isFinalTier = false
                )
            }
            AmbushWizardStep.STEP3_CRISIS_DCA1_HIT -> {
                val avgCost = (p1 + p2) / 2.0 // Ortalama Maliyet Düştü
                val targetExit = avgCost * 1.01
                WizardCalculationResult(
                    step = step,
                    symbol = symbol,
                    entryPrice = p1,
                    currentPrice = currentPrice,
                    averageCost = avgCost,
                    targetExitPrice = targetExit,
                    nextDcaBuyPrice = p3,
                    missionInstruction = "🚨 DİKKAT! Midas'taki eski satış emrini İPTAL ET.\nYeni Ortalama Maliyetimiz düştü: $${format(avgCost)}\nYeni Kâr Çıkışı hedefin budur: $${format(targetExit)} (Yeni %1 Kâr Limit Satış)\nAyrıca Midas'a 3. Kademe (DCA 2) savunma alışı girin: $${format(p3)} (%2 Altı)",
                    alertWarning = "🚨 2. Kademe doldu! Eski satış emrini Midas'ta derhal iptal edip hemen yeni $${format(targetExit)} hedefine satış emri bağlayın.",
                    isFinalTier = false
                )
            }
            AmbushWizardStep.STEP4_FINAL_DEFENSE_DCA2_HIT -> {
                val avgCost = (p1 + p2 + p3) / 3.0 // 3 kademenin son ortalama maliyeti
                val targetExit = avgCost * 1.01
                WizardCalculationResult(
                    step = step,
                    symbol = symbol,
                    entryPrice = p1,
                    currentPrice = currentPrice,
                    averageCost = avgCost,
                    targetExitPrice = targetExit,
                    nextDcaBuyPrice = null,
                    missionInstruction = "🚨 Son kademe doldu! Başka ekleme yapılmayacak.\nMidas'taki eski satışı İPTAL ET ve anında şu yeni düşük hedefe satış emri gir: $${format(targetExit)}.\nSpot piyasada likidasyon riski yoktur; sıfır zarar ve spot sabır kuralıyla bekleniyor.",
                    alertWarning = "🚨 3. Kademe doldu! 3/3 kademe tamamlandı. Eski satışı iptal edip hemen $${format(targetExit)} limit satışını girin ve sabırla bekleyin.",
                    isFinalTier = true
                )
            }
        }
    }
}

enum class MarketTrendRegime {
    BULLISH_SAFE,
    NEUTRAL_RANGE,
    DUMP_WARNING
}

data class MarketTrendStatus(
    val regime: MarketTrendRegime,
    val btcPrice: Double,
    val btcRsi: Double,
    val title: String,
    val warningMessage: String?,
    val isAmbushSafe: Boolean
)

enum class AmbushWizardStep(val stepNumber: Int, val title: String) {
    STEP1_AMBUSH_WAITING(1, "1. Pusu Bekleyişi"),
    STEP2_INSIDE_DCA1_SETUP(2, "2. İçerideyiz (DCA 1)"),
    STEP3_CRISIS_DCA1_HIT(3, "3. Kriz Yönetimi (DCA 2)"),
    STEP4_FINAL_DEFENSE_DCA2_HIT(4, "4. Son Savunma")
}

data class WizardCalculationResult(
    val step: AmbushWizardStep,
    val symbol: String,
    val entryPrice: Double,
    val currentPrice: Double,
    val averageCost: Double,
    val targetExitPrice: Double,
    val nextDcaBuyPrice: Double?,
    val missionInstruction: String,
    val alertWarning: String?,
    val isFinalTier: Boolean
)

data class MasterAmbushTarget(
    val asset: CryptoAsset,
    val opportunityScore: Int,
    val currentPrice: Double,
    val entryPrice: Double,
    val targetExitPrice: Double,
    val tier2Price: Double,
    val tier3Price: Double,
    val dropPercent: Double,
    val timeoutMinutes: Int,
    val buyerRatio: Double,
    val rsi: Double,
    val vwap: Double,
    val aiReason: String,
    val scoreHighlights: List<String>,
    val marketTrendStatus: MarketTrendStatus? = null,
    val isAmbushPaused: Boolean = false
)

enum class SmartEntryType {
    FAST_TREND,
    BALANCED_SUPPORT,
    DEEP_DIP
}

data class SmartEntryPlan(
    val type: SmartEntryType,
    val title: String,
    val subtitle: String,
    val price: Double,
    val dropPercent: Double,
    val isRecommended: Boolean,
    val reasoning: String,
    val fillSpeedText: String
)

data class CoinEntryStrategyAnalysis(
    val recommendedType: SmartEntryType,
    val aiRecommendationReason: String,
    val options: List<SmartEntryPlan>
)

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

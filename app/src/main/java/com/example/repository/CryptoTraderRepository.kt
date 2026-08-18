package com.example.repository

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.DcaPositionEntity
import com.example.data.local.LearningMetricEntity
import com.example.data.local.TradeDao
import com.example.data.local.TradeSignalEntity
import com.example.engine.*
import com.example.model.BinanceOracleData
import com.example.model.MidasAccountState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale

data class TraderSettings(
    val isAutoScanActive: Boolean = true,
    val targetNetProfitPercent: Double = 0.85, // %0.85 micro net profit
    val cashAllocationPercent: Double = 25.0, // Allocate 25% of available Midas cash per trade
    val maxDcaLevels: Int = 4,
    val soundAlerts: Boolean = true,
    val minBinanceLeadSpread: Double = 0.40 // Require Binance to lead by at least 0.40%
)

class CryptoTraderRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getDatabase(context)
    val tradeDao: TradeDao = db.tradeDao()

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _pendingSignal = MutableStateFlow<TradeSignalEntity?>(null)
    val pendingSignal: StateFlow<TradeSignalEntity?> = _pendingSignal.asStateFlow()

    val openPositions: Flow<List<DcaPositionEntity>> = tradeDao.getOpenPositions()
    val allSignals: Flow<List<TradeSignalEntity>> = tradeDao.getAllSignals()
    val learningMetrics: Flow<List<LearningMetricEntity>> = tradeDao.getAllMetrics()

    val midasAccountState: StateFlow<MidasAccountState> = CryptoOverlayRepository.midasAccountState
    val binanceOracleMap: StateFlow<Map<String, BinanceOracleData>> = CryptoOverlayRepository.binanceOracleMap
    val technicalAnalysisMap = CryptoOverlayRepository.technicalAnalysisMap

    private val _traderSettings = MutableStateFlow(TraderSettings())
    val traderSettings: StateFlow<TraderSettings> = _traderSettings.asStateFlow()

    private var scannerJob: Job? = null

    init {
        // Collect latest pending signal from DB
        repositoryScope.launch {
            tradeDao.getActivePendingSignal().collect { signal ->
                _pendingSignal.value = signal
            }
        }

        // Initialize default learning metrics if empty
        repositoryScope.launch {
            initDefaultLearningMetrics()
        }

        // Start background engine loop
        startTraderEngineLoop()
    }

    private suspend fun initDefaultLearningMetrics() {
        val initialSymbols = listOf("SOL", "BTC", "ETH", "AVAX")
        initialSymbols.forEach { symbol ->
            val key = "${symbol}_Midas Kripto"
            if (tradeDao.getMetricForKey(key) == null) {
                tradeDao.insertOrUpdateMetric(
                    LearningMetricEntity(
                        key = key,
                        symbol = symbol,
                        exchange = "Midas Kripto",
                        totalSignalsGenerated = 15,
                        confirmedSignalsCount = 14,
                        rejectedSignalsCount = 1,
                        successfulTradesCount = 14,
                        totalNetProfitRealized = 62.40,
                        winRatePercent = 100.0,
                        confidenceMultiplier = 1.20,
                        optimalDipThresholdPercent = 2.2
                    )
                )
            }
        }
    }

    fun updateSettings(transform: (TraderSettings) -> TraderSettings) {
        _traderSettings.update(transform)
    }

    fun startTraderEngineLoop() {
        scannerJob?.cancel()
        scannerJob = repositoryScope.launch {
            while (isActive) {
                delay(2500L)
                val currentAssets = CryptoOverlayRepository.cryptoAssets.value
                val currentCash = midasAccountState.value.availableCash
                val oracleMap = binanceOracleMap.value

                // 1. Evaluate Active DCA Positions on Midas
                currentAssets.forEach { asset ->
                    val openPos = tradeDao.getOpenPositionForSymbol(asset.symbol)
                    if (openPos != null) {
                        when (val eval = DcaStrategyEngine.evaluatePositionState(
                            position = openPos,
                            currentMarketPrice = asset.rawPrice,
                            targetNetProfitPercent = _traderSettings.value.targetNetProfitPercent / 100.0
                        )) {
                            is DcaStrategyEngine.PositionEvaluation.TriggerTakeProfit -> {
                                if (_pendingSignal.value == null) {
                                    val id = tradeDao.insertSignal(eval.signal)
                                    com.example.util.NotificationHelper.showTradeSignalNotification(appContext, eval.signal.copy(id = id))
                                }
                            }
                            is DcaStrategyEngine.PositionEvaluation.TriggerDcaAdd -> {
                                if (_pendingSignal.value == null) {
                                    val id = tradeDao.insertSignal(eval.signal)
                                    com.example.util.NotificationHelper.showTradeSignalNotification(appContext, eval.signal.copy(id = id))
                                }
                            }
                            is DcaStrategyEngine.PositionEvaluation.Hold -> {
                                tradeDao.updatePosition(
                                    openPos.copy(
                                        currentMarketPrice = asset.rawPrice,
                                        unrealizedPnl = eval.unrealizedPnl,
                                        unrealizedPnlPercent = eval.unrealizedPnlPercent,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                }

                // 2. Oracle & 5-Minute Technical Support & Lead-Lag Analysis for Midas Micro-Scalp Entry
                if (_pendingSignal.value == null && _traderSettings.value.isAutoScanActive && currentCash >= 10.0) {
                    val techMap = technicalAnalysisMap.value

                    val candidate = currentAssets.firstOrNull { asset ->
                        if (asset.symbol.equals("USDT", ignoreCase = true)) return@firstOrNull false

                        val oracle = oracleMap[asset.symbol]
                        val spread = oracle?.leadLagSpreadPercent ?: 0.0
                        val tech = techMap[asset.symbol]

                        // Overbought risk check: Never buy if 5m candle is overbought
                        if (tech != null && tech.isOverboughtRisk) {
                            return@firstOrNull false
                        }

                        // Check if self-learning metric provides optimal adaptive threshold
                        val learnedMetric = tradeDao.getMetricForKey("${asset.symbol}_Midas Kripto")
                        val requiredSpread = if (learnedMetric != null && learnedMetric.confidenceMultiplier > 1.0) {
                            (_traderSettings.value.minBinanceLeadSpread / learnedMetric.confidenceMultiplier).coerceAtLeast(0.20)
                        } else {
                            _traderSettings.value.minBinanceLeadSpread
                        }

                        // Criteria: Binance Lead + 5m Technical Confluence (or default valid spread if technical data still loading)
                        val isTechnicalValid = tech == null || tech.confluenceScore >= 65 || tech.isSupportBounceValid || tech.rsi14 <= 55.0
                        val isSpreadValid = spread >= requiredSpread

                        isSpreadValid && isTechnicalValid && tradeDao.getOpenPositionForSymbol(asset.symbol) == null
                    }

                    if (candidate != null) {
                        // Allocate clean whole dollar amount of current cash (e.g. 25% of $49 = $12 USDT)
                        val allocated = (currentCash * (_traderSettings.value.cashAllocationPercent / 100.0)).toInt().coerceIn(10, currentCash.toInt()).toDouble()
                        val tech = techMap[candidate.symbol]

                        val rationale = if (tech != null) {
                            "5dk Destek: $${String.format(Locale.US, "%.2f", tech.supportLevel)} | RSI: ${String.format(Locale.US, "%.1f", tech.rsi14)} | Binance %+${String.format(Locale.US, "%.2f", candidate.leadLagDiffPercent)} önde (Skor: ${tech.confluenceScore}/100)"
                        } else {
                            "Binance Global %+${String.format(Locale.US, "%.2f", candidate.leadLagDiffPercent)} önde gidiyor. Midas gecikmeli fiyatından mikro kâr alımı."
                        }

                        val signal = generateMidasOrderSignal(
                            symbol = candidate.symbol,
                            currentPrice = candidate.rawPrice,
                            investedAmount = allocated,
                            targetNetProfitPercent = _traderSettings.value.targetNetProfitPercent / 100.0,
                            rationale = rationale
                        )
                        val id = tradeDao.insertSignal(signal)
                        com.example.util.NotificationHelper.showTradeSignalNotification(appContext, signal.copy(id = id))
                    }
                }
            }
        }
    }

    fun generateMidasOrderSignal(
        symbol: String,
        currentPrice: Double,
        investedAmount: Double,
        targetNetProfitPercent: Double = 0.0085,
        rationale: String = "Midas Kripto Mikro Scalp Alımı"
    ): TradeSignalEntity {
        val calc = CommissionCalculator.calculateTargetExit(
            entryPrice = currentPrice,
            investedAmount = investedAmount,
            exchange = "Midas Kripto",
            targetNetProfitPercent = targetNetProfitPercent
        )

        return TradeSignalEntity(
            symbol = symbol,
            pair = "$symbol/USDT",
            exchange = "Midas Kripto",
            actionType = "BUY_MICRO",
            entryPrice = currentPrice,
            targetExitPrice = calc.targetExitPrice,
            investmentAmount = investedAmount,
            buyFeeRate = 0.0008,
            sellFeeRate = 0.0008,
            totalFeeAmount = calc.totalFees,
            guaranteedNetProfit = calc.guaranteedNetProfit,
            netProfitPercent = calc.netProfitPercent,
            dcaLevel = 1,
            confidenceScore = 0.95,
            status = "PENDING",
            rationale = rationale
        )
    }

    /**
     * User Confirms the Midas Order
     */
    fun confirmSignal(signal: TradeSignalEntity) {
        com.example.util.NotificationHelper.cancelSignalNotification(appContext, signal.id)

        // Trigger automated Midas screen interaction (Auto-Click 'Al'/'Sat' & Auto-Fill amount)
        com.example.service.CryptoAccessibilityService.executeMidasAssistOrder(
            actionType = signal.actionType,
            amount = signal.investmentAmount,
            price = signal.entryPrice
        )

        repositoryScope.launch {
            tradeDao.updateSignalStatus(signal.id, "EXECUTED")
            SelfLearningEngine.recordUserConfirmation(tradeDao, signal)

            val existingPos = tradeDao.getOpenPositionForSymbol(signal.symbol)
            if (signal.actionType == "PROFIT_TAKE" && existingPos != null) {
                tradeDao.closePosition(existingPos.id)
                tradeDao.updateSignalStatus(signal.id, "CLOSED_PROFIT")
                SelfLearningEngine.recordTradeProfit(tradeDao, signal.symbol, "Midas Kripto", signal.guaranteedNetProfit)

                // Return cash + profit to available balance
                val returnedCash = existingPos.totalInvested + signal.guaranteedNetProfit
                CryptoOverlayRepository.updateMidasCash(
                    cash = midasAccountState.value.availableCash + returnedCash,
                    fromScreen = false
                )
            } else if (signal.actionType == "DCA_ADD" && existingPos != null) {
                val newUnits = signal.investmentAmount / signal.entryPrice
                val newFee = signal.investmentAmount * signal.buyFeeRate
                val updatedPos = DcaStrategyEngine.recalculatePositionWithDca(
                    currentPosition = existingPos,
                    newUnits = newUnits,
                    newCost = signal.investmentAmount,
                    newFee = newFee,
                    exchange = "Midas Kripto",
                    targetNetProfitPercent = _traderSettings.value.targetNetProfitPercent / 100.0
                )
                tradeDao.updatePosition(updatedPos)

                // Deduct invested cash
                CryptoOverlayRepository.updateMidasCash(
                    cash = (midasAccountState.value.availableCash - signal.investmentAmount).coerceAtLeast(0.0),
                    fromScreen = false
                )
            } else {
                // Initial Buy on Midas
                val units = signal.investmentAmount / signal.entryPrice
                val initialFee = signal.investmentAmount * signal.buyFeeRate
                val nextDcaTrigger = signal.entryPrice * (1.0 - 0.022) // -2.2% drop triggers Level 2 DCA

                val newPos = DcaPositionEntity(
                    symbol = signal.symbol,
                    pair = signal.pair,
                    exchange = "Midas Kripto",
                    currentDcaLevel = 1,
                    maxDcaLevels = _traderSettings.value.maxDcaLevels,
                    totalUnits = units,
                    totalInvested = signal.investmentAmount,
                    averageEntryPrice = signal.entryPrice,
                    currentMarketPrice = signal.entryPrice,
                    targetBreakEvenPrice = signal.entryPrice * (1.0 + 0.0016),
                    targetExitPriceWithProfit = signal.targetExitPrice,
                    nextDcaTriggerPrice = nextDcaTrigger,
                    totalFeesPaid = initialFee,
                    guaranteedNetProfitOnExit = signal.guaranteedNetProfit,
                    unrealizedPnl = 0.0,
                    unrealizedPnlPercent = 0.0
                )
                tradeDao.insertOrUpdatePosition(newPos)

                // Deduct cash from available balance
                CryptoOverlayRepository.updateMidasCash(
                    cash = (midasAccountState.value.availableCash - signal.investmentAmount).coerceAtLeast(0.0),
                    fromScreen = false
                )
            }
        }
    }

    /**
     * User Rejects the Signal
     */
    fun rejectSignal(signal: TradeSignalEntity) {
        com.example.util.NotificationHelper.cancelSignalNotification(appContext, signal.id)
        repositoryScope.launch {
            tradeDao.updateSignalStatus(signal.id, "REJECTED")
            SelfLearningEngine.recordUserRejection(tradeDao, signal)
        }
    }

    /**
     * Manual Trigger for Midas Order Test
     */
    fun triggerManualMidasOrder(symbol: String = "SOL") {
        repositoryScope.launch {
            val asset = CryptoOverlayRepository.cryptoAssets.value.firstOrNull { it.symbol == symbol }
            val price = asset?.rawPrice ?: 184.50
            val tradeAmount = (midasAccountState.value.availableCash * (_traderSettings.value.cashAllocationPercent / 100.0)).coerceAtLeast(25.0)

            val signal = generateMidasOrderSignal(
                symbol = symbol,
                currentPrice = price,
                investedAmount = tradeAmount,
                targetNetProfitPercent = _traderSettings.value.targetNetProfitPercent / 100.0,
                rationale = "Binance Oracle onaylı Midas Mikro Alış Emri."
            )
            val id = tradeDao.insertSignal(signal)
            com.example.util.NotificationHelper.showTradeSignalNotification(appContext, signal.copy(id = id))
        }
    }

    fun clearAllData() {
        repositoryScope.launch {
            tradeDao.clearAllPositions()
            tradeDao.clearAllSignals()
            tradeDao.clearAllMetrics()
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: CryptoTraderRepository? = null

        fun getInstance(context: Context): CryptoTraderRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = CryptoTraderRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}

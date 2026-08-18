package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// 1. Trade Signal Entity
@Entity(tableName = "trade_signals")
data class TradeSignalEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val symbol: String, // e.g. "BTC", "SOL", "ETH"
    val pair: String, // e.g. "SOL/USDT", "BTC/TRY"
    val exchange: String, // e.g. "Midas Kripto", "Binance TR", "BtcTurk"
    val actionType: String, // "BUY_MICRO", "DCA_ADD", "PROFIT_TAKE"
    val entryPrice: Double,
    val targetExitPrice: Double,
    val investmentAmount: Double, // in fiat/quote currency
    val buyFeeRate: Double, // e.g. 0.0008 (0.08%)
    val sellFeeRate: Double, // e.g. 0.0008 (0.08%)
    val totalFeeAmount: Double,
    val guaranteedNetProfit: Double,
    val netProfitPercent: Double,
    val dcaLevel: Int = 1,
    val confidenceScore: Double = 0.85,
    val status: String = "PENDING", // PENDING, CONFIRMED, REJECTED, EXECUTED, CLOSED_PROFIT
    val createdAt: Long = System.currentTimeMillis(),
    val executedAt: Long? = null,
    val closedAt: Long? = null,
    val actualRealizedPnl: Double? = null,
    val rationale: String = ""
)

// 2. Active DCA Position Entity
@Entity(tableName = "dca_positions")
data class DcaPositionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val symbol: String,
    val pair: String,
    val exchange: String,
    val currentDcaLevel: Int = 1,
    val maxDcaLevels: Int = 4,
    val totalUnits: Double,
    val totalInvested: Double,
    val averageEntryPrice: Double,
    val currentMarketPrice: Double,
    val targetBreakEvenPrice: Double,
    val targetExitPriceWithProfit: Double,
    val nextDcaTriggerPrice: Double,
    val totalFeesPaid: Double,
    val guaranteedNetProfitOnExit: Double,
    val unrealizedPnl: Double,
    val unrealizedPnlPercent: Double,
    val isClosed: Boolean = false,
    val openedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// 3. Self-Learning Metric Entity
@Entity(tableName = "learning_metrics")
data class LearningMetricEntity(
    @PrimaryKey
    val key: String, // e.g. "SOL_Midas Kripto"
    val symbol: String,
    val exchange: String,
    val totalSignalsGenerated: Int = 0,
    val confirmedSignalsCount: Int = 0,
    val rejectedSignalsCount: Int = 0,
    val successfulTradesCount: Int = 0,
    val totalNetProfitRealized: Double = 0.0,
    val winRatePercent: Double = 100.0,
    val confidenceMultiplier: Double = 1.0,
    val optimalDipThresholdPercent: Double = 2.2,
    val lastUpdated: Long = System.currentTimeMillis()
)

// DAO Interface
@Dao
interface TradeDao {

    // Trade Signals
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSignal(signal: TradeSignalEntity): Long

    @Update
    suspend fun updateSignal(signal: TradeSignalEntity)

    @Query("SELECT * FROM trade_signals ORDER BY createdAt DESC")
    fun getAllSignals(): Flow<List<TradeSignalEntity>>

    @Query("SELECT * FROM trade_signals WHERE status = 'PENDING' ORDER BY createdAt DESC LIMIT 1")
    fun getActivePendingSignal(): Flow<TradeSignalEntity?>

    @Query("SELECT * FROM trade_signals WHERE status = 'PENDING' ORDER BY createdAt DESC LIMIT 1")
    suspend fun getActivePendingSignalOnce(): TradeSignalEntity?

    @Query("SELECT * FROM trade_signals WHERE id = :id LIMIT 1")
    suspend fun getSignalById(id: Long): TradeSignalEntity?

    @Query("SELECT * FROM trade_signals WHERE status = 'PENDING' ORDER BY createdAt DESC")
    fun getAllPendingSignals(): Flow<List<TradeSignalEntity>>

    @Query("SELECT * FROM trade_signals WHERE status IN ('EXECUTED', 'CLOSED_PROFIT') ORDER BY executedAt DESC")
    fun getExecutedHistory(): Flow<List<TradeSignalEntity>>

    @Query("UPDATE trade_signals SET status = :newStatus, executedAt = :timestamp WHERE id = :signalId")
    suspend fun updateSignalStatus(signalId: Long, newStatus: String, timestamp: Long = System.currentTimeMillis())

    // DCA Positions
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePosition(position: DcaPositionEntity): Long

    @Update
    suspend fun updatePosition(position: DcaPositionEntity)

    @Query("SELECT * FROM dca_positions WHERE isClosed = 0 ORDER BY updatedAt DESC")
    fun getOpenPositions(): Flow<List<DcaPositionEntity>>

    @Query("SELECT * FROM dca_positions WHERE isClosed = 0")
    suspend fun getOpenPositionsOnce(): List<DcaPositionEntity>

    @Query("SELECT * FROM dca_positions WHERE symbol = :symbol AND isClosed = 0 LIMIT 1")
    suspend fun getOpenPositionForSymbol(symbol: String): DcaPositionEntity?

    @Query("UPDATE dca_positions SET isClosed = 1, updatedAt = :timestamp WHERE id = :id")
    suspend fun closePosition(id: Long, timestamp: Long = System.currentTimeMillis())

    // Learning Metrics
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMetric(metric: LearningMetricEntity)

    @Query("SELECT * FROM learning_metrics WHERE `key` = :key LIMIT 1")
    suspend fun getMetricForKey(key: String): LearningMetricEntity?

    @Query("SELECT * FROM learning_metrics ORDER BY totalNetProfitRealized DESC")
    fun getAllMetrics(): Flow<List<LearningMetricEntity>>

    @Query("DELETE FROM dca_positions")
    suspend fun clearAllPositions()

    @Query("DELETE FROM trade_signals")
    suspend fun clearAllSignals()

    @Query("DELETE FROM learning_metrics")
    suspend fun clearAllMetrics()
}

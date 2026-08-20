package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// 1. Historical & Active Trades Archive
@Entity(tableName = "app_trades")
data class AppTradeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val symbol: String, // e.g. "SOL", "BTC"
    val pair: String = "USDT", // e.g. "SOL/USDT"
    val entryPrice: Double,
    val targetExitPrice: Double,
    val actualExitPrice: Double? = null,
    val stopLossPrice: Double = 0.0,
    val investedUsdt: Double = 50.0,
    val coinAmount: Double = 0.0,
    val midasTotalFeeUsdt: Double = 0.0,
    val netProfitUsdt: Double = 0.0,
    val netProfitPercent: Double = 0.0,
    val entryRsi: Double = 50.0,
    val dcaLevel: Int = 1, // 1: İlk Giriş, 2: 2. Kademe Dip, 3: 3. Son Savunma
    val maxDcaLevels: Int = 3,
    val nextDcaPrice: Double = 0.0,
    val nextDcaAmountUsdt: Double = 0.0,
    val status: String = "ACTIVE_OPEN", // "ACTIVE_OPEN", "COMPLETED_PROFIT", "COMPLETED_LOSS", "CANCELLED"
    val openedAt: Long = System.currentTimeMillis(),
    val closedAt: Long? = null,
    val aiNote: String = ""
)

// 2. Portfolio Cash & Capital Memory
@Entity(tableName = "capital_profile")
data class CapitalProfileEntity(
    @PrimaryKey
    val id: Int = 1,
    val isInitialized: Boolean = false,
    val availableCashUsdt: Double = 0.0,
    val minSafeThresholdUsdt: Double = 0.0,
    val weeklyTargetPercent: Double = 5.0,
    val weekStartCapitalUsdt: Double = 0.0,
    val weekStartTimestamp: Long = System.currentTimeMillis(),
    val totalWithdrawnUsdt: Double = 0.0,
    val totalDepositedUsdt: Double = 0.0,
    val lastRecordedBalanceUsdt: Double = 0.0,
    val lastBalanceUpdateTimestamp: Long = System.currentTimeMillis()
)

// 3. Weekly AI Reports Archive
@Entity(tableName = "weekly_reports")
data class WeeklyReportEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val weekLabel: String,
    val startCapitalUsdt: Double,
    val endCapitalUsdt: Double,
    val netProfitUsdt: Double,
    val growthPercent: Double,
    val targetPercent: Double,
    val isTargetAchieved: Boolean,
    val totalTrades: Int,
    val successfulTrades: Int,
    val winRatePercent: Double,
    val bestCoin: String,
    val aiAnalysisText: String,
    val fullExportMarkdown: String,
    val createdAt: Long = System.currentTimeMillis()
)

// 4. Coin Learning & Memory Metrics
@Entity(tableName = "coin_memory_metrics")
data class CoinMemoryEntity(
    @PrimaryKey
    val symbol: String, // "SOL", "BTC"
    val totalTrades: Int = 0,
    val successfulTrades: Int = 0,
    val totalNetProfitUsdt: Double = 0.0,
    val winRatePercent: Double = 100.0,
    val avgHoldMinutes: Long = 30,
    val optimalDipRsi: Double = 33.0,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Dao
interface AppDatabaseDao {

    // Trade Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrade(trade: AppTradeEntity): Long

    @Update
    suspend fun updateTrade(trade: AppTradeEntity)

    @Query("SELECT * FROM app_trades ORDER BY openedAt DESC")
    fun getAllTradesFlow(): Flow<List<AppTradeEntity>>

    @Query("SELECT * FROM app_trades WHERE status = 'ACTIVE_OPEN' ORDER BY openedAt DESC")
    fun getActiveTradesFlow(): Flow<List<AppTradeEntity>>

    @Query("SELECT * FROM app_trades WHERE status != 'ACTIVE_OPEN' ORDER BY closedAt DESC")
    fun getHistoricalTradesFlow(): Flow<List<AppTradeEntity>>

    @Query("SELECT * FROM app_trades WHERE status != 'ACTIVE_OPEN' ORDER BY closedAt DESC")
    suspend fun getHistoricalTradesOnce(): List<AppTradeEntity>

    @Query("SELECT * FROM app_trades WHERE id = :id LIMIT 1")
    suspend fun getTradeById(id: Long): AppTradeEntity?

    @Query("DELETE FROM app_trades WHERE id = :id")
    suspend fun deleteTradeById(id: Long)

    @Query("DELETE FROM app_trades")
    suspend fun clearAllTrades()

    // Capital Profile Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCapitalProfile(profile: CapitalProfileEntity)

    @Query("SELECT * FROM capital_profile WHERE id = 1 LIMIT 1")
    fun getCapitalProfileFlow(): Flow<CapitalProfileEntity?>

    @Query("SELECT * FROM capital_profile WHERE id = 1 LIMIT 1")
    suspend fun getCapitalProfileOnce(): CapitalProfileEntity?

    @Query("DELETE FROM capital_profile")
    suspend fun clearCapitalProfile()

    // Weekly Reports Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWeeklyReport(report: WeeklyReportEntity): Long

    @Query("SELECT * FROM weekly_reports ORDER BY createdAt DESC")
    fun getAllWeeklyReportsFlow(): Flow<List<WeeklyReportEntity>>

    @Query("DELETE FROM weekly_reports")
    suspend fun clearWeeklyReports()

    // Coin Memory Metrics
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCoinMemory(memory: CoinMemoryEntity)

    @Query("SELECT * FROM coin_memory_metrics WHERE symbol = :symbol LIMIT 1")
    suspend fun getCoinMemory(symbol: String): CoinMemoryEntity?

    @Query("SELECT * FROM coin_memory_metrics ORDER BY totalNetProfitUsdt DESC")
    fun getAllCoinMemoriesFlow(): Flow<List<CoinMemoryEntity>>

    @Query("SELECT * FROM coin_memory_metrics ORDER BY totalNetProfitUsdt DESC")
    suspend fun getAllCoinMemoriesOnce(): List<CoinMemoryEntity>

    @Query("DELETE FROM coin_memory_metrics")
    suspend fun clearCoinMemory()
}

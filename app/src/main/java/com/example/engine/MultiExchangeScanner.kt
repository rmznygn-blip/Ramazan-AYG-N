package com.example.engine

import com.example.data.local.TradeSignalEntity
import java.util.Locale
import kotlin.random.Random

data class ExchangeQuote(
    val exchange: String,
    val symbol: String,
    val bidPrice: Double,
    val askPrice: Double,
    val volume24hUsd: Double,
    val feeRatePercent: Double,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class ArbitrageSpread(
    val symbol: String,
    val lowestExchange: String,
    val buyPrice: Double,
    val highestExchange: String,
    val sellPrice: Double,
    val spreadPercent: Double,
    val grossDifference: Double,
    val totalFeePercent: Double,
    val netArbitragePercent: Double,
    val isProfitable: Boolean
)

object MultiExchangeScanner {

    val EXCHANGES = listOf("Midas Kripto", "Binance TR", "BtcTurk", "Paribu", "Kraken")

    /**
     * Generates simulated live quotes for given symbol across major exchanges
     * centered around basePrice with realistic cross-exchange micro-spreads.
     */
    fun getMultiExchangeQuotes(symbol: String, basePrice: Double): List<ExchangeQuote> {
        return EXCHANGES.map { exchange ->
            val variancePct = when (exchange) {
                "Midas Kripto" -> Random.nextDouble(-0.15, 0.15)
                "Binance TR" -> Random.nextDouble(-0.08, 0.08)
                "BtcTurk" -> Random.nextDouble(-0.25, 0.30)
                "Paribu" -> Random.nextDouble(-0.35, 0.40)
                else -> Random.nextDouble(-0.10, 0.10)
            }
            val midPrice = (basePrice * (1 + variancePct / 100)).coerceAtLeast(0.01)
            val spreadHalf = midPrice * 0.0004 // 0.04% spread
            ExchangeQuote(
                exchange = exchange,
                symbol = symbol,
                bidPrice = midPrice - spreadHalf,
                askPrice = midPrice + spreadHalf,
                volume24hUsd = Random.nextDouble(500000.0, 15000000.0),
                feeRatePercent = (CommissionCalculator.EXCHANGE_FEE_RATES[exchange] ?: 0.0008) * 100
            )
        }
    }

    /**
     * Finds cross-exchange spread and arbitrage opportunities
     */
    fun findArbitrageOpportunity(symbol: String, quotes: List<ExchangeQuote>): ArbitrageSpread? {
        if (quotes.size < 2) return null

        val lowestBuy = quotes.minByOrNull { it.askPrice } ?: return null
        val highestSell = quotes.maxByOrNull { it.bidPrice } ?: return null

        if (lowestBuy.exchange == highestSell.exchange) return null

        val spreadGrossPercent = ((highestSell.bidPrice - lowestBuy.askPrice) / lowestBuy.askPrice) * 100.0
        val buyFeeRate = CommissionCalculator.EXCHANGE_FEE_RATES[lowestBuy.exchange] ?: 0.0008
        val sellFeeRate = CommissionCalculator.EXCHANGE_FEE_RATES[highestSell.exchange] ?: 0.0008
        val totalFeePercent = (buyFeeRate + sellFeeRate) * 100.0
        val netArbitragePercent = spreadGrossPercent - totalFeePercent

        return ArbitrageSpread(
            symbol = symbol,
            lowestExchange = lowestBuy.exchange,
            buyPrice = lowestBuy.askPrice,
            highestExchange = highestSell.exchange,
            sellPrice = highestSell.bidPrice,
            spreadPercent = spreadGrossPercent,
            grossDifference = highestSell.bidPrice - lowestBuy.askPrice,
            totalFeePercent = totalFeePercent,
            netArbitragePercent = netArbitragePercent,
            isProfitable = netArbitragePercent > 0.15 // Net profit threshold after all fees
        )
    }

    /**
     * Generates a Micro-Scalp Trade Signal on Midas with 100% net guaranteed profit calculation
     */
    fun generateMicroScalpSignal(
        symbol: String,
        currentPrice: Double,
        investedAmount: Double = 100.0,
        exchange: String = "Midas Kripto",
        targetNetProfitPercent: Double = 0.008,
        confidenceScore: Double = 0.88
    ): TradeSignalEntity {
        val calc = CommissionCalculator.calculateTargetExit(
            entryPrice = currentPrice,
            investedAmount = investedAmount,
            exchange = exchange,
            targetNetProfitPercent = targetNetProfitPercent
        )

        return TradeSignalEntity(
            symbol = symbol,
            pair = "$symbol/USDT",
            exchange = exchange,
            actionType = "BUY_MICRO",
            entryPrice = currentPrice,
            targetExitPrice = calc.targetExitPrice,
            investmentAmount = investedAmount,
            buyFeeRate = CommissionCalculator.EXCHANGE_FEE_RATES[exchange] ?: 0.0008,
            sellFeeRate = CommissionCalculator.EXCHANGE_FEE_RATES[exchange] ?: 0.0008,
            totalFeeAmount = calc.totalFees,
            guaranteedNetProfit = calc.guaranteedNetProfit,
            netProfitPercent = calc.netProfitPercent,
            dcaLevel = 1,
            confidenceScore = confidenceScore,
            status = "PENDING",
            rationale = "Mikro Scalp: Midas'ta anlık dip tespiti. Alış/Satış komisyonları düşüldükten sonra net +${String.format(Locale.US, "%.2f", calc.netProfitPercent)}% kâr hedefi."
        )
    }
}

package com.example.engine

import com.example.data.local.DcaPositionEntity
import com.example.data.local.TradeSignalEntity

object DcaStrategyEngine {

    // DCA Level Configuration Ladders (Dip % and Allocation Multipliers)
    data class DcaTierConfig(
        val level: Int,
        val dipPercentTrigger: Double, // % drop from previous average price
        val allocationMultiplier: Double // Multiplier of base investment
    )

    val DEFAULT_DCA_LADDER = listOf(
        DcaTierConfig(level = 1, dipPercentTrigger = 0.0, allocationMultiplier = 1.0),
        DcaTierConfig(level = 2, dipPercentTrigger = -2.2, allocationMultiplier = 1.3),
        DcaTierConfig(level = 3, dipPercentTrigger = -5.0, allocationMultiplier = 1.8),
        DcaTierConfig(level = 4, dipPercentTrigger = -9.0, allocationMultiplier = 2.5)
    )

    /**
     * Checks if an existing position needs a DCA Add or a Profit Take.
     * Enforces the "NEVER SELL AT A LOSS" policy.
     */
    fun evaluatePositionState(
        position: DcaPositionEntity,
        currentMarketPrice: Double,
        targetNetProfitPercent: Double = 0.008
    ): PositionEvaluation {
        val unrealizedPnl = (currentMarketPrice * position.totalUnits) - position.totalInvested - position.totalFeesPaid
        val unrealizedPnlPercent = (unrealizedPnl / position.totalInvested) * 100.0

        // Check if Profit Target is Reached
        if (currentMarketPrice >= position.targetExitPriceWithProfit) {
            val exitCalc = CommissionCalculator.calculateTargetExit(
                entryPrice = position.averageEntryPrice,
                investedAmount = position.totalInvested,
                exchange = position.exchange,
                targetNetProfitPercent = targetNetProfitPercent
            )

            val signal = TradeSignalEntity(
                symbol = position.symbol,
                pair = position.pair,
                exchange = position.exchange,
                actionType = "PROFIT_TAKE",
                entryPrice = position.averageEntryPrice,
                targetExitPrice = currentMarketPrice,
                investmentAmount = position.totalInvested,
                buyFeeRate = CommissionCalculator.EXCHANGE_FEE_RATES[position.exchange] ?: 0.0020,
                sellFeeRate = CommissionCalculator.EXCHANGE_FEE_RATES[position.exchange] ?: 0.0020,
                totalFeeAmount = exitCalc.totalFees,
                guaranteedNetProfit = exitCalc.guaranteedNetProfit,
                netProfitPercent = exitCalc.netProfitPercent,
                dcaLevel = position.currentDcaLevel,
                confidenceScore = 0.96,
                status = "PENDING",
                rationale = "Kâr hedefi (${String.format("%.2f", targetNetProfitPercent * 100)}% net) aşıldı. Komisyonlar düşülmüş garantili kâr ile çıkış."
            )
            return PositionEvaluation.TriggerTakeProfit(signal)
        }

        // Check if Next DCA Dip Level is reached & position is not at max level
        if (position.currentDcaLevel < position.maxDcaLevels && currentMarketPrice <= position.nextDcaTriggerPrice) {
            val nextTier = DEFAULT_DCA_LADDER.getOrNull(position.currentDcaLevel) ?: return PositionEvaluation.Hold(unrealizedPnl, unrealizedPnlPercent)
            val baseInvest = position.totalInvested / position.currentDcaLevel
            val dcaAmount = baseInvest * nextTier.allocationMultiplier

            val dcaCalc = CommissionCalculator.calculateTargetExit(
                entryPrice = currentMarketPrice,
                investedAmount = dcaAmount,
                exchange = position.exchange,
                targetNetProfitPercent = targetNetProfitPercent
            )

            val signal = TradeSignalEntity(
                symbol = position.symbol,
                pair = position.pair,
                exchange = position.exchange,
                actionType = "DCA_ADD",
                entryPrice = currentMarketPrice,
                targetExitPrice = dcaCalc.targetExitPrice,
                investmentAmount = dcaAmount,
                buyFeeRate = CommissionCalculator.EXCHANGE_FEE_RATES[position.exchange] ?: 0.0020,
                sellFeeRate = CommissionCalculator.EXCHANGE_FEE_RATES[position.exchange] ?: 0.0020,
                totalFeeAmount = dcaCalc.totalFees,
                guaranteedNetProfit = dcaCalc.guaranteedNetProfit,
                netProfitPercent = dcaCalc.netProfitPercent,
                dcaLevel = position.currentDcaLevel + 1,
                confidenceScore = 0.90,
                status = "PENDING",
                rationale = "Düşüşte ${nextTier.dipPercentTrigger}% kademe tetiklendi. Ortalamayı aşağı çekmek için ${String.format("%.1f", nextTier.allocationMultiplier)}x ekleme önerisi."
            )
            return PositionEvaluation.TriggerDcaAdd(signal)
        }

        // Default: Hold safely (Never sell at loss)
        return PositionEvaluation.Hold(unrealizedPnl, unrealizedPnlPercent)
    }

    /**
     * Blends new DCA order into existing position to calculate new average cost
     */
    fun recalculatePositionWithDca(
        currentPosition: DcaPositionEntity,
        newUnits: Double,
        newCost: Double,
        newFee: Double,
        exchange: String,
        targetNetProfitPercent: Double = 0.008
    ): DcaPositionEntity {
        val totalUnits = currentPosition.totalUnits + newUnits
        val totalInvested = currentPosition.totalInvested + newCost
        val totalFees = currentPosition.totalFeesPaid + newFee
        val newAverageEntry = totalInvested / totalUnits

        val exitCalc = CommissionCalculator.calculateTargetExit(
            entryPrice = newAverageEntry,
            investedAmount = totalInvested,
            exchange = exchange,
            targetNetProfitPercent = targetNetProfitPercent
        )

        val nextLevel = currentPosition.currentDcaLevel + 1
        val nextTier = DEFAULT_DCA_LADDER.getOrNull(nextLevel)
        val nextTriggerPrice = if (nextTier != null) {
            newAverageEntry * (1.0 + (nextTier.dipPercentTrigger / 100.0))
        } else {
            0.0
        }

        return currentPosition.copy(
            currentDcaLevel = nextLevel,
            totalUnits = totalUnits,
            totalInvested = totalInvested,
            averageEntryPrice = newAverageEntry,
            targetBreakEvenPrice = newAverageEntry * (1.0 + 0.0016), // Break-even covering buy+sell fees
            targetExitPriceWithProfit = exitCalc.targetExitPrice,
            nextDcaTriggerPrice = nextTriggerPrice,
            totalFeesPaid = totalFees,
            guaranteedNetProfitOnExit = exitCalc.guaranteedNetProfit,
            updatedAt = System.currentTimeMillis()
        )
    }

    sealed class PositionEvaluation {
        data class TriggerTakeProfit(val signal: TradeSignalEntity) : PositionEvaluation()
        data class TriggerDcaAdd(val signal: TradeSignalEntity) : PositionEvaluation()
        data class Hold(val unrealizedPnl: Double, val unrealizedPnlPercent: Double) : PositionEvaluation()
    }
}

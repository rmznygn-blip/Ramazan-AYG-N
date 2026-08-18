package com.example.engine

import java.util.Locale

object CommissionCalculator {

    // Standard default exchange fee rates (can be customized by user)
    val EXCHANGE_FEE_RATES = mapOf(
        "Midas Kripto" to 0.0008, // 0.08%
        "Binance TR" to 0.00075, // 0.075%
        "BtcTurk" to 0.0009, // 0.09%
        "Paribu" to 0.0015, // 0.15%
        "Binance Global" to 0.00075, // 0.075%
        "Kraken" to 0.0016 // 0.16%
    )

    const val DEFAULT_SLIPPAGE_BUFFER = 0.0005 // 0.05% safety cushion

    data class FeeCalculationResult(
        val entryPrice: Double,
        val targetExitPrice: Double,
        val units: Double,
        val investedAmount: Double,
        val grossReturn: Double,
        val grossProfit: Double,
        val buyFeeAmount: Double,
        val sellFeeAmount: Double,
        val totalFees: Double,
        val guaranteedNetProfit: Double,
        val netProfitPercent: Double,
        val isNetProfitGuaranteed: Boolean
    )

    /**
     * Calculates the guaranteed net profit and target exit price.
     * Formula ensures Net PnL is STRICTLY POSITIVE after all fees and slippage.
     */
    fun calculateTargetExit(
        entryPrice: Double,
        investedAmount: Double,
        exchange: String = "Midas Kripto",
        targetNetProfitPercent: Double = 0.008, // 0.8% default micro-profit target
        slippageBuffer: Double = DEFAULT_SLIPPAGE_BUFFER
    ): FeeCalculationResult {
        val buyFeeRate = EXCHANGE_FEE_RATES[exchange] ?: 0.0008
        val sellFeeRate = EXCHANGE_FEE_RATES[exchange] ?: 0.0008

        val units = investedAmount / entryPrice
        val buyFeeAmount = investedAmount * buyFeeRate

        // Formula: Exit Price must cover buy fee, sell fee, slippage, AND desired net profit margin
        // Net = (Exit * units * (1 - sellFee)) - (Entry * units * (1 + buyFee))
        // Setting Net / Invested = targetNetProfitPercent:
        val requiredMultiplier = ((1.0 + buyFeeRate) * (1.0 + targetNetProfitPercent + slippageBuffer)) / (1.0 - sellFeeRate)
        val targetExitPrice = entryPrice * requiredMultiplier

        val grossReturn = targetExitPrice * units
        val grossProfit = grossReturn - investedAmount
        val sellFeeAmount = grossReturn * sellFeeRate
        val totalFees = buyFeeAmount + sellFeeAmount
        val guaranteedNetProfit = grossReturn - investedAmount - totalFees
        val actualNetProfitPercent = (guaranteedNetProfit / investedAmount) * 100.0

        return FeeCalculationResult(
            entryPrice = entryPrice,
            targetExitPrice = targetExitPrice,
            units = units,
            investedAmount = investedAmount,
            grossReturn = grossReturn,
            grossProfit = grossProfit,
            buyFeeAmount = buyFeeAmount,
            sellFeeAmount = sellFeeAmount,
            totalFees = totalFees,
            guaranteedNetProfit = guaranteedNetProfit,
            netProfitPercent = actualNetProfitPercent,
            isNetProfitGuaranteed = guaranteedNetProfit > 0
        )
    }

    fun formatCurrency(value: Double, symbol: String = "$"): String {
        return if (value >= 1000) {
            String.format(Locale.US, "%s%,.2f", symbol, value)
        } else if (value >= 1) {
            String.format(Locale.US, "%s%.2f", symbol, value)
        } else {
            String.format(Locale.US, "%s%.4f", symbol, value)
        }
    }
}

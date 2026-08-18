package com.example.engine

import java.util.Locale

object CommissionCalculator {

    // Standard exchange fee rates (Realistic Midas fees with safety buffer)
    val EXCHANGE_FEE_RATES = mapOf(
        "Midas Kripto" to 0.0020, // 0.20% Alış / 0.20% Satış (Toplam %0.40 Komisyon Kalkanı)
        "Binance TR" to 0.0010, // 0.10%
        "BtcTurk" to 0.0012, // 0.12%
        "Paribu" to 0.0020, // 0.20%
        "Binance Global" to 0.0008, // 0.08%
        "Kraken" to 0.0016 // 0.16%
    )

    const val DEFAULT_SLIPPAGE_BUFFER = 0.0015 // 0.15% Spread ve Kayma Güvenlik Tamponu

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
        targetNetProfitPercent: Double = 0.018, // Minimum %1.80 Net Kâr Hedefi (Komisyonlar sonrası)
        slippageBuffer: Double = DEFAULT_SLIPPAGE_BUFFER
    ): FeeCalculationResult {
        val buyFeeRate = EXCHANGE_FEE_RATES[exchange] ?: 0.0020
        val sellFeeRate = EXCHANGE_FEE_RATES[exchange] ?: 0.0020

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

    fun formatCurrency(value: Double, symbol: String = "USDT"): String {
        return if (value >= 1000) {
            String.format(Locale.US, "%,.2f %s", value, symbol)
        } else if (value >= 1) {
            String.format(Locale.US, "%.2f %s", value, symbol)
        } else {
            String.format(Locale.US, "%.4f %s", value, symbol)
        }
    }
}

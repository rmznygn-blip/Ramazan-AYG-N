package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.model.CryptoAsset
import com.example.repository.CryptoOverlayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.regex.Pattern

class CryptoAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main)

    companion object {
        private const val TAG = "CryptoAccessibility"
        @Volatile
        var instance: CryptoAccessibilityService? = null

        private val KNOWN_SYMBOLS = listOf(
            "SOL", "BTC", "ETH", "AVAX", "XRP", "DOGE", "USDT", "ADA",
            "DOT", "LINK", "NEAR", "PEPE", "SHIB", "TRX", "SUI", "ARB", "RENDER", "BNB"
        )
        private val CASH_KEYWORDS = listOf("kullanılabilir", "nakit", "alım gücü", "bakiye", "available", "cash", "cüzdan", "usdt")

        fun parseMidasNumber(raw: String): Double? {
            if (raw.isBlank() || raw.contains("%")) return null
            val clean = raw.replace("$", "")
                .replace("₺", "")
                .replace("€", "")
                .replace("~", "")
                .replace("USDT", "")
                .replace("USD", "")
                .trim()

            val regex = Regex("""[0-9]+(?:[.,][0-9]+)*""")
            val match = regex.find(clean)?.value ?: return null

            return try {
                if (match.contains(",") && match.contains(".")) {
                    val lastComma = match.lastIndexOf(',')
                    val lastDot = match.lastIndexOf('.')
                    if (lastComma > lastDot) {
                        match.replace(".", "").replace(",", ".").toDoubleOrNull()
                    } else {
                        match.replace(",", "").toDoubleOrNull()
                    }
                } else if (match.contains(",")) {
                    val parts = match.split(",")
                    if (parts.size == 2 && parts[1].length <= 4) {
                        "${parts[0]}.${parts[1]}".toDoubleOrNull()
                    } else {
                        match.replace(",", "").toDoubleOrNull()
                    }
                } else {
                    match.toDoubleOrNull()
                }
            } catch (e: Exception) {
                null
            }
        }

        fun parseMidasPercentage(raw: String): Double? {
            if (!raw.contains("%")) return null
            val isNegative = raw.contains("-")
            val clean = raw.replace("%", "").replace("+", "").replace("-", "").replace("(", "").replace(")", "").trim()
            val regex = Regex("""[0-9]+(?:[.,][0-9]+)*""")
            val match = regex.find(clean)?.value ?: return null
            val num = parseMidasNumber(match) ?: return null
            return if (isNegative) -num else num
        }

        fun executeMidasAssistOrder(actionType: String, amount: Double, price: Double = 0.0) {
            instance?.triggerOrderAssist(actionType, amount, price)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "CryptoAccessibilityService Connected")
        CryptoOverlayRepository.updateAccessibilityConnected(true)

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    fun triggerOrderAssist(actionType: String, amount: Double, price: Double) {
        val isSell = actionType.contains("SELL") || actionType.contains("PROFIT")
        val targetButtonText = if (isSell) "Sat" else "Al"
        // For buys: always use clean integer USDT (e.g. "12") so Midas keypad doesn't multiply by 1000!
        val formattedAmount = if (!isSell) {
            "${amount.toInt().coerceAtLeast(10)}"
        } else {
            if (price > 0) String.format(Locale.US, "%.4f", amount / price) else "${amount.toInt()}"
        }

        // 1. Copy amount to clipboard for instant pasting fallback
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Midas Trade Amount", formattedAmount)
            clipboard.setPrimaryClip(clip)
        } catch (ignored: Exception) {}

        serviceScope.launch {
            // Step 1: Find and click the "Al" or "Sat" button on Midas
            val root = rootInActiveWindow
            if (root != null) {
                val buttonFound = findAndClickNodeWithText(root, targetButtonText)
                try { root.recycle() } catch (ignored: Exception) {}

                if (buttonFound) {
                    delay(700L) // Wait for order sheet/screen to animate in

                    val orderSheetRoot = rootInActiveWindow
                    if (orderSheetRoot != null) {
                        if (isSell) {
                            // On Midas Sell screen, prioritize clicking "%100" / "Tümü" / "Tümünü Sat"
                            val clickedPercent = findAndClickAnyNodeWithTexts(
                                orderSheetRoot,
                                listOf("%100", "100%", "Tümü", "Tümünü sat", "Tümünü Sat", "Maksimum", "Max")
                            )
                            if (!clickedPercent) {
                                fillAmountInEditText(orderSheetRoot, formattedAmount)
                            }
                        } else {
                            // On Midas Buy screen, fill clean integer USDT amount
                            fillAmountInEditText(orderSheetRoot, formattedAmount)
                        }
                        try { orderSheetRoot.recycle() } catch (ignored: Exception) {}
                    }
                }
            }
        }
    }

    private fun findAndClickAnyNodeWithTexts(node: AccessibilityNodeInfo?, texts: List<String>): Boolean {
        if (node == null) return false

        val nodeText = node.text?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""

        if (texts.any { nodeText.equals(it, ignoreCase = true) || contentDesc.equals(it, ignoreCase = true) }) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            } else {
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        try { parent.recycle() } catch (ignored: Exception) {}
                        return true
                    }
                    val nextParent = parent.parent
                    try { parent.recycle() } catch (ignored: Exception) {}
                    parent = nextParent
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val clicked = findAndClickAnyNodeWithTexts(child, texts)
            try { child?.recycle() } catch (ignored: Exception) {}
            if (clicked) return true
        }

        return false
    }

    private fun findAndClickNodeWithText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false

        val nodeText = node.text?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""

        if (nodeText.equals(text, ignoreCase = true) || contentDesc.equals(text, ignoreCase = true)) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            } else {
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        try { parent.recycle() } catch (ignored: Exception) {}
                        return true
                    }
                    val nextParent = parent.parent
                    try { parent.recycle() } catch (ignored: Exception) {}
                    parent = nextParent
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val clicked = findAndClickNodeWithText(child, text)
            try { child?.recycle() } catch (ignored: Exception) {}
            if (clicked) return true
        }

        return false
    }

    private fun fillAmountInEditText(node: AccessibilityNodeInfo?, amount: String): Boolean {
        if (node == null) return false

        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, amount)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val filled = fillAmountInEditText(child, amount)
            try { child?.recycle() } catch (ignored: Exception) {}
            if (filled) return true
        }

        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // Extract nodes from root window
        val rootNode = rootInActiveWindow ?: return
        try {
            val textList = mutableListOf<String>()
            extractAllText(rootNode, textList)

            if (textList.isNotEmpty()) {
                processExtractedTexts(packageName, textList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing accessibility tree: ${e.message}")
        } finally {
            try {
                rootNode.recycle()
            } catch (ignored: Exception) {}
        }
    }

    private fun extractAllText(node: AccessibilityNodeInfo?, output: MutableList<String>) {
        if (node == null) return

        val text = node.text?.toString()?.trim()
        val contentDescription = node.contentDescription?.toString()?.trim()

        if (!text.isNullOrEmpty()) {
            output.add(text)
        } else if (!contentDescription.isNullOrEmpty()) {
            output.add(contentDescription)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            extractAllText(child, output)
            try {
                child?.recycle()
            } catch (ignored: Exception) {}
        }
    }

    private fun processExtractedTexts(packageName: String, texts: List<String>) {
        val detectedAssets = mutableListOf<CryptoAsset>()
        val detectedSymbols = mutableListOf<String>()
        val combinedText = texts.joinToString(" | ")
        var detectedCash: Double? = null

        // 1. Detect Midas Cash / Available Balance (USDT priority)
        var foundUsdtCash: Double? = null
        for (i in texts.indices) {
            val lower = texts[i].lowercase(Locale.ROOT)
            val isUsdt = lower == "usdt" || lower.startsWith("usdt ") || lower.contains("usdt")
            val isCashLabel = CASH_KEYWORDS.any { lower.contains(it) }

            if (isUsdt) {
                val lookahead = (i..minOf(i + 4, texts.lastIndex))
                for (k in lookahead) {
                    val candidate = texts[k]
                    val parsed = parseMidasNumber(candidate)
                    if (parsed != null && parsed > 0 && !candidate.contains("%") && !candidate.contains("APY", ignoreCase = true)) {
                        foundUsdtCash = parsed
                        break
                    }
                }
            } else if (isCashLabel && detectedCash == null) {
                val lookahead = (i..minOf(i + 3, texts.lastIndex))
                for (k in lookahead) {
                    val candidate = texts[k]
                    val parsed = parseMidasNumber(candidate)
                    if (parsed != null && parsed > 0 && !candidate.contains("%")) {
                        detectedCash = parsed
                        break
                    }
                }
            }
        }

        val effectiveCash = foundUsdtCash ?: detectedCash

        // 2. Detect Crypto Assets & Prices
        for (i in texts.indices) {
            val token = texts[i].uppercase(Locale.US)
            val matchedSymbol = KNOWN_SYMBOLS.firstOrNull { token == it || token.startsWith("$it/") || token.startsWith("$it ") || token.startsWith("$it·") }

            if (matchedSymbol != null && !detectedSymbols.contains(matchedSymbol)) {
                detectedSymbols.add(matchedSymbol)

                var priceFormatted = "$0.00"
                var rawPrice = 0.0
                var currencySymbol = "$"
                var changeFormatted = "+0.00%"
                var changePercent = 0.0
                var isPositive = true

                // Look at next 6 tokens
                val lookaheadRange = (i + 1..minOf(i + 6, texts.lastIndex))
                for (j in lookaheadRange) {
                    val candidate = texts[j]

                    // Check percentage
                    val parsedPct = parseMidasPercentage(candidate)
                    if (parsedPct != null) {
                        changePercent = parsedPct
                        isPositive = parsedPct >= 0
                        val sign = if (isPositive) "+" else ""
                        changeFormatted = "$sign${String.format(Locale.US, "%.2f", parsedPct)}%"
                    }

                    // Check price
                    if (rawPrice == 0.0 && !candidate.contains("%") && !candidate.contains("APY", ignoreCase = true)) {
                        val parsed = parseMidasNumber(candidate)
                        if (parsed != null && parsed > 0) {
                            rawPrice = parsed
                            currencySymbol = if (candidate.contains("₺")) "₺" else if (candidate.contains("€")) "€" else "$"
                            priceFormatted = formatPrice(parsed, currencySymbol)
                        }
                    }
                }

                if (rawPrice > 0) {
                    detectedAssets.add(
                        CryptoAsset(
                            id = matchedSymbol,
                            symbol = matchedSymbol,
                            name = getCryptoName(matchedSymbol),
                            priceFormatted = priceFormatted,
                            rawPrice = rawPrice,
                            currencySymbol = currencySymbol,
                            changePercent = changePercent,
                            changeFormatted = changeFormatted,
                            isPositive = isPositive,
                            detectedAt = System.currentTimeMillis(),
                            sourceApp = "Midas Kripto"
                        )
                    )
                }
            }
        }

        if (detectedAssets.isNotEmpty() || detectedSymbols.isNotEmpty() || effectiveCash != null) {
            CryptoOverlayRepository.addExtractedAssets(
                assets = detectedAssets,
                rawText = combinedText,
                sourcePackage = packageName,
                detectedCash = effectiveCash
            )
        }
    }

    private fun getCryptoName(symbol: String): String {
        return when (symbol) {
            "SOL" -> "Solana"
            "BTC" -> "Bitcoin"
            "ETH" -> "Ethereum"
            "AVAX" -> "Avalanche"
            "XRP" -> "Ripple"
            "DOGE" -> "Dogecoin"
            "USDT" -> "Tether"
            "ADA" -> "Cardano"
            "DOT" -> "Polkadot"
            "LINK" -> "Chainlink"
            "NEAR" -> "Near Protocol"
            "PEPE" -> "Pepe"
            "SHIB" -> "Shiba Inu"
            "TRX" -> "Tron"
            "SUI" -> "Sui"
            "BNB" -> "BNB"
            else -> symbol
        }
    }

    private fun formatPrice(price: Double, currency: String): String {
        return if (price >= 1000) {
            String.format(Locale.US, "%s%,.2f", currency, price)
        } else if (price >= 1) {
            String.format(Locale.US, "%s%.2f", currency, price)
        } else {
            String.format(Locale.US, "%s%.4f", currency, price)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "CryptoAccessibilityService Interrupted")
        CryptoOverlayRepository.updateAccessibilityConnected(false)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        CryptoOverlayRepository.updateAccessibilityConnected(false)
        return super.onUnbind(intent)
    }
}

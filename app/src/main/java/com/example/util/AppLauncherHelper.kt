package com.example.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast

object AppLauncherHelper {

    private const val TAG = "AppLauncherHelper"

    // Supported Turkish & Global Crypto Apps
    val SUPPORTED_EXCHANGES = listOf(
        ExchangeAppInfo(id = "midas", name = "Midas", packageName = "com.getmidas.app", iconLabel = "Midas: Borsa & Kripto"),
        ExchangeAppInfo(id = "binancetr", name = "Binance TR", packageName = "com.binance.tr", iconLabel = "Binance TR"),
        ExchangeAppInfo(id = "paribu", name = "Paribu", packageName = "com.paribu.app", iconLabel = "Paribu"),
        ExchangeAppInfo(id = "btcturk", name = "BtcTurk | Kripto", packageName = "com.eliptik.btcturkpro", iconLabel = "BtcTurk Kripto"),
        ExchangeAppInfo(id = "okx", name = "OKX", packageName = "com.okinc.okex.gp", iconLabel = "OKX")
    )

    data class ExchangeAppInfo(
        val id: String,
        val name: String,
        val packageName: String,
        val iconLabel: String
    )

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun launchTargetExchange(context: Context, targetPackageName: String = "com.getmidas.app"): Boolean {
        val pm = context.packageManager

        // 1. Try launching the explicitly configured target package first
        try {
            val intent = pm.getLaunchIntentForPackage(targetPackageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
                Log.d(TAG, "Successfully launched target exchange: $targetPackageName")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching target package $targetPackageName", e)
        }

        // 2. If target is Midas specifically, try common Midas variants
        if (targetPackageName.contains("midas", ignoreCase = true)) {
            val midasPackages = listOf(
                "com.getmidas.app",
                "com.midas.app",
                "tr.com.midas.app",
                "com.getmidas.android"
            )
            for (pkg in midasPackages) {
                try {
                    val intent = pm.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        context.startActivity(intent)
                        Log.d(TAG, "Launched Midas variant: $pkg")
                        return true
                    }
                } catch (e: Exception) {
                    // continue
                }
            }
        }

        // 3. Inform user instead of opening random apps
        Toast.makeText(
            context,
            "Hedef borsa uygulaması ($targetPackageName) cihazınızda bulunamadı. Lütfen Ayarlar sekmesinden hedef borsanızı seçin.",
            Toast.LENGTH_LONG
        ).show()

        return false
    }

    fun launchMidasApp(context: Context): Boolean {
        return launchTargetExchange(context, "com.getmidas.app")
    }
}

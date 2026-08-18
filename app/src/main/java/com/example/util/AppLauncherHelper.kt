package com.example.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

object AppLauncherHelper {

    private const val TAG = "AppLauncherHelper"

    private val MIDAS_CANDIDATE_PACKAGES = listOf(
        "com.getmidas.app",
        "com.getmidas.android",
        "com.midas.app",
        "com.midas.investment",
        "tr.com.midas.app",
        "com.getmidas.crypto",
        "com.midas.kripto"
    )

    fun launchMidasApp(context: Context): Boolean {
        val pm = context.packageManager

        // 1. Direct package check
        for (pkg in MIDAS_CANDIDATE_PACKAGES) {
            try {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    context.startActivity(intent)
                    Log.d(TAG, "Launched Midas via known package: $pkg")
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking package $pkg", e)
            }
        }

        // 2. Query all installed applications to find any package containing 'midas'
        try {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                val pkgName = app.packageName.lowercase()
                val label = pm.getApplicationLabel(app).toString().lowercase()

                if (pkgName.contains("midas") || label.contains("midas")) {
                    val intent = pm.getLaunchIntentForPackage(app.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        context.startActivity(intent)
                        Log.d(TAG, "Launched Midas via installed app scan: ${app.packageName}")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning installed apps for Midas", e)
        }

        // 3. Fallback: Intent with market / browser
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.getmidas.app")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
            return true
        } catch (e: Exception) {
            return false
        }
    }
}

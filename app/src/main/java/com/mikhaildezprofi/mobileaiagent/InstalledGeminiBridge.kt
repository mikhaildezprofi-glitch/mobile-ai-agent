package com.mikhaildezprofi.mobileaiagent

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build

/**
 * Explicit, user-mediated bridge to the official Gemini Android app.
 *
 * This bridge intentionally does not use undocumented Gemini activities,
 * accessibility automation, clipboard scraping, or UI automation.
 */
object InstalledGeminiBridge {
    const val PACKAGE_NAME = "com.google.android.apps.bard"

    fun isInstalled(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                PACKAGE_NAME,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    /** Opens Gemini. Returns false when it is absent or has no launch activity. */
    fun open(context: Context): Boolean {
        if (!isInstalled(context)) return false
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
                ?: return false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Opens the Android Sharesheet with the exact user-selected prompt.
     * The user must choose Gemini (if offered) and confirm the handoff.
     * Android/Gemini decides whether the receiving app accepts the shared text.
     */
    fun sharePrompt(context: Context, prompt: String): Boolean {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return false
        return try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, cleanPrompt)
            }
            val chooser = Intent.createChooser(sendIntent, "Передать запрос в Gemini")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

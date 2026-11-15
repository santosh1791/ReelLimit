package com.example.reellimit.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.reellimit.overlay.OverlayAlertManager

class ReelGuardAccessibilityService : AccessibilityService() {

    private lateinit var overlayManager: OverlayAlertManager
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val TAG = "ReelGuardService"
        const val PREFS_NAME = "reel_limit_prefs"
        const val KEY_DEMO_MODE = "demo_mode"

        // Default monitored social apps
        val DEFAULT_TARGET_APPS = mapOf(
            "com.instagram.android" to "Instagram",
            "com.facebook.katana" to "Facebook",
            "com.facebook.lite" to "Facebook Lite",
            "com.google.android.youtube" to "YouTube",
            "com.zhiliaoapp.musically" to "TikTok"
        )
    }

    private var currentMonitoredPackage: String? = null

    // 5 minutes warning, 6 minutes auto-exit
    private val PROD_ALERT_DELAY = 5 * 60 * 1000L   // 5 minutes
    private val PROD_EXIT_DELAY = 6 * 60 * 1000L    // 6 minutes

    // Demo Mode timings (10 seconds alert, 15 seconds auto-exit for quick testing)
    private val DEMO_ALERT_DELAY = 10 * 1000L       // 10 seconds
    private val DEMO_EXIT_DELAY = 15 * 1000L        // 15 seconds

    private val alertRunnable = Runnable {
        val pkg = currentMonitoredPackage ?: return@Runnable
        val appName = DEFAULT_TARGET_APPS[pkg] ?: "Social App"
        val isDemo = isDemoMode()

        val msg = if (isDemo) {
            "⚡ [DEMO] 10s exceeded for $appName! (Normal is 5 mins)"
        } else {
            "Usage of 5 mins for $appName exceeded!"
        }

        Log.d(TAG, "Showing overlay alert for $appName")
        overlayManager.showAlert(appName, msg) {
            Log.d(TAG, "User acknowledged 5-min alert for $appName")
        }
    }

    private val exitRunnable = Runnable {
        val pkg = currentMonitoredPackage ?: return@Runnable
        val appName = DEFAULT_TARGET_APPS[pkg] ?: "App"
        Log.d(TAG, "Usage threshold exceeded for $appName. Closing app and returning to home screen.")

        // 1. Dismiss overlay
        overlayManager.dismiss()

        // 2. Return to home screen
        takeUserToHomeScreen()

        // 3. Reset state
        resetTracking()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayManager = OverlayAlertManager(this)
        Log.d(TAG, "ReelGuardAccessibilityService connected successfully")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val pkgName = event.packageName?.toString() ?: return

        // Ignore system UI elements (status bar, keyboard, system alerts)
        if (isSystemPackage(pkgName)) {
            return
        }

        if (DEFAULT_TARGET_APPS.containsKey(pkgName)) {
            // Already tracking this app
            if (currentMonitoredPackage == pkgName) {
                return
            }

            // User switched to a monitored app
            startTracking(pkgName)
        } else {
            // User switched out of the monitored app (or went to launcher/other app)
            if (currentMonitoredPackage != null) {
                Log.d(TAG, "User exited monitored app $currentMonitoredPackage (now at $pkgName). Resetting timer.")
                resetTracking()
            }
        }
    }

    private fun startTracking(packageName: String) {
        resetTracking()
        currentMonitoredPackage = packageName
        val appName = DEFAULT_TARGET_APPS[packageName] ?: packageName
        val isDemo = isDemoMode()

        val alertDelay = if (isDemo) DEMO_ALERT_DELAY else PROD_ALERT_DELAY
        val exitDelay = if (isDemo) DEMO_EXIT_DELAY else PROD_EXIT_DELAY

        Log.d(TAG, "Started monitoring $appName. Alert in ${alertDelay / 1000}s, exit in ${exitDelay / 1000}s")

        handler.postDelayed(alertRunnable, alertDelay)
        handler.postDelayed(exitRunnable, exitDelay)
    }

    private fun resetTracking() {
        handler.removeCallbacks(alertRunnable)
        handler.removeCallbacks(exitRunnable)
        overlayManager.dismiss()
        currentMonitoredPackage = null
    }

    private fun takeUserToHomeScreen() {
        val performed = performGlobalAction(GLOBAL_ACTION_HOME)
        if (!performed) {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                startActivity(homeIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error returning to home screen: ${e.message}")
            }
        }
    }

    private fun isSystemPackage(pkg: String): Boolean {
        return pkg == "com.android.systemui" ||
                pkg == "android" ||
                pkg == "com.google.android.inputmethod.latin" ||
                pkg.contains("inputmethod")
    }

    private fun isDemoMode(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DEMO_MODE, false)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        resetTracking()
    }

    override fun onDestroy() {
        super.onDestroy()
        resetTracking()
        Log.d(TAG, "ReelGuardAccessibilityService destroyed")
    }
}

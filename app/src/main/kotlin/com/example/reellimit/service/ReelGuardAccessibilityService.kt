package com.example.reellimit.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.reellimit.notification.NotificationHelper
import com.example.reellimit.overlay.OverlayAlertManager

class ReelGuardAccessibilityService : AccessibilityService() {

    private lateinit var overlayManager: OverlayAlertManager
    private lateinit var notificationHelper: NotificationHelper
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val TAG = "ReelGuardService"
        const val PREFS_NAME = "reel_limit_prefs"
        const val KEY_DEMO_MODE = "demo_mode"
        const val KEY_ALERT_MINUTES = "alert_minutes"
        const val DEFAULT_ALERT_MINUTES = 5

        // Default monitored social apps
        val DEFAULT_TARGET_APPS = mapOf(
            "com.instagram.android" to "Instagram",
            "com.facebook.katana" to "Facebook",
            "com.facebook.lite" to "Facebook Lite",
            "com.google.android.youtube" to "YouTube",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok"
        )
    }

    // Tracking state
    private var activeAppPackage: String? = null
    private var sessionStartTime: Long = 0L
    private var alertShown: Boolean = false
    private var isTickerRunning: Boolean = false

    // Anti-loop cooldowns:
    // 1. For auto-closing (when limit reached)
    private var lastAutoExitTime: Long = 0L
    private val AUTO_EXIT_COOLDOWN_MS = 3500L

    // 2. For manual home exit (ignores trailing minimize/onStop events from the closing app)
    private var lastUserHomeTime: Long = 0L
    private var lastClosedAppPackage: String? = null
    private val MANUAL_HOME_COOLDOWN_MS = 2500L

    // Demo Mode timings (10 seconds alert, 15 seconds exit)
    private val DEMO_ALERT_MS = 10 * 1000L
    private val DEMO_EXIT_MS = 15 * 1000L

    // Grace period for brief transient switches (e.g. volume, quick reply)
    private var pendingExitRunnable: Runnable? = null
    private val GRACE_PERIOD_MS = 3000L

    private val tickerRunnable = object : Runnable {
        override fun run() {
            val pkg = activeAppPackage ?: return
            val appName = DEFAULT_TARGET_APPS[pkg] ?: pkg
            val isDemo = isDemoMode()
            val alertMinutes = getAlertMinutes()

            val alertThreshold = if (isDemo) DEMO_ALERT_MS else (alertMinutes * 60 * 1000L)
            val exitThreshold = if (isDemo) DEMO_EXIT_MS else ((alertMinutes + 1) * 60 * 1000L)

            val elapsed = SystemClock.elapsedRealtime() - sessionStartTime
            val elapsedSec = elapsed / 1000
            val alertSec = alertThreshold / 1000
            val exitSec = exitThreshold / 1000

            if (elapsedSec % 2L == 0L || elapsedSec <= 3) {
                Log.d(TAG, "[$appName] Active usage: ${elapsedSec}s (Alert at ${alertSec}s, Exit at ${exitSec}s, Demo=$isDemo)")
            }

            // 1. Alert threshold
            if (elapsed >= alertThreshold && !alertShown) {
                alertShown = true
                val msg = if (isDemo) {
                    "⚡ [DEMO] ${alertSec}s exceeded for $appName!"
                } else {
                    "Usage of $alertMinutes mins for $appName exceeded! App will auto-close in 1 minute."
                }
                Log.d(TAG, "[$appName] Threshold reached (${alertSec}s) -> Showing Overlay Alert!")
                overlayManager.showAlert(appName, msg) {
                    Log.d(TAG, "[$appName] User tapped OK on overlay. Timer continues toward ${exitSec}s.")
                }
            }

            // 2. Exit threshold
            if (elapsed >= exitThreshold) {
                Log.d(TAG, "[$appName] Maximum limit reached (${exitSec}s)! Closing app and returning to home screen.")
                lastAutoExitTime = SystemClock.elapsedRealtime()
                lastClosedAppPackage = pkg
                stopSession(dismissOverlay = true)
                takeUserToHomeScreen()
                return
            }

            // Continue ticking every 1 second
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayManager = OverlayAlertManager(this)
        notificationHelper = NotificationHelper(this)
        Log.d(TAG, "ReelGuardAccessibilityService connected and ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val pkgName = event.packageName?.toString() ?: return

        // 1. Ignore events from our own app or overlay
        if (pkgName == packageName) {
            return
        }

        // 2. Ignore transient system packages (status bar, volume dialog, keyboard, autofill, notification shade)
        // Pulling down the notification shade (com.android.systemui) does NOT interrupt the session!
        if (isSystemTransientPackage(pkgName)) {
            return
        }

        // 3. Auto-close cooldown check
        if (SystemClock.elapsedRealtime() - lastAutoExitTime < AUTO_EXIT_COOLDOWN_MS) {
            return
        }

        // 4. Manual Home exit cooldown check:
        // Ignore trailing minimize/onStop window events from the app that the user just closed to Home
        if (SystemClock.elapsedRealtime() - lastUserHomeTime < MANUAL_HOME_COOLDOWN_MS && pkgName == lastClosedAppPackage) {
            Log.d(TAG, "Ignoring trailing minimize event from closed app ($pkgName)")
            return
        }

        if (DEFAULT_TARGET_APPS.containsKey(pkgName)) {
            cancelPendingGraceExit()

            // If already tracking this exact app, let the running ticker continue
            if (activeAppPackage == pkgName) {
                return
            }

            // User opened or switched to a monitored app
            startSession(pkgName)
        } else {
            // User switched away to an unmonitored app or Home launcher
            if (activeAppPackage != null) {
                if (isLauncherPackage(pkgName)) {
                    Log.d(TAG, "User returned to Home/Launcher ($pkgName). Stopping session.")
                    lastUserHomeTime = SystemClock.elapsedRealtime()
                    lastClosedAppPackage = activeAppPackage
                    cancelPendingGraceExit()
                    stopSession(dismissOverlay = true)
                } else {
                    scheduleGraceExit(pkgName)
                }
            }
        }
    }

    private fun startSession(packageName: String) {
        stopSession(dismissOverlay = true)
        activeAppPackage = packageName
        sessionStartTime = SystemClock.elapsedRealtime()
        alertShown = false
        isTickerRunning = true

        val appName = DEFAULT_TARGET_APPS[packageName] ?: packageName
        val isDemo = isDemoMode()
        val alertMinutes = getAlertMinutes()

        Log.d(TAG, "=== STARTED MONITORING [$appName] (Limit=${alertMinutes}m, Demo=$isDemo) ===")

        // Show live chronometer timer in notification shade
        notificationHelper.showTimerNotification(
            appName = appName,
            sessionStartTimeWallClock = System.currentTimeMillis(),
            alertMinutes = alertMinutes,
            isDemo = isDemo
        )

        mainHandler.post(tickerRunnable)
    }

    private fun stopSession(dismissOverlay: Boolean) {
        if (isTickerRunning) {
            mainHandler.removeCallbacks(tickerRunnable)
            isTickerRunning = false
        }
        if (dismissOverlay) {
            overlayManager.dismiss()
        }
        // Dismiss notification timer when exiting monitored app
        if (::notificationHelper.isInitialized) {
            notificationHelper.dismissTimerNotification()
        }
        activeAppPackage = null
        alertShown = false
    }

    private fun scheduleGraceExit(newPkg: String) {
        if (pendingExitRunnable != null) return

        val runnable = Runnable {
            Log.d(TAG, "Grace period expired in ($newPkg). Stopping session.")
            stopSession(dismissOverlay = true)
            pendingExitRunnable = null
        }
        pendingExitRunnable = runnable
        mainHandler.postDelayed(runnable, GRACE_PERIOD_MS)
    }

    private fun cancelPendingGraceExit() {
        pendingExitRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingExitRunnable = null
        }
    }

    private fun takeUserToHomeScreen() {
        val performed = performGlobalAction(GLOBAL_ACTION_HOME)
        Log.d(TAG, "performGlobalAction(GLOBAL_ACTION_HOME) returned: $performed")

        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            startActivity(homeIntent)
            Log.d(TAG, "Home Intent dispatched successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching Home Intent: ${e.message}")
        }
    }

    private fun isSystemTransientPackage(pkg: String): Boolean {
        return pkg == "com.android.systemui" ||
                pkg == "android" ||
                pkg == "com.google.android.inputmethod.latin" ||
                pkg == "com.touchtype.swiftkey" ||
                pkg == "com.google.android.gms" ||
                pkg.contains("inputmethod") ||
                pkg.contains("accessibility")
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        if (pkg == "com.miui.home" ||
            pkg == "com.google.android.apps.nexuslauncher" ||
            pkg == "com.android.launcher" ||
            pkg == "com.android.launcher3" ||
            pkg.contains("launcher")
        ) {
            return true
        }

        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            val defaultLauncher = resolveInfo?.activityInfo?.packageName
            pkg == defaultLauncher
        } catch (e: Exception) {
            false
        }
    }

    private fun isDemoMode(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DEMO_MODE, false)
    }

    private fun getAlertMinutes(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_ALERT_MINUTES, DEFAULT_ALERT_MINUTES)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        stopSession(dismissOverlay = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSession(dismissOverlay = true)
        Log.d(TAG, "ReelGuardAccessibilityService destroyed")
    }
}

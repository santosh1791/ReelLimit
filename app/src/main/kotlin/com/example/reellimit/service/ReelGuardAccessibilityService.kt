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

    // Active tracking state
    private var activeAppPackage: String? = null
    private var accumulatedUsageMs: Long = 0L
    private var segmentStartRealtime: Long = 0L
    private var isTickerRunning: Boolean = false
    private var alertShown: Boolean = false

    // Anti-loop cooldowns
    private var lastAutoExitTime: Long = 0L
    private val AUTO_EXIT_COOLDOWN_MS = 3500L

    // Resume window: If user switches away or transient events occur, keep accumulated time for 30s
    private var pendingSessionResetRunnable: Runnable? = null
    private val SESSION_RESUME_WINDOW_MS = 30_000L // 30 seconds

    // Demo Mode timings (10 seconds alert, 15 seconds exit)
    private val DEMO_ALERT_MS = 10 * 1000L
    private val DEMO_EXIT_MS = 15 * 1000L

    private val tickerRunnable = object : Runnable {
        override fun run() {
            val pkg = activeAppPackage ?: return
            val appName = DEFAULT_TARGET_APPS[pkg] ?: pkg
            val isDemo = isDemoMode()
            val alertMinutes = getAlertMinutes()

            val alertThreshold = if (isDemo) DEMO_ALERT_MS else (alertMinutes * 60 * 1000L)
            val exitThreshold = if (isDemo) DEMO_EXIT_MS else ((alertMinutes + 1) * 60 * 1000L)

            val currentSegment = SystemClock.elapsedRealtime() - segmentStartRealtime
            val totalElapsedMs = accumulatedUsageMs + currentSegment
            val totalElapsedSec = totalElapsedMs / 1000
            val alertSec = alertThreshold / 1000
            val exitSec = exitThreshold / 1000

            if (totalElapsedSec % 2L == 0L || totalElapsedSec <= 3) {
                Log.d(TAG, "[$appName] Active usage: ${totalElapsedSec}s (Alert at ${alertSec}s, Exit at ${exitSec}s, Demo=$isDemo)")
            }

            // 1. Alert threshold
            if (totalElapsedMs >= alertThreshold && !alertShown) {
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
            if (totalElapsedMs >= exitThreshold) {
                Log.d(TAG, "[$appName] Maximum limit reached (${exitSec}s)! Closing app and returning to home screen.")
                lastAutoExitTime = SystemClock.elapsedRealtime()
                resetSession(dismissOverlay = true)
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

        // 1. Ignore events from our own app or overlay window
        if (pkgName == packageName) {
            return
        }

        // 2. Ignore transient system & OEM packages (Xiaomi HyperOS, keyboards, WebViews, autofill, notification shade)
        if (isSystemTransientPackage(pkgName)) {
            return
        }

        // 3. Auto-close cooldown: ignore lingering minimize events right after force-close
        if (SystemClock.elapsedRealtime() - lastAutoExitTime < AUTO_EXIT_COOLDOWN_MS) {
            return
        }

        if (DEFAULT_TARGET_APPS.containsKey(pkgName)) {
            // Cancel any pending session timeout since user is back in the monitored app
            cancelPendingSessionReset()

            if (activeAppPackage == pkgName && isTickerRunning) {
                // Already actively running on this app
                return
            }

            // Resume or start session
            resumeOrStartSession(pkgName)
        } else {
            // User navigated to a non-monitored package (e.g. Launcher or another app)
            if (activeAppPackage != null && isTickerRunning) {
                pauseActiveSession(pkgName)
            }
        }
    }

    private fun resumeOrStartSession(packageName: String) {
        val now = SystemClock.elapsedRealtime()
        val appName = DEFAULT_TARGET_APPS[packageName] ?: packageName
        val isDemo = isDemoMode()
        val alertMinutes = getAlertMinutes()

        if (activeAppPackage == packageName && accumulatedUsageMs > 0) {
            // RESUME SESSION: Keep existing accumulated usage!
            segmentStartRealtime = now
            isTickerRunning = true

            val totalElapsedMs = accumulatedUsageMs
            Log.d(TAG, "=== RESUMING [$appName] (Accumulated=${totalElapsedMs / 1000}s, Limit=${alertMinutes}m) ===")

            // Update live notification with correct accumulated elapsed time base
            notificationHelper.showTimerNotification(
                appName = appName,
                sessionStartTimeWallClock = System.currentTimeMillis() - totalElapsedMs,
                alertMinutes = alertMinutes,
                isDemo = isDemo
            )

            mainHandler.post(tickerRunnable)
        } else {
            // START FRESH SESSION
            resetSession(dismissOverlay = true)
            activeAppPackage = packageName
            accumulatedUsageMs = 0L
            segmentStartRealtime = now
            alertShown = false
            isTickerRunning = true

            Log.d(TAG, "=== STARTED FRESH SESSION [$appName] (Limit=${alertMinutes}m, Demo=$isDemo) ===")

            notificationHelper.showTimerNotification(
                appName = appName,
                sessionStartTimeWallClock = System.currentTimeMillis(),
                alertMinutes = alertMinutes,
                isDemo = isDemo
            )

            mainHandler.post(tickerRunnable)
        }
    }

    private fun pauseActiveSession(newPkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (isTickerRunning) {
            accumulatedUsageMs += (now - segmentStartRealtime)
            mainHandler.removeCallbacks(tickerRunnable)
            isTickerRunning = false
        }

        val appName = DEFAULT_TARGET_APPS[activeAppPackage] ?: "App"
        Log.d(TAG, "[$appName] Paused at ${accumulatedUsageMs / 1000}s (switched to $newPkg). Holding session for 30s.")

        // Schedule session reset only if the user stays away for more than 30 seconds
        scheduleSessionReset()
    }

    private fun scheduleSessionReset() {
        if (pendingSessionResetRunnable != null) return

        val runnable = Runnable {
            Log.d(TAG, "Session resume window (30s) expired. Resetting session to 0.")
            resetSession(dismissOverlay = true)
            pendingSessionResetRunnable = null
        }
        pendingSessionResetRunnable = runnable
        mainHandler.postDelayed(runnable, SESSION_RESUME_WINDOW_MS)
    }

    private fun cancelPendingSessionReset() {
        pendingSessionResetRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingSessionResetRunnable = null
        }
    }

    private fun resetSession(dismissOverlay: Boolean) {
        if (isTickerRunning) {
            mainHandler.removeCallbacks(tickerRunnable)
            isTickerRunning = false
        }
        if (dismissOverlay) {
            overlayManager.dismiss()
        }
        if (::notificationHelper.isInitialized) {
            notificationHelper.dismissTimerNotification()
        }
        activeAppPackage = null
        accumulatedUsageMs = 0L
        segmentStartRealtime = 0L
        alertShown = false
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
        // Essential Android system packages
        if (pkg == "com.android.systemui" ||
            pkg == "android" ||
            pkg == "com.google.android.inputmethod.latin" ||
            pkg == "com.touchtype.swiftkey" ||
            pkg == "com.google.android.gms" ||
            pkg == "com.google.android.webview" ||
            pkg == "com.android.permissioncontroller" ||
            pkg == "com.google.android.packageinstaller"
        ) {
            return true
        }

        // Generic filters
        if (pkg.contains("inputmethod") ||
            pkg.contains("accessibility") ||
            pkg.contains("overlay")
        ) {
            return true
        }

        // Xiaomi / HyperOS specific system packages (Game Turbo, sidebar, floating windows, cleaners, security)
        // Note: com.miui.home is the Home Launcher, handled separately
        if ((pkg.startsWith("com.miui.") && pkg != "com.miui.home") ||
            pkg.startsWith("com.xiaomi.")
        ) {
            return true
        }

        return false
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
        resetSession(dismissOverlay = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        resetSession(dismissOverlay = true)
        Log.d(TAG, "ReelGuardAccessibilityService destroyed")
    }
}

package com.onesec.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * The core engine of One Sec.
 *
 * Listens for window state changes (i.e. a new app comes to foreground).
 * When a guarded package is detected, it immediately launches the
 * InterventionActivity as a full-screen overlay.
 *
 * No background polling, no UsageStatsManager loops — purely event-driven
 * via the Accessibility API. Zero battery drain.
 */
class AppWatcherService : AccessibilityService() {

    // Track the last package we triggered on to avoid double-firing
    private var lastTriggeredPackage: String? = null
    private var lastTriggeredTime: Long = 0L
    private val DEBOUNCE_MS = 1500L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return

        val bypassPkg = AppPrefs.getOpenAnywayPackage(this)
        if (bypassPkg != null) {
            if (pkg == bypassPkg) {
                return
            }

            val isIgnorable = pkg == packageName || pkg == "com.android.systemui" || pkg == "android"
            if (!isIgnorable) {
                AppPrefs.clearOpenAnywayPackage(this)
            }
        }

        // Ignore our own app and system UI
        if (pkg == packageName) return
        if (pkg == "com.android.systemui") return
        if (pkg == "android") return

        // Debounce: don't re-trigger same app within cooldown window
        val now = System.currentTimeMillis()
        if (pkg == lastTriggeredPackage && (now - lastTriggeredTime) < DEBOUNCE_MS) return

        // Check if this package is guarded
        if (!AppPrefs.isGuarded(this, pkg)) return

        // Record the attempt
        AppPrefs.recordPause(this, pkg)

        lastTriggeredPackage = pkg
        lastTriggeredTime = now

        // Launch intervention overlay
        val intent = Intent(this, InterventionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(InterventionActivity.EXTRA_PACKAGE, pkg)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        // Nothing to clean up
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Service is running
    }
}

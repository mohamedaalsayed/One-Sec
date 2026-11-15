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

        // Check if this package is guarded
        if (!AppPrefs.isGuarded(this, pkg)) return

        // If a pause for this app is already in progress, the app has pushed a
        // window on top of it. Links do this: the app's link-handler activity
        // shows up first, then starts the real screen a moment later (after we
        // already launched the pause). Never ignore that second window, or the
        // app covers the pause and opens without waiting. Bring the pause back
        // instead, without counting it as a new attempt.
        if (InterventionActivity.pendingPackage != pkg) {
            AppPrefs.recordPause(this, pkg)
            InterventionActivity.pendingPackage = pkg
        }

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

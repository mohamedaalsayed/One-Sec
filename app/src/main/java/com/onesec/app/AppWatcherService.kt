package com.onesec.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat

/**
 * The core engine of One Sec.
 *
 * Listens for window state changes (i.e. a new app comes to foreground).
 * When a guarded package is detected, it immediately launches the
 * InterventionActivity as a full-screen overlay. If the guarded app was
 * opened from another app (a link someone sent), the pause offers to watch
 * just that one thing, and scroll events feed the resulting [LinkSession].
 *
 * Guarded apps open without a pause while the user has access: a running
 * [ScrollSession] (all apps), or a quick check / "just this one" for the app
 * in front (until they switch apps or the screen goes off).
 *
 * No background polling, no UsageStatsManager loops — purely event-driven
 * via the Accessibility API. Zero battery drain.
 */
class AppWatcherService : AccessibilityService() {

    companion object {
        private const val HOME_ECHO_MS = 1000L
        private const val UNLOCK_SETTLE_MS = 800L

        /** App the user is in (ignoring our own screens, system UI, keyboards). */
        @Volatile
        var foregroundPkg: String? = null
            private set
    }

    // Last app the user was in, to tell link opens from launcher opens
    private var lastForegroundPkg: String? = null
        set(value) {
            field = value
            foregroundPkg = value
        }
    private var lastForegroundTime = 0L
    private var homePackages: Set<String> = emptySet()
    private var keyboardPackages: Set<String> = emptySet()

    private val handler = Handler(Looper.getMainLooper())
    private val unlockCheck = Runnable { onUnlocked() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Keep lastForegroundPkg: unlocking back into the same app sends
                // no window event for it, and the user may tap a link right away.
                // Per-app access doesn't survive the screen going off.
                Intent.ACTION_SCREEN_OFF -> endAppAccess()
                // Give a notification tapped on the lock screen time to open its
                // app before deciding the user is back in the one they left
                Intent.ACTION_USER_PRESENT -> {
                    handler.removeCallbacks(unlockCheck)
                    handler.postDelayed(unlockCheck, UNLOCK_SETTLE_MS)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        homePackages = packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
            .toSet()
        keyboardPackages = getSystemService(InputMethodManager::class.java)
            ?.inputMethodList
            ?.map { it.packageName }
            ?.toSet()
            ?: emptySet()
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // A scroll session may have started before the process was restarted
        ScrollSession.schedule(this)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {}
        handler.removeCallbacks(unlockCheck)
        LinkSession.end()
        QuickCheck.end()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                if (pkg == LinkSession.pkg) LinkSession.onScroll(this, event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg == LinkSession.pkg) LinkSession.onWindow(event.className?.toString() ?: "")
                onWindowChanged(pkg)
            }
        }
    }

    // Our own screens, system UI and keyboards pop up over apps without the
    // user actually switching apps
    private fun isIgnorable(pkg: String) =
        pkg == packageName || pkg == "com.android.systemui" || pkg == "android" ||
            pkg in keyboardPackages

    /** Ends a quick check or "just this one" for the app in front. */
    private fun endAppAccess() {
        if (AppPrefs.getAllowedPackage(this) == null) return
        AppPrefs.clearAllowedPackage(this)
        QuickCheck.end()
        LinkSession.end()
    }

    private fun onWindowChanged(pkg: String) {
        if (isIgnorable(pkg)) return

        // Some launchers (ColorOS) report a home-screen window ~0.3s after
        // opening an app from it. That's not the user going home; counting it
        // would make a link tapped in that app look like a launcher open.
        val now = SystemClock.uptimeMillis()
        val previousPkg = lastForegroundPkg
        if (pkg in homePackages && previousPkg != null && previousPkg !in homePackages &&
            now - lastForegroundTime < HOME_ECHO_MS
        ) return
        lastForegroundPkg = pkg
        lastForegroundTime = now

        // Every guarded app is open while a scroll session runs
        if (ScrollSession.isActive(this)) return

        val allowedPkg = AppPrefs.getAllowedPackage(this)
        if (allowedPkg != null) {
            if (pkg == allowedPkg) {
                return
            }
            // Switched to another app
            endAppAccess()
        }

        // Switched away from a pause without choosing (e.g. pressed Home):
        // the next open of that app is a new attempt, not the old pause
        if (InterventionActivity.pendingPackage != null && pkg != InterventionActivity.pendingPackage) {
            InterventionActivity.pendingPackage = null
        }

        // Check if this package is guarded
        if (!AppPrefs.isGuarded(this, pkg)) return

        // If a pause for this app is already in progress, the app has pushed a
        // window on top of it. Links do this: the app's link-handler activity
        // shows up first, then starts the real screen a moment later (after we
        // already launched the pause). Never ignore that second window, or the
        // app covers the pause and opens without waiting. Bring the pause back
        // instead, without counting it as a new attempt.
        if (InterventionActivity.pendingPackage == pkg) {
            startActivity(InterventionActivity.intent(this, pkg))
            return
        }

        // Opened from another app rather than the home screen: most likely a
        // link or share someone sent
        val source = previousPkg?.takeIf { it != pkg && it !in homePackages }
        startPause(pkg, source)
    }

    /** Back in the app that was in front when the screen went off. */
    private fun onUnlocked() {
        // A scroll session that ran out while the phone was locked
        ScrollSession.onEnded(this)

        val pkg = lastForegroundPkg ?: return
        if (!AppPrefs.isGuarded(this, pkg)) return
        if (ScrollSession.isActive(this)) return
        if (AppPrefs.getAllowedPackage(this) == pkg) return
        if (InterventionActivity.pendingPackage == pkg) {
            startActivity(InterventionActivity.intent(this, pkg))
            return
        }
        startPause(pkg, null)
    }

    private fun startPause(pkg: String, source: String?) {
        AppPrefs.recordPause(this, pkg)
        InterventionActivity.pendingPackage = pkg

        val mode = when {
            ScrollSession.takeUnhandledEnd(this) -> InterventionActivity.Mode.SCROLL_END
            source != null -> InterventionActivity.Mode.LINK
            else -> InterventionActivity.Mode.PAUSE
        }

        // Launch intervention overlay
        startActivity(InterventionActivity.intent(this, pkg, mode, source))
    }

    override fun onInterrupt() {
        // Nothing to clean up
    }
}

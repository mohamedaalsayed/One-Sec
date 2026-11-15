package com.onesec.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * A few minutes in one guarded app (length set in the main screen).
 *
 * Runs while the user stays in that app; switching to another app or the
 * screen going off ends it quietly (see AppWatcherService). When the time
 * runs out in the app, the time's-up screen takes over.
 */
object QuickCheck {

    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null

    /** App the quick check is for, or null when none is running. */
    var pkg: String? = null
        private set

    fun start(ctx: Context, pkg: String, minutes: Int) {
        end()
        this.pkg = pkg
        val appCtx = ctx.applicationContext
        timeout = Runnable { timeUp(appCtx) }.also { handler.postDelayed(it, minutes * 60_000L) }
    }

    fun end() {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
        pkg = null
    }

    private fun timeUp(ctx: Context) {
        val p = pkg ?: return
        end()

        AppPrefs.clearAllowedPackage(ctx)
        // Screen off: nothing to show; unlocking back into the app pauses it
        if (ctx.getSystemService(PowerManager::class.java)?.isInteractive == false) return

        InterventionActivity.pendingPackage = p
        ctx.startActivity(InterventionActivity.intent(ctx, p, InterventionActivity.Mode.TIME_UP))
    }
}

package com.onesec.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * "I just want to scroll": 30 minutes of wall-clock time shared by every
 * guarded app. It keeps running while the apps are in the background, and
 * it's stored in prefs so it survives the process being killed.
 *
 * After each session a cooldown blocks the next one, growing through the
 * day: 1h after the first, then 3h, 5h, 7h... Both reset at 4 AM.
 */
object ScrollSession {

    const val DURATION_MINUTES = 30
    private const val DURATION_MS = DURATION_MINUTES * 60_000L
    private const val HOUR_MS = 3_600_000L

    private val handler = Handler(Looper.getMainLooper())
    private val endCheck = Runnable { appCtx?.let { onEnded(it) } }
    private var appCtx: Context? = null

    // End time whose "30 minutes are up" prompt was already dealt with
    private var handledEnd = 0L

    fun isActive(ctx: Context): Boolean =
        System.currentTimeMillis() < AppPrefs.getScrollSessionEnd(ctx)

    /** When the next session can start, or null if one can start now. */
    fun nextAvailableAt(ctx: Context): Long? {
        if (isActive(ctx)) return null
        val sessions = AppPrefs.getScrollSessionsToday(ctx)
        if (sessions == 0) return null
        val at = AppPrefs.getScrollSessionEnd(ctx) + cooldownMs(sessions)
        return at.takeIf { it > System.currentTimeMillis() }
    }

    /** When the next session would unlock if one started now. */
    fun nextAvailableIfStartedNow(ctx: Context): Long =
        System.currentTimeMillis() + DURATION_MS + cooldownMs(AppPrefs.getScrollSessionsToday(ctx) + 1)

    // 1h after the 1st session of the day, 3h after the 2nd, 5h after the 3rd...
    private fun cooldownMs(sessionsToday: Int): Long = (2L * sessionsToday - 1) * HOUR_MS

    fun start(ctx: Context) {
        val endsAt = System.currentTimeMillis() + DURATION_MS
        AppPrefs.recordScrollSession(ctx, endsAt)
        schedule(ctx)
    }

    /** (Re)arm the end timer; called on start and when the service connects. */
    fun schedule(ctx: Context) {
        appCtx = ctx.applicationContext
        handler.removeCallbacks(endCheck)
        val remaining = AppPrefs.getScrollSessionEnd(ctx) - System.currentTimeMillis()
        if (remaining > 0) handler.postDelayed(endCheck, remaining)
    }

    /**
     * The session has ended: if the user is still in a guarded app, ask how
     * they feel. Also called on unlock, since timers don't run in deep sleep
     * and the user may come back straight into the app they left.
     */
    fun onEnded(ctx: Context) {
        val end = AppPrefs.getScrollSessionEnd(ctx)
        if (end == 0L || end == handledEnd || System.currentTimeMillis() < end) return
        if (ctx.getSystemService(PowerManager::class.java)?.isInteractive == false) return
        handledEnd = end

        val pkg = AppWatcherService.foregroundPkg ?: return
        if (!AppPrefs.isGuarded(ctx, pkg)) return
        InterventionActivity.pendingPackage = pkg
        ctx.startActivity(InterventionActivity.intent(ctx, pkg, InterventionActivity.Mode.SCROLL_END))
    }

    /** True once, if a session ended unseen and its prompt is still owed. */
    fun takeUnhandledEnd(ctx: Context): Boolean {
        val end = AppPrefs.getScrollSessionEnd(ctx)
        if (end == 0L || end == handledEnd || System.currentTimeMillis() < end) return false
        handledEnd = end
        // Only owed if it ended recently; days-old sessions don't need a prompt
        return System.currentTimeMillis() - end < HOUR_MS
    }
}

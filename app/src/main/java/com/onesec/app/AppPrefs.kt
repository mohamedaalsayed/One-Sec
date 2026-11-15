package com.onesec.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Single lightweight data layer — just SharedPreferences.
 * No Room DB, no SQLite, no external libs. Minimal storage footprint.
 */
object AppPrefs {

    private const val PREF_NAME = "onesec"
    private const val KEY_GUARDED = "guarded_packages"
    private const val KEY_TODAY_DATE = "today_date"
    private const val KEY_TODAY_PAUSES = "today_pauses"
    private const val KEY_TODAY_SKIPPED = "today_skipped"
    private const val KEY_ALLOWED_PACKAGE = "open_anyway_package"
    private const val KEY_QUICK_CHECK_MINUTES = "quick_check_minutes"
    private const val KEY_SCROLL_SESSION_END = "scroll_session_end"
    private const val KEY_ALTERNATIVES = "alternatives"
    const val MAX_ALTERNATIVES = 5

    // Shown before a scroll session until the user writes their own
    private val DEFAULT_ALTERNATIVES = setOf(
        "Go for a walk", "Read 10 pages", "Text a friend", "Stretch for 5 minutes"
    )

    const val QUICK_CHECK_MIN_MINUTES = 2
    const val QUICK_CHECK_MAX_MINUTES = 5
    private const val QUICK_CHECK_DEFAULT_MINUTES = 3

    // Per-package stats stored as "pkg_pauses_<date>" and "pkg_skipped_<date>"
    private fun pauseKey(pkg: String, date: String) = "p_${pkg}_$date"
    private fun skipKey(pkg: String, date: String) = "s_${pkg}_$date"

    // Daily totals for links someone sent: watched "just this one", and how
    // many of those turned into "keep scrolling"
    private fun linksKey(date: String) = "links_$date"
    private fun keptScrollingKey(date: String) = "kept_$date"

    // Quick checks and scroll sessions used, per limit day (see limitDayStr)
    private fun quickChecksKey(day: String) = "qc_$day"
    private fun scrollSessionsKey(day: String) = "scroll_count_$day"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ---- Guarded apps ----

    fun getGuardedPackages(ctx: Context): Set<String> {
        return prefs(ctx).getStringSet(KEY_GUARDED, emptySet()) ?: emptySet()
    }

    fun addGuardedPackage(ctx: Context, pkg: String) {
        val set = getGuardedPackages(ctx).toMutableSet()
        set.add(pkg)
        prefs(ctx).edit().putStringSet(KEY_GUARDED, set).apply()
    }

    fun removeGuardedPackage(ctx: Context, pkg: String) {
        val set = getGuardedPackages(ctx).toMutableSet()
        set.remove(pkg)
        prefs(ctx).edit().putStringSet(KEY_GUARDED, set).apply()
    }

    fun isGuarded(ctx: Context, pkg: String): Boolean =
        getGuardedPackages(ctx).contains(pkg)

    // ---- Per-app access (quick check, "just this one") ----
    // Lets one guarded app through until the user switches to another app,
    // the screen goes off, or its timer runs out

    fun setAllowedPackage(ctx: Context, pkg: String) {
        prefs(ctx).edit().putString(KEY_ALLOWED_PACKAGE, pkg).apply()
    }

    fun getAllowedPackage(ctx: Context): String? {
        return prefs(ctx).getString(KEY_ALLOWED_PACKAGE, null)
    }

    fun clearAllowedPackage(ctx: Context) {
        prefs(ctx).edit().remove(KEY_ALLOWED_PACKAGE).apply()
    }

    // ---- Quick check ----

    fun getQuickCheckMinutes(ctx: Context): Int =
        prefs(ctx).getInt(KEY_QUICK_CHECK_MINUTES, QUICK_CHECK_DEFAULT_MINUTES)
            .coerceIn(QUICK_CHECK_MIN_MINUTES, QUICK_CHECK_MAX_MINUTES)

    fun setQuickCheckMinutes(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt(KEY_QUICK_CHECK_MINUTES, minutes).apply()
    }

    fun getQuickChecksToday(ctx: Context): Int =
        prefs(ctx).getInt(quickChecksKey(limitDayStr()), 0)

    fun recordQuickCheck(ctx: Context) {
        val p = prefs(ctx)
        val key = quickChecksKey(limitDayStr())
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
    }

    // ---- Scroll session ----

    /** Wall-clock end of the latest scroll session, or 0 if there's never been one. */
    fun getScrollSessionEnd(ctx: Context): Long =
        prefs(ctx).getLong(KEY_SCROLL_SESSION_END, 0L)

    fun getScrollSessionsToday(ctx: Context): Int =
        prefs(ctx).getInt(scrollSessionsKey(limitDayStr()), 0)

    fun recordScrollSession(ctx: Context, endsAt: Long) {
        val p = prefs(ctx)
        val key = scrollSessionsKey(limitDayStr())
        p.edit()
            .putLong(KEY_SCROLL_SESSION_END, endsAt)
            .putInt(key, p.getInt(key, 0) + 1)
            .apply()
    }

    // ---- Things to do instead of scrolling ----

    /** The user's list, or the defaults if they've never edited it. */
    fun getAlternatives(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_ALTERNATIVES, null) ?: DEFAULT_ALTERNATIVES

    fun setAlternatives(ctx: Context, alternatives: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_ALTERNATIVES, alternatives).apply()
    }

    /** What to suggest; the defaults if the user emptied the list. */
    fun getAlternativesToSuggest(ctx: Context): List<String> =
        getAlternatives(ctx).ifEmpty { DEFAULT_ALTERNATIVES }.sorted().take(MAX_ALTERNATIVES)

    // ---- Daily stats ----

    private fun todayStr(): String {
        val cal = java.util.Calendar.getInstance()
        return "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
    }

    // Limits reset at 4 AM rather than midnight, so late-night scrolling
    // counts as the same night
    private fun limitDayStr(): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.HOUR_OF_DAY, -4)
        return "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
    }

    fun recordPause(ctx: Context, pkg: String) {
        val today = todayStr()
        val p = prefs(ctx)
        val key = pauseKey(pkg, today)
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
    }

    fun recordSkipped(ctx: Context, pkg: String) {
        val today = todayStr()
        val p = prefs(ctx)
        val key = skipKey(pkg, today)
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
    }

    fun recordLinkWatched(ctx: Context) {
        val p = prefs(ctx)
        val key = linksKey(todayStr())
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
    }

    fun recordKeptScrolling(ctx: Context) {
        val p = prefs(ctx)
        val key = keptScrollingKey(todayStr())
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
    }

    fun getTodayLinksWatched(ctx: Context): Int =
        prefs(ctx).getInt(linksKey(todayStr()), 0)

    fun getTodayKeptScrolling(ctx: Context): Int =
        prefs(ctx).getInt(keptScrollingKey(todayStr()), 0)

    /** Total pauses across all apps today */
    fun getTodayTotalPauses(ctx: Context): Int {
        val today = todayStr()
        val p = prefs(ctx)
        return getGuardedPackages(ctx).sumOf { pkg -> p.getInt(pauseKey(pkg, today), 0) }
    }

    /** Total skipped (opened anyway) across all apps today */
    fun getTodayTotalSkipped(ctx: Context): Int {
        val today = todayStr()
        val p = prefs(ctx)
        return getGuardedPackages(ctx).sumOf { pkg -> p.getInt(skipKey(pkg, today), 0) }
    }

    /** Pauses for a specific package today */
    fun getPausesForPackage(ctx: Context, pkg: String): Int {
        return prefs(ctx).getInt(pauseKey(pkg, todayStr()), 0)
    }

    /** How many times user tried to open pkg today (pauses + skips) */
    fun getAttemptsForPackage(ctx: Context, pkg: String): Int {
        val today = todayStr()
        val p = prefs(ctx)
        return p.getInt(pauseKey(pkg, today), 0) + p.getInt(skipKey(pkg, today), 0)
    }
}

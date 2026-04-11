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
    private const val KEY_OPEN_ANYWAY_PACKAGE = "open_anyway_package"

    // Per-package stats stored as "pkg_pauses_<date>" and "pkg_skipped_<date>"
    private fun pauseKey(pkg: String, date: String) = "p_${pkg}_$date"
    private fun skipKey(pkg: String, date: String) = "s_${pkg}_$date"

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

    // ---- Open-anyway bypass ----

    fun setOpenAnywayPackage(ctx: Context, pkg: String) {
        prefs(ctx).edit().putString(KEY_OPEN_ANYWAY_PACKAGE, pkg).apply()
    }

    fun getOpenAnywayPackage(ctx: Context): String? {
        return prefs(ctx).getString(KEY_OPEN_ANYWAY_PACKAGE, null)
    }

    fun clearOpenAnywayPackage(ctx: Context) {
        prefs(ctx).edit().remove(KEY_OPEN_ANYWAY_PACKAGE).apply()
    }

    // ---- Daily stats ----

    private fun todayStr(): String {
        val cal = java.util.Calendar.getInstance()
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

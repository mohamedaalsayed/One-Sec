package com.onesec.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var tvStats: TextView
    private lateinit var layoutPermissions: LinearLayout
    private lateinit var btnAccessibility: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnAddApp: Button
    private lateinit var tvNoApps: TextView
    private lateinit var layoutQuickCheckMinutes: LinearLayout
    private lateinit var layoutAlternatives: LinearLayout
    private lateinit var etAlternative: EditText
    private lateinit var recyclerApps: RecyclerView
    private lateinit var guardedAdapter: GuardedAppsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStats = findViewById(R.id.tvStats)
        layoutPermissions = findViewById(R.id.layoutPermissions)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnAddApp = findViewById(R.id.btnAddApp)
        tvNoApps = findViewById(R.id.tvNoApps)
        layoutQuickCheckMinutes = findViewById(R.id.layoutQuickCheckMinutes)
        layoutAlternatives = findViewById(R.id.layoutAlternatives)
        etAlternative = findViewById(R.id.etAlternative)
        recyclerApps = findViewById(R.id.recyclerApps)

        setupRecycler()
        setupButtons()
        setupQuickCheckMinutes()
        setupAlternatives()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        refreshAppsList()
        refreshStats()
    }

    private fun setupRecycler() {
        guardedAdapter = GuardedAppsAdapter(this) { pkg ->
            AppPrefs.removeGuardedPackage(this, pkg)
            refreshAppsList()
        }
        recyclerApps.layoutManager = LinearLayoutManager(this)
        recyclerApps.adapter = guardedAdapter
    }

    private fun setupButtons() {
        btnAccessibility.setOnClickListener {
            if (isAccessibilityEnabled()) return@setOnClickListener

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.accessibility_help_title))
                .setMessage(getString(R.string.accessibility_help_message))
                .setPositiveButton(getString(R.string.accessibility_help_open_app_info)) { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton(getString(R.string.accessibility_help_open_accessibility)) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .show()
        }

        btnOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnAddApp.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
    }

    private fun setupQuickCheckMinutes() {
        val density = resources.displayMetrics.density
        for (minutes in AppPrefs.QUICK_CHECK_MIN_MINUTES..AppPrefs.QUICK_CHECK_MAX_MINUTES) {
            val chip = TextView(this).apply {
                text = getString(R.string.quick_check_minutes_chip, minutes)
                textSize = 13f
                setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
                setOnClickListener {
                    AppPrefs.setQuickCheckMinutes(this@MainActivity, minutes)
                    refreshQuickCheckMinutes()
                }
                tag = minutes
            }
            layoutQuickCheckMinutes.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * density).toInt() })
        }
        refreshQuickCheckMinutes()
    }

    private fun refreshQuickCheckMinutes() {
        val selected = AppPrefs.getQuickCheckMinutes(this)
        for (i in 0 until layoutQuickCheckMinutes.childCount) {
            val chip = layoutQuickCheckMinutes.getChildAt(i) as TextView
            if (chip.tag == selected) {
                chip.setBackgroundResource(R.drawable.bg_chip_selected)
                chip.setTextColor(getColor(R.color.bg))
            } else {
                chip.setBackgroundResource(R.drawable.bg_chip)
                chip.setTextColor(getColor(R.color.text_secondary))
            }
        }
    }

    private fun setupAlternatives() {
        findViewById<Button>(R.id.btnAddAlternative).setOnClickListener { addAlternative() }
        etAlternative.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addAlternative()
                true
            } else {
                false
            }
        }
        refreshAlternatives()
    }

    private fun addAlternative() {
        val text = etAlternative.text.toString().trim()
        if (text.isEmpty()) return
        if (AppPrefs.getAlternatives(this).size >= AppPrefs.MAX_ALTERNATIVES) return
        AppPrefs.setAlternatives(this, AppPrefs.getAlternatives(this) + text)
        etAlternative.setText("")
        refreshAlternatives()
    }

    private fun refreshAlternatives() {
        layoutAlternatives.removeAllViews()
        val density = resources.displayMetrics.density
        AppPrefs.getAlternatives(this).sorted().forEach { alternative ->
            val row = TextView(this).apply {
                text = "$alternative   ×"
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                setOnClickListener {
                    AppPrefs.setAlternatives(this@MainActivity, AppPrefs.getAlternatives(this@MainActivity) - alternative)
                    refreshAlternatives()
                }
            }
            layoutAlternatives.addView(row)
        }

        // Room for at most MAX_ALTERNATIVES, so they all fit on the pause screen
        val full = AppPrefs.getAlternatives(this).size >= AppPrefs.MAX_ALTERNATIVES
        etAlternative.isEnabled = !full
        findViewById<Button>(R.id.btnAddAlternative).isEnabled = !full
        etAlternative.hint = if (full) {
            getString(R.string.alternatives_full_hint, AppPrefs.MAX_ALTERNATIVES)
        } else {
            getString(R.string.alternatives_hint)
        }
    }

    private fun refreshPermissionState() {
        val accessibilityOk = isAccessibilityEnabled()
        val overlayOk = Settings.canDrawOverlays(this)
        val allOk = accessibilityOk && overlayOk

        layoutPermissions.visibility = if (allOk) View.GONE else View.VISIBLE

        if (!accessibilityOk) {
            btnAccessibility.visibility = View.VISIBLE
            btnAccessibility.text = getString(R.string.btn_enable_service)
            btnAccessibility.isEnabled = true
        } else {
            btnAccessibility.visibility = View.VISIBLE
            btnAccessibility.text = getString(R.string.btn_service_enabled)
            btnAccessibility.isEnabled = false
        }

        if (!overlayOk) {
            btnOverlay.visibility = View.VISIBLE
            btnOverlay.text = getString(R.string.btn_enable_overlay)
            btnOverlay.isEnabled = true
        } else {
            btnOverlay.visibility = View.VISIBLE
            btnOverlay.text = getString(R.string.btn_overlay_enabled)
            btnOverlay.isEnabled = false
        }
    }

    private fun refreshAppsList() {
        val packages = AppPrefs.getGuardedPackages(this)
        if (packages.isEmpty()) {
            tvNoApps.visibility = View.VISIBLE
            recyclerApps.visibility = View.GONE
        } else {
            tvNoApps.visibility = View.GONE
            recyclerApps.visibility = View.VISIBLE
            guardedAdapter.setPackages(packages.toList())
        }
    }

    private fun refreshStats() {
        val pauses = AppPrefs.getTodayTotalPauses(this)
        val skipped = AppPrefs.getTodayTotalSkipped(this)
        val links = AppPrefs.getTodayLinksWatched(this)
        val keptScrolling = AppPrefs.getTodayKeptScrolling(this)

        val lines = mutableListOf<String>()
        if (pauses > 0) {
            lines += String.format("%d pauses today  ·  %d opened", pauses, skipped)
        }
        if (links > 0) {
            lines += String.format("%d links watched  ·  %d kept scrolling", links, keptScrolling)
        }

        val timeFormat = DateFormat.getTimeFormat(this)
        val quickChecks = AppPrefs.getQuickChecksToday(this)
        val nextScroll = ScrollSession.nextAvailableAt(this)
        val scrollStatus = when {
            ScrollSession.isActive(this) ->
                "scroll session until " + timeFormat.format(AppPrefs.getScrollSessionEnd(this))
            nextScroll != null -> "next scroll session " + timeFormat.format(nextScroll)
            else -> null
        }
        if (quickChecks > 0 || scrollStatus != null) {
            lines += listOfNotNull(
                if (quickChecks > 0) String.format("%d quick checks", quickChecks) else null,
                scrollStatus
            ).joinToString("  ·  ")
        }
        tvStats.text = lines.joinToString("\n")
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            enabled?.contains(packageName) == true
        } catch (e: Exception) {
            false
        }
    }
}

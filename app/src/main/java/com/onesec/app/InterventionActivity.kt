package com.onesec.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen intervention that appears over a guarded app.
 *
 * Phase 1: Breathing countdown (animated ring)
 * Phase 2: Decision screen
 *
 * Modes:
 *  - PAUSE: opened from the launcher. 5-second breath, reason chips, then
 *    go back / quick check / scroll session.
 *  - QUICK_CONFIRM: "Is it that important?" before a quick check, with more
 *    friction the more quick checks there have been today.
 *  - SCROLL_CONFIRM: before a scroll session, one of the user's own
 *    alternatives and when the next session would unlock; "scroll" stays
 *    locked for a few seconds.
 *  - LINK: opened from another app, most likely a link someone sent.
 *    Shorter breath, then watch just this one (starts a [LinkSession])
 *    or go back to that app.
 *  - CHECK_IN: shown by [LinkSession] once the user moves past what they
 *    were sent. Back to the app it came from, or keep scrolling through
 *    a full pause.
 *  - TIME_UP: a [QuickCheck] ran out.
 *  - SCROLL_END: a [ScrollSession] ran out.
 */
class InterventionActivity : AppCompatActivity() {

    enum class Mode { PAUSE, QUICK_CONFIRM, SCROLL_CONFIRM, LINK, CHECK_IN, TIME_UP, SCROLL_END }

    companion object {
        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SOURCE = "source"

        private const val PAUSE_SECONDS = 5
        private const val LINK_PAUSE_SECONDS = 3

        // Quick-check friction: from the 3rd of the day a wait before "yes",
        // from the 5th also typing what you're checking
        private const val QUICK_WAIT_FROM = 3
        private const val QUICK_TYPE_FROM = 5
        private const val QUICK_WAIT_SECONDS = 10
        private const val QUICK_REASON_MIN_CHARS = 3

        private const val SCROLL_CONFIRM_WAIT_SECONDS = 5

        /**
         * Package whose pause is in progress (showing, or covered by the app),
         * or null once the user has made a choice.
         */
        @Volatile
        var pendingPackage: String? = null

        /**
         * With a [mode], starts a fresh pause (replacing any old one still
         * alive). Without one, just brings the pause in progress back to front.
         */
        fun intent(ctx: Context, pkg: String, mode: Mode? = null, source: String? = null): Intent =
            Intent(ctx, InterventionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_PACKAGE, pkg)
                if (mode != null) putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_SOURCE, source)
            }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var countdownSeconds = PAUSE_SECONDS
    private var selectedReason: String? = null
    private lateinit var targetPackage: String
    private var mode = Mode.PAUSE

    // App the guarded app was opened from (LINK and CHECK_IN modes)
    private var sourcePackage: String? = null

    // Set when the pause screen gets hidden before the user decides, so the
    // countdown starts over instead of finishing in the background
    private var restartOnStart = false

    // Views - Breathe phase
    private lateinit var layoutBreathe: LinearLayout
    private lateinit var ivBreathCore: ImageView
    private lateinit var ivBreathRingMid: ImageView
    private lateinit var ivBreathRingOuter: ImageView
    private lateinit var tvCountdown: TextView
    private lateinit var tvBreatheLabel: TextView
    private lateinit var ivAppIcon: ImageView
    private lateinit var tvAppName: TextView

    // Views - Decide phase
    private lateinit var layoutDecide: LinearLayout
    private lateinit var tvDecideTag: TextView
    private lateinit var tvTitleTop: TextView
    private lateinit var tvTitleBottom: TextView
    private lateinit var layoutReasons: LinearLayout
    private lateinit var etQuickReason: EditText
    private lateinit var layoutAlternativeButtons: LinearLayout
    private lateinit var btnPrimary: Button
    private lateinit var btnSecondary: Button
    private lateinit var btnTertiary: Button
    private lateinit var chipBored: TextView
    private lateinit var chipHabit: TextView
    private lateinit var chipEscape: TextView
    private lateinit var chipProcrastinate: TextView
    private lateinit var chipIntentional: TextView

    private val allChips get() = listOf(chipBored, chipHabit, chipEscape, chipProcrastinate, chipIntentional)

    private var breathAnimator: ValueAnimator? = null
    private var countdownRunnable: Runnable? = null
    private var quickReasonWatcher: TextWatcher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intervention)

        if (!readIntent(intent)) {
            finish()
            return
        }
        pendingPackage = targetPackage

        bindViews()
        setupAppInfo()
        setupChips()
        startFlow()
    }

    // singleTask: the service re-launching us lands here, not in onCreate
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        pendingPackage = pkg
        // Fresh pause, or a different app: start over with the new intent.
        // Otherwise we're just being brought back to front: keep the flow.
        if (intent.hasExtra(EXTRA_MODE) || pkg != targetPackage) {
            readIntent(intent)
            setupAppInfo()
            restartOnStart = false
            restartFlow()
        }
    }

    override fun onStart() {
        super.onStart()
        if (restartOnStart) {
            restartOnStart = false
            restartFlow()
        }
    }

    override fun onStop() {
        super.onStop()
        // Covered by the guarded app (e.g. opened from a link) or the user
        // pressed Home: don't let the countdown run out unseen.
        if (!isFinishing && !isChangingConfigurations) {
            cancelCountdown()
            breathAnimator?.cancel()
            restartOnStart = true
        }
    }

    private fun readIntent(intent: Intent): Boolean {
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE) ?: return false
        sourcePackage = intent.getStringExtra(EXTRA_SOURCE)
        val requested = intent.getStringExtra(EXTRA_MODE)
            ?.let { name -> Mode.values().firstOrNull { it.name == name } }
            ?: Mode.PAUSE
        // LINK and CHECK_IN are about going back to the source app
        mode = if (sourcePackage == null && (requested == Mode.LINK || requested == Mode.CHECK_IN)) {
            Mode.PAUSE
        } else {
            requested
        }
        return true
    }

    private fun bindViews() {
        layoutBreathe = findViewById(R.id.layoutBreathe)
        ivBreathCore = findViewById(R.id.ivBreathCore)
        ivBreathRingMid = findViewById(R.id.ivBreathRingMid)
        ivBreathRingOuter = findViewById(R.id.ivBreathRingOuter)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvBreatheLabel = findViewById(R.id.tvBreatheLabel)
        ivAppIcon = findViewById(R.id.ivAppIcon)
        tvAppName = findViewById(R.id.tvAppName)

        layoutDecide = findViewById(R.id.layoutDecide)
        tvDecideTag = findViewById(R.id.tvDecideTag)
        tvTitleTop = findViewById(R.id.tvTitleTop)
        tvTitleBottom = findViewById(R.id.tvTitleBottom)
        layoutReasons = findViewById(R.id.layoutReasons)
        etQuickReason = findViewById(R.id.etQuickReason)
        layoutAlternativeButtons = findViewById(R.id.layoutAlternativeButtons)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnSecondary = findViewById(R.id.btnSecondary)
        btnTertiary = findViewById(R.id.btnTertiary)
        chipBored = findViewById(R.id.chipBored)
        chipHabit = findViewById(R.id.chipHabit)
        chipEscape = findViewById(R.id.chipEscape)
        chipProcrastinate = findViewById(R.id.chipProcrastinate)
        chipIntentional = findViewById(R.id.chipIntentional)
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun setupAppInfo() {
        try {
            val icon: Drawable = packageManager.getApplicationIcon(targetPackage)
            ivAppIcon.setImageDrawable(icon)
        } catch (_: Exception) {}
        tvAppName.text = appLabel(targetPackage)
    }

    private fun setupChips() {
        allChips.forEach { chip ->
            chip.setOnClickListener {
                selectChip(chip)
            }
        }
    }

    private fun selectChip(selected: TextView) {
        allChips.forEach { chip ->
            if (chip == selected) {
                chip.setBackgroundResource(R.drawable.bg_chip_selected)
                chip.setTextColor(getColor(R.color.bg))
                selectedReason = chip.text.toString()
            } else {
                chip.setBackgroundResource(R.drawable.bg_chip)
                chip.setTextColor(getColor(R.color.text_secondary))
            }
        }
    }

    // ---- Flow ----

    private fun startFlow() {
        when (mode) {
            Mode.PAUSE -> startBreathingPhase(PAUSE_SECONDS)
            Mode.LINK -> startBreathingPhase(LINK_PAUSE_SECONDS)
            // They already waited when they opened the app
            Mode.CHECK_IN, Mode.TIME_UP, Mode.SCROLL_END -> showDecidePhase()
            // Only reached from the PAUSE decision screen
            Mode.QUICK_CONFIRM, Mode.SCROLL_CONFIRM -> {
                mode = Mode.PAUSE
                startBreathingPhase(PAUSE_SECONDS)
            }
        }
    }

    private fun restartFlow() {
        cancelCountdown()
        breathAnimator?.cancel()
        layoutDecide.animate().cancel()

        selectedReason = null
        allChips.forEach { chip ->
            chip.setBackgroundResource(R.drawable.bg_chip)
            chip.setTextColor(getColor(R.color.text_secondary))
        }
        clearQuickReason()
        clearAlternativeButtons()
        layoutDecide.visibility = View.GONE
        layoutBreathe.visibility = View.VISIBLE

        startFlow()
    }

    // ---- Breathing Phase ----

    private fun startBreathingPhase(seconds: Int) {
        countdownSeconds = seconds
        tvCountdown.text = countdownSeconds.toString()
        tvBreatheLabel.text = "breathe in"

        startBreathAnimation()
        vibrateSoft()
        startCountdown()
    }

    private fun startBreathAnimation() {
        breathAnimator?.cancel()

        // Animate the core circle scaling in and out (4-second cycle)
        breathAnimator = ValueAnimator.ofFloat(0.6f, 1.0f, 0.6f).apply {
            duration = 4000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                ivBreathCore.scaleX = scale
                ivBreathCore.scaleY = scale
                ivBreathRingMid.scaleX = 0.8f + (scale - 0.6f) * 0.5f
                ivBreathRingMid.scaleY = 0.8f + (scale - 0.6f) * 0.5f
                ivBreathRingOuter.scaleX = 0.6f + (scale - 0.6f) * 0.3f
                ivBreathRingOuter.scaleY = 0.6f + (scale - 0.6f) * 0.3f
                ivBreathRingOuter.alpha = 0.15f + (scale - 0.6f) * 0.4f

                // Update breathe label halfway through
                if (anim.animatedFraction < 0.5f) {
                    tvBreatheLabel.text = "breathe in"
                } else {
                    tvBreatheLabel.text = "breathe out"
                }
            }
            start()
        }
    }

    private fun startCountdown() {
        val runnable = object : Runnable {
            override fun run() {
                countdownSeconds--
                tvCountdown.text = countdownSeconds.toString()

                if (countdownSeconds <= 0) {
                    showDecidePhase()
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        countdownRunnable = runnable
        handler.postDelayed(runnable, 1000)
    }

    // ---- Decision Phase ----

    private fun showDecidePhase() {
        cancelCountdown()
        breathAnimator?.cancel()

        val sourceName = sourcePackage?.let { appLabel(it) }
        val quickMinutes = AppPrefs.getQuickCheckMinutes(this)
        layoutReasons.visibility = View.GONE
        btnTertiary.visibility = View.GONE
        clearAlternativeButtons()

        when (mode) {
            Mode.PAUSE -> {
                val attempts = AppPrefs.getAttemptsForPackage(this, targetPackage)
                tvDecideTag.text = when {
                    attempts <= 1 -> "first time today"
                    else -> "$attempts times today"
                }
                tvTitleTop.text = getString(R.string.pause_title_top)
                tvTitleBottom.text = getString(R.string.pause_title_bottom)
                layoutReasons.visibility = View.VISIBLE
                setButtons(
                    getString(R.string.btn_go_back), ::goHome,
                    getString(R.string.btn_quick_check, quickMinutes), ::showQuickConfirm
                )
                showScrollSessionButton()
            }
            Mode.QUICK_CONFIRM -> {
                showQuickConfirm()
                return
            }
            Mode.SCROLL_CONFIRM -> {
                showScrollConfirm()
                return
            }
            Mode.LINK -> {
                tvDecideTag.text = getString(R.string.tag_from_app, sourceName)
                tvTitleTop.text = getString(R.string.link_title_top)
                tvTitleBottom.text = getString(R.string.link_title_bottom)
                setButtons(
                    getString(R.string.btn_watch_one), ::watchJustThisOne,
                    getString(R.string.btn_back_to, sourceName), ::backToSource
                )
            }
            Mode.CHECK_IN -> {
                tvDecideTag.text = getString(R.string.tag_from_app, sourceName)
                tvTitleTop.text = getString(R.string.checkin_title_top)
                tvTitleBottom.text = getString(R.string.checkin_title_bottom, sourceName)
                setButtons(
                    getString(R.string.btn_back_to, sourceName), ::backToSource,
                    getString(R.string.btn_keep_scrolling), ::keepScrolling
                )
            }
            Mode.TIME_UP -> {
                tvDecideTag.text = getString(R.string.timeup_tag, quickMinutes)
                tvTitleTop.text = getString(R.string.timeup_title_top)
                tvTitleBottom.text = getString(R.string.timeup_title_bottom)
                setButtons(
                    getString(R.string.btn_done), ::goHome,
                    getString(R.string.btn_more_time), ::backToFullPause
                )
            }
            Mode.SCROLL_END -> {
                tvDecideTag.text = getString(R.string.scroll_end_tag, ScrollSession.DURATION_MINUTES)
                tvTitleTop.text = getString(R.string.scroll_end_title_top, ScrollSession.DURATION_MINUTES)
                tvTitleBottom.text = getString(R.string.scroll_end_title_bottom)
                setButtons(
                    getString(R.string.btn_im_done), ::goHome,
                    getString(R.string.btn_keep_going), ::backToFullPause
                )
            }
        }

        revealDecidePhase()
    }

    private fun revealDecidePhase() {
        layoutBreathe.visibility = View.GONE
        layoutDecide.visibility = View.VISIBLE
        layoutDecide.alpha = 0f
        layoutDecide.animate().alpha(1f).setDuration(300).start()

        vibrateSoft()
    }

    private fun setButtons(
        primaryText: String, onPrimary: () -> Unit,
        secondaryText: String, onSecondary: () -> Unit
    ) {
        btnPrimary.text = primaryText
        btnPrimary.visibility = View.VISIBLE
        btnPrimary.setOnClickListener { onPrimary() }
        btnSecondary.text = secondaryText
        btnSecondary.isEnabled = true
        btnSecondary.setOnClickListener { onSecondary() }
    }

    private fun showScrollSessionButton() {
        btnTertiary.visibility = View.VISIBLE
        val nextAt = ScrollSession.nextAvailableAt(this)
        if (nextAt == null) {
            btnTertiary.text = getString(R.string.btn_scroll_session, ScrollSession.DURATION_MINUTES)
            btnTertiary.isEnabled = true
            btnTertiary.setOnClickListener { showScrollConfirm() }
        } else {
            btnTertiary.text = getString(
                R.string.btn_scroll_cooldown, DateFormat.getTimeFormat(this).format(nextAt)
            )
            btnTertiary.isEnabled = false
            btnTertiary.setOnClickListener(null)
        }
    }

    // ---- Scroll session confirmation ----

    private fun showScrollConfirm() {
        mode = Mode.SCROLL_CONFIRM
        cancelCountdown()

        val minutes = ScrollSession.DURATION_MINUTES
        val number = AppPrefs.getScrollSessionsToday(this) + 1
        val nextAt = DateFormat.getTimeFormat(this).format(ScrollSession.nextAvailableIfStartedNow(this))

        tvDecideTag.text = getString(R.string.scroll_confirm_tag, number, nextAt)
        tvTitleTop.text = getString(R.string.scroll_confirm_title_top)
        tvTitleBottom.text = getString(R.string.scroll_confirm_title_bottom)
        layoutReasons.visibility = View.GONE
        btnTertiary.visibility = View.GONE
        setButtons(
            "", ::goHome,
            getString(R.string.btn_scroll_confirm, minutes), ::startScrollSession
        )
        // The alternatives take the main button's place, one each
        btnPrimary.visibility = View.GONE
        clearAlternativeButtons()
        AppPrefs.getAlternativesToSuggest(this).forEach { alternative ->
            val button = layoutInflater.inflate(
                R.layout.item_alternative_button, layoutAlternativeButtons, false
            ) as Button
            button.text = alternative
            button.setOnClickListener { goHome() }
            layoutAlternativeButtons.addView(button)
        }
        layoutAlternativeButtons.visibility = View.VISIBLE

        // Time to actually read it before "scroll" unlocks
        var waitLeft = SCROLL_CONFIRM_WAIT_SECONDS
        btnSecondary.isEnabled = false
        btnSecondary.text = getString(R.string.btn_scroll_confirm_wait, minutes, waitLeft)
        val runnable = object : Runnable {
            override fun run() {
                waitLeft--
                if (waitLeft > 0) {
                    btnSecondary.text = getString(R.string.btn_scroll_confirm_wait, minutes, waitLeft)
                    handler.postDelayed(this, 1000)
                } else {
                    btnSecondary.text = getString(R.string.btn_scroll_confirm, minutes)
                    btnSecondary.isEnabled = true
                }
            }
        }
        countdownRunnable = runnable
        handler.postDelayed(runnable, 1000)

        if (layoutDecide.visibility != View.VISIBLE) revealDecidePhase()
    }

    private fun clearAlternativeButtons() {
        layoutAlternativeButtons.removeAllViews()
        layoutAlternativeButtons.visibility = View.GONE
    }

    // ---- Quick check confirmation ----

    private fun showQuickConfirm() {
        mode = Mode.QUICK_CONFIRM
        cancelCountdown()

        val minutes = AppPrefs.getQuickCheckMinutes(this)
        val number = AppPrefs.getQuickChecksToday(this) + 1
        val yesText = getString(R.string.btn_quick_yes, minutes)

        tvDecideTag.text = if (number == 1) {
            getString(R.string.quick_tag_first)
        } else {
            getString(R.string.quick_tag_count, number)
        }
        tvTitleTop.text = getString(R.string.quick_title_top)
        tvTitleBottom.text = getString(R.string.quick_title_bottom)
        layoutReasons.visibility = View.GONE
        btnTertiary.visibility = View.GONE
        setButtons(getString(R.string.btn_quick_no), ::goHome, yesText, ::startQuickCheck)

        val mustType = number >= QUICK_TYPE_FROM
        val mustWait = number >= QUICK_WAIT_FROM
        var waitLeft = if (mustWait) QUICK_WAIT_SECONDS else 0

        fun refreshYes() {
            val typedEnough = !mustType ||
                etQuickReason.text.toString().trim().length >= QUICK_REASON_MIN_CHARS
            btnSecondary.isEnabled = waitLeft == 0 && typedEnough
            btnSecondary.text = if (waitLeft > 0) {
                getString(R.string.btn_quick_yes_wait, minutes, waitLeft)
            } else {
                yesText
            }
        }

        clearQuickReason()
        if (mustType) {
            etQuickReason.visibility = View.VISIBLE
            quickReasonWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = refreshYes()
            }.also { etQuickReason.addTextChangedListener(it) }
        }

        if (mustWait) {
            val runnable = object : Runnable {
                override fun run() {
                    waitLeft--
                    refreshYes()
                    if (waitLeft > 0) handler.postDelayed(this, 1000)
                }
            }
            countdownRunnable = runnable
            handler.postDelayed(runnable, 1000)
        }
        refreshYes()

        if (layoutDecide.visibility != View.VISIBLE) revealDecidePhase()
    }

    private fun clearQuickReason() {
        quickReasonWatcher?.let { etQuickReason.removeTextChangedListener(it) }
        quickReasonWatcher = null
        etQuickReason.setText("")
        etQuickReason.visibility = View.GONE
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(etQuickReason.windowToken, 0)
    }

    // ---- Choices ----

    private fun startQuickCheck() {
        AppPrefs.recordQuickCheck(this)
        AppPrefs.recordSkipped(this, targetPackage)
        AppPrefs.setAllowedPackage(this, targetPackage)
        pendingPackage = null
        QuickCheck.start(this, targetPackage, AppPrefs.getQuickCheckMinutes(this))
        openTargetApp()
    }

    private fun startScrollSession() {
        AppPrefs.recordSkipped(this, targetPackage)
        pendingPackage = null
        ScrollSession.start(this)
        openTargetApp()
    }

    private fun watchJustThisOne() {
        val source = sourcePackage ?: return backToFullPause()
        AppPrefs.recordLinkWatched(this)
        AppPrefs.setAllowedPackage(this, targetPackage)
        pendingPackage = null
        LinkSession.start(this, targetPackage, source)
        // Just close: the link's content is right underneath. Relaunching the
        // app from its launcher entry can land on its home feed instead
        // (Facebook does), which is exactly where the scrolling starts.
        finish()
    }

    private fun keepScrolling() {
        AppPrefs.recordKeptScrolling(this)
        backToFullPause()
    }

    // More time is a fresh decision: full pause, reasons, the usual options
    private fun backToFullPause() {
        mode = Mode.PAUSE
        sourcePackage = null
        restartFlow()
    }

    // ---- Navigation ----

    private fun goHome() {
        pendingPackage = null
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    private fun backToSource() {
        // Brings the source app's existing task (e.g. the chat) to front
        val launchIntent = sourcePackage?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launchIntent == null) {
            goHome()
            return
        }
        pendingPackage = null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        finish()
    }

    private fun openTargetApp() {
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }
        finish()
    }

    // ---- Haptics ----

    private fun vibrateSoft() {
        try {
            val vibrator = getSystemService(Vibrator::class.java)
            vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    // ---- Cleanup ----

    private fun cancelCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
    }

    override fun onDestroy() {
        cancelCountdown()
        breathAnimator?.cancel()
        super.onDestroy()
    }

    // Prevent back button from dismissing without going through the flow
    override fun onBackPressed() {
        when (mode) {
            Mode.LINK, Mode.CHECK_IN -> backToSource()
            else -> goHome()
        }
    }
}

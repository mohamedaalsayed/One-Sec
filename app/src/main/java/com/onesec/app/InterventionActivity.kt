package com.onesec.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen intervention that appears over a guarded app.
 *
 * Phase 1: Breathing countdown (5 seconds, animated ring)
 * Phase 2: Decision screen with reason chips + action buttons
 */
class InterventionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE = "pkg"

        /**
         * Package whose pause is in progress (showing, or covered by the app),
         * or null once the user has chosen "go back" or "open anyway".
         */
        @Volatile
        var pendingPackage: String? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var countdownSeconds = 5
    private var selectedReason: String? = null
    private lateinit var targetPackage: String

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
    private lateinit var tvAttemptCount: TextView
    private lateinit var btnGoBack: Button
    private lateinit var btnOpenAnyway: Button
    private lateinit var chipBored: TextView
    private lateinit var chipHabit: TextView
    private lateinit var chipEscape: TextView
    private lateinit var chipProcrastinate: TextView
    private lateinit var chipIntentional: TextView

    private val allChips get() = listOf(chipBored, chipHabit, chipEscape, chipProcrastinate, chipIntentional)

    private var breathAnimator: ValueAnimator? = null
    private var countdownRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intervention)

        targetPackage = intent.getStringExtra(EXTRA_PACKAGE) ?: run {
            finish()
            return
        }
        pendingPackage = targetPackage

        bindViews()
        setupAppInfo()
        setupChips()
        setupButtons()
        startBreathingPhase()
    }

    // singleTask: the service re-launching us lands here, not in onCreate
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        pendingPackage = pkg
        if (pkg != targetPackage) {
            targetPackage = pkg
            setupAppInfo()
            restartBreathingPhase()
        }
    }

    override fun onStart() {
        super.onStart()
        if (restartOnStart) {
            restartOnStart = false
            restartBreathingPhase()
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
        tvAttemptCount = findViewById(R.id.tvAttemptCount)
        btnGoBack = findViewById(R.id.btnGoBack)
        btnOpenAnyway = findViewById(R.id.btnOpenAnyway)
        chipBored = findViewById(R.id.chipBored)
        chipHabit = findViewById(R.id.chipHabit)
        chipEscape = findViewById(R.id.chipEscape)
        chipProcrastinate = findViewById(R.id.chipProcrastinate)
        chipIntentional = findViewById(R.id.chipIntentional)
    }

    private fun setupAppInfo() {
        try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(targetPackage, 0)
            val appName = pm.getApplicationLabel(appInfo).toString()
            val icon: Drawable = pm.getApplicationIcon(appInfo)
            ivAppIcon.setImageDrawable(icon)
            tvAppName.text = appName
        } catch (e: Exception) {
            tvAppName.text = targetPackage
        }
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

    private fun setupButtons() {
        btnGoBack.setOnClickListener {
            // User chose to go back — success! Just close this and go home.
            goHome()
        }

        btnOpenAnyway.setOnClickListener {
            // User chose to open the app anyway
            AppPrefs.recordSkipped(this, targetPackage)
            AppPrefs.setOpenAnywayPackage(this, targetPackage)
            pendingPackage = null
            openTargetApp()
        }
    }

    // ---- Breathing Phase ----

    private fun startBreathingPhase() {
        countdownSeconds = 5
        tvCountdown.text = countdownSeconds.toString()
        tvBreatheLabel.text = "breathe in"

        startBreathAnimation()
        vibrateSoft()
        startCountdown()
    }

    private fun restartBreathingPhase() {
        cancelCountdown()
        breathAnimator?.cancel()
        layoutDecide.animate().cancel()

        selectedReason = null
        allChips.forEach { chip ->
            chip.setBackgroundResource(R.drawable.bg_chip)
            chip.setTextColor(getColor(R.color.text_secondary))
        }
        layoutDecide.visibility = View.GONE
        layoutBreathe.visibility = View.VISIBLE

        startBreathingPhase()
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

        val attempts = AppPrefs.getAttemptsForPackage(this, targetPackage)
        tvAttemptCount.text = when {
            attempts <= 1 -> "first time today"
            else -> "$attempts times today"
        }

        layoutBreathe.visibility = View.GONE
        layoutDecide.visibility = View.VISIBLE
        layoutDecide.alpha = 0f
        layoutDecide.animate().alpha(1f).setDuration(300).start()

        vibrateSoft()
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
        goHome()
    }
}

package com.Fluent.keyboard

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import com.Fluent.keyboard.ai.Rewriter
import kotlinx.coroutines.launch

/**
 * Onboarding + Gemini setup. Designed to match fluent_ai_home_v2.html exactly:
 * - Two-step onboarding cards with animated badge transitions
 * - API key input with password eye toggle
 * - Bottom toast notifications
 * - "Fluent AI" wordmark with blue "AI" span
 */
class MainActivity : AppCompatActivity() {

    private lateinit var keyInput: EditText
    private lateinit var keyStatus: TextView
    private lateinit var btnSaveKey: TextView
    private lateinit var btnTestKey: TextView
    private lateinit var modelList: LinearLayout
    private lateinit var btnEnable: TextView
    private lateinit var btnSwitch: TextView
    private lateinit var cardStep2: LinearLayout
    private lateinit var numStep1: TextView
    private lateinit var numStep2: TextView
    private lateinit var arrowStep1: ImageView
    private lateinit var arrowStep2: ImageView
    private lateinit var btnEyeToggle: ImageView
    private lateinit var tvWordmark: TextView

    private var keyVisible = false
    private var step1Done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Wire views
        tvWordmark   = findViewById(R.id.tvWordmark)
        btnEnable    = findViewById(R.id.btnEnable)
        btnSwitch    = findViewById(R.id.btnSwitch)
        cardStep2    = findViewById(R.id.cardStep2)
        numStep1     = findViewById(R.id.numStep1)
        numStep2     = findViewById(R.id.numStep2)
        arrowStep1   = findViewById(R.id.arrowStep1)
        arrowStep2   = findViewById(R.id.arrowStep2)
        keyInput     = findViewById(R.id.keyInput)
        keyStatus    = findViewById(R.id.keyStatus)
        btnTestKey   = findViewById(R.id.btnTestKey)
        btnSaveKey   = findViewById(R.id.btnSaveKey)
        btnEyeToggle = findViewById(R.id.btnEyeToggle)
        modelList    = findViewById(R.id.modelList)

        // "Fluent AI" wordmark — "AI" in blue
        applyWordmarkSpan()

        // Step 1 — Enable keyboard
        btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        // Step 2 — Select keyboard (disabled until step 1 completes)
        btnSwitch.setOnClickListener {
            if (!step1Done) return@setOnClickListener
            getSystemService<InputMethodManager>()?.showInputMethodPicker()
            completeStep2()
        }

        // Eye toggle
        btnEyeToggle.setOnClickListener { toggleVisibility() }

        // Save key
        btnSaveKey.setOnClickListener {
            val k = keyInput.text.toString().trim()
            if (k.isEmpty()) { showToast(getString(R.string.key_empty)); return@setOnClickListener }
            Rewriter.setApiKey(this, k)
            showToast(getString(R.string.key_saved))
        }

        // Test key
        btnTestKey.setOnClickListener {
            val k = keyInput.text.toString().trim()
            if (k.isEmpty()) { showToast(getString(R.string.key_empty)); return@setOnClickListener }
            btnTestKey.isEnabled = false
            btnSaveKey.isEnabled = false
            keyStatus.text = getString(R.string.key_testing)
            keyStatus.visibility = View.VISIBLE
            modelList.removeAllViews()
            lifecycleScope.launch {
                val models = Rewriter.listModels(this@MainActivity, k)
                btnTestKey.isEnabled = true
                btnSaveKey.isEnabled = true
                if (models.isEmpty()) {
                    keyStatus.text = getString(R.string.key_fail, Rewriter.lastError() ?: "unknown error")
                    keyStatus.setTextColor(color(R.color.text_hint))
                    return@launch
                }
                Rewriter.setApiKey(this@MainActivity, k)
                keyStatus.text = getString(R.string.key_ok_count, models.size)
                keyStatus.setTextColor(color(R.color.accent_green))
                val saved = Rewriter.getModel(this@MainActivity)
                val chosen = when {
                    models.contains(saved) -> saved
                    models.any { it.contains("flash") } -> models.first { it.contains("flash") }
                    else -> models.first()
                }
                Rewriter.setModel(this@MainActivity, chosen)
                renderModels(models, chosen)
            }
        }

        // Restore key status
        if (Rewriter.hasApiKey(this)) {
            keyInput.setText(Rewriter.getApiKey(this))
            keyStatus.text = getString(R.string.key_present_model, Rewriter.getModel(this))
            keyStatus.setTextColor(color(R.color.accent_green))
            keyStatus.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        // If returning from keyboard settings, check if our IME is enabled and advance step
        if (!step1Done && isImeEnabled()) {
            completeStep1(showToastMsg = false)
        }
    }

    // ─── Step flow ───────────────────────────────────────────────────────────

    private fun completeStep1(showToastMsg: Boolean = true) {
        step1Done = true

        // Mark step 1 done
        numStep1.text = "✓"
        numStep1.background = ContextCompat.getDrawable(this, R.drawable.bg_step_done)
        numStep1.setTextColor(color(R.color.accent_green))

        // Change step 1 button to "done" style
        btnEnable.text = "✓ Enabled"
        btnEnable.setTextColor(color(R.color.accent_blue))
        btnEnable.background = ContextCompat.getDrawable(this, R.drawable.bg_btn_done)
        btnEnable.setOnClickListener(null)
        btnEnable.isClickable = false

        // Fade-in step 2 card
        cardStep2.animate().alpha(1f).setDuration(300).start()

        // Activate step 2 badge and button
        numStep2.setTextColor(color(R.color.accent_blue))
        numStep2.background = ContextCompat.getDrawable(this, R.drawable.bg_step_active)

        btnSwitch.text = getString(R.string.step2_btn_active)
        btnSwitch.setTextColor(color(R.color.text_primary))
        btnSwitch.background = ContextCompat.getDrawable(this, R.drawable.bg_btn_primary)
        btnSwitch.isClickable = true
        btnSwitch.isFocusable = true

        arrowStep1.setColorFilter(color(R.color.accent_blue))
        arrowStep2.alpha = 1f
        arrowStep2.setColorFilter(color(R.color.accent_blue))

        if (showToastMsg) showToast(getString(R.string.toast_step1))
    }

    private fun completeStep2() {
        numStep2.text = "✓"
        numStep2.background = ContextCompat.getDrawable(this, R.drawable.bg_step_done)
        numStep2.setTextColor(color(R.color.accent_green))
        btnSwitch.text = "✓ Selected"
        btnSwitch.setTextColor(color(R.color.accent_blue))
        btnSwitch.background = ContextCompat.getDrawable(this, R.drawable.bg_btn_done)
        btnSwitch.isClickable = false
        showToast(getString(R.string.toast_step2))
    }

    // ─── Eye toggle ──────────────────────────────────────────────────────────

    private fun toggleVisibility() {
        keyVisible = !keyVisible
        keyInput.inputType = if (keyVisible)
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        else
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        // Move cursor to end
        keyInput.setSelection(keyInput.text.length)
        btnEyeToggle.alpha = if (keyVisible) 1f else 0.5f
        btnEyeToggle.setColorFilter(
            if (keyVisible) color(R.color.accent_blue) else Color.TRANSPARENT
        )
    }

    // ─── Toast ───────────────────────────────────────────────────────────────

    private var toastView: TextView? = null
    private val toastHandler = Handler(Looper.getMainLooper())

    private fun showToast(msg: String) {
        // Remove existing toast
        toastView?.let { (it.parent as? ViewGroup)?.removeView(it) }

        val root = window.decorView as FrameLayout
        val tv = TextView(this).apply {
            text = msg
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_toast)
            elevation = dp(8).toFloat()
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(56)
        }
        root.addView(tv, lp)
        toastView = tv

        // Animate in
        tv.alpha = 0f
        tv.translationY = dp(12).toFloat()
        tv.animate().alpha(1f).translationY(0f).setDuration(220).start()

        // Auto dismiss after 2.2s
        toastHandler.removeCallbacksAndMessages(null)
        toastHandler.postDelayed({
            tv.animate().alpha(0f).translationY(dp(12).toFloat()).setDuration(200).withEndAction {
                (tv.parent as? ViewGroup)?.removeView(tv)
                if (toastView === tv) toastView = null
            }.start()
        }, 2200)
    }

    // ─── Wordmark ─────────────────────────────────────────────────────────────

    private fun applyWordmarkSpan() {
        val full = "Fluent AI"
        val span = SpannableString(full)
        val aiStart = full.indexOf("AI")
        span.setSpan(
            ForegroundColorSpan(color(R.color.accent_blue)),
            aiStart, aiStart + 2,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvWordmark.text = span
    }

    // ─── Model list ──────────────────────────────────────────────────────────

    private fun renderModels(models: List<String>, selected: String) {
        modelList.removeAllViews()
        modelList.addView(TextView(this).apply {
            text = getString(R.string.models_label)
            textSize = 13f
            setTextColor(color(R.color.text_hint))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        })
        for (m in models) {
            modelList.addView(TextView(this).apply {
                text = m
                textSize = 14f
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(3), 0, dp(3)) }
                background = if (m == selected)
                    ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_btn_primary)
                else
                    ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_btn_secondary)
                setTextColor(color(R.color.text_primary))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Rewriter.setModel(this@MainActivity, m)
                    keyStatus.text = getString(R.string.model_now, m)
                    renderModels(models, m)
                }
            })
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService<InputMethodManager>() ?: return false
        val pkgName = packageName
        return imm.enabledInputMethodList.any { it.packageName == pkgName }
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

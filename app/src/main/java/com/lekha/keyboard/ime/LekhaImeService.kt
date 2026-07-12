package com.lekha.keyboard.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lekha.keyboard.MainActivity
import com.lekha.keyboard.R
import com.lekha.keyboard.ai.Rewriter
import com.lekha.keyboard.engine.Correction
import com.lekha.keyboard.engine.RuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The Lekha keyboard service. Hosts a custom KeyboardView for typing and a top bar with
 *   - instant offline rule fixes (green suggestion),
 *   - a "Why?" explanation,
 *   - a sparkle for Gemini rewrites (2-3 tappable options, plus "More options").
 * The service owns the InputConnection; the KeyboardView just reports key events.
 */
class LekhaImeService : InputMethodService(), KeyboardView.Listener {

    private val engine = RuleEngine()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var aiJob: Job? = null
    private var aiBusy = false
    private var aiSourceText: String? = null
    private val aiSeen = mutableListOf<String>()

    private lateinit var suggestionBtn: Button
    private lateinit var whyBtn: Button
    private lateinit var aiButton: ImageButton
    private lateinit var explanationTv: TextView
    private lateinit var aiPanel: LinearLayout
    private lateinit var keyboardView: KeyboardView

    private var current: Correction? = null
    private var explanationShown = false
    private var allowProcessing = true

    // ---------------------------------------------------------------- view tree

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setBackgroundColor(color(R.color.kb_background))
        }
        root.addView(buildStrip())

        aiPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setBackgroundColor(color(R.color.kb_background))
            visibility = View.GONE
        }
        root.addView(aiPanel)

        explanationTv = TextView(this).apply {
            setPadding(dp(16), dp(6), dp(16), dp(10))
            textSize = 14f
            setTextColor(color(R.color.kb_key_text))
            setBackgroundColor(color(R.color.kb_strip_background))
            visibility = View.GONE
        }
        root.addView(explanationTv)

        keyboardView = KeyboardView(this).apply {
            listener = this@LekhaImeService
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(keyboardView)

        clearSuggestion()
        return root
    }

    private fun buildStrip(): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(52))
            setBackgroundColor(color(R.color.kb_strip_background))
            gravity = Gravity.CENTER_VERTICAL
        }
        suggestionBtn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
            isAllCaps = false
            textSize = 17f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(16), 0, dp(8), 0)
            setOnClickListener { acceptSuggestion() }
        }
        whyBtn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT)
            isAllCaps = false
            text = getString(R.string.why)
            textSize = 15f
            setOnClickListener { toggleExplanation() }
        }
        aiButton = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), MATCH_PARENT)
            setImageResource(R.drawable.ic_ai)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(color(R.color.kb_accent))
            setBackgroundColor(color(R.color.kb_strip_background))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            contentDescription = getString(R.string.ai_button_desc)
            setOnClickListener { onAiTapped() }
        }
        strip.addView(suggestionBtn)
        strip.addView(whyBtn)
        strip.addView(aiButton)
        return strip
    }

    // ---------------------------------------------------------------- KeyboardView.Listener

    override fun onText(text: String) {
        currentInputConnection?.commitText(text, 1)
        afterInput()
    }

    override fun onBackspace() {
        currentInputConnection?.deleteSurroundingText(1, 0)
        afterInput()
    }

    override fun onDeleteWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(120, 0)?.toString() ?: ""
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1] == ' ') i--
        while (i > 0 && before[i - 1] != ' ' && before[i - 1] != '\n') i--
        val del = before.length - i
        ic.deleteSurroundingText(if (del > 0) del else 1, 0)
        afterInput()
    }

    override fun onEnter() {
        currentInputConnection?.commitText("\n", 1)
        afterInput()
    }

    // ---------------------------------------------------------------- input flow

    private fun afterInput() {
        if (::aiPanel.isInitialized && aiPanel.visibility == View.VISIBLE && !aiBusy) hideAiPanel()
        refreshAutoCap()
        analyze()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        allowProcessing = attribute?.let { isProcessable(it) } ?: true
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::aiPanel.isInitialized) hideAiPanel()
        if (::suggestionBtn.isInitialized) clearSuggestion()
        refreshAutoCap()
        analyze()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun refreshAutoCap() {
        if (!::keyboardView.isInitialized) return
        val before = currentInputConnection?.getTextBeforeCursor(3, 0)?.toString() ?: ""
        val trimmed = before.trimEnd()
        val auto = trimmed.isEmpty() ||
            trimmed.endsWith(".") || trimmed.endsWith("?") ||
            trimmed.endsWith("!") || before.endsWith("\n")
        keyboardView.setAutoCaps(auto)
    }

    private fun analyze() {
        if (!allowProcessing) { clearSuggestion(); return }
        val ic = currentInputConnection ?: return clearSuggestion()
        val after = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
        if (after.isNotEmpty()) { clearSuggestion(); return }
        val before = ic.getTextBeforeCursor(400, 0)?.toString() ?: ""
        val sentence = before.substring(sentenceStart(before))
        val corr = engine.correct(sentence)
        if (corr != null) showSuggestion(corr) else clearSuggestion()
    }

    private fun sentenceStart(before: String): Int {
        var boundary = 0
        for (j in before.indices.reversed()) {
            val c = before[j]
            if (c == '.' || c == '?' || c == '!' || c == '\n') { boundary = j + 1; break }
        }
        var s = boundary
        while (s < before.length && before[s] == ' ') s++
        return s
    }

    private fun acceptSuggestion() {
        val corr = current ?: return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(400, 0)?.toString() ?: return
        val start = sentenceStart(before)
        val replaceLen = before.length - start
        ic.beginBatchEdit()
        if (replaceLen > 0) ic.deleteSurroundingText(replaceLen, 0)
        ic.commitText(corr.corrected, 1)
        ic.endBatchEdit()
        vibrate()
        clearSuggestion()
        refreshAutoCap()
    }

    // ---------------------------------------------------------------- rule strip state

    private fun showSuggestion(corr: Correction) {
        current = corr
        suggestionBtn.text = "\u2713  ${corr.corrected}"
        suggestionBtn.setBackgroundColor(color(R.color.kb_accent))
        suggestionBtn.setTextColor(color(R.color.kb_accent_text))
        whyBtn.visibility = if (corr.explanations.isNotEmpty()) View.VISIBLE else View.INVISIBLE
        if (explanationShown) renderExplanation()
    }

    private fun clearSuggestion() {
        current = null
        explanationShown = false
        suggestionBtn.text = getString(R.string.hint_type)
        suggestionBtn.setBackgroundColor(color(R.color.kb_strip_background))
        suggestionBtn.setTextColor(color(R.color.kb_hint_text))
        whyBtn.visibility = View.INVISIBLE
        explanationTv.visibility = View.GONE
    }

    private fun toggleExplanation() {
        val corr = current ?: return
        if (corr.explanations.isEmpty()) return
        explanationShown = !explanationShown
        if (explanationShown) {
            renderExplanation()
            explanationTv.visibility = View.VISIBLE
        } else {
            explanationTv.visibility = View.GONE
        }
    }

    private fun renderExplanation() {
        val corr = current ?: return
        explanationTv.text = corr.explanations.joinToString("\n") { "\u2022 ${it.whyEn}\n   ${it.whyKn}" }
    }

    // ---------------------------------------------------------------- AI (Gemini) panel

    private fun onAiTapped() {
        if (aiBusy) return
        if (!Rewriter.hasApiKey(this)) {
            showAiMessage(getString(R.string.ai_need_key), showOpenApp = true); return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(2000, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(2000, 0)?.toString() ?: ""
        val full = (before + after).trim()
        if (full.length < 2) { showAiMessage(getString(R.string.ai_type_first), showOpenApp = false); return }

        aiSourceText = full
        aiSeen.clear()
        runRewrite(more = false)
    }

    private fun onAiMore() {
        if (aiBusy || aiSourceText == null) return
        runRewrite(more = true)
    }

    private fun runRewrite(more: Boolean) {
        val src = aiSourceText ?: return
        aiBusy = true
        showAiThinking()
        aiJob = uiScope.launch {
            val options = try {
                if (more) Rewriter.rewriteMore(this@LekhaImeService, src, aiSeen.toList())
                else Rewriter.rewrite(this@LekhaImeService, src)
            } catch (t: Throwable) { emptyList() }
            aiBusy = false
            if (options.isEmpty()) showAiMessage(Rewriter.lastError() ?: getString(R.string.ai_failed), showOpenApp = false)
            else showAiOptions(options)
        }
    }

    private fun showAiThinking() {
        explanationTv.visibility = View.GONE
        aiPanel.removeAllViews()
        aiPanel.addView(TextView(this).apply {
            text = getString(R.string.ai_thinking)
            textSize = 15f
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setTextColor(color(R.color.kb_key_text))
        })
        aiPanel.visibility = View.VISIBLE
    }

    private fun showAiOptions(options: List<String>) {
        explanationTv.visibility = View.GONE
        for (o in options) if (aiSeen.none { it.equals(o, ignoreCase = true) }) aiSeen.add(o)

        aiPanel.removeAllViews()
        aiPanel.addView(TextView(this).apply {
            text = getString(R.string.ai_tap_hint)
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(4))
            setTextColor(color(R.color.kb_hint_text))
        })
        for (opt in options) {
            aiPanel.addView(optionButton(opt).apply { setOnClickListener { applyRewrite(opt) } })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        row.addView(smallButton(getString(R.string.ai_more), color(R.color.kb_special), color(R.color.kb_key_text), 1f) { onAiMore() })
        row.addView(smallButton(getString(R.string.ai_close), color(R.color.kb_special), color(R.color.kb_key_text), 1f) { hideAiPanel() })
        aiPanel.addView(row)
        aiPanel.visibility = View.VISIBLE
    }

    private fun showAiMessage(msg: String, showOpenApp: Boolean) {
        explanationTv.visibility = View.GONE
        aiPanel.removeAllViews()
        aiPanel.addView(TextView(this).apply {
            text = msg
            textSize = 15f
            setSingleLine(false)
            maxLines = 4
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setTextColor(color(R.color.kb_key_text))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        if (showOpenApp) {
            row.addView(smallButton(getString(R.string.ai_open_app), color(R.color.kb_accent), color(R.color.kb_accent_text), 1f) { openApp() })
        }
        row.addView(smallButton(getString(R.string.ai_close), color(R.color.kb_special), color(R.color.kb_key_text), 1f) { hideAiPanel() })
        aiPanel.addView(row)
        aiPanel.visibility = View.VISIBLE
    }

    private fun optionButton(text: String): Button = Button(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .apply { setMargins(dp(6), dp(3), dp(6), dp(3)) }
        isAllCaps = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setSingleLine(false)
        maxLines = 4
        textSize = 16f
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setTextColor(color(R.color.kb_key_text))
        setBackgroundColor(color(R.color.kb_key))
        this.text = text
    }

    private fun smallButton(label: String, bg: Int, fg: Int, weight: Float, onClick: () -> Unit): Button = Button(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, weight)
            .apply { setMargins(dp(6), dp(2), dp(6), dp(6)) }
        isAllCaps = false
        textSize = 14f
        text = label
        setBackgroundColor(bg)
        setTextColor(fg)
        setOnClickListener { onClick() }
    }

    private fun applyRewrite(text: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(4000, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(4000, 0)?.toString() ?: ""
        ic.beginBatchEdit()
        if (before.isNotEmpty() || after.isNotEmpty()) ic.deleteSurroundingText(before.length, after.length)
        ic.commitText(text, 1)
        ic.endBatchEdit()
        hideAiPanel()
        clearSuggestion()
        refreshAutoCap()
        vibrate()
    }

    private fun hideAiPanel() {
        aiJob?.cancel()
        aiBusy = false
        if (::aiPanel.isInitialized) {
            aiPanel.removeAllViews()
            aiPanel.visibility = View.GONE
        }
    }

    private fun openApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- helpers

    private fun isProcessable(info: EditorInfo): Boolean {
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val isPassword =
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        if (isPassword) return false
        if ((info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false
        return true
    }

    private fun vibrate() {
        try {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            }
            v?.vibrate(VibrationEffect.createOneShot(12, 40))
        } catch (_: Exception) { }
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

package com.lekha.keyboard.ime

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
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
 * IME service matching fluent_ai_keyboard_v3.html design:
 *
 *  ┌──────────────────────────────────────────────────┐
 *  │ [word1]  │  [word2]  │  [word3]            [AI⚡] │  ← word strip (44dp)
 *  ├──────────────────────────────────────────────────┤
 *  │  ╔══════════════════════════════════════════╗    │
 *  │  ║   AI panel (collapsible, 0→wrap_content) ║    │  ← AI panel (Gemini options)
 *  │  ╚══════════════════════════════════════════╝    │
 *  ├──────────────────────────────────────────────────┤
 *  │              [explanation row]                    │  ← grammar explanation (optional)
 *  ├──────────────────────────────────────────────────┤
 *  │              [KeyboardView]                       │
 *  └──────────────────────────────────────────────────┘
 *
 * Word strip behaviour:
 *  • The 3 chips show context-based word predictions (same mapping as the HTML)
 *  • When a grammar correction exists, the MIDDLE chip is replaced with the correction
 *    and turns green — tapping it applies the correction (Why? is gone; the chip itself
 *    is the action, keeping things compact). Left and right chips still show predictions.
 *  • Grammar explanation is shown in the optional explanation row when tapping a
 *    "Why?" hidden in the middle chip long-press.
 *
 * AI panel behaviour:
 *  • Tapping the AI key on the keyboard toggles the AI panel open/closed
 *  • When open it shows Gemini rewrite options (+ More / Close row)
 *  • The AI key on the keyboard glows when the panel is open
 */
class LekhaImeService : InputMethodService(), KeyboardView.Listener {

    private val engine = RuleEngine()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var aiJob: Job? = null
    private var aiBusy = false
    private var aiSourceText: String? = null
    private val aiSeen = mutableListOf<String>()

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var wordChip1: TextView
    private lateinit var wordChip2: TextView
    private lateinit var wordChip3: TextView
    private lateinit var aiStripBtn: TextView
    private lateinit var aiPanel: LinearLayout
    private lateinit var explanationTv: TextView
    private lateinit var keyboardView: KeyboardView

    // ── State ─────────────────────────────────────────────────────────────────
    private var current: Correction? = null
    private var allowProcessing = true
    private var panelOpen = false

    // ── Word prediction – frequency-ranked common words ─────────────────────
    // 500 most common English words sorted roughly by frequency.
    // Predictions match the prefix the user is currently typing, just like a
    // normal keyboard (Gboard, SwiftKey, etc.).
    private val wordBank = listOf(
        "the","I","to","and","a","is","it","you","that","of","in","was","for","on","are",
        "but","not","with","he","as","do","at","this","his","by","from","they","be","have",
        "or","one","had","all","we","can","her","was","there","been","if","has","will",
        "no","more","when","who","what","so","up","its","about","into","than","them","could",
        "other","out","some","time","very","your","would","make","like","just","over","such",
        "how","after","know","take","come","many","well","only","also","back","use","two",
        "want","way","look","first","new","day","because","get","people","did","got","made",
        "find","long","here","thing","see","him","going","right","still","own","say","left",
        "should","call","need","too","any","each","tell","help","yes","last","most","never",
        "big","us","old","really","good","great","much","then","same","around","another",
        "think","down","between","work","life","before","being","under","even","our","where",
        "now","go","me","she","my","did","said","does","always","why","off","let","put",
        "again","might","both","every","few","must","those","keep","while","home","start",
        "point","school","hand","high","part","small","end","went","world","next","came",
        "show","place","asked","man","thought","why","something","through","went",
        "year","run","away","live","room","head","set","own","read","far","end",
        "number","change","play","move","try","line","turn","state","late","give",
        "close","open","seem","together","group","often","may","large","old","real",
        "side","enough","almost","water","house","city","above","family","young",
        "leave","area","already","hard","money","table","please","stop","bring","along",
        "sure","okay","thanks","hello","sorry","maybe","yeah","alright","fine","done",
        "tomorrow","today","tonight","morning","afternoon","evening","night","because",
        "though","actually","probably","definitely","already","anything","everything",
        "nothing","someone","everyone","something","always","never","sometimes",
        "about","above","across","after","against","along","among","around","before",
        "behind","below","beneath","beside","between","beyond","during","except",
        "inside","outside","since","through","toward","under","until","upon","within",
        "without","however","therefore","meanwhile","although","whenever","wherever",
        "whoever","whatever","beautiful","important","different","interesting",
        "available","possible","necessary","wonderful","difficult","certainly",
        "happy","love","friend","family","work","school","phone","message","meeting",
        "lunch","dinner","breakfast","coming","going","leaving","looking","waiting",
        "running","talking","working","playing","writing","reading","watching",
        "listening","thinking","feeling","trying","getting","making","taking",
        "saying","asking","telling","knowing","seeing","doing","being","having",
        "I'm","I'll","I've","I'd","don't","can't","won't","isn't","aren't","wasn't",
        "weren't","hasn't","haven't","hadn't","doesn't","didn't","couldn't","shouldn't",
        "wouldn't","that's","it's","what's","there's","here's","let's","he's","she's",
        "we're","they're","you're","we've","they've","you've","we'll","they'll","you'll"
    ).distinct()

    // ── View tree ────────────────────────────────────────────────────────────

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setBackgroundColor(color(R.color.kb_background))
        }

        root.addView(buildWordStrip())

        // AI panel (collapsed by default)
        aiPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0)   // height=0 when collapsed
            setBackgroundColor(color(R.color.bg_pressed))               // #2c2c2e
            // Thin top divider
            val border = View(this@LekhaImeService).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
                setBackgroundColor(color(R.color.divider_light))
            }
            addView(border)
        }
        root.addView(aiPanel)

        // Grammar explanation row (hidden by default)
        explanationTv = TextView(this).apply {
            setPadding(dp(16), dp(6), dp(16), dp(10))
            textSize = 14f
            setTextColor(color(R.color.text_hint))
            setBackgroundColor(color(R.color.kb_strip_background))
            visibility = View.GONE
        }
        root.addView(explanationTv)

        // Keyboard
        keyboardView = KeyboardView(this).apply {
            listener = this@LekhaImeService
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(keyboardView)

        updateWordStrip()
        return root
    }

    // ── Word strip ────────────────────────────────────────────────────────────

    private fun buildWordStrip(): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(44))
            setBackgroundColor(color(R.color.kb_strip_background))
            gravity = Gravity.CENTER_VERTICAL
        }

        fun divider() = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(20)).apply {
                // no extra margin needed
            }
            setBackgroundColor(color(R.color.divider_light))
        }

        wordChip1 = wordChip("sure") { insertWord(0) }
        wordChip2 = wordChip("okay") { onChip2Tapped() }
        wordChip3 = wordChip("thanks") { insertWord(2) }

        strip.addView(wordChip1)
        strip.addView(divider())
        strip.addView(wordChip2)
        strip.addView(divider())
        // Chip3 takes remaining space, AI button docked to end
        wordChip3.layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
        strip.addView(wordChip3)

        // AI button — right side of strip (matching HTML positioning)
        aiStripBtn = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), MATCH_PARENT)
            text = "AI"
            gravity = Gravity.CENTER
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.accent_blue))
            setBackgroundColor(color(R.color.kb_strip_background))
            setPadding(0, 0, 0, 0)
            setOnClickListener { onAiTapped() }
        }
        strip.addView(aiStripBtn)

        return strip
    }

    private fun wordChip(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
            text = label
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(color(R.color.text_primary))
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    /** Chip 2 tap: if there's a grammar correction, apply it; else insert word prediction. */
    private fun onChip2Tapped() {
        if (current != null) {
            acceptSuggestion()
        } else {
            insertWord(1)
        }
    }

    // ── Word predictions ──────────────────────────────────────────────────────

    /** Default suggestions when the cursor is at a word boundary (nothing being typed). */
    private val defaultWords = listOf("I", "the", "and")

    /**
     * Return the partial word the user is currently typing (everything after the
     * last space/newline) and the number of chars to delete to replace it.
     */
    private fun currentPartial(): Pair<String, Int> {
        val before = currentInputConnection
            ?.getTextBeforeCursor(400, 0)?.toString() ?: ""
        if (before.isEmpty()) return "" to 0
        // If the cursor is right after a space, there's no partial word.
        if (before.last() == ' ' || before.last() == '\n') return "" to 0
        val lastSpace = before.lastIndexOfAny(charArrayOf(' ', '\n'))
        val partial = if (lastSpace >= 0) before.substring(lastSpace + 1) else before
        return partial to partial.length
    }

    /**
     * Find up to 3 predictions matching [prefix]. The prefix is matched case-
     * insensitively. Words that exactly equal the prefix are skipped so we only
     * show completions (like a real keyboard).
     */
    private fun predictWords(prefix: String): List<String> {
        if (prefix.isEmpty()) return defaultWords
        val lower = prefix.lowercase()
        return wordBank
            .filter { it.lowercase().startsWith(lower) && !it.equals(prefix, ignoreCase = true) }
            .take(3)
    }

    /** Cache for the currently displayed predictions so insertWord can use them. */
    private var displayedPredictions = defaultWords

    private fun updateWordStrip() {
        val (partial, _) = currentPartial()
        val words = predictWords(partial).let {
            // If no matches, fall back to defaults so the strip is never empty
            if (it.isEmpty()) defaultWords else it
        }
        displayedPredictions = words

        // Grammar correction trumps middle chip
        val corr = current
        if (corr != null) {
            wordChip1.text = words.getOrElse(0) { "" }
            wordChip1.setTextColor(color(R.color.text_primary))

            wordChip2.text = "\u2713 ${corr.corrected}"
            wordChip2.setTextColor(color(R.color.accent_green))

            wordChip3.text = words.getOrElse(if (words.size > 2) 2 else 1) { "" }
            wordChip3.setTextColor(color(R.color.text_primary))
        } else {
            wordChip1.text = words.getOrElse(0) { "" }
            wordChip1.setTextColor(color(R.color.text_primary))
            wordChip2.text = words.getOrElse(1) { "" }
            wordChip2.setTextColor(color(R.color.text_primary))
            wordChip3.text = words.getOrElse(2) { "" }
            wordChip3.setTextColor(color(R.color.text_primary))
            explanationTv.visibility = View.GONE
        }
    }

    private fun insertWord(idx: Int) {
        val ic = currentInputConnection ?: return
        val word = displayedPredictions.getOrNull(idx) ?: return
        val (_, deleteLen) = currentPartial()
        // Delete the partial word the user was typing, then commit the full word + space
        if (deleteLen > 0) ic.deleteSurroundingText(deleteLen, 0)
        ic.commitText("$word ", 1)
        afterInput()
    }

    // ── KeyboardView.Listener ─────────────────────────────────────────────────

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

    override fun onAiToggle() {
        if (panelOpen) hideAiPanel() else onAiTapped()
    }

    override fun onMoreToggle() {
        // Reserved for future feature; currently a no-op
    }

    // ── Input flow ────────────────────────────────────────────────────────────

    private fun afterInput() {
        refreshAutoCap()
        analyze()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        allowProcessing = attribute?.let { isProcessable(it) } ?: true
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        hideAiPanel()
        current = null
        if (::keyboardView.isInitialized) refreshAutoCap()
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
        if (!allowProcessing) { current = null; updateWordStrip(); return }
        val ic = currentInputConnection ?: run { current = null; updateWordStrip(); return }
        val after = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
        if (after.isNotEmpty()) { current = null; updateWordStrip(); return }
        val before = ic.getTextBeforeCursor(400, 0)?.toString() ?: ""
        val sentence = before.substring(sentenceStart(before))
        current = engine.correct(sentence)
        updateWordStrip()
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
        current = null
        explanationTv.visibility = View.GONE
        updateWordStrip()
        refreshAutoCap()
    }

    // ── AI panel ─────────────────────────────────────────────────────────────

    private fun onAiTapped() {
        if (aiBusy) return
        if (!Rewriter.hasApiKey(this)) {
            expandAiPanel()
            showAiMessage(getString(R.string.ai_need_key), showOpenApp = true)
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(2000, 0)?.toString() ?: ""
        val after  = ic.getTextAfterCursor(2000,  0)?.toString() ?: ""
        val full = (before + after).trim()
        if (full.length < 2) {
            expandAiPanel()
            showAiMessage(getString(R.string.ai_type_first), showOpenApp = false)
            return
        }

        aiSourceText = full
        aiSeen.clear()
        expandAiPanel()
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
                else      Rewriter.rewrite(this@LekhaImeService, src)
            } catch (t: Throwable) { emptyList() }
            aiBusy = false
            if (options.isEmpty()) showAiMessage(Rewriter.lastError() ?: getString(R.string.ai_failed), showOpenApp = false)
            else showAiOptions(options)
        }
    }

    // ── AI panel content builders ─────────────────────────────────────────────

    private fun showAiThinking() {
        setPanelContent {
            addView(TextView(this@LekhaImeService).apply {
                text = getString(R.string.ai_thinking)
                textSize = 15f
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setTextColor(color(R.color.text_primary))
            })
        }
    }

    private fun showAiOptions(options: List<String>) {
        for (o in options) if (aiSeen.none { it.equals(o, ignoreCase = true) }) aiSeen.add(o)
        setPanelContent {
            addView(TextView(this@LekhaImeService).apply {
                text = getString(R.string.ai_tap_hint)
                textSize = 13f
                setPadding(dp(14), dp(8), dp(14), dp(4))
                setTextColor(color(R.color.text_hint))
            })
            for (opt in options) {
                addView(aiOptionChip(opt))
            }
            addView(buildAiActionRow())
        }
    }

    private fun showAiMessage(msg: String, showOpenApp: Boolean) {
        setPanelContent {
            addView(TextView(this@LekhaImeService).apply {
                text = msg
                textSize = 15f
                setSingleLine(false)
                maxLines = 4
                setPadding(dp(16), dp(12), dp(16), dp(10))
                setTextColor(color(R.color.text_primary))
            })
            val row = LinearLayout(this@LekhaImeService).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            if (showOpenApp) {
                row.addView(aiSmallBtn(getString(R.string.ai_open_app),
                    color(R.color.accent_blue), color(R.color.text_primary)) { openApp() })
            }
            row.addView(aiSmallBtn(getString(R.string.ai_close),
                color(R.color.kb_key), color(R.color.text_primary)) { hideAiPanel() })
            addView(row)
        }
    }

    private fun aiOptionChip(text: String): TextView = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .apply { setMargins(dp(6), dp(3), dp(6), dp(3)) }
        this.text = text
        textSize = 15f
        setSingleLine(false)
        maxLines = 4
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setTextColor(color(R.color.text_primary))
        setBackgroundColor(color(R.color.kb_key))
        isClickable = true
        isFocusable = true
        setOnClickListener { applyRewrite(text) }
    }

    private fun buildAiActionRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        addView(aiSmallBtn(getString(R.string.ai_more),
            color(R.color.kb_special), color(R.color.text_primary)) { onAiMore() })
        addView(aiSmallBtn(getString(R.string.ai_close),
            color(R.color.kb_special), color(R.color.text_primary)) { hideAiPanel() })
    }

    private fun aiSmallBtn(label: String, bg: Int, fg: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                .apply { setMargins(dp(6), dp(2), dp(6), dp(6)) }
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            setBackgroundColor(bg)
            setTextColor(fg)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    /** Replace all panel content (except the border divider at index 0) */
    private fun setPanelContent(block: LinearLayout.() -> Unit) {
        // Keep the top border (child 0)
        while (aiPanel.childCount > 1) aiPanel.removeViewAt(1)
        aiPanel.block()
    }

    // ── AI panel open/close animation ─────────────────────────────────────────

    private fun expandAiPanel() {
        if (panelOpen) return
        panelOpen = true
        keyboardView.aiPanelOpen = true
        aiStripBtn.setTextColor(color(R.color.accent_blue))

        // Animate height 0 → wrap_content
        aiPanel.visibility = View.VISIBLE
        aiPanel.measure(
            View.MeasureSpec.makeMeasureSpec(aiPanel.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val targetH = aiPanel.measuredHeight.coerceAtLeast(dp(132))

        val lp = aiPanel.layoutParams as LinearLayout.LayoutParams
        ValueAnimator.ofInt(0, targetH).apply {
            duration = 220
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                lp.height = it.animatedValue as Int
                aiPanel.layoutParams = lp
            }
        }.start()
    }

    fun hideAiPanel() {
        if (!panelOpen) return
        panelOpen = false
        if (::keyboardView.isInitialized) keyboardView.aiPanelOpen = false
        aiJob?.cancel()
        aiBusy = false

        val lp = aiPanel.layoutParams as LinearLayout.LayoutParams
        val startH = lp.height
        ValueAnimator.ofInt(startH, 0).apply {
            duration = 180
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                lp.height = it.animatedValue as Int
                aiPanel.layoutParams = lp
                if (lp.height == 0) aiPanel.visibility = View.GONE
            }
        }.start()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun applyRewrite(text: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(4000, 0)?.toString() ?: ""
        val after  = ic.getTextAfterCursor(4000,  0)?.toString() ?: ""
        ic.beginBatchEdit()
        if (before.isNotEmpty() || after.isNotEmpty()) ic.deleteSurroundingText(before.length, after.length)
        ic.commitText(text, 1)
        ic.endBatchEdit()
        hideAiPanel()
        current = null
        updateWordStrip()
        refreshAutoCap()
        vibrate()
    }

    private fun openApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

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

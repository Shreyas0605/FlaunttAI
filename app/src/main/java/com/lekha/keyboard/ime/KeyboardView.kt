package com.lekha.keyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.lekha.keyboard.R

/**
 * Canvas-drawn keyboard matching the fluent_ai_keyboard_v3.html design:
 * - Dark #3a3a3c regular keys, #1c1c1e special keys with subtle border
 * - 8dp border-radius on all keys
 * - Bottom row: ?123 | AI | space | ···more | ↵
 * - AI key toggles the expandable AI panel (communicated via listener)
 * - Shift / CapsLock / Number layer fully functional
 * - Word predictions + grammar correction shown in the strip (handled by LekhaImeService)
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onDeleteWord()
        fun onEnter()
        fun onAiToggle()      // AI key pressed → toggle AI panel
        fun onMoreToggle()    // ··· key pressed (reserved / future)
    }

    var listener: Listener? = null

    private enum class Type {
        CHAR, SHIFT, BACKSPACE, SPACE, ENTER,
        SYMBOLS, LETTERS, SPACER, AI, MORE
    }
    private enum class Shift { OFF, ON, LOCK }
    private enum class Layer { LETTERS, SYMBOLS }

    private class Key(val label: String, val output: String?, val type: Type, val weight: Float) {
        val bounds = RectF()
        /** true = use special (dark #1c1c1e) background, false = regular (#3a3a3c) */
        val isSpecial get() = type != Type.CHAR && type != Type.SPACE
    }

    var aiPanelOpen: Boolean = false
        set(value) { field = value; invalidate() }

    private var shift = Shift.ON
    private var layer = Layer.LETTERS
    private var rows: List<List<Key>> = emptyList()
    private var pressed: Key? = null
    private var lastShiftTap = 0L

    private val density = resources.displayMetrics.density
    private val radius   = 8f  * density   // 8dp — matching HTML's border-radius:8px
    private val inset    = 3f  * density
    private val rectF    = RectF()

    // ── Colors ─────────────────────────────────────────────────────────────────
    private val keyText    = ContextCompat.getColor(context, R.color.kb_key_text)
    private val hintText   = ContextCompat.getColor(context, R.color.kb_hint_text)
    private val accentText = ContextCompat.getColor(context, R.color.kb_accent_text)
    private val accentBlue = ContextCompat.getColor(context, R.color.kb_accent)

    private val keyPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.kb_key) }
    private val specialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.kb_special) }
    private val accentPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.kb_accent) }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555555.toInt() }
    private val textPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** Subtle border for special keys — #555 at 0.5dp, matching HTML's border:0.5px solid #555 */
    private val borderPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.kb_key_border)
        style = Paint.Style.STROKE
        strokeWidth = 0.5f * density
    }

    private val shiftIcon     = AppCompatResources.getDrawable(context, R.drawable.ic_shift)?.mutate()
    private val shiftLockIcon = AppCompatResources.getDrawable(context, R.drawable.ic_capslock)?.mutate()
    private val backspaceIcon = AppCompatResources.getDrawable(context, R.drawable.ic_backspace)?.mutate()
    private val enterIcon     = AppCompatResources.getDrawable(context, R.drawable.ic_enter)?.mutate()

    private fun specialIcon(key: Key) = when (key.type) {
        Type.SHIFT     -> if (shift == Shift.LOCK) shiftLockIcon else shiftIcon
        Type.BACKSPACE -> backspaceIcon
        Type.ENTER     -> enterIcon
        else           -> null
    }

    // ── Repeat-backspace handler ────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private var repeatCount = 0
    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeatCount++
            if (repeatCount > 15) {
                listener?.onDeleteWord()
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                handler.postDelayed(this, 90)
            } else {
                listener?.onBackspace()
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                handler.postDelayed(this, 55)
            }
        }
    }

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.kb_background))
        isHapticFeedbackEnabled = true
        buildRows()
    }

    // ── Public ──────────────────────────────────────────────────────────────────

    fun setAutoCaps(on: Boolean) {
        if (shift == Shift.LOCK) return
        val target = if (on) Shift.ON else Shift.OFF
        if (shift != target) { shift = target; invalidate() }
    }

    // ── Key layouts ─────────────────────────────────────────────────────────────

    private fun k(label: String, out: String? = label, type: Type = Type.CHAR, w: Float = 1f) =
        Key(label, out, type, w)

    private fun lettersLayout(): List<List<Key>> = listOf(
        // Row 1: q w e r t y u i o p
        "qwertyuiop".map { k(it.toString()) },

        // Row 2: [spacer] a s d f g h j k l [spacer]
        listOf(k("", null, Type.SPACER, 0.5f)) +
            "asdfghjkl".map { k(it.toString()) } +
            listOf(k("", null, Type.SPACER, 0.5f)),

        // Row 3: ⇧  z x c v b n m  ⌫
        listOf(k("\u21E7", null, Type.SHIFT, 1.5f)) +
            "zxcvbnm".map { k(it.toString()) } +
            listOf(k("\u232B", null, Type.BACKSPACE, 1.5f)),

        // Row 4: 123 | AI | space | ··· | ↵
        listOf(
            k("123", null, Type.SYMBOLS, 1.3f),
            k("AI",  null, Type.AI,      1.0f),
            k("space", " ", Type.SPACE,  5f),
            k("\u2022\u2022\u2022", null, Type.MORE, 1.0f),
            k("\u21B5", null, Type.ENTER, 1.3f)
        )
    )

    private fun symbolsLayout(): List<List<Key>> = listOf(
        "1234567890".map { k(it.toString()) },
        listOf("@", "#", "\u20B9", "%", "&", "-", "+", "(", ")", "/").map { k(it) },
        listOf(k("", null, Type.SPACER, 1.5f)) +
            listOf("*", "\"", "'", ":", ";", "!", "?").map { k(it) } +
            listOf(k("\u232B", null, Type.BACKSPACE, 1.5f)),
        listOf(
            k("ABC", null, Type.LETTERS, 1.3f),
            k("AI",  null, Type.AI,      1.0f),
            k("space", " ", Type.SPACE,  5f),
            k("\u2022\u2022\u2022", null, Type.MORE, 1.0f),
            k("\u21B5", null, Type.ENTER, 1.3f)
        )
    )

    private fun buildRows() {
        rows = if (layer == Layer.LETTERS) lettersLayout() else symbolsLayout()
        if (width > 0) computeBounds()
        requestLayout()
        invalidate()
    }

    // ── Measure / layout ────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rowH = 56f * density   // 56dp per row ≈ HTML's 44px + 9px gap at 160dpi
        val h = (rowH * rows.size).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeBounds()
    }

    private fun computeBounds() {
        if (rows.isEmpty() || width == 0) return
        val w = width.toFloat()
        val rowH = height.toFloat() / rows.size
        for ((r, row) in rows.withIndex()) {
            var total = 0f
            for (key in row) total += key.weight
            var x = 0f
            val top = r * rowH
            for (key in row) {
                val kw = key.weight / total * w
                key.bounds.set(x, top, x + kw, top + rowH)
                x += kw
            }
        }
    }

    private fun rowHpx() = if (rows.isEmpty()) height.toFloat() else height.toFloat() / rows.size

    // ── Draw ────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (rows.isEmpty()) return
        for (row in rows) for (key in row) {
            if (key.type == Type.SPACER) continue

            val b = key.bounds
            rectF.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)

            val isShiftActive = key.type == Type.SHIFT && shift != Shift.OFF
            val isAiActive    = key.type == Type.AI && aiPanelOpen
            val isCurrentlyPressed = key === pressed

            // Background fill
            val paint = when {
                isShiftActive || isAiActive -> accentPaint
                isCurrentlyPressed          -> pressedPaint
                key.isSpecial               -> specialPaint
                else                        -> keyPaint
            }
            canvas.drawRoundRect(rectF, radius, radius, paint)

            // Border for special keys (matching HTML .key-dark border)
            if (key.isSpecial && !isShiftActive && !isAiActive) {
                canvas.drawRoundRect(rectF, radius, radius, borderPaint)
            }

            // Icon or label
            val icon = specialIcon(key)
            if (icon != null) {
                val size = (rowHpx() * 0.42f).toInt()
                val cx = rectF.centerX().toInt()
                val cy = rectF.centerY().toInt()
                icon.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
                icon.setTint(if (isShiftActive) accentText else keyText)
                icon.draw(canvas)
            } else {
                val label = displayLabel(key)
                val isSubLabel = label.length > 2          // "123", "ABC", "space", "···"
                val isAiLabel  = key.type == Type.AI
                val isMorLabel = key.type == Type.MORE

                textPaint.textSize = when {
                    isAiLabel  -> rowHpx() * 0.26f         // bold 13sp equivalent
                    isMorLabel -> rowHpx() * 0.32f
                    isSubLabel -> rowHpx() * 0.28f
                    isCurrentlyPressed && key.type == Type.CHAR -> rowHpx() * 0.52f
                    else       -> rowHpx() * 0.40f
                }
                textPaint.isFakeBoldText = isAiLabel

                val textColor = when {
                    isShiftActive || isAiActive -> accentText
                    isMorLabel                  -> hintText
                    key.type == Type.AI && !isAiActive -> accentBlue   // AI always blue
                    else                        -> keyText
                }
                textPaint.color = textColor

                // For "···more" style, draw two lines
                if (isMorLabel) {
                    val fm = textPaint.fontMetrics
                    val lineH = fm.descent - fm.ascent
                    // dots line
                    canvas.drawText("\u2022\u2022\u2022",
                        rectF.centerX(),
                        rectF.centerY() - lineH * 0.35f - (fm.ascent + fm.descent) / 2,
                        textPaint)
                    // "more" sub-label
                    textPaint.textSize = rowHpx() * 0.18f
                    textPaint.isFakeBoldText = false
                    textPaint.color = hintText
                    canvas.drawText("more",
                        rectF.centerX(),
                        rectF.centerY() + lineH * 0.65f - (fm.ascent + fm.descent) / 2,
                        textPaint)
                } else {
                    val fm = textPaint.fontMetrics
                    canvas.drawText(label, rectF.centerX(),
                        rectF.centerY() - (fm.ascent + fm.descent) / 2, textPaint)
                }
            }
        }
    }

    private fun displayLabel(key: Key): String = when (key.type) {
        Type.SHIFT -> if (shift == Shift.LOCK) "\u21EA" else "\u21E7"
        Type.SPACE -> "space"
        Type.CHAR  -> {
            val base = key.output ?: key.label
            if (layer == Layer.LETTERS && base.length == 1 && base[0].isLetter() && shift != Shift.OFF)
                base.uppercase() else key.label
        }
        else -> key.label
    }

    // ── Touch ────────────────────────────────────────────────────────────────────

    private fun keyAt(x: Float, y: Float): Key? {
        if (rows.isEmpty()) return null
        val rowH = height.toFloat() / rows.size
        val r = (y / rowH).toInt().coerceIn(0, rows.size - 1)
        val row = rows[r]
        for (key in row) if (x < key.bounds.right) return if (key.type == Type.SPACER) null else key
        val last = row.lastOrNull()
        return if (last == null || last.type == Type.SPACER) null else last
    }

    private fun stopRepeat() {
        handler.removeCallbacks(repeatRunnable)
        repeatCount = 0
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = keyAt(x, y)
                invalidate()
                val p = pressed ?: return true
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (p.type == Type.BACKSPACE) {
                    listener?.onBackspace()
                    stopRepeat()
                    handler.postDelayed(repeatRunnable, 350)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val k = keyAt(x, y)
                if (k !== pressed) {
                    if (pressed?.type == Type.BACKSPACE) stopRepeat()
                    pressed = k
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                stopRepeat()
                val p = pressed
                pressed = null
                invalidate()
                if (p != null && p.type != Type.BACKSPACE) emit(p)
            }
            MotionEvent.ACTION_CANCEL -> {
                stopRepeat(); pressed = null; invalidate()
            }
        }
        return true
    }

    private fun emit(key: Key) {
        when (key.type) {
            Type.CHAR -> {
                val base = key.output ?: key.label
                val out = if (layer == Layer.LETTERS && shift != Shift.OFF) base.uppercase() else base
                listener?.onText(out)
                if (shift == Shift.ON) { shift = Shift.OFF; invalidate() }
            }
            Type.SPACE   -> listener?.onText(" ")
            Type.ENTER   -> listener?.onEnter()
            Type.AI      -> listener?.onAiToggle()
            Type.MORE    -> listener?.onMoreToggle()
            Type.SHIFT   -> {
                val now = System.currentTimeMillis()
                shift = when {
                    now - lastShiftTap < 300 -> Shift.LOCK
                    shift == Shift.OFF       -> Shift.ON
                    else                     -> Shift.OFF
                }
                lastShiftTap = now
                invalidate()
            }
            Type.SYMBOLS -> { layer = Layer.SYMBOLS; buildRows() }
            Type.LETTERS -> { layer = Layer.LETTERS; buildRows() }
            Type.BACKSPACE, Type.SPACER -> { }
        }
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        super.onDetachedFromWindow()
    }
}

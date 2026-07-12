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
 * A single-surface, canvas-drawn keyboard — the way real phone keyboards work.
 * Every touch maps to the nearest key (no dead gaps between keys), keys highlight on
 * press with haptics, backspace repeats on hold (accelerating, then deleting whole
 * words), and a ?123 layer provides numbers and symbols. This fixes the "needs precise
 * touch" and "backspace won't hold" problems of the old button grid.
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onText(text: String)   // an already-cased character/string to commit
        fun onBackspace()
        fun onDeleteWord()
        fun onEnter()
    }

    var listener: Listener? = null

    private enum class Type { CHAR, SHIFT, BACKSPACE, SPACE, ENTER, SYMBOLS, LETTERS, SPACER }
    private enum class Shift { OFF, ON, LOCK }
    private enum class Layer { LETTERS, SYMBOLS }

    private class Key(val label: String, val output: String?, val type: Type, val weight: Float) {
        val bounds = RectF()
    }

    private var shift = Shift.ON
    private var layer = Layer.LETTERS
    private var rows: List<List<Key>> = emptyList()
    private var pressed: Key? = null
    private var lastShiftTap = 0L

    private val density = resources.displayMetrics.density
    private val radius = 7f * density
    private val inset = 3f * density
    private val rectF = RectF()

    private val keyText = ContextCompat.getColor(context, R.color.kb_key_text)
    private val accentText = ContextCompat.getColor(context, R.color.kb_accent_text)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.kb_key) }
    private val specialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.kb_special) }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.kb_accent) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val shiftIcon = AppCompatResources.getDrawable(context, R.drawable.ic_shift)?.mutate()
    private val shiftLockIcon = AppCompatResources.getDrawable(context, R.drawable.ic_capslock)?.mutate()
    private val backspaceIcon = AppCompatResources.getDrawable(context, R.drawable.ic_backspace)?.mutate()
    private val enterIcon = AppCompatResources.getDrawable(context, R.drawable.ic_enter)?.mutate()

    private fun specialIcon(key: Key) = when (key.type) {
        Type.SHIFT -> if (shift == Shift.LOCK) shiftLockIcon else shiftIcon
        Type.BACKSPACE -> backspaceIcon
        Type.ENTER -> enterIcon
        else -> null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var repeatCount = 0
    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeatCount++
            if (repeatCount > 15) {
                listener?.onDeleteWord()
                this@KeyboardView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                handler.postDelayed(this, 90)
            } else {
                listener?.onBackspace()
                this@KeyboardView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                handler.postDelayed(this, 55)
            }
        }
    }

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.kb_background))
        isHapticFeedbackEnabled = true
        buildRows()
    }

    // ---- public control ----

    fun setAutoCaps(on: Boolean) {
        if (shift == Shift.LOCK) return
        val target = if (on) Shift.ON else Shift.OFF
        if (shift != target) { shift = target; invalidate() }
    }

    // ---- layouts ----

    private fun k(label: String, out: String? = label, type: Type = Type.CHAR, w: Float = 1f) =
        Key(label, out, type, w)

    private fun lettersLayout(): List<List<Key>> = listOf(
        "qwertyuiop".map { k(it.toString()) },
        listOf(k("", null, Type.SPACER, 0.5f)) +
            "asdfghjkl".map { k(it.toString()) } +
            listOf(k("", null, Type.SPACER, 0.5f)),
        listOf(k("\u21E7", null, Type.SHIFT, 1.5f)) +
            "zxcvbnm".map { k(it.toString()) } +
            listOf(k("\u232B", null, Type.BACKSPACE, 1.5f)),
        listOf(
            k("?123", null, Type.SYMBOLS, 1.5f),
            k(",", ",", Type.CHAR, 1f),
            k("space", " ", Type.SPACE, 5f),
            k(".", ".", Type.CHAR, 1f),
            k("\u21B5", null, Type.ENTER, 1.5f)
        )
    )

    private fun symbolsLayout(): List<List<Key>> = listOf(
        "1234567890".map { k(it.toString()) },
        listOf("@", "#", "\u20B9", "%", "&", "-", "+", "(", ")", "/").map { k(it) },
        listOf(k("", null, Type.SPACER, 1.5f)) +
            listOf("*", "\"", "'", ":", ";", "!", "?").map { k(it) } +
            listOf(k("\u232B", null, Type.BACKSPACE, 1.5f)),
        listOf(
            k("ABC", null, Type.LETTERS, 1.5f),
            k(",", ",", Type.CHAR, 1f),
            k("space", " ", Type.SPACE, 5f),
            k(".", ".", Type.CHAR, 1f),
            k("\u21B5", null, Type.ENTER, 1.5f)
        )
    )

    private fun buildRows() {
        rows = if (layer == Layer.LETTERS) lettersLayout() else symbolsLayout()
        if (width > 0) computeBounds()
        requestLayout()
        invalidate()
    }

    // ---- measure / layout ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rowH = 58f * density
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

    // ---- draw ----

    override fun onDraw(canvas: Canvas) {
        if (rows.isEmpty()) return
        for (row in rows) for (key in row) {
            if (key.type == Type.SPACER) continue
            val b = key.bounds
            rectF.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
            val active = key === pressed || (key.type == Type.SHIFT && shift != Shift.OFF)
            val paint = when {
                active -> accentPaint
                key.type == Type.CHAR || key.type == Type.SPACE -> keyPaint
                else -> specialPaint
            }
            canvas.drawRoundRect(rectF, radius, radius, paint)

            val icon = specialIcon(key)
            if (icon != null) {
                val size = (rowHpx() * 0.44f).toInt()
                val cx = rectF.centerX().toInt()
                val cy = rectF.centerY().toInt()
                icon.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
                icon.setTint(if (active) accentText else keyText)
                icon.draw(canvas)
            } else {
                val label = displayLabel(key)
                textPaint.textSize = when {
                    label.length > 2 -> rowHpx() * 0.30f
                    key === pressed && key.type == Type.CHAR -> rowHpx() * 0.55f
                    else -> rowHpx() * 0.42f
                }
                textPaint.color = if (active) accentText else keyText
                val fm = textPaint.fontMetrics
                canvas.drawText(label, rectF.centerX(), rectF.centerY() - (fm.ascent + fm.descent) / 2, textPaint)
            }
        }
    }

    private fun displayLabel(key: Key): String = when (key.type) {
        Type.SHIFT -> if (shift == Shift.LOCK) "\u21EA" else "\u21E7"
        Type.SPACE -> "space"
        Type.CHAR -> {
            val base = key.output ?: key.label
            if (layer == Layer.LETTERS && base.length == 1 && base[0].isLetter() && shift != Shift.OFF)
                base.uppercase() else key.label
        }
        else -> key.label
    }

    // ---- touch ----

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
        val x = event.x
        val y = event.y
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
                stopRepeat()
                pressed = null
                invalidate()
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
            Type.SPACE -> listener?.onText(" ")
            Type.ENTER -> listener?.onEnter()
            Type.SHIFT -> {
                val now = System.currentTimeMillis()
                shift = when {
                    now - lastShiftTap < 300 -> Shift.LOCK
                    shift == Shift.OFF -> Shift.ON
                    else -> Shift.OFF
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

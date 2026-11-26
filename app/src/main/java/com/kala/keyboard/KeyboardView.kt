package com.kala.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.PopupWindow
import kotlin.math.max
import kotlin.math.min

enum class KeyType { CHAR, SHIFT, BACK, SPACE, ENTER, MODE, SYMSHIFT, SPACER }

class KeyDef(
    val label: String,
    val type: KeyType = KeyType.CHAR,
    val weight: Float = 1f,
    val alt: String? = null
)

interface KeyboardListener {
    fun onText(text: String)
    fun onBackspace()
    fun onEnter()
}

class KeyboardView(
    context: Context,
    private val settings: KalaSettings,
    private val sound: SoundEngine,
    var listener: KeyboardListener?
) : View(context) {

    private enum class Page { LETTERS, SYM1, SYM2 }
    private enum class ShiftState { OFF, ONCE, LOCK }

    private class KeyRect(
        val def: KeyDef,
        val l: Float, val t: Float, val r: Float, val b: Float
    )

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    var theme: KeyTheme = Themes.byId(settings.themeId)
        set(value) {
            field = value
            buildLedShader()
            invalidate()
        }

    private var page = Page.LETTERS
    private var shiftState = ShiftState.OFF
    private var lastShiftTap = 0L

    private val keyRects = ArrayList<KeyRect>()
    private var rowCount = 4
    private var rowHeight = 0f

    private val padH = dp(3f)
    private val padTop = dp(8f)
    private val padBottom = dp(8f)

    private val handler = Handler(Looper.getMainLooper())
    private val pressedKeys = HashMap<Int, KeyRect>()
    private val longPressTasks = HashMap<Int, Runnable>()
    private val repeatTasks = HashMap<Int, Runnable>()
    private val longHandled = HashSet<Int>()

    // paints
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2f)
    }
    private val ledWide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
        alpha = 55
    }
    private val ledThin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        alpha = 235
    }
    private val rectTop = RectF()
    private val rectSide = RectF()
    private val rectLed = RectF()
    private val path = Path()

    private var ledShader: LinearGradient? = null
    private val ledMatrix = Matrix()
    private var ledPhase = 0f
    private var ledAnimator: ValueAnimator? = null

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    // ---------------------------------------------------------------- layouts

    private fun row(tokens: String, alts: String? = null): List<KeyDef> {
        val t = tokens.split(" ")
        val a = alts?.split(" ")
        return t.mapIndexed { i, s -> KeyDef(s, KeyType.CHAR, 1f, a?.getOrNull(i)) }
    }

    private fun spacer(w: Float) = KeyDef("", KeyType.SPACER, w)

    private fun bottomRow(modeLabel: String): List<KeyDef> = listOf(
        KeyDef(modeLabel, KeyType.MODE, 1.5f),
        KeyDef(",", KeyType.CHAR, 1f, "!"),
        KeyDef("space", KeyType.SPACE, 5f),
        KeyDef(".", KeyType.CHAR, 1f, "?"),
        KeyDef("enter", KeyType.ENTER, 1.5f)
    )

    private fun buildRows(): List<List<KeyDef>> {
        val nums = row("1 2 3 4 5 6 7 8 9 0")
        val rows = ArrayList<List<KeyDef>>()
        when (page) {
            Page.LETTERS -> {
                if (settings.numberRow) rows.add(nums)
                rows.add(row("q w e r t y u i o p", if (settings.numberRow) null else "1 2 3 4 5 6 7 8 9 0"))
                rows.add(listOf(spacer(0.5f)) + row("a s d f g h j k l") + listOf(spacer(0.5f)))
                rows.add(
                    listOf(KeyDef("shift", KeyType.SHIFT, 1.5f)) + row("z x c v b n m") +
                        listOf(KeyDef("del", KeyType.BACK, 1.5f))
                )
                rows.add(bottomRow("?123"))
            }
            Page.SYM1 -> {
                rows.add(nums)
                rows.add(row("@ # $ _ & - + ( ) /"))
                rows.add(
                    listOf(KeyDef("=\\<", KeyType.SYMSHIFT, 1.5f)) + row("* \" ' : ; ! ?") +
                        listOf(KeyDef("del", KeyType.BACK, 1.5f))
                )
                rows.add(bottomRow("ABC"))
            }
            Page.SYM2 -> {
                rows.add(nums)
                rows.add(row("~ ` | { } [ ] < > \\"))
                rows.add(row("£ € ¥ ^ ° = × ÷ ： ；"))
                rows.add(
                    listOf(KeyDef("?123", KeyType.SYMSHIFT, 1.5f)) + row("， 。 ？ ！ 、 「 」") +
                        listOf(KeyDef("del", KeyType.BACK, 1.5f))
                )
                rows.add(bottomRow("ABC"))
            }
        }
        return rows
    }

    private fun computeHeight(): Int {
        val fixedRows = if (settings.numberRow) 5 else 4
        val base = dp(54f) * settings.heightPct / 100f
        return (fixedRows * base + padTop + padBottom).toInt()
    }

    private fun layoutKeys() {
        keyRects.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val rows = buildRows()
        rowCount = rows.size
        rowHeight = (h - padTop - padBottom) / rowCount
        for ((ri, rw) in rows.withIndex()) {
            val sum = rw.map { it.weight }.sum()
            val unit = (w - 2 * padH) / sum
            var x = padH
            val top = padTop + ri * rowHeight
            for (k in rw) {
                val kw = unit * k.weight
                keyRects.add(KeyRect(k, x, top, x + kw, top + rowHeight))
                x += kw
            }
        }
    }

    // ---------------------------------------------------------------- public api

    fun showLetters() {
        page = Page.LETTERS
        layoutKeys()
        invalidate()
    }

    fun showSymbols() {
        page = Page.SYM1
        layoutKeys()
        invalidate()
    }

    /** Called after settings (number row, height, LED, theme...) changed. */
    fun applySettings() {
        theme = Themes.byId(settings.themeId)
        requestLayout()
        layoutKeys()
        updateLed()
        invalidate()
    }

    /** Auto capitalisation at sentence start. */
    fun setAutoShift(on: Boolean) {
        if (on && shiftState == ShiftState.OFF) {
            shiftState = ShiftState.ONCE
            invalidate()
        } else if (!on && shiftState == ShiftState.ONCE) {
            shiftState = ShiftState.OFF
            invalidate()
        }
    }

    // ---------------------------------------------------------------- view plumbing

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var w = MeasureSpec.getSize(widthMeasureSpec)
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || w == 0) {
            w = resources.displayMetrics.widthPixels
        }
        setMeasuredDimension(w, computeHeight())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
        buildLedShader()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateLed()
    }

    override fun onDetachedFromWindow() {
        stopLed()
        cancelAllTouches()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) updateLed() else {
            stopLed()
            cancelAllTouches()
        }
    }

    private fun buildLedShader() {
        if (width <= 0) return
        val base = theme.led
        val colors = base + base[0]
        val shader = LinearGradient(0f, 0f, width.toFloat(), 0f, colors, null, Shader.TileMode.REPEAT)
        ledShader = shader
        ledWide.shader = shader
        ledThin.shader = shader
    }

    private fun updateLed() {
        if (settings.ledOn && isAttachedToWindow && windowVisibility == VISIBLE) {
            if (ledAnimator == null) {
                ledAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 6000
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        ledPhase = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        } else {
            stopLed()
        }
    }

    private fun stopLed() {
        ledAnimator?.cancel()
        ledAnimator = null
    }

    // ---------------------------------------------------------------- drawing

    private fun blend(a: Int, b: Int, f: Float): Int {
        val inv = 1f - f
        return Color.argb(
            255,
            (Color.red(a) * inv + Color.red(b) * f).toInt(),
            (Color.green(a) * inv + Color.green(b) * f).toInt(),
            (Color.blue(a) * inv + Color.blue(b) * f).toInt()
        )
    }

    private fun outputOf(def: KeyDef): String {
        return if (page == Page.LETTERS && shiftState != ShiftState.OFF &&
            def.label.length == 1 && def.label[0].isLetter()
        ) def.label.uppercase() else def.label
    }

    override fun onDraw(canvas: Canvas) {
        val th = theme
        canvas.drawColor(th.bg)
        ledShader?.let {
            ledMatrix.setTranslate(ledPhase * width, 0f)
            it.setLocalMatrix(ledMatrix)
        }
        for (k in keyRects) {
            if (k.def.type == KeyType.SPACER) continue
            drawKey(canvas, k, th)
        }
    }

    private fun drawText(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int) {
        textPaint.color = color
        textPaint.textSize = size
        val fm = textPaint.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    private fun drawKey(c: Canvas, k: KeyRect, th: KeyTheme) {
        val inset = dp(2.2f)
        val l = k.l + inset
        val r = k.r - inset
        val t = k.t + inset
        val b = k.b - inset
        val type = k.def.type
        val pressed = pressedKeys.containsValue(k)
        val depth = dp(3.2f)
        val rad = dp(8f)
        val isEnter = type == KeyType.ENTER
        val isLetterLike = type == KeyType.CHAR || type == KeyType.SPACE
        val shiftActive = type == KeyType.SHIFT && shiftState != ShiftState.OFF

        var top = when {
            isEnter -> th.accent
            isLetterLike -> th.keyTop
            else -> th.funcTop
        }
        if (shiftActive) top = blend(top, th.accent, 0.4f)
        if (pressed) top = blend(top, th.accent, 0.3f)
        val side = if (isEnter) blend(th.accent, Color.BLACK, 0.35f) else th.keySide
        val textColor = when {
            isEnter -> Color.WHITE
            isLetterLike -> th.keyText
            else -> th.funcText
        }

        if (settings.ledOn && ledShader != null) {
            rectLed.set(l - dp(0.5f), t - dp(0.5f), r + dp(0.5f), b + dp(0.5f))
            c.drawRoundRect(rectLed, rad + dp(1f), rad + dp(1f), ledWide)
            c.drawRoundRect(rectLed, rad + dp(1f), rad + dp(1f), ledThin)
        }

        rectSide.set(l, t + depth, r, b)
        fill.color = side
        c.drawRoundRect(rectSide, rad, rad, fill)

        if (pressed) rectTop.set(l, t + depth * 0.8f, r, b - depth * 0.2f)
        else rectTop.set(l, t, r, b - depth)
        fill.color = top
        c.drawRoundRect(rectTop, rad, rad, fill)

        val cx = rectTop.centerX()
        val cy = rectTop.centerY()
        val s = min(rowHeight, rectTop.width()) * 0.17f
        iconPaint.color = textColor

        when (type) {
            KeyType.CHAR -> drawText(c, outputOf(k.def), cx, cy, rowHeight * 0.40f, textColor)
            KeyType.MODE, KeyType.SYMSHIFT -> drawText(c, k.def.label, cx, cy, rowHeight * 0.27f, textColor)
            KeyType.SHIFT -> {
                iconPaint.style = if (shiftActive) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
                path.reset()
                path.moveTo(cx, cy - s)
                path.lineTo(cx + s, cy)
                path.lineTo(cx + s * 0.45f, cy)
                path.lineTo(cx + s * 0.45f, cy + s)
                path.lineTo(cx - s * 0.45f, cy + s)
                path.lineTo(cx - s * 0.45f, cy)
                path.lineTo(cx - s, cy)
                path.close()
                c.drawPath(path, iconPaint)
                if (shiftState == ShiftState.LOCK) {
                    c.drawLine(cx - s * 0.45f, cy + s * 1.35f, cx + s * 0.45f, cy + s * 1.35f, iconPaint)
                }
                iconPaint.style = Paint.Style.STROKE
            }
            KeyType.BACK -> {
                iconPaint.style = Paint.Style.STROKE
                path.reset()
                path.moveTo(cx - s * 1.1f, cy)
                path.lineTo(cx - s * 0.4f, cy - s * 0.75f)
                path.lineTo(cx + s * 1.1f, cy - s * 0.75f)
                path.lineTo(cx + s * 1.1f, cy + s * 0.75f)
                path.lineTo(cx - s * 0.4f, cy + s * 0.75f)
                path.close()
                c.drawPath(path, iconPaint)
                c.drawLine(cx - s * 0.05f, cy - s * 0.3f, cx + s * 0.6f, cy + s * 0.3f, iconPaint)
                c.drawLine(cx - s * 0.05f, cy + s * 0.3f, cx + s * 0.6f, cy - s * 0.3f, iconPaint)
            }
            KeyType.ENTER -> {
                iconPaint.style = Paint.Style.STROKE
                path.reset()
                path.moveTo(cx + s, cy - s * 0.7f)
                path.lineTo(cx + s, cy + s * 0.2f)
                path.lineTo(cx - s, cy + s * 0.2f)
                c.drawPath(path, iconPaint)
                path.reset()
                path.moveTo(cx - s * 0.4f, cy - s * 0.35f)
                path.lineTo(cx - s, cy + s * 0.2f)
                path.lineTo(cx - s * 0.4f, cy + s * 0.75f)
                c.drawPath(path, iconPaint)
            }
            KeyType.SPACE, KeyType.SPACER -> {}
        }

        val alt = k.def.alt
        if (alt != null && type == KeyType.CHAR) {
            drawText(c, alt, rectTop.right - dp(9f), rectTop.top + dp(14f), rowHeight * 0.20f, blend(top, textColor, 0.55f))
        }
    }

    // ---------------------------------------------------------------- touch

    private fun keyAt(x: Float, y: Float): KeyRect? {
        var best: KeyRect? = null
        var bestD = Float.MAX_VALUE
        for (k in keyRects) {
            if (k.def.type == KeyType.SPACER) continue
            val dx = if (x < k.l) k.l - x else if (x > k.r) x - k.r else 0f
            val dy = if (y < k.t) k.t - y else if (y > k.b) y - k.b else 0f
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = k
            }
        }
        return best
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                pressPointer(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) movePointer(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                releasePointer(e.getPointerId(i), true)
            }
            MotionEvent.ACTION_CANCEL -> cancelAllTouches()
        }
        return true
    }

    private fun cancelAllTouches() {
        for (id in pressedKeys.keys.toList()) releasePointer(id, false)
    }

    private fun pressPointer(id: Int, x: Float, y: Float) {
        val k = keyAt(x, y) ?: return
        pressedKeys[id] = k
        longHandled.remove(id)
        onKeyDown(id, k)
        invalidate()
    }

    private fun movePointer(id: Int, x: Float, y: Float) {
        val old = pressedKeys[id] ?: return
        val nk = keyAt(x, y) ?: return
        if (nk !== old) {
            cancelTasks(id)
            longHandled.remove(id)
            pressedKeys[id] = nk
            onKeyDown(id, nk)
            invalidate()
        }
    }

    private fun releasePointer(id: Int, commit: Boolean) {
        val k = pressedKeys.remove(id) ?: return
        cancelTasks(id)
        hidePreview()
        val handled = longHandled.remove(id)
        if (commit && !handled) commitKey(k)
        if (settings.releaseSound) playSound("up")
        invalidate()
    }

    private fun cancelTasks(id: Int) {
        longPressTasks.remove(id)?.let { handler.removeCallbacks(it) }
        repeatTasks.remove(id)?.let { handler.removeCallbacks(it) }
    }

    private fun onKeyDown(id: Int, k: KeyRect) {
        val kind = when (k.def.type) {
            KeyType.SPACE -> "space"
            KeyType.ENTER -> "enter"
            KeyType.BACK -> "back"
            else -> "down"
        }
        playSound(kind)
        vibrate()
        when (k.def.type) {
            KeyType.CHAR -> {
                showPreview(k, outputOf(k.def))
                val alt = k.def.alt
                if (alt != null) {
                    val task = Runnable {
                        val cur = pressedKeys[id]
                        if (cur === k) {
                            listener?.onText(alt)
                            longHandled.add(id)
                            hidePreview()
                            vibrate()
                        }
                    }
                    longPressTasks[id] = task
                    handler.postDelayed(task, 400)
                }
            }
            KeyType.BACK -> {
                hidePreview()
                listener?.onBackspace()
                val task = object : Runnable {
                    var count = 0
                    override fun run() {
                        val cur = pressedKeys[id]
                        if (cur == null || cur.def.type != KeyType.BACK) return
                        listener?.onBackspace()
                        playSound("back")
                        count++
                        handler.postDelayed(this, max(35L, 75L - count * 4L))
                    }
                }
                repeatTasks[id] = task
                handler.postDelayed(task, 400)
            }
            else -> hidePreview()
        }
    }

    private fun commitKey(k: KeyRect) {
        when (k.def.type) {
            KeyType.CHAR -> {
                val isLetter = page == Page.LETTERS && k.def.label.length == 1 && k.def.label[0].isLetter()
                listener?.onText(outputOf(k.def))
                if (isLetter && shiftState == ShiftState.ONCE) shiftState = ShiftState.OFF
            }
            KeyType.SPACE -> {
                listener?.onText(" ")
                if (shiftState == ShiftState.ONCE) shiftState = ShiftState.OFF
            }
            KeyType.ENTER -> listener?.onEnter()
            KeyType.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shiftState = when {
                    shiftState == ShiftState.LOCK -> ShiftState.OFF
                    shiftState == ShiftState.ONCE && now - lastShiftTap < 350 -> ShiftState.LOCK
                    shiftState == ShiftState.ONCE -> ShiftState.OFF
                    else -> ShiftState.ONCE
                }
                lastShiftTap = now
            }
            KeyType.MODE -> {
                page = if (page == Page.LETTERS) Page.SYM1 else Page.LETTERS
                layoutKeys()
            }
            KeyType.SYMSHIFT -> {
                page = if (page == Page.SYM1) Page.SYM2 else Page.SYM1
                layoutKeys()
            }
            KeyType.BACK, KeyType.SPACER -> {}
        }
        invalidate()
    }

    // ---------------------------------------------------------------- feedback

    private fun playSound(kind: String) {
        if (settings.soundOn) sound.play(kind, settings.volume)
    }

    private fun vibrate() {
        if (!settings.vibrateOn) return
        val ms = settings.vibrateMs.coerceIn(3, 60).toLong()
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- key preview bubble

    private inner class PreviewView(ctx: Context) : View(ctx) {
        var text: String = ""
        private val rect = RectF()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val th = theme
            val rad = dp(10f)
            rect.set(dp(2f), dp(2f), width - dp(2f), height - dp(2f))
            p.style = Paint.Style.FILL
            p.color = th.keySide
            c.drawRoundRect(rect, rad, rad, p)
            rect.set(dp(2f), dp(2f), width - dp(2f), height - dp(6f))
            p.color = th.keyTop
            c.drawRoundRect(rect, rad, rad, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(2f)
            p.color = th.accent
            c.drawRoundRect(rect, rad, rad, p)
            textPaint.color = th.keyText
            textPaint.textSize = height * 0.5f
            val fm = textPaint.fontMetrics
            c.drawText(text, width / 2f, rect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
        }
    }

    private var previewPopup: PopupWindow? = null
    private var previewView: PreviewView? = null

    private fun showPreview(k: KeyRect, text: String) {
        if (!settings.popupOn || !isAttachedToWindow) return
        try {
            val pw = ((k.r - k.l) * 1.3f).toInt()
            val ph = (rowHeight * 1.3f).toInt()
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val x = (loc[0] + (k.l + k.r) / 2f - pw / 2f).toInt()
            val y = (loc[1] + k.t - ph + dp(8f)).toInt()
            if (previewPopup == null) {
                val pv = PreviewView(context)
                previewView = pv
                previewPopup = PopupWindow(pv, pw, ph).apply {
                    isClippingEnabled = false
                    isTouchable = false
                    isFocusable = false
                }
            }
            previewView?.text = text
            previewView?.invalidate()
            val pop = previewPopup ?: return
            if (pop.isShowing) {
                pop.update(x, y, pw, ph)
            } else {
                pop.width = pw
                pop.height = ph
                pop.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
            }
        } catch (e: Exception) {
        }
    }

    private fun hidePreview() {
        try {
            previewPopup?.dismiss()
        } catch (e: Exception) {
        }
    }
}

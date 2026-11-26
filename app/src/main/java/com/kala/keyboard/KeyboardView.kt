package com.kala.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
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
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.widget.PopupWindow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class KeyType { CHAR, ZY, SHIFT, BACK, SPACE, ENTER, MODE, SYMSHIFT, LANG, SPACER }

/** [alt] is both the small corner hint and the long-press output. */
class KeyDef(
    val label: String,
    val type: KeyType = KeyType.CHAR,
    val weight: Float = 1f,
    val alt: String? = null
)

interface KeyboardListener {
    fun onText(text: String)
    fun onZhuyin(c: Char)
    fun onSpace()
    fun onBackspace()
    fun onEnter()
    fun onCandidate(index: Int)
    fun onToggleLanguage()
    fun onOpenSettings() {}
    fun onSwitchIme() {}
}

class KeyboardView(
    context: Context,
    private val settings: KalaSettings,
    private val sound: SoundEngine,
    var listener: KeyboardListener?
) : View(context) {

    private enum class Page { LETTERS, SYM1, SYM2 }
    private enum class ShiftState { OFF, ONCE, LOCK }
    private enum class BarMode { CAND, TOOLS, THEMES, SWITCHES, CLIP }

    private class KeyRect(val def: KeyDef, val l: Float, val t: Float, val r: Float, val b: Float)

    private class BarItem(
        val label: String,
        val swatch: IntArray?,
        val selected: Boolean,
        val action: () -> Unit
    )

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    var theme: KeyTheme = Themes.byId(settings.themeId)
        set(value) {
            field = value
            buildLedShader()
            invalidate()
        }

    private var zh = settings.lang == "zh"
    private var page = Page.LETTERS
    private var shiftState = ShiftState.OFF
    private var lastShiftTap = 0L

    private val keyRects = ArrayList<KeyRect>()
    private var rowHeight = 0f

    private val barH = dp(48f)
    private val padH = dp(3f)
    private val padTop = dp(2f)
    private val padBottom = dp(10f)

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val pressedKeys = HashMap<Int, KeyRect>()
    private val longPressTasks = HashMap<Int, Runnable>()
    private val repeatTasks = HashMap<Int, Runnable>()
    private val longHandled = HashSet<Int>()

    // candidate / tool bar
    private var barMode = BarMode.TOOLS
    private var candTexts: List<String> = emptyList()
    private var barItems: List<BarItem> = emptyList()
    private var barWidths = FloatArray(0)
    private var barTotal = 0f
    private var barScroll = 0f
    private var barPointer = -1
    private var barDownX = 0f
    private var barScroll0 = 0f
    private var barMoved = false

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
        alpha = 50
    }
    private val ledThin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        alpha = 220
    }
    private val rectTop = RectF()
    private val rectSide = RectF()
    private val rectLed = RectF()
    private val rectTmp = RectF()
    private val srcRect = Rect()
    private val dstRect = Rect()
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

    // ------------------------------------------------------------------ layouts

    private fun row(tokens: String, alts: String? = null): List<KeyDef> {
        val t = tokens.split(" ")
        val a = alts?.split(" ")
        return t.mapIndexed { i, s -> KeyDef(s, KeyType.CHAR, 1f, a?.getOrNull(i)) }
    }

    private fun zrow(sym: String, hints: String): List<KeyDef> =
        sym.mapIndexed { i, c -> KeyDef(c.toString(), KeyType.ZY, 1f, hints[i].toString()) }

    private fun spacer(w: Float) = KeyDef("", KeyType.SPACER, w)

    private fun comma() = if (zh) KeyDef("，", KeyType.CHAR, 1f, "。") else KeyDef(",", KeyType.CHAR, 1f, "!")
    private fun period() = if (zh) KeyDef("。", KeyType.CHAR, 1f, "？") else KeyDef(".", KeyType.CHAR, 1f, "?")

    private fun bottomRow(modeLabel: String): List<KeyDef> = listOf(
        KeyDef(modeLabel, KeyType.MODE, 1.5f),
        comma(),
        KeyDef("lang", KeyType.LANG, 1f),
        KeyDef("space", KeyType.SPACE, 4f),
        period(),
        KeyDef("enter", KeyType.ENTER, 1.5f)
    )

    private fun buildRows(): List<List<KeyDef>> {
        val nums = row("1 2 3 4 5 6 7 8 9 0")
        val rows = ArrayList<List<KeyDef>>()
        when (page) {
            Page.LETTERS -> if (zh) {
                rows.add(zrow("ㄅㄉˇˋㄓˊ˙ㄚㄞㄢ", "1234567890"))
                rows.add(zrow("ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ", "qwertyuiop"))
                rows.add(zrow("ㄇㄋㄎㄑㄕㄘㄨㄜㄠㄤ", "asdfghjkl;"))
                rows.add(zrow("ㄈㄌㄏㄒㄖㄙㄩㄝㄡㄥ", "zxcvbnm,./"))
                rows.add(
                    listOf(
                        KeyDef("?123", KeyType.MODE, 1.5f),
                        KeyDef("，", KeyType.CHAR, 1f, "。"),
                        KeyDef("lang", KeyType.LANG, 1f),
                        KeyDef("space", KeyType.SPACE, 3.5f),
                        KeyDef("ㄦ", KeyType.ZY, 1f, "-"),
                        KeyDef("del", KeyType.BACK, 1f),
                        KeyDef("enter", KeyType.ENTER, 1.5f)
                    )
                )
            } else {
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
                if (zh) {
                    rows.add(row("， 。 ？ ！ 、 ： ； 「 」 ～"))
                    rows.add(row("@ # $ % & * - + ( )"))
                    rows.add(
                        listOf(KeyDef("=\\<", KeyType.SYMSHIFT, 1.5f)) + row("/ _ = ' \" … ·") +
                            listOf(KeyDef("del", KeyType.BACK, 1.5f))
                    )
                } else {
                    rows.add(row("@ # $ _ & - + ( ) /"))
                    rows.add(
                        listOf(KeyDef("=\\<", KeyType.SYMSHIFT, 1.5f)) + row("* \" ' : ; ! ?") +
                            listOf(KeyDef("del", KeyType.BACK, 1.5f))
                    )
                }
                rows.add(bottomRow("ABC"))
            }
            Page.SYM2 -> {
                rows.add(nums)
                rows.add(row("~ ` | { } [ ] < > \\"))
                rows.add(row("£ € ¥ ^ ° = × ÷ ： ；"))
                rows.add(
                    listOf(KeyDef("?123", KeyType.SYMSHIFT, 1.5f)) + row("『 』 《 》 （ ） ～") +
                        listOf(KeyDef("del", KeyType.BACK, 1.5f))
                )
                rows.add(bottomRow("ABC"))
            }
        }
        return rows
    }

    private fun computeHeight(): Int {
        val rows = buildRows().size
        val base = dp(56f) * settings.heightPct / 100f
        return (barH + rows * base + padTop + padBottom).toInt()
    }

    private fun layoutKeys() {
        keyRects.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val rows = buildRows()
        val top0 = barH + padTop
        rowHeight = (h - top0 - padBottom) / rows.size
        for ((ri, rw) in rows.withIndex()) {
            val sum = rw.map { it.weight }.sum()
            val unit = (w - 2 * padH) / sum
            var x = padH
            val top = top0 + ri * rowHeight
            for (k in rw) {
                val kw = unit * k.weight
                keyRects.add(KeyRect(k, x, top, x + kw, top + rowHeight))
                x += kw
            }
        }
    }

    // ------------------------------------------------------------------ public api

    fun showLetters() {
        page = Page.LETTERS
        relayout()
    }

    fun showSymbols() {
        page = Page.SYM1
        relayout()
    }

    fun setChinese(on: Boolean) {
        zh = on
        page = Page.LETTERS
        shiftState = ShiftState.OFF
        relayout()
        rebuildBar()
    }

    private fun relayout() {
        requestLayout()
        layoutKeys()
        invalidate()
    }

    /** Called after settings (number row, height, LED, theme...) changed. */
    fun applySettings() {
        theme = Themes.byId(settings.themeId)
        zh = settings.lang == "zh"
        relayout()
        updateLed()
        rebuildBar()
    }

    /** Auto capitalisation at sentence start (English only). */
    fun setAutoShift(on: Boolean) {
        if (zh || page != Page.LETTERS) return
        if (on && shiftState == ShiftState.OFF) {
            shiftState = ShiftState.ONCE
            invalidate()
        } else if (!on && shiftState == ShiftState.ONCE) {
            shiftState = ShiftState.OFF
            invalidate()
        }
    }

    fun setCandidates(items: List<String>) {
        candTexts = items
        barMode = BarMode.CAND
        barScroll = 0f
        rebuildBar()
    }

    fun clearCandidates() {
        candTexts = emptyList()
        if (barMode == BarMode.CAND) barMode = BarMode.TOOLS
        barScroll = 0f
        rebuildBar()
    }

    // ------------------------------------------------------------------ view plumbing

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
        rebuildBar()
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

    // ------------------------------------------------------------------ clipboard panel

    private var clipScroll = 0f
    private var clipCards = ArrayList<Pair<RectF, ClipItem>>()
    private var clipDownY = 0f
    private var clipDownX = 0f
    private var clipScroll0 = 0f
    private var clipMoved = false
    private var clipPointer = -1
    private val clipPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)

    private fun openClip() {
        ClipStore.init(context)
        clipScroll = 0f
        setBar(BarMode.CLIP)
    }

    fun resetBar() {
        if (barMode == BarMode.CLIP || barMode == BarMode.THEMES || barMode == BarMode.SWITCHES) {
            barMode = if (candTexts.isNotEmpty()) BarMode.CAND else BarMode.TOOLS
            rebuildBar()
        }
    }

    private fun clipGeometry(): Float {
        val items = ClipStore.all()
        clipCards.clear()
        val gap = dp(8f)
        val cw = (width - gap * 3) / 2f
        val chh = dp(64f)
        val top0 = barH + gap
        for ((i, item) in items.withIndex()) {
            val col = i % 2
            val row = i / 2
            val l = gap + col * (cw + gap)
            val t = top0 + row * (chh + gap) - clipScroll
            clipCards.add(Pair(RectF(l, t, l + cw, t + chh), item))
        }
        val rows = (items.size + 1) / 2
        return rows * (chh + gap) + gap
    }

    private fun drawClip(c: Canvas, th: KeyTheme) {
        val content = clipGeometry()
        val viewH = height - barH
        val maxScroll = max(0f, content - viewH)
        if (clipScroll > maxScroll) { clipScroll = maxScroll; clipGeometry() }
        val textColor = if (th.id == "custom") Color.WHITE else th.keyText
        c.save()
        c.clipRect(0f, barH, width.toFloat(), height.toFloat())
        if (clipCards.isEmpty()) {
            drawText(c, "尚無剪貼內容\n複製文字後會自動出現在這裡".replace("\n", " · "), width / 2f, barH + viewH / 2f, dp(15f), blend(textColor, Color.TRANSPARENT, 0.7f))
        }
        clipPaint.textSize = dp(14f)
        clipPaint.color = textColor
        for ((rc, item) in clipCards) {
            if (rc.bottom < barH || rc.top > height) continue
            fill.color = if (item.pinned) blend(th.keyTop, th.accent, 0.3f) else th.keyTop
            c.drawRoundRect(rc, dp(10f), dp(10f), fill)
            val tw = (rc.width() - dp(44f)).toInt()
            val sl = android.text.StaticLayout.Builder
                .obtain(item.text.replace('\n', ' '), 0, item.text.length.coerceAtMost(200), clipPaint, tw)
                .setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
            c.save()
            c.translate(rc.left + dp(10f), rc.top + dp(8f))
            sl.draw(c)
            c.restore()
            drawText(c, if (item.pinned) "📌" else "📍", rc.right - dp(18f), rc.top + dp(18f), dp(14f), textColor)
            drawText(c, "✕", rc.right - dp(18f), rc.bottom - dp(16f), dp(14f), blend(textColor, Color.TRANSPARENT, 0.6f))
        }
        c.restore()
    }

    private fun clipTap(x: Float, y: Float) {
        for ((rc, item) in clipCards) {
            if (!rc.contains(x, y)) continue
            vibrate()
            if (x > rc.right - dp(36f) && y < rc.top + dp(32f)) {
                ClipStore.togglePin(item)
            } else if (x > rc.right - dp(36f) && y > rc.bottom - dp(32f)) {
                ClipStore.remove(item)
            } else {
                listener?.onText(item.text)
                setBar(if (candTexts.isNotEmpty()) BarMode.CAND else BarMode.TOOLS)
                return
            }
            invalidate()
            return
        }
    }

    // ------------------------------------------------------------------ bar model

    private fun leftButtonLabel(): String? = when (barMode) {
        BarMode.CAND -> "☰"
        BarMode.TOOLS -> null
        else -> "‹"
    }

    private fun leftButtonWidth(): Float = if (leftButtonLabel() != null) dp(46f) else 0f

    private fun rebuildBar() {
        val items = ArrayList<BarItem>()
        when (barMode) {
            BarMode.CAND -> candTexts.forEachIndexed { i, s ->
                items.add(BarItem(s, null, false) { listener?.onCandidate(i) })
            }
            BarMode.CLIP -> {
                items.add(BarItem("🗑 清除未釘選", null, false) {
                    ClipStore.clearUnpinned()
                    clipScroll = 0f
                    invalidate()
                })
            }
            BarMode.TOOLS -> {
                items.add(BarItem("📋 剪貼簿", null, false) { openClip() })
                items.add(BarItem("🎨 主題", null, false) { setBar(BarMode.THEMES) })
                items.add(BarItem("⌨ 軸體", null, false) { setBar(BarMode.SWITCHES) })
                items.add(BarItem(if (settings.soundOn) "🔊 音效開" else "🔇 音效關", null, false) {
                    settings.soundOn = !settings.soundOn
                    rebuildBar()
                })
                items.add(BarItem("⚙ 設定", null, false) { listener?.onOpenSettings() })
                items.add(BarItem("🌐 輸入法", null, false) { listener?.onSwitchIme() })
            }
            BarMode.THEMES -> for (th in Themes.list(context)) {
                items.add(BarItem(th.name, intArrayOf(th.keyTop, th.accent, th.led[0]), th.id == settings.themeId) {
                    settings.themeId = th.id
                    theme = th
                    relayout()
                    rebuildBar()
                })
            }
            BarMode.SWITCHES -> for (sw in Switches.all) {
                items.add(BarItem(sw.name, intArrayOf(sw.color), sw.id == settings.switchId) {
                    settings.switchId = sw.id
                    sound.load(sw.id)
                    handler.postDelayed({ sound.play("down", settings.volume) }, 160)
                    handler.postDelayed({ sound.play("up", settings.volume) }, 330)
                    rebuildBar()
                })
            }
        }
        barItems = items
        measureBar()
        invalidate()
    }

    private fun setBar(mode: BarMode) {
        barMode = mode
        barScroll = 0f
        rebuildBar()
    }

    private fun measureBar() {
        val cand = barMode == BarMode.CAND
        textPaint.textSize = if (cand) dp(21f) else dp(15f)
        barWidths = FloatArray(barItems.size)
        var total = 0f
        for (i in barItems.indices) {
            val it = barItems[i]
            val tw = textPaint.measureText(it.label)
            var w = if (cand) max(dp(48f), tw + dp(30f)) else tw + dp(30f)
            val sw = it.swatch
            if (sw != null) w += sw.size * dp(11f) + dp(4f)
            barWidths[i] = w
            total += w
        }
        barTotal = total
        clampScroll()
    }

    private fun clampScroll() {
        val maxScroll = max(0f, barTotal + leftButtonWidth() + dp(8f) - width)
        barScroll = barScroll.coerceIn(0f, maxScroll)
    }

    // ------------------------------------------------------------------ drawing

    private fun blend(a: Int, b: Int, f: Float): Int {
        val inv = 1f - f
        return Color.argb(
            (Color.alpha(a) * inv + Color.alpha(b) * f).toInt(),
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
        drawBackground(canvas, th)
        ledShader?.let {
            ledMatrix.setTranslate(ledPhase * width, 0f)
            it.setLocalMatrix(ledMatrix)
        }
        if (barMode == BarMode.CLIP) {
            drawClip(canvas, th)
            drawBar(canvas, th)
            return
        }
        drawBar(canvas, th)
        for (k in keyRects) {
            if (k.def.type == KeyType.SPACER) continue
            drawKey(canvas, k, th)
        }
    }

    private fun drawBackground(c: Canvas, th: KeyTheme) {
        if (th.id == "custom") {
            val bmp = BgImage.load(context)
            if (bmp != null) {
                val vw = width.toFloat()
                val vh = height.toFloat()
                val scale = max(vw / bmp.width, vh / bmp.height)
                val sw = (vw / scale).toInt()
                val sh = (vh / scale).toInt()
                val sx = (bmp.width - sw) / 2
                val sy = (bmp.height - sh) / 2
                srcRect.set(sx, sy, sx + sw, sy + sh)
                dstRect.set(0, 0, width, height)
                c.drawColor(Color.BLACK)
                c.drawBitmap(bmp, srcRect, dstRect, null)
                c.drawColor(Color.argb(70, 0, 0, 0))
                return
            }
        }
        c.drawColor(th.bg)
    }

    private fun drawText(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int) {
        textPaint.color = color
        textPaint.textSize = size
        val fm = textPaint.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    private fun drawBar(c: Canvas, th: KeyTheme) {
        val cand = barMode == BarMode.CAND
        val left = leftButtonWidth()
        val textColor = if (th.id == "custom") Color.WHITE else th.keyText
        c.save()
        c.clipRect(left, 0f, width.toFloat(), barH)
        var x = left + dp(4f) - barScroll
        for (i in barItems.indices) {
            val w = barWidths[i]
            if (x + w > left && x < width) {
                val it = barItems[i]
                if (cand) {
                    val color = if (i == 0 && barItems.size > 1) th.accent else textColor
                    drawText(c, it.label, x + w / 2f, barH / 2f, dp(21f), color)
                    fill.color = blend(textColor, Color.TRANSPARENT, 0.8f)
                    c.drawRect(x + w - dp(0.5f), barH * 0.3f, x + w, barH * 0.7f, fill)
                } else {
                    rectTmp.set(x + dp(3f), dp(7f), x + w - dp(3f), barH - dp(7f))
                    fill.color = if (it.selected) blend(th.funcTop, th.accent, 0.4f) else th.funcTop
                    c.drawRoundRect(rectTmp, dp(16f), dp(16f), fill)
                    var tx = rectTmp.left + dp(14f)
                    val sw = it.swatch
                    if (sw != null) {
                        for (col in sw) {
                            fill.color = col
                            c.drawCircle(tx + dp(4.5f), barH / 2f, dp(4.5f), fill)
                            tx += dp(11f)
                        }
                        tx += dp(4f)
                    }
                    textPaint.textAlign = Paint.Align.LEFT
                    val fm = textPaint.fontMetrics
                    textPaint.color = th.funcText
                    textPaint.textSize = dp(15f)
                    c.drawText(it.label, tx, barH / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
                    textPaint.textAlign = Paint.Align.CENTER
                }
            }
            x += w
        }
        c.restore()
        val lb = leftButtonLabel()
        if (lb != null) {
            drawText(c, lb, left / 2f, barH / 2f, dp(22f), textColor)
        }
        fill.color = blend(textColor, Color.TRANSPARENT, 0.85f)
        c.drawRect(0f, barH - dp(0.5f), width.toFloat(), barH, fill)
    }

    private fun drawKey(c: Canvas, k: KeyRect, th: KeyTheme) {
        val insetX = dp(settings.keyGap * 0.5f)
        val insetY = dp(settings.keyGap * 0.6f)
        val l = k.l + insetX
        val r = k.r - insetX
        val t = k.t + insetY
        val b = k.b - insetY
        val type = k.def.type
        val pressed = pressedKeys.containsValue(k)
        val depth = dp(2.6f)
        val rad = dp(9f)
        val isEnter = type == KeyType.ENTER
        val isLetterLike = type == KeyType.CHAR || type == KeyType.ZY || type == KeyType.SPACE
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
            KeyType.ZY -> {
                val tone = "ˊˇˋ˙".indexOf(k.def.label[0]) >= 0
                drawText(c, k.def.label, cx, cy + (if (tone) -rowHeight * 0.02f else 0f), rowHeight * (if (tone) 0.5f else 0.44f), textColor)
            }
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
            KeyType.LANG -> {
                iconPaint.style = Paint.Style.STROKE
                c.drawCircle(cx, cy, s * 1.05f, iconPaint)
                rectTmp.set(cx - s * 0.5f, cy - s * 1.05f, cx + s * 0.5f, cy + s * 1.05f)
                c.drawOval(rectTmp, iconPaint)
                c.drawLine(cx - s * 1.05f, cy, cx + s * 1.05f, cy, iconPaint)
            }
            KeyType.SPACE -> {
                drawText(c, if (zh) "注音" else "English", cx, cy, rowHeight * 0.26f, blend(top, textColor, 0.75f))
            }
            KeyType.SPACER -> {}
        }

        val alt = k.def.alt
        if (alt != null && (type == KeyType.CHAR || type == KeyType.ZY)) {
            drawText(c, alt, rectTop.right - dp(9f), rectTop.top + dp(14f), rowHeight * 0.20f, blend(top, textColor, 0.55f))
        }
    }

    // ------------------------------------------------------------------ touch

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
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                if (y < barH) {
                    if (barPointer == -1) {
                        barPointer = id
                        barDownX = x
                        barScroll0 = barScroll
                        barMoved = false
                    }
                } else if (barMode == BarMode.CLIP) {
                    if (clipPointer == -1) {
                        clipPointer = id
                        clipDownX = x
                        clipDownY = y
                        clipScroll0 = clipScroll
                        clipMoved = false
                    }
                } else {
                    pressPointer(id, x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    if (id == barPointer) {
                        val dx = e.getX(i) - barDownX
                        if (abs(dx) > touchSlop) barMoved = true
                        if (barMoved) {
                            barScroll = barScroll0 - dx
                            clampScroll()
                            invalidate()
                        }
                    } else if (id == clipPointer) {
                        val dy = e.getY(i) - clipDownY
                        if (abs(dy) > touchSlop) clipMoved = true
                        if (clipMoved) {
                            clipScroll = max(0f, clipScroll0 - dy)
                            invalidate()
                        }
                    } else {
                        movePointer(id, e.getX(i), e.getY(i))
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                if (id == barPointer) {
                    if (!barMoved) barTap(e.getX(i))
                    barPointer = -1
                } else if (id == clipPointer) {
                    if (!clipMoved) clipTap(e.getX(i), e.getY(i))
                    clipPointer = -1
                } else {
                    releasePointer(id, true)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                barPointer = -1
                clipPointer = -1
                cancelAllTouches()
            }
        }
        return true
    }

    private fun barTap(x: Float) {
        val left = leftButtonWidth()
        if (x < left) {
            vibrate()
            when (barMode) {
                BarMode.CAND -> setBar(BarMode.TOOLS)
                else -> setBar(if (candTexts.isNotEmpty()) BarMode.CAND else BarMode.TOOLS)
            }
            return
        }
        var px = left + dp(4f) - barScroll
        for (i in barItems.indices) {
            val w = barWidths[i]
            if (x >= px && x < px + w) {
                vibrate()
                barItems[i].action()
                return
            }
            px += w
        }
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
        val alt = k.def.alt
        when (k.def.type) {
            KeyType.CHAR, KeyType.ZY -> {
                showPreview(k, outputOf(k.def))
                if (alt != null) {
                    val task = Runnable {
                        if (pressedKeys[id] === k) {
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
            KeyType.LANG -> {
                hidePreview()
                val task = Runnable {
                    if (pressedKeys[id] === k) {
                        listener?.onSwitchIme()
                        longHandled.add(id)
                        vibrate()
                    }
                }
                longPressTasks[id] = task
                handler.postDelayed(task, 450)
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
                val isLetter = page == Page.LETTERS && !zh && k.def.label.length == 1 && k.def.label[0].isLetter()
                listener?.onText(outputOf(k.def))
                if (isLetter && shiftState == ShiftState.ONCE) shiftState = ShiftState.OFF
            }
            KeyType.ZY -> listener?.onZhuyin(k.def.label[0])
            KeyType.SPACE -> {
                listener?.onSpace()
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
                relayout()
            }
            KeyType.SYMSHIFT -> {
                page = if (page == Page.SYM1) Page.SYM2 else Page.SYM1
                relayout()
            }
            KeyType.LANG -> listener?.onToggleLanguage()
            KeyType.BACK, KeyType.SPACER -> {}
        }
        invalidate()
    }

    // ------------------------------------------------------------------ feedback

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

    // ------------------------------------------------------------------ key preview bubble

    private inner class PreviewView(ctx: Context) : View(ctx) {
        var text: String = ""
        private val rect = RectF()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        override fun onDraw(c: Canvas) {
            val th = theme
            val rad = dp(10f)
            val face = if (Color.alpha(th.keyTop) < 255) blend(th.keyTop, Color.WHITE, 0.6f) else th.keyTop
            rect.set(dp(2f), dp(2f), width - dp(2f), height - dp(2f))
            p.style = Paint.Style.FILL
            p.color = if (Color.alpha(th.keySide) < 255) Color.DKGRAY else th.keySide
            c.drawRoundRect(rect, rad, rad, p)
            rect.set(dp(2f), dp(2f), width - dp(2f), height - dp(6f))
            p.color = face
            c.drawRoundRect(rect, rad, rad, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(2f)
            p.color = th.accent
            c.drawRoundRect(rect, rad, rad, p)
            tp.color = th.keyText
            tp.textSize = height * 0.5f
            val fm = tp.fontMetrics
            c.drawText(text, width / 2f, rect.centerY() - (fm.ascent + fm.descent) / 2f, tp)
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

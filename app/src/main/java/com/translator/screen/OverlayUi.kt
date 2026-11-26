package com.translator.screen

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

interface OverlayCallbacks {
    fun onPairChanged()
    fun onCloseConfirmed()
    fun onTranslateInput(text: String, done: (String?, String?) -> Unit)
    fun replyTarget(): Lang
}

class OverlayUi(
    private val svc: AccessibilityService,
    private val prefs: Prefs,
    private val cb: OverlayCallbacks
) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val imm = svc.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val density = svc.resources.displayMetrics.density

    private val cBg = 0xF2111827.toInt()
    private val cChip = 0x33FFFFFF
    private val cRed = 0xCCDC2626.toInt()
    private val cGray = 0xFF9CA3AF.toInt()
    private val cWarn = 0xFFFFB4B4.toInt()

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun roundBg(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun chip(label: String): TextView {
        val tv = TextView(svc)
        tv.text = label
        tv.setTextColor(Color.WHITE)
        tv.textSize = 13f
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(10), dp(5), dp(10), dp(5))
        tv.background = roundBg(cChip, 10)
        tv.isClickable = true
        return tv
    }

    private var inputOpen = false
    private var shown = false
    private var closing = false
    private var remain = 0
    private var dropdownForMine = true

    private val root: LinearLayout = object : LinearLayout(svc) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && inputOpen) {
                if (event.action == KeyEvent.ACTION_UP) toggleInput(false)
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private val resultScroll = MaxHeightScrollView(svc, (svc.resources.displayMetrics.heightPixels * 0.17f).toInt())
    private val resultList = LinearLayout(svc)
    private val tvStatus = TextView(svc)
    private val controls = LinearLayout(svc)
    private val btnMine = chip("")
    private val btnForeign = chip("")
    private val btnClose = chip("✕ 關閉")
    private val btnInput = chip("⌨")
    private val dropdown = LinearLayout(svc)
    private val inputPanel = LinearLayout(svc)
    private val et = EditText(svc)
    private val tvTarget = TextView(svc)
    private val btnGo = chip("翻譯")
    private val resultRow = LinearLayout(svc)
    private val tvResult = TextView(svc)
    private val btnCopy = chip("複製")

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    )

    private val tick = object : Runnable {
        override fun run() {
            remain--
            if (remain <= 0) {
                closing = false
                cb.onCloseConfirmed()
            } else {
                btnClose.text = "取消 $remain"
                root.postDelayed(this, 1000)
            }
        }
    }

    init {
        lp.gravity = Gravity.TOP

        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(8), dp(4), dp(8), dp(6))
        val bg = GradientDrawable()
        bg.setColor(cBg)
        val r = dp(16).toFloat()
        bg.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        root.background = bg

        // 翻譯結果區
        resultList.orientation = LinearLayout.VERTICAL
        resultScroll.addView(resultList)
        root.addView(resultScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        tvStatus.textSize = 11f
        tvStatus.maxLines = 8
        tvStatus.setTextColor(cWarn)
        tvStatus.visibility = View.GONE
        tvStatus.setPadding(0, dp(4), 0, 0)
        root.addView(tvStatus)

        // 控制列
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER_VERTICAL
        val cp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        cp.topMargin = dp(4)
        root.addView(controls, cp)

        val arrow = TextView(svc)
        arrow.text = ">"
        arrow.setTextColor(Color.WHITE)
        arrow.textSize = 15f
        arrow.setPadding(dp(8), 0, dp(8), 0)

        controls.addView(btnMine)
        controls.addView(arrow)
        controls.addView(btnForeign)
        controls.addView(View(svc), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(btnClose)
        val ip = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        ip.leftMargin = dp(8)
        controls.addView(btnInput, ip)

        btnMine.setOnClickListener { showDropdown(true) }
        btnForeign.setOnClickListener { showDropdown(false) }
        btnClose.setOnClickListener { onCloseClick() }
        btnInput.setOnClickListener { toggleInput(!inputOpen) }

        // 下拉選單
        dropdown.orientation = LinearLayout.VERTICAL
        dropdown.visibility = View.GONE
        val dp1 = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        dp1.topMargin = dp(6)
        root.addView(dropdown, dp1)

        // 輸入翻譯面板
        inputPanel.orientation = LinearLayout.VERTICAL
        inputPanel.visibility = View.GONE
        val ipp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        ipp.topMargin = dp(8)
        root.addView(inputPanel, ipp)

        et.setTextColor(Color.WHITE)
        et.setHintTextColor(cGray)
        et.hint = "輸入繁體中文…"
        et.textSize = 16f
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        et.minLines = 1
        et.maxLines = 4
        et.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_SEND
        et.setPadding(dp(12), dp(8), dp(12), dp(8))
        et.background = roundBg(0x22FFFFFF, 10)
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                go()
                true
            } else {
                false
            }
        }
        inputPanel.addView(et, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val row2 = LinearLayout(svc)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.gravity = Gravity.CENTER_VERTICAL
        tvTarget.setTextColor(cGray)
        tvTarget.textSize = 14f
        row2.addView(tvTarget, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(btnGo)
        val r2p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        r2p.topMargin = dp(6)
        inputPanel.addView(row2, r2p)
        btnGo.setOnClickListener { go() }

        resultRow.orientation = LinearLayout.HORIZONTAL
        resultRow.gravity = Gravity.CENTER_VERTICAL
        resultRow.visibility = View.GONE
        resultRow.setPadding(dp(12), dp(8), dp(8), dp(8))
        resultRow.background = roundBg(0x22FFFFFF, 10)
        tvResult.setTextColor(Color.WHITE)
        tvResult.textSize = 16f
        tvResult.setTextIsSelectable(true)
        resultRow.addView(tvResult, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val cpp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        cpp.leftMargin = dp(8)
        resultRow.addView(btnCopy, cpp)
        val rrp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        rrp.topMargin = dp(6)
        inputPanel.addView(resultRow, rrp)
        btnCopy.setOnClickListener {
            val s = tvResult.text.toString()
            if (s.isNotEmpty()) {
                val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("translation", s))
                Toast.makeText(svc, "已複製", Toast.LENGTH_SHORT).show()
            }
        }

        refreshButtons()
        setLines(emptyList())
    }

    // ---- 公開 ----

    fun show() {
        if (shown) return
        try {
            wm.addView(root, lp)
            shown = true
        } catch (e: Exception) {
            // ignore
        }
    }

    fun hide() {
        root.removeCallbacks(tick)
        try {
            imm.hideSoftInputFromWindow(et.windowToken, 0)
        } catch (e: Exception) {
            // ignore
        }
        if (shown) {
            try {
                wm.removeView(root)
            } catch (e: Exception) {
                // ignore
            }
            shown = false
        }
    }

    fun setStatus(msg: String?) {
        if (msg == null || msg.isEmpty()) {
            tvStatus.visibility = View.GONE
        } else {
            tvStatus.text = msg
            tvStatus.visibility = View.VISIBLE
        }
    }

    /** null 代表翻譯中 */
    fun setLines(lines: List<String?>) {
        val oldY = resultScroll.scrollY
        val atBottom = oldY + resultScroll.height >= resultList.height - dp(24)
        resultList.removeAllViews()
        if (lines.isEmpty()) {
            val tv = TextView(svc)
            tv.text = "等待畫面上的${prefs.foreign.label}…"
            tv.setTextColor(cGray)
            tv.textSize = 12f
            resultList.addView(tv)
        } else {
            for (line in lines) {
                val tv = TextView(svc)
                tv.text = line ?: "⋯"
                tv.setTextColor(if (line == null) cGray else Color.WHITE)
                tv.textSize = 14f
                tv.setPadding(0, dp(2), 0, dp(2))
                resultList.addView(tv)
            }
        }
        resultScroll.post {
            if (atBottom) resultScroll.fullScroll(View.FOCUS_DOWN) else resultScroll.scrollTo(0, oldY)
        }
    }

    // ---- 內部 ----

    private fun refreshButtons() {
        btnMine.text = "${prefs.mine.short} ▾"
        btnForeign.text = "${prefs.foreign.short} ▾"
        tvTarget.text = "→ ${cb.replyTarget().label}"
    }

    private fun showDropdown(forMine: Boolean) {
        if (dropdown.visibility == View.VISIBLE && dropdownForMine == forMine) {
            dropdown.visibility = View.GONE
            return
        }
        dropdownForMine = forMine
        dropdown.removeAllViews()
        val current = if (forMine) prefs.mine else prefs.foreign
        for (l in Lang.values()) {
            if (l == current) continue
            val item = chip("${l.short}  ${l.label}")
            item.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            item.setOnClickListener { choose(forMine, l) }
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            p.topMargin = dp(4)
            dropdown.addView(item, p)
        }
        dropdown.visibility = View.VISIBLE
    }

    private fun choose(forMine: Boolean, l: Lang) {
        if (forMine) {
            val old = prefs.mine
            if (l == prefs.foreign) prefs.foreign = old
            prefs.mine = l
        } else {
            val old = prefs.foreign
            if (l == prefs.mine) prefs.mine = old
            prefs.foreign = l
        }
        dropdown.visibility = View.GONE
        refreshButtons()
        cb.onPairChanged()
    }

    private fun onCloseClick() {
        if (closing) {
            closing = false
            root.removeCallbacks(tick)
            btnClose.text = "✕ 關閉"
            btnClose.background = roundBg(cChip, 10)
        } else {
            closing = true
            remain = 5
            btnClose.text = "取消 $remain"
            btnClose.background = roundBg(cRed, 10)
            root.postDelayed(tick, 1000)
        }
    }

    private fun setFocusable(f: Boolean) {
        lp.flags = if (f) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (shown) {
            try {
                wm.updateViewLayout(root, lp)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun toggleInput(show: Boolean) {
        inputOpen = show
        if (show) {
            tvTarget.text = "→ ${cb.replyTarget().label}"
            inputPanel.visibility = View.VISIBLE
            setFocusable(true)
            et.requestFocus()
            root.postDelayed({ imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT) }, 250)
            root.postDelayed({ if (inputOpen) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT) }, 700)
        } else {
            imm.hideSoftInputFromWindow(et.windowToken, 0)
            et.setText("")
            tvResult.text = ""
            resultRow.visibility = View.GONE
            inputPanel.visibility = View.GONE
            setFocusable(false)
        }
    }

    private fun go() {
        val t = et.text.toString().trim()
        if (t.isEmpty()) return
        tvResult.text = "翻譯中…"
        resultRow.visibility = View.VISIBLE
        cb.onTranslateInput(t) { res, err ->
            if (inputOpen) {
                tvResult.text = res ?: ("⚠ " + (err ?: "翻譯失敗"))
            }
        }
    }
}

/** 最高不超過 maxH，內容少時只佔需要的高度 */
class MaxHeightScrollView(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }
}

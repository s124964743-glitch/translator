package com.kala.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity(), KeyboardListener {

    private lateinit var settings: KalaSettings
    private lateinit var sound: SoundEngine
    private lateinit var kb: KeyboardView
    private lateinit var testField: EditText
    private lateinit var enableStatus: TextView
    private lateinit var selectStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val accent = Color.parseColor("#5B5BD6")
    private val ink = Color.parseColor("#1E1B3A")
    private val gray = Color.parseColor("#6E6E80")

    private val switchRows = ArrayList<Pair<String, LinearLayout>>()
    private val switchChecks = HashMap<String, TextView>()
    private val themeChips = ArrayList<Pair<String, LinearLayout>>()

    // ------------------------------------------------------------- helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }
    }

    private fun label(s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 18)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
    }

    private fun fullWidth(topDp: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topDp) }
    }

    private fun primaryButton(s: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = s
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(accent, 12)
            setOnClickListener { onClick() }
        }
    }

    // ------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = KalaSettings(this)
        sound = SoundEngine(this)
        sound.load(settings.switchId)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("喀啦鍵盤", 30f, ink, true))
        root.addView(label("機械軸打字音效・永久免費・無廣告", 14f, gray), fullWidth(2))

        buildSetupCard(root)
        buildTestCard(root)
        buildSwitchCard(root)
        buildThemeCard(root)
        buildSettingsCard(root)
        buildAboutCard(root)
    }

    override fun onResume() {
        super.onResume()
        refreshSetupStatus()
    }

    override fun onDestroy() {
        sound.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------- setup

    private fun buildSetupCard(root: LinearLayout) {
        val c = card()
        c.addView(label("開始使用", 17f, ink, true))
        enableStatus = label("", 14f, gray)
        c.addView(enableStatus, fullWidth(10))
        c.addView(primaryButton("① 前往啟用「喀啦鍵盤」") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }, fullWidth(4))
        selectStatus = label("", 14f, gray)
        c.addView(selectStatus, fullWidth(12))
        c.addView(primaryButton("② 切換為喀啦鍵盤") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }, fullWidth(4))
        root.addView(c)
    }

    private fun refreshSetupStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val cur = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = cur.startsWith("$packageName/")
        enableStatus.text = if (enabled) "✓ 已啟用" else "尚未啟用"
        enableStatus.setTextColor(if (enabled) Color.parseColor("#1B9E5A") else gray)
        selectStatus.text = if (selected) "✓ 目前正在使用" else "尚未切換為目前輸入法"
        selectStatus.setTextColor(if (selected) Color.parseColor("#1B9E5A") else gray)
    }

    // ------------------------------------------------------------- test area

    private fun buildTestCard(root: LinearLayout) {
        val c = card()
        c.addView(label("試打看看", 17f, ink, true))
        c.addView(label("點下方的鍵盤，就能聽到目前選的軸體聲音", 13f, gray), fullWidth(2))

        testField = EditText(this).apply {
            hint = "在這裡試打…"
            showSoftInputOnFocus = false
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            background = rounded(Color.parseColor("#F1F2F8"), 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        c.addView(testField, fullWidth(10))

        kb = KeyboardView(this, settings, sound, this)
        kb.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
            }
        }
        kb.clipToOutline = true
        c.addView(kb, fullWidth(10))
        root.addView(c)
    }

    override fun onText(text: String) {
        val s = max(testField.selectionStart, 0)
        val e = max(testField.selectionEnd, 0)
        val a = min(s, e)
        val b = max(s, e)
        testField.text.replace(a, b, text)
        testField.setSelection(min(a + text.length, testField.text.length))
    }

    override fun onBackspace() {
        val s = max(testField.selectionStart, 0)
        val e = max(testField.selectionEnd, 0)
        val a = min(s, e)
        val b = max(s, e)
        if (a != b) {
            testField.text.delete(a, b)
            testField.setSelection(a)
        } else if (a > 0) {
            testField.text.delete(a - 1, a)
            testField.setSelection(a - 1)
        }
    }

    override fun onEnter() {
        onText("\n")
    }

    // ------------------------------------------------------------- switches

    private fun buildSwitchCard(root: LinearLayout) {
        val c = card()
        c.addView(label("機械軸音效", 17f, ink, true))
        c.addView(label("點一下即可試聽", 13f, gray), fullWidth(2))
        for (sw in Switches.all) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(sw.color)
                }
            }
            row.addView(dot, LinearLayout.LayoutParams(dp(26), dp(26)))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(sw.name, 16f, ink, true))
            texts.addView(label(sw.desc, 12f, gray))
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12)
            })
            val check = label("✓", 20f, accent, true)
            row.addView(check)
            switchChecks[sw.id] = check
            row.setOnClickListener { selectSwitch(sw.id, true) }
            switchRows.add(Pair(sw.id, row))
            c.addView(row, fullWidth(8))
        }
        root.addView(c)
        selectSwitch(settings.switchId, false)
    }

    private fun selectSwitch(id: String, preview: Boolean) {
        settings.switchId = id
        sound.load(id)
        for ((sid, row) in switchRows) {
            val on = sid == id
            row.background = if (on) rounded(Color.parseColor("#EEF0FF"), 14, accent, 2)
            else rounded(Color.parseColor("#F6F7FB"), 14)
            switchChecks[sid]?.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
        if (preview) {
            // SoundPool needs a moment to decode freshly loaded samples
            handler.postDelayed({ sound.play("down", settings.volume) }, 180)
            handler.postDelayed({ sound.play("up", settings.volume) }, 320)
            handler.postDelayed({ sound.play("space", settings.volume) }, 650)
        }
    }

    // ------------------------------------------------------------- themes

    private fun buildThemeCard(root: LinearLayout) {
        val c = card()
        c.addView(label("鍵盤主題", 17f, ink, true))
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val list = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (th in Themes.all) {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val swatch = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = rounded(th.bg, 10, Color.parseColor("#D8D9E6"), 1)
            }
            val colors = intArrayOf(th.keyTop, th.accent, th.led[1 % th.led.size])
            for (col in colors) {
                val sq = View(this).apply { background = rounded(col, 4) }
                swatch.addView(sq, LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                    leftMargin = dp(3); rightMargin = dp(3)
                })
            }
            chip.addView(swatch, LinearLayout.LayoutParams(dp(84), dp(44)))
            chip.addView(label(th.name, 12f, ink), fullWidth(4))
            chip.setOnClickListener { selectTheme(th.id) }
            themeChips.add(Pair(th.id, chip))
            list.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) })
        }
        hs.addView(list)
        c.addView(hs, fullWidth(8))
        root.addView(c)
        selectTheme(settings.themeId)
    }

    private fun selectTheme(id: String) {
        settings.themeId = id
        for ((tid, chip) in themeChips) {
            chip.background = if (tid == id) rounded(Color.parseColor("#EEF0FF"), 14, accent, 2)
            else rounded(Color.TRANSPARENT, 14)
        }
        kb.applySettings()
    }

    // ------------------------------------------------------------- settings

    private fun toggle(c: LinearLayout, title: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label(title, 15f, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            isChecked = get()
            setOnCheckedChangeListener { _, checked ->
                set(checked)
                kb.applySettings()
            }
        }
        row.addView(sw)
        c.addView(row, fullWidth(10))
    }

    private fun slider(
        c: LinearLayout, title: String, lo: Int, hi: Int, get: () -> Int,
        unit: String, set: (Int) -> Unit
    ) {
        val head = label("$title：${get()}$unit", 15f, ink)
        c.addView(head, fullWidth(14))
        val bar = SeekBar(this).apply {
            this.max = hi - lo
            progress = get() - lo
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + lo
                    head.text = "$title：$v$unit"
                    if (fromUser) {
                        set(v)
                        kb.applySettings()
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    if (title == "音量") sound.play("down", settings.volume)
                }
            })
        }
        c.addView(bar, fullWidth(2))
    }

    private fun buildSettingsCard(root: LinearLayout) {
        val c = card()
        c.addView(label("設定", 17f, ink, true))
        toggle(c, "按鍵音效", { settings.soundOn }, { settings.soundOn = it })
        toggle(c, "放開按鍵時的回彈聲", { settings.releaseSound }, { settings.releaseSound = it })
        toggle(c, "按鍵震動", { settings.vibrateOn }, { settings.vibrateOn = it })
        toggle(c, "按鍵放大提示", { settings.popupOn }, { settings.popupOn = it })
        toggle(c, "LED 流光燈效", { settings.ledOn }, { settings.ledOn = it })
        toggle(c, "顯示數字列", { settings.numberRow }, { settings.numberRow = it })
        slider(c, "音量", 0, 100, { settings.volume }, "%") { settings.volume = it }
        slider(c, "震動強度", 3, 40, { settings.vibrateMs }, " ms") { settings.vibrateMs = it }
        slider(c, "鍵盤高度", 80, 130, { settings.heightPct }, "%") { settings.heightPct = it }
        root.addView(c)
    }

    private fun buildAboutCard(root: LinearLayout) {
        val c = card()
        c.addView(label("關於", 17f, ink, true))
        c.addView(
            label(
                "永久免費、沒有廣告、沒有內購。\n本 App 沒有申請網路權限，你輸入的內容不會離開手機。\n\n" +
                    "目前支援英文與符號輸入；長按 Q~P 可輸入數字（關閉數字列時），長按「,」與「.」可輸入「!」與「?」。",
                13f, gray
            ), fullWidth(6)
        )
        root.addView(c)
    }
}

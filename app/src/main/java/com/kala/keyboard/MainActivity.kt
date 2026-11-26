package com.kala.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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

class MainActivity : Activity() {

    private lateinit var settings: KalaSettings
    private lateinit var sound: SoundEngine
    private lateinit var kb: KeyboardView
    private lateinit var controller: InputController
    private lateinit var themeList: LinearLayout
    private var compStart = -1
    private var compLen = 0
    private lateinit var testField: EditText
    private lateinit var enableStatus: TextView
    private lateinit var selectStatus: TextView

    private val REQ_BG = 4242
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
        ZhuyinDictHolder.ensure(this)

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

        kb = KeyboardView(this, settings, sound, null)
        controller = InputController(editorTarget, kb, settings)
        kb.listener = controller
        controller.reset()
        kb.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
            }
        }
        kb.clipToOutline = true
        c.addView(kb, fullWidth(10))
        root.addView(c)
    }

    private fun selRange(): IntArray {
        val s = max(testField.selectionStart, 0)
        val e = max(testField.selectionEnd, 0)
        return intArrayOf(min(s, e), max(s, e))
    }

    private val editorTarget = object : TextTarget {
        override fun commit(text: String) {
            val ed = testField.text
            if (compLen > 0 && compStart >= 0) {
                ed.replace(compStart, compStart + compLen, text)
                testField.setSelection(min(compStart + text.length, ed.length))
            } else {
                val r = selRange()
                ed.replace(r[0], r[1], text)
                testField.setSelection(min(r[0] + text.length, ed.length))
            }
            compStart = -1
            compLen = 0
        }

        override fun setComposing(text: String) {
            val ed = testField.text
            if (compLen > 0 && compStart >= 0) {
                ed.replace(compStart, compStart + compLen, text)
            } else if (text.isNotEmpty()) {
                val r = selRange()
                ed.replace(r[0], r[1], text)
                compStart = r[0]
            }
            compLen = text.length
            if (compLen == 0) compStart = -1 else testField.setSelection(min(compStart + compLen, ed.length))
        }

        override fun backspace() {
            val r = selRange()
            val ed = testField.text
            if (r[0] != r[1]) {
                ed.delete(r[0], r[1])
                testField.setSelection(r[0])
            } else if (r[0] > 0) {
                ed.delete(r[0] - 1, r[0])
                testField.setSelection(r[0] - 1)
            }
        }

        override fun enter() {
            val r = selRange()
            testField.text.replace(r[0], r[1], "\n")
            testField.setSelection(min(r[0] + 1, testField.text.length))
        }

        override fun wantsCaps(): Boolean = selRange()[0] == 0
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
        c.addView(label("也可以直接在鍵盤上方工具列的「主題」切換", 12f, gray), fullWidth(2))
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        themeList = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hs.addView(themeList)
        c.addView(hs, fullWidth(8))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(primaryButton("選擇背景圖片") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "image/*"
            startActivityForResult(i, REQ_BG)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(primaryButton("移除背景圖片") {
            BgImage.remove(this)
            if (settings.themeId == "custom") settings.themeId = "gdark"
            fillThemeChips()
            kb.applySettings()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        c.addView(row, fullWidth(10))
        root.addView(c)
        fillThemeChips()
    }

    private fun fillThemeChips() {
        themeList.removeAllViews()
        themeChips.clear()
        for (th in Themes.list(this)) {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val swatch = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = rounded(if (th.id == "custom") Color.parseColor("#888888") else th.bg, 10, Color.parseColor("#D8D9E6"), 1)
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
            themeList.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) })
        }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BG && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                if (bmp != null) {
                    BgImage.save(this, bmp)
                    settings.themeId = "custom"
                    fillThemeChips()
                }
            } catch (e: Exception) {
                // ignore unreadable images
            }
        }
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
        toggle(c, "預設使用注音輸入", { settings.lang == "zh" }, {
            settings.lang = if (it) "zh" else "en"
            controller.reset()
        })
        toggle(c, "聯想字", { settings.assocOn }, { settings.assocOn = it })
        toggle(c, "按鍵音效", { settings.soundOn }, { settings.soundOn = it })
        toggle(c, "放開按鍵時的回彈聲", { settings.releaseSound }, { settings.releaseSound = it })
        toggle(c, "按鍵震動", { settings.vibrateOn }, { settings.vibrateOn = it })
        toggle(c, "按鍵放大提示", { settings.popupOn }, { settings.popupOn = it })
        toggle(c, "LED 流光燈效", { settings.ledOn }, { settings.ledOn = it })
        toggle(c, "顯示數字列", { settings.numberRow }, { settings.numberRow = it })
        slider(c, "音量", 0, 100, { settings.volume }, "%") { settings.volume = it }
        slider(c, "震動強度", 3, 40, { settings.vibrateMs }, " ms") { settings.vibrateMs = it }
        slider(c, "按鍵間距", 2, 12, { settings.keyGap }, " dp") { settings.keyGap = it }
        slider(c, "鍵盤高度", 80, 130, { settings.heightPct }, "%") { settings.heightPct = it }
        root.addView(c)
    }

    private fun buildAboutCard(root: LinearLayout) {
        val c = card()
        c.addView(label("關於", 17f, ink, true))
        c.addView(
            label(
                "永久免費、沒有廣告、沒有內購。\n本 App 沒有申請網路權限，你輸入的內容不會離開手機。\n\n" +
                    "支援繁體中文注音（大千排列）與英文。注音鍵右上角的小字是長按可輸入的數字／字母；" +
                    "按住鍵盤上的 🌐 可切換系統輸入法。詞庫來自 McBopomofo（MIT 授權）。",
                13f, gray
            ), fullWidth(6)
        )
        root.addView(c)
    }
}

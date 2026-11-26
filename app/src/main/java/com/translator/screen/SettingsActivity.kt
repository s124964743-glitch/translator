package com.translator.screen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var tvStatus: TextView
    private lateinit var rbClaude: RadioButton
    private lateinit var rbMs: RadioButton
    private lateinit var etKey: EditText
    private lateinit var etModel: EditText
    private lateinit var tvTest: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 15f): TextView {
        val tv = TextView(this)
        tv.text = s
        tv.textSize = size
        tv.setPadding(0, dp(6), 0, dp(6))
        return tv
    }

    private fun button(s: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = s
        b.setOnClickListener { onClick() }
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(40), dp(16), dp(24))
        sv.addView(col)

        col.addView(text("螢幕翻譯 設定", 22f))
        col.addView(
            text(
                "使用方式：\n1. 開啟下方「無障礙服務」並啟用「螢幕翻譯」。\n" +
                    "2. 之後只要點桌面的「螢幕翻譯」圖示就會啟動，並自動回到主畫面；切到要翻譯的 App 即可即時翻譯。\n" +
                    "3. 按浮窗上的「✕ 關閉」會倒數 5 秒關閉（再按一次可取消）。關閉後需再點圖示才會啟動。\n" +
                    "4. 長按圖示 →「設定」可回到此頁。\n\n" +
                    "左側語言＝翻成的語言，右側語言＝要辨識並翻譯的語言（例：繁>越＝把畫面上的越南文翻成繁體中文，繁體中文不翻）。",
                14f
            )
        )

        tvStatus = text("")
        col.addView(tvStatus)
        col.addView(button("前往開啟無障礙服務") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("App 資訊（Android 13+ 若選項灰色，先按右上角⋮→允許受限制的設定）") {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        })

        col.addView(text("翻譯引擎", 18f))
        val rg = RadioGroup(this)
        rbClaude = RadioButton(this)
        rbClaude.text = "Claude（精準、口語，需 API Key）"
        rbMs = RadioButton(this)
        rbMs.text = "Microsoft 翻譯（免金鑰備援，口語度較差）"
        rg.addView(rbClaude)
        rg.addView(rbMs)
        col.addView(rg)
        if (prefs.engine == "claude") rbClaude.isChecked = true else rbMs.isChecked = true

        col.addView(text("Claude API Key（sk-ant-…）"))
        etKey = EditText(this)
        etKey.setText(prefs.apiKey)
        etKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        etKey.setSingleLine(true)
        col.addView(etKey)

        col.addView(text("模型（預設 ${Prefs.DEFAULT_MODEL}；要更高品質可改 claude-sonnet-5-5）"))
        etModel = EditText(this)
        etModel.setText(prefs.model)
        etModel.setSingleLine(true)
        col.addView(etModel)

        col.addView(button("儲存") {
            save()
            Toast.makeText(this, "已儲存", Toast.LENGTH_SHORT).show()
        })
        col.addView(button("測試翻譯") { runTest() })
        tvTest = text("")
        col.addView(tvTest)

        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        tvStatus.text = if (TranslatorService.instance != null) "無障礙服務：✅ 已啟用" else "無障礙服務：❌ 尚未啟用"
    }

    private fun save() {
        prefs.engine = if (rbClaude.isChecked) "claude" else "ms"
        prefs.apiKey = etKey.text.toString().trim()
        prefs.model = etModel.text.toString().trim().ifEmpty { Prefs.DEFAULT_MODEL }
    }

    private fun runTest() {
        save()
        tvTest.text = "測試中…"
        Thread {
            val m = TranslationManager(prefs)
            val msg = try {
                val r = m.translate(listOf("欸，你今天晚上有空嗎？一起去吃宵夜啦。"), Lang.ZH, Lang.VI)[0]
                r + (m.lastNote?.let { "\n⚠ $it" } ?: "")
            } catch (e: Exception) {
                "⚠ 失敗：" + (e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread { tvTest.text = msg }
        }.start()
    }
}

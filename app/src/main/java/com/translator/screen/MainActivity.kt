package com.translator.screen

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** 點圖示：啟動翻譯並退回主畫面；服務尚未啟用時開啟設定頁 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = TranslatorService.instance
        if (s != null) {
            s.activate()
        } else {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        finish()
    }
}

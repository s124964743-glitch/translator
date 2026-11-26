package com.translator.screen

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** 左側：翻譯成的語言（我的語言） */
    var mine: Lang
        get() = Lang.from(sp.getString("mine", null), Lang.ZH)
        set(v) { sp.edit().putString("mine", v.code).apply() }

    /** 右側：要辨識並翻譯的語言（畫面上的外語） */
    var foreign: Lang
        get() = Lang.from(sp.getString("foreign", null), Lang.VI)
        set(v) { sp.edit().putString("foreign", v.code).apply() }

    var engine: String
        get() = sp.getString("engine", "claude") ?: "claude"
        set(v) { sp.edit().putString("engine", v).apply() }

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) { sp.edit().putString("apiKey", v).apply() }

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) { sp.edit().putString("model", v).apply() }

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"
    }
}

package com.kala.keyboard

import android.content.Context
import android.content.SharedPreferences

class KalaSettings(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("kala_prefs", Context.MODE_PRIVATE)

    var soundOn: Boolean
        get() = sp.getBoolean("soundOn", true)
        set(v) = sp.edit().putBoolean("soundOn", v).apply()

    var releaseSound: Boolean
        get() = sp.getBoolean("releaseSound", true)
        set(v) = sp.edit().putBoolean("releaseSound", v).apply()

    var volume: Int
        get() = sp.getInt("volume", 80)
        set(v) = sp.edit().putInt("volume", v).apply()

    var switchId: String
        get() = sp.getString("switchId", "blue") ?: "blue"
        set(v) = sp.edit().putString("switchId", v).apply()

    var vibrateOn: Boolean
        get() = sp.getBoolean("vibrateOn", true)
        set(v) = sp.edit().putBoolean("vibrateOn", v).apply()

    var vibrateMs: Int
        get() = sp.getInt("vibrateMs", 12)
        set(v) = sp.edit().putInt("vibrateMs", v).apply()

    var popupOn: Boolean
        get() = sp.getBoolean("popupOn", true)
        set(v) = sp.edit().putBoolean("popupOn", v).apply()

    var ledOn: Boolean
        get() = sp.getBoolean("ledOn", true)
        set(v) = sp.edit().putBoolean("ledOn", v).apply()

    var numberRow: Boolean
        get() = sp.getBoolean("numberRow", false)
        set(v) = sp.edit().putBoolean("numberRow", v).apply()

    var themeId: String
        get() = sp.getString("themeId", "sakura") ?: "sakura"
        set(v) = sp.edit().putString("themeId", v).apply()

    /** "zh" (注音) or "en" */
    var lang: String
        get() = sp.getString("lang", "zh") ?: "zh"
        set(v) = sp.edit().putString("lang", v).apply()

    var assocOn: Boolean
        get() = sp.getBoolean("assocOn", true)
        set(v) = sp.edit().putBoolean("assocOn", v).apply()

    /** keyboard height in percent (80..130) */
    var heightPct: Int
        get() = sp.getInt("heightPct", 100)
        set(v) = sp.edit().putInt("heightPct", v).apply()
}

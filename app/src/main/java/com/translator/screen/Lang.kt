package com.translator.screen

enum class Lang(
    val code: String,
    val short: String,
    val label: String,
    val ms: String,
    val english: String
) {
    ZH("zh", "繁", "繁體中文", "zh-Hant", "Traditional Chinese (Taiwan)"),
    VI("vi", "越", "越南語", "vi", "Vietnamese"),
    EN("en", "英", "英文", "en", "English"),
    TH("th", "泰", "泰語", "th", "Thai"),
    JA("ja", "日", "日文", "ja", "Japanese");

    companion object {
        fun from(code: String?, def: Lang): Lang = values().firstOrNull { it.code == code } ?: def
    }
}

object LangDetect {
    private const val VI_UNIQUE = "ăâđêôơưĂÂĐÊÔƠƯãÃõÕĩĨũŨ"

    private fun isViUnique(ch: Char): Boolean =
        VI_UNIQUE.indexOf(ch) >= 0 || ch.code in 0x1EA0..0x1EF9

    /** 回傳文字的主要語言；無法判斷（純數字、符號等）回傳 null */
    fun detect(s: String): Lang? {
        var thai = 0
        var kana = 0
        var han = 0
        var latin = 0
        var viU = 0
        var accented = 0
        for (ch in s) {
            val c = ch.code
            when {
                c in 0x0E00..0x0E7F -> thai++
                c in 0x3040..0x30FF || c in 0x31F0..0x31FF || c in 0xFF66..0xFF9F -> kana++
                c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF -> han++
                ch in 'A'..'Z' || ch in 'a'..'z' -> latin++
                c in 0x00C0..0x024F || c in 0x1E00..0x1EFF -> {
                    latin++
                    if (isViUnique(ch)) viU++ else accented++
                }
            }
        }
        val tScore = thai * 2
        val cScore = (han + kana) * 2
        val best = maxOf(tScore, cScore, latin)
        if (best == 0) return null
        if (best == tScore) return Lang.TH
        if (best == cScore) return if (kana > 0) Lang.JA else Lang.ZH
        if (latin < 2) return null
        val vi = viU > 0 || (accented > 0 && accented * 100 >= latin * 8)
        return if (vi) Lang.VI else Lang.EN
    }
}

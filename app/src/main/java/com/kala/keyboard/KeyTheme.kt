package com.kala.keyboard

import android.graphics.Color

class KeyTheme(
    val id: String,
    val name: String,
    val bg: Int,
    val keyTop: Int,
    val keySide: Int,
    val keyText: Int,
    val funcTop: Int,
    val funcText: Int,
    val accent: Int,
    val led: IntArray
)

object Themes {
    private fun c(hex: String): Int = Color.parseColor(hex)

    val all: List<KeyTheme> = listOf(
        KeyTheme("sakura", "櫻花糖",
            c("#F8E6EE"), c("#FFFFFF"), c("#E3BCCD"), c("#6B3B50"),
            c("#F5C6D8"), c("#6B3B50"), c("#F06292"),
            intArrayOf(c("#FF8AB3"), c("#FFC1E3"), c("#C9A7FF"))),
        KeyTheme("glacier", "冰川藍",
            c("#E4F1F8"), c("#F7FCFF"), c("#B3D0E3"), c("#27475C"),
            c("#CFE6F5"), c("#27475C"), c("#3E9AD6"),
            intArrayOf(c("#6DD5FA"), c("#2193B0"), c("#A8E6FF"))),
        KeyTheme("lavender", "薰衣草",
            c("#E9E4F7"), c("#FAF8FF"), c("#C2B7E3"), c("#3E3363"),
            c("#D8CEF3"), c("#3E3363"), c("#8E6FE0"),
            intArrayOf(c("#B39DFF"), c("#7F5AF0"), c("#F4A6FF"))),
        KeyTheme("jelly", "薄荷果凍",
            c("#E3F6EA"), c("#F5FFF8"), c("#B0DBBF"), c("#24533A"),
            c("#CDEEDB"), c("#24533A"), c("#2BBF7A"),
            intArrayOf(c("#7BE495"), c("#FFD86B"), c("#FF8FA3"))),
        KeyTheme("aurora", "極光",
            c("#0F1A24"), c("#1F2E3C"), c("#09111A"), c("#DFF5F0"),
            c("#16222D"), c("#DFF5F0"), c("#3DDC97"),
            intArrayOf(c("#3DDC97"), c("#00C2FF"), c("#9B5DE5"))),
        KeyTheme("basalt", "玄武岩",
            c("#17171A"), c("#2C2C33"), c("#0C0C0E"), c("#E8E8EC"),
            c("#202026"), c("#E8E8EC"), c("#FF9F43"),
            intArrayOf(c("#FF9F43"), c("#FF6B6B"), c("#FFD166"))),
        KeyTheme("cyber", "賽博龐克",
            c("#0B0620"), c("#1B1140"), c("#06030F"), c("#00F0FF"),
            c("#241450"), c("#00F0FF"), c("#FF2BD6"),
            intArrayOf(c("#FF2BD6"), c("#00F0FF"), c("#F5E642"))),
        KeyTheme("racing", "賽車紅",
            c("#111111"), c("#262626"), c("#080808"), c("#F2F2F2"),
            c("#3A0D0D"), c("#F2F2F2"), c("#E10600"),
            intArrayOf(c("#E10600"), c("#FFFFFF"), c("#FFB800")))
    )

    fun byId(id: String): KeyTheme = all.firstOrNull { it.id == id } ?: all[0]
}

class SwitchInfo(val id: String, val name: String, val desc: String, val color: Int)

object Switches {
    val all: List<SwitchInfo> = listOf(
        SwitchInfo("red", "紅軸", "線性順滑，輕柔安靜，帶有厚實的觸底聲", Color.parseColor("#E5484D")),
        SwitchInfo("brown", "茶軸", "輕微段落感，清脆有回饋", Color.parseColor("#9A6B45")),
        SwitchInfo("black", "黑軸", "重壓線性，低沉穩重", Color.parseColor("#3A3A42")),
        SwitchInfo("blue", "青軸", "清脆響亮的「喀嗒」段落聲", Color.parseColor("#3B7BEA")),
        SwitchInfo("creamy", "奶油軸", "圓潤低沉的「噠」聲，客製鍵盤愛好者最愛", Color.parseColor("#E9C46A")),
        SwitchInfo("typewriter", "打字機", "復古金屬敲擊聲，帶有清脆回音", Color.parseColor("#8D99AE"))
    )
}

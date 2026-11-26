package com.kala.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File

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

    private val base: List<KeyTheme> = listOf(
        KeyTheme("gdark", "經典深灰",
            c("#202124"), c("#3C4043"), c("#17181A"), c("#E8EAED"),
            c("#2D2F31"), c("#E8EAED"), c("#8AB4F8"),
            intArrayOf(c("#8AB4F8"), c("#81C995"), c("#F28B82"))),
        KeyTheme("glight", "經典淺灰",
            c("#E8EAED"), c("#FFFFFF"), c("#BFC3C9"), c("#202124"),
            c("#DADCE0"), c("#202124"), c("#1A73E8"),
            intArrayOf(c("#1A73E8"), c("#34A853"), c("#FBBC04"))),
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
        KeyTheme("milktea", "奶茶",
            c("#EADBC8"), c("#FBF4EA"), c("#C9B196"), c("#5A4632"),
            c("#DCC8AE"), c("#5A4632"), c("#B7791F"),
            intArrayOf(c("#E0A96D"), c("#F4D6A0"), c("#C98B5A"))),
        KeyTheme("peach", "蜜桃",
            c("#FFE8E0"), c("#FFF8F5"), c("#F2BFB0"), c("#7A3B2E"),
            c("#FFD3C4"), c("#7A3B2E"), c("#FF7F6B"),
            intArrayOf(c("#FF9E80"), c("#FFCC80"), c("#FF8A9B"))),
        KeyTheme("aurora", "極光",
            c("#0F1A24"), c("#1F2E3C"), c("#09111A"), c("#DFF5F0"),
            c("#16222D"), c("#DFF5F0"), c("#3DDC97"),
            intArrayOf(c("#3DDC97"), c("#00C2FF"), c("#9B5DE5"))),
        KeyTheme("midnight", "午夜藍",
            c("#0D1B2A"), c("#1B2B40"), c("#08121D"), c("#E0E8F5"),
            c("#142234"), c("#E0E8F5"), c("#4D9DE0"),
            intArrayOf(c("#4D9DE0"), c("#7B6CF6"), c("#3DD6D0"))),
        KeyTheme("basalt", "玄武岩",
            c("#17171A"), c("#2C2C33"), c("#0C0C0E"), c("#E8E8EC"),
            c("#202026"), c("#E8E8EC"), c("#FF9F43"),
            intArrayOf(c("#FF9F43"), c("#FF6B6B"), c("#FFD166"))),
        KeyTheme("blackgold", "黑金",
            c("#101010"), c("#222222"), c("#050505"), c("#F1D58A"),
            c("#1A1A1A"), c("#F1D58A"), c("#D4A93C"),
            intArrayOf(c("#D4A93C"), c("#F6E3A1"), c("#B8860B"))),
        KeyTheme("cyber", "賽博龐克",
            c("#0B0620"), c("#1B1140"), c("#06030F"), c("#00F0FF"),
            c("#241450"), c("#00F0FF"), c("#FF2BD6"),
            intArrayOf(c("#FF2BD6"), c("#00F0FF"), c("#F5E642"))),
        KeyTheme("racing", "賽車紅",
            c("#111111"), c("#262626"), c("#080808"), c("#F2F2F2"),
            c("#3A0D0D"), c("#F2F2F2"), c("#E10600"),
            intArrayOf(c("#E10600"), c("#FFFFFF"), c("#FFB800")))
    )

    /** translucent keys drawn over the user's own background picture */
    val custom = KeyTheme("custom", "自訂圖片",
        c("#000000"), c("#B3FFFFFF"), c("#40000000"), c("#1B1B1F"),
        c("#80FFFFFF"), c("#1B1B1F"), c("#6C8CFF"),
        intArrayOf(c("#6C8CFF"), c("#FF8AB3"), c("#7BE495")))

    fun list(ctx: Context): List<KeyTheme> =
        if (BgImage.exists(ctx)) base + custom else base

    fun byId(id: String): KeyTheme =
        if (id == "custom") custom else base.firstOrNull { it.id == id } ?: base[0]
}

object BgImage {
    private var cached: Bitmap? = null
    private var cachedStamp = -1L

    private fun file(ctx: Context) = File(ctx.filesDir, "bg.jpg")

    fun exists(ctx: Context): Boolean = file(ctx).exists()

    fun load(ctx: Context): Bitmap? {
        val f = file(ctx)
        if (!f.exists()) {
            cached = null
            cachedStamp = -1L
            return null
        }
        if (cached != null && cachedStamp == f.lastModified()) return cached
        return try {
            val bmp = BitmapFactory.decodeFile(f.absolutePath)
            cached = bmp
            cachedStamp = f.lastModified()
            bmp
        } catch (e: Exception) {
            null
        }
    }

    fun remove(ctx: Context) {
        file(ctx).delete()
        cached = null
        cachedStamp = -1L
    }

    fun save(ctx: Context, src: Bitmap) {
        val maxSide = 1400
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val out = if (scale < 1f) Bitmap.createScaledBitmap(
            src, (src.width * scale).toInt(), (src.height * scale).toInt(), true
        ) else src
        file(ctx).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        cached = null
        cachedStamp = -1L
    }
}

class SwitchInfo(val id: String, val name: String, val desc: String, val color: Int)

object Switches {
    val all: List<SwitchInfo> = listOf(
        SwitchInfo("blue", "青軸", "Cherry MX Blue．清脆響亮的「喀嗒」段落聲", Color.parseColor("#3B7BEA")),
        SwitchInfo("brown", "茶軸", "Cherry MX Brown．輕柔段落感，聲音收斂", Color.parseColor("#9A6B45")),
        SwitchInfo("red", "紅軸", "Cherry MX Red．線性順滑，輕巧安靜", Color.parseColor("#E5484D")),
        SwitchInfo("black", "黑軸", "Cherry MX Black．重壓線性，低沉穩重", Color.parseColor("#3A3A42")),
        SwitchInfo("oreo", "奶油 Oreo", "EG Oreo．圓潤飽滿的客製鍵盤音", Color.parseColor("#C9B8A6")),
        SwitchInfo("cream", "NK Cream", "NK Cream．低沉柔和的「噠噠」聲", Color.parseColor("#E9C46A")),
        SwitchInfo("crystal", "水晶紫", "EG Crystal Purple．清亮帶回音", Color.parseColor("#9B7BE8")),
        SwitchInfo("topre", "靜電容", "Topre 紫軸混合．沉穩的 thock 聲", Color.parseColor("#7C5CBF")),
        SwitchInfo("ibm", "IBM 復古", "IBM Model M 彈簧鍵盤．經典響亮", Color.parseColor("#8D99AE"))
    )
}

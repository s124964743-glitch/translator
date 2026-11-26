package com.translator.screen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TranslatorService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TranslatorService? = null
    }

    private class Item(val text: String, val top: Int, val left: Int, val desc: Boolean)

    private var lastStats = ""
    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            refresh()
            main.postDelayed(this, 1500)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var manager: TranslationManager
    private var overlay: OverlayUi? = null
    private var active = false
    private var refreshPending = false
    private var lastSig = ""
    private var currentTexts: List<String> = emptyList()
    private var launcherPkg: String? = null
    private var failUntil = 0L
    private val inflight = HashSet<String>()
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 800
    }

    private val callbacks = object : OverlayCallbacks {
        override fun onPairChanged() {
            lastSig = ""
            scheduleRefresh()
        }

        override fun onCloseConfirmed() {
            deactivate()
        }

        override fun replyTarget(): Lang =
            if (prefs.foreign != Lang.ZH) prefs.foreign else prefs.mine

        override fun onTranslateInput(text: String, done: (String?, String?) -> Unit) {
            val target = replyTarget()
            executor.execute {
                try {
                    val r = manager.translate(listOf(text), Lang.ZH, target)[0]
                    main.post { done(r, null) }
                } catch (e: Exception) {
                    main.post { done(null, e.message ?: e.javaClass.simpleName) }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        manager = TranslationManager(prefs)
        instance = this
        launcherPkg = try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!active || event == null) return
        if (event.packageName?.toString() == packageName) return
        scheduleRefresh()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        deactivate()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        deactivate()
        executor.shutdown()
        super.onDestroy()
    }

    // ---- 啟動 / 關閉 ----

    fun activate() {
        active = true
        lastSig = ""
        currentTexts = emptyList()
        if (overlay == null) overlay = OverlayUi(this, prefs, callbacks)
        overlay?.show()
        main.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
            main.postDelayed(ticker, 800)
        }, 250)
    }

    fun deactivate() {
        active = false
        refreshPending = false
        main.removeCallbacksAndMessages(null)
        inflight.clear()
        overlay?.hide()
        overlay = null
    }

    // ---- 讀取與翻譯 ----

    private fun scheduleRefresh() {
        if (!active || refreshPending) return
        refreshPending = true
        main.postDelayed({
            refreshPending = false
            refresh()
        }, 400)
    }

    private fun key(foreign: Lang, mine: Lang, t: String) = "${foreign.code}>${mine.code}|$t"

    private fun refresh() {
        if (!active) return
        val mine = prefs.mine
        val foreign = prefs.foreign
        val texts = collect(foreign)
        val sig = foreign.code + mine.code + "\u0001" + texts.joinToString("\u0000") + "\u0002" + lastStats
        if (sig == lastSig) return
        lastSig = sig
        if (texts.isEmpty()) overlay?.setStatus(lastStats)
        render(texts, mine, foreign)
    }

    private fun collect(foreign: Lang): List<String> {
        val items = ArrayList<Item>()
        val seenPkgs = ArrayList<String>()
        val counter = IntArray(1)
        val ws: List<AccessibilityWindowInfo> = try {
            windows
        } catch (e: Exception) {
            emptyList()
        }
        for (w in ws) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = w.root ?: continue
            if (skipPackage(root.packageName?.toString())) continue
            seenPkgs.add(root.packageName?.toString() ?: "?")
            walk(root, items, counter, 0)
        }
        if (ws.isEmpty()) {
            val root = rootInActiveWindow
            if (root != null && !skipPackage(root.packageName?.toString())) {
                seenPkgs.add("active:" + (root.packageName?.toString() ?: "?"))
                walk(root, items, counter, 0)
            }
        }
        items.sortWith(compareBy<Item>({ it.top }, { it.left }))
        val out = ArrayList<String>()
        var prevText = ""
        var prevTop = -1
        for (item in items) {
            if (item.text == prevText && item.top == prevTop) continue
            prevText = item.text
            prevTop = item.top
            // 被描述文字（contentDescription）完整包含的短文字視為重複
            if (items.any { o -> o !== item && o.desc && o.text.length > item.text.length && o.text.contains(item.text) }) continue
            val kept = extract(item.text, foreign)
            if (kept != null) out.add(kept)
        }
        lastStats = "讀到 ${items.size} 段文字，其中 ${out.size} 段是${foreign.label}"
        return if (out.size > 40) out.subList(out.size - 40, out.size).toList() else out
    }

    /**
     * 有些 App（如 Messenger）會把「寄件者, 訊息，點按兩下可查看…」合成一段文字。
     * 依逗號等分隔符拆開，只留下屬於要辨識語言的部分。
     */
    private fun extract(text: String, foreign: Lang): String? {
        val parts = text.split(Regex("[,，;；|｜\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val keep = parts.filter { LangDetect.detect(it) == foreign }
        if (keep.isEmpty()) return null
        return keep.joinToString(if (foreign == Lang.ZH || foreign == Lang.JA) "，" else ", ")
    }

    private fun skipPackage(pkg: String?): Boolean =
        pkg == null || pkg == packageName || pkg == launcherPkg || pkg == "com.android.systemui"

    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<Item>, counter: IntArray, depth: Int) {
        if (node == null || depth > 40 || counter[0] > 2500) return
        counter[0]++
        if (!node.isVisibleToUser) return
        if (!node.isEditable && !node.isPassword) {
            var raw = node.text?.toString()?.trim()
            var isDesc = false
            if (raw == null || raw.isEmpty()) {
                val d = node.contentDescription?.toString()?.trim()
                if (d != null && d.length >= 2) {
                    raw = d
                    isDesc = true
                }
            }
            if (raw != null && raw.isNotEmpty()) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    out.add(Item(if (raw.length > 1500) raw.substring(0, 1500) else raw, r.top, r.left, isDesc))
                }
            }
        }
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), out, counter, depth + 1)
        }
    }

    private fun redraw(mine: Lang, foreign: Lang) {
        overlay?.setLines(currentTexts.map { cache[key(foreign, mine, it)] })
    }

    private fun chunk(list: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var chars = 0
        for (t in list) {
            if (cur.isNotEmpty() && (cur.size >= 25 || chars + t.length > 3500)) {
                out.add(cur)
                cur = ArrayList()
                chars = 0
            }
            cur.add(t)
            chars += t.length
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun render(texts: List<String>, mine: Lang, foreign: Lang) {
        currentTexts = texts
        redraw(mine, foreign)
        if (System.currentTimeMillis() < failUntil) return
        val need = texts.distinct().filter {
            val k = key(foreign, mine, it)
            cache[k] == null && !inflight.contains(k)
        }
        if (need.isEmpty()) return
        // 最新（畫面下方）的訊息優先翻譯
        val chunks = chunk(need.asReversed()).map { it.asReversed().toList() }
        for (c in chunks) {
            for (t in c) inflight.add(key(foreign, mine, t))
            executor.execute {
                try {
                    val res = manager.translate(c, foreign, mine)
                    main.post {
                        c.forEachIndexed { i, t ->
                            val k = key(foreign, mine, t)
                            cache[k] = res[i]
                            inflight.remove(k)
                        }
                        if (active) {
                            overlay?.setStatus(manager.lastNote)
                            redraw(prefs.mine, prefs.foreign)
                        }
                    }
                } catch (e: Exception) {
                    main.post {
                        for (t in c) inflight.remove(key(foreign, mine, t))
                        failUntil = System.currentTimeMillis() + 3000
                        if (active) {
                            overlay?.setStatus("⚠ 翻譯失敗：" + (e.message ?: e.javaClass.simpleName))
                            main.postDelayed({
                                lastSig = ""
                                scheduleRefresh()
                            }, 5000)
                        }
                    }
                }
            }
        }
    }
}

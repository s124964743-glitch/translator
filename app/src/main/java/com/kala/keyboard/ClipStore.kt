package com.kala.keyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local clipboard history (stored only in this app's private preferences). */
class ClipItem(val text: String, var pinned: Boolean)

object ClipStore {
    private const val MAX = 40
    private var items = ArrayList<ClipItem>()
    private var loaded = false
    private var ctx: Context? = null

    private fun prefs() = ctx!!.getSharedPreferences("kala_clips", Context.MODE_PRIVATE)

    @Synchronized
    fun init(c: Context) {
        if (loaded) return
        ctx = c.applicationContext
        loaded = true
        try {
            val arr = JSONArray(prefs().getString("items", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(ClipItem(o.getString("t"), o.optBoolean("p", false)))
            }
        } catch (e: Exception) {
            items.clear()
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (it in items) arr.put(JSONObject().put("t", it.text).put("p", it.pinned))
        prefs().edit().putString("items", arr.toString()).apply()
    }

    @Synchronized
    fun all(): List<ClipItem> = items.sortedByDescending { it.pinned }.let { ArrayList(it) }

    @Synchronized
    fun add(text: String) {
        val t = text.trim()
        if (t.isEmpty() || t.length > 5000) return
        val old = items.firstOrNull { it.text == t }
        val pinned = old?.pinned ?: false
        if (old != null) items.remove(old)
        items.add(0, ClipItem(t, pinned))
        while (items.size > MAX) {
            val idx = items.indexOfLast { !it.pinned }
            if (idx < 0) break
            items.removeAt(idx)
        }
        save()
    }

    @Synchronized
    fun togglePin(item: ClipItem) {
        item.pinned = !item.pinned
        save()
    }

    @Synchronized
    fun remove(item: ClipItem) {
        items.remove(item)
        save()
    }

    @Synchronized
    fun clearUnpinned() {
        items.removeAll { !it.pinned }
        save()
    }
}

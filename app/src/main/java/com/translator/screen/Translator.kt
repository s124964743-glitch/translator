package com.translator.screen

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    fun request(url: String, method: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }
}

interface Engine {
    fun translate(texts: List<String>, from: Lang, to: Lang): List<String>
}

/** 以 Claude 翻譯：依上下文、口語化 */
class ClaudeEngine(private val apiKey: String, private val model: String) : Engine {
    private val system = "You are a professional interpreter translating live chat messages and on-screen text. " +
        "Translate each item from the source language into the target language the way a native speaker would actually say it in everyday conversation: " +
        "natural, colloquial and idiomatic, never word-for-word. Keep the speaker's tone, slang, emoji and punctuation. " +
        "Items are in on-screen order and usually belong to one conversation, so use neighbouring items as context for meaning, pronouns and ellipsis. " +
        "Keep names, numbers, URLs and brand names unchanged. " +
        "If the target is Traditional Chinese, use Taiwan usage and vocabulary, never Simplified Chinese. " +
        "Do not explain or add anything. The input is a JSON array of {\"id\":number,\"text\":string}. " +
        "Reply with ONLY a JSON array of {\"id\":number,\"t\":string} using the same ids."

    override fun translate(texts: List<String>, from: Lang, to: Lang): List<String> {
        val arr = JSONArray()
        texts.forEachIndexed { i, t -> arr.put(JSONObject().put("id", i).put("text", t)) }
        val userMsg = "Source language: ${from.english}\nTarget language: ${to.english}\n\n$arr"
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userMsg)))
        val (code, resp) = Http.request(
            "https://api.anthropic.com/v1/messages", "POST",
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                "content-type" to "application/json"
            ),
            body.toString()
        )
        if (code !in 200..299) {
            val msg = try {
                JSONObject(resp).optJSONObject("error")?.optString("message") ?: resp.take(120)
            } catch (e: Exception) {
                resp.take(120)
            }
            throw IOException("HTTP $code $msg")
        }
        val content = JSONObject(resp).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return parse(sb.toString(), texts)
    }

    private fun parse(raw: String, texts: List<String>): List<String> {
        val s = raw.indexOf('[')
        val e = raw.lastIndexOf(']')
        if (s < 0 || e <= s) throw IOException("回應格式錯誤")
        val arr = JSONArray(raw.substring(s, e + 1))
        val out = ArrayList<String>(texts)
        var hit = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", -1)
            val t = o.optString("t", "")
            if (id in texts.indices && t.isNotBlank()) {
                out[id] = t.trim()
                hit++
            }
        }
        if (hit == 0) throw IOException("回應內容無法解析")
        return out
    }
}

/** 備援：Microsoft 翻譯（免金鑰，口語度較差） */
object MsEngine : Engine {
    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L

    private fun auth(force: Boolean): String {
        val cur = token
        if (!force && cur != null && System.currentTimeMillis() - tokenAt < 8 * 60 * 1000) return cur
        val (c, t) = Http.request("https://edge.microsoft.com/translate/auth", "GET", mapOf("User-Agent" to "Mozilla/5.0"), null)
        if (c !in 200..299 || t.isBlank()) throw IOException("Microsoft 授權失敗 $c")
        token = t.trim()
        tokenAt = System.currentTimeMillis()
        return t.trim()
    }

    override fun translate(texts: List<String>, from: Lang, to: Lang): List<String> {
        val arr = JSONArray()
        for (t in texts) arr.put(JSONObject().put("Text", t))
        val url = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&from=${from.ms}&to=${to.ms}&textType=plain"
        var attempt = 0
        while (true) {
            val (code, resp) = Http.request(
                url, "POST",
                mapOf("Authorization" to "Bearer " + auth(attempt > 0), "Content-Type" to "application/json"),
                arr.toString()
            )
            if (code == 401 && attempt == 0) {
                attempt++
                continue
            }
            if (code !in 200..299) throw IOException("Microsoft HTTP $code")
            val res = JSONArray(resp)
            val out = ArrayList<String>()
            for (i in 0 until res.length()) {
                out.add(res.getJSONObject(i).getJSONArray("translations").getJSONObject(0).getString("text"))
            }
            if (out.size != texts.size) throw IOException("Microsoft 回應數量不符")
            return out
        }
    }
}

class TranslationManager(private val prefs: Prefs) {
    @Volatile var lastNote: String? = null

    fun translate(texts: List<String>, from: Lang, to: Lang): List<String> {
        if (prefs.engine == "claude") {
            val key = prefs.apiKey.trim()
            if (key.isEmpty()) {
                lastNote = "未設定 Claude API Key，目前使用備援引擎（口語度較差）"
            } else {
                try {
                    val r = ClaudeEngine(key, prefs.model.trim().ifEmpty { Prefs.DEFAULT_MODEL }).translate(texts, from, to)
                    lastNote = null
                    return r
                } catch (e: Exception) {
                    lastNote = "Claude 失敗（${e.message}），已改用備援引擎"
                }
            }
        } else {
            lastNote = null
        }
        return MsEngine.translate(texts, from, to)
    }
}

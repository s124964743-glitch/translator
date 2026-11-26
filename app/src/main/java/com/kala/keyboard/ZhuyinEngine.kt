package com.kala.keyboard

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** One dictionary entry: text, toneless syllables, tone codes ('1'..'5', one per syllable) and frequency. */
class DictEntry(val text: String, val sy: Array<String>, val tones: String, val freq: Int)

class Cand(val text: String, val used: Int)

class ZhuyinDict private constructor(
    private val byFirst: HashMap<String, ArrayList<DictEntry>>,
    private val firstKeys: List<String>,
    private val byFirstChar: HashMap<Char, ArrayList<DictEntry>>,
    val logTotal: Double
) {
    private fun matches(e: DictEntry, bodies: List<String>, tones: CharArray, start: Int, len: Int, prefixLast: Boolean): Boolean {
        if (e.sy.size != len) return false
        for (i in 0 until len) {
            val body = e.sy[i]
            val typed = bodies[start + i]
            if (i == len - 1 && prefixLast) {
                if (!body.startsWith(typed)) return false
            } else if (body != typed) return false
            val t = tones[start + i]
            if (t != ' ' && t != e.tones[i]) return false
        }
        return true
    }

    /** Most frequent word for syllables [start, start+len); lists are sorted by frequency so the first hit wins. */
    fun bestWord(bodies: List<String>, tones: CharArray, start: Int, len: Int, prefixLast: Boolean): DictEntry? {
        if (len == 1 && prefixLast) {
            var best: DictEntry? = null
            for (key in firstKeys) {
                if (!key.startsWith(bodies[start])) continue
                val list = byFirst[key] ?: continue
                for (e in list) {
                    if (matches(e, bodies, tones, start, len, true)) {
                        if (best == null || e.freq > best.freq) best = e
                        break
                    }
                }
            }
            return best
        }
        val list = byFirst[bodies[start]] ?: return null
        for (e in list) if (matches(e, bodies, tones, start, len, prefixLast)) return e
        return null
    }

    /**
     * Collects entries matching the first [k] syllables. [tones] holds ' ' for "not typed".
     * If [prefixLast] the last typed syllable only has to be a prefix of the entry's syllable.
     */
    fun lookup(bodies: List<String>, tones: CharArray, k: Int, prefixLast: Boolean, cap: Int, out: ArrayList<DictEntry>) {
        val lists = ArrayList<ArrayList<DictEntry>>()
        if (k == 1 && prefixLast) {
            for (key in firstKeys) if (key.startsWith(bodies[0])) byFirst[key]?.let { lists.add(it) }
        } else {
            byFirst[bodies[0]]?.let { lists.add(it) }
        }
        val gathered = ArrayList<DictEntry>()
        for (list in lists) {
            var taken = 0
            for (e in list) {
                if (e.sy.size != k) continue
                var ok = true
                for (i in 0 until k) {
                    val body = e.sy[i]
                    if (i == k - 1 && prefixLast) {
                        if (!body.startsWith(bodies[i])) { ok = false; break }
                    } else if (body != bodies[i]) { ok = false; break }
                    if (tones[i] != ' ' && tones[i] != e.tones[i]) { ok = false; break }
                }
                if (ok) {
                    gathered.add(e)
                    taken++
                    if (taken >= cap) break
                }
            }
        }
        if (lists.size > 1) gathered.sortByDescending { it.freq }
        for (e in gathered) {
            out.add(e)
            if (out.size >= cap) break
        }
    }

    /** Words that continue after [ch] (suffix part only), most frequent first. */
    fun assoc(ch: Char, limit: Int): List<String> {
        val list = byFirstChar[ch] ?: return emptyList()
        val seen = HashSet<String>()
        val res = ArrayList<String>()
        for (e in list) {
            val suf = e.text.substring(1)
            if (suf.length > 3) continue
            if (seen.add(suf)) {
                res.add(suf)
                if (res.size >= limit) break
            }
        }
        return res
    }

    companion object {
        private const val TONE_MARKS = "ˊˇˋ˙"

        fun load(ctx: Context): ZhuyinDict {
            val byFirst = HashMap<String, ArrayList<DictEntry>>(4096)
            val byChar = HashMap<Char, ArrayList<DictEntry>>(8192)
            val pool = HashMap<String, String>(8192)
            var totalChars = 0.0
            ctx.assets.open("zhuyin.txt").use { ins ->
                BufferedReader(InputStreamReader(ins, "UTF-8"), 1 shl 16).use { br ->
                    while (true) {
                        val line = br.readLine() ?: break
                        val a = line.indexOf('\t')
                        if (a < 0) continue
                        val b = line.indexOf('\t', a + 1)
                        if (b < 0) continue
                        val reading = line.substring(0, a)
                        val text = line.substring(a + 1, b)
                        val freq = line.substring(b + 1).toIntOrNull() ?: 0
                        val parts = reading.split(' ')
                        val sy = Array(parts.size) { "" }
                        val tn = StringBuilder()
                        for (i in parts.indices) {
                            val s = parts[i]
                            val last = s[s.length - 1]
                            val ti = TONE_MARKS.indexOf(last)
                            val body = if (ti >= 0) s.substring(0, s.length - 1) else s
                            tn.append(if (ti >= 0) ('2' + ti) else '1')
                            sy[i] = pool.getOrPut(body) { body }
                        }
                        val e = DictEntry(text, sy, tn.toString(), freq)
                        if (text.length == 1) totalChars += freq
                        byFirst.getOrPut(sy[0]) { ArrayList() }.add(e)
                        if (text.length >= 2 && freq > 0) byChar.getOrPut(text[0]) { ArrayList() }.add(e)
                    }
                }
            }
            return ZhuyinDict(byFirst, byFirst.keys.toList(), byChar, ln(max(totalChars, 1.0)))
        }
    }
}

/** Loads the dictionary once per process in a background thread. */
object ZhuyinDictHolder {
    @Volatile private var dict: ZhuyinDict? = null
    @Volatile private var loading = false

    fun ensure(ctx: Context) {
        if (dict != null || loading) return
        loading = true
        val app = ctx.applicationContext
        Thread {
            try {
                dict = ZhuyinDict.load(app)
            } catch (e: Exception) {
                // dictionary missing: composing still works, only candidates are empty
            }
            loading = false
        }.start()
    }

    fun get(): ZhuyinDict? = dict
}

/** State of the syllable being typed with the Dachen (大千) keyboard. */
class ZhuyinComposer {
    private class Syl(var body: String, var tone: Char = ' ')

    private val sylls = ArrayList<Syl>()

    fun isEmpty(): Boolean = sylls.isEmpty()
    fun size(): Int = sylls.size
    fun clear() = sylls.clear()

    private fun cls(c: Char): Int = when {
        c in 'ㄅ'..'ㄙ' -> 1   // initials ㄅ..ㄙ
        c in 'ㄧ'..'ㄩ' -> 2   // medials ㄧㄨㄩ
        else -> 3                      // finals ㄚ..ㄦ
    }

    private fun toneOf(c: Char): Char = when (c) {
        'ˊ' -> '2'
        'ˇ' -> '3'
        'ˋ' -> '4'
        '˙' -> '5'
        else -> ' '
    }

    private fun markOf(t: Char): String = when (t) {
        '2' -> "ˊ"
        '3' -> "ˇ"
        '4' -> "ˋ"
        '5' -> "˙"
        else -> ""
    }

    fun typeSymbol(c: Char) {
        val tone = toneOf(c)
        if (tone != ' ') {
            val last = sylls.lastOrNull() ?: return
            if (last.tone == ' ' && last.body.isNotEmpty()) last.tone = tone
            return
        }
        val cur = sylls.lastOrNull()
        if (cur != null && cur.tone == ' ' && cls(c) > cls(cur.body[cur.body.length - 1])) {
            cur.body += c
        } else {
            sylls.add(Syl(c.toString()))
        }
    }

    /** Space after an open syllable means first tone. Returns false if nothing to mark. */
    fun markFirstTone(): Boolean {
        val last = sylls.lastOrNull() ?: return false
        if (last.tone != ' ') return false
        last.tone = '1'
        return true
    }

    fun backspace() {
        val last = sylls.lastOrNull() ?: return
        if (last.tone != ' ') {
            last.tone = ' '
        } else {
            last.body = last.body.substring(0, last.body.length - 1)
            if (last.body.isEmpty()) sylls.removeAt(sylls.size - 1)
        }
    }

    fun consume(n: Int) {
        var i = 0
        while (i < n && sylls.isNotEmpty()) {
            sylls.removeAt(0)
            i++
        }
    }

    fun display(): String {
        val sb = StringBuilder()
        for (s in sylls) {
            sb.append(s.body)
            sb.append(markOf(s.tone))
        }
        return sb.toString()
    }

    /** Best whole-sentence reading for every prefix length (index j = first j syllables). */
    private fun sentences(dict: ZhuyinDict, bodies: List<String>, tones: CharArray): Array<String?> {
        val n = bodies.size
        val dp = DoubleArray(n + 1) { -1e18 }
        val back = IntArray(n + 1)
        val word = arrayOfNulls<String>(n + 1)
        dp[0] = 0.0
        val maxL = 6
        for (i in 0 until n) {
            if (dp[i] < -1e17) continue
            for (len in 1..min(maxL, n - i)) {
                val prefix = (i + len == n && tones[n - 1] == ' ')
                val e = dict.bestWord(bodies, tones, i, len, prefix)
                val text: String
                val sc: Double
                if (e == null) {
                    if (len > 1) continue
                    text = bodies[i] + markOf(tones[i])
                    sc = -30.0
                } else {
                    text = e.text
                    sc = ln(e.freq + 1.0) - dict.logTotal
                }
                if (dp[i] + sc > dp[i + len]) {
                    dp[i + len] = dp[i] + sc
                    back[i + len] = i
                    word[i + len] = text
                }
            }
        }
        val out = arrayOfNulls<String>(n + 1)
        for (j in 1..n) {
            if (dp[j] < -1e17) continue
            val sb = ArrayList<String>()
            var k = j
            while (k > 0) {
                sb.add(word[k] ?: "")
                k = back[k]
            }
            sb.reverse()
            out[j] = sb.joinToString("")
        }
        return out
    }

    fun candidates(dict: ZhuyinDict?): List<Cand> {
        val out = ArrayList<Cand>()
        val n = sylls.size
        if (n == 0) return out
        if (dict != null) {
            val bodies = sylls.map { it.body }
            val tones = CharArray(n) { sylls[it].tone }
            val seen = HashSet<String>()
            val sent = if (n >= 2) sentences(dict, bodies, tones) else arrayOfNulls<String>(n + 1)
            for (k in n downTo 1) {
                if (k >= 2) {
                    val st = sent[k]
                    if (st != null && seen.add(st)) out.add(Cand(st, k))
                }
                if (k <= 8) {
                    val entries = ArrayList<DictEntry>()
                    val prefix = (k == n && sylls[n - 1].tone == ' ')
                    dict.lookup(bodies, tones, k, prefix, if (k == n) 60 else 16, entries)
                    for (e in entries) if (seen.add(e.text)) out.add(Cand(e.text, k))
                }
                if (out.size >= 120) break
            }
        }
        out.add(Cand(display(), n))
        return out
    }
}

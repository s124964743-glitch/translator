package com.kala.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.random.Random

/**
 * Plays real keyboard recordings (sliced per key) with a SoundPool.
 * Letter keys pick randomly among several variants so repeated typing does not sound robotic.
 */
class SoundEngine(private val ctx: Context) {
    private var pool: SoundPool? = null
    private val downs = ArrayList<Int>()
    private val ups = ArrayList<Int>()
    private val single = HashMap<String, Int>()
    private var loadedSwitch: String? = null
    private var lastDown = -1

    fun load(switchId: String) {
        if (loadedSwitch == switchId && pool != null) return
        release()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(14).setAudioAttributes(attrs).build()
        for (i in 0 until 6) {
            val r = res(switchId + "_d" + i)
            if (r != 0) downs.add(p.load(ctx, r, 1))
        }
        for (i in 0 until 4) {
            val r = res(switchId + "_u" + i)
            if (r != 0) ups.add(p.load(ctx, r, 1))
        }
        for (k in arrayOf("space", "enter", "back")) {
            val r = res(switchId + "_" + k)
            if (r != 0) single[k] = p.load(ctx, r, 1)
        }
        pool = p
        loadedSwitch = switchId
    }

    private fun res(name: String): Int = ctx.resources.getIdentifier(name, "raw", ctx.packageName)

    /** kind: down, up, space, enter, back. volume: 0..100 */
    fun play(kind: String, volume: Int) {
        val p = pool ?: return
        val id: Int = when (kind) {
            "down" -> {
                if (downs.isEmpty()) return
                var i = Random.nextInt(downs.size)
                if (downs.size > 1 && i == lastDown) i = (i + 1) % downs.size
                lastDown = i
                downs[i]
            }
            "up" -> {
                if (ups.isEmpty()) return
                ups[Random.nextInt(ups.size)]
            }
            else -> single[kind] ?: return
        }
        val v = volume.coerceIn(0, 100) / 100f
        val rate = 0.985f + Random.nextFloat() * 0.03f
        p.play(id, v, v, 1, 0, rate)
    }

    fun release() {
        pool?.release()
        pool = null
        downs.clear()
        ups.clear()
        single.clear()
        loadedSwitch = null
        lastDown = -1
    }
}

package com.kala.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.random.Random

/** Plays the mechanical switch sounds with a SoundPool (low latency, many overlapping voices). */
class SoundEngine(private val ctx: Context) {
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private var loadedSwitch: String? = null

    fun load(switchId: String) {
        if (loadedSwitch == switchId && pool != null) return
        release()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(12).setAudioAttributes(attrs).build()
        for (kind in KINDS) {
            val res = ctx.resources.getIdentifier(switchId + "_" + kind, "raw", ctx.packageName)
            if (res != 0) ids[kind] = p.load(ctx, res, 1)
        }
        pool = p
        loadedSwitch = switchId
    }

    /** kind: down, up, space, enter, back. volume: 0..100 */
    fun play(kind: String, volume: Int) {
        val p = pool ?: return
        val id = ids[kind] ?: return
        val v = (volume.coerceIn(0, 100) / 100f)
        val vol = if (kind == "up") v * 0.8f else v
        val rate = 0.96f + Random.nextFloat() * 0.08f
        p.play(id, vol, vol, 1, 0, rate)
    }

    fun release() {
        pool?.release()
        pool = null
        ids.clear()
        loadedSwitch = null
    }

    companion object {
        val KINDS = arrayOf("down", "up", "space", "enter", "back")
    }
}

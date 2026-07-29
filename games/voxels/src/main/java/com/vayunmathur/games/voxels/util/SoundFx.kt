package com.vayunmathur.games.voxels.util

import android.content.Context
import android.media.SoundPool
import java.io.File
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

// Lightweight procedural SFX: synthesizes short break/place blips into cached WAVs and plays them via
// SoundPool. No audio assets required. All calls are best-effort (never throw into the UI).
object SoundFx {
    private const val SR = 22050
    private var pool: SoundPool? = null
    private var breakId = 0
    private var placeId = 0

    fun init(ctx: Context) {
        if (pool != null) return
        try {
            val p = SoundPool.Builder().setMaxStreams(4).build()
            breakId = p.load(wav(ctx, "vox_break.wav", gen(150.0, 0.14, 0.55, true)), 1)
            placeId = p.load(wav(ctx, "vox_place.wav", gen(520.0, 0.07, 0.4, false)), 1)
            pool = p
        } catch (_: Throwable) {}
    }

    fun playBreak() { try { pool?.play(breakId, 0.6f, 0.6f, 1, 0, 1f) } catch (_: Throwable) {} }
    fun playPlace() { try { pool?.play(placeId, 0.6f, 0.6f, 1, 0, 1f) } catch (_: Throwable) {} }

    // Decaying tone (or noise burst); mono 16-bit PCM.
    private fun gen(freq: Double, dur: Double, amp: Double, noise: Boolean): ShortArray {
        val n = (SR * dur).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = exp(-t * 20.0)
            val s = if (noise) (Random.nextDouble() * 2.0 - 1.0) else sin(2.0 * Math.PI * freq * t)
            out[i] = (s * env * amp * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun wav(ctx: Context, name: String, pcm: ShortArray): String {
        val f = File(ctx.cacheDir, name)
        val dataLen = pcm.size * 2
        val bytes = ByteArray(44 + dataLen)
        fun le32(off: Int, v: Int) { bytes[off]=(v and 0xff).toByte(); bytes[off+1]=((v shr 8) and 0xff).toByte(); bytes[off+2]=((v shr 16) and 0xff).toByte(); bytes[off+3]=((v shr 24) and 0xff).toByte() }
        fun le16(off: Int, v: Int) { bytes[off]=(v and 0xff).toByte(); bytes[off+1]=((v shr 8) and 0xff).toByte() }
        "RIFF".toByteArray().copyInto(bytes, 0)
        le32(4, 36 + dataLen)
        "WAVE".toByteArray().copyInto(bytes, 8)
        "fmt ".toByteArray().copyInto(bytes, 12)
        le32(16, 16); le16(20, 1); le16(22, 1)
        le32(24, SR); le32(28, SR * 2); le16(32, 2); le16(34, 16)
        "data".toByteArray().copyInto(bytes, 36)
        le32(40, dataLen)
        for (i in pcm.indices) le16(44 + i * 2, pcm[i].toInt())
        f.writeBytes(bytes)
        return f.absolutePath
    }
}

package com.yoshikan.walkman

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Sonidos mecánicos generados en código (no requiere archivos). */
class Sounds(context: Context) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val clackId: Int
    private val tickId: Int
    private val ejectId: Int
    private val switchId: Int

    init {
        val dir = context.cacheDir
        clackId = pool.load(write(dir, "clack.wav", clack(0.06, 110.0, 1.0)), 1)
        tickId = pool.load(write(dir, "tick.wav", clack(0.025, 220.0, 0.5)), 1)
        switchId = pool.load(write(dir, "switch.wav", clack(0.03, 600.0, 0.45)), 1)
        ejectId = pool.load(write(dir, "eject.wav", eject()), 1)
    }

    fun clack() { pool.play(clackId, 0.9f, 0.9f, 1, 0, 1f) }
    fun tick() { pool.play(tickId, 0.6f, 0.6f, 1, 0, 1f) }
    fun sw() { pool.play(switchId, 0.7f, 0.7f, 1, 0, 1f) }
    fun eject() { pool.play(ejectId, 0.9f, 0.9f, 1, 0, 1f) }

    fun release() { pool.release() }

    private val rate = 22050

    private fun clack(len: Double, thumpHz: Double, gain: Double): ShortArray {
        val n = (rate * len).toInt()
        val out = ShortArray(n)
        val rnd = Random(7)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val noise = (rnd.nextDouble() * 2 - 1) * exp(-t * 160)
            val thump = sin(2 * PI * thumpHz * t) * exp(-t * 70)
            val v = (noise * 0.55 + thump * 0.6) * gain
            out[i] = (v.coerceIn(-1.0, 1.0) * 30000).toInt().toShort()
        }
        return out
    }

    private fun eject(): ShortArray {
        val n = (rate * 0.35).toInt()
        val out = ShortArray(n)
        val rnd = Random(11)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            var v = 0.0
            // primer golpe del seguro
            v += (rnd.nextDouble() * 2 - 1) * exp(-t * 140) * 0.7
            // resorte de la tapa
            v += sin(2 * PI * (900 + 400 * t) * t) * exp(-t * 12) * 0.12
            // tope al abrirse
            val t2 = t - 0.18
            if (t2 > 0) v += (rnd.nextDouble() * 2 - 1) * exp(-t2 * 120) * 0.6 + sin(2 * PI * 140 * t2) * exp(-t2 * 50) * 0.5
            out[i] = (v.coerceIn(-1.0, 1.0) * 30000).toInt().toShort()
        }
        return out
    }

    private fun write(dir: File, name: String, pcm: ShortArray): String {
        val f = File(dir, name)
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(rate); bb.putInt(rate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        f.writeBytes(bb.array())
        return f.absolutePath
    }
}

package com.yoshikan.walkman

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Sonidos mecánicos sintetizados en código, distintos para cada aparato. */
class Sounds(context: Context) {

    enum class Ev { KEY_DOWN, KEY_UP, PLAY, PAUSE, STOP, EJECT, NEXT, PREV, SWITCH }

    var kind = SoundKind.TAPE
    var muted = false
        set(v) { field = v; if (v) { stopScan(); stopAmbient() } }

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()
    private val ids = HashMap<String, Int>()
    private val handler = Handler(Looper.getMainLooper())
    private var scanStream = 0
    private var ambStream = 0
    private var ambType: Ambient? = null
    private val R = 22050

    init {
        val dir = context.cacheDir
        val gen: Map<String, () -> FloatArray> = mapOf(
            "clack" to { click(0.07, 110.0, 160.0, 70.0, 0.55, 0.6) },
            "tick" to { click(0.03, 220.0, 220.0, 120.0, 0.3, 0.25) },
            "tunk" to { click(0.10, 70.0, 120.0, 35.0, 0.45, 0.8) },
            "softclick" to { click(0.03, 1400.0, 300.0, 200.0, 0.35, 0.2) },
            "bigclick" to { click(0.09, 170.0, 80.0, 45.0, 0.8, 0.6) },
            "motor" to { motor() },
            "spring" to { spring() },
            "zip" to { zip() },
            "whine" to { whineLoop() },
            "armclick" to { click(0.04, 900.0, 260.0, 160.0, 0.35, 0.25) },
            "needle" to { needle() },
            "armlift" to { armLift() },
            "cdwhir" to { cdWhir() },
            "servo" to { servo() },
            "seek" to { seekLoop() },
            "lid" to { lid() },
            "tubewarm" to { tubeWarm() },
            "static" to { staticBurst() },
            "staticloop" to { staticLoop() },
            "jukemech" to { jukeMech() },
            "selector" to { selector() },
            "eightclunk" to { click(0.14, 60.0, 70.0, 25.0, 0.7, 0.9) },
            "hiss" to { hissLoop() },
            "crackle" to { crackleLoop() },
            "hum" to { humLoop() }
        )
        for ((name, f) in gen) ids[name] = pool.load(write(dir, "$name.wav", f()), 1)
    }

    // --------- tabla de sonidos por aparato ---------
    private fun seq(ev: Ev): List<Pair<String, Long>> = when (kind) {
        SoundKind.TAPE -> when (ev) {
            Ev.KEY_DOWN -> listOf("clack" to 0L)
            Ev.KEY_UP -> listOf("tick" to 0L)
            Ev.PLAY -> listOf("motor" to 60L)
            Ev.PAUSE -> listOf("tunk" to 0L)
            Ev.STOP -> listOf("tunk" to 0L)
            Ev.EJECT -> listOf("spring" to 40L)
            Ev.NEXT, Ev.PREV -> listOf("zip" to 0L)
            Ev.SWITCH -> listOf("softclick" to 0L)
        }
        SoundKind.EIGHT -> when (ev) {
            Ev.KEY_DOWN -> listOf("clack" to 0L)
            Ev.KEY_UP -> emptyList()
            Ev.PLAY -> listOf("eightclunk" to 0L, "motor" to 120L)
            Ev.PAUSE, Ev.STOP -> listOf("tunk" to 0L)
            Ev.EJECT -> listOf("eightclunk" to 0L, "spring" to 90L)
            Ev.NEXT, Ev.PREV -> listOf("eightclunk" to 0L)
            Ev.SWITCH -> listOf("softclick" to 0L)
        }
        SoundKind.VINYL -> when (ev) {
            Ev.KEY_DOWN -> listOf("softclick" to 0L)
            Ev.KEY_UP -> emptyList()
            Ev.PLAY -> listOf("armclick" to 0L, "needle" to 420L)
            Ev.PAUSE, Ev.STOP, Ev.EJECT -> listOf("armlift" to 0L)
            Ev.NEXT, Ev.PREV -> listOf("armlift" to 0L, "needle" to 380L)
            Ev.SWITCH -> listOf("softclick" to 0L)
        }
        SoundKind.JUKEBOX -> when (ev) {
            Ev.KEY_DOWN -> listOf("selector" to 0L)
            Ev.KEY_UP -> emptyList()
            Ev.PLAY -> listOf("jukemech" to 0L, "needle" to 900L)
            Ev.PAUSE, Ev.STOP -> listOf("armlift" to 0L)
            Ev.EJECT -> listOf("jukemech" to 0L)
            Ev.NEXT, Ev.PREV -> listOf("jukemech" to 0L, "needle" to 900L)
            Ev.SWITCH -> listOf("selector" to 0L)
        }
        SoundKind.CD -> when (ev) {
            Ev.KEY_DOWN -> listOf("softclick" to 0L)
            Ev.KEY_UP -> emptyList()
            Ev.PLAY -> listOf("cdwhir" to 0L)
            Ev.PAUSE, Ev.STOP -> listOf("servo" to 0L)
            Ev.EJECT -> listOf("lid" to 0L)
            Ev.NEXT, Ev.PREV -> listOf("servo" to 0L)
            Ev.SWITCH -> listOf("softclick" to 0L)
        }
        SoundKind.RADIO -> when (ev) {
            Ev.KEY_DOWN -> listOf("bigclick" to 0L)
            Ev.KEY_UP -> emptyList()
            Ev.PLAY -> listOf("tubewarm" to 80L)
            Ev.PAUSE, Ev.STOP, Ev.EJECT -> emptyList()
            Ev.NEXT, Ev.PREV -> listOf("static" to 0L)
            Ev.SWITCH -> listOf("bigclick" to 0L)
        }
    }

    fun play(ev: Ev) {
        if (muted) return
        for ((name, delay) in seq(ev)) {
            val id = ids[name] ?: continue
            if (delay == 0L) pool.play(id, 0.9f, 0.9f, 1, 0, 1f)
            else handler.postDelayed({ if (!muted) pool.play(id, 0.9f, 0.9f, 1, 0, 1f) }, delay)
        }
    }

    fun startScan() {
        if (muted) return
        stopScan()
        val name = when (kind) {
            SoundKind.TAPE, SoundKind.EIGHT -> "whine"
            SoundKind.CD -> "seek"
            SoundKind.RADIO -> "staticloop"
            else -> return
        }
        val id = ids[name] ?: return
        scanStream = pool.play(id, 0.6f, 0.6f, 2, -1, 1f)
    }

    fun stopScan() {
        if (scanStream != 0) pool.stop(scanStream)
        scanStream = 0
    }

    /** Ambiente de fondo (siseo de cinta, crujido de vinilo, zumbido de bulbos). */
    fun setAmbient(type: Ambient, on: Boolean) {
        if (!on || muted) { stopAmbient(); return }
        if (ambStream != 0 && ambType == type) return
        stopAmbient()
        val name = when (type) { Ambient.HISS -> "hiss"; Ambient.CRACKLE -> "crackle"; Ambient.HUM -> "hum" }
        val id = ids[name] ?: return
        ambStream = pool.play(id, 0.35f, 0.35f, 0, -1, 1f)
        ambType = type
    }

    fun stopAmbient() {
        if (ambStream != 0) pool.stop(ambStream)
        ambStream = 0
        ambType = null
    }

    fun release() { pool.release() }

    // ================= síntesis =================

    private fun buf(sec: Double) = FloatArray((R * sec).toInt())
    private fun t(i: Int) = i.toDouble() / R
    private fun noise(rnd: Random) = rnd.nextDouble() * 2 - 1

    private fun click(len: Double, hz: Double, nDecay: Double, tDecay: Double, nGain: Double, tGain: Double): FloatArray {
        val b = buf(len); val rnd = Random(hz.toInt())
        for (i in b.indices) {
            val x = t(i)
            b[i] = (noise(rnd) * exp(-x * nDecay) * nGain + sin(2 * PI * hz * x) * exp(-x * tDecay) * tGain).toFloat()
        }
        return fade(b, 0.01)
    }

    private fun motor(): FloatArray {
        val b = buf(0.5); val rnd = Random(3); var ph = 0.0
        for (i in b.indices) {
            val x = t(i)
            val f = 45.0 + 55.0 * (1 - exp(-x * 8))
            ph += 2 * PI * f / R
            val env = (x / 0.05).coerceAtMost(1.0) * (if (x > 0.35) exp(-(x - 0.35) * 18) else 1.0)
            b[i] = ((sin(ph) * 0.35 + sin(ph * 2) * 0.2 + sin(ph * 3) * 0.1 + noise(rnd) * 0.06) * env).toFloat()
        }
        return b
    }

    private fun spring(): FloatArray {
        val b = buf(0.35); val rnd = Random(11)
        for (i in b.indices) {
            val x = t(i)
            var v = noise(rnd) * exp(-x * 140) * 0.7
            v += sin(2 * PI * (900 + 400 * x) * x) * exp(-x * 12) * 0.12
            val x2 = x - 0.18
            if (x2 > 0) v += noise(rnd) * exp(-x2 * 120) * 0.6 + sin(2 * PI * 140 * x2) * exp(-x2 * 50) * 0.5
            b[i] = v.toFloat()
        }
        return b
    }

    private fun zip(): FloatArray {
        val b = buf(0.22); val rnd = Random(5); var ph = 0.0
        for (i in b.indices) {
            val x = t(i)
            ph += 2 * PI * (2600 - 1800 * x / 0.22) / R
            val env = (x / 0.02).coerceAtMost(1.0) * exp(-x * 9)
            b[i] = ((sin(ph) * 0.22 + noise(rnd) * 0.12) * env).toFloat()
        }
        return b
    }

    private fun whineLoop(): FloatArray {
        val b = buf(1.0); val rnd = Random(9)
        var lp = 0.0
        for (i in b.indices) {
            val x = t(i)
            val ph = 2 * PI * 1800 * x - (500.0 / 6.0) * cos(2 * PI * 6 * x)
            lp += 0.2 * (noise(rnd) - lp)
            b[i] = (sin(ph) * 0.14 + lp * 0.25).toFloat()
        }
        return b
    }

    private fun needle(): FloatArray {
        val b = buf(0.6); val rnd = Random(13)
        for (i in b.indices) {
            val x = t(i)
            var v = sin(2 * PI * 55 * x) * exp(-x * 22) * 0.8 + noise(rnd) * exp(-x * 60) * 0.4
            if (rnd.nextDouble() < 0.004) v += noise(rnd) * 0.7 * exp(-x * 3)
            v += noise(rnd) * 0.04 * exp(-x * 4)
            b[i] = v.toFloat()
        }
        return b
    }

    private fun armLift(): FloatArray {
        val b = buf(0.18); val rnd = Random(17)
        for (i in b.indices) {
            val x = t(i)
            b[i] = (noise(rnd) * exp(-x * 90) * 0.35 + sin(2 * PI * 320 * x) * exp(-x * 60) * 0.2 + noise(rnd) * 0.05 * exp(-x * 15)).toFloat()
        }
        return b
    }

    private fun cdWhir(): FloatArray {
        val b = buf(0.8); val rnd = Random(19); var ph = 0.0
        for (i in b.indices) {
            val x = t(i)
            val f = 150 * 6.0.pow(x / 0.8)
            ph += 2 * PI * f / R
            val env = (x / 0.05).coerceAtMost(1.0) * (if (x > 0.6) exp(-(x - 0.6) * 15) else 1.0)
            var v = sin(ph) * 0.12 + noise(rnd) * 0.03
            if (x < 0.25 && (x * 40).toInt() % 3 == 0) v += sin(2 * PI * 120 * x) * 0.15
            b[i] = (v * env).toFloat()
        }
        return b
    }

    private fun servo(): FloatArray {
        val b = buf(0.22)
        for (i in b.indices) {
            val x = t(i)
            val sq = if (sin(2 * PI * 140 * x) > 0) 1.0 else -1.0
            val gate = if ((x * 30).toInt() % 2 == 0) 1.0 else 0.3
            b[i] = (sq * 0.08 * gate * exp(-x * 8)).toFloat()
        }
        return fade(b, 0.02)
    }

    private fun seekLoop(): FloatArray {
        val b = buf(0.5)
        for (i in b.indices) {
            val x = t(i)
            val sq = if (sin(2 * PI * 160 * x) > 0) 1.0 else -1.0
            val ph = (x * 8) % 1.0
            b[i] = (sq * 0.07 * exp(-ph * 6)).toFloat()
        }
        return b
    }

    private fun lid(): FloatArray {
        val a = click(0.06, 800.0, 200.0, 120.0, 0.45, 0.25)
        val b = buf(0.30)
        for (i in a.indices) b[i] += a[i]
        val sp = spring()
        val off = R / 20
        for (i in sp.indices) if (i + off < b.size) b[i + off] += sp[i] * 0.5f
        return b
    }

    private fun tubeWarm(): FloatArray {
        val b = buf(1.3); val rnd = Random(23); var lp = 0.0
        for (i in b.indices) {
            val x = t(i)
            val up = x.coerceAtMost(1.0)
            lp += 0.08 * (noise(rnd) - lp)
            val stat = lp * 0.6 * sin(PI * (x / 1.3)).coerceAtLeast(0.0)
            val hum = (sin(2 * PI * 60 * x) * 0.18 + sin(2 * PI * 120 * x) * 0.08) * up
            b[i] = ((stat + hum) * (if (x > 1.1) exp(-(x - 1.1) * 12) else 1.0)).toFloat()
        }
        return b
    }

    private fun staticBurst(): FloatArray {
        val b = buf(0.45); val rnd = Random(29); var ph = 0.0; var lp = 0.0
        for (i in b.indices) {
            val x = t(i)
            ph += 2 * PI * (3000 - 2400 * x / 0.45) / R
            lp += 0.3 * (noise(rnd) - lp)
            val env = (x / 0.02).coerceAtMost(1.0) * exp(-x * 5)
            b[i] = ((lp * 0.4 + sin(ph) * 0.08) * env).toFloat()
        }
        return b
    }

    private fun staticLoop(): FloatArray {
        val b = buf(1.0); val rnd = Random(31); var lp = 0.0
        for (i in b.indices) {
            val x = t(i)
            lp += 0.3 * (noise(rnd) - lp)
            b[i] = (lp * 0.3 + sin(2 * PI * 1200 * x + 4 * sin(2 * PI * 3 * x)) * 0.05).toFloat()
        }
        return b
    }

    private fun jukeMech(): FloatArray {
        val b = buf(1.0); val rnd = Random(37); var ph = 0.0
        for (i in b.indices) {
            val x = t(i)
            ph += 2 * PI * 70 / R
            var v = 0.0
            if (x < 0.75) v += (sin(ph) * 0.15 + sin(ph * 3) * 0.06 + noise(rnd) * 0.04) * (x / 0.05).coerceAtMost(1.0)
            for (gx in doubleArrayOf(0.12, 0.30, 0.48, 0.66)) {
                val dd = x - gx
                if (dd > 0) v += noise(rnd) * exp(-dd * 150) * 0.4
            }
            val d2 = x - 0.80
            if (d2 > 0) v += sin(2 * PI * 80 * d2) * exp(-d2 * 30) * 0.7 + noise(rnd) * exp(-d2 * 90) * 0.4
            b[i] = v.toFloat()
        }
        return b
    }

    private fun selector(): FloatArray {
        val a = click(0.06, 220.0, 100.0, 50.0, 0.6, 0.5)
        val b = buf(0.12)
        val off = R * 3 / 100
        for (i in a.indices) {
            b[i] += a[i]
            if (i + off < b.size) b[i + off] += a[i] * 0.7f
        }
        return b
    }

    private fun hissLoop(): FloatArray {
        val b = buf(2.0); val rnd = Random(41); var prev = 0.0
        for (i in b.indices) {
            val n = noise(rnd)
            b[i] = ((n - prev) * 0.05).toFloat()
            prev = n
        }
        return b
    }

    private fun crackleLoop(): FloatArray {
        val b = buf(2.0); val rnd = Random(43); var lp = 0.0
        var pop = 0.0
        for (i in b.indices) {
            lp += 0.05 * (noise(rnd) - lp)
            if (rnd.nextDouble() < 0.0004) pop = (rnd.nextDouble() * 0.5 + 0.2) * (if (rnd.nextBoolean()) 1.0 else -1.0)
            if (rnd.nextDouble() < 0.003) pop = (rnd.nextDouble() * 0.12) * (if (rnd.nextBoolean()) 1.0 else -1.0)
            pop *= 0.82
            b[i] = (lp * 0.06 + pop).toFloat()
        }
        return b
    }

    private fun humLoop(): FloatArray {
        val b = buf(2.0); val rnd = Random(47)
        for (i in b.indices) {
            val x = t(i)
            b[i] = (sin(2 * PI * 60 * x) * 0.06 + sin(2 * PI * 120 * x) * 0.03 + sin(2 * PI * 180 * x) * 0.015 + noise(rnd) * 0.012).toFloat()
        }
        return b
    }

    private fun fade(b: FloatArray, sec: Double): FloatArray {
        val n = (R * sec).toInt().coerceAtMost(b.size)
        for (i in 0 until n) b[b.size - 1 - i] *= i.toFloat() / n
        return b
    }

    private fun write(dir: File, name: String, data: FloatArray): String {
        val f = File(dir, name)
        val dataLen = data.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(R); bb.putInt(R * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        for (s in data) bb.putShort((s.coerceIn(-1f, 1f) * 30000).toInt().toShort())
        f.writeBytes(bb.array())
        return f.absolutePath
    }
}

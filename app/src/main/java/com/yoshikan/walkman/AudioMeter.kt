package com.yoshikan.walkman

import android.media.audiofx.Visualizer
import android.os.SystemClock
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Lee el nivel real del audio que suena en el teléfono (requiere permiso de micrófono; no graba). */
class AudioMeter {
    private var vis: Visualizer? = null
    @Volatile var level = 0f
    @Volatile var levelL = 0f
    @Volatile var levelR = 0f
    val bands = FloatArray(10)
    @Volatile private var lastSignal = 0L
    var running = false
        private set

    fun start(): Boolean {
        if (running) return true
        return try {
            val v = Visualizer(0)
            v.setEnabled(false)
            v.setCaptureSize(Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024))
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer?, wave: ByteArray?, rate: Int) {
                    val w = wave ?: return
                    var sum = 0.0
                    var sumL = 0.0
                    var sumR = 0.0
                    for (i in w.indices) {
                        val s = ((w[i].toInt() and 0xFF) - 128) / 128.0
                        sum += s * s
                        if (i % 2 == 0) sumL += s * s else sumR += s * s
                    }
                    val rms = sqrt(sum / w.size)
                    if (rms > 0.01) lastSignal = SystemClock.uptimeMillis()
                    level = (rms * 3.2).toFloat().coerceIn(0f, 1f)
                    levelL = (sqrt(sumL / (w.size / 2)) * 3.2).toFloat().coerceIn(0f, 1f)
                    levelR = (sqrt(sumR / (w.size / 2)) * 3.2).toFloat().coerceIn(0f, 1f)
                }

                override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, rate: Int) {
                    val f = fft ?: return
                    val n = f.size / 2
                    if (n < 16) return
                    for (k in 0 until 10) {
                        val a = (n.toDouble().pow(k / 10.0)).toInt().coerceAtLeast(1)
                        val b = (n.toDouble().pow((k + 1) / 10.0)).toInt().coerceIn(a + 1, n)
                        var m = 0.0
                        for (i in a until b) m += hypot(f[2 * i].toDouble(), f[2 * i + 1].toDouble())
                        m /= (b - a)
                        bands[k] = (log10(1 + m) / 2.0).toFloat().coerceIn(0f, 1f)
                    }
                }
            }, Visualizer.getMaxCaptureRate(), true, true)
            v.setEnabled(true)
            vis = v
            running = true
            true
        } catch (e: Throwable) {
            running = false
            false
        }
    }

    /** true si en los últimos 3 s llegó sonido real (si no, se usa la animación de respaldo). */
    fun alive() = running && SystemClock.uptimeMillis() - lastSignal < 3000

    fun stop() {
        try { vis?.setEnabled(false); vis?.release() } catch (e: Throwable) {}
        vis = null
        running = false
    }
}

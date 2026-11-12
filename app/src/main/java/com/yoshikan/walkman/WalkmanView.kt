package com.yoshikan.walkman

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Reproductor de casete dibujado a mano con Canvas.
 * Teclas: 0 REW, 1 FF, 2 PLAY, 3 STOP/EJECT, 4 PAUSE.
 * Interruptores: 0 MEGA BASS, 1 AUTO REVERSE (repetir), 2 SHUFFLE.
 */
class WalkmanView(context: Context) : View(context) {

    interface Listener {
        fun onPlayKey()
        fun onPauseKey()
        fun onStopEjectKey()
        fun onSkip(dir: Int)
        fun onScanStart(dir: Int)
        fun onScanEnd()
        fun onVolume(fraction: Float)
        fun onSwitch(index: Int, on: Boolean)
        fun onCassetteTap()
        fun onKeySound(down: Boolean)
        fun onSwitchSound()
    }

    var listener: Listener? = null

    // ---------- estado del reproductor ----------
    var connected = false
    var isPlaying = false
    var hasTrack = false
    var title = ""
    var artist = ""
    var statusText = "Conectando con Spotify…"
    var art: Bitmap? = null
    private var durationMs = 1L
    private var basePosMs = 0L
    private var baseTime = 0L
    private var volume = 0.5f
    private var volumeShownUntil = 0L
    val switches = booleanArrayOf(false, false, false)
    private val switchAnim = floatArrayOf(0f, 0f, 0f)
    private var counterOffsetSec = 0.0

    // ---------- interacción ----------
    private var pressedKey = -1
    private var scanDir = 0
    private var scanning = false
    private var draggingWheel = false
    private var lastTouchY = 0f
    private var resetPressed = false
    private val longPress = Runnable {
        if (pressedKey == 0 || pressedKey == 1) {
            scanning = true
            scanDir = if (pressedKey == 0) -1 else 1
            listener?.onScanStart(scanDir)
        }
    }

    // ---------- animación ----------
    private var reelAngleL = 0f
    private var reelAngleR = 0f
    private var lastFrame = 0L
    private val keyAnim = FloatArray(5)

    // ---------- geometría ----------
    private val dp = resources.displayMetrics.density
    private var bx = 0f; private var by = 0f; private var bw = 0f; private var bh = 0f; private var keyH = 0f
    private val keyRects = Array(5) { RectF() }
    private val keyHit = Array(5) { RectF() }
    private val door = RectF()
    private val shell = RectF()
    private val label = RectF()
    private val window = RectF()
    private val artRect = RectF()
    private var reelCy = 0f; private var reelLx = 0f; private var reelRx = 0f
    private var hubR = 0f; private var rMin = 0f; private var rMax = 0f
    private val counterRect = RectF()
    private val resetRect = RectF()
    private val switchRects = Array(3) { RectF() }
    private val switchHit = Array(3) { RectF() }
    private val wheelRect = RectF()
    private val wheelHit = RectF()
    private var ledX = 0f; private var ledY = 0f
    private var staticLayer: Bitmap? = null
    private var glassLayer: Bitmap? = null

    // ---------- pinturas ----------
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val hand = Typeface.create("casual", Typeface.BOLD)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val keyNames = arrayOf("REW", "FF", "PLAY", "STOP/EJ", "PAUSE")

    init {
        isHapticFeedbackEnabled = true
    }

    // ================= API pública =================

    fun updatePlayer(playing: Boolean, posMs: Long, durMs: Long, t: String, a: String, shuffle: Boolean, repeat: Boolean) {
        isPlaying = playing
        basePosMs = posMs
        baseTime = SystemClock.uptimeMillis()
        durationMs = if (durMs > 0) durMs else 1L
        hasTrack = t.isNotEmpty()
        title = t
        artist = a
        switches[1] = repeat
        switches[2] = shuffle
        invalidate()
    }

    fun setVolumeFraction(f: Float, show: Boolean) {
        volume = f.coerceIn(0f, 1f)
        if (show) volumeShownUntil = SystemClock.uptimeMillis() + 1500
        invalidate()
    }

    fun setSwitch(i: Int, on: Boolean) {
        switches[i] = on
        invalidate()
    }

    private fun currentPosMs(): Long {
        val pos = if (isPlaying) basePosMs + (SystemClock.uptimeMillis() - baseTime) else basePosMs
        return pos.coerceIn(0L, durationMs)
    }

    // ================= layout =================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        bw = min(w * 0.84f, h * 0.92f / 1.60f)
        keyH = bw * 0.10f
        bh = bw * 1.50f
        bx = (w - bw) / 2f - bw * 0.02f
        by = (h - (keyH + bh)) / 2f + keyH

        // teclas
        val spanL = bx + bw * 0.07f
        val span = bw * 0.84f
        val slot = span / 5f
        val kw = slot * 0.86f
        for (i in 0 until 5) {
            val l = spanL + slot * i + (slot - kw) / 2f
            keyRects[i].set(l, by - keyH, l + kw, by + keyH * 0.4f)
            keyHit[i].set(l - slot * 0.06f, by - keyH - 24 * dp, l + kw + slot * 0.06f, by + bh * 0.06f)
        }

        // tapa y casete
        val doorH = bw * 0.88f * 0.66f
        door.set(bx + bw * 0.06f, by + bh * 0.07f, bx + bw * 0.94f, by + bh * 0.07f + doorH)
        val ins = bw * 0.035f
        shell.set(door.left + ins, door.top + ins, door.right - ins, door.bottom - ins * 0.4f)
        label.set(
            shell.left + shell.width() * 0.05f, shell.top + shell.height() * 0.07f,
            shell.right - shell.width() * 0.05f, shell.bottom - shell.height() * 0.28f
        )
        val winW = shell.width() * 0.54f
        val winH = label.height() * 0.40f
        val winCy = label.top + label.height() * 0.60f
        window.set(shell.centerX() - winW / 2f, winCy - winH / 2f, shell.centerX() + winW / 2f, winCy + winH / 2f)
        val artS = min(window.height() * 1.15f, (window.left - label.left) * 0.80f)
        artRect.set(label.left + label.width() * 0.025f, winCy - artS / 2f, label.left + label.width() * 0.025f + artS, winCy + artS / 2f)
        reelCy = window.centerY()
        reelLx = window.left + window.width() * 0.22f
        reelRx = window.right - window.width() * 0.22f
        hubR = window.height() * 0.27f
        rMin = hubR * 1.2f
        rMax = (reelRx - reelLx) - rMin - hubR * 0.15f

        // panel inferior
        val panelTop = door.bottom + bh * 0.035f
        counterRect.set(bx + bw * 0.08f, panelTop, bx + bw * 0.36f, panelTop + bw * 0.11f)
        val rs = counterRect.height() * 0.55f
        resetRect.set(counterRect.right + bw * 0.04f, counterRect.centerY() - rs / 2f, counterRect.right + bw * 0.04f + rs, counterRect.centerY() + rs / 2f)
        ledX = bx + bw * 0.78f
        ledY = counterRect.centerY() - counterRect.height() * 0.12f

        val swTop = counterRect.bottom + bh * 0.075f
        val swW = bw * 0.17f
        val swH = bw * 0.065f
        val centers = floatArrayOf(0.20f, 0.50f, 0.80f)
        for (i in 0 until 3) {
            val cx = bx + bw * centers[i]
            switchRects[i].set(cx - swW / 2f, swTop, cx + swW / 2f, swTop + swH)
            switchHit[i].set(cx - swW * 0.75f, swTop - swH * 1.4f, cx + swW * 0.75f, swTop + swH * 2.2f)
            switchAnim[i] = if (switches[i]) 1f else 0f
        }

        wheelRect.set(bx + bw * 0.985f, by + bh * 0.50f, bx + bw * 1.06f, by + bh * 0.74f)
        wheelHit.set(bx + bw * 0.93f, wheelRect.top - bh * 0.03f, w.toFloat(), wheelRect.bottom + bh * 0.03f)

        staticLayer?.recycle(); glassLayer?.recycle()
        staticLayer = buildStatic(w, h)
        glassLayer = buildGlass(w, h)
    }

    // ================= capas estáticas =================

    private fun buildStatic(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = bw * 0.06f
        val body = RectF(bx, by, bx + bw, by + bh)

        // sombra sobre la mesa
        p.reset(); p.isAntiAlias = true
        p.color = Color.BLACK
        p.setShadowLayer(bw * 0.05f, 0f, bw * 0.03f, 0xAA000000.toInt())
        c.drawRoundRect(body, r, r, p)
        p.clearShadowLayer()

        // cuerpo metálico cepillado
        p.shader = LinearGradient(bx, by, bx + bw, by + bh,
            intArrayOf(0xFFE4E7EB.toInt(), 0xFFC3C8CF.toInt(), 0xFFAEB4BC.toInt(), 0xFFC9CDD3.toInt()),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(body, r, r, p)
        p.shader = null
        c.save()
        c.clipRect(body)
        val rnd = Random(3)
        p.strokeWidth = 1f
        var yy = by
        while (yy < by + bh) {
            p.color = if (rnd.nextBoolean()) 0x14FFFFFF else 0x10000000
            c.drawLine(bx, yy, bx + bw, yy + rnd.nextFloat() * 1.5f, p)
            yy += 1.5f + rnd.nextFloat() * 2.5f
        }
        c.restore()
        // bisel
        p.style = Paint.Style.STROKE
        p.strokeWidth = bw * 0.012f
        p.color = 0x66FFFFFF
        c.drawRoundRect(RectF(body.left + p.strokeWidth, body.top + p.strokeWidth, body.right - p.strokeWidth, body.bottom - p.strokeWidth), r, r, p)
        p.color = 0x55000000
        p.strokeWidth = 2f
        c.drawRoundRect(body, r, r, p)
        p.style = Paint.Style.FILL

        // ranura de teclas
        p.color = 0xFF2A2C30.toInt()
        c.drawRoundRect(RectF(keyRects[0].left - bw * 0.01f, by - bw * 0.004f, keyRects[4].right + bw * 0.01f, by + bw * 0.012f), 4f, 4f, p)

        // nombres de teclas grabados
        tp.typeface = condensed
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = bw * 0.03f
        for (i in 0 until 5) {
            engrave(c, keyNames[i], keyRects[i].centerX(), by + bh * 0.045f)
        }

        // hueco de la tapa
        p.color = 0xFF3B3E44.toInt()
        val dr = bw * 0.03f
        c.drawRoundRect(RectF(door.left - 4f, door.top - 4f, door.right + 4f, door.bottom + 4f), dr, dr, p)
        p.color = 0xFF16171A.toInt()
        c.drawRoundRect(door, dr, dr, p)

        // contador
        tp.textSize = bw * 0.026f
        engrave(c, "TAPE COUNTER", counterRect.centerX(), counterRect.top - bw * 0.012f)
        engrave(c, "RESET", resetRect.centerX(), counterRect.top - bw * 0.012f)
        p.color = 0xFF1A1B1E.toInt()
        c.drawRoundRect(RectF(counterRect.left - 5f, counterRect.top - 5f, counterRect.right + 5f, counterRect.bottom + 5f), 8f, 8f, p)
        engrave(c, "SPOTIFY", ledX, counterRect.bottom + bw * 0.005f)

        // interruptores
        val swLabels = arrayOf("MEGA BASS", "AUTO REVERSE", "SHUFFLE")
        for (i in 0 until 3) {
            val sr = switchRects[i]
            tp.textSize = bw * 0.03f
            engrave(c, swLabels[i], sr.centerX(), sr.top - bw * 0.02f)
            tp.textSize = bw * 0.022f
            engrave(c, "OFF", sr.left + sr.width() * 0.2f, sr.bottom + bw * 0.035f)
            engrave(c, "ON", sr.right - sr.width() * 0.2f, sr.bottom + bw * 0.035f)
            p.color = 0xFF202226.toInt()
            c.drawRoundRect(sr, sr.height() / 2f, sr.height() / 2f, p)
        }

        // leyenda
        tp.textSize = bw * 0.045f
        tp.typeface = condensed
        engrave(c, "STEREO CASSETTE PLAYER", bx + bw * 0.5f, by + bh * 0.755f)

        // banda azul inferior
        val band = RectF(bx, by + bh * 0.80f, bx + bw, by + bh * 0.90f)
        p.shader = LinearGradient(0f, band.top, 0f, band.bottom, 0xFF2A4C86.toInt(), 0xFF162B52.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(band, p)
        p.shader = null
        tp.color = 0xFFE9ECF1.toInt()
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = band.height() * 0.62f
        tp.typeface = Typeface.create("sans-serif-black", Typeface.ITALIC)
        c.drawText("UL", band.left + bw * 0.06f, band.centerY() + tp.textSize * 0.36f, tp)
        tp.textAlign = Paint.Align.RIGHT
        tp.textSize = band.height() * 0.30f
        tp.typeface = condensed
        c.drawText("AUTO REVERSE  ·  MEGA BASS", band.right - bw * 0.06f, band.centerY() + tp.textSize * 0.36f, tp)
        p.color = 0xFFE8742B.toInt()
        c.drawRect(band.left, band.top - bw * 0.008f, band.right, band.top, p)

        // marcas de volumen
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = bw * 0.022f
        engrave(c, "VOL", bx + bw * 0.94f, wheelRect.top - bw * 0.015f)
        for (k in 0..10 step 2) {
            val yk = wheelRect.bottom - (wheelRect.height() * k / 10f)
            engrave(c, k.toString(), bx + bw * 0.94f, yk + tp.textSize * 0.35f)
        }

        // tornillos
        for (sx in floatArrayOf(bx + bw * 0.04f, bx + bw * 0.96f)) for (sy in floatArrayOf(by + bh * 0.03f, by + bh * 0.97f)) screw(c, sx, sy, bw * 0.014f)
        return bmp
    }

    private fun buildGlass(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val dr = bw * 0.03f
        p.reset(); p.isAntiAlias = true
        p.color = 0x30101830
        c.drawRoundRect(door, dr, dr, p)
        p.shader = LinearGradient(door.left, door.top, door.left + door.width() * 0.6f, door.bottom,
            intArrayOf(0x55FFFFFF, 0x10FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        val glare = Path()
        glare.moveTo(door.left, door.top)
        glare.lineTo(door.left + door.width() * 0.55f, door.top)
        glare.lineTo(door.left + door.width() * 0.25f, door.bottom)
        glare.lineTo(door.left, door.bottom)
        glare.close()
        c.save(); c.clipRect(door)
        c.drawPath(glare, p)
        c.restore()
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = 0x88FFFFFF.toInt()
        c.drawRoundRect(door, dr, dr, p)
        p.style = Paint.Style.FILL
        return bmp
    }

    private fun engrave(c: Canvas, s: String, x: Float, y: Float) {
        tp.color = 0x99FFFFFF.toInt()
        c.drawText(s, x, y + 1.5f, tp)
        tp.color = 0xFF3C4148.toInt()
        c.drawText(s, x, y, tp)
    }

    private fun screw(c: Canvas, x: Float, y: Float, r: Float) {
        p.shader = RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.3f, 0xFFF2F2F2.toInt(), 0xFF7D838B.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
        p.shader = null
        p.color = 0xFF4A4F57.toInt()
        p.strokeWidth = r * 0.3f
        c.drawLine(x - r * 0.7f, y, x + r * 0.7f, y, p)
    }

    // ================= dibujo dinámico =================

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1000f, 0.05f)
        lastFrame = now

        // animaciones
        for (i in 0 until 5) {
            val down = pressedKey == i || (i == 2 && isPlaying)
            val target = if (down) 1f else 0f
            keyAnim[i] += (target - keyAnim[i]) * min(1f, dt * 28f)
        }
        for (i in 0 until 3) {
            val target = if (switches[i]) 1f else 0f
            switchAnim[i] += (target - switchAnim[i]) * min(1f, dt * 18f)
        }
        val progress = if (hasTrack) (currentPosMs().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val leftR = rMin + (rMax - rMin) * (1f - progress)
        val rightR = rMin + (rMax - rMin) * progress
        val tapeSpeed = when {
            scanning -> rMin * 10f * scanDir
            isPlaying -> rMin * 1.8f
            else -> 0f
        }
        reelAngleL += dt * tapeSpeed / leftR * 57.3f
        reelAngleR += dt * tapeSpeed / rightR * 57.3f

        drawKeys(c)
        staticLayer?.let { c.drawBitmap(it, 0f, 0f, null) }
        drawCassette(c, leftR, rightR)
        glassLayer?.let { c.drawBitmap(it, 0f, 0f, null) }
        drawCounter(c)
        drawLed(c, now)
        drawSwitches(c)
        drawWheel(c, now)
        drawStatus(c)

        postInvalidateOnAnimation()
    }

    private fun drawKeys(c: Canvas) {
        p.reset(); p.isAntiAlias = true
        for (i in 0 until 5) {
            val k = keyRects[i]
            val off = keyAnim[i] * keyH * 0.6f
            val r = RectF(k.left, k.top + off, k.right, k.bottom + off)
            val base = if (i == 2) 0xFFE8742B.toInt() else 0xFF3A3D42.toInt()
            val light = if (i == 2) 0xFFF6A066.toInt() else 0xFF62666E.toInt()
            p.shader = LinearGradient(0f, r.top, 0f, r.top + keyH * 0.6f, light, base, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, keyH * 0.18f, keyH * 0.18f, p)
            p.shader = null
            // símbolo
            p.color = if (i == 2) 0xFF2A1408.toInt() else 0xFFE6E8EC.toInt()
            drawSymbol(c, i, r.centerX(), r.top + keyH * 0.42f, keyH * 0.22f)
        }
    }

    private fun drawSymbol(c: Canvas, i: Int, cx: Float, cy: Float, s: Float) {
        val path = Path()
        fun tri(x: Float, dir: Int) {
            path.moveTo(x - s * 0.5f * dir, cy - s * 0.6f)
            path.lineTo(x + s * 0.5f * dir, cy)
            path.lineTo(x - s * 0.5f * dir, cy + s * 0.6f)
            path.close()
        }
        when (i) {
            0 -> { tri(cx - s * 0.45f, -1); tri(cx + s * 0.45f, -1) }
            1 -> { tri(cx - s * 0.45f, 1); tri(cx + s * 0.45f, 1) }
            2 -> tri(cx + s * 0.1f, 1)
            3 -> {
                path.addRect(cx - s * 1.05f, cy - s * 0.5f, cx - s * 0.05f, cy + s * 0.5f, Path.Direction.CW)
                path.moveTo(cx + s * 0.25f, cy + s * 0.15f)
                path.lineTo(cx + s * 0.75f, cy - s * 0.55f)
                path.lineTo(cx + s * 1.25f, cy + s * 0.15f)
                path.close()
                path.addRect(cx + s * 0.25f, cy + s * 0.32f, cx + s * 1.25f, cy + s * 0.55f, Path.Direction.CW)
            }
            4 -> {
                path.addRect(cx - s * 0.55f, cy - s * 0.6f, cx - s * 0.18f, cy + s * 0.6f, Path.Direction.CW)
                path.addRect(cx + s * 0.18f, cy - s * 0.6f, cx + s * 0.55f, cy + s * 0.6f, Path.Direction.CW)
            }
        }
        c.drawPath(path, p)
    }

    private fun drawCassette(c: Canvas, leftR: Float, rightR: Float) {
        p.reset(); p.isAntiAlias = true
        c.save()
        c.clipRect(door)

        // carcasa
        val sr = bw * 0.02f
        p.shader = LinearGradient(0f, shell.top, 0f, shell.bottom, 0xFF3A3C41.toInt(), 0xFF24262A.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(shell, sr, sr, p)
        p.shader = null

        // parte inferior trapezoidal
        val trap = Path()
        val tTop = shell.bottom - shell.height() * 0.22f
        trap.moveTo(shell.left + shell.width() * 0.13f, shell.bottom)
        trap.lineTo(shell.left + shell.width() * 0.19f, tTop)
        trap.lineTo(shell.right - shell.width() * 0.19f, tTop)
        trap.lineTo(shell.right - shell.width() * 0.13f, shell.bottom)
        trap.close()
        p.color = 0xFF46494F.toInt()
        c.drawPath(trap, p)
        p.color = 0xFF151619.toInt()
        val holeY = tTop + (shell.bottom - tTop) * 0.5f
        for (fx in floatArrayOf(0.30f, 0.42f, 0.58f, 0.70f)) c.drawCircle(shell.left + shell.width() * fx, holeY, shell.height() * 0.025f, p)
        // cinta visible abajo
        p.color = 0xFF5A3A24.toInt()
        c.drawRect(shell.left + shell.width() * 0.22f, shell.bottom - shell.height() * 0.03f, shell.right - shell.width() * 0.22f, shell.bottom - shell.height() * 0.012f, p)

        // etiqueta
        val lr = bw * 0.012f
        p.color = 0xFFF3E9D2.toInt()
        c.drawRoundRect(label, lr, lr, p)
        // franjas de color
        val stripeTop = window.bottom + label.height() * 0.06f
        val sh = (label.bottom - stripeTop) / 3f
        val stripeColors = intArrayOf(0xFFE8742B.toInt(), 0xFFD9472B.toInt(), 0xFF1F3A68.toInt())
        for (k in 0 until 3) {
            p.color = stripeColors[k]
            c.drawRect(label.left, stripeTop + sh * k, label.right, stripeTop + sh * (k + 1), p)
        }
        // renglones manuscritos
        p.color = 0x553C6EA8
        p.strokeWidth = 1.2f
        val lineTop = label.top + label.height() * 0.24f
        val lineGap = label.height() * 0.15f
        c.drawLine(label.left + label.width() * 0.10f, lineTop, label.right - label.width() * 0.04f, lineTop, p)
        c.drawLine(label.left + label.width() * 0.10f, lineTop + lineGap, label.right - label.width() * 0.04f, lineTop + lineGap, p)

        // lado A
        tp.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        tp.textAlign = Paint.Align.CENTER
        tp.color = 0xFFD9472B.toInt()
        tp.textSize = label.height() * 0.22f
        c.drawText("A", label.left + label.width() * 0.055f, lineTop + tp.textSize * 0.15f, tp)

        // texto manuscrito
        tp.typeface = hand
        tp.textAlign = Paint.Align.LEFT
        tp.color = 0xFF1C2F5A.toInt()
        val textX = label.left + label.width() * 0.11f
        val textW = label.right - label.width() * 0.04f - textX
        val t1: String
        val t2: String
        when {
            !connected -> { t1 = "Spotify"; t2 = "toca el casete para conectar" }
            !hasTrack -> { t1 = "Sin casete"; t2 = "STOP/EJ para elegir uno" }
            else -> { t1 = title; t2 = artist }
        }
        tp.textSize = label.height() * 0.15f
        c.drawText(TextUtils.ellipsize(t1, tp, textW, TextUtils.TruncateAt.END).toString(), textX, lineTop - label.height() * 0.03f, tp)
        tp.textSize = label.height() * 0.115f
        tp.color = 0xFF3B4E78.toInt()
        c.drawText(TextUtils.ellipsize(t2, tp, textW, TextUtils.TruncateAt.END).toString(), textX, lineTop + lineGap - label.height() * 0.03f, tp)

        // portada
        val a = art
        if (a != null && !a.isRecycled) {
            val ap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            c.drawBitmap(a, null, artRect, ap)
        } else {
            p.color = 0xFFE0D3B5.toInt()
            c.drawRect(artRect, p)
            p.color = 0xFFB9A982.toInt()
            c.drawCircle(artRect.centerX(), artRect.centerY(), artRect.width() * 0.3f, p)
            p.color = 0xFFE0D3B5.toInt()
            c.drawCircle(artRect.centerX(), artRect.centerY(), artRect.width() * 0.08f, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0x55000000
        c.drawRect(artRect, p)
        p.style = Paint.Style.FILL

        // tiempo restante del lado derecho de la etiqueta
        if (hasTrack) {
            tp.typeface = condensed
            tp.textAlign = Paint.Align.CENTER
            tp.color = 0xFF1C2F5A.toInt()
            tp.textSize = window.height() * 0.30f
            val rx = (window.right + label.right) / 2f
            c.drawText(fmt(currentPosMs()), rx, window.centerY() - tp.textSize * 0.15f, tp)
            tp.color = 0xFF6B7690.toInt()
            tp.textSize = window.height() * 0.24f
            c.drawText(fmt(durationMs), rx, window.centerY() + tp.textSize * 1.05f, tp)
        }

        // ventana con carretes
        val wr = window.height() * 0.5f
        p.color = 0xFF0E0F11.toInt()
        c.drawRoundRect(window, wr, wr, p)
        c.save()
        val clip = Path(); clip.addRoundRect(window, wr, wr, Path.Direction.CW)
        c.clipPath(clip)
        drawPack(c, reelLx, leftR)
        drawPack(c, reelRx, rightR)
        drawHub(c, reelLx, reelAngleL)
        drawHub(c, reelRx, reelAngleR)
        // reflejo de la ventana
        p.color = 0x22FFFFFF
        c.drawRect(window.left, window.top, window.right, window.top + window.height() * 0.18f, p)
        c.restore()
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0xFF8C8270.toInt()
        c.drawRoundRect(window, wr, wr, p)
        p.style = Paint.Style.FILL

        c.restore()
    }

    private fun drawPack(c: Canvas, cx: Float, r: Float) {
        p.shader = RadialGradient(cx, reelCy, r, intArrayOf(0xFF3E2A1C.toInt(), 0xFF6A4528.toInt(), 0xFF4A301E.toInt()), floatArrayOf(0f, 0.85f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, reelCy, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = 0x22FFFFFF
        var rr = rMin
        while (rr < r) { c.drawCircle(cx, reelCy, rr, p); rr += hubR * 0.22f }
        p.style = Paint.Style.FILL
    }

    private fun drawHub(c: Canvas, cx: Float, angle: Float) {
        c.save()
        c.rotate(angle, cx, reelCy)
        p.color = 0xFFEDEDED.toInt()
        c.drawCircle(cx, reelCy, hubR, p)
        p.color = 0xFF2A2B2E.toInt()
        c.drawCircle(cx, reelCy, hubR * 0.55f, p)
        p.color = 0xFFEDEDED.toInt()
        for (k in 0 until 6) {
            c.save()
            c.rotate(k * 60f, cx, reelCy)
            c.drawRect(cx - hubR * 0.09f, reelCy - hubR * 0.56f, cx + hubR * 0.09f, reelCy - hubR * 0.30f, p)
            c.restore()
        }
        // marca para notar el giro
        p.color = 0xFFBFBFBF.toInt()
        c.drawCircle(cx + hubR * 0.78f, reelCy, hubR * 0.08f, p)
        c.restore()
    }

    private fun drawCounter(c: Canvas) {
        val sec = (currentPosMs() / 1000.0) - counterOffsetSec
        val v = ((sec % 1000.0) + 1000.0) % 1000.0
        val cellW = counterRect.width() / 3f
        tp.typeface = mono
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = counterRect.height() * 0.7f
        val ones = floor(v).toInt()
        val frac = (v - ones).toFloat()
        val digits = intArrayOf((ones / 100) % 10, (ones / 10) % 10, ones % 10)
        for (k in 0 until 3) {
            val cell = RectF(counterRect.left + cellW * k + 3f, counterRect.top + 3f, counterRect.left + cellW * (k + 1) - 3f, counterRect.bottom - 3f)
            p.shader = LinearGradient(0f, cell.top, 0f, cell.bottom,
                intArrayOf(0xFF9A9A9A.toInt(), 0xFFF4F4F4.toInt(), 0xFF9A9A9A.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(cell, p)
            p.shader = null
            c.save(); c.clipRect(cell)
            tp.color = 0xFF111111.toInt()
            val baseY = cell.centerY() + tp.textSize * 0.36f
            if (k == 2) {
                val roll = frac * cell.height()
                c.drawText(digits[k].toString(), cell.centerX(), baseY - roll, tp)
                c.drawText(((digits[k] + 1) % 10).toString(), cell.centerX(), baseY - roll + cell.height(), tp)
            } else {
                c.drawText(digits[k].toString(), cell.centerX(), baseY, tp)
            }
            c.restore()
        }
        // botón reset
        val rr = resetRect.width() / 2f
        p.shader = RadialGradient(resetRect.centerX(), resetRect.centerY() - rr * 0.3f, rr * 1.2f,
            if (resetPressed) 0xFF8A8F96.toInt() else 0xFFEDEFF2.toInt(), 0xFF6E747C.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(resetRect.centerX(), resetRect.centerY(), rr, p)
        p.shader = null
    }

    private fun drawLed(c: Canvas, now: Long) {
        val r = bw * 0.018f
        val on: Boolean
        val color: Int
        if (connected) { on = true; color = 0xFF39E66B.toInt() } else { on = (now / 500) % 2 == 0L; color = 0xFFFF3B30.toInt() }
        if (on) {
            p.shader = RadialGradient(ledX, ledY, r * 3f, (color and 0x00FFFFFF) or 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
            c.drawCircle(ledX, ledY, r * 3f, p)
            p.shader = null
        }
        p.color = if (on) color else 0xFF5A1E1A.toInt()
        c.drawCircle(ledX, ledY, r, p)
        p.color = 0x88FFFFFF.toInt()
        c.drawCircle(ledX - r * 0.3f, ledY - r * 0.3f, r * 0.3f, p)
    }

    private fun drawSwitches(c: Canvas) {
        for (i in 0 until 3) {
            val sr = switchRects[i]
            val kw = sr.width() * 0.48f
            val kx = sr.left + 2f + (sr.width() - kw - 4f) * switchAnim[i]
            val k = RectF(kx, sr.top + 2f, kx + kw, sr.bottom - 2f)
            p.shader = LinearGradient(0f, k.top, 0f, k.bottom, 0xFFF4F5F7.toInt(), 0xFF9BA1A9.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(k, k.height() / 2f, k.height() / 2f, p)
            p.shader = null
            p.color = 0x66000000
            p.strokeWidth = 1.5f
            for (j in -1..1) {
                val x = k.centerX() + j * k.width() * 0.16f
                c.drawLine(x, k.top + k.height() * 0.25f, x, k.bottom - k.height() * 0.25f, p)
            }
            if (switches[i]) {
                p.color = 0xFFE8742B.toInt()
                c.drawCircle(sr.right + sr.height() * 0.45f, sr.centerY(), sr.height() * 0.14f, p)
            }
        }
    }

    private fun drawWheel(c: Canvas, now: Long) {
        val w = wheelRect
        p.shader = LinearGradient(w.left, 0f, w.right, 0f,
            intArrayOf(0xFF2A2C30.toInt(), 0xFF6A6F77.toInt(), 0xFF2A2C30.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(w, w.width() * 0.5f, w.width() * 0.25f, p)
        p.shader = null
        c.save(); c.clipRect(w)
        val gap = w.height() / 14f
        val off = (volume * w.height() * 2.2f) % gap
        p.strokeWidth = gap * 0.35f
        var yy = w.top - gap + off
        while (yy < w.bottom + gap) {
            val t = ((yy - w.top) / w.height() - 0.5f) * 2f
            p.color = if (abs(t) < 0.9f) 0xFF1A1B1E.toInt() else 0xFF2E3034.toInt()
            c.drawLine(w.left, yy, w.right, yy, p)
            yy += gap
        }
        c.restore()
        // burbuja de volumen
        if (now < volumeShownUntil || draggingWheel) {
            val txt = "VOL " + (volume * 10f).roundToInt()
            tp.typeface = condensed
            tp.textAlign = Paint.Align.CENTER
            tp.textSize = bw * 0.045f
            val bwid = tp.measureText(txt) + bw * 0.05f
            val cy = w.top + w.height() * (1f - volume)
            val b = RectF(w.left - bw * 0.04f - bwid, cy - bw * 0.04f, w.left - bw * 0.04f, cy + bw * 0.04f)
            p.color = 0xEE16171A.toInt()
            c.drawRoundRect(b, bw * 0.02f, bw * 0.02f, p)
            tp.color = 0xFF39E66B.toInt()
            c.drawText(txt, b.centerX(), b.centerY() + tp.textSize * 0.36f, tp)
        }
    }

    private fun drawStatus(c: Canvas) {
        if (statusText.isEmpty()) return
        tp.typeface = condensed
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = bw * 0.032f
        tp.color = 0xFF3C4148.toInt()
        val s = TextUtils.ellipsize(statusText, tp, bw * 0.9f, TextUtils.TruncateAt.END).toString()
        c.drawText(s, bx + bw / 2f, by + bh * 0.945f, tp)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ================= toques =================

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (wheelHit.contains(x, y)) {
                    draggingWheel = true
                    lastTouchY = y
                    volumeShownUntil = SystemClock.uptimeMillis() + 1500
                    return true
                }
                for (i in 0 until 5) if (keyHit[i].contains(x, y)) { pressKey(i); return true }
                for (i in 0 until 3) if (switchHit[i].contains(x, y)) {
                    switches[i] = !switches[i]
                    listener?.onSwitchSound()
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    listener?.onSwitch(i, switches[i])
                    return true
                }
                val rh = RectF(resetRect); rh.inset(-resetRect.width() * 0.6f, -resetRect.height() * 0.6f)
                if (rh.contains(x, y)) {
                    resetPressed = true
                    counterOffsetSec = currentPosMs() / 1000.0
                    listener?.onSwitchSound()
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    return true
                }
                if (door.contains(x, y)) { listener?.onCassetteTap(); return true }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingWheel) {
                    val dy = lastTouchY - y
                    lastTouchY = y
                    volume = (volume + dy / (wheelRect.height() * 1.4f)).coerceIn(0f, 1f)
                    volumeShownUntil = SystemClock.uptimeMillis() + 1500
                    listener?.onVolume(volume)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingWheel = false
                resetPressed = false
                if (pressedKey >= 0) releaseKey(e.actionMasked == MotionEvent.ACTION_UP)
            }
        }
        return true
    }

    private fun pressKey(i: Int) {
        pressedKey = i
        listener?.onKeySound(true)
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        when (i) {
            0, 1 -> postDelayed(longPress, 420)
            2 -> listener?.onPlayKey()
            3 -> listener?.onStopEjectKey()
            4 -> listener?.onPauseKey()
        }
    }

    private fun releaseKey(up: Boolean) {
        val i = pressedKey
        removeCallbacks(longPress)
        if (i == 0 || i == 1) {
            if (scanning) {
                scanning = false
                scanDir = 0
                listener?.onScanEnd()
            } else if (up) {
                listener?.onSkip(if (i == 0) -1 else 1)
            }
        }
        pressedKey = -1
        listener?.onKeySound(false)
    }
}

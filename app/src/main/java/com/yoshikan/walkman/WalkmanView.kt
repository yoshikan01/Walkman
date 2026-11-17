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
import kotlin.math.min

/**
 * Casete a pantalla completa con 5 teclas mecánicas abajo.
 * Teclas: 0 REW, 1 FF, 2 PLAY, 3 STOP/EJECT, 4 PAUSE.
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

    // ---------- estado ----------
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

    // ---------- interacción ----------
    private var pressedKey = -1
    private var scanDir = 0
    private var scanning = false
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
    private var keyH = 0f
    private var deckTop = 0f
    private val keyRects = Array(5) { RectF() }
    private val keyHit = Array(5) { RectF() }
    private val shell = RectF()
    private val label = RectF()
    private val window = RectF()
    private val artRect = RectF()
    private var reelCy = 0f; private var reelLx = 0f; private var reelRx = 0f
    private var hubR = 0f; private var rMin = 0f; private var rMax = 0f
    private var ledX = 0f; private var ledY = 0f; private var ledR = 0f
    private var staticLayer: Bitmap? = null

    // ---------- pinturas ----------
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val hand = Typeface.create("casual", Typeface.BOLD)
    private val keyNames = arrayOf("REW", "FF", "PLAY", "STOP / EJECT", "PAUSE")

    init { isHapticFeedbackEnabled = true }

    // ================= API pública =================

    fun updatePlayer(playing: Boolean, posMs: Long, durMs: Long, t: String, a: String, shuffle: Boolean, repeat: Boolean) {
        isPlaying = playing
        basePosMs = posMs
        baseTime = SystemClock.uptimeMillis()
        durationMs = if (durMs > 0) durMs else 1L
        hasTrack = t.isNotEmpty()
        title = t
        artist = a
        invalidate()
    }

    fun setVolumeFraction(f: Float, show: Boolean) {}
    fun setSwitch(i: Int, on: Boolean) {}

    private fun currentPosMs(): Long {
        val pos = if (isPlaying) basePosMs + (SystemClock.uptimeMillis() - baseTime) else basePosMs
        return pos.coerceIn(0L, durationMs)
    }

    // ================= layout =================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return

        // fila de teclas abajo
        keyH = h * 0.17f
        deckTop = h - keyH - h * 0.03f
        val margin = w * 0.03f
        val gap = w * 0.012f
        val weights = floatArrayOf(1f, 1f, 1.35f, 1.35f, 1f)
        val unit = (w - margin * 2 - gap * 4) / weights.sum()
        var x = margin
        for (i in 0 until 5) {
            val kw = unit * weights[i]
            keyRects[i].set(x, deckTop, x + kw, deckTop + keyH)
            keyHit[i].set(x - gap / 2, deckTop - h * 0.03f, x + kw + gap / 2, h.toFloat())
            x += kw + gap
        }

        // casete: ocupa todo el espacio de arriba manteniendo proporción real (≈1.56:1)
        val areaTop = h * 0.035f
        val areaBottom = deckTop - h * 0.04f
        val areaH = areaBottom - areaTop
        var sw = w * 0.96f
        var sh = sw / 1.56f
        if (sh > areaH) { sh = areaH; sw = sh * 1.56f }
        val sl = (w - sw) / 2f
        shell.set(sl, areaTop + (areaH - sh) / 2f, sl + sw, areaTop + (areaH - sh) / 2f + sh)

        label.set(
            shell.left + shell.width() * 0.05f, shell.top + shell.height() * 0.07f,
            shell.right - shell.width() * 0.05f, shell.bottom - shell.height() * 0.28f
        )
        val winW = shell.width() * 0.50f
        val winH = label.height() * 0.40f
        val winCy = label.top + label.height() * 0.60f
        window.set(shell.centerX() - winW / 2f, winCy - winH / 2f, shell.centerX() + winW / 2f, winCy + winH / 2f)
        val artS = min(window.height() * 1.2f, (window.left - label.left) * 0.80f)
        val artL = label.left + (window.left - label.left - artS) / 2f
        artRect.set(artL, winCy - artS / 2f, artL + artS, winCy + artS / 2f)
        reelCy = window.centerY()
        reelLx = window.left + window.width() * 0.22f
        reelRx = window.right - window.width() * 0.22f
        hubR = window.height() * 0.27f
        rMin = hubR * 1.2f
        rMax = (reelRx - reelLx) - rMin - hubR * 0.15f

        ledR = shell.height() * 0.022f
        ledX = shell.right - shell.width() * 0.07f
        ledY = shell.bottom - shell.height() * 0.12f

        staticLayer?.recycle()
        staticLayer = buildStatic(w, h)
    }

    private fun buildStatic(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        p.reset(); p.isAntiAlias = true
        // fondo
        p.shader = RadialGradient(w / 2f, h * 0.4f, w * 0.75f, 0xFF2A2D33.toInt(), 0xFF0D0E10.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        // ranura de las teclas
        p.color = 0xFF050506.toInt()
        val slot = RectF(keyRects[0].left - 8f, deckTop - 8f, keyRects[4].right + 8f, deckTop + keyH + 8f)
        c.drawRoundRect(slot, keyH * 0.2f, keyH * 0.2f, p)
        // sombra del casete
        p.color = Color.BLACK
        p.setShadowLayer(shell.height() * 0.05f, 0f, shell.height() * 0.025f, 0xCC000000.toInt())
        c.drawRoundRect(shell, shell.height() * 0.04f, shell.height() * 0.04f, p)
        p.clearShadowLayer()
        return bmp
    }

    // ================= dibujo =================

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1000f, 0.05f)
        lastFrame = now

        for (i in 0 until 5) {
            val down = pressedKey == i || (i == 2 && isPlaying)
            keyAnim[i] += ((if (down) 1f else 0f) - keyAnim[i]) * min(1f, dt * 28f)
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

        staticLayer?.let { c.drawBitmap(it, 0f, 0f, null) }
        drawCassette(c, leftR, rightR)
        drawLed(c, now)
        drawKeys(c)

        postInvalidateOnAnimation()
    }

    private fun drawKeys(c: Canvas) {
        p.reset(); p.isAntiAlias = true
        for (i in 0 until 5) {
            val k = keyRects[i]
            val off = keyAnim[i] * keyH * 0.08f
            val r = RectF(k.left, k.top + off, k.right, k.bottom)
            val play = i == 2
            // lateral (profundidad)
            p.color = if (play) 0xFF8A3E10.toInt() else 0xFF15161A.toInt()
            c.drawRoundRect(r, keyH * 0.14f, keyH * 0.14f, p)
            // cara
            val face = RectF(r.left, r.top, r.right, r.bottom - keyH * 0.09f * (1f - keyAnim[i]) - keyH * 0.02f)
            p.shader = LinearGradient(0f, face.top, 0f, face.bottom,
                if (play) 0xFFF6A066.toInt() else 0xFF5C6068.toInt(),
                if (play) 0xFFE0661F.toInt() else 0xFF33363C.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(face, keyH * 0.14f, keyH * 0.14f, p)
            p.shader = null
            // símbolo y nombre
            val ink = if (play) 0xFF2A1408.toInt() else 0xFFE6E8EC.toInt()
            p.color = ink
            drawSymbol(c, i, face.centerX(), face.top + face.height() * 0.40f, face.height() * 0.17f)
            tp.typeface = condensed
            tp.textAlign = Paint.Align.CENTER
            tp.textSize = face.height() * 0.17f
            tp.color = ink
            c.drawText(keyNames[i], face.centerX(), face.top + face.height() * 0.82f, tp)
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
                path.addRect(cx - s * 1.15f, cy - s * 0.5f, cx - s * 0.15f, cy + s * 0.5f, Path.Direction.CW)
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
        val sr = shell.height() * 0.04f

        // carcasa
        p.shader = LinearGradient(0f, shell.top, 0f, shell.bottom, 0xFF3A3C41.toInt(), 0xFF202226.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(shell, sr, sr, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0x33FFFFFF
        c.drawRoundRect(shell, sr, sr, p)
        p.style = Paint.Style.FILL

        // tornillos de la carcasa
        val scR = shell.height() * 0.022f
        for (fx in floatArrayOf(0.025f, 0.975f)) for (fy in floatArrayOf(0.035f, 0.94f)) screw(c, shell.left + shell.width() * fx, shell.top + shell.height() * fy, scR)
        screw(c, shell.centerX(), shell.top + shell.height() * 0.035f, scR)

        // trapecio inferior
        val trap = Path()
        val tTop = shell.bottom - shell.height() * 0.22f
        trap.moveTo(shell.left + shell.width() * 0.13f, shell.bottom)
        trap.lineTo(shell.left + shell.width() * 0.19f, tTop)
        trap.lineTo(shell.right - shell.width() * 0.19f, tTop)
        trap.lineTo(shell.right - shell.width() * 0.13f, shell.bottom)
        trap.close()
        p.color = 0xFF46494F.toInt()
        c.drawPath(trap, p)
        p.color = 0xFF111214.toInt()
        val holeY = tTop + (shell.bottom - tTop) * 0.5f
        for (fx in floatArrayOf(0.30f, 0.42f, 0.58f, 0.70f)) c.drawCircle(shell.left + shell.width() * fx, holeY, shell.height() * 0.025f, p)
        p.color = 0xFF5A3A24.toInt()
        c.drawRect(shell.left + shell.width() * 0.22f, shell.bottom - shell.height() * 0.03f, shell.right - shell.width() * 0.22f, shell.bottom - shell.height() * 0.012f, p)

        // etiqueta
        val lr = shell.height() * 0.02f
        p.color = 0xFFF3E9D2.toInt()
        c.drawRoundRect(label, lr, lr, p)
        val stripeTop = window.bottom + label.height() * 0.06f
        val sh = (label.bottom - stripeTop) / 3f
        val stripeColors = intArrayOf(0xFFE8742B.toInt(), 0xFFD9472B.toInt(), 0xFF1F3A68.toInt())
        for (k in 0 until 3) {
            p.color = stripeColors[k]
            c.drawRect(label.left, stripeTop + sh * k, label.right, stripeTop + sh * (k + 1), p)
        }
        p.color = 0x553C6EA8
        p.strokeWidth = 1.5f
        val lineTop = label.top + label.height() * 0.24f
        val lineGap = label.height() * 0.15f
        c.drawLine(label.left + label.width() * 0.09f, lineTop, label.right - label.width() * 0.03f, lineTop, p)
        c.drawLine(label.left + label.width() * 0.09f, lineTop + lineGap, label.right - label.width() * 0.03f, lineTop + lineGap, p)

        tp.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        tp.textAlign = Paint.Align.CENTER
        tp.color = 0xFFD9472B.toInt()
        tp.textSize = label.height() * 0.22f
        c.drawText("A", label.left + label.width() * 0.045f, lineTop + tp.textSize * 0.15f, tp)

        // texto manuscrito
        tp.typeface = hand
        tp.textAlign = Paint.Align.LEFT
        tp.color = 0xFF1C2F5A.toInt()
        val textX = label.left + label.width() * 0.09f
        val textW = label.right - label.width() * 0.03f - textX
        val t1: String
        val t2: String
        when {
            !connected -> { t1 = "Spotify · toca el casete para conectar"; t2 = statusText }
            !hasTrack -> { t1 = "Sin casete"; t2 = "STOP / EJECT para elegir uno" }
            else -> { t1 = title; t2 = artist }
        }
        tp.textSize = label.height() * 0.15f
        c.drawText(TextUtils.ellipsize(t1, tp, textW, TextUtils.TruncateAt.END).toString(), textX, lineTop - label.height() * 0.03f, tp)
        tp.textSize = label.height() * 0.11f
        tp.color = if (connected) 0xFF3B4E78.toInt() else 0xFFB3261E.toInt()
        c.drawText(TextUtils.ellipsize(t2, tp, textW, TextUtils.TruncateAt.END).toString(), textX, lineTop + lineGap - label.height() * 0.03f, tp)

        // portada
        val a = art
        if (a != null && !a.isRecycled) {
            c.drawBitmap(a, null, artRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } else {
            p.color = 0xFFE0D3B5.toInt(); c.drawRect(artRect, p)
            p.color = 0xFFB9A982.toInt(); c.drawCircle(artRect.centerX(), artRect.centerY(), artRect.width() * 0.3f, p)
            p.color = 0xFFE0D3B5.toInt(); c.drawCircle(artRect.centerX(), artRect.centerY(), artRect.width() * 0.08f, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0x55000000
        c.drawRect(artRect, p)
        p.style = Paint.Style.FILL

        // tiempo
        if (hasTrack) {
            tp.typeface = condensed
            tp.textAlign = Paint.Align.CENTER
            tp.color = 0xFF1C2F5A.toInt()
            tp.textSize = window.height() * 0.32f
            val rx = (window.right + label.right) / 2f
            c.drawText(fmt(currentPosMs()), rx, window.centerY() - tp.textSize * 0.15f, tp)
            tp.color = 0xFF6B7690.toInt()
            tp.textSize = window.height() * 0.25f
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
        p.color = 0x22FFFFFF
        c.drawRect(window.left, window.top, window.right, window.top + window.height() * 0.18f, p)
        c.restore()
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0xFF8C8270.toInt()
        c.drawRoundRect(window, wr, wr, p)
        p.style = Paint.Style.FILL
    }

    private fun screw(c: Canvas, x: Float, y: Float, r: Float) {
        p.shader = RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.3f, 0xFFDADADA.toInt(), 0xFF5D636B.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
        p.shader = null
        p.color = 0xFF2F3338.toInt()
        p.strokeWidth = r * 0.3f
        c.drawLine(x - r * 0.6f, y - r * 0.6f, x + r * 0.6f, y + r * 0.6f, p)
        c.drawLine(x - r * 0.6f, y + r * 0.6f, x + r * 0.6f, y - r * 0.6f, p)
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
        p.color = 0xFFBFBFBF.toInt()
        c.drawCircle(cx + hubR * 0.78f, reelCy, hubR * 0.08f, p)
        c.restore()
    }

    private fun drawLed(c: Canvas, now: Long) {
        val on: Boolean
        val color: Int
        if (connected) { on = true; color = 0xFF39E66B.toInt() } else { on = (now / 500) % 2 == 0L; color = 0xFFFF3B30.toInt() }
        if (on) {
            p.shader = RadialGradient(ledX, ledY, ledR * 3f, (color and 0x00FFFFFF) or 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
            c.drawCircle(ledX, ledY, ledR * 3f, p)
            p.shader = null
        }
        p.color = if (on) color else 0xFF5A1E1A.toInt()
        c.drawCircle(ledX, ledY, ledR, p)
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
                for (i in 0 until 5) if (keyHit[i].contains(x, y)) { pressKey(i); return true }
                if (shell.contains(x, y)) { listener?.onCassetteTap(); return true }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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

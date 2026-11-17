package com.yoshikan.walkman

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

fun col(v: Long): Int = v.toInt()

enum class SoundKind { TAPE, VINYL, CD, RADIO, JUKEBOX, EIGHT }
enum class Ambient { HISS, CRACKLE, HUM }

/** Estado compartido del reproductor (lo leen todos los skins). */
class Deck {
    var connected = false
    var isPlaying = false
    var hasTrack = false
    var title = ""
    var artist = ""
    var album = ""
    var status = "Conectando…"
    var art: Bitmap? = null
    var durationMs = 1L
    var basePosMs = 0L
    var baseTime = 0L
    var trackNo = 0
    var scanDir = 0
    var spin = 0f
    var level = 0f
    var levelL = 0f
    var levelR = 0f
    val bands = FloatArray(10)

    fun pos(): Long {
        val p = if (isPlaying) basePosMs + (SystemClock.uptimeMillis() - baseTime) else basePosMs
        return p.coerceIn(0L, durationMs)
    }

    fun progress(): Float = if (hasTrack) (pos().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

abstract class Skin {
    abstract val name: String
    abstract val aspect: Float
    abstract val kind: SoundKind
    abstract val ambient: Ambient
    abstract val shelfTitle: String
    /** 0 portada cuadrada, 1 vinilo, 2 CD, 3 casete */
    abstract val thumbShape: Int
    open val accent: Int = col(0xFFE8742B)
    open val bgTop: Int = col(0xFF2A2D33)
    open val bgBottom: Int = col(0xFF0D0E10)
    open val keyLight: Int = col(0xFF5C6068)
    open val keyDark: Int = col(0xFF33363C)
    open val keyInk: Int = col(0xFFE6E8EC)
    open val shelfBg: Int = col(0xF01C130D)

    val r = RectF()
    protected val p = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    protected val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    protected val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    protected val hand: Typeface = Typeface.create("casual", Typeface.BOLD)
    protected val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    protected val black: Typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    protected val serif: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)

    var onSeek: ((Float) -> Unit)? = null
    var onSkip: ((Int) -> Unit)? = null
    var onSeekBy: ((Long) -> Unit)? = null

    // ---------- giro con el dedo (estilo DJ) ----------
    protected var scrubbing = false
    private var scrubLast = 0f
    private var scrubAcc = 0f
    /** Grados que el dedo giró desde el último cuadro; el skin los suma a su ángulo y los pone en 0. */
    protected var spinNudge = 0f

    protected fun scrubTouch(e: MotionEvent, d: Deck, cx: Float, cy: Float, rad: Float): Boolean {
        val a = Math.toDegrees(atan2((e.y - cy).toDouble(), (e.x - cx).toDouble())).toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!d.hasTrack || hypot(e.x - cx, e.y - cy) > rad) return false
                scrubbing = true; scrubLast = a; scrubAcc = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> if (scrubbing) {
                var da = a - scrubLast
                if (da > 180f) da -= 360f
                if (da < -180f) da += 360f
                scrubLast = a; scrubAcc += da; spinNudge += da
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (scrubbing) {
                scrubbing = false
                if (kotlin.math.abs(scrubAcc) > 8f) onSeekBy?.invoke((scrubAcc / 360f * 8000f).toLong())
                return true
            }
        }
        return false
    }

    fun layout(area: RectF) {
        var w = area.width()
        var h = w / aspect
        if (h > area.height()) { h = area.height(); w = h * aspect }
        r.set(area.centerX() - w / 2f, area.centerY() - h / 2f, area.centerX() + w / 2f, area.centerY() + h / 2f)
        onLayout()
    }

    open fun onLayout() {}
    abstract fun draw(c: Canvas, d: Deck, dt: Float, now: Long)
    /** Devuelve true si el skin se queda con el gesto (arrastre de brazo, perillas…). */
    open fun touch(e: MotionEvent, d: Deck): Boolean = false

    // ---------- utilidades ----------
    protected fun X(f: Float) = r.left + r.width() * f
    protected fun Y(f: Float) = r.top + r.height() * f
    protected val W get() = r.width()
    protected val H get() = r.height()

    protected fun reset() { p.reset(); p.isAntiAlias = true }

    protected fun grad(c: Canvas, rect: RectF, rad: Float, top: Int, bottom: Int) {
        p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, top, bottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, rad, rad, p)
        p.shader = null
    }

    protected fun wood(c: Canvas, path: Path, bounds: RectF, light: Int, dark: Int, seed: Int) {
        p.shader = LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom, light, dark, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        c.save()
        c.clipPath(path)
        val rnd = Random(seed)
        p.style = Paint.Style.STROKE
        var y = bounds.top
        while (y < bounds.bottom) {
            p.strokeWidth = 1f + rnd.nextFloat() * 2.5f
            p.color = if (rnd.nextBoolean()) 0x18000000 else 0x10FFFFFF
            val wav = bounds.height() * 0.01f * rnd.nextFloat()
            val gp = Path()
            gp.moveTo(bounds.left, y)
            gp.cubicTo(bounds.left + bounds.width() * 0.3f, y - wav, bounds.left + bounds.width() * 0.7f, y + wav, bounds.right, y + rnd.nextFloat() * 2f)
            c.drawPath(gp, p)
            y += bounds.height() * (0.012f + rnd.nextFloat() * 0.03f)
        }
        p.style = Paint.Style.FILL
        c.restore()
    }

    protected fun screw(c: Canvas, x: Float, y: Float, rad: Float) {
        p.shader = RadialGradient(x - rad * 0.3f, y - rad * 0.3f, rad * 1.3f, col(0xFFE5E5E5), col(0xFF5D636B), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, rad, p)
        p.shader = null
        p.color = col(0xFF2F3338)
        p.strokeWidth = rad * 0.3f
        c.drawLine(x - rad * 0.6f, y - rad * 0.6f, x + rad * 0.6f, y + rad * 0.6f, p)
        c.drawLine(x - rad * 0.6f, y + rad * 0.6f, x + rad * 0.6f, y - rad * 0.6f, p)
    }

    protected fun artCircle(c: Canvas, art: Bitmap?, cx: Float, cy: Float, rad: Float, angle: Float, fallback: Int) {
        c.save()
        c.rotate(angle, cx, cy)
        val clip = Path()
        clip.addCircle(cx, cy, rad, Path.Direction.CW)
        c.clipPath(clip)
        if (art != null && !art.isRecycled) {
            c.drawBitmap(art, null, RectF(cx - rad, cy - rad, cx + rad, cy + rad), bp)
        } else {
            p.color = fallback
            c.drawCircle(cx, cy, rad, p)
            p.color = 0x33FFFFFF
            c.drawRect(cx - rad, cy - rad * 0.12f, cx + rad, cy + rad * 0.12f, p)
        }
        c.restore()
    }

    protected fun artRect(c: Canvas, art: Bitmap?, rect: RectF, fallback: Int) {
        if (art != null && !art.isRecycled) c.drawBitmap(art, null, rect, bp)
        else {
            p.color = fallback
            c.drawRect(rect, p)
            p.color = 0x33000000
            c.drawCircle(rect.centerX(), rect.centerY(), rect.width() * 0.3f, p)
        }
    }

    protected fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER, tf: Typeface = condensed, maxW: Float = 0f) {
        tp.typeface = tf
        tp.textSize = size
        tp.color = color
        tp.textAlign = align
        val t = if (maxW > 0f) TextUtils.ellipsize(s, tp, maxW, TextUtils.TruncateAt.END).toString() else s
        c.drawText(t, x, y, tp)
    }

    protected fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    protected fun lineOne(d: Deck): String = when {
        !d.connected -> "Toca aquí para conectar tu música"
        !d.hasTrack -> "Sin música · STOP / EJECT"
        else -> d.title
    }

    protected fun lineTwo(d: Deck): String = when {
        !d.connected -> d.status
        !d.hasTrack -> "Abre tus recientes"
        else -> d.artist
    }

    protected fun ease(cur: Float, target: Float, dt: Float, speed: Float) = cur + (target - cur) * min(1f, dt * speed)
}

// =====================================================================
// Casete dibujado dentro de cualquier rectángulo (lo usan Casete y Boombox)
// =====================================================================
class CassetteArt {
    val shell = RectF()
    private val label = RectF()
    private val window = RectF()
    private val artRect = RectF()
    private var reelCy = 0f
    private var reelLx = 0f
    private var reelRx = 0f
    private var hubR = 0f
    private var rMin = 0f
    private var rMax = 0f
    private var angL = 0f
    private var angR = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val hand = Typeface.create("casual", Typeface.BOLD)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    fun layout(s: RectF) {
        shell.set(s)
        label.set(shell.left + shell.width() * 0.05f, shell.top + shell.height() * 0.07f, shell.right - shell.width() * 0.05f, shell.bottom - shell.height() * 0.28f)
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
    }

    fun draw(c: Canvas, d: Deck, dt: Float, t1: String, t2: String, t2Red: Boolean) {
        p.reset(); p.isAntiAlias = true
        val prog = d.progress()
        val leftR = rMin + (rMax - rMin) * (1f - prog)
        val rightR = rMin + (rMax - rMin) * prog
        val speed = rMin * 1.8f * d.spin
        angL += dt * speed / leftR * 57.3f
        angR += dt * speed / rightR * 57.3f
        val sh = shell.height()
        val sr = sh * 0.04f

        p.shader = LinearGradient(0f, shell.top, 0f, shell.bottom, col(0xFF3A3C41), col(0xFF202226), Shader.TileMode.CLAMP)
        c.drawRoundRect(shell, sr, sr, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0x33FFFFFF
        c.drawRoundRect(shell, sr, sr, p)
        p.style = Paint.Style.FILL

        val scR = sh * 0.022f
        for (fx in floatArrayOf(0.025f, 0.975f)) for (fy in floatArrayOf(0.035f, 0.94f)) screw(c, shell.left + shell.width() * fx, shell.top + sh * fy, scR)

        val trap = Path()
        val tTop = shell.bottom - sh * 0.22f
        trap.moveTo(shell.left + shell.width() * 0.13f, shell.bottom)
        trap.lineTo(shell.left + shell.width() * 0.19f, tTop)
        trap.lineTo(shell.right - shell.width() * 0.19f, tTop)
        trap.lineTo(shell.right - shell.width() * 0.13f, shell.bottom)
        trap.close()
        p.color = col(0xFF46494F)
        c.drawPath(trap, p)
        p.color = col(0xFF111214)
        val holeY = tTop + (shell.bottom - tTop) * 0.5f
        for (fx in floatArrayOf(0.30f, 0.42f, 0.58f, 0.70f)) c.drawCircle(shell.left + shell.width() * fx, holeY, sh * 0.025f, p)
        p.color = col(0xFF5A3A24)
        c.drawRect(shell.left + shell.width() * 0.22f, shell.bottom - sh * 0.03f, shell.right - shell.width() * 0.22f, shell.bottom - sh * 0.012f, p)

        val lr = sh * 0.02f
        p.color = col(0xFFF3E9D2)
        c.drawRoundRect(label, lr, lr, p)
        val stripeTop = window.bottom + label.height() * 0.06f
        val sth = (label.bottom - stripeTop) / 3f
        val stripes = intArrayOf(col(0xFFE8742B), col(0xFFD9472B), col(0xFF1F3A68))
        for (k in 0 until 3) {
            p.color = stripes[k]
            c.drawRect(label.left, stripeTop + sth * k, label.right, stripeTop + sth * (k + 1), p)
        }
        p.color = 0x553C6EA8
        p.strokeWidth = 1.5f
        val lineTop = label.top + label.height() * 0.24f
        val lineGap = label.height() * 0.15f
        c.drawLine(label.left + label.width() * 0.09f, lineTop, label.right - label.width() * 0.03f, lineTop, p)
        c.drawLine(label.left + label.width() * 0.09f, lineTop + lineGap, label.right - label.width() * 0.03f, lineTop + lineGap, p)

        tp.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        tp.textAlign = Paint.Align.CENTER
        tp.color = col(0xFFD9472B)
        tp.textSize = label.height() * 0.22f
        c.drawText("A", label.left + label.width() * 0.045f, lineTop + tp.textSize * 0.15f, tp)

        tp.typeface = hand
        tp.textAlign = Paint.Align.LEFT
        tp.color = col(0xFF1C2F5A)
        val textX = label.left + label.width() * 0.09f
        val textW = label.right - label.width() * 0.03f - textX
        tp.textSize = label.height() * 0.15f
        c.drawText(TextUtils.ellipsize(t1, tp, textW, TextUtils.TruncateAt.END).toString(), textX, lineTop - label.height() * 0.03f, tp)
        tp.textSize = label.height() * 0.11f
        tp.color = if (t2Red) col(0xFFB3261E) else col(0xFF3B4E78)
        c.drawText(TextUtils.ellipsize(t2, tp, textW, TextUtils.TruncateAt.END).toString(), textX, lineTop + lineGap - label.height() * 0.03f, tp)

        val a = d.art
        if (a != null && !a.isRecycled) c.drawBitmap(a, null, artRect, bp)
        else {
            p.color = col(0xFFE0D3B5); c.drawRect(artRect, p)
            p.color = col(0xFFB9A982); c.drawCircle(artRect.centerX(), artRect.centerY(), artRect.width() * 0.3f, p)
        }

        if (d.hasTrack) {
            tp.typeface = condensed
            tp.textAlign = Paint.Align.CENTER
            tp.color = col(0xFF1C2F5A)
            tp.textSize = window.height() * 0.32f
            val rx = (window.right + label.right) / 2f
            val s = d.pos() / 1000
            c.drawText("%d:%02d".format(s / 60, s % 60), rx, window.centerY() - tp.textSize * 0.15f, tp)
            tp.color = col(0xFF6B7690)
            tp.textSize = window.height() * 0.25f
            val dd = d.durationMs / 1000
            c.drawText("%d:%02d".format(dd / 60, dd % 60), rx, window.centerY() + tp.textSize * 1.05f, tp)
        }

        val wr = window.height() * 0.5f
        p.color = col(0xFF0E0F11)
        c.drawRoundRect(window, wr, wr, p)
        c.save()
        val clip = Path(); clip.addRoundRect(window, wr, wr, Path.Direction.CW)
        c.clipPath(clip)
        pack(c, reelLx, leftR)
        pack(c, reelRx, rightR)
        hub(c, reelLx, angL)
        hub(c, reelRx, angR)
        p.color = 0x22FFFFFF
        c.drawRect(window.left, window.top, window.right, window.top + window.height() * 0.18f, p)
        c.restore()
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = col(0xFF8C8270)
        c.drawRoundRect(window, wr, wr, p)
        p.style = Paint.Style.FILL
    }

    private fun screw(c: Canvas, x: Float, y: Float, rad: Float) {
        p.shader = RadialGradient(x - rad * 0.3f, y - rad * 0.3f, rad * 1.3f, col(0xFFDADADA), col(0xFF5D636B), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, rad, p)
        p.shader = null
        p.color = col(0xFF2F3338)
        p.strokeWidth = rad * 0.3f
        c.drawLine(x - rad * 0.6f, y - rad * 0.6f, x + rad * 0.6f, y + rad * 0.6f, p)
        c.drawLine(x - rad * 0.6f, y + rad * 0.6f, x + rad * 0.6f, y - rad * 0.6f, p)
    }

    private fun pack(c: Canvas, cx: Float, rad: Float) {
        p.shader = RadialGradient(cx, reelCy, rad, intArrayOf(col(0xFF3E2A1C), col(0xFF6A4528), col(0xFF4A301E)), floatArrayOf(0f, 0.85f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, reelCy, rad, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = 0x22FFFFFF
        var rr = rMin
        while (rr < rad) { c.drawCircle(cx, reelCy, rr, p); rr += hubR * 0.22f }
        p.style = Paint.Style.FILL
    }

    private fun hub(c: Canvas, cx: Float, angle: Float) {
        c.save()
        c.rotate(angle, cx, reelCy)
        p.color = col(0xFFEDEDED)
        c.drawCircle(cx, reelCy, hubR, p)
        p.color = col(0xFF2A2B2E)
        c.drawCircle(cx, reelCy, hubR * 0.55f, p)
        p.color = col(0xFFEDEDED)
        for (k in 0 until 6) {
            c.save()
            c.rotate(k * 60f, cx, reelCy)
            c.drawRect(cx - hubR * 0.09f, reelCy - hubR * 0.56f, cx + hubR * 0.09f, reelCy - hubR * 0.30f, p)
            c.restore()
        }
        p.color = col(0xFFBFBFBF)
        c.drawCircle(cx + hubR * 0.78f, reelCy, hubR * 0.08f, p)
        c.restore()
    }
}

// =====================================================================
// 1. Casete
// =====================================================================
class CassetteSkin : Skin() {
    override val name = "Casete"
    override val aspect = 1.56f
    override val kind = SoundKind.TAPE
    override val ambient = Ambient.HISS
    override val shelfTitle = "RACK DE CASETES"
    override val thumbShape = 3
    private val art = CassetteArt()

    override fun onLayout() { art.layout(r) }

    override fun draw(c: Canvas, d: Deck, dt: Float, now: Long) {
        reset()
        p.color = col(0xFF000000)
        p.setShadowLayer(H * 0.05f, 0f, H * 0.025f, col(0xCC000000))
        c.drawRoundRect(r, H * 0.04f, H * 0.04f, p)
        p.clearShadowLayer()
        art.draw(c, d, dt, lineOne(d), lineTwo(d), !d.connected)
    }
}

// =====================================================================
// 2. Tocadiscos
// =====================================================================
class TurntableSkin : Skin() {
    override val name = "Tocadiscos"
    override val aspect = 1.38f
    override val kind = SoundKind.VINYL
    override val ambient = Ambient.CRACKLE
    override val shelfTitle = "REPISA DE DISCOS"
    override val thumbShape = 1
    override val accent = col(0xFFC8A15A)
    override val bgTop = col(0xFF3A2C22)
    override val bgBottom = col(0xFF120D09)
    override val shelfBg = col(0xF0201610)

    private var cx = 0f; private var cy = 0f; private var platR = 0f; private var recR = 0f
    private var px = 0f; private var py = 0f; private var armL = 0f; private var dist = 0f; private var alpha = 0f
    private var thOuter = 0f; private var thInner = 0f; private var thRest = 0f
    private var outerR = 0f; private var innerR = 0f
    private var arm = Float.NaN
    private var lift = 1f
    private var recAng = 0f
    private var dragging = false
    private var dragAng = 0f

    override fun onLayout() {
        cx = X(0.40f); cy = Y(0.52f); platR = H * 0.43f; recR = platR * 0.97f
        px = X(0.85f); py = Y(0.17f)
        dist = hypot(cx - px, cy - py)
        armL = dist - recR * 0.30f
        alpha = atan2(cy - py, cx - px)
        outerR = recR * 0.92f; innerR = recR * 0.38f
        thOuter = angFor(outerR); thInner = angFor(innerR); thRest = thOuter - 0.30f
        arm = thRest
    }

    private fun angFor(rt: Float): Float {
        val cv = ((dist * dist + armL * armL - rt * rt) / (2f * dist * armL)).coerceIn(-1f, 1f)
        return alpha - acos(cv)
    }

    private fun sx(a: Float) = px + armL * cos(a)
    private fun sy(a: Float) = py + armL * sin(a)

    override fun draw(c: Canvas, d: Deck, dt: Float, now: Long) {
        reset()
        val target = when {
            dragging -> dragAng
            d.hasTrack -> angFor(outerR - (outerR - innerR) * d.progress())
            else -> thRest
        }
        arm = ease(arm, target, dt, if (dragging) 30f else 4f)
        lift = ease(lift, if (d.isPlaying && !dragging && d.scanDir == 0) 0f else 1f, dt, 6f)
        recAng += if (scrubbing) 0f else dt * 200f * d.spin
        recAng += spinNudge; spinNudge = 0f

        // plinto de madera
        val plinth = Path(); plinth.addRoundRect(r, H * 0.04f, H * 0.04f, Path.Direction.CW)
        p.color = col(0xFF000000)
        p.setShadowLayer(H * 0.05f, 0f, H * 0.025f, col(0xCC000000))
        c.drawPath(plinth, p)
        p.clearShadowLayer()
        wood(c, plinth, r, col(0xFF8A5A33), col(0xFF4A2D18), 5)
        p.style = Paint.Style.STROKE; p.strokeWidth = H * 0.012f; p.color = col(0xFFB8B8B8)
        c.drawRoundRect(RectF(r.left + H * 0.02f, r.top + H * 0.02f, r.right - H * 0.02f, r.bottom - H * 0.02f), H * 0.03f, H * 0.03f, p)
        p.style = Paint.Style.FILL

        // plato con puntos estroboscópicos
        p.shader = RadialGradient(cx, cy, platR, intArrayOf(col(0xFFD8D8D8), col(0xFF9A9A9A), col(0xFFE5E5E5)), floatArrayOf(0.85f, 0.95f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, platR, p)
        p.shader = null
        c.save(); c.rotate(recAng, cx, cy)
        p.color = col(0xFF555555)
        for (k in 0 until 72) {
            val a = Math.toRadians(k * 5.0).toFloat()
            c.drawCircle(cx + cos(a) * platR * 0.985f, cy + sin(a) * platR * 0.985f, platR * 0.008f, p)
        }
        c.restore()

        // disco
        p.shader = RadialGradient(cx, cy, recR, intArrayOf(col(0xFF1A1A1A), col(0xFF0A0A0A), col(0xFF222222)), floatArrayOf(0.3f, 0.95f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, recR, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        var gr = recR * 0.36f
        var k = 0
        while (gr < recR * 0.97f) {
            p.strokeWidth = 1f
            p.color = if (k % 2 == 0) 0x14FFFFFF else 0x0AFFFFFF
            c.drawCircle(cx, cy, gr, p)
            gr += recR * 0.012f
            k++
        }
        p.color = 0x22FFFFFF; p.strokeWidth = recR * 0.012f
        for (f in floatArrayOf(0.52f, 0.66f, 0.80f)) c.drawCircle(cx, cy, recR * f, p)
        p.style = Paint.Style.FILL
        // brillo fijo (la luz no gira)
        p.shader = SweepGradient(cx, cy, intArrayOf(0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.08f, 0.16f, 0.5f, 0.58f, 0.66f, 1f))
        c.drawCircle(cx, cy, recR, p)
        p.shader = null
        // etiqueta
        artCircle(c, d.art, cx, cy, recR * 0.33f, recAng, col(0xFFC0392B))
        p.color = col(0xFFDDDDDD)
        c.drawCircle(cx, cy, recR * 0.025f, p)

        // controles
        val bs = H * 0.08f
        grad(c, RectF(X(0.04f), Y(0.86f), X(0.04f) + bs * 1.4f, Y(0.86f) + bs), bs * 0.15f, col(0xFFEEEEEE), col(0xFF999999))
        text(c, "START", X(0.04f) + bs * 0.7f, Y(0.86f) + bs * 0.65f, bs * 0.32f, col(0xFF333333))
        text(c, "33", X(0.04f) + bs * 2.0f, Y(0.86f) + bs * 0.65f, bs * 0.4f, col(0xFFFFD27A))
        text(c, "45", X(0.04f) + bs * 2.8f, Y(0.86f) + bs * 0.65f, bs * 0.4f, col(0x88FFFFFF))
        text(c, "UL · DIRECT DRIVE", X(0.97f), Y(0.95f), H * 0.04f, col(0xCCE8D8B8), Paint.Align.RIGHT, serif)

        // brazo
        val a = arm
        val ex = sx(a); val ey = sy(a)
        val sh = H * 0.012f + lift * H * 0.03f
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0x66000000
        p.strokeWidth = H * 0.022f
        c.drawLine(px + sh, py + sh, ex + sh, ey + sh, p)
        // base
        p.shader = RadialGradient(px, py, H * 0.08f, col(0xFF6A6A6A), col(0xFF1E1E1E), Shader.TileMode.CLAMP)
        c.drawCircle(px, py, H * 0.08f, p)
        p.shader = null
        // contrapeso
        val bx = px - cos(a) * H * 0.12f; val by = py - sin(a) * H * 0.12f
        p.color = col(0xFF2B2B2B); p.strokeWidth = H * 0.06f
        c.drawLine(px, py, bx, by, p)
        // tubo
        p.color = col(0xFFCFCFCF); p.strokeWidth = H * 0.02f
        c.drawLine(px, py, ex, ey, p)
        p.color = col(0xFFFFFFFF); p.strokeWidth = H * 0.005f
        c.drawLine(px, py - H * 0.004f, ex, ey - H * 0.004f, p)
        p.strokeCap = Paint.Cap.BUTT
        // cápsula
        c.save()
        c.translate(ex, ey)
        c.rotate(Math.toDegrees(a.toDouble()).toFloat() + 25f)
        p.color = col(0xFF222222)
        c.drawRoundRect(RectF(-H * 0.02f, -H * 0.035f, H * 0.075f, H * 0.035f), H * 0.01f, H * 0.01f, p)
        p.color = col(0xFFC8A15A)
        c.drawRect(H * 0.02f, -H * 0.02f, H * 0.055f, H * 0.02f, p)
        c.restore()
        p.color = col(0xFF888888)
        c.drawCircle(px, py, H * 0.025f, p)
    }

    override fun touch(e: MotionEvent, d: Deck): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!d.hasTrack) return false
                val near = hypot(e.x - sx(arm), e.y - sy(arm)) < H * 0.16f
                if (!near) return scrubTouch(e, d, cx, cy, recR)
                dragging = true
                dragAng = arm
                return true
            }
            MotionEvent.ACTION_MOVE -> if (scrubbing) return scrubTouch(e, d, cx, cy, recR) else if (dragging) {
                dragAng = atan2(e.y - py, e.x - px).coerceIn(thRest, thInner)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (scrubbing) return scrubTouch(e, d, cx, cy, recR) else if (dragging) {
                dragging = false
                if (dragAng >= thOuter - 0.02f) {
                    val rad = hypot(sx(dragAng) - cx, sy(dragAng) - cy)
                    onSeek?.invoke(((outerR - rad) / (outerR - innerR)).coerceIn(0f, 0.995f))
                }
            }
        }
        return true
    }
}

// =====================================================================
// 3. Discman / CD
// =====================================================================
class DiscmanSkin : Skin() {
    override val name = "CD portátil"
    override val aspect = 0.95f
    override val kind = SoundKind.CD
    override val ambient = Ambient.HISS
    override val shelfTitle = "TORRE DE CDs"
    override val thumbShape = 2
    override val accent = col(0xFF6FB7FF)
    override val bgTop = col(0xFF27303A)
    override val bgBottom = col(0xFF0B0E12)
    override val shelfBg = col(0xF0101820)
    private var ang = 0f

    override fun draw(c: Canvas, d: Deck, dt: Float, now: Long) {
        reset()
        ang += if (scrubbing) 0f else dt * 540f * d.spin
        ang += spinNudge; spinNudge = 0f
        val body = RectF(r)
        p.color = col(0xFF000000)
        p.setShadowLayer(H * 0.05f, 0f, H * 0.02f, col(0xCC000000))
        c.drawRoundRect(body, W * 0.32f, W * 0.32f, p)
        p.clearShadowLayer()
        p.shader = LinearGradient(body.left, body.top, body.right, body.bottom, intArrayOf(col(0xFFF1F3F6), col(0xFFB7BEC8), col(0xFF8D96A3)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(body, W * 0.32f, W * 0.32f, p)
        p.shader = null

        val cx = X(0.5f); val cy = Y(0.44f); val lidR = W * 0.42f; val discR = lidR * 0.9f
        // ranura de la tapa
        p.color = col(0xFF6B7380)
        c.drawCircle(cx, cy, lidR + W * 0.012f, p)
        p.color = col(0xFF1A1D22)
        c.drawCircle(cx, cy, lidR, p)

        // disco
        c.save()
        c.rotate(ang, cx, cy)
        p.shader = SweepGradient(cx, cy, intArrayOf(col(0xFFFF6B6B), col(0xFFFFE66B), col(0xFF6BFF9E), col(0xFF6BD5FF), col(0xFFB56BFF), col(0xFFFF6B6B)), null)
        c.drawCircle(cx, cy, discR, p)
        p.shader = null
        c.restore()
        p.color = 0x66FFFFFF
        c.drawCircle(cx, cy, discR, p)
        bp.alpha = 230
        artCircle(c, d.art, cx, cy, discR * 0.93f, ang, col(0xFF2E5C8A))
        bp.alpha = 255
        // reflejo tornasol fijo
        p.shader = SweepGradient(cx, cy, intArrayOf(0x00FFFFFF, 0x40FF80FF, 0x4080FFFF, 0x00FFFFFF, 0x00FFFFFF, 0x40FFFF80, 0x00FFFFFF), floatArrayOf(0f, 0.08f, 0.14f, 0.22f, 0.55f, 0.65f, 0.75f))
        c.drawCircle(cx, cy, discR, p)
        p.shader = null
        p.color = col(0xCCD5DCE5)
        c.drawCircle(cx, cy, discR * 0.30f, p)
        p.color = col(0xFF9AA3AE)
        c.drawCircle(cx, cy, discR * 0.18f, p)
        p.color = col(0xFF1A1D22)
        c.drawCircle(cx, cy, discR * 0.07f, p)

        // tapa de acrílico
        p.color = 0x22A0C0FF
        c.drawCircle(cx, cy, lidR, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = lidR * 0.05f
        p.color = 0x44FFFFFF
        c.drawArc(RectF(cx - lidR * 0.85f, cy - lidR * 0.85f, cx + lidR * 0.85f, cy + lidR * 0.85f), 200f, 60f, false, p)
        p.strokeWidth = 2f; p.color = 0x55FFFFFF
        c.drawCircle(cx, cy, lidR * 0.98f, p)
        p.style = Paint.Style.FILL
        text(c, "UL COMPACT PLAYER", cx, cy + lidR * 0.80f, lidR * 0.09f, 0xAAFFFFFF.toInt(), tf = black)
        text(c, "ESP · ANTI-SKIP · 1-BIT DAC", cx, cy - lidR * 0.80f, lidR * 0.06f, 0x99FFFFFF.toInt())

        // pantalla LCD
        val lcd = RectF(X(0.22f), Y(0.875f), X(0.78f), Y(0.965f))
        p.color = col(0xFF4A505A)
        c.drawRoundRect(RectF(lcd.left - 4f, lcd.top - 4f, lcd.right + 4f, lcd.bottom + 4f), 8f, 8f, p)
        p.color = col(0xFFA7B892)
        c.drawRoundRect(lcd, 6f, 6f, p)
        val ink = col(0xFF1F2A1A)
        val ty = lcd.centerY() + lcd.height() * 0.22f
        val sz = lcd.height() * 0.6f
        text(c, if (d.hasTrack) "TRK %02d".format(d.trackNo.coerceAtLeast(1)) else "NO DISC", lcd.left + lcd.width() * 0.05f, ty, sz, ink, Paint.Align.LEFT, mono)
        if (d.hasTrack) text(c, fmt(d.pos()), lcd.right - lcd.width() * 0.05f, ty, sz, ink, Paint.Align.RIGHT, mono)
        // indicador play / pausa
        val ix = lcd.centerX() + lcd.width() * 0.08f; val iy = lcd.centerY(); val s = lcd.height() * 0.22f
        p.color = ink
        if (d.isPlaying) {
            val tri = Path(); tri.moveTo(ix - s * 0.6f, iy - s); tri.lineTo(ix + s * 0.8f, iy); tri.lineTo(ix - s * 0.6f, iy + s); tri.close()
            c.drawPath(tri, p)
        } else if (d.hasTrack) {
            c.drawRect(ix - s * 0.7f, iy - s, ix - s * 0.2f, iy + s, p)
            c.drawRect(ix + s * 0.2f, iy - s, ix + s * 0.7f, iy + s, p)
        }
        // botón OPEN
        grad(c, RectF(X(0.80f), Y(0.08f), X(0.92f), Y(0.13f)), H * 0.02f, col(0xFFE0E3E8), col(0xFF9AA1AB))
        text(c, "OPEN", X(0.86f), Y(0.118f), H * 0.03f, col(0xFF3A3F47))
    }

    override fun touch(e: MotionEvent, d: Deck): Boolean = scrubTouch(e, d, X(0.5f), Y(0.44f), W * 0.38f)
}

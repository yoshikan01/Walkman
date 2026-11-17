package com.yoshikan.walkman

import android.content.Context
import android.graphics.Canvas
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
import kotlin.math.exp
import kotlin.math.min
import kotlin.random.Random

/** Pantalla: barra superior, aparato (skin), tira de información y 5 teclas mecánicas. */
class WalkmanView(context: Context, val skins: List<Skin>) : View(context) {

    interface Listener {
        fun onPlayKey()
        fun onPauseKey()
        fun onStopEjectKey()
        fun onSkip(dir: Int)
        fun onScanStart(dir: Int)
        fun onScanEnd()
        fun onDeviceTap()
        fun onSeek(fraction: Float)
        fun onKeySound(down: Boolean)
        fun onSkinChanged(index: Int)
        fun onSkinMenu()
        fun onOpenShelf()
        fun onToggleMute()
        fun onToggleAmbient()
        fun onSeekBy(ms: Long)
        fun onTimerMenu()
    }

    var listener: Listener? = null
    val deck = Deck()
    var skinIndex = 0
        set(v) { field = (v + skins.size) % skins.size; relayout() }
    var muted = false
    var ambientOn = false
    var meter: AudioMeter? = null
    /** Texto del temporizador (null = apagado). */
    var sleepLabel: String? = null
    private var prevSkin: Skin? = null
    private var trans = 1f
    private var devDown = false
    private var downX = 0f
    private var downY = 0f
    private var swipeOff = 0f

    private val dp = resources.displayMetrics.density
    private val btnPrev = RectF(); private val btnName = RectF(); private val btnNext = RectF()
    private val btnShelf = RectF(); private val btnAmb = RectF(); private val btnSnd = RectF(); private val btnTimer = RectF()
    private var compact = false
    private val info = RectF()
    private val device = RectF()
    private var keyH = 0f
    private val keyRects = Array(5) { RectF() }
    private val keyHit = Array(5) { RectF() }
    private val keyNames = arrayOf("REW", "FF", "PLAY", "STOP / EJECT", "PAUSE")
    private val keyAnim = FloatArray(5)

    private var pressedKey = -1
    private var scanning = false
    private var skinTouch = false
    private var lastFrame = 0L
    private val rnd = Random(1)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private val longPress = Runnable {
        if (pressedKey == 0 || pressedKey == 1) {
            scanning = true
            deck.scanDir = if (pressedKey == 0) -1 else 1
            listener?.onScanStart(deck.scanDir)
        }
    }

    init {
        isHapticFeedbackEnabled = true
        for (s in skins) {
            s.onSeek = { f -> listener?.onSeek(f) }
            s.onSkip = { dir -> listener?.onSkip(dir) }
            s.onSeekBy = { ms -> listener?.onSeekBy(ms) }
        }
    }

    fun skin() = skins[skinIndex]

    /** Cambia de aparato con animación: el anterior sale hacia arriba y el nuevo cae en su lugar. */
    fun switchSkin(i: Int) {
        if ((i + skins.size) % skins.size == skinIndex) return
        prevSkin = skin()
        trans = 0f
        skinIndex = i
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    private fun relayout() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val portrait = h > w
        compact = w < 720 * dp

        // barra superior
        val topH = 44 * dp
        val pad = 8 * dp
        val bh = 34 * dp
        val by = (topH - bh) / 2f + 4 * dp
        btnPrev.set(pad, by, pad + bh, by + bh)
        tp.typeface = condensed; tp.textSize = 15 * dp
        val nameW = (tp.measureText(skin().name.uppercase()) + 24 * dp).coerceAtMost(w * 0.42f)
        btnName.set(btnPrev.right + 4 * dp, by, btnPrev.right + 4 * dp + nameW, by + bh)
        btnNext.set(btnName.right + 4 * dp, by, btnName.right + 4 * dp + bh, by + bh)
        val pillW = if (compact) bh else 104 * dp
        btnSnd.set(w - pad - pillW, by, w - pad, by + bh)
        btnAmb.set(btnSnd.left - 6 * dp - pillW, by, btnSnd.left - 6 * dp, by + bh)
        btnShelf.set(btnAmb.left - 6 * dp - pillW, by, btnAmb.left - 6 * dp, by + bh)
        btnTimer.set(btnShelf.left - 6 * dp - pillW, by, btnShelf.left - 6 * dp, by + bh)

        // teclas
        keyH = if (portrait) min(h * 0.11f, w * 0.26f) else h * 0.17f
        val bottomPad = if (portrait) 22 * dp else 8 * dp
        val deckTop = h - keyH - bottomPad
        val margin = w * 0.03f
        val gap = w * 0.012f
        val weights = floatArrayOf(1f, 1f, 1.35f, 1.35f, 1f)
        val unit = (w - margin * 2 - gap * 4) / weights.sum()
        var x = margin
        for (i in 0 until 5) {
            val kw = unit * weights[i]
            keyRects[i].set(x, deckTop, x + kw, deckTop + keyH)
            keyHit[i].set(x - gap / 2, deckTop - 12 * dp, x + kw + gap / 2, h)
            x += kw + gap
        }

        // información de la canción
        val infoH = if (portrait) 62 * dp else 40 * dp
        info.set(margin, deckTop - infoH - 10 * dp, w - margin, deckTop - 10 * dp)
        device.set(w * 0.03f, topH + 8 * dp, w * 0.97f, info.top - 8 * dp)
        skin().layout(device)
    }

    // ================= dibujo =================

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1000f, 0.05f)
        lastFrame = now
        updateMotion(dt, now)

        val s = skin()
        val w = width.toFloat(); val h = height.toFloat()
        p.reset(); p.isAntiAlias = true
        p.shader = RadialGradient(w / 2f, h * 0.4f, maxOf(w, h) * 0.75f, s.bgTop, s.bgBottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null

        val prev = prevSkin
        if (trans < 1f && prev != null) {
            trans = min(1f, trans + dt / 0.65f)
            val t = trans
            val up = t * t
            c.save()
            c.translate(0f, -up * (device.bottom))
            c.saveLayerAlpha(0f, 0f, w, h, ((1f - t) * 255).toInt())
            prev.draw(c, deck, dt, now)
            c.restore()
            c.restore()
            // el nuevo cae desde arriba con un pequeño rebote
            val k = 1f - (1f - t) * (1f - t) * (1f - t)
            val bounce = if (t > 0.75f) kotlin.math.sin((t - 0.75f) / 0.25f * Math.PI.toFloat()) * 0.04f else 0f
            c.save()
            c.translate(0f, -(1f - k) * device.bottom + bounce * device.height())
            s.draw(c, deck, dt, now)
            c.restore()
            if (trans >= 1f) prevSkin = null
        } else {
            c.save()
            c.translate(swipeOff * 0.35f, 0f)
            s.draw(c, deck, dt, now)
            c.restore()
            if (!devDown) swipeOff *= 0.8f
        }
        drawTopBar(c)
        drawInfo(c)
        drawKeys(c, dt)
        postInvalidateOnAnimation()
    }

    private fun updateMotion(dt: Float, now: Long) {
        val d = deck
        val target = when {
            d.scanDir != 0 -> d.scanDir * 6f
            d.isPlaying -> 1f
            else -> 0f
        }
        d.spin += (target - d.spin) * min(1f, dt * 3f)
        val m = meter
        if (d.isPlaying && m != null && m.alive()) {
            d.level += (m.level - d.level) * min(1f, dt * 18f)
            d.levelL += (m.levelL - d.levelL) * min(1f, dt * 14f)
            d.levelR += (m.levelR - d.levelR) * min(1f, dt * 14f)
            for (i in d.bands.indices) d.bands[i] += (m.bands[i] - d.bands[i]) * min(1f, dt * 16f)
            return
        }
        if (d.isPlaying) {
            val ph = (now % 500L) / 500f
            val kick = exp(-ph * 6f)
            d.level += ((0.25f + 0.55f * kick + rnd.nextFloat() * 0.2f) - d.level) * min(1f, dt * 14f)
            d.levelL += ((0.3f + 0.5f * kick + rnd.nextFloat() * 0.25f) - d.levelL) * min(1f, dt * 10f)
            d.levelR += ((0.3f + 0.5f * kick + rnd.nextFloat() * 0.25f) - d.levelR) * min(1f, dt * 10f)
        } else {
            d.level *= 0.9f; d.levelL *= 0.9f; d.levelR *= 0.9f
        }
        for (i in d.bands.indices) {
            val tgt = if (d.isPlaying) (d.level * (0.35f + 0.65f * rnd.nextFloat()) * (1.15f - i * 0.05f)).coerceIn(0f, 1f) else 0f
            d.bands[i] += (tgt - d.bands[i]) * min(1f, dt * 12f)
        }
    }

    private fun pill(c: Canvas, r: RectF, on: Boolean) {
        p.color = if (on) (skin().accent and 0x00FFFFFF) or 0xCC000000.toInt() else 0x33FFFFFF
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, p)
    }

    private fun drawTopBar(c: Canvas) {
        p.reset(); p.isAntiAlias = true
        pill(c, btnPrev, false); pill(c, btnName, false); pill(c, btnNext, false)
        val ink = 0xFFFFFFFF.toInt()
        p.color = ink
        arrow(c, btnPrev, -1); arrow(c, btnNext, 1)
        tp.typeface = condensed; tp.textAlign = Paint.Align.CENTER; tp.textSize = 15 * dp; tp.color = ink
        val nm = TextUtils.ellipsize(skin().name.uppercase(), tp, btnName.width() - 16 * dp, TextUtils.TruncateAt.END).toString()
        c.drawText(nm, btnName.centerX(), btnName.centerY() + tp.textSize * 0.36f, tp)

        pill(c, btnShelf, false); pill(c, btnAmb, ambientOn); pill(c, btnSnd, !muted)
        iconLabel(c, btnShelf, 0, "RECIENTES")
        iconLabel(c, btnAmb, 1, "AMBIENTE")
        iconLabel(c, btnSnd, if (muted) 3 else 2, if (muted) "SIN SONIDO" else "SONIDOS")
        val sl = sleepLabel
        pill(c, btnTimer, sl != null)
        if (sl != null && compact) {
            tp.typeface = condensed; tp.textAlign = Paint.Align.CENTER; tp.textSize = 11 * dp; tp.color = 0xFFFFFFFF.toInt()
            c.drawText(sl.take(5), btnTimer.centerX(), btnTimer.centerY() + tp.textSize * 0.36f, tp)
        } else iconLabel(c, btnTimer, 4, sl ?: "DORMIR")
    }

    private fun arrow(c: Canvas, r: RectF, dir: Int) {
        val s = r.height() * 0.2f
        val path = Path()
        path.moveTo(r.centerX() - s * 0.6f * dir, r.centerY() - s)
        path.lineTo(r.centerX() + s * 0.6f * dir, r.centerY())
        path.lineTo(r.centerX() - s * 0.6f * dir, r.centerY() + s)
        path.close()
        c.drawPath(path, p)
    }

    private fun iconLabel(c: Canvas, r: RectF, icon: Int, label: String) {
        val s = r.height() * 0.22f
        val ix = if (compact) r.centerX() else r.left + r.height() * 0.55f
        val iy = r.centerY()
        p.color = 0xFFFFFFFF.toInt()
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * dp; p.strokeCap = Paint.Cap.ROUND
        when (icon) {
            0 -> for (k in -1..1) c.drawLine(ix - s, iy + k * s * 0.7f, ix + s, iy + k * s * 0.7f, p)
            4 -> {
                c.drawCircle(ix, iy, s, p)
                c.drawLine(ix, iy, ix, iy - s * 0.6f, p)
                c.drawLine(ix, iy, ix + s * 0.45f, iy + s * 0.2f, p)
            }
            1 -> {
                val wp = Path()
                for (k in 0..1) {
                    val yy = iy - s * 0.4f + k * s * 0.8f
                    wp.moveTo(ix - s, yy)
                    wp.cubicTo(ix - s * 0.5f, yy - s * 0.5f, ix, yy + s * 0.5f, ix + s * 0.5f, yy)
                    wp.lineTo(ix + s, yy - s * 0.2f)
                }
                c.drawPath(wp, p)
            }
            else -> {
                p.style = Paint.Style.FILL
                val sp = Path()
                sp.moveTo(ix - s, iy - s * 0.35f); sp.lineTo(ix - s * 0.5f, iy - s * 0.35f); sp.lineTo(ix, iy - s * 0.9f)
                sp.lineTo(ix, iy + s * 0.9f); sp.lineTo(ix - s * 0.5f, iy + s * 0.35f); sp.lineTo(ix - s, iy + s * 0.35f); sp.close()
                c.drawPath(sp, p)
                p.style = Paint.Style.STROKE
                if (icon == 2) {
                    c.drawArc(RectF(ix - s * 0.2f, iy - s * 0.6f, ix + s * 0.6f, iy + s * 0.6f), -50f, 100f, false, p)
                } else {
                    c.drawLine(ix + s * 0.3f, iy - s * 0.4f, ix + s, iy + s * 0.4f, p)
                    c.drawLine(ix + s * 0.3f, iy + s * 0.4f, ix + s, iy - s * 0.4f, p)
                }
            }
        }
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        if (!compact) {
            tp.typeface = condensed; tp.textAlign = Paint.Align.LEFT; tp.textSize = 13 * dp; tp.color = 0xFFFFFFFF.toInt()
            c.drawText(label, ix + s * 1.6f, iy + tp.textSize * 0.36f, tp)
        }
    }

    private fun drawInfo(c: Canvas) {
        val d = deck
        val s = skin()
        val portrait = height > width
        tp.typeface = condensed; tp.textAlign = Paint.Align.LEFT
        val t1: String; val t2: String; val red: Boolean
        when {
            !d.connected -> { t1 = "Toca el aparato para conectar tu música"; t2 = d.status; red = true }
            !d.hasTrack -> { t1 = "Sin música"; t2 = "STOP / EJECT abre tus recientes"; red = false }
            else -> { t1 = d.title; t2 = listOf(d.artist, d.album).filter { it.isNotEmpty() }.joinToString(" · "); red = false }
        }
        val barH = 4 * dp
        val barY = info.bottom - barH
        if (portrait) {
            tp.textSize = 19 * dp; tp.color = 0xFFFFFFFF.toInt()
            c.drawText(TextUtils.ellipsize(t1, tp, info.width(), TextUtils.TruncateAt.END).toString(), info.left, info.top + 20 * dp, tp)
            tp.textSize = 14 * dp; tp.color = if (red) 0xFFFF8A80.toInt() else 0xB3FFFFFF.toInt()
            c.drawText(TextUtils.ellipsize(t2, tp, info.width(), TextUtils.TruncateAt.END).toString(), info.left, info.top + 40 * dp, tp)
        } else {
            tp.textSize = 16 * dp; tp.color = 0xFFFFFFFF.toInt()
            val tw = info.width() * 0.5f
            c.drawText(TextUtils.ellipsize(t1, tp, tw, TextUtils.TruncateAt.END).toString(), info.left, info.top + 18 * dp, tp)
            tp.textSize = 13 * dp; tp.color = if (red) 0xFFFF8A80.toInt() else 0xB3FFFFFF.toInt()
            tp.textAlign = Paint.Align.RIGHT
            c.drawText(TextUtils.ellipsize(t2, tp, info.width() * 0.45f, TextUtils.TruncateAt.END).toString(), info.right, info.top + 18 * dp, tp)
        }
        if (d.hasTrack) {
            p.color = 0x33FFFFFF
            c.drawRoundRect(RectF(info.left, barY, info.right, barY + barH), barH, barH, p)
            p.color = s.accent
            c.drawRoundRect(RectF(info.left, barY, info.left + info.width() * d.progress(), barY + barH), barH, barH, p)
            tp.textSize = 11 * dp; tp.color = 0x99FFFFFF.toInt()
            tp.textAlign = Paint.Align.LEFT
            c.drawText(fmt(d.pos()), info.left, barY - 4 * dp, tp)
            tp.textAlign = Paint.Align.RIGHT
            c.drawText(fmt(d.durationMs), info.right, barY - 4 * dp, tp)
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun drawKeys(c: Canvas, dt: Float) {
        val s = skin()
        p.reset(); p.isAntiAlias = true
        p.color = 0xFF050506.toInt()
        c.drawRoundRect(RectF(keyRects[0].left - 6 * dp, keyRects[0].top - 6 * dp, keyRects[4].right + 6 * dp, keyRects[0].bottom + 6 * dp), keyH * 0.2f, keyH * 0.2f, p)
        for (i in 0 until 5) {
            val down = pressedKey == i || (i == 2 && deck.isPlaying)
            keyAnim[i] += ((if (down) 1f else 0f) - keyAnim[i]) * min(1f, dt * 28f)
            val k = keyRects[i]
            val off = keyAnim[i] * keyH * 0.08f
            val r = RectF(k.left, k.top + off, k.right, k.bottom)
            val play = i == 2
            val light = if (play) lighten(s.accent) else s.keyLight
            val dark = if (play) s.accent else s.keyDark
            val ink = if (play) 0xFF1A1208.toInt() else s.keyInk
            p.color = darken(dark)
            c.drawRoundRect(r, keyH * 0.14f, keyH * 0.14f, p)
            val face = RectF(r.left, r.top, r.right, r.bottom - keyH * 0.09f * (1f - keyAnim[i]) - keyH * 0.02f)
            p.shader = LinearGradient(0f, face.top, 0f, face.bottom, light, dark, Shader.TileMode.CLAMP)
            c.drawRoundRect(face, keyH * 0.14f, keyH * 0.14f, p)
            p.shader = null
            p.color = ink
            symbol(c, i, face.centerX(), face.top + face.height() * 0.40f, face.height() * 0.17f)
            tp.typeface = condensed; tp.textAlign = Paint.Align.CENTER
            tp.textSize = min(face.height() * 0.17f, face.width() * 0.16f)
            tp.color = ink
            c.drawText(keyNames[i], face.centerX(), face.top + face.height() * 0.82f, tp)
        }
    }

    private fun lighten(c: Int): Int {
        val r = ((c shr 16) and 0xFF); val g = ((c shr 8) and 0xFF); val b = (c and 0xFF)
        return (0xFF shl 24) or (minOf(255, r + 50) shl 16) or (minOf(255, g + 50) shl 8) or minOf(255, b + 50)
    }

    private fun darken(c: Int): Int {
        val r = ((c shr 16) and 0xFF) / 2; val g = ((c shr 8) and 0xFF) / 2; val b = (c and 0xFF) / 2
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun symbol(c: Canvas, i: Int, cx: Float, cy: Float, s: Float) {
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

    // ================= toques =================

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                when {
                    btnPrev.contains(x, y) -> { changeSkin(-1); return true }
                    btnNext.contains(x, y) -> { changeSkin(1); return true }
                    btnName.contains(x, y) -> { listener?.onSkinMenu(); return true }
                    btnShelf.contains(x, y) -> { tapFx(); listener?.onOpenShelf(); return true }
                    btnAmb.contains(x, y) -> { tapFx(); listener?.onToggleAmbient(); return true }
                    btnSnd.contains(x, y) -> { tapFx(); listener?.onToggleMute(); return true }
                    btnTimer.contains(x, y) -> { tapFx(); listener?.onTimerMenu(); return true }
                }
                for (i in 0 until 5) if (keyHit[i].contains(x, y)) { pressKey(i); return true }
                val s = skin()
                if (s.r.contains(x, y)) {
                    if (deck.connected && s.touch(e, deck)) { skinTouch = true; return true }
                    devDown = true; downX = x; downY = y; swipeOff = 0f
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (skinTouch) skin().touch(e, deck)
                else if (devDown) swipeOff = x - downX
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (skinTouch) { skin().touch(e, deck); skinTouch = false }
                if (devDown) {
                    devDown = false
                    val dx = x - downX; val dy = y - downY
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        if (kotlin.math.abs(dx) > device.width() * 0.18f && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                            tapFx()
                            listener?.onSkip(if (dx < 0) 1 else -1)
                        } else if (kotlin.math.abs(dx) < 14 * dp && kotlin.math.abs(dy) < 14 * dp) {
                            listener?.onDeviceTap()
                        }
                    }
                }
                if (pressedKey >= 0) releaseKey(e.actionMasked == MotionEvent.ACTION_UP)
            }
        }
        return true
    }

    private fun tapFx() { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    private fun changeSkin(dir: Int) {
        tapFx()
        switchSkin(skinIndex + dir)
        listener?.onSkinChanged(skinIndex)
    }

    private fun pressKey(i: Int) {
        pressedKey = i
        listener?.onKeySound(true)
        tapFx()
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
                deck.scanDir = 0
                listener?.onScanEnd()
            } else if (up) {
                listener?.onSkip(if (i == 0) -1 else 1)
            }
        }
        pressedKey = -1
        listener?.onKeySound(false)
    }
}

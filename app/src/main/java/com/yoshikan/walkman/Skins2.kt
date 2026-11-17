package com.yoshikan.walkman

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

// =====================================================================
// 4. Radio de bulbos
// =====================================================================
class RadioSkin : Skin() {
    override val name = "Radio de bulbos"
    override val aspect = 1.55f
    override val kind = SoundKind.RADIO
    override val ambient = Ambient.HUM
    override val shelfTitle = "ESTACIONES"
    override val thumbShape = 0
    override val accent = col(0xFFF2B45A)
    override val bgTop = col(0xFF3B2A1E)
    override val bgBottom = col(0xFF110B07)
    override val keyLight = col(0xFFE9DCC0)
    override val keyDark = col(0xFFB9A57E)
    override val keyInk = col(0xFF3A2614)
    override val shelfBg = col(0xF0241810)
    private var warm = 0f
    private var k1x = 0f; private var k1y = 0f; private var k2x = 0f; private var k2y = 0f; private var kr = 0f

    override fun onLayout() {
        k1x = X(0.68f); k1y = Y(0.72f); k2x = X(0.87f); k2y = Y(0.72f); kr = H * 0.09f
    }

    override fun draw(c: Canvas, d: Deck, dt: Float, now: Long) {
        reset()
        warm = ease(warm, if (d.connected && d.isPlaying) 1f else if (d.connected) 0.35f else 0f, dt, 0.9f)
        val cab = RectF(r.left, r.top, r.right, Y(0.94f))
        val path = Path()
        val tr = H * 0.22f; val br = H * 0.04f
        path.addRoundRect(cab, floatArrayOf(tr, tr, tr, tr, br, br, br, br), Path.Direction.CW)
        p.color = col(0xFF000000)
        p.setShadowLayer(H * 0.05f, 0f, H * 0.025f, col(0xCC000000))
        c.drawPath(path, p)
        p.clearShadowLayer()
        wood(c, path, cab, col(0xFF9A5E2C), col(0xFF4A2A12), 11)
        // patas
        p.color = col(0xFF2A1A0E)
        c.drawRect(X(0.10f), Y(0.94f), X(0.18f), Y(1f), p)
        c.drawRect(X(0.82f), Y(0.94f), X(0.90f), Y(1f), p)
        // filo dorado
        val inner = RectF(X(0.035f), Y(0.05f), X(0.965f), Y(0.90f))
        val ip = Path(); val itr = tr * 0.8f
        ip.addRoundRect(inner, floatArrayOf(itr, itr, itr, itr, br, br, br, br), Path.Direction.CW)
        p.style = Paint.Style.STROKE; p.strokeWidth = H * 0.01f; p.color = col(0xFFC9A45C)
        c.drawPath(ip, p)
        p.style = Paint.Style.FILL

        // rejilla de tela con luz de bulbos
        val g = RectF(X(0.07f), Y(0.17f), X(0.55f), Y(0.84f))
        p.color = col(0xFF7A5A38)
        c.drawRoundRect(g, H * 0.03f, H * 0.03f, p)
        p.shader = RadialGradient(g.centerX(), g.bottom, g.height(), (((0x66 * warm).toInt() shl 24) or 0xFFB347), 0x00FFB347, Shader.TileMode.CLAMP)
        c.drawRoundRect(g, H * 0.03f, H * 0.03f, p)
        p.shader = null
        c.save(); c.clipRect(g)
        p.strokeWidth = 1f
        val step = H * 0.012f
        var y = g.top
        while (y < g.bottom) { p.color = 0x22000000; c.drawLine(g.left, y, g.right, y, p); y += step }
        var x = g.left
        while (x < g.right) { p.color = 0x16FFFFFF; c.drawLine(x, g.top, x, g.bottom, p); x += step }
        c.restore()
        for (k in 1..3) {
            val bx = g.left + g.width() * k / 4f
            p.shader = LinearGradient(bx - H * 0.015f, 0f, bx + H * 0.015f, 0f, col(0xFF6E3E1A), col(0xFFB07A44), Shader.TileMode.MIRROR)
            c.drawRect(bx - H * 0.015f, g.top, bx + H * 0.015f, g.bottom, p)
            p.shader = null
        }

        // cuadrante
        val dial = RectF(X(0.60f), Y(0.15f), X(0.94f), Y(0.50f))
        p.color = col(0xFF1A120A)
        c.drawRoundRect(dial, H * 0.03f, H * 0.03f, p)
        val glowA = (0xEE * (0.25f + 0.75f * warm)).toInt()
        p.shader = LinearGradient(0f, dial.top, 0f, dial.bottom, (glowA shl 24) or 0xF2D29A, (glowA shl 24) or 0xC98A3A, Shader.TileMode.CLAMP)
        val dIn = RectF(dial.left + H * 0.015f, dial.top + H * 0.015f, dial.right - H * 0.015f, dial.bottom - H * 0.015f)
        c.drawRoundRect(dIn, H * 0.02f, H * 0.02f, p)
        p.shader = null
        val nums = arrayOf("55", "60", "70", "80", "100", "120", "140", "160")
        for (i in nums.indices) {
            val nx = dIn.left + dIn.width() * (0.07f + 0.86f * i / (nums.size - 1))
            text(c, nums[i], nx, dIn.top + dIn.height() * 0.30f, dIn.height() * 0.13f, col(0xFF3A2410))
            p.color = col(0xFF3A2410); p.strokeWidth = 2f
            c.drawLine(nx, dIn.top + dIn.height() * 0.36f, nx, dIn.top + dIn.height() * 0.46f, p)
        }
        p.strokeWidth = 1.5f
        c.drawLine(dIn.left + dIn.width() * 0.05f, dIn.top + dIn.height() * 0.46f, dIn.right - dIn.width() * 0.05f, dIn.top + dIn.height() * 0.46f, p)
        text(c, lineOne(d), dIn.centerX(), dIn.top + dIn.height() * 0.68f, dIn.height() * 0.14f, col(0xFF2A1808), maxW = dIn.width() * 0.9f, tf = serif)
        text(c, lineTwo(d), dIn.centerX(), dIn.top + dIn.height() * 0.86f, dIn.height() * 0.11f, if (d.connected) col(0xFF5A3A18) else col(0xFFA02010), maxW = dIn.width() * 0.9f)
        val nx = dIn.left + dIn.width() * (0.07f + 0.86f * (if (preview >= 0f) preview else d.progress()))
        p.color = col(0xFFD02010); p.strokeWidth = H * 0.008f
        c.drawLine(nx, dIn.top + dIn.height() * 0.05f, nx, dIn.bottom - dIn.height() * 0.05f, p)
        // vidrio
        p.color = 0x22FFFFFF
        c.drawRect(dial.left, dial.top, dial.right, dial.top + dial.height() * 0.15f, p)

        text(c, "Ulibertad", X(0.77f), Y(0.585f), H * 0.065f, col(0xFFE2C27A), tf = serif)

        knob(c, k1x, k1y, kr, -60f)
        knob(c, k2x, k2y, kr, -140f + 280f * (if (preview >= 0f) preview else d.progress()))
        text(c, "◀ ANTERIOR", k1x, k1y + kr * 1.55f, H * 0.035f, col(0xFFE2C27A))
        text(c, "GIRA · SINTONÍA ▶", k2x, k2y + kr * 1.55f, H * 0.035f, col(0xFFE2C27A))
        // foco piloto
        p.color = if (d.connected) col(0xFFFFC04D) else col(0xFF5A3A1A)
        c.drawCircle(X(0.77f), Y(0.66f), H * 0.012f, p)
    }

    private fun knob(c: Canvas, x: Float, y: Float, rad: Float, ang: Float) {
        p.color = 0x66000000
        c.drawCircle(x + rad * 0.08f, y + rad * 0.12f, rad, p)
        p.shader = RadialGradient(x - rad * 0.3f, y - rad * 0.3f, rad * 1.3f, col(0xFF8A5530), col(0xFF2A160A), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, rad, p)
        p.shader = null
        p.color = 0x33000000
        for (k in 0 until 24) {
            val a = Math.toRadians(k * 15.0).toFloat()
            c.drawCircle(x + cos(a) * rad * 0.93f, y + sin(a) * rad * 0.93f, rad * 0.05f, p)
        }
        p.color = col(0xFFC9A45C)
        c.drawCircle(x, y, rad * 0.45f, p)
        val a = Math.toRadians(ang.toDouble() - 90.0).toFloat()
        p.color = col(0xFF2A160A); p.strokeWidth = rad * 0.1f; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(x + cos(a) * rad * 0.15f, y + sin(a) * rad * 0.15f, x + cos(a) * rad * 0.4f, y + sin(a) * rad * 0.4f, p)
        p.strokeCap = Paint.Cap.BUTT
    }

    private var knobDrag = false
    private var knobLast = 0f
    private var knobAcc = 0f
    private var preview = -1f

    override fun touch(e: MotionEvent, d: Deck): Boolean {
        val a = Math.toDegrees(kotlin.math.atan2((e.y - k2y).toDouble(), (e.x - k2x).toDouble())).toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (hypot(e.x - k1x, e.y - k1y) < kr * 1.5f) { onSkip?.invoke(-1); return true }
                if (hypot(e.x - k2x, e.y - k2y) < kr * 1.6f) { knobDrag = true; knobLast = a; knobAcc = 0f; return true }
                return false
            }
            MotionEvent.ACTION_MOVE -> if (knobDrag) {
                var da = a - knobLast
                if (da > 180f) da -= 360f
                if (da < -180f) da += 360f
                knobLast = a; knobAcc += da
                if (d.hasTrack) preview = (d.progress() + knobAcc / 300f).coerceIn(0f, 0.995f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (knobDrag) {
                knobDrag = false
                if (kotlin.math.abs(knobAcc) < 6f) onSkip?.invoke(1) else if (preview >= 0f) onSeek?.invoke(preview)
                preview = -1f
            }
        }
        return true
    }
}

// =====================================================================
// 5. Rocola
// =====================================================================
class JukeboxSkin : Skin() {
    override val name = "Rocola"
    override val aspect = 0.80f
    override val kind = SoundKind.JUKEBOX
    override val ambient = Ambient.CRACKLE
    override val shelfTitle = "SELECCIÓN DE LA ROCOLA"
    override val thumbShape = 1
    override val accent = col(0xFFFF4D6D)
    override val bgTop = col(0xFF2A1430)
    override val bgBottom = col(0xFF0C0510)
    override val shelfBg = col(0xF01A0C1E)
    private var recAng = 0f
    private var armLift = 1f
    private val mtx = Matrix()

    override fun draw(c: Canvas, d: Deck, dt: Float, now: Long) {
        reset()
        recAng += dt * 280f * d.spin
        armLift = ease(armLift, if (d.isPlaying) 0f else 1f, dt, 4f)
        val ccx = X(0.5f); val acy = Y(0.36f); val ar = W * 0.44f
        val body = Path()
        body.moveTo(X(0.06f), Y(1f))
        body.lineTo(X(0.06f), acy)
        body.arcTo(RectF(ccx - ar, acy - ar, ccx + ar, acy + ar), 180f, 180f)
        body.lineTo(X(0.94f), Y(1f))
        body.close()
        p.color = col(0xFF000000)
        p.setShadowLayer(H * 0.04f, 0f, H * 0.02f, col(0xCC000000))
        c.drawPath(body, p)
        p.clearShadowLayer()
        wood(c, body, r, col(0xFF8E2F2A), col(0xFF3E1010), 21)

        // tubo de burbujas de colores
        val tubeR = ar * 0.88f
        val glow = if (d.connected) 0.55f + 0.45f * d.level else 0.25f
        val sg = SweepGradient(ccx, acy, intArrayOf(col(0xFFFF3B3B), col(0xFFFF9F1C), col(0xFFFFE14D), col(0xFF4DFF88), col(0xFF4DD2FF), col(0xFF7A5CFF), col(0xFFFF4DD8), col(0xFFFF3B3B)), null)
        mtx.setRotate((now / 25L % 360L).toFloat(), ccx, acy)
        sg.setLocalMatrix(mtx)
        p.shader = sg
        p.style = Paint.Style.STROKE
        p.strokeWidth = W * 0.07f
        p.alpha = (255 * glow).toInt()
        val tubeOval = RectF(ccx - tubeR, acy - tubeR, ccx + tubeR, acy + tubeR)
        c.drawArc(tubeOval, 180f, 180f, false, p)
        p.shader = null
        p.alpha = 255
        // columnas laterales
        p.strokeCap = Paint.Cap.BUTT
        c.drawLine(ccx - tubeR, acy, ccx - tubeR, Y(0.97f), p.apply { shader = sg; alpha = (255 * glow).toInt() })
        c.drawLine(ccx + tubeR, acy, ccx + tubeR, Y(0.97f), p)
        p.shader = null; p.alpha = 255
        p.style = Paint.Style.FILL
        // burbujas
        p.color = 0xAAFFFFFF.toInt()
        for (k in 0 until 10) {
            val t = ((now / 4000f) + k / 10f) % 1f
            val a = Math.toRadians((180.0 + 180.0 * t)).toFloat()
            c.drawCircle(ccx + cos(a) * tubeR, acy + sin(a) * tubeR, W * 0.010f, p)
        }
        // chrome
        p.style = Paint.Style.STROKE; p.strokeWidth = W * 0.012f; p.color = col(0xFFDADDE2)
        c.drawArc(RectF(ccx - ar * 0.78f, acy - ar * 0.78f, ccx + ar * 0.78f, acy + ar * 0.78f), 180f, 180f, false, p)
        p.style = Paint.Style.FILL

        // ventana con el tocadiscos
        val win = RectF(X(0.20f), Y(0.17f), X(0.80f), Y(0.52f))
        p.shader = LinearGradient(0f, win.top, 0f, win.bottom, col(0xFF1A1020), col(0xFF050207), Shader.TileMode.CLAMP)
        c.drawRoundRect(win, W * 0.05f, W * 0.05f, p)
        p.shader = null
        // magazine de discos atrás
        p.color = col(0xFF2A2A2A)
        for (k in 0 until 9) {
            val mx = win.left + win.width() * (0.12f + 0.095f * k)
            c.drawOval(RectF(mx - W * 0.012f, win.top + win.height() * 0.08f, mx + W * 0.012f, win.top + win.height() * 0.45f), p)
        }
        // disco en perspectiva
        val rcx = win.centerX(); val rcy = win.top + win.height() * 0.70f; val rr = win.width() * 0.36f
        c.save()
        c.translate(rcx, rcy)
        c.scale(1f, 0.32f)
        p.color = col(0xFF0A0A0A)
        c.drawCircle(0f, 0f, rr, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f
        var gr = rr * 0.4f
        while (gr < rr) { p.color = 0x18FFFFFF; c.drawCircle(0f, 0f, gr, p); gr += rr * 0.06f }
        p.style = Paint.Style.FILL
        artCircle(c, d.art, 0f, 0f, rr * 0.34f, recAng, col(0xFFFFD34D))
        c.restore()
        // brazo
        val ax = win.left + win.width() * 0.88f; val ay = win.top + win.height() * 0.40f
        val tx = rcx + rr * 0.55f; val ty = rcy - win.height() * 0.05f - armLift * win.height() * 0.10f
        p.color = col(0xFFCFCFCF); p.strokeWidth = W * 0.012f; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(ax, ay, tx, ty, p)
        p.strokeCap = Paint.Cap.BUTT
        p.color = 0x22FFFFFF
        c.drawRect(win.left, win.top, win.right, win.top + win.height() * 0.12f, p)

        // tarjetas de selección
        val panel = RectF(X(0.14f), Y(0.56f), X(0.86f), Y(0.73f))
        grad(c, panel, W * 0.02f, col(0xFFE8EAEE), col(0xFF9DA3AD))
        val cur = if (d.hasTrack) (d.trackNo - 1).coerceAtLeast(0) % 8 else -1
        for (k in 0 until 8) {
            val col4 = k % 4; val row = k / 4
            val cw = panel.width() * 0.22f; val ch = panel.height() * 0.38f
            val cl = panel.left + panel.width() * 0.03f + col4 * (cw + panel.width() * 0.025f)
            val ct = panel.top + panel.height() * 0.08f + row * (ch + panel.height() * 0.08f)
            val card = RectF(cl, ct, cl + cw, ct + ch)
            p.color = if (k == cur) col(0xFFFFE27A) else col(0xFFF8F1E0)
            c.drawRect(card, p)
            text(c, (if (row == 0) "A" else "B") + (col4 + 1), card.left + cw * 0.12f, card.top + ch * 0.42f, ch * 0.32f, col(0xFFC0392B), Paint.Align.LEFT, black)
            val t = if (k == cur) d.title else ""
            if (t.isNotEmpty()) text(c, t, card.left + cw * 0.05f, card.top + ch * 0.85f, ch * 0.24f, col(0xFF1C2F5A), Paint.Align.LEFT, maxW = cw * 0.9f)
        }
        if (!d.connected) text(c, lineOne(d), panel.centerX(), panel.bottom + H * 0.03f, H * 0.025f, col(0xFFFFE27A), maxW = panel.width())

        // rejilla
        val grill = RectF(X(0.14f), Y(0.78f), X(0.86f), Y(0.96f))
        p.color = col(0xFF1A0A0A)
        c.drawRoundRect(grill, W * 0.02f, W * 0.02f, p)
        p.shader = RadialGradient(grill.centerX(), grill.centerY(), grill.width() * 0.5f, (((0x99 * glow).toInt() shl 24) or 0xFF6A3D), 0x00FF6A3D, Shader.TileMode.CLAMP)
        c.drawRoundRect(grill, W * 0.02f, W * 0.02f, p)
        p.shader = null
        for (k in 0 until 9) {
            val gx = grill.left + grill.width() * (0.06f + 0.11f * k)
            grad(c, RectF(gx - W * 0.008f, grill.top, gx + W * 0.008f, grill.bottom), W * 0.008f, col(0xFFF0F2F5), col(0xFF8D949E))
        }
    }
}

// =====================================================================
// 6. 8-Track
// =====================================================================
class EightTrackSkin : Skin() {
    override val name = "8-Track"
    override val aspect = 1.5f
    override val kind = SoundKind.EIGHT
    override val ambient = Ambient.HISS
    override val shelfTitle = "CARTUCHOS"
    override val thumbShape = 3
    override val accent = col(0xFFF4C430)
    override val bgTop = col(0xFF3A2A12)
    override val bgBottom = col(0xFF120C04)
    override val shelfBg = col(0xF0201608)
    private var insert = 0f
    private var tapeOff = 0f

    override fun draw(c: Canvas, d: Deck, dt: Float, now: Long) {
        reset()
        insert = ease(insert, if (d.hasTrack) 1f else 0f, dt, 5f)
        tapeOff += dt * 60f * d.spin
        val lift = (1f - insert) * H * 0.10f

        // cartucho
        val cart = RectF(X(0.20f), Y(0.02f) - lift, X(0.80f), Y(0.56f) - lift)
        val cp = Path()
        val cr = H * 0.04f
        cp.addRoundRect(cart, floatArrayOf(cr, cr, cr, cr, 0f, 0f, 0f, 0f), Path.Direction.CW)
        p.color = col(0xFF000000)
        p.setShadowLayer(H * 0.03f, 0f, H * 0.015f, col(0xAA000000))
        c.drawPath(cp, p)
        p.clearShadowLayer()
        p.shader = LinearGradient(0f, cart.top, 0f, cart.bottom, col(0xFF3C3C40), col(0xFF1A1A1D), Shader.TileMode.CLAMP)
        c.drawPath(cp, p)
        p.shader = null
        p.color = 0x33FFFFFF
        for (k in 0 until 10) c.drawRect(cart.left + cart.width() * (0.05f + 0.09f * k), cart.top + H * 0.015f, cart.left + cart.width() * (0.09f + 0.09f * k), cart.top + H * 0.035f, p)
        val lab = RectF(cart.left + cart.width() * 0.05f, cart.top + H * 0.06f, cart.right - cart.width() * 0.05f, cart.top + H * 0.40f)
        p.color = col(0xFFF5EBD2)
        c.drawRoundRect(lab, H * 0.01f, H * 0.01f, p)
        p.color = col(0xFFE0662B)
        c.drawRect(lab.left, lab.top, lab.right, lab.top + lab.height() * 0.18f, p)
        text(c, "8-TRACK STEREO CARTRIDGE", lab.centerX(), lab.top + lab.height() * 0.13f, lab.height() * 0.10f, col(0xFFFFF5DC), tf = black)
        val ar = RectF(lab.left + lab.width() * 0.03f, lab.top + lab.height() * 0.25f, lab.left + lab.width() * 0.03f + lab.height() * 0.68f, lab.top + lab.height() * 0.93f)
        artRect(c, d.art, ar, col(0xFFCDA85A))
        val tx = ar.right + lab.width() * 0.04f
        val tw = lab.right - tx - lab.width() * 0.03f
        text(c, lineOne(d), tx, lab.top + lab.height() * 0.45f, lab.height() * 0.14f, col(0xFF2A1C0A), Paint.Align.LEFT, hand, tw)
        text(c, lineTwo(d), tx, lab.top + lab.height() * 0.62f, lab.height() * 0.11f, if (d.connected) col(0xFF6A4A20) else col(0xFFA02010), Paint.Align.LEFT, maxW = tw)
        // ventanita de cinta
        val tw2 = RectF(tx, lab.top + lab.height() * 0.72f, tx + tw * 0.6f, lab.top + lab.height() * 0.90f)
        p.color = col(0xFF141414); c.drawRect(tw2, p)
        c.save(); c.clipRect(tw2)
        p.color = col(0xFF6A4528)
        var sx = tw2.left - (tapeOff % (tw2.height() * 2f))
        while (sx < tw2.right) { c.drawRect(sx, tw2.top + tw2.height() * 0.3f, sx + tw2.height(), tw2.bottom - tw2.height() * 0.3f, p); sx += tw2.height() * 2f }
        c.restore()

        // reproductor
        val body = RectF(r.left, Y(0.46f), r.right, r.bottom)
        val bpth = Path(); bpth.addRoundRect(body, H * 0.04f, H * 0.04f, Path.Direction.CW)
        p.color = col(0xFF000000)
        p.setShadowLayer(H * 0.04f, 0f, H * 0.02f, col(0xCC000000))
        c.drawPath(bpth, p)
        p.clearShadowLayer()
        wood(c, bpth, body, col(0xFF7A4A22), col(0xFF3A200C), 31)
        val front = RectF(X(0.04f), Y(0.50f), X(0.96f), Y(0.95f))
        grad(c, front, H * 0.02f, col(0xFFE4E7EB), col(0xFF9CA3AD))
        p.color = col(0xFF0A0A0A)
        c.drawRoundRect(RectF(X(0.18f), Y(0.515f), X(0.82f), Y(0.565f)), H * 0.01f, H * 0.01f, p)

        // luces de programa
        val cur = if (d.hasTrack) (d.trackNo - 1).coerceAtLeast(0) % 4 else -1
        for (k in 0 until 4) {
            val lx = X(0.32f + 0.12f * k); val ly = Y(0.66f)
            val on = k == cur
            if (on) {
                p.shader = RadialGradient(lx, ly, H * 0.06f, col(0x88FFB000), 0x00FFB000, Shader.TileMode.CLAMP)
                c.drawCircle(lx, ly, H * 0.06f, p)
                p.shader = null
            }
            p.color = if (on) col(0xFFFFC23A) else col(0xFF6A5420)
            c.drawCircle(lx, ly, H * 0.022f, p)
            text(c, "${k + 1}", lx, ly + H * 0.07f, H * 0.04f, col(0xFF3A3F47))
        }
        text(c, "PROGRAM", X(0.5f), Y(0.61f), H * 0.03f, col(0xFF3A3F47))
        knob(c, X(0.13f), Y(0.73f), H * 0.08f)
        knob(c, X(0.87f), Y(0.73f), H * 0.08f)
        text(c, "UL 8-TRACK", X(0.5f), Y(0.90f), H * 0.05f, col(0xFF2A2F37), tf = serif)
    }

    private fun knob(c: Canvas, x: Float, y: Float, rad: Float) {
        p.shader = RadialGradient(x - rad * 0.3f, y - rad * 0.3f, rad * 1.3f, col(0xFFF5F5F5), col(0xFF6E747C), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, rad, p)
        p.shader = null
        p.color = col(0xFF2A2F37)
        c.drawCircle(x, y - rad * 0.6f, rad * 0.1f, p)
    }
}

// =====================================================================
// 7. Grabadora de carrete abierto
// =====================================================================
class ReelSkin : Skin() {
    override val name = "Carrete abierto"
    override val aspect = 0.92f
    override val kind = SoundKind.TAPE
    override val ambient = Ambient.HISS
    override val shelfTitle = "CINTAS MAESTRAS"
    override val thumbShape = 0
    override val accent = col(0xFF59C2FF)
    override val shelfBg = col(0xF0101418)
    private var angL = 0f
    private var angR = 0f
    private var capAng = 0f
    private var needleL = 0f
    private var needleR = 0f

    override fun draw(c: Canvas, d: Deck, dt: Float, now: Long) {
        reset()
        val prog = d.progress()
        val reelR = W * 0.215f
        val pMin = reelR * 0.30f; val pMax = reelR * 0.90f
        val lp = pMin + (pMax - pMin) * (1f - prog)
        val rp = pMin + (pMax - pMin) * prog
        val v = reelR * 1.6f * d.spin
        angL += dt * v / lp * 57.3f
        angR += dt * v / rp * 57.3f
        capAng += dt * 600f * d.spin
        needleL = ease(needleL, if (d.isPlaying) d.levelL else 0f, dt, 10f)
        needleR = ease(needleR, if (d.isPlaying) d.levelR else 0f, dt, 10f)

        p.color = col(0xFF000000)
        p.setShadowLayer(H * 0.04f, 0f, H * 0.02f, col(0xCC000000))
        c.drawRoundRect(r, W * 0.04f, W * 0.04f, p)
        p.clearShadowLayer()
        grad(c, r, W * 0.04f, col(0xFF2A2C30), col(0xFF141518))
        val face = RectF(X(0.035f), Y(0.03f), X(0.965f), Y(0.97f))
        grad(c, face, W * 0.03f, col(0xFFDADDE2), col(0xFFA4AAB3))

        val lx = X(0.27f); val rx = X(0.73f); val ry = Y(0.25f)
        val g1x = X(0.12f); val g2x = X(0.88f); val gy = Y(0.53f)
        // cinta
        p.color = col(0xFF4A2E1A); p.strokeWidth = H * 0.006f
        c.drawLine(lx - lp, ry, g1x, gy, p)
        c.drawLine(rx + rp, ry, g2x, gy, p)
        c.drawLine(g1x, gy, g2x, gy, p)
        reel(c, lx, ry, reelR, lp, angL)
        reel(c, rx, ry, reelR, rp, angR)
        // guías y cabezales
        for (gx in floatArrayOf(g1x, g2x)) {
            p.shader = RadialGradient(gx, gy, H * 0.025f, col(0xFFFFFFFF), col(0xFF7A8088), Shader.TileMode.CLAMP)
            c.drawCircle(gx, gy, H * 0.022f, p); p.shader = null
        }
        val head = RectF(X(0.36f), Y(0.50f), X(0.64f), Y(0.585f))
        grad(c, head, H * 0.01f, col(0xFF5A6068), col(0xFF2E3238))
        text(c, "ERASE · REC · PLAY", head.centerX(), head.centerY() + H * 0.01f, H * 0.022f, col(0xFFCDD3DA))
        val cx = X(0.72f); val cy = Y(0.565f)
        c.save(); c.rotate(capAng, cx, cy)
        p.color = col(0xFF141414); c.drawCircle(cx, cy, H * 0.03f, p)
        p.color = col(0xFF555555); c.drawRect(cx - H * 0.004f, cy - H * 0.028f, cx + H * 0.004f, cy - H * 0.012f, p)
        c.restore()

        // vúmetros
        vu(c, RectF(X(0.10f), Y(0.63f), X(0.47f), Y(0.80f)), needleL, d.connected, "L")
        vu(c, RectF(X(0.53f), Y(0.63f), X(0.90f), Y(0.80f)), needleR, d.connected, "R")

        // contador y título
        val cnt = RectF(X(0.38f), Y(0.83f), X(0.62f), Y(0.88f))
        p.color = col(0xFF111111); c.drawRoundRect(cnt, 6f, 6f, p)
        text(c, "%04d".format((d.pos() / 1000) % 10000), cnt.centerX(), cnt.bottom - cnt.height() * 0.2f, cnt.height() * 0.8f, col(0xFFF2F2F2), tf = mono)
        text(c, lineOne(d), X(0.5f), Y(0.925f), H * 0.03f, col(0xFF22262C), maxW = W * 0.85f, tf = hand)
        text(c, lineTwo(d), X(0.5f), Y(0.955f), H * 0.022f, if (d.connected) col(0xFF4A515A) else col(0xFFA02010), maxW = W * 0.85f)
        text(c, "UL REEL-TO-REEL", X(0.08f), Y(0.87f), H * 0.025f, col(0xFF2A2F37), Paint.Align.LEFT, black)
    }

    private fun reel(c: Canvas, x: Float, y: Float, rad: Float, pack: Float, ang: Float) {
        p.color = 0x44000000
        c.drawCircle(x + rad * 0.03f, y + rad * 0.05f, rad, p)
        // bobina de cinta
        p.shader = RadialGradient(x, y, pack, intArrayOf(col(0xFF3A2414), col(0xFF6A4528), col(0xFF4A301E)), floatArrayOf(0f, 0.9f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, pack, p)
        p.shader = null
        // brida metálica con ventanas
        c.save()
        c.rotate(ang, x, y)
        val fl = Path()
        fl.addCircle(x, y, rad, Path.Direction.CW)
        val inner = rad * 0.32f; val outer = rad * 0.86f
        for (k in 0 until 3) {
            val st = k * 120f + 15f
            val cut = Path()
            cut.arcTo(RectF(x - outer, y - outer, x + outer, y + outer), st, 90f, true)
            cut.arcTo(RectF(x - inner, y - inner, x + inner, y + inner), st + 90f, -90f)
            cut.close()
            fl.op(cut, Path.Op.DIFFERENCE)
        }
        p.shader = SweepGradient(x, y, intArrayOf(col(0xCCE8EBEF), col(0xCC9AA1AB), col(0xCCF5F7FA), col(0xCC8E959F), col(0xCCE8EBEF)), null)
        c.drawPath(fl, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = col(0xFF6E757F)
        c.drawPath(fl, p)
        p.style = Paint.Style.FILL
        p.color = col(0xFF2A2D32)
        c.drawCircle(x, y, rad * 0.14f, p)
        p.color = col(0xFFDADDE2)
        for (k in 0 until 3) {
            c.save(); c.rotate(k * 120f, x, y)
            c.drawRect(x - rad * 0.03f, y - rad * 0.14f, x + rad * 0.03f, y - rad * 0.07f, p)
            c.restore()
        }
        c.restore()
    }

    private fun vu(c: Canvas, box: RectF, v: Float, on: Boolean, ch: String) {
        p.color = col(0xFF1A1A1A)
        c.drawRoundRect(box, 8f, 8f, p)
        val inn = RectF(box.left + 6f, box.top + 6f, box.right - 6f, box.bottom - 6f)
        p.color = if (on) col(0xFFF6E3B0) else col(0xFFBFB59A)
        c.drawRoundRect(inn, 6f, 6f, p)
        val pvx = inn.centerX(); val pvy = inn.bottom + inn.height() * 0.35f
        val rad = inn.height() * 1.05f
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f
        p.color = col(0xFF2A2A2A)
        c.drawArc(RectF(pvx - rad, pvy - rad, pvx + rad, pvy + rad), 230f, 55f, false, p)
        p.color = col(0xFFD02010); p.strokeWidth = 4f
        c.drawArc(RectF(pvx - rad, pvy - rad, pvx + rad, pvy + rad), 285f, 25f, false, p)
        p.style = Paint.Style.FILL
        text(c, "VU", inn.centerX(), inn.bottom - inn.height() * 0.12f, inn.height() * 0.18f, col(0xFF2A2A2A), tf = black)
        text(c, ch, inn.left + inn.width() * 0.08f, inn.bottom - inn.height() * 0.12f, inn.height() * 0.16f, col(0xFF2A2A2A))
        val a = Math.toRadians((230.0 + 80.0 * v.coerceIn(0f, 1f))).toFloat()
        p.color = col(0xFF111111); p.strokeWidth = 2.5f
        c.save(); c.clipRect(inn)
        c.drawLine(pvx, pvy, pvx + cos(a) * rad * 1.02f, pvy + sin(a) * rad * 1.02f, p)
        c.restore()
    }
}

// =====================================================================
// 8. Boombox
// =====================================================================
class BoomboxSkin : Skin() {
    override val name = "Boombox"
    override val aspect = 1.9f
    override val kind = SoundKind.TAPE
    override val ambient = Ambient.HISS
    override val shelfTitle = "MIXTAPES"
    override val thumbShape = 3
    override val accent = col(0xFFFF3B6B)
    override val bgTop = col(0xFF1E2230)
    override val bgBottom = col(0xFF07080C)
    private val cass = CassetteArt()
    private val door = RectF()
    private var pulse = 0f

    override fun onLayout() {
        door.set(X(0.345f), Y(0.43f), X(0.655f), Y(0.93f))
        val inset = door.height() * 0.08f
        val avail = RectF(door.left + inset, door.top + inset, door.right - inset, door.bottom - inset)
        var cw = avail.width(); var ch = cw / 1.56f
        if (ch > avail.height()) { ch = avail.height(); cw = ch * 1.56f }
        cass.layout(RectF(avail.centerX() - cw / 2f, avail.centerY() - ch / 2f, avail.centerX() + cw / 2f, avail.centerY() + ch / 2f))
    }

    override fun draw(c: Canvas, d: Deck, dt: Float, now: Long) {
        reset()
        pulse = ease(pulse, if (d.isPlaying) d.level else 0f, dt, 18f)
        // asa y antena
        p.style = Paint.Style.STROKE; p.strokeWidth = H * 0.05f; p.color = col(0xFFB8BEC8); p.strokeCap = Paint.Cap.ROUND
        c.drawRoundRect(RectF(X(0.28f), Y(0.03f), X(0.72f), Y(0.22f)), H * 0.08f, H * 0.08f, p)
        p.strokeWidth = H * 0.012f; p.color = col(0xFFE5E8EC)
        c.drawLine(X(0.88f), Y(0.16f), X(0.995f), Y(0.005f), p)
        p.strokeCap = Paint.Cap.BUTT
        p.style = Paint.Style.FILL
        p.color = col(0xFFE5E8EC); c.drawCircle(X(0.995f), Y(0.005f), H * 0.012f, p)

        val body = RectF(r.left, Y(0.13f), r.right, r.bottom)
        p.color = col(0xFF000000)
        p.setShadowLayer(H * 0.05f, 0f, H * 0.03f, col(0xCC000000))
        c.drawRoundRect(body, H * 0.08f, H * 0.08f, p)
        p.clearShadowLayer()
        grad(c, body, H * 0.08f, col(0xFF3A3F4A), col(0xFF15171C))
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0x44FFFFFF
        c.drawRoundRect(body, H * 0.08f, H * 0.08f, p)
        p.style = Paint.Style.FILL

        speaker(c, X(0.17f), Y(0.585f), H * 0.30f)
        speaker(c, X(0.83f), Y(0.585f), H * 0.30f)

        // ecualizador
        val eq = RectF(X(0.35f), Y(0.17f), X(0.65f), Y(0.36f))
        p.color = col(0xFF05070A); c.drawRoundRect(eq, H * 0.02f, H * 0.02f, p)
        val bw = eq.width() / 10f
        for (i in 0 until 10) {
            val segs = 8
            val lit = (d.bands[i] * segs).toInt()
            for (s in 0 until segs) {
                val sy = eq.bottom - eq.height() * 0.08f - (s + 1) * (eq.height() * 0.84f / segs)
                val on = s < lit
                p.color = when {
                    !on -> 0x22FFFFFF
                    s >= 6 -> col(0xFFFF3B3B)
                    s >= 4 -> col(0xFFFFD23B)
                    else -> col(0xFF3BFF7A)
                }
                c.drawRect(eq.left + bw * i + bw * 0.15f, sy + 1.5f, eq.left + bw * (i + 1) - bw * 0.15f, sy + eq.height() * 0.84f / segs - 1.5f, p)
            }
        }
        text(c, "UL · DOUBLE BASS STEREO", X(0.5f), Y(0.405f), H * 0.035f, col(0xFFE5E8EC), tf = black)

        // puerta con casete
        p.color = col(0xFF0A0B0E); c.drawRoundRect(door, H * 0.03f, H * 0.03f, p)
        cass.draw(c, d, dt, lineOne(d), lineTwo(d), !d.connected)
        p.color = 0x22A0C0FF; c.drawRoundRect(door, H * 0.03f, H * 0.03f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = col(0xFF6E757F)
        c.drawRoundRect(door, H * 0.03f, H * 0.03f, p)
        p.style = Paint.Style.FILL
    }

    private fun speaker(c: Canvas, x: Float, y: Float, rad: Float) {
        p.shader = RadialGradient(x, y, rad, intArrayOf(col(0xFF9DA4AE), col(0xFFF2F4F7), col(0xFF6E757F)), floatArrayOf(0.88f, 0.94f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, rad, p)
        p.shader = null
        val cone = rad * 0.86f
        p.shader = RadialGradient(x, y, cone, intArrayOf(col(0xFF2A2D33), col(0xFF15171B), col(0xFF26292E)), floatArrayOf(0.3f, 0.8f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, cone, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f
        val bump = 1f + pulse * 0.06f
        for (k in 1..4) { p.color = 0x22FFFFFF; c.drawCircle(x, y, cone * (0.35f + k * 0.14f) * bump, p) }
        p.style = Paint.Style.FILL
        val cap = rad * 0.26f * (1f + pulse * 0.18f)
        p.shader = RadialGradient(x - cap * 0.3f, y - cap * 0.3f, cap * 1.4f, col(0xFF5A5F68), col(0xFF101215), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, cap, p)
        p.shader = null
    }
}

fun allSkins(): List<Skin> = listOf(
    CassetteSkin(), TurntableSkin(), DiscmanSkin(), RadioSkin(),
    JukeboxSkin(), EightTrackSkin(), ReelSkin(), BoomboxSkin()
)

@Suppress("unused")
private fun unusedMin(a: Float, b: Float) = min(a, b)

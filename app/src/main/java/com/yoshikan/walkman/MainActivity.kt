package com.yoshikan.walkman

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), WalkmanView.Listener, SourceHost {

    companion object {
        const val AUTH_REQ = 1337
        const val MIC_REQ = 7
    }

    override val ctx: Activity get() = this

    private lateinit var walkman: WalkmanView
    private lateinit var prefs: SharedPreferences
    private lateinit var sounds: Sounds
    private val meter = AudioMeter()
    private val handler = Handler(Looper.getMainLooper())
    private var spotify: MusicSource? = null
    private lateinit var universal: UniversalSource
    private lateinit var source: MusicSource
    private var backCb: Any? = null
    private var lastTrack: String? = null
    private var scanDir = 0
    private var sleepAt = 0L
    private var sleepEndOfTrack = false
    private val scanRunnable = object : Runnable {
        override fun run() {
            if (scanDir != 0) {
                source.seekBy(scanDir * 6000L)
                handler.postDelayed(this, 350)
            }
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            tickSleep()
            handler.postDelayed(this, 1000)
        }
    }

    // estante
    private lateinit var shelf: LinearLayout
    private lateinit var shelfTitle: TextView
    private lateinit var shelfStatus: TextView
    private lateinit var shelfAdapter: ShelfAdapter
    private val rows = mutableListOf<Any>()   // String = encabezado, ShelfEntry = elemento
    private val thumbs = HashMap<ShelfEntry, Bitmap>()

    private val d get() = walkman.deck

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        prefs = getSharedPreferences("walkman", Context.MODE_PRIVATE)
        sounds = Sounds(this)

        spotify = Flavor.createSpotify(this, AUTH_REQ)
        universal = UniversalSource(this)
        val sp = spotify
        source = if (sp != null && prefs.getString("source", "spotify") == "spotify") sp else universal

        val root = FrameLayout(this)
        root.setBackgroundColor(col(0xFF0D0E10))
        walkman = WalkmanView(this, allSkins())
        walkman.listener = this
        walkman.meter = meter
        walkman.skinIndex = prefs.getInt("skin", 0)
        walkman.muted = prefs.getBoolean("muted", false)
        walkman.ambientOn = prefs.getBoolean("ambient", false)
        sounds.muted = walkman.muted
        sounds.kind = walkman.skin().kind
        root.addView(walkman, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shelf = buildShelf()
        shelf.visibility = View.GONE
        root.addView(shelf, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        hideBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            val ic = window.insetsController
            if (ic != null) {
                ic.hide(WindowInsets.Type.systemBars())
                ic.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                return
            }
        }
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onStart() {
        super.onStart()
        source.start()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) meter.start()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        onScanEnd()
        sounds.stopAmbient()
        source.stop()
        meter.stop()
        handler.removeCallbacks(ticker)
        d.connected = false
    }

    override fun onDestroy() {
        super.onDestroy()
        sounds.release()
    }

    // ================= SourceHost =================

    override fun onPlayer(playing: Boolean, posMs: Long, durMs: Long, title: String, artist: String, album: String, trackKey: String?) {
        if (trackKey != null && trackKey != lastTrack) {
            if (lastTrack != null && sleepEndOfTrack) { lastTrack = trackKey; doSleep(); return }
            lastTrack = trackKey
            d.trackNo++
        }
        d.isPlaying = playing
        d.basePosMs = posMs
        d.baseTime = SystemClock.uptimeMillis()
        d.durationMs = if (durMs > 0) durMs else 1L
        d.title = title
        d.artist = artist
        d.album = album
        d.hasTrack = title.isNotEmpty()
        updateAmbient()
        if (playing) askMicOnce()
    }

    override fun onArt(bmp: Bitmap?) { d.art = bmp }

    override fun onConnection(connected: Boolean, status: String) {
        d.connected = connected
        d.status = status
    }

    override fun shelfSection(title: String, entries: List<ShelfEntry>, first: Boolean) {
        val block = mutableListOf<Any>(title)
        block.addAll(entries)
        if (first) rows.addAll(0, block) else rows.addAll(block)
        shelfStatus.text = "${rows.count { it is ShelfEntry }} opciones · toca una para ponerla"
        shelfAdapter.notifyDataSetChanged()
        for (e in entries) e.image { b -> thumbs[e] = b; shelfAdapter.notifyDataSetChanged() }
    }

    override fun shelfStatus(s: String) { shelfStatus.text = s }

    override fun note(s: String) { toast(s) }

    // ================= Spotify (solo edición personal) =================

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AUTH_REQ) return
        Flavor.handleAuth(spotify, this, resultCode, data, source === spotify)
    }

    // ================= micrófono para niveles reales =================

    private fun askMicOnce() {
        if (meter.running) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { meter.start(); return }
        if (prefs.getBoolean("askedMic", false)) return
        prefs.edit().putBoolean("askedMic", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Vúmetros de verdad")
            .setMessage("Para que las agujas, bocinas y ecualizador se muevan con tu música, Android pide el permiso de micrófono. La app no graba nada: solo mide el volumen de lo que suena.")
            .setPositiveButton("Permitir") { _, _ -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQ) }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_REQ && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (!meter.start()) toast("Este teléfono no deja leer el audio; se usa la animación")
        }
    }

    // ================= Teclas y controles =================

    private fun ready(): Boolean {
        if (!source.connected) { source.onDeviceTap(); return false }
        return true
    }

    override fun onPlayKey() {
        if (!ready()) return
        if (d.hasTrack) {
            if (!d.isPlaying) sounds.play(Sounds.Ev.PLAY)
            d.isPlaying = true
            source.play()
        } else openShelf()
    }

    override fun onPauseKey() {
        if (!ready()) return
        if (d.isPlaying) { sounds.play(Sounds.Ev.PAUSE); d.isPlaying = false; source.pause() }
        else if (d.hasTrack) { sounds.play(Sounds.Ev.PLAY); d.isPlaying = true; source.play() }
        updateAmbient()
    }

    override fun onStopEjectKey() {
        if (!ready()) return
        if (d.isPlaying) { sounds.play(Sounds.Ev.STOP); d.isPlaying = false; source.pause(); updateAmbient() }
        else openShelf()
    }

    override fun onSkip(dir: Int) {
        if (!ready()) return
        sounds.play(if (dir > 0) Sounds.Ev.NEXT else Sounds.Ev.PREV)
        if (dir > 0) source.next() else source.prev()
    }

    override fun onScanStart(dir: Int) {
        if (!source.connected) return
        scanDir = dir
        sounds.startScan()
        handler.removeCallbacks(scanRunnable)
        handler.post(scanRunnable)
    }

    override fun onScanEnd() {
        scanDir = 0
        sounds.stopScan()
        handler.removeCallbacks(scanRunnable)
    }

    override fun onDeviceTap() {
        if (!source.connected) source.onDeviceTap() else if (!d.hasTrack) openShelf()
    }

    override fun onSeek(fraction: Float) {
        if (!ready()) return
        val ms = (d.durationMs * fraction).toLong()
        d.basePosMs = ms
        d.baseTime = SystemClock.uptimeMillis()
        sounds.play(Sounds.Ev.PLAY)
        source.seekTo(ms)
        if (!d.isPlaying) source.play()
    }

    override fun onSeekBy(ms: Long) {
        if (!ready()) return
        d.basePosMs = (d.pos() + ms).coerceIn(0L, d.durationMs)
        d.baseTime = SystemClock.uptimeMillis()
        source.seekBy(ms)
    }

    override fun onKeySound(down: Boolean) {
        sounds.play(if (down) Sounds.Ev.KEY_DOWN else Sounds.Ev.KEY_UP)
    }

    override fun onSkinChanged(index: Int) {
        prefs.edit().putInt("skin", index).apply()
        sounds.kind = walkman.skin().kind
        sounds.stopAmbient()
        updateAmbient()
        sounds.play(Sounds.Ev.EJECT)
    }

    override fun onSkinMenu() {
        val names = walkman.skins.map { it.name }.toTypedArray()
        val b = AlertDialog.Builder(this)
            .setTitle("Elige tu aparato")
            .setSingleChoiceItems(names, walkman.skinIndex) { dlg, which ->
                walkman.switchSkin(which)
                onSkinChanged(which)
                dlg.dismiss()
            }
        if (spotify != null) {
            val other = if (source === spotify) "Universal (cualquier app)" else "Spotify"
            b.setNeutralButton("Fuente: ${source.label} → $other") { _, _ -> switchSource() }
        }
        b.show()
    }

    private fun switchSource() {
        val sp = spotify ?: return
        source.stop()
        source = if (source === sp) universal else sp
        prefs.edit().putString("source", if (source === universal) "universal" else "spotify").apply()
        lastTrack = null
        d.hasTrack = false; d.isPlaying = false; d.art = null; d.connected = false
        updateAmbient()
        toast(if (source === universal) "Fuente universal: controla la app de música que estés usando" else "Fuente: Spotify")
        source.start()
    }

    override fun onOpenShelf() { openShelf() }

    override fun onToggleMute() {
        walkman.muted = !walkman.muted
        sounds.muted = walkman.muted
        prefs.edit().putBoolean("muted", walkman.muted).apply()
        if (!walkman.muted) { sounds.play(Sounds.Ev.SWITCH); updateAmbient() }
        toast(if (walkman.muted) "Sonidos del aparato apagados" else "Sonidos del aparato encendidos")
    }

    override fun onToggleAmbient() {
        walkman.ambientOn = !walkman.ambientOn
        prefs.edit().putBoolean("ambient", walkman.ambientOn).apply()
        sounds.play(Sounds.Ev.SWITCH)
        updateAmbient()
        val what = when (walkman.skin().ambient) {
            Ambient.HISS -> "siseo de cinta"
            Ambient.CRACKLE -> "crujido de vinilo"
            Ambient.HUM -> "zumbido de bulbos"
        }
        toast(if (walkman.ambientOn) "Ambiente encendido ($what)" else "Ambiente apagado")
    }

    private fun updateAmbient() {
        sounds.setAmbient(walkman.skin().ambient, walkman.ambientOn && d.isPlaying)
    }

    // ================= Temporizador para dormir =================

    override fun onTimerMenu() {
        val opts = arrayOf("Apagado", "15 minutos", "30 minutos", "45 minutos", "60 minutos", "90 minutos", "Al terminar esta canción")
        val mins = intArrayOf(0, 15, 30, 45, 60, 90, -1)
        AlertDialog.Builder(this)
            .setTitle("Apagar la música en…")
            .setItems(opts) { _, i ->
                sleepEndOfTrack = mins[i] == -1
                sleepAt = if (mins[i] > 0) SystemClock.uptimeMillis() + mins[i] * 60_000L else 0L
                sounds.play(Sounds.Ev.SWITCH)
                tickSleep()
                toast(if (i == 0) "Temporizador apagado" else "La música se detendrá: " + opts[i].lowercase())
            }
            .show()
    }

    private fun tickSleep() {
        walkman.sleepLabel = when {
            sleepEndOfTrack -> "FIN CANCIÓN"
            sleepAt > 0L -> {
                val left = (sleepAt - SystemClock.uptimeMillis()).coerceAtLeast(0L) / 1000
                if (left == 0L) { doSleep(); null } else "%d:%02d".format(left / 60, left % 60)
            }
            else -> null
        }
    }

    private fun doSleep() {
        sleepAt = 0L
        sleepEndOfTrack = false
        walkman.sleepLabel = null
        if (source.connected) source.pause()
        d.isPlaying = false
        sounds.play(Sounds.Ev.STOP)
        updateAmbient()
        toast("Temporizador: música en pausa. Buenas noches.")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (shelf.visibility == View.VISIBLE) closeShelf() else super.onBackPressed()
    }

    // ================= Estante de recientes =================

    private fun buildShelf(): LinearLayout {
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding((20 * dp).toInt(), (28 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
        box.isClickable = true

        shelfTitle = TextView(this)
        shelfTitle.setTextColor(col(0xFFF3E9D2))
        shelfTitle.textSize = 22f
        shelfTitle.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        shelfTitle.letterSpacing = 0.08f
        box.addView(shelfTitle)

        shelfStatus = TextView(this)
        shelfStatus.setTextColor(col(0xFFB9A982))
        shelfStatus.textSize = 14f
        shelfStatus.setPadding(0, (4 * dp).toInt(), 0, (10 * dp).toInt())
        box.addView(shelfStatus)

        val list = ListView(this)
        list.divider = null
        list.dividerHeight = (6 * dp).toInt()
        shelfAdapter = ShelfAdapter()
        list.adapter = shelfAdapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val row = rows[pos]
            if (row is ShelfEntry) {
                source.playEntry(row)
                sounds.play(Sounds.Ev.PLAY)
                closeShelf()
            }
        }
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val close = Button(this)
        close.text = "Cerrar"
        close.setOnClickListener { closeShelf() }
        box.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun openShelf() {
        if (!source.connected) { source.onDeviceTap(); return }
        val s = walkman.skin()
        sounds.play(Sounds.Ev.EJECT)
        shelf.setBackgroundColor(s.shelfBg)
        shelfTitle.text = s.shelfTitle + " · " + source.label.uppercase()
        shelf.visibility = View.VISIBLE
        setBackHandler(true)
        rows.clear()
        thumbs.clear()
        shelfAdapter.notifyDataSetChanged()
        shelfStatus.text = "Buscando lo último que escuchaste…"
        source.loadShelf()
    }

    private fun closeShelf() {
        sounds.play(Sounds.Ev.KEY_DOWN)
        shelf.visibility = View.GONE
        setBackHandler(false)
    }

    /** Botón "atrás" de Android 13+ (atrás predictivo): cierra el estante. */
    private fun setBackHandler(on: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val disp = onBackInvokedDispatcher
        if (on && backCb == null) {
            val cb = OnBackInvokedCallback { closeShelf() }
            disp.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCb = cb
        } else if (!on) {
            val cb = backCb as? OnBackInvokedCallback ?: return
            disp.unregisterOnBackInvokedCallback(cb)
            backCb = null
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private inner class ThumbView(c0: Context) : View(c0) {
        var bmp: Bitmap? = null
        var shape = 0
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val cx = w / 2f; val cy = h / 2f; val rad = minOf(w, h) / 2f
            val b = bmp
            when (shape) {
                1 -> {
                    p.color = col(0xFF0A0A0A); c.drawCircle(cx, cy, rad, p)
                    p.style = Paint.Style.STROKE; p.color = 0x22FFFFFF; p.strokeWidth = 1f
                    var rr = rad * 0.5f
                    while (rr < rad) { c.drawCircle(cx, cy, rr, p); rr += rad * 0.08f }
                    p.style = Paint.Style.FILL
                    circleArt(c, b, cx, cy, rad * 0.42f)
                    p.color = col(0xFFDDDDDD); c.drawCircle(cx, cy, rad * 0.05f, p)
                }
                2 -> {
                    p.shader = SweepGradient(cx, cy, intArrayOf(col(0xFFFF8080), col(0xFFFFF080), col(0xFF80FFB0), col(0xFF80D0FF), col(0xFFC080FF), col(0xFFFF8080)), null)
                    c.drawCircle(cx, cy, rad, p); p.shader = null
                    circleArt(c, b, cx, cy, rad * 0.92f)
                    p.color = col(0xCCD5DCE5); c.drawCircle(cx, cy, rad * 0.28f, p)
                    p.color = col(0xFF1A1D22); c.drawCircle(cx, cy, rad * 0.08f, p)
                }
                3 -> {
                    val shell = RectF(0f, h * 0.18f, w, h * 0.82f)
                    p.color = col(0xFF2A2C30); c.drawRoundRect(shell, h * 0.06f, h * 0.06f, p)
                    val lab = RectF(w * 0.06f, h * 0.24f, w * 0.94f, h * 0.62f)
                    if (b != null) c.drawBitmap(b, null, lab, p) else { p.color = col(0xFFF3E9D2); c.drawRect(lab, p) }
                    p.color = col(0xFF0E0F11)
                    c.drawRoundRect(RectF(w * 0.28f, h * 0.48f, w * 0.72f, h * 0.60f), h * 0.06f, h * 0.06f, p)
                    p.color = col(0xFFEDEDED)
                    c.drawCircle(w * 0.36f, h * 0.54f, h * 0.04f, p); c.drawCircle(w * 0.64f, h * 0.54f, h * 0.04f, p)
                }
                else -> {
                    val rr = RectF(0f, 0f, w, h)
                    if (b != null) c.drawBitmap(b, null, rr, p) else { p.color = col(0xFF444444); c.drawRect(rr, p) }
                }
            }
        }

        private fun circleArt(c: Canvas, b: Bitmap?, cx: Float, cy: Float, rad: Float) {
            c.save()
            val clip = Path(); clip.addCircle(cx, cy, rad, Path.Direction.CW)
            c.clipPath(clip)
            if (b != null) c.drawBitmap(b, null, RectF(cx - rad, cy - rad, cx + rad, cy + rad), p)
            else { p.color = col(0xFFC0392B); c.drawCircle(cx, cy, rad, p) }
            c.restore()
        }
    }

    private inner class ShelfAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is String) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is ShelfEntry

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val dp = resources.displayMetrics.density
            val row = rows[position]
            if (row is String) {
                val t = TextView(this@MainActivity)
                t.text = (if (row.startsWith("Escuchado")) "▶  " else "") + row.uppercase()
                t.setTextColor(walkman.skin().accent)
                t.textSize = 15f
                t.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                t.setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
                return t
            }
            val item = row as ShelfEntry
            val box = LinearLayout(this@MainActivity)
            box.orientation = LinearLayout.HORIZONTAL
            box.gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable()
            bg.cornerRadius = 8 * dp
            bg.setColor(0x14FFFFFF)
            box.background = bg
            box.setPadding((8 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())

            val th = ThumbView(this@MainActivity)
            th.shape = walkman.skin().thumbShape
            th.bmp = thumbs[item]
            box.addView(th, LinearLayout.LayoutParams((60 * dp).toInt(), (60 * dp).toInt()))

            val texts = LinearLayout(this@MainActivity)
            texts.orientation = LinearLayout.VERTICAL
            texts.setPadding((12 * dp).toInt(), 0, 0, 0)
            val t = TextView(this@MainActivity)
            t.text = item.title
            t.setTextColor(col(0xFFF3E9D2))
            t.textSize = 16f
            t.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            t.maxLines = 1
            texts.addView(t)
            val s = TextView(this@MainActivity)
            s.text = item.subtitle
            s.setTextColor(col(0xFFA89E8A))
            s.textSize = 13f
            s.maxLines = 1
            texts.addView(s)
            box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return box
        }
    }
}

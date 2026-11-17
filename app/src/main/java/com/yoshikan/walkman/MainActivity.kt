package com.yoshikan.walkman

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.ContentApi
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.ListItem
import com.spotify.protocol.types.PlayerState
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse

class MainActivity : Activity(), WalkmanView.Listener {

    companion object {
        const val CLIENT_ID = "1d4f1c05d332459b8ff8cd74e247cf9a"
        const val REDIRECT_URI = "walkmanul://callback"
        const val AUTH_REQ = 1337
    }

    private var remote: SpotifyAppRemote? = null
    private var connecting = false
    private var authorized = false
    private var authInFlight = false
    private lateinit var walkman: WalkmanView
    private lateinit var prefs: SharedPreferences
    private lateinit var sounds: Sounds
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastImageUri: String? = null
    private var lastTrackUri: String? = null
    private var scanDir = 0
    private val scanRunnable = object : Runnable {
        override fun run() {
            if (scanDir != 0) {
                remote?.playerApi?.seekToRelativePosition(scanDir * 6000L)
                handler.postDelayed(this, 350)
            }
        }
    }

    // estante
    private lateinit var shelf: LinearLayout
    private lateinit var shelfTitle: TextView
    private lateinit var shelfStatus: TextView
    private lateinit var shelfAdapter: ShelfAdapter
    private val rows = mutableListOf<Row>()
    private val thumbs = HashMap<String, Bitmap>()

    private sealed class Row {
        class Header(val title: String) : Row()
        class Item(val item: ListItem) : Row()
    }

    private val d get() = walkman.deck

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        volumeControlStream = AudioManager.STREAM_MUSIC
        prefs = getSharedPreferences("walkman", Context.MODE_PRIVATE)
        sounds = Sounds(this)

        val root = FrameLayout(this)
        root.setBackgroundColor(col(0xFF0D0E10))
        walkman = WalkmanView(this, allSkins())
        walkman.listener = this
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
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onStart() {
        super.onStart()
        connect()
    }

    override fun onStop() {
        super.onStop()
        scanDir = 0
        sounds.stopScan()
        sounds.stopAmbient()
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        connecting = false
        d.connected = false
    }

    override fun onDestroy() {
        super.onDestroy()
        sounds.release()
    }

    // ================= Spotify =================

    private fun connect() {
        if (!authorized) { startAuth(); return }
        if (remote != null || connecting) return
        if (!SpotifyAppRemote.isSpotifyInstalled(this)) {
            d.status = "Instala la app de Spotify en este teléfono"
            return
        }
        connecting = true
        d.status = "Conectando con Spotify…"
        val params = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(false)
            .build()
        SpotifyAppRemote.connect(this, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                connecting = false
                remote = appRemote
                d.connected = true
                d.status = ""
                appRemote.playerApi.subscribeToPlayerState().setEventCallback { onPlayerState(it) }
            }

            override fun onFailure(error: Throwable) {
                connecting = false
                remote = null
                d.connected = false
                val name = error.javaClass.simpleName
                val detail = name + ": " + (error.message ?: "")
                d.status = when {
                    name.contains("NotLoggedIn") -> "Inicia sesión en la app de Spotify · $detail"
                    name.contains("UserNotAuthorized") || name.contains("Authentication") -> { authorized = false; "Falta autorizar · $detail" }
                    name.contains("CouldNotFindSpotifyApp") -> "Instala la app de Spotify"
                    name.contains("OfflineMode") -> "Spotify está sin conexión"
                    else -> detail
                }
                toast(d.status)
            }
        })
    }

    private fun startAuth() {
        if (authInFlight) return
        authInFlight = true
        d.status = "Pidiendo permiso a Spotify…"
        val req = AuthorizationRequest.Builder(CLIENT_ID, AuthorizationResponse.Type.TOKEN, REDIRECT_URI)
            .setScopes(arrayOf("app-remote-control", "streaming", "user-read-playback-state", "user-modify-playback-state", "user-read-recently-played"))
            .setShowDialog(false)
            .build()
        AuthorizationClient.openLoginActivity(this, AUTH_REQ, req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AUTH_REQ) return
        authInFlight = false
        val resp = AuthorizationClient.getResponse(resultCode, data)
        when (resp.type) {
            AuthorizationResponse.Type.TOKEN -> { authorized = true; connect() }
            AuthorizationResponse.Type.ERROR -> {
                d.status = "Spotify rechazó el acceso: " + resp.error
                toast(d.status)
            }
            else -> d.status = "Autorización cancelada · toca el aparato"
        }
    }

    private fun onPlayerState(ps: PlayerState) {
        val track = ps.track
        val uri = track?.uri
        if (uri != null && uri != lastTrackUri) { lastTrackUri = uri; d.trackNo++ }
        d.isPlaying = !ps.isPaused && track != null
        d.basePosMs = ps.playbackPosition
        d.baseTime = android.os.SystemClock.uptimeMillis()
        d.durationMs = if ((track?.duration ?: 0L) > 0L) track!!.duration else 1L
        d.title = track?.name ?: ""
        d.artist = track?.artist?.name ?: ""
        d.album = track?.album?.name ?: ""
        d.hasTrack = d.title.isNotEmpty()
        updateAmbient()
        val img = track?.imageUri
        val raw = img?.raw
        if (img != null && raw != null && raw != lastImageUri) {
            lastImageUri = raw
            remote?.imagesApi?.getImage(img, Image.Dimension.MEDIUM)?.setResultCallback { bmp -> d.art = bmp }
        } else if (img == null) {
            lastImageUri = null
            d.art = null
        }
    }

    private fun needRemote(): SpotifyAppRemote? {
        val r = remote
        if (r == null) { toast("Conectando con Spotify…"); connect() }
        return r
    }

    private fun updateAmbient() {
        sounds.setAmbient(walkman.skin().ambient, walkman.ambientOn && d.isPlaying)
    }

    // ================= Teclas y controles =================

    override fun onPlayKey() {
        val r = needRemote() ?: return
        if (d.hasTrack) {
            if (!d.isPlaying) sounds.play(Sounds.Ev.PLAY)
            d.isPlaying = true
            r.playerApi.resume()
        } else openShelf()
    }

    override fun onPauseKey() {
        val r = needRemote() ?: return
        if (d.isPlaying) {
            sounds.play(Sounds.Ev.PAUSE)
            d.isPlaying = false
            r.playerApi.pause()
        } else if (d.hasTrack) {
            sounds.play(Sounds.Ev.PLAY)
            d.isPlaying = true
            r.playerApi.resume()
        }
        updateAmbient()
    }

    override fun onStopEjectKey() {
        val r = needRemote() ?: return
        if (d.isPlaying) {
            sounds.play(Sounds.Ev.STOP)
            d.isPlaying = false
            r.playerApi.pause()
            updateAmbient()
        } else openShelf()
    }

    override fun onSkip(dir: Int) {
        val r = needRemote() ?: return
        sounds.play(if (dir > 0) Sounds.Ev.NEXT else Sounds.Ev.PREV)
        if (dir > 0) r.playerApi.skipNext() else r.playerApi.skipPrevious()
    }

    override fun onScanStart(dir: Int) {
        if (needRemote() == null) return
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
        if (remote == null) connect() else if (!d.hasTrack) openShelf()
    }

    override fun onSeek(fraction: Float) {
        val r = needRemote() ?: return
        val ms = (d.durationMs * fraction).toLong()
        d.basePosMs = ms
        d.baseTime = android.os.SystemClock.uptimeMillis()
        sounds.play(Sounds.Ev.PLAY)
        r.playerApi.seekTo(ms)
        if (!d.isPlaying) r.playerApi.resume()
    }

    override fun onKeySound(down: Boolean) {
        sounds.play(if (down) Sounds.Ev.KEY_DOWN else Sounds.Ev.KEY_UP)
    }

    override fun onSkinChanged(index: Int) {
        prefs.edit().putInt("skin", index).apply()
        sounds.kind = walkman.skin().kind
        sounds.stopAmbient()
        updateAmbient()
        sounds.play(Sounds.Ev.SWITCH)
    }

    override fun onSkinMenu() {
        val names = walkman.skins.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Elige tu aparato")
            .setSingleChoiceItems(names, walkman.skinIndex) { dlg, which ->
                walkman.skinIndex = which
                onSkinChanged(which)
                dlg.dismiss()
            }
            .show()
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
            if (row is Row.Item) playItem(row.item)
        }
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val close = Button(this)
        close.text = "Cerrar"
        close.setOnClickListener { closeShelf() }
        box.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun isRecent(title: String?): Boolean {
        val t = (title ?: "").lowercase()
        return t.contains("recien") || t.contains("recent") || t.contains("escuchad") || t.contains("reproducid") || t.contains("jump back") || t.contains("volver a escuchar")
    }

    private fun openShelf() {
        val r = remote ?: run { connect(); return }
        val s = walkman.skin()
        sounds.play(Sounds.Ev.EJECT)
        shelf.setBackgroundColor(s.shelfBg)
        shelfTitle.text = s.shelfTitle
        shelf.visibility = View.VISIBLE
        rows.clear()
        shelfAdapter.notifyDataSetChanged()
        shelfStatus.text = "Buscando lo último que escuchaste…"
        val call = r.contentApi.getRecommendedContentItems(ContentApi.ContentType.DEFAULT)
        call.setErrorCallback { e -> shelfStatus.text = "No se pudo leer: " + (e.message ?: "") }
        call.setResultCallback { top ->
            val items = top.items ?: emptyArray()
            if (items.isEmpty()) shelfStatus.text = "Spotify no devolvió nada. Pon algo en Spotify y vuelve a intentar."
            val loose = items.filter { it.playable && !it.hasChildren }
            if (loose.isNotEmpty()) addSection("Para ti", loose, false)
            for (sec in items.filter { it.hasChildren }) {
                r.contentApi.getChildrenOfItem(sec, 20, 0).setResultCallback { kids ->
                    val list = (kids.items ?: emptyArray()).filter { it.playable }
                    if (list.isNotEmpty()) addSection(sec.title ?: "", list, isRecent(sec.title))
                }
            }
        }
    }

    private fun addSection(title: String, items: List<ListItem>, recent: Boolean) {
        val block = mutableListOf<Row>()
        block.add(Row.Header(if (recent) "▶  Escuchado recientemente" else title))
        for (it in items) block.add(Row.Item(it))
        if (recent) rows.addAll(0, block) else rows.addAll(block)
        val count = rows.count { it is Row.Item }
        shelfStatus.text = "$count opciones · toca una para ponerla"
        shelfAdapter.notifyDataSetChanged()
        for (it in items) loadThumb(it)
    }

    private fun loadThumb(item: ListItem) {
        val img = item.imageUri ?: return
        val key = img.raw ?: return
        if (thumbs.containsKey(key)) return
        remote?.imagesApi?.getImage(img, Image.Dimension.SMALL)?.setResultCallback { bmp ->
            thumbs[key] = bmp
            shelfAdapter.notifyDataSetChanged()
        }
    }

    private fun playItem(item: ListItem) {
        val r = remote ?: return
        r.contentApi.playContentItem(item)
        sounds.play(Sounds.Ev.PLAY)
        d.isPlaying = true
        closeShelf()
    }

    private fun closeShelf() {
        sounds.play(Sounds.Ev.KEY_DOWN)
        shelf.visibility = View.GONE
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // miniatura con forma según el aparato
    private inner class ThumbView(ctx: Context) : View(ctx) {
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
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is Row.Item

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val dp = resources.displayMetrics.density
            val row = rows[position]
            if (row is Row.Header) {
                val t = TextView(this@MainActivity)
                t.text = row.title.uppercase()
                t.setTextColor(walkman.skin().accent)
                t.textSize = 15f
                t.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                t.setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
                return t
            }
            val item = (row as Row.Item).item
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
            th.bmp = item.imageUri?.raw?.let { thumbs[it] }
            box.addView(th, LinearLayout.LayoutParams((60 * dp).toInt(), (60 * dp).toInt()))

            val texts = LinearLayout(this@MainActivity)
            texts.orientation = LinearLayout.VERTICAL
            texts.setPadding((12 * dp).toInt(), 0, 0, 0)
            val t = TextView(this@MainActivity)
            t.text = item.title ?: ""
            t.setTextColor(col(0xFFF3E9D2))
            t.textSize = 16f
            t.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            t.maxLines = 1
            texts.addView(t)
            val s = TextView(this@MainActivity)
            s.text = item.subtitle ?: ""
            s.setTextColor(col(0xFFA89E8A))
            s.textSize = 13f
            s.maxLines = 1
            texts.addView(s)
            box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return box
        }
    }

    @Suppress("unused")
    private val keepShader: Shader? = null
}

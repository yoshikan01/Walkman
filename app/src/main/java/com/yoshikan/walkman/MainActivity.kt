package com.yoshikan.walkman

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
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
import com.spotify.protocol.types.Repeat
import kotlin.math.roundToInt

class MainActivity : Activity(), WalkmanView.Listener {

    companion object {
        const val CLIENT_ID = "1d4f1c05d332459b8ff8cd74e247cf9a"
        const val REDIRECT_URI = "walkmanul://callback"
    }

    private var remote: SpotifyAppRemote? = null
    private var connecting = false
    private lateinit var walkman: WalkmanView
    private lateinit var rack: LinearLayout
    private lateinit var rackStatus: TextView
    private lateinit var rackAdapter: CassetteAdapter
    private val rackItems = mutableListOf<ListItem>()
    private lateinit var sounds: Sounds
    private lateinit var audio: AudioManager
    private var bass: BassBoost? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastImageUri: String? = null
    private var scanDir = 0
    private val scanRunnable = object : Runnable {
        override fun run() {
            if (scanDir != 0) {
                remote?.playerApi?.seekToRelativePosition(scanDir * 6000L)
                handler.postDelayed(this, 350)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sounds = Sounds(this)

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF2B1E15.toInt())
        walkman = WalkmanView(this)
        walkman.listener = this
        root.addView(walkman, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rack = buildRack()
        rack.visibility = View.GONE
        root.addView(rack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        syncVolume(false)
    }

    override fun onStart() {
        super.onStart()
        connect()
    }

    override fun onStop() {
        super.onStop()
        scanDir = 0
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        connecting = false
        walkman.connected = false
    }

    override fun onDestroy() {
        super.onDestroy()
        try { bass?.release() } catch (_: Exception) {}
        sounds.release()
    }

    // ================= Spotify =================

    private fun connect() {
        if (remote != null || connecting) return
        if (!SpotifyAppRemote.isSpotifyInstalled(this)) {
            walkman.statusText = "Instala la app de Spotify en este teléfono"
            walkman.connected = false
            return
        }
        connecting = true
        walkman.statusText = "Conectando con Spotify…"
        val params = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(this, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                connecting = false
                remote = appRemote
                walkman.connected = true
                walkman.statusText = "Conectado · STOP/EJ abre el estante de casetes"
                appRemote.playerApi.subscribeToPlayerState().setEventCallback { onPlayerState(it) }
            }

            override fun onFailure(error: Throwable) {
                connecting = false
                remote = null
                walkman.connected = false
                val name = error.javaClass.simpleName
                walkman.statusText = when {
                    name.contains("NotLoggedIn") -> "Inicia sesión en la app de Spotify y toca el casete"
                    name.contains("UserNotAuthorized") || name.contains("Authentication") -> "Autoriza Walkman UL en Spotify (toca el casete)"
                    name.contains("CouldNotFindSpotifyApp") -> "Instala la app de Spotify"
                    name.contains("OfflineMode") -> "Spotify está sin conexión"
                    else -> "Sin conexión: " + (error.message ?: name)
                }
            }
        })
    }

    private fun onPlayerState(ps: PlayerState) {
        val track = ps.track
        val title = track?.name ?: ""
        val artist = track?.artist?.name ?: ""
        val dur = track?.duration ?: 1L
        val opts = ps.playbackOptions
        val repeatOn = opts != null && opts.repeatMode != Repeat.OFF
        val shuffle = opts != null && opts.isShuffling
        walkman.updatePlayer(!ps.isPaused && track != null, ps.playbackPosition, dur, title, artist, shuffle, repeatOn)
        if (track != null) {
            walkman.statusText = if (ps.isPaused) "En pausa" else "♪ " + (track.album?.name ?: "")
        }
        val img = track?.imageUri
        val raw = img?.raw
        if (img != null && raw != null && raw != lastImageUri) {
            lastImageUri = raw
            remote?.imagesApi?.getImage(img, Image.Dimension.MEDIUM)?.setResultCallback { bmp ->
                walkman.art = bmp
            }
        } else if (img == null) {
            lastImageUri = null
            walkman.art = null
        }
    }

    private fun needRemote(): SpotifyAppRemote? {
        val r = remote
        if (r == null) {
            toast("Conectando con Spotify…")
            connect()
        }
        return r
    }

    // ================= Teclas =================

    override fun onPlayKey() {
        val r = needRemote() ?: return
        if (walkman.hasTrack) {
            walkman.isPlaying = true
            r.playerApi.resume()
        } else {
            openRack()
        }
    }

    override fun onPauseKey() {
        val r = needRemote() ?: return
        if (walkman.isPlaying) {
            walkman.isPlaying = false
            r.playerApi.pause()
        } else if (walkman.hasTrack) {
            walkman.isPlaying = true
            r.playerApi.resume()
        }
    }

    override fun onStopEjectKey() {
        val r = needRemote() ?: return
        if (walkman.isPlaying) {
            walkman.isPlaying = false
            r.playerApi.pause()
        } else {
            openRack()
        }
    }

    override fun onSkip(dir: Int) {
        val r = needRemote() ?: return
        if (dir > 0) r.playerApi.skipNext() else r.playerApi.skipPrevious()
    }

    override fun onScanStart(dir: Int) {
        if (needRemote() == null) return
        scanDir = dir
        handler.removeCallbacks(scanRunnable)
        handler.post(scanRunnable)
    }

    override fun onScanEnd() {
        scanDir = 0
        handler.removeCallbacks(scanRunnable)
    }

    override fun onVolume(fraction: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction * max).roundToInt(), 0)
    }

    override fun onSwitch(index: Int, on: Boolean) {
        when (index) {
            0 -> setMegaBass(on)
            1 -> remote?.playerApi?.setRepeat(if (on) Repeat.ALL else Repeat.OFF)
            2 -> remote?.playerApi?.setShuffle(on)
        }
    }

    override fun onCassetteTap() {
        if (remote == null) connect() else if (!walkman.hasTrack) openRack()
    }

    override fun onKeySound(down: Boolean) {
        if (down) sounds.clack() else sounds.tick()
    }

    override fun onSwitchSound() {
        sounds.sw()
    }

    private fun setMegaBass(on: Boolean) {
        try {
            if (bass == null) bass = BassBoost(0, 0)
            val b = bass!!
            if (b.strengthSupported) b.setStrength(1000.toShort())
            b.setEnabled(on)
        } catch (e: Exception) {
            walkman.setSwitch(0, false)
            toast("Mega Bass no está disponible en este teléfono")
        }
    }

    private fun syncVolume(show: Boolean) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        walkman.setVolumeFraction(cur.toFloat() / max, show)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            handler.postDelayed({ syncVolume(true) }, 60)
        }
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (rack.visibility == View.VISIBLE) closeRack() else super.onBackPressed()
    }

    // ================= Estante de casetes =================

    private fun buildRack(): LinearLayout {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(0xF01C130D.toInt())
        box.setPadding((20 * d).toInt(), (36 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
        box.isClickable = true

        val title = TextView(this)
        title.text = "ESTANTE DE CASETES"
        title.setTextColor(0xFFF3E9D2.toInt())
        title.textSize = 22f
        title.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        title.letterSpacing = 0.08f
        box.addView(title)

        rackStatus = TextView(this)
        rackStatus.setTextColor(0xFFB9A982.toInt())
        rackStatus.textSize = 14f
        rackStatus.setPadding(0, (4 * d).toInt(), 0, (12 * d).toInt())
        box.addView(rackStatus)

        val list = ListView(this)
        list.divider = null
        list.dividerHeight = (8 * d).toInt()
        rackAdapter = CassetteAdapter()
        list.adapter = rackAdapter
        list.setOnItemClickListener { _, _, pos, _ -> playCassette(rackItems[pos]) }
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val close = Button(this)
        close.text = "Cerrar tapa"
        close.setOnClickListener { closeRack() }
        box.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun openRack() {
        val r = remote ?: run { connect(); return }
        sounds.eject()
        rack.visibility = View.VISIBLE
        rackItems.clear()
        rackAdapter.notifyDataSetChanged()
        rackStatus.text = "Buscando tus casetes…"
        val call = r.contentApi.getRecommendedContentItems(ContentApi.ContentType.DEFAULT)
        call.setErrorCallback { e -> rackStatus.text = "No se pudo leer el estante: " + (e.message ?: "") }
        call.setResultCallback { top ->
                val items = top.items ?: emptyArray()
                if (items.isEmpty()) rackStatus.text = "Spotify no devolvió casetes. Pon algo en Spotify y vuelve a intentar."
                for (item in items) {
                    if (item.playable) addCassette(item)
                    if (item.hasChildren) {
                        r.contentApi.getChildrenOfItem(item, 12, 0).setResultCallback { kids ->
                            for (k in kids.items ?: emptyArray()) if (k.playable) addCassette(k)
                        }
                    }
                }
            }
    }

    private fun addCassette(item: ListItem) {
        if (rackItems.any { it.uri == item.uri }) return
        rackItems.add(item)
        rackStatus.text = "${rackItems.size} casetes · toca uno para meterlo"
        rackAdapter.notifyDataSetChanged()
    }

    private fun playCassette(item: ListItem) {
        val r = remote ?: return
        r.contentApi.playContentItem(item)
        walkman.isPlaying = true
        closeRack()
    }

    private fun closeRack() {
        sounds.clack()
        rack.visibility = View.GONE
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private inner class CassetteAdapter : BaseAdapter() {
        private val colors = intArrayOf(0xFFE8742B.toInt(), 0xFF1F3A68.toInt(), 0xFFD9472B.toInt(), 0xFF2E7D5B.toInt(), 0xFF7A4FA0.toInt(), 0xFFC9A227.toInt())
        override fun getCount() = rackItems.size
        override fun getItem(position: Int): Any = rackItems[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val d = resources.displayMetrics.density
            val item = rackItems[position]
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable()
            bg.cornerRadius = 6 * d
            bg.setColor(0xFFF3E9D2.toInt())
            row.background = bg
            row.setPadding(0, 0, (12 * d).toInt(), 0)

            val spine = View(this@MainActivity)
            val sbg = GradientDrawable()
            sbg.cornerRadii = floatArrayOf(6 * d, 6 * d, 0f, 0f, 0f, 0f, 6 * d, 6 * d)
            sbg.setColor(colors[position % colors.size])
            spine.background = sbg
            row.addView(spine, LinearLayout.LayoutParams((14 * d).toInt(), (64 * d).toInt()))

            val texts = LinearLayout(this@MainActivity)
            texts.orientation = LinearLayout.VERTICAL
            texts.setPadding((12 * d).toInt(), (8 * d).toInt(), 0, (8 * d).toInt())
            val t = TextView(this@MainActivity)
            t.text = item.title ?: "Casete"
            t.setTextColor(0xFF1C2F5A.toInt())
            t.textSize = 17f
            t.typeface = Typeface.create("casual", Typeface.BOLD)
            t.maxLines = 1
            texts.addView(t)
            val s = TextView(this@MainActivity)
            s.text = item.subtitle ?: ""
            s.setTextColor(Color.parseColor("#6B7690"))
            s.textSize = 13f
            s.maxLines = 1
            texts.addView(s)
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val side = TextView(this@MainActivity)
            side.text = "A"
            side.setTextColor(0xFFD9472B.toInt())
            side.textSize = 20f
            side.typeface = Typeface.DEFAULT_BOLD
            row.addView(side)
            return row
        }
    }
}

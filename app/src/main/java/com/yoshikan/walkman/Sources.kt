package com.yoshikan.walkman

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.ContentApi
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.ListItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/** Servicio vacío: Android exige un "lector de notificaciones" para dejarnos ver los controles de música. */
class MediaListener : NotificationListenerService()

class ShelfEntry(
    val title: String,
    val subtitle: String,
    val payload: Any?,
    val image: ((Bitmap) -> Unit) -> Unit
)

interface SourceHost {
    val ctx: Activity
    fun onPlayer(playing: Boolean, posMs: Long, durMs: Long, title: String, artist: String, album: String, trackKey: String?)
    fun onArt(bmp: Bitmap?)
    fun onConnection(connected: Boolean, status: String)
    fun shelfSection(title: String, entries: List<ShelfEntry>, first: Boolean)
    fun shelfStatus(s: String)
    fun note(s: String)
}

interface MusicSource {
    val label: String
    val connected: Boolean
    fun start()
    fun stop()
    fun onDeviceTap()
    fun play()
    fun pause()
    fun next()
    fun prev()
    fun seekTo(ms: Long)
    fun seekBy(ms: Long)
    fun setRepeat(on: Boolean) {}
    fun loadShelf()
    fun playEntry(e: ShelfEntry)
}

object Net {
    val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, Bitmap>()

    fun get(url: String, token: String?): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) { null }

    fun image(url: String, cb: (Bitmap) -> Unit) {
        synchronized(cache) { cache[url] }?.let { cb(it); return }
        io.execute {
            val bmp = try { URL(url).openStream().use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
            if (bmp != null) {
                synchronized(cache) { cache[url] = bmp }
                main.post { cb(bmp) }
            }
        }
    }

    fun post(r: () -> Unit) { main.post(r) }

    fun ago(ms: Long): String {
        val m = (System.currentTimeMillis() - ms) / 60000
        return when {
            m < 1 -> "hace un momento"
            m < 60 -> "hace $m min"
            m < 60 * 24 -> "hace ${m / 60} h"
            m < 60 * 48 -> "ayer"
            else -> "hace ${m / (60 * 24)} días"
        }
    }
}

// =====================================================================
// Spotify (SDK App Remote + historial exacto por Web API)
// =====================================================================
class SpotifySource(private val host: SourceHost) : MusicSource {
    companion object {
        const val CLIENT_ID = "1d4f1c05d332459b8ff8cd74e247cf9a"
        const val REDIRECT_URI = "walkmanul://callback"
    }

    override val label = "Spotify"
    var token: String? = null
    var needAuth: (() -> Unit)? = null
    private var remote: SpotifyAppRemote? = null
    private var connecting = false
    private var lastImage: String? = null
    override val connected get() = remote != null

    override fun start() {
        val ctx = host.ctx
        if (token == null) { needAuth?.invoke(); return }
        if (remote != null || connecting) return
        if (!SpotifyAppRemote.isSpotifyInstalled(ctx)) {
            host.onConnection(false, "Instala Spotify o cambia la fuente a Universal")
            return
        }
        connecting = true
        host.onConnection(false, "Conectando con Spotify…")
        val params = ConnectionParams.Builder(CLIENT_ID).setRedirectUri(REDIRECT_URI).showAuthView(false).build()
        SpotifyAppRemote.connect(ctx, params, object : Connector.ConnectionListener {
            override fun onConnected(r: SpotifyAppRemote) {
                connecting = false
                remote = r
                host.onConnection(true, "")
                r.playerApi.subscribeToPlayerState().setEventCallback { ps ->
                    val t = ps.track
                    host.onPlayer(!ps.isPaused && t != null, ps.playbackPosition, t?.duration ?: 1L,
                        t?.name ?: "", t?.artist?.name ?: "", t?.album?.name ?: "", t?.uri)
                    val img = t?.imageUri
                    val raw = img?.raw
                    if (img != null && raw != null && raw != lastImage) {
                        lastImage = raw
                        r.imagesApi.getImage(img, Image.Dimension.MEDIUM).setResultCallback { host.onArt(it) }
                    } else if (img == null) { lastImage = null; host.onArt(null) }
                }
            }

            override fun onFailure(error: Throwable) {
                connecting = false
                remote = null
                val name = error.javaClass.simpleName
                if (name.contains("UserNotAuthorized") || name.contains("Authentication")) token = null
                host.onConnection(false, name + ": " + (error.message ?: ""))
            }
        })
    }

    override fun stop() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        connecting = false
    }

    override fun onDeviceTap() { start() }
    override fun play() { remote?.playerApi?.resume() }
    override fun pause() { remote?.playerApi?.pause() }
    override fun next() { remote?.playerApi?.skipNext() }
    override fun prev() { remote?.playerApi?.skipPrevious() }
    override fun seekTo(ms: Long) { remote?.playerApi?.seekTo(ms) }
    override fun seekBy(ms: Long) { remote?.playerApi?.seekToRelativePosition(ms) }

    override fun loadShelf() {
        val r = remote ?: return
        host.shelfStatus("Buscando lo último que escuchaste…")
        // 1) historial exacto (Web API)
        val tok = token
        if (tok != null) Net.io.execute {
            val list = recents(tok)
            Net.post {
                if (list.isNotEmpty()) host.shelfSection("Escuchado recientemente", list, true)
            }
        }
        // 2) secciones que Spotify ofrece a la app
        val call = r.contentApi.getRecommendedContentItems(ContentApi.ContentType.DEFAULT)
        call.setErrorCallback { e -> host.shelfStatus("No se pudo leer: " + (e.message ?: "")) }
        call.setResultCallback { top ->
            val items = top.items ?: emptyArray()
            val loose = items.filter { it.playable && !it.hasChildren }
            if (loose.isNotEmpty()) host.shelfSection("Para ti", loose.map { entry(it) }, false)
            for (sec in items.filter { it.hasChildren }) {
                r.contentApi.getChildrenOfItem(sec, 20, 0).setResultCallback { kids ->
                    val list = (kids.items ?: emptyArray()).filter { it.playable }
                    if (list.isNotEmpty()) host.shelfSection(sec.title ?: "", list.map { entry(it) }, false)
                }
            }
        }
    }

    private fun entry(it: ListItem) = ShelfEntry(it.title ?: "", it.subtitle ?: "", it) { cb ->
        val img = it.imageUri
        if (img != null) remote?.imagesApi?.getImage(img, Image.Dimension.SMALL)?.setResultCallback { b -> cb(b) }
    }

    private fun recents(tok: String): List<ShelfEntry> {
        val js = Net.get("https://api.spotify.com/v1/me/player/recently-played?limit=50", tok) ?: return emptyList()
        val items = try { JSONObject(js).optJSONArray("items") } catch (e: Exception) { null } ?: return emptyList()
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val seen = LinkedHashSet<String>()
        val out = ArrayList<ShelfEntry>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val track = it.optJSONObject("track") ?: continue
            val ctx = it.optJSONObject("context")
            val ctxUri = ctx?.optString("uri")?.takeIf { s -> s.isNotEmpty() }
            val ctxType = ctx?.optString("type") ?: ""
            val key = ctxUri ?: track.optString("uri")
            if (key.isEmpty() || !seen.add(key)) continue
            val playedAt = try { fmt.parse(it.optString("played_at").take(19))?.time ?: 0L } catch (e: Exception) { 0L }
            val album = track.optJSONObject("album")
            val imgs = album?.optJSONArray("images")
            val imgUrl = imgs?.let { a -> (if (a.length() > 1) a.optJSONObject(1) else a.optJSONObject(0))?.optString("url") }
            val artist = track.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: ""
            var title: String
            val kind: String
            var img = imgUrl
            when (ctxType) {
                "album" -> { title = album?.optString("name") ?: ""; kind = "Álbum" }
                "playlist" -> {
                    kind = "Playlist"
                    title = "Playlist"
                    val id = ctxUri?.substringAfterLast(":")
                    val pj = if (id != null) Net.get("https://api.spotify.com/v1/playlists/$id?fields=name,images", tok) else null
                    if (pj != null) try {
                        val o = JSONObject(pj)
                        title = o.optString("name", "Playlist")
                        o.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.let { u -> if (u.isNotEmpty()) img = u }
                    } catch (e: Exception) {}
                }
                "artist" -> { title = artist; kind = "Artista" }
                else -> { title = track.optString("name"); kind = "Canción" }
            }
            val sub = "$kind · " + (if (kind == "Artista") "" else "$artist · ") + Net.ago(playedAt)
            val u = img
            out.add(ShelfEntry(title, sub, key) { cb -> if (u != null) Net.image(u, cb) })
            if (out.size >= 25) break
        }
        return out
    }

    override fun playEntry(e: ShelfEntry) {
        val r = remote ?: return
        when (val p = e.payload) {
            is ListItem -> r.contentApi.playContentItem(p)
            is String -> r.playerApi.play(p)
        }
    }

    override fun setRepeat(on: Boolean) {
        remote?.playerApi?.setRepeat(if (on) 2 else 0)
    }
}

// =====================================================================
// Universal: controla cualquier app de música con los controles de Android
// =====================================================================
class UniversalSource(private val host: SourceHost) : MusicSource {
    override val label = "Universal"
    private val ctx: Activity = host.ctx
    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val comp = ComponentName(ctx, MediaListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var listening = false
    private var lastKey: String? = null
    private var lastArtKey: String? = null
    private val prefs = ctx.getSharedPreferences("historial", Context.MODE_PRIVATE)
    private val thumbDir = File(ctx.filesDir, "thumbs").apply { mkdirs() }
    override val connected get() = controller != null

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { push() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { push() }
        override fun onSessionDestroyed() { pick(sessions()) }
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    fun hasAccess(): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
        return s.contains(ctx.packageName)
    }

    private fun sessions(): List<MediaController> = try { msm.getActiveSessions(comp) } catch (e: SecurityException) { emptyList() }

    override fun start() {
        if (!hasAccess()) {
            host.onConnection(false, "Toca el aparato para dar acceso a los controles de música")
            return
        }
        if (!listening) {
            try { msm.addOnActiveSessionsChangedListener(sessionsListener, comp, handler); listening = true } catch (e: SecurityException) {}
        }
        pick(sessions())
    }

    override fun stop() {
        if (listening) try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) {}
        listening = false
        controller?.unregisterCallback(cb)
        controller = null
    }

    override fun onDeviceTap() {
        if (!hasAccess()) {
            host.note("Activa \"Walkman UL\" en la lista y regresa a la app")
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } else start()
    }

    private fun pick(list: List<MediaController>?) {
        val l = list ?: emptyList()
        val chosen = l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: l.firstOrNull { it.packageName == controller?.packageName } ?: l.firstOrNull()
        use(chosen)
    }

    private fun use(c: MediaController?) {
        if (c?.sessionToken == controller?.sessionToken && c != null) { push(); return }
        controller?.unregisterCallback(cb)
        controller = c
        if (c == null) {
            host.onConnection(false, "Abre tu app de música (Spotify, YouTube Music…) y pon algo")
            host.onPlayer(false, 0, 1, "", "", "", null)
            return
        }
        c.registerCallback(cb, handler)
        host.onConnection(true, "")
        push()
    }

    private fun appName(pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg.substringAfterLast('.') }

    private fun push() {
        val c = controller ?: return
        val st = c.playbackState
        val md = c.metadata
        val playing = st?.state == PlaybackState.STATE_PLAYING
        var pos = st?.position ?: 0L
        if (playing && st != null && st.lastPositionUpdateTime > 0) pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val key = if (title.isEmpty()) null else "$title|$artist"
        host.onPlayer(playing, pos, if (dur > 0) dur else 1L, title, artist, album, key)
        if (key != lastArtKey) {
            lastArtKey = key
            val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            host.onArt(art)
            if (key != null && key != lastKey) { lastKey = key; remember(title, artist, album, c.packageName, art) }
        }
    }

    // ---------- historial propio ----------
    private fun history(): JSONArray = try { JSONArray(prefs.getString("items", "[]")) } catch (e: Exception) { JSONArray() }

    private fun remember(t: String, a: String, al: String, pkg: String, art: Bitmap?) {
        val h = history()
        val out = JSONArray()
        val id = (t + "|" + a).hashCode().toString()
        out.put(JSONObject().put("t", t).put("a", a).put("al", al).put("pkg", pkg).put("time", System.currentTimeMillis()).put("id", id))
        for (i in 0 until h.length()) {
            val o = h.optJSONObject(i) ?: continue
            if (o.optString("id") != id && out.length() < 60) out.put(o)
        }
        prefs.edit().putString("items", out.toString()).apply()
        if (art != null) Net.io.execute {
            try {
                val sc = Bitmap.createScaledBitmap(art, 160, 160, true)
                FileOutputStream(File(thumbDir, "$id.png")).use { sc.compress(Bitmap.CompressFormat.PNG, 90, it) }
            } catch (e: Exception) {}
        }
    }

    override fun play() { controller?.transportControls?.play() }
    override fun pause() { controller?.transportControls?.pause() }
    override fun next() { controller?.transportControls?.skipToNext() }
    override fun prev() { controller?.transportControls?.skipToPrevious() }
    override fun seekTo(ms: Long) { controller?.transportControls?.seekTo(ms) }
    override fun seekBy(ms: Long) {
        val c = controller ?: return
        val st = c.playbackState ?: return
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        c.transportControls.seekTo((pos + ms).coerceAtLeast(0L))
    }

    override fun loadShelf() {
        val h = history()
        val list = ArrayList<ShelfEntry>()
        for (i in 0 until h.length()) {
            val o = h.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val sub = listOf(o.optString("a"), appName(o.optString("pkg")), Net.ago(o.optLong("time"))).filter { it.isNotEmpty() }.joinToString(" · ")
            list.add(ShelfEntry(o.optString("t"), sub, o) { cb ->
                Net.io.execute {
                    val f = File(thumbDir, "$id.png")
                    val b = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
                    if (b != null) Net.post { cb(b) }
                }
            })
        }
        val apps = sessions().map { mc ->
            val t = mc.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Sin canción"
            ShelfEntry(appName(mc.packageName), "Usar esta app · $t", mc) { cb ->
                mc.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { cb(it) }
            }
        }
        if (apps.isNotEmpty()) host.shelfSection("Apps de música abiertas", apps, false)
        if (list.isNotEmpty()) host.shelfSection("Escuchado recientemente", list, true)
        host.shelfStatus(if (list.isEmpty() && apps.isEmpty()) "Aún no hay historial. Pon música en cualquier app." else "Toca una para ponerla")
    }

    override fun playEntry(e: ShelfEntry) {
        when (val p = e.payload) {
            is MediaController -> { use(p); host.note("Ahora controlas " + appName(p.packageName)) }
            is JSONObject -> {
                val pkg = p.optString("pkg")
                val target = sessions().firstOrNull { it.packageName == pkg } ?: controller
                if (target == null) { host.note("Abre " + appName(pkg) + " primero"); return }
                val actions = target.playbackState?.actions ?: 0L
                if (actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L) {
                    target.transportControls.playFromSearch(p.optString("t") + " " + p.optString("a"), Bundle())
                    host.note("Pidiendo a " + appName(target.packageName) + " que la ponga…")
                } else {
                    host.note(appName(target.packageName) + " no deja elegir canciones desde fuera; ábrela para cambiar")
                    ctx.packageManager.getLaunchIntentForPackage(target.packageName)?.let { ctx.startActivity(it) }
                }
            }
        }
    }
}

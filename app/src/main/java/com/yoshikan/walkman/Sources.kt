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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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
            val name = ctx.applicationInfo.loadLabel(ctx.packageManager).toString()
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Controlar tu música")
                .setMessage("Para mostrar la canción que suena y manejarla con los botones, $name necesita el permiso \"Acceso a notificaciones\" de Android.\n\nSolo se usa para leer el título, artista, portada y posición de la música, y para enviar play, pausa y cambio de canción. No se leen ni guardan tus otras notificaciones y nada sale de tu teléfono.")
                .setPositiveButton("Ir a Ajustes") { _, _ ->
                    host.note("Activa \"$name\" en la lista y regresa a la app")
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Ahora no", null)
                .show()
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
            host.onConnection(false, "Abre tu app de música y pon algo")
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

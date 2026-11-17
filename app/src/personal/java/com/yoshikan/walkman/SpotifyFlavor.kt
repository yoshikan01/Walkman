package com.yoshikan.walkman

import android.app.Activity
import android.content.Intent
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.ContentApi
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.ListItem
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Edición personal: incluye la conexión directa con Spotify. */
object Flavor {
    const val hasSpotify = true
    private var authInFlight = false

    fun createSpotify(host: SourceHost, authReq: Int): MusicSource {
        val s = SpotifySource(host)
        s.needAuth = { startAuth(host, authReq) }
        return s
    }

    private fun startAuth(host: SourceHost, authReq: Int) {
        if (authInFlight) return
        authInFlight = true
        host.onConnection(false, "Pidiendo permiso a Spotify…")
        val req = AuthorizationRequest.Builder(SpotifySource.CLIENT_ID, AuthorizationResponse.Type.TOKEN, SpotifySource.REDIRECT_URI)
            .setScopes(arrayOf("app-remote-control", "streaming", "user-read-playback-state", "user-modify-playback-state", "user-read-recently-played", "playlist-read-private"))
            .setShowDialog(false)
            .build()
        AuthorizationClient.openLoginActivity(host.ctx, authReq, req)
    }

    /** Procesa la respuesta del inicio de sesión de Spotify. */
    fun handleAuth(src: MusicSource?, host: SourceHost, resultCode: Int, data: Intent?, active: Boolean) {
        authInFlight = false
        val s = src as? SpotifySource ?: return
        val resp = AuthorizationClient.getResponse(resultCode, data)
        when (resp.type) {
            AuthorizationResponse.Type.TOKEN -> { s.token = resp.accessToken; if (active) s.start() }
            AuthorizationResponse.Type.ERROR -> { host.onConnection(false, "Spotify rechazó el acceso: " + resp.error); host.note("Spotify rechazó el acceso: " + resp.error) }
            else -> host.onConnection(false, "Autorización cancelada · toca el aparato")
        }
    }

    @Suppress("unused") private fun keep(a: Activity) = a
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


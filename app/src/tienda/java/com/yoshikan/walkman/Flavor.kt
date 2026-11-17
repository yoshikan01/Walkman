package com.yoshikan.walkman

import android.content.Intent

/** Edición de tienda: sin Spotify, solo la fuente universal. */
object Flavor {
    const val hasSpotify = false
    fun createSpotify(host: SourceHost, authReq: Int): MusicSource? = null
    fun handleAuth(src: MusicSource?, host: SourceHost, resultCode: Int, data: Intent?, active: Boolean) {}
}

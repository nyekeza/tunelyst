package com.tunelyst.music.playback

import java.util.concurrent.ConcurrentHashMap

/** Process-local bridge for ReplayGain values resolved after a MediaItem has already started. */
internal object ReplayGainRuntimeCache {
    private val gainByMediaId = ConcurrentHashMap<String, Float>()

    fun put(mediaId: String, gainDb: Float) {
        if (mediaId.isNotBlank() && gainDb.isFinite()) gainByMediaId[mediaId] = gainDb
    }

    fun get(mediaId: String?): Float? = mediaId?.let(gainByMediaId::get)
}

package com.tunelyst.music.playback

import android.util.LruCache

/** Process-local bridge for ReplayGain values resolved after a MediaItem has already started. */
internal object ReplayGainRuntimeCache {
    internal const val MAX_CACHED_GAINS = 512
    private val gainByMediaId = LruCache<String, Float>(MAX_CACHED_GAINS)

    fun put(mediaId: String, gainDb: Float) {
        if (mediaId.isNotBlank() && gainDb.isFinite()) gainByMediaId.put(mediaId, gainDb)
    }

    fun get(mediaId: String?): Float? = mediaId?.let(gainByMediaId::get)
}

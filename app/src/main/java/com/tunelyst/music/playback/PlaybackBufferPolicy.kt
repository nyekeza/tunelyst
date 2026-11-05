package com.tunelyst.music.playback

/** Explicit per-player limits; crossfades may briefly hold two independent audio buffers. */
internal data class PlaybackBufferPolicy(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int
) {
    companion object {
        private const val MIB = 1024 * 1024

        fun forLowMemoryDevice(lowMemory: Boolean): PlaybackBufferPolicy = if (lowMemory) {
            PlaybackBufferPolicy(
                minBufferMs = 2_500,
                maxBufferMs = 8_000,
                bufferForPlaybackMs = 500,
                bufferForPlaybackAfterRebufferMs = 1_000,
                targetBufferBytes = 2 * MIB
            )
        } else {
            PlaybackBufferPolicy(
                minBufferMs = 4_000,
                maxBufferMs = 12_000,
                bufferForPlaybackMs = 750,
                bufferForPlaybackAfterRebufferMs = 1_500,
                targetBufferBytes = 4 * MIB
            )
        }
    }
}

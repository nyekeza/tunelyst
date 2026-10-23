package com.tunelyst.music.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-local handoff from the playback service to the app UI while a second player fades in.
 * The MediaSession continues to expose the foreground player until the transition completes.
 */
internal object CrossfadeDisplayState {
    private val _incomingMediaId = MutableStateFlow<String?>(null)
    val incomingMediaId = _incomingMediaId.asStateFlow()

    fun showIncoming(mediaId: String?) {
        _incomingMediaId.value = mediaId?.takeIf(String::isNotBlank)
    }

    fun clearIncoming(expectedMediaId: String? = null) {
        _incomingMediaId.update { current ->
            if (expectedMediaId == null || current == expectedMediaId) null else current
        }
    }
}

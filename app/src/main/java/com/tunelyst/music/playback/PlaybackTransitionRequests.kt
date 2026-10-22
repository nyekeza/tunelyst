package com.tunelyst.music.playback

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import com.tunelyst.music.data.CrossfadeCurve
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A queue change handed from the app controller to the playback service. */
internal data class PlaybackTransitionRequest(
    val mediaItems: List<MediaItem>,
    val startIndex: Int,
    val startPositionMs: Long = C.TIME_UNSET,
    val repeatMode: Int,
    val shuffleModeEnabled: Boolean,
    val playWhenReady: Boolean,
    /** Keep the active queue and seek within it instead of replacing it when no fade is configured. */
    val keepCurrentQueue: Boolean = false,
    /** Preserve the active player's precomputed shuffle order for an in-queue transition. */
    val copyCurrentShuffleOrder: Boolean = false
)

/** Builds a service-owned transition request for a skip within the currently playing queue. */
internal fun inQueueTransitionRequest(
    player: Player,
    startIndex: Int,
    startPositionMs: Long = C.TIME_UNSET
): PlaybackTransitionRequest? {
    if (startIndex !in 0 until player.mediaItemCount) return null
    return PlaybackTransitionRequest(
        mediaItems = (0 until player.mediaItemCount).map(player::getMediaItemAt),
        startIndex = startIndex,
        startPositionMs = startPositionMs,
        repeatMode = player.repeatMode,
        shuffleModeEnabled = player.shuffleModeEnabled,
        playWhenReady = player.playWhenReady,
        keepCurrentQueue = true,
        copyCurrentShuffleOrder = true
    )
}

/**
 * The Activity and MediaSessionService run in the same app process. Keeping queue snapshots here
 * avoids sending potentially large playlists through Binder as custom-command extras.
 */
internal object PendingPlaybackTransitionRequests {
    private data class Entry(val request: PlaybackTransitionRequest, val expiresAtElapsedMs: Long)

    private const val REQUEST_LIFETIME_MS = 30_000L
    private val requests = ConcurrentHashMap<String, Entry>()
    private val cleanupHandler by lazy { Handler(Looper.getMainLooper()) }

    fun register(request: PlaybackTransitionRequest): String {
        val requestId = UUID.randomUUID().toString()
        val entry = Entry(request, SystemClock.elapsedRealtime() + REQUEST_LIFETIME_MS)
        requests[requestId] = entry
        cleanupHandler.postDelayed({ requests.remove(requestId, entry) }, REQUEST_LIFETIME_MS)
        return requestId
    }

    fun take(requestId: String): PlaybackTransitionRequest? {
        val entry = requests.remove(requestId) ?: return null
        return entry.request.takeIf { SystemClock.elapsedRealtime() <= entry.expiresAtElapsedMs }
    }

    fun discard(requestId: String) {
        requests.remove(requestId)
    }
}

internal object PlaybackTransitionProtocol {
    const val ACTION_TRANSITION_TO_QUEUE = "com.tunelyst.music.action.TRANSITION_TO_QUEUE"
    const val EXTRA_REQUEST_ID = "com.tunelyst.music.extra.TRANSITION_REQUEST_ID"

    val transitionToQueueCommand = SessionCommand(ACTION_TRANSITION_TO_QUEUE, Bundle.EMPTY)
}

internal data class CrossfadeGains(val outgoing: Float, val incoming: Float)

/** Returns a normalized pair of gains for a crossfade position in the inclusive range 0–1. */
internal fun crossfadeGains(progress: Float, curve: CrossfadeCurve): CrossfadeGains {
    val normalizedProgress = progress.coerceIn(0f, 1f)
    return when (curve) {
        CrossfadeCurve.EQUAL_POWER -> {
            val angle = normalizedProgress * (PI.toFloat() / 2f)
            CrossfadeGains(
                outgoing = cos(angle).coerceIn(0f, 1f),
                incoming = sin(angle).coerceIn(0f, 1f)
            )
        }
        CrossfadeCurve.LINEAR -> CrossfadeGains(
            outgoing = 1f - normalizedProgress,
            incoming = normalizedProgress
        )
    }
}

@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.tunelyst.music.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import com.tunelyst.music.data.Song
import com.tunelyst.music.playback.PendingPlaybackTransitionRequests
import com.tunelyst.music.playback.inQueueTransitionRequest
import com.tunelyst.music.playback.PlaybackTransitionProtocol
import com.tunelyst.music.playback.PlaybackTransitionRequest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executor

internal fun MusicViewModel.clearPlaybackErrorImpl() {
    lastPlaybackError = null
    _playbackState.update { it.copy(lastPlaybackError = null) }
}

internal fun MusicViewModel.clearSmartShuffleCompletionPromptImpl() {
    promptForSmartShuffleOnCompletion = false
    _playbackState.update { it.copy(promptForSmartShuffleOnCompletion = false) }
}

internal fun MusicViewModel.dispatchQueueTransitionImpl(
    activePlayer: Player,
    request: PlaybackTransitionRequest,
    expectedGeneration: Long,
    fallback: () -> Unit
) {
    val controller = activePlayer as? MediaController
    if (controller == null) {
        fallback()
        return
    }
    val requestId = PendingPlaybackTransitionRequests.register(request)
    val args = Bundle().apply {
        putString(PlaybackTransitionProtocol.EXTRA_REQUEST_ID, requestId)
    }
    val future = runCatching {
        controller.sendCustomCommand(PlaybackTransitionProtocol.transitionToQueueCommand, args)
    }.getOrElse {
        PendingPlaybackTransitionRequests.discard(requestId)
        fallback()
        return
    }
    future.addListener(
        Runnable {
            val accepted = runCatching { future.get().resultCode == SessionResult.RESULT_SUCCESS }
                .getOrDefault(false)
            if (!accepted) {
                PendingPlaybackTransitionRequests.discard(requestId)
                Handler(Looper.getMainLooper()).post {
                    if (player === activePlayer && playbackGeneration == expectedGeneration) fallback()
                }
            }
        },
        Executor { command -> command.run() }
    )
}

internal fun MusicViewModel.dispatchInQueueTransitionImpl(
    activePlayer: Player,
    targetIndex: Int,
    fallback: () -> Unit
) {
    val request = inQueueTransitionRequest(activePlayer, targetIndex)
    if (request == null) {
        fallback()
        return
    }
    dispatchQueueTransition(activePlayer, request, playbackGeneration, fallback)
}

internal fun MusicViewModel.playFromImpl(
    queue: List<Song>,
    index: Int,
    shuffle: Boolean? = null,
    autoShuffleAtEnd: Boolean = false,
    preserveOrder: Boolean = false,
    smartShuffle: Boolean = false,
    radioSession: Boolean = false,
    promptForSmartShuffleOnCompletion: Boolean = false
) {
    if (queue.isEmpty()) return
    radioActive = radioSession && !smartShuffle
    playNextIndicatorIds.clear()
    queuedIndicatorIds.clear()
    if (shuffle == true) {
        playSmartShuffle(queue)
        return
    }
    clearPlaybackError()
    autoShuffleJob?.cancel()
    autoShuffleJob = null
    playbackGeneration++
    smartShuffleActive = smartShuffle
    preserveCurrentQueueOrder = preserveOrder
    autoShuffleOnCompletion = autoShuffleAtEnd || smartShuffle
    this.promptForSmartShuffleOnCompletion = promptForSmartShuffleOnCompletion && !autoShuffleOnCompletion && !smartShuffle
    val safeIndex = index.coerceIn(0, queue.lastIndex)
    val target = queue[safeIndex]
    if (listeningSession?.songId?.let { it != target.id } == true) finishCurrentSessionAsSkip()
    if (target.id < 0L) store.clearPlaybackSession()
    val alphabeticalOrder = compareBy<Song> { it.title.trim().lowercase() }
        .thenBy { it.artist.trim().lowercase() }
        .thenBy { it.album.trim().lowercase() }
    val expandedQueue = if (queue.size == 1 && !preserveOrder) {
        val remaining = _uiState.value.songs.distinctBy(Song::id)
            .filterNot { it.id == target.id }
            .sortedWith(alphabeticalOrder)
        listOf(target) + remaining
    } else queue
    val playbackQueue = if (preserveOrder) expandedQueue else expandedQueue.sortedWith(alphabeticalOrder)
    val playbackIndex = if (queue.size == 1 && !preserveOrder) 0 else
        playbackQueue.indexOfFirst { it.id == target.id }.takeIf { it >= 0 } ?: safeIndex
    val items = playbackQueue.map(::toMediaItem)
    player?.let { activePlayer ->
        // Repeat-one/all would prevent the Search/share auto-shuffle handoff from reaching STATE_ENDED.
        val requestedRepeatMode = if (autoShuffleOnCompletion || radioActive) {
            Player.REPEAT_MODE_OFF
        } else {
            activePlayer.repeatMode
        }
        val request = PlaybackTransitionRequest(
            mediaItems = items,
            startIndex = playbackIndex,
            startPositionMs = C.TIME_UNSET,
            repeatMode = requestedRepeatMode,
            shuffleModeEnabled = false,
            playWhenReady = true
        )
        val expectedGeneration = playbackGeneration
        dispatchQueueTransition(activePlayer, request, expectedGeneration) {
            activePlayer.shuffleModeEnabled = false
            activePlayer.repeatMode = requestedRepeatMode
            activePlayer.setMediaItems(items, playbackIndex, C.TIME_UNSET)
            activePlayer.prepare()
            activePlayer.play()
            syncPlayback(activePlayer)
            persistPlaybackSession(activePlayer, force = true)
        }
        recordPlayed(target.id, countPlay = false)
    }
    scheduleEmbeddedReplayGainForCurrentQueue()
}

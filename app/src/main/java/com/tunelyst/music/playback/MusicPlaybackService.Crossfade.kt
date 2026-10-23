@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.tunelyst.music.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.tunelyst.music.data.AudioSettingsContract

internal fun MusicPlaybackService.updateCrossfadeImpl() {
    if (!::foregroundPlayer.isInitialized) return
    updateSleepTimerFadeState()
    if (sleepTimerFadeActive) {
        if (pendingManualTransition != null) {
            applyPendingManualTransitionImmediately()
        } else if (crossfading && crossfadeKind == CrossfadeKind.MANUAL && activeManualTransition != null) {
            applyActiveManualTransitionImmediately()
        } else if (crossfading || preparedNextIndex != C.INDEX_UNSET || crossfadeCancelRestoreStartVolume != null) {
            cancelCrossfade(restoreVolume = false, debugReason = "sleep-timer")
        }
        return
    }
    if (updateCrossfadeCancelVolumeRestore()) return
    if (pendingManualTransition != null) {
        maybeBeginManualTransition()
        return
    }
    if (crossfading && crossfadeKind == CrossfadeKind.MANUAL) {
        updateManualCrossfade()
        return
    }

    val player = foregroundPlayer
    if (!player.isPlaying || player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
        if (crossfading && !player.playWhenReady && ::standbyPlayer.isInitialized) standbyPlayer.pause()
        return
    }
    val requestedMs = audioSettings.crossfadeSeconds * 1_000L
    if (requestedMs <= 0L || player.repeatMode == Player.REPEAT_MODE_ONE || !player.hasNextMediaItem()) {
        if (crossfading || preparedNextIndex != C.INDEX_UNSET) cancelCrossfade(restoreVolume = true)
        return
    }
    val durationMs = player.duration
    if (durationMs <= 0L || durationMs == C.TIME_UNSET) return
    val nextIndex = player.nextMediaItemIndex
    if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until player.mediaItemCount) return
    if (audioSettings.crossfadeAlbumBoundariesOnly && sameAlbum(player.currentMediaItem, player.getMediaItemAt(nextIndex))) {
        if (crossfading || preparedNextIndex != C.INDEX_UNSET) cancelCrossfade(restoreVolume = true)
        return
    }

    val effectiveFadeMs = minOf(requestedMs, (durationMs / 2L).coerceAtLeast(1L))
    val remainingMs = (durationMs - player.currentPosition).coerceAtLeast(0L)
    if (remainingMs <= effectiveFadeMs + PRELOAD_LEAD_MS && preparedNextIndex != nextIndex && !crossfading) {
        prepareNextPlayer(nextIndex)
    }
    if (!crossfading && remainingMs in 1L..effectiveFadeMs && preparedNextIndex == nextIndex &&
        ::standbyPlayer.isInitialized && standbyPlayer.playbackState == Player.STATE_READY
    ) {
        beginCrossfade(player.currentPosition, minOf(effectiveFadeMs, remainingMs), nextIndex)
    }
    if (crossfading && crossfadeKind == CrossfadeKind.AUTOMATIC) {
        if (player.currentMediaItemIndex != fadeSourceIndex || player.nextMediaItemIndex != preparedNextIndex) {
            cancelCrossfade(restoreVolume = true, smoothRestore = true)
            return
        }
        val elapsed = (player.currentPosition - fadeStartPositionMs).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / fadeDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        applyCrossfadeGains(progress)
    }
}

internal fun MusicPlaybackService.applyCrossfadeGainsImpl(progress: Float) {
    if (!::standbyPlayer.isInitialized || !::foregroundPlayer.isInitialized) return
    val gains = crossfadeGains(progress, audioSettings.crossfadeCurve)
    foregroundPlayer.volume = gains.outgoing
    standbyPlayer.volume = gains.incoming
}

internal fun MusicPlaybackService.updateManualCrossfadeImpl() {
    if (!::standbyPlayer.isInitialized || !foregroundPlayer.isPlaying || !standbyPlayer.isPlaying) return
    resumeManualFadeClockIfPlaying()
    if (manualFadeStartedAtElapsedMs == 0L) return
    val elapsedMs = manualFadeElapsedMs +
        (SystemClock.elapsedRealtime() - manualFadeStartedAtElapsedMs).coerceAtLeast(0L)
    val progress = (elapsedMs.toFloat() / fadeDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    applyCrossfadeGains(progress)
    if (progress >= 1f) completeCrossfade()
}

internal fun MusicPlaybackService.sameAlbumImpl(first: MediaItem?, second: MediaItem?): Boolean {
    val firstMetadata = first?.mediaMetadata ?: return false
    val secondMetadata = second?.mediaMetadata ?: return false
    val firstAlbum = firstMetadata.albumTitle?.toString()?.trim().orEmpty()
    val secondAlbum = secondMetadata.albumTitle?.toString()?.trim().orEmpty()
    if (firstAlbum.isBlank() || secondAlbum.isBlank() || !firstAlbum.equals(secondAlbum, ignoreCase = true)) return false
    val firstArtist = firstMetadata.albumArtist?.toString()?.trim().orEmpty()
    val secondArtist = secondMetadata.albumArtist?.toString()?.trim().orEmpty()
    return firstArtist.isBlank() || secondArtist.isBlank() || firstArtist.equals(secondArtist, ignoreCase = true)
}

internal fun MusicPlaybackService.advancePastCurrentManualTransitionImpl(): Boolean {
    if (pendingManualTransition == null &&
        !(crossfading && crossfadeKind == CrossfadeKind.MANUAL)
    ) return false

    if (crossfading && crossfadeKind == CrossfadeKind.MANUAL) {
        completeCrossfade()
    } else {
        applyPendingManualTransitionImmediately()
    }
    val active = foregroundPlayer
    if (active.hasNextMediaItem()) requestManualTransitionToIndex(active.nextMediaItemIndex)
    return true
}

internal fun MusicPlaybackService.requestManualTransitionToIndexImpl(index: Int, positionMs: Long = C.TIME_UNSET) {
    val request = inQueueTransitionRequest(foregroundPlayer, index, positionMs) ?: return
    transitionToQueue(request)
}

internal fun MusicPlaybackService.transitionToQueueImpl(request: PlaybackTransitionRequest): Boolean {
    if (!::foregroundPlayer.isInitialized || request.mediaItems.isEmpty() ||
        request.startIndex !in request.mediaItems.indices
    ) return false
    val source = foregroundPlayer
    val targetMediaItem = request.mediaItems[request.startIndex]
    lastManualTransitionDebugEvent = "request:${targetMediaItem.mediaId}/source=${source.currentMediaItem?.mediaId}"

    val isEquivalentAutomaticTarget = crossfading && crossfadeKind == CrossfadeKind.AUTOMATIC &&
        targetMediaItem.mediaId == targetMediaId && request.startIndex == preparedNextIndex &&
        request.repeatMode == source.repeatMode && request.shuffleModeEnabled == source.shuffleModeEnabled &&
        queuesHaveSameIds(request.mediaItems, source)
    if (isEquivalentAutomaticTarget) {
        completeCrossfade()
        return true
    }

    if (crossfading || pendingManualTransition != null || preparedNextIndex != C.INDEX_UNSET ||
        crossfadeCancelRestoreStartVolume != null
    ) {
        cancelCrossfade(
            restoreVolume = !sleepTimerFadeActive,
            smoothRestore = crossfading
        )
    }

    val manualFadeMs = audioSettings.manualSkipFadeSeconds.coerceIn(
        0,
        AudioSettingsContract.MAX_MANUAL_SKIP_FADE_SECONDS
    ) * 1_000L
    val shouldOverlap = manualFadeMs > 0L && source.isPlaying && request.playWhenReady &&
        !sleepTimerFadeActive && source.currentMediaItem?.mediaId != targetMediaItem.mediaId

    if (!shouldOverlap) {
        applyQueueToForeground(request, restoreVolume = !sleepTimerFadeActive)
        lastManualTransitionDebugEvent = "immediate:${targetMediaItem.mediaId}"
        return true
    }

    ensureStandbyPlayer()
    pendingManualTransition = request
    activeManualTransition = null
    manualTransitionSourceMediaId = source.currentMediaItem?.mediaId
    manualTransitionSourceQueue = (0 until source.mediaItemCount).map(source::getMediaItemAt)
    manualTransitionSourceRepeatMode = source.repeatMode
    manualTransitionSourceShuffleEnabled = source.shuffleModeEnabled
    lastManualTransitionDebugEvent = "pending:${targetMediaItem.mediaId}"
    preparedNextIndex = request.startIndex
    targetMediaId = targetMediaItem.mediaId
    fadeSourceIndex = source.currentMediaItemIndex
    fadeStartPositionMs = 0L
    val sourceDurationMs = source.duration
    val sourceRemainingMs = if (sourceDurationMs > 0L && sourceDurationMs != C.TIME_UNSET) {
        (sourceDurationMs - source.currentPosition).coerceAtLeast(1L)
    } else {
        manualFadeMs
    }
    fadeDurationMs = minOf(manualFadeMs, sourceRemainingMs).coerceAtLeast(1L)
    manualFadeElapsedMs = 0L
    manualFadeStartedAtElapsedMs = 0L

    standbyPlayer.volume = 0f
    standbyPlayer.repeatMode = request.repeatMode
    standbyPlayer.setMediaItems(request.mediaItems, request.startIndex, request.startPositionMs)
    if (request.copyCurrentShuffleOrder && source.shuffleModeEnabled) {
        standbyPlayer.setShuffleOrder(source.shuffleOrder)
    }
    standbyPlayer.shuffleModeEnabled = request.shuffleModeEnabled
    standbyPlayer.prepare()
    scheduleManualTransitionTimeout()
    maybeBeginManualTransition()
    return true
}

internal fun MusicPlaybackService.queuesHaveSameIdsImpl(items: List<MediaItem>, source: ExoPlayer): Boolean {
    if (items.size != source.mediaItemCount) return false
    return items.indices.all { index -> items[index].mediaId == source.getMediaItemAt(index).mediaId }
}

internal fun MusicPlaybackService.maybeBeginManualTransitionImpl() {
    val request = pendingManualTransition ?: return
    if (sleepTimerFadeActive) {
        applyPendingManualTransitionImmediately()
        return
    }
    if (crossfadeCancelRestoreStartVolume != null) return
    if (!::standbyPlayer.isInitialized || standbyPlayer.playbackState != Player.STATE_READY) return
    if (foregroundPlayer.currentMediaItem?.mediaId != manualTransitionSourceMediaId ||
        !foregroundPlayer.isPlaying || !request.playWhenReady
    ) {
        applyPendingManualTransitionImmediately()
        return
    }
    val target = request.mediaItems[request.startIndex]
    if (target.mediaId != standbyPlayer.currentMediaItem?.mediaId) {
        applyPendingManualTransitionImmediately()
        return
    }

    clearManualTransitionTimeout()
    pendingManualTransition = null
    activeManualTransition = request
    crossfading = true
    crossfadeKind = CrossfadeKind.MANUAL
    lastManualTransitionDebugEvent = "active:${target.mediaId}"
    fadeSourceIndex = foregroundPlayer.currentMediaItemIndex
    fadeStartPositionMs = 0L
    targetMediaId = target.mediaId
    CrossfadeDisplayState.showIncoming(targetMediaId)
    preparedNextIndex = request.startIndex
    val incomingDurationMs = standbyPlayer.duration
    if (incomingDurationMs > 0L && incomingDurationMs != C.TIME_UNSET) {
        fadeDurationMs = minOf(fadeDurationMs, incomingDurationMs).coerceAtLeast(1L)
    }
    manualFadeElapsedMs = 0L
    manualFadeStartedAtElapsedMs = 0L
    standbyPlayer.volume = 0f
    scheduleManualTransitionTimeout()
    standbyPlayer.play()
    resumeManualFadeClockIfPlaying()
}

internal fun MusicPlaybackService.scheduleManualTransitionTimeoutImpl(timeoutMs: Long = MANUAL_PREPARE_TIMEOUT_MS) {
    clearManualTransitionTimeout()
    val timeout = Runnable {
        when {
            pendingManualTransition != null -> applyPendingManualTransitionImmediately()
            crossfading && crossfadeKind == CrossfadeKind.MANUAL && activeManualTransition != null ->
                applyActiveManualTransitionImmediately()
        }
    }
    manualTransitionTimeout = timeout
    mainHandler.postDelayed(timeout, timeoutMs)
}

internal fun MusicPlaybackService.clearManualTransitionTimeoutImpl() {
    manualTransitionTimeout?.let(mainHandler::removeCallbacks)
    manualTransitionTimeout = null
}

internal fun MusicPlaybackService.applyPendingManualTransitionImmediatelyImpl() {
    val request = pendingManualTransition ?: return
    clearManualTransitionTimeout()
    pendingManualTransition = null
    manualTransitionSourceMediaId = null
    if (crossfading || preparedNextIndex != C.INDEX_UNSET || crossfadeCancelRestoreStartVolume != null) {
        cancelCrossfade(restoreVolume = !sleepTimerFadeActive, smoothRestore = crossfading)
    }
    applyManualTransitionImmediately(request, restoreVolume = !sleepTimerFadeActive)
    lastManualTransitionDebugEvent = "pending-fallback:${request.mediaItems.getOrNull(request.startIndex)?.mediaId}"
}

internal fun MusicPlaybackService.applyActiveManualTransitionImmediatelyImpl() {
    val request = activeManualTransition ?: return
    val restoreVolume = !sleepTimerFadeActive
    cancelCrossfade(restoreVolume = restoreVolume)
    applyManualTransitionImmediately(request, restoreVolume = restoreVolume)
    lastManualTransitionDebugEvent = "active-fallback:${request.mediaItems.getOrNull(request.startIndex)?.mediaId}"
}

internal fun MusicPlaybackService.applyQueueToForegroundImpl(request: PlaybackTransitionRequest, restoreVolume: Boolean) {
    if (!::foregroundPlayer.isInitialized) return
    if (restoreVolume) {
        foregroundPlayer.volume = 1f
        clearCrossfadeCancelVolumeRestore()
    }
    if (request.keepCurrentQueue) {
        foregroundPlayer.seekTo(request.startIndex, request.startPositionMs)
        if (request.playWhenReady) foregroundPlayer.play() else foregroundPlayer.pause()
        return
    }
    foregroundPlayer.repeatMode = request.repeatMode
    foregroundPlayer.shuffleModeEnabled = request.shuffleModeEnabled
    foregroundPlayer.setMediaItems(request.mediaItems, request.startIndex, request.startPositionMs)
    foregroundPlayer.prepare()
    if (request.playWhenReady) foregroundPlayer.play() else foregroundPlayer.pause()
}

internal fun MusicPlaybackService.applyManualTransitionImmediatelyImpl(request: PlaybackTransitionRequest, restoreVolume: Boolean) {
    if (!::foregroundPlayer.isInitialized) return
    applyQueueToForeground(
        request.copy(playWhenReady = foregroundPlayer.playWhenReady),
        restoreVolume = restoreVolume
    )
}

internal fun MusicPlaybackService.resumeManualFadeClockIfPlayingImpl() {
    if (crossfadeKind != CrossfadeKind.MANUAL || manualFadeStartedAtElapsedMs != 0L ||
        !foregroundPlayer.isPlaying || !::standbyPlayer.isInitialized || !standbyPlayer.isPlaying
    ) return
    manualFadeStartedAtElapsedMs = SystemClock.elapsedRealtime()
    val remainingFadeMs = (fadeDurationMs - manualFadeElapsedMs).coerceAtLeast(0L)
    scheduleManualTransitionTimeout(remainingFadeMs + MANUAL_FADE_COMPLETION_GRACE_MS)
}

internal fun MusicPlaybackService.pauseManualFadeClockImpl() {
    if (crossfadeKind == CrossfadeKind.MANUAL && activeManualTransition != null) {
        clearManualTransitionTimeout()
    }
    val startedAt = manualFadeStartedAtElapsedMs
    if (startedAt == 0L) return
    manualFadeElapsedMs += (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    manualFadeStartedAtElapsedMs = 0L
}

@OptIn(UnstableApi::class)
internal fun MusicPlaybackService.prepareNextPlayerImpl(nextIndex: Int) {
    val source = foregroundPlayer
    if (nextIndex !in 0 until source.mediaItemCount) return
    ensureStandbyPlayer()
    clearPreparedPlayer()
    val items = (0 until source.mediaItemCount).map(source::getMediaItemAt)
    standbyPlayer.volume = 0f
    standbyPlayer.repeatMode = source.repeatMode
    standbyPlayer.shuffleModeEnabled = source.shuffleModeEnabled
    standbyPlayer.setMediaItems(items, nextIndex, 0L)
    // The shuffle-enabled flag does not copy the playlist's randomized order. Both players must
    // traverse the same precomputed order so swapping players at the crossfade doesn't reshuffle.
    standbyPlayer.setShuffleOrder(source.shuffleOrder)
    standbyPlayer.prepare()
    standbyPlayer.pause()
    preparedNextIndex = nextIndex
    targetMediaId = source.getMediaItemAt(nextIndex).mediaId
}

internal fun MusicPlaybackService.beginCrossfadeImpl(startPositionMs: Long, durationMs: Long, nextIndex: Int) {
    if (durationMs <= 0L || !::standbyPlayer.isInitialized || standbyPlayer.playbackState != Player.STATE_READY) return
    crossfading = true
    crossfadeKind = CrossfadeKind.AUTOMATIC
    fadeSourceIndex = foregroundPlayer.currentMediaItemIndex
    fadeStartPositionMs = startPositionMs
    fadeDurationMs = durationMs
    preparedNextIndex = nextIndex
    targetMediaId = foregroundPlayer.getMediaItemAt(nextIndex).mediaId
    CrossfadeDisplayState.showIncoming(targetMediaId)
    manualFadeElapsedMs = 0L
    manualFadeStartedAtElapsedMs = 0L
    standbyPlayer.volume = 0f
    standbyPlayer.play()
}

internal fun MusicPlaybackService.completeCrossfadeImpl() {
    if (!crossfading) return
    lastManualTransitionDebugEvent = "complete:${targetMediaId}"
    val outgoing = foregroundPlayer
    val incoming = standbyPlayer
    incoming.volume = 1f
    outgoing.volume = 0f
    incoming.setAudioAttributes(musicAudioAttributes, true)
    foregroundPlayer = incoming
    standbyPlayer = outgoing
    mediaSession.setPlayer(createSessionPlayer(foregroundPlayer))
    crossfading = false
    crossfadeKind = null
    activeManualTransition = null
    pendingManualTransition = null
    manualTransitionSourceMediaId = null
    manualTransitionSourceQueue = null
    manualTransitionSourceRepeatMode = null
    manualTransitionSourceShuffleEnabled = null
    clearManualTransitionTimeout()
    manualFadeElapsedMs = 0L
    manualFadeStartedAtElapsedMs = 0L
    clearCrossfadeCancelVolumeRestore()
    preparedNextIndex = C.INDEX_UNSET
    targetMediaId = null
    fadeSourceIndex = C.INDEX_UNSET
    fadeStartPositionMs = 0L
    fadeDurationMs = 0L
    standbyPlayer.pause()
    standbyPlayer.stop()
    standbyPlayer.clearMediaItems()
    standbyPlayer.volume = 1f
    standbyPlayer.setAudioAttributes(musicAudioAttributes, false)
}

internal fun MusicPlaybackService.cancelCrossfadeImpl(
    restoreVolume: Boolean,
    smoothRestore: Boolean = false,
    debugReason: String = "unspecified"
) {
    val wasCrossfading = crossfading
    val hadManualTransition = crossfadeKind == CrossfadeKind.MANUAL ||
        pendingManualTransition != null || activeManualTransition != null
    if (hadManualTransition) lastManualTransitionDebugEvent = "cancel:$debugReason"
    val volumeBeforeCancel = if (::foregroundPlayer.isInitialized) foregroundPlayer.volume else 1f
    targetMediaId?.let { CrossfadeDisplayState.clearIncoming(it) }
    crossfading = false
    crossfadeKind = null
    pendingManualTransition = null
    activeManualTransition = null
    manualTransitionSourceMediaId = null
    manualTransitionSourceQueue = null
    manualTransitionSourceRepeatMode = null
    manualTransitionSourceShuffleEnabled = null
    clearManualTransitionTimeout()
    manualFadeElapsedMs = 0L
    manualFadeStartedAtElapsedMs = 0L
    if (restoreVolume && ::foregroundPlayer.isInitialized) {
        if (smoothRestore && wasCrossfading && volumeBeforeCancel < 0.999f) {
            crossfadeCancelRestoreStartVolume = volumeBeforeCancel.coerceIn(0f, 1f)
            crossfadeCancelRestoreStartedAtMs = SystemClock.elapsedRealtime()
        } else {
            foregroundPlayer.volume = 1f
            clearCrossfadeCancelVolumeRestore()
        }
    } else {
        clearCrossfadeCancelVolumeRestore()
    }
    if (::standbyPlayer.isInitialized) {
        standbyPlayer.pause()
        standbyPlayer.stop()
        standbyPlayer.clearMediaItems()
        standbyPlayer.volume = 1f
    }
    preparedNextIndex = C.INDEX_UNSET
    targetMediaId = null
    fadeSourceIndex = C.INDEX_UNSET
    fadeStartPositionMs = 0L
    fadeDurationMs = 0L
}

internal fun MusicPlaybackService.updateCrossfadeCancelVolumeRestoreImpl(): Boolean {
    val startVolume = crossfadeCancelRestoreStartVolume ?: return false
    val elapsedMs = (SystemClock.elapsedRealtime() - crossfadeCancelRestoreStartedAtMs).coerceAtLeast(0L)
    val progress = (elapsedMs.toFloat() / CROSSFADE_CANCEL_RESTORE_DURATION_MS).coerceIn(0f, 1f)
    foregroundPlayer.volume = startVolume + (1f - startVolume) * progress
    if (progress >= 1f) {
        foregroundPlayer.volume = 1f
        clearCrossfadeCancelVolumeRestore()
        return false
    }
    return true
}

internal fun MusicPlaybackService.clearCrossfadeCancelVolumeRestoreImpl() {
    crossfadeCancelRestoreStartVolume = null
    crossfadeCancelRestoreStartedAtMs = 0L
}

internal fun MusicPlaybackService.clearPreparedPlayerImpl() {
    if (crossfading) return
    if (::standbyPlayer.isInitialized) {
        standbyPlayer.pause()
        standbyPlayer.stop()
        standbyPlayer.clearMediaItems()
        standbyPlayer.volume = 1f
    }
    preparedNextIndex = C.INDEX_UNSET
    targetMediaId = null
}

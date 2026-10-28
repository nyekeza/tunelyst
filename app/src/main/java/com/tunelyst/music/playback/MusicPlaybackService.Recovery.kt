@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.tunelyst.music.playback

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

internal fun MusicPlaybackService.manualTransitionDebugStateImpl(): String {
    val source = foregroundPlayer
    val standby = if (isStandbyPlayerInitialized()) standbyPlayer else null
    val standbyState = standby?.let {
        "${it.currentMediaItem?.mediaId}/${it.playbackState}/${it.playWhenReady}/${it.isPlaying}/${it.currentPosition}"
    } ?: "uninitialized"
    val sourceEffects = "${equalizers.containsKey(source)}/${loudnessEnhancers.containsKey(source)}"
    val standbyEffects = standby?.let {
        "${equalizers.containsKey(it)}/${loudnessEnhancers.containsKey(it)}"
    } ?: "uninitialized"
    val standbySessionId = standby?.let { audioSessionIds[it] ?: 0 } ?: 0
    val sourceVideoDisabled = C.TRACK_TYPE_VIDEO in source.trackSelectionParameters.disabledTrackTypes
    val standbyVideoDisabled = standby?.let {
        C.TRACK_TYPE_VIDEO in it.trackSelectionParameters.disabledTrackTypes
    } ?: false
    val memoryGuardState = "$memoryGuardLastPressureBytes/$memoryGuardThresholdBytes/" +
        "$memoryGuardHighSamples/$memoryGuardRecycleCount/$memoryGuardRecoveryAttemptCount"
    return "event=$lastManualTransitionDebugEvent;standbyGeneration=$standbyPlayerGeneration;" +
        "foregroundGeneration=$foregroundPlayerGeneration;" +
        "foregroundPlayerIdentity=${System.identityHashCode(source)};" +
        "pending=${pendingManualTransition != null};active=${activeManualTransition != null};" +
        "crossfade=$crossfading/$crossfadeKind;" +
        "source=${source.currentMediaItem?.mediaId}/${source.currentMediaItemIndex}/" +
        "${source.playbackState}/${source.playWhenReady}/${source.isPlaying}/${source.currentPosition};" +
        "standby=$standbyState;fade=$manualFadeElapsedMs/$fadeDurationMs;" +
        "started=$manualFadeStartedAtElapsedMs;timeout=${manualTransitionTimeout != null};" +
        "prepared=$preparedNextIndex;sessions=${audioSessionIds[source] ?: 0}/$standbySessionId;" +
        "effects=eq${equalizers.size}/le${loudnessEnhancers.size};" +
        "sourceEffects=$sourceEffects;standbyEffects=$standbyEffects;" +
        "videoDisabled=$sourceVideoDisabled/$standbyVideoDisabled;memory=$memoryGuardState;" +
        "memoryNativePss=$memoryGuardLastNativePssBytes;" +
        "memoryTotalPss=$memoryGuardLastTotalPssBytes;" +
        "memoryNativeAllocated=$memoryGuardLastNativeAllocatedBytes;" +
        "memoryPressure=$memoryGuardLastPressureBytes;" +
        "memoryRecoveryBaseline=$memoryGuardRecoveryBaselinePressureBytes;" +
        "memoryLastAttemptElapsed=$memoryGuardLastRecoveryAttemptElapsedMs"
}

internal fun MusicPlaybackService.recoverUnexpectedReadyPauseImpl() {
    if (!isForegroundPlayerInitialized()) return
    val player = foregroundPlayer
    val now = SystemClock.elapsedRealtime()
    val shouldBeAudiblyPlaying = player.playWhenReady &&
        player.playbackState == Player.STATE_READY &&
        player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
        !player.isPlaying
    if (!shouldBeAudiblyPlaying) {
        unexpectedPauseStartedAtElapsedMs = 0L
        return
    }
    if (unexpectedPauseStartedAtElapsedMs == 0L) {
        unexpectedPauseStartedAtElapsedMs = now
        return
    }
    val stuckForMs = now - unexpectedPauseStartedAtElapsedMs
    val sinceLastAttemptMs = now - lastUnexpectedPauseRecoveryAtElapsedMs
    if (stuckForMs < UNEXPECTED_PAUSE_RECOVERY_DELAY_MS ||
        sinceLastAttemptMs < UNEXPECTED_PAUSE_RECOVERY_COOLDOWN_MS
    ) return

    Log.w(
        "TunelystPlayback",
        "Recovering a ready player that remained paused without user intent or audio-focus suppression"
    )
    lastUnexpectedPauseRecoveryAtElapsedMs = now
    player.play()
}

internal fun MusicPlaybackService.ensureStandbyPlayerImpl(): ExoPlayer {
    if (!isStandbyPlayerInitialized()) standbyPlayer = createStandbyPlayerImpl()
    return standbyPlayer
}

internal fun MusicPlaybackService.createStandbyPlayerImpl(): ExoPlayer {
    val player = createPlayer(handleAudioFocus = false)
    installListener(player)
    if (isAudioSettingsInitialized()) {
        player.setPlaybackParameters(PlaybackParameters(audioSettings.playbackSpeed, audioSettings.playbackPitch))
    }
    standbyPlayerGeneration++
    return player
}

/** Fully releases the inactive decoder after a transition instead of reusing native codec state. */
internal fun MusicPlaybackService.recycleStandbyPlayerImpl() {
    if (!isStandbyPlayerInitialized()) return
    val oldPlayer = standbyPlayer
    runCatching { oldPlayer.pause() }
    runCatching { oldPlayer.stop() }
    runCatching { oldPlayer.clearMediaItems() }

    // Create the empty replacement before dropping the old instance, so a rare player-construction
    // failure leaves a stopped, cleared player available for the next transition attempt.
    val replacement = runCatching { createStandbyPlayerImpl() }
        .onFailure { Log.w("TunelystPlayback", "Could not recreate the standby player after a transition", it) }
        .getOrNull() ?: return
    standbyPlayer = replacement

    releaseDetachedPlayerImpl(oldPlayer, "standby recycle")
}

/** Releases all service-owned listener and audio-effect state for a player that is no longer active. */
internal fun MusicPlaybackService.releaseDetachedPlayerImpl(target: ExoPlayer, reason: String) {
    playerListeners.remove(target)?.let { listener ->
        runCatching { target.removeListener(listener) }
    }
    releaseEqualizer(target)
    releaseLoudnessEnhancer(target)
    audioSessionIds.remove(target)
    appliedReplayGainByPlayer.remove(target)
    runCatching { target.release() }
        .onFailure { Log.w("TunelystPlayback", "Could not release a detached player ($reason)", it) }
}

@OptIn(UnstableApi::class)
internal fun MusicPlaybackService.installListenerImpl(target: ExoPlayer) {
    val listener = object : Player.Listener {
        @OptIn(UnstableApi::class)
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (audioSessionId > 0) audioSessionIds[target] = audioSessionId
            else audioSessionIds.remove(target)
            attachEqualizer(target, audioSessionId)
            attachLoudnessEnhancer(target, audioSessionId)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && audioSettings.equalizerEnabled) {
                ensureEqualizerAttached(target)
            }
            if (isStandbyPlayerInitialized() && target === standbyPlayer && playbackState == Player.STATE_READY) {
                if (pendingManualTransition != null) maybeBeginManualTransition()
                else if (!crossfading && preparedNextIndex != C.INDEX_UNSET) updateCrossfade()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            applyReplayGain(target, mediaItem)
            if (target !== foregroundPlayer) return
            val displayedIncomingId = CrossfadeDisplayState.incomingMediaId.value
            if (displayedIncomingId != null && displayedIncomingId != mediaItem?.mediaId) {
                CrossfadeDisplayState.clearIncoming(displayedIncomingId)
            }
            if (mediaItem?.mediaId != transientRetryAttemptedMediaId) transientRetryAttemptedMediaId = null
            val expectedAutoTransition = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            val manualRequest = pendingManualTransition ?: activeManualTransition
            if (manualRequest != null) {
                cancelCrossfade(restoreVolume = !sleepTimerFadeActive)
                applyManualTransitionImmediately(manualRequest, restoreVolume = !sleepTimerFadeActive)
            } else if (crossfading && expectedAutoTransition && mediaItem?.mediaId == targetMediaId) {
                completeCrossfade()
            } else if (crossfading || preparedNextIndex != C.INDEX_UNSET) {
                cancelCrossfade(restoreVolume = !sleepTimerFadeActive, smoothRestore = true)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            recoverPlaybackAfterError(target, error)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            applyReplayGain(target, target.currentMediaItem)
            if (target === foregroundPlayer && reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                transientRetryAttemptedMediaId = null
            }
            val manualTransitionIsUnchanged = (pendingManualTransition != null ||
                crossfading && crossfadeKind == CrossfadeKind.MANUAL) &&
                manualTransitionSourceQueue?.let { queuesHaveSameIds(it, target) } == true &&
                manualTransitionSourceRepeatMode == target.repeatMode &&
                manualTransitionSourceShuffleEnabled == target.shuffleModeEnabled
            if (target === foregroundPlayer &&
                (crossfading || preparedNextIndex != C.INDEX_UNSET || pendingManualTransition != null) &&
                !manualTransitionIsUnchanged
            ) {
                cancelCrossfade(
                    restoreVolume = !sleepTimerFadeActive,
                    smoothRestore = true,
                    debugReason = "timeline-changed"
                )
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            val manualRequest = pendingManualTransition
                ?: activeManualTransition.takeIf { crossfadeKind == CrossfadeKind.MANUAL }
            val manualSourceIsStillCurrent = manualRequest != null &&
                target.currentMediaItemIndex == fadeSourceIndex &&
                target.currentMediaItem?.mediaId == manualTransitionSourceMediaId
            if (target === foregroundPlayer && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                (crossfading || pendingManualTransition != null) && !manualSourceIsStillCurrent
            ) {
                cancelCrossfade(
                    restoreVolume = !sleepTimerFadeActive,
                    smoothRestore = true,
                    debugReason = "position-discontinuity-$reason"
                )
            } else if (target === foregroundPlayer && !crossfading && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                pendingManualTransition == null
            ) {
                clearPreparedPlayer()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && audioSettings.equalizerEnabled) ensureEqualizerAttached(target)
            if (target === foregroundPlayer && crossfading && isStandbyPlayerInitialized()) {
                if (isPlaying) {
                    standbyPlayer.play()
                    resumeManualFadeClockIfPlaying()
                } else {
                    pauseManualFadeClock()
                    standbyPlayer.pause()
                }
            } else if (isStandbyPlayerInitialized() && target === standbyPlayer &&
                crossfading && crossfadeKind == CrossfadeKind.MANUAL
            ) {
                if (isPlaying) resumeManualFadeClockIfPlaying() else pauseManualFadeClock()
            }
            if (target === foregroundPlayer && !isPlaying && pendingManualTransition != null &&
                isStandbyPlayerInitialized() && standbyPlayer.playbackState == Player.STATE_READY
            ) {
                applyPendingManualTransitionImmediately()
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            val manualTransitionIsUnchanged = (pendingManualTransition != null ||
                crossfading && crossfadeKind == CrossfadeKind.MANUAL) &&
                manualTransitionSourceShuffleEnabled == shuffleModeEnabled
            if (target === foregroundPlayer &&
                (crossfading || preparedNextIndex != C.INDEX_UNSET || pendingManualTransition != null) &&
                !manualTransitionIsUnchanged
            ) {
                cancelCrossfade(
                    restoreVolume = !sleepTimerFadeActive,
                    smoothRestore = true,
                    debugReason = "shuffle-changed"
                )
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            val manualTransitionIsUnchanged = (pendingManualTransition != null ||
                crossfading && crossfadeKind == CrossfadeKind.MANUAL) &&
                manualTransitionSourceRepeatMode == repeatMode
            if (target === foregroundPlayer &&
                (crossfading || preparedNextIndex != C.INDEX_UNSET || pendingManualTransition != null) &&
                !manualTransitionIsUnchanged
            ) {
                cancelCrossfade(
                    restoreVolume = !sleepTimerFadeActive,
                    smoothRestore = true,
                    debugReason = "repeat-changed"
                )
            }
        }
    }
    playerListeners[target] = listener
    target.addListener(listener)
}

internal fun MusicPlaybackService.recoverPlaybackAfterErrorImpl(target: ExoPlayer, error: PlaybackException) {
    val failedItemId = target.currentMediaItem?.mediaId
    if (target !== foregroundPlayer) {
        Log.w("TunelystPlayback", "Standby player failed for $failedItemId; preserving foreground playback", error)
        if (isStandbyPlayerInitialized() && target === standbyPlayer) {
            val manualRequest = pendingManualTransition ?: activeManualTransition
            if (manualRequest != null) {
                cancelCrossfade(restoreVolume = !sleepTimerFadeActive, smoothRestore = true)
                applyManualTransitionImmediately(manualRequest, restoreVolume = false)
            } else if (crossfading) {
                cancelCrossfade(restoreVolume = !sleepTimerFadeActive)
            } else if (preparedNextIndex != C.INDEX_UNSET) {
                clearPreparedPlayer()
            }
        }
        return
    }

    Log.e("TunelystPlayback", "Foreground playback failed for $failedItemId (code ${error.errorCode})", error)
    val manualRequest = pendingManualTransition ?: activeManualTransition
    if (manualRequest != null) {
        cancelCrossfade(restoreVolume = !sleepTimerFadeActive)
        applyManualTransitionImmediately(manualRequest, restoreVolume = !sleepTimerFadeActive)
        return
    }
    if (crossfading || preparedNextIndex != C.INDEX_UNSET) {
        cancelCrossfade(restoreVolume = !sleepTimerFadeActive)
    }
    if (!target.playWhenReady) return

    val transientReadFailure = error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
    if (transientReadFailure && failedItemId != null && transientRetryAttemptedMediaId != failedItemId) {
        transientRetryAttemptedMediaId = failedItemId
        val failedIndex = target.currentMediaItemIndex
        lateinit var retry: Runnable
        retry = Runnable {
            pendingPlaybackErrorRecoveries.remove(retry)
            val retryStillApplies = isForegroundPlayerInitialized() && target === foregroundPlayer &&
                target.currentMediaItemIndex == failedIndex && target.currentMediaItem?.mediaId == failedItemId &&
                target.playWhenReady && target.playbackState == Player.STATE_IDLE
            if (retryStillApplies) {
                target.prepare()
                target.play()
            }
        }
        pendingPlaybackErrorRecoveries += retry
        mainHandler.postDelayed(retry, TRANSIENT_PLAYBACK_RETRY_DELAY_MS)
        return
    }

    val restoreRepeatOne = target.repeatMode == Player.REPEAT_MODE_ONE
    if (restoreRepeatOne) target.repeatMode = Player.REPEAT_MODE_OFF
    if (target.hasNextMediaItem()) {
        transientRetryAttemptedMediaId = null
        target.seekToNextMediaItem()
        target.prepare()
        target.play()
    }
    if (restoreRepeatOne) target.repeatMode = Player.REPEAT_MODE_ONE
}

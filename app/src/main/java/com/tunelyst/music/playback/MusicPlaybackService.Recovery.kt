@file:OptIn(androidx.media3.common.util.UnstableApi::class)
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
    val standby = if (::standbyPlayer.isInitialized) standbyPlayer else null
    val standbyState = standby?.let {
        "${it.currentMediaItem?.mediaId}/${it.playbackState}/${it.playWhenReady}/${it.isPlaying}/${it.currentPosition}"
    } ?: "uninitialized"
    return "event=$lastManualTransitionDebugEvent;pending=${pendingManualTransition != null};" +
        "active=${activeManualTransition != null};crossfade=$crossfading/$crossfadeKind;" +
        "source=${source.currentMediaItem?.mediaId}/${source.currentMediaItemIndex}/" +
        "${source.playbackState}/${source.playWhenReady}/${source.isPlaying}/${source.currentPosition};" +
        "standby=$standbyState;fade=$manualFadeElapsedMs/$fadeDurationMs;" +
        "started=$manualFadeStartedAtElapsedMs;timeout=${manualTransitionTimeout != null};" +
        "prepared=$preparedNextIndex"
}

internal fun MusicPlaybackService.recoverUnexpectedReadyPauseImpl() {
    if (!::foregroundPlayer.isInitialized) return
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
    if (!::standbyPlayer.isInitialized) {
        standbyPlayer = createPlayer(handleAudioFocus = false)
        installListener(standbyPlayer)
        if (::audioSettings.isInitialized) {
            standbyPlayer.setPlaybackParameters(PlaybackParameters(audioSettings.playbackSpeed, audioSettings.playbackPitch))
            applyReplayGain(standbyPlayer, standbyPlayer.currentMediaItem)
        }
    }
    return standbyPlayer
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
            if (::standbyPlayer.isInitialized && target === standbyPlayer && playbackState == Player.STATE_READY) {
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
            if (target === foregroundPlayer && crossfading && ::standbyPlayer.isInitialized) {
                if (isPlaying) {
                    standbyPlayer.play()
                    resumeManualFadeClockIfPlaying()
                } else {
                    pauseManualFadeClock()
                    standbyPlayer.pause()
                }
            } else if (::standbyPlayer.isInitialized && target === standbyPlayer &&
                crossfading && crossfadeKind == CrossfadeKind.MANUAL
            ) {
                if (isPlaying) resumeManualFadeClockIfPlaying() else pauseManualFadeClock()
            }
            if (target === foregroundPlayer && !isPlaying && pendingManualTransition != null &&
                ::standbyPlayer.isInitialized && standbyPlayer.playbackState == Player.STATE_READY
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
        if (::standbyPlayer.isInitialized && target === standbyPlayer) {
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
            val retryStillApplies = ::foregroundPlayer.isInitialized && target === foregroundPlayer &&
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

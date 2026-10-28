@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.tunelyst.music.playback

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.ShuffleOrder
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer

private data class PlaybackMemorySample(
    val nativePssBytes: Long,
    val totalPssBytes: Long,
    val nativeHeapAllocatedBytes: Long
) {
    /** PSS is the guard's primary signal; allocated bytes keep a useful fallback on OEM builds. */
    val pressureBytes: Long
        get() = nativePssBytes.takeIf { it > 0L } ?: nativeHeapAllocatedBytes
}

internal fun MusicPlaybackService.initializePlaybackMemoryGuardImpl() {
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    val totalDeviceMemoryBytes = runCatching {
        activityManager?.getMemoryInfo(memoryInfo)
        memoryInfo.totalMem
    }.getOrDefault(0L)
    memoryGuardThresholdBytes = PlaybackMemoryGuardPolicy.thresholdBytes(
        totalDeviceMemoryBytes = totalDeviceMemoryBytes,
        lowRamDevice = activityManager?.isLowRamDevice == true
    )

    val sample = readPlaybackMemorySample()
    recordPlaybackMemorySample(sample)
    memoryGuardRecoveryBaselinePressureBytes = sample.pressureBytes
    memoryGuardLastSampleElapsedMs = SystemClock.elapsedRealtime()
    Log.i(
        "TunelystPlayback",
        "Memory guard initialized: nativePss=${sample.nativePssBytes}, " +
            "nativeAllocated=${sample.nativeHeapAllocatedBytes}, totalPss=${sample.totalPssBytes}, " +
            "threshold=$memoryGuardThresholdBytes bytes"
    )
}

/** Samples memory during stable playback; the service ticker continues while playback is backgrounded. */
internal fun MusicPlaybackService.updatePlaybackMemoryGuardImpl() {
    if (!isForegroundPlayerInitialized()) return
    val now = SystemClock.elapsedRealtime()
    if (now - memoryGuardLastSampleElapsedMs < PlaybackMemoryGuardPolicy.SAMPLE_INTERVAL_MS) return
    memoryGuardLastSampleElapsedMs = now

    val player = foregroundPlayer
    val transitionInFlight = crossfading || pendingManualTransition != null ||
        activeManualTransition != null || preparedNextIndex != C.INDEX_UNSET ||
        crossfadeCancelRestoreStartVolume != null || sleepTimerFadeActive
    if (!player.isPlaying || player.currentMediaItem == null || transitionInFlight) {
        memoryGuardHighSamples = 0
        return
    }

    val sample = readPlaybackMemorySample()
    recordPlaybackMemorySample(sample)
    memoryGuardHighSamples = PlaybackMemoryGuardPolicy.nextHighSampleCount(
        current = memoryGuardHighSamples,
        pressureBytes = sample.pressureBytes,
        thresholdBytes = memoryGuardThresholdBytes,
        recoveryBaselineBytes = memoryGuardRecoveryBaselinePressureBytes,
        hasRecoveredBefore = memoryGuardRecycleCount > 0
    )
    if (!PlaybackMemoryGuardPolicy.shouldRecycle(
            highSamples = memoryGuardHighSamples,
            nowElapsedMs = now,
            lastAttemptElapsedMs = memoryGuardLastRecoveryAttemptElapsedMs
        )
    ) return

    memoryGuardHighSamples = 0
    memoryGuardLastRecoveryAttemptElapsedMs = now
    memoryGuardRecoveryAttemptCount++
    if (replaceForegroundPlayerForMemoryPressureImpl("sustained-native-pss", sample)) {
        memoryGuardRecycleCount++
    }
}

/** Debug-only instrumentation entry point exercises the exact foreground-player replacement path. */
internal fun MusicPlaybackService.recyclePlaybackPipelineForMemoryPressureImpl(): Boolean {
    val sample = readPlaybackMemorySample()
    recordPlaybackMemorySample(sample)
    memoryGuardHighSamples = 0
    memoryGuardLastRecoveryAttemptElapsedMs = SystemClock.elapsedRealtime()
    memoryGuardRecoveryAttemptCount++
    val replaced = replaceForegroundPlayerForMemoryPressureImpl("debug-instrumentation", sample)
    if (replaced) memoryGuardRecycleCount++
    return replaced
}

private fun MusicPlaybackService.replaceForegroundPlayerForMemoryPressureImpl(
    reason: String,
    memoryBefore: PlaybackMemorySample
): Boolean {
    if (!isForegroundPlayerInitialized()) return false
    val oldPlayer = foregroundPlayer
    val hasPendingStandbyResources = isStandbyPlayerInitialized() &&
        (standbyPlayer.mediaItemCount > 0 || standbyPlayer.playbackState != Player.STATE_IDLE)
    if (!oldPlayer.playWhenReady || !oldPlayer.isPlaying || oldPlayer.currentMediaItem == null ||
        oldPlayer.mediaItemCount == 0 || crossfading || pendingManualTransition != null ||
        activeManualTransition != null || preparedNextIndex != C.INDEX_UNSET ||
        crossfadeCancelRestoreStartVolume != null || sleepTimerFadeActive || hasPendingStandbyResources
    ) return false

    val mediaItemCount = oldPlayer.mediaItemCount
    val currentIndex = oldPlayer.currentMediaItemIndex
    if (currentIndex !in 0 until mediaItemCount) return false

    // Snapshot the complete player state before stop/release. MediaItem objects contain the local
    // URIs and metadata needed to rebuild the same queue without involving the network or catalog.
    val queue = (0 until mediaItemCount).map(oldPlayer::getMediaItemAt)
    val currentPositionMs = oldPlayer.currentPosition.coerceAtLeast(0L)
    val repeatMode = oldPlayer.repeatMode
    val shuffleModeEnabled = oldPlayer.shuffleModeEnabled
    val shuffleOrder: ShuffleOrder = oldPlayer.shuffleOrder
    val playbackParameters: PlaybackParameters = oldPlayer.playbackParameters
    val trackSelectionParameters: TrackSelectionParameters = oldPlayer.trackSelectionParameters
    val volume = oldPlayer.volume
    val resumePlayback = oldPlayer.playWhenReady
    val previousForegroundGeneration = foregroundPlayerGeneration

    Log.w(
        "TunelystPlayback",
        "Replacing foreground ExoPlayer ($reason): nativePss=${memoryBefore.nativePssBytes}, " +
            "nativeAllocated=${memoryBefore.nativeHeapAllocatedBytes}, totalPss=${memoryBefore.totalPssBytes}, " +
            "pressure=${memoryBefore.pressureBytes}, threshold=$memoryGuardThresholdBytes, " +
            "queue=$mediaItemCount index=$currentIndex positionMs=$currentPositionMs " +
            "generation=$previousForegroundGeneration"
    )

    var replacement: ExoPlayer? = null
    var foregroundSwapped = false
    var sessionSwapped = false
    var oldPlayerReleased = false
    try {
        // Stop first so renderers and loaded media are torn down before constructing the new player.
        // Keep the playlist on the old player until the MediaSession has atomically moved to the new one.
        oldPlayer.pause()
        oldPlayer.stop()

        replacement = createPlayer(handleAudioFocus = true)
        installListener(replacement)
        replacement.setMediaItems(queue, currentIndex, currentPositionMs)
        replacement.setShuffleOrder(shuffleOrder)
        replacement.repeatMode = repeatMode
        replacement.shuffleModeEnabled = shuffleModeEnabled
        replacement.setPlaybackParameters(playbackParameters)
        replacement.trackSelectionParameters = trackSelectionParameters
        replacement.volume = volume

        // Swap the service's foreground reference and MediaSession together. The same MediaSession
        // remains alive, so controllers keep their commands and the same-song queue update is not a
        // new listening session in the ViewModel.
        foregroundPlayer = replacement
        foregroundPlayerGeneration = previousForegroundGeneration + 1
        foregroundSwapped = true
        mediaSession.setPlayer(createSessionPlayer(replacement))
        sessionSwapped = true

        // Tear down the detached player before preparing a decoder in the replacement. This keeps
        // peak native usage lower and ensures old audio-fx sessions cannot outlive their player.
        oldPlayer.stop()
        oldPlayer.clearMediaItems()
        releaseDetachedPlayerImpl(oldPlayer, "memory recovery")
        oldPlayerReleased = true

        replacement.prepare()
        if (resumePlayback) replacement.play() else replacement.pause()
        unexpectedPauseStartedAtElapsedMs = 0L
        memoryGuardLastSampleElapsedMs = SystemClock.elapsedRealtime()

        val memoryAfter = readPlaybackMemorySample()
        recordPlaybackMemorySample(memoryAfter)
        memoryGuardRecoveryBaselinePressureBytes = memoryAfter.pressureBytes
        Log.i(
            "TunelystPlayback",
            "Foreground ExoPlayer replacement complete: nativePss=${memoryAfter.nativePssBytes}, " +
                "nativeAllocated=${memoryAfter.nativeHeapAllocatedBytes}, " +
                "totalPss=${memoryAfter.totalPssBytes}, pressure=${memoryAfter.pressureBytes}, " +
                "generation=$foregroundPlayerGeneration"
        )
        return true
    } catch (failure: Throwable) {
        Log.e("TunelystPlayback", "Foreground ExoPlayer replacement failed ($reason)", failure)
        val failedReplacement = replacement
        if (!oldPlayerReleased) {
            if (foregroundSwapped) {
                foregroundPlayer = oldPlayer
                foregroundPlayerGeneration = previousForegroundGeneration
            }
            if (sessionSwapped || foregroundSwapped) {
                runCatching { mediaSession.setPlayer(createSessionPlayer(oldPlayer)) }
                    .onFailure { Log.e("TunelystPlayback", "Could not restore the MediaSession to the previous player", it) }
            }
            failedReplacement?.let { releaseDetachedPlayerImpl(it, "failed memory recovery replacement") }
            runCatching {
                oldPlayer.setMediaItems(queue, currentIndex, currentPositionMs)
                oldPlayer.setShuffleOrder(shuffleOrder)
                oldPlayer.repeatMode = repeatMode
                oldPlayer.shuffleModeEnabled = shuffleModeEnabled
                oldPlayer.setPlaybackParameters(playbackParameters)
                oldPlayer.trackSelectionParameters = trackSelectionParameters
                oldPlayer.volume = volume
                oldPlayer.prepare()
                if (resumePlayback) oldPlayer.play() else oldPlayer.pause()
            }.onFailure { Log.e("TunelystPlayback", "Could not resume the previous player after replacement failure", it) }
        } else if (failedReplacement != null) {
            // A failure after releasing the old player is rare (prepare/play normally report async
            // errors through Player.Listener). Keep the new session usable if a synchronous error occurs.
            runCatching {
                failedReplacement.setMediaItems(queue, currentIndex, currentPositionMs)
                failedReplacement.setShuffleOrder(shuffleOrder)
                failedReplacement.repeatMode = repeatMode
                failedReplacement.shuffleModeEnabled = shuffleModeEnabled
                failedReplacement.setPlaybackParameters(playbackParameters)
                failedReplacement.trackSelectionParameters = trackSelectionParameters
                failedReplacement.volume = volume
                failedReplacement.prepare()
                if (resumePlayback) failedReplacement.play() else failedReplacement.pause()
            }.onFailure { Log.e("TunelystPlayback", "Could not restart the replacement player", it) }
        }
        return false
    }
}

private fun MusicPlaybackService.recordPlaybackMemorySample(sample: PlaybackMemorySample) {
    memoryGuardLastNativePssBytes = sample.nativePssBytes
    memoryGuardLastTotalPssBytes = sample.totalPssBytes
    memoryGuardLastNativeAllocatedBytes = sample.nativeHeapAllocatedBytes
    memoryGuardLastPressureBytes = sample.pressureBytes
}

private fun readPlaybackMemorySample(): PlaybackMemorySample {
    val memoryInfo = Debug.MemoryInfo()
    val pss = runCatching {
        Debug.getMemoryInfo(memoryInfo)
        PlaybackMemorySample(
            nativePssBytes = memoryInfo.nativePss.toLong().coerceAtLeast(0L) * 1_024L,
            totalPssBytes = memoryInfo.totalPss.toLong().coerceAtLeast(0L) * 1_024L,
            nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L)
        )
    }.getOrElse {
        PlaybackMemorySample(
            nativePssBytes = 0L,
            totalPssBytes = 0L,
            nativeHeapAllocatedBytes = runCatching { Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L) }
                .getOrDefault(0L)
        )
    }
    return pss
}

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.tunelyst.music.playback

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder

private data class PlaybackMemorySample(
    val nativePssBytes: Long,
    val totalPssBytes: Long,
    val nativeHeapAllocatedBytes: Long
) {
    /** Total process PSS sees managed, native, and graphics-attributed growth. */
    val pressureBytes: Long
        get() = PlaybackMemoryGuardPolicy.pressureBytes(
            totalPssBytes = totalPssBytes,
            nativePssBytes = nativePssBytes,
            nativeHeapAllocatedBytes = nativeHeapAllocatedBytes
        )
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
    if (replaceForegroundPlayerForMemoryPressureImpl("sustained-process-pss", sample)) {
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
    var foregroundDetached = false
    var oldListenerDetached = false
    var oldPlayerReleased = false

    fun restoreReleasedPlayer(target: ExoPlayer): Boolean = runCatching {
        installListener(target)
        target.setMediaItems(queue, currentIndex, currentPositionMs)
        target.setShuffleOrder(shuffleOrder)
        target.repeatMode = repeatMode
        target.shuffleModeEnabled = shuffleModeEnabled
        target.setPlaybackParameters(playbackParameters)
        target.trackSelectionParameters = trackSelectionParameters
        target.volume = volume
        foregroundPlayerRef = target
        foregroundPlayerGeneration = previousForegroundGeneration + 1
        mediaSession.setPlayer(createSessionPlayer(target))
        target.prepare()
        if (resumePlayback) target.play() else target.pause()
        unexpectedPauseStartedAtElapsedMs = 0L
        memoryGuardLastSampleElapsedMs = SystemClock.elapsedRealtime()
    }.onFailure {
        Log.e("TunelystPlayback", "Could not restore playback on a replacement player", it)
    }.isSuccess

    try {
        // An idle standby is optional. Release it before rebuilding the active slot so the recovery
        // path cannot keep a second allocator/effect set alive under memory pressure.
        if (isStandbyPlayerInitialized()) releaseStandbyPlayerImpl("memory recovery")

        // Tear down and release the foreground instance before creating its replacement. Keep only
        // the small immutable queue/state snapshot so peak player, decoder, buffer, and effect memory
        // does not overlap during this emergency recovery.
        oldPlayer.pause()
        oldPlayer.stop()
        oldPlayer.clearMediaItems()
        playerListeners.remove(oldPlayer)?.let { listener ->
            runCatching { oldPlayer.removeListener(listener) }
            oldListenerDetached = true
        }
        foregroundPlayerRef = null
        foregroundDetached = true
        releaseDetachedPlayerImpl(oldPlayer, "memory recovery")
        oldPlayerReleased = true

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
        foregroundPlayerRef = replacement
        foregroundPlayerGeneration = previousForegroundGeneration + 1
        mediaSession.setPlayer(createSessionPlayer(replacement))
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
            if (foregroundDetached) foregroundPlayerRef = oldPlayer
            if (oldListenerDetached) {
                runCatching { installListener(oldPlayer) }
                    .onFailure { Log.e("TunelystPlayback", "Could not restore the foreground player listener", it) }
            }
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
        } else {
            var recovered = failedReplacement != null && restoreReleasedPlayer(failedReplacement)
            if (!recovered && failedReplacement != null) {
                foregroundPlayerRef = null
                releaseDetachedPlayerImpl(failedReplacement, "failed memory recovery replacement")
            }
            if (!recovered) {
                val fallback = runCatching { createPlayer(handleAudioFocus = true) }
                    .onFailure { Log.e("TunelystPlayback", "Could not create a fallback player after memory recovery failed", it) }
                    .getOrNull()
                if (fallback != null) {
                    recovered = restoreReleasedPlayer(fallback)
                    if (!recovered) {
                        foregroundPlayerRef = null
                        releaseDetachedPlayerImpl(fallback, "failed fallback memory recovery replacement")
                    }
                }
            }
            if (recovered) {
                val memoryAfter = readPlaybackMemorySample()
                recordPlaybackMemorySample(memoryAfter)
                memoryGuardRecoveryBaselinePressureBytes = memoryAfter.pressureBytes
                Log.w(
                    "TunelystPlayback",
                    "Recovered playback using a replacement after a synchronous recovery error: " +
                        "generation=$foregroundPlayerGeneration pressure=${memoryAfter.pressureBytes}"
                )
                return true
            }
            foregroundPlayerRef = null
            foregroundPlayerGeneration = previousForegroundGeneration
            Log.e("TunelystPlayback", "Playback could not be restored after the foreground player was released")
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

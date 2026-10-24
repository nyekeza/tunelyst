@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.tunelyst.music.playback

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters

internal fun MusicPlaybackService.initializePlaybackMemoryGuardImpl() {
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    val totalMemoryBytes = runCatching {
        activityManager?.getMemoryInfo(memoryInfo)
        memoryInfo.totalMem
    }.getOrDefault(0L)
    memoryGuardThresholdBytes = PlaybackMemoryGuardPolicy.thresholdBytes(
        totalDeviceMemoryBytes = totalMemoryBytes,
        lowRamDevice = activityManager?.isLowRamDevice == true
    )
    memoryGuardBaselineNativePssBytes = nativeHeapPssBytes()
    memoryGuardLastSampleElapsedMs = SystemClock.elapsedRealtime()
}

/** Samples native-heap PSS only during stable playback; screen-off playback uses the same service ticker. */
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

    val nativePssBytes = nativeHeapPssBytes()
    memoryGuardLastNativePssBytes = nativePssBytes
    memoryGuardHighSamples = PlaybackMemoryGuardPolicy.nextHighSampleCount(
        current = memoryGuardHighSamples,
        nativePssBytes = nativePssBytes,
        thresholdBytes = memoryGuardThresholdBytes,
        baselineNativePssBytes = memoryGuardBaselineNativePssBytes
    )
    if (!PlaybackMemoryGuardPolicy.shouldRecycle(
            highSamples = memoryGuardHighSamples,
            nowElapsedMs = now,
            lastRecycleElapsedMs = memoryGuardLastRecycleElapsedMs
        )
    ) return

    if (recyclePlaybackPipelineImpl(reason = "sustained-native-pss", recordCurrentMemoryForDebug = false)) {
        memoryGuardHighSamples = 0
        memoryGuardLastRecycleElapsedMs = now
        memoryGuardRecycleCount++
    } else {
        memoryGuardHighSamples = 0
    }
}

internal fun MusicPlaybackService.recyclePlaybackPipelineForMemoryPressureImpl(): Boolean {
    val recycled = recyclePlaybackPipelineImpl(
        reason = "debug-instrumentation",
        recordCurrentMemoryForDebug = true
    )
    if (recycled) {
        memoryGuardHighSamples = 0
        memoryGuardLastRecycleElapsedMs = SystemClock.elapsedRealtime()
        memoryGuardRecycleCount++
    }
    return recycled
}

private fun MusicPlaybackService.recyclePlaybackPipelineImpl(
    reason: String,
    recordCurrentMemoryForDebug: Boolean
): Boolean {
    if (!isForegroundPlayerInitialized()) return false
    val player = foregroundPlayer
    val hasPendingStandbyResources = isStandbyPlayerInitialized() &&
        (standbyPlayer.mediaItemCount > 0 || standbyPlayer.playbackState != Player.STATE_IDLE)
    if (!player.playWhenReady || !player.isPlaying || player.currentMediaItem == null ||
        player.mediaItemCount == 0 || crossfading || pendingManualTransition != null ||
        activeManualTransition != null || preparedNextIndex != C.INDEX_UNSET ||
        crossfadeCancelRestoreStartVolume != null || sleepTimerFadeActive || hasPendingStandbyResources
    ) return false

    val mediaItemCount = player.mediaItemCount
    val currentItem = player.currentMediaItem ?: return false
    val startIndex = player.currentMediaItemIndex
    if (startIndex !in 0 until mediaItemCount) return false

    val startPositionMs = player.currentPosition.coerceAtLeast(0L)
    val currentMediaId = currentItem.mediaId
    val repeatMode = player.repeatMode
    val shuffleModeEnabled = player.shuffleModeEnabled
    val playbackParameters = player.playbackParameters
    val trackSelectionParameters: TrackSelectionParameters = player.trackSelectionParameters
    val volume = player.volume
    val resumePlayback = player.playWhenReady
    val nativePssBytes = nativeHeapPssBytes()
    if (recordCurrentMemoryForDebug) memoryGuardLastNativePssBytes = nativePssBytes

    Log.w(
        "TunelystPlayback",
        "Recycling playback pipeline ($reason) at native-heap PSS $nativePssBytes bytes " +
            "(guard threshold $memoryGuardThresholdBytes); preserving ${mediaItemCount} queue items"
    )

    val reset = runCatching {
        // stop() releases loaded media/decoder resources but keeps the playlist, current item,
        // repeat/shuffle modes, and playback position in place. The explicit seek below restores
        // the captured position without publishing an empty playlist to the MediaSession.
        player.pause()
        player.stop()
        player.trackSelectionParameters = trackSelectionParameters
        player.repeatMode = repeatMode
        player.shuffleModeEnabled = shuffleModeEnabled
        player.setPlaybackParameters(playbackParameters)
        player.volume = volume
        player.seekTo(startIndex, startPositionMs)
        player.prepare()
        if (resumePlayback) player.play() else player.pause()
    }
    if (reset.isFailure) {
        Log.e("TunelystPlayback", "Playback pipeline recycle failed; attempting to resume the current item", reset.exceptionOrNull())
        runCatching {
            if (player.currentMediaItem?.mediaId == currentMediaId) {
                player.seekTo(startIndex, startPositionMs)
                player.prepare()
                if (resumePlayback) player.play() else player.pause()
            }
        }.onFailure { Log.e("TunelystPlayback", "Playback resume after a failed recycle also failed", it) }
        return false
    }

    return true
}

private fun nativeHeapPssBytes(): Long {
    val memoryInfo = Debug.MemoryInfo()
    return runCatching {
        Debug.getMemoryInfo(memoryInfo)
        memoryInfo.nativePss.toLong().coerceAtLeast(0L) * 1_024L
    }.getOrElse {
        Debug.getNativeHeapAllocatedSize()
    }
}

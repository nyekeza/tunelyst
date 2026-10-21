package com.tunelyst.music.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Project a player position while it is actively advancing; paused/buffering playback stays anchored. */
internal fun projectPlaybackProgress(
    positionMs: Long,
    elapsedMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    shouldAdvance: Boolean
): Float {
    if (durationMs <= 0L) return 0f
    val start = positionMs.coerceIn(0L, durationMs)
    val speed = playbackSpeed.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 0.0
    val elapsed = if (shouldAdvance) elapsedMs.coerceAtLeast(0L).toDouble() else 0.0
    val projectedMs = (start.toDouble() + elapsed * speed).coerceIn(0.0, durationMs.toDouble())
    return (projectedMs / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
}

/** A lightweight, local projection between Media3 position callbacks for the Home progress ring. */
@Composable
internal fun rememberHomePlaybackProgress(
    trackId: Long?,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isBuffering: Boolean,
    playbackSpeed: Float
): Float {
    val progressState = remember(trackId) {
        mutableFloatStateOf(projectPlaybackProgress(positionMs, 0L, durationMs, playbackSpeed, shouldAdvance = false))
    }
    LaunchedEffect(trackId, positionMs, durationMs, isPlaying, isBuffering, playbackSpeed) {
        val canAdvance = isPlaying && !isBuffering && durationMs > 0L && playbackSpeed.isFinite() && playbackSpeed > 0f
        progressState.floatValue = projectPlaybackProgress(
            positionMs = positionMs,
            elapsedMs = 0L,
            durationMs = durationMs,
            playbackSpeed = playbackSpeed,
            shouldAdvance = false
        )
        if (!canAdvance) return@LaunchedEffect

        val anchoredAtMs = SystemClock.elapsedRealtime()
        while (isActive) {
            val elapsedMs = (SystemClock.elapsedRealtime() - anchoredAtMs).coerceAtLeast(0L)
            progressState.floatValue = projectPlaybackProgress(
                positionMs = positionMs,
                elapsedMs = elapsedMs,
                durationMs = durationMs,
                playbackSpeed = playbackSpeed,
                shouldAdvance = true
            )
            delay(PROGRESS_PROJECTION_INTERVAL_MS)
        }
    }
    return progressState.floatValue
}

private const val PROGRESS_PROJECTION_INTERVAL_MS = 50L

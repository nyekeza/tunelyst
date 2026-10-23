@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.tunelyst.music.playback

import android.os.SystemClock
import androidx.media3.common.C

internal fun MusicPlaybackService.refreshSleepTimerScheduleImpl() {
    val schedule = audioSettingsStore.sleepTimerSchedule()
    sleepTimerEndElapsedMs = schedule?.endElapsedMs ?: 0L
    sleepTimerFadeOutMs = (schedule?.fadeOutSeconds ?: 0).coerceAtLeast(0) * 1_000L
    val fadeStart = if (sleepTimerFadeOutMs > 0L && sleepTimerEndElapsedMs > 0L) {
        (sleepTimerEndElapsedMs - sleepTimerFadeOutMs).coerceAtLeast(1L)
    } else 0L
    sleepFadeWindowStartElapsedMs = fadeStart
    sleepFadeWindowEndElapsedMs = if (sleepFadeWindowStartElapsedMs > 0L) sleepTimerEndElapsedMs else 0L
    if (schedule == null) {
        // Remove legacy fade-only state left by versions that did not persist a service-owned deadline.
        audioSettingsStore.clearSleepTimerFadeWindow()
    }
    updateSleepTimerFadeState()
}

internal fun MusicPlaybackService.updateSleepTimerFadeStateImpl() {
    val now = SystemClock.elapsedRealtime()
    val active = sleepFadeWindowStartElapsedMs > 0L &&
        sleepFadeWindowEndElapsedMs > sleepFadeWindowStartElapsedMs &&
        now >= sleepFadeWindowStartElapsedMs && now < sleepFadeWindowEndElapsedMs
    if (active == sleepTimerFadeActive) return
    val wasActive = sleepTimerFadeActive
    sleepTimerFadeActive = active
    if (active && isForegroundPlayerInitialized()) {
        val manualRequest = pendingManualTransition ?: activeManualTransition
        if (manualRequest != null) {
            cancelCrossfade(restoreVolume = false)
            // Preserve the requested skip while leaving the sleep timer in control of volume.
            applyManualTransitionImmediately(manualRequest, restoreVolume = false)
        } else if (crossfading || preparedNextIndex != C.INDEX_UNSET || crossfadeCancelRestoreStartVolume != null) {
            // Sleep-timer gain is the active owner of the foreground volume; never restore it to 1 here.
            cancelCrossfade(restoreVolume = false)
        }
    } else if (!active && wasActive && sleepTimerVolumeApplied && isForegroundPlayerInitialized()) {
        foregroundPlayer.volume = 1f
        sleepTimerVolumeApplied = false
    }
}

internal fun MusicPlaybackService.updateSleepTimerImpl() {
    if (sleepTimerEndElapsedMs <= 0L || !isForegroundPlayerInitialized()) return
    val remainingMs = sleepTimerEndElapsedMs - SystemClock.elapsedRealtime()
    if (sleepTimerFadeOutMs > 0L && remainingMs in 1L..sleepTimerFadeOutMs) {
        updateSleepTimerFadeState()
        foregroundPlayer.volume = (remainingMs.toFloat() / sleepTimerFadeOutMs.toFloat()).coerceIn(0f, 1f)
        sleepTimerVolumeApplied = true
    }
    if (remainingMs > 0L) return

    if (crossfading || preparedNextIndex != C.INDEX_UNSET || pendingManualTransition != null) {
        cancelCrossfade(restoreVolume = false)
    }
    if (isStandbyPlayerInitialized()) {
        standbyPlayer.pause()
        standbyPlayer.volume = 1f
    }
    foregroundPlayer.pause()
    foregroundPlayer.volume = 1f
    sleepTimerVolumeApplied = false
    sleepTimerEndElapsedMs = 0L
    sleepTimerFadeOutMs = 0L
    sleepFadeWindowStartElapsedMs = 0L
    sleepFadeWindowEndElapsedMs = 0L
    updateSleepTimerFadeState()
    audioSettingsStore.clearSleepTimer()
}

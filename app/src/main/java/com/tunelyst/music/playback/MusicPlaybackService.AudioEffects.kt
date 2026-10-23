@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.tunelyst.music.playback

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

internal fun MusicPlaybackService.refreshDeviceEqualizerProfileImpl() {
    if (!::audioSettingsStore.isInitialized || !::foregroundPlayer.isInitialized) return
    audioSettings = readAudioSettings()
    applyEqualizerSettings()
}

internal fun MusicPlaybackService.attachEqualizerImpl(target: ExoPlayer, audioSessionId: Int) {
    if (audioSessionId <= 0) {
        releaseEqualizer(target)
        return
    }
    audioSessionIds[target] = audioSessionId

    val existing = equalizers[target]
    val existingHasControl = existing?.let { runCatching { it.hasControl() }.getOrDefault(false) } == true
    if (existingHasControl && equalizerSessionIds[target] == audioSessionId) {
        applyEqualizer(existing!!)
        return
    }
    releaseEqualizer(target)

    val effect = runCatching { Equalizer(10, audioSessionId) }
        .onFailure { Log.w("TunelystPlayback", "Device equalizer could not attach to audio session $audioSessionId", it) }
        .getOrNull() ?: return
    equalizers[target] = effect
    equalizerSessionIds[target] = audioSessionId
    if (!runCatching { effect.hasControl() }.getOrDefault(false)) {
        Log.w("TunelystPlayback", "Device equalizer has no control for audio session $audioSessionId; it will be retried when playback becomes ready")
        return
    }
    applyEqualizer(effect)
}

internal fun MusicPlaybackService.releaseEqualizerImpl(target: ExoPlayer) {
    equalizers.remove(target)?.let { runCatching { it.release() } }
    equalizerSessionIds.remove(target)
}

internal fun MusicPlaybackService.ensureEqualizerAttachedImpl(target: ExoPlayer) {
    if (!audioSettings.equalizerEnabled) return
    val sessionId = audioSessionIds[target] ?: return
    val effect = equalizers[target]
    val effectHasControl = effect?.let { runCatching { it.hasControl() }.getOrDefault(false) } == true
    if (!effectHasControl || equalizerSessionIds[target] != sessionId) {
        attachEqualizer(target, sessionId)
    } else {
        applyEqualizer(effect!!)
    }
}

internal fun MusicPlaybackService.attachLoudnessEnhancerImpl(target: ExoPlayer, audioSessionId: Int) {
    if (audioSessionId <= 0) return
    loudnessEnhancers.remove(target)?.let { runCatching { it.release() } }
    val effect = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull() ?: return
    loudnessEnhancers[target] = effect
    appliedReplayGainByPlayer.remove(target)
    applyReplayGain(target, target.currentMediaItem)
}

internal fun MusicPlaybackService.applyEqualizerSettingsImpl() {
    if (!audioSettings.equalizerEnabled) {
        equalizers.values.forEach { effect ->
            runCatching { effect.enabled = false }
                .onFailure { Log.w("TunelystPlayback", "Failed to disable device equalizer", it) }
        }
        return
    }
    if (::foregroundPlayer.isInitialized) ensureEqualizerAttached(foregroundPlayer)
    if (::standbyPlayer.isInitialized) ensureEqualizerAttached(standbyPlayer)
}

internal fun MusicPlaybackService.applyPlaybackParametersImpl() {
    if (!::foregroundPlayer.isInitialized) return
    val parameters = PlaybackParameters(audioSettings.playbackSpeed, audioSettings.playbackPitch)
    foregroundPlayer.setPlaybackParameters(parameters)
    if (::standbyPlayer.isInitialized) standbyPlayer.setPlaybackParameters(parameters)
}

internal fun MusicPlaybackService.applyLoudnessSettingsImpl() {
    if (!::foregroundPlayer.isInitialized) return
    applyReplayGain(foregroundPlayer, foregroundPlayer.currentMediaItem)
    if (::standbyPlayer.isInitialized) applyReplayGain(standbyPlayer, standbyPlayer.currentMediaItem)
}

internal fun MusicPlaybackService.applyReplayGainImpl(target: ExoPlayer, item: MediaItem?) {
    val effect = loudnessEnhancers[target] ?: return
    val metadataExtras = item?.mediaMetadata?.extras
    val metadataGain = metadataExtras
        ?.takeIf { it.containsKey("replay_gain_db") }
        ?.getFloat("replay_gain_db")
        ?.takeIf { it.isFinite() }
    val requestedGain = metadataGain ?: ReplayGainRuntimeCache.get(item?.mediaId)
    val gainMilliBel = ((requestedGain ?: 0f).coerceIn(-12f, 6f) * 100f)
        .roundToInt()
        .coerceIn(-1_200, 600)
    val enabled = audioSettings.normalizeVolume && requestedGain != null
    val applied = AppliedReplayGain(item?.mediaId, gainMilliBel, enabled)
    if (appliedReplayGainByPlayer[target] == applied) return
    runCatching {
        effect.setTargetGain(gainMilliBel)
        effect.enabled = enabled
        appliedReplayGainByPlayer[target] = applied
    }
}

internal fun MusicPlaybackService.applyEqualizerImpl(effect: Equalizer) {
    runCatching {
        if (!effect.hasControl()) return@runCatching
        val count = effect.numberOfBands.toInt()
        if (count <= 0) {
            Log.w("TunelystPlayback", "Device equalizer reports no bands")
            return@runCatching
        }
        val targetFrequenciesHz = listOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)
        val levelsByBand = mutableMapOf<Int, MutableList<Int>>()
        targetFrequenciesHz.forEachIndexed { index, targetHz ->
            if (index >= audioSettings.bandLevelsDb.size) return@forEachIndexed
            val nearestBand = (0 until count).minByOrNull { bandIndex ->
                val centerHz = effect.getCenterFreq(bandIndex.toShort()).toFloat() / 1_000f
                if (centerHz <= 0f) Float.MAX_VALUE else abs(ln(centerHz / targetHz))
            } ?: return@forEachIndexed
            levelsByBand.getOrPut(nearestBand) { mutableListOf() }.add(audioSettings.bandLevelsDb[index])
        }
        val range = effect.bandLevelRange
        if (range.size < 2 || range[0] >= range[1]) {
            Log.w("TunelystPlayback", "Device equalizer reports an invalid band level range")
            return@runCatching
        }
        levelsByBand.forEach { (bandIndex, requestedLevels) ->
            val averageDb = requestedLevels.average()
            val level = (averageDb * 100f).roundToInt()
                .coerceIn(range[0].toInt(), range[1].toInt())
                .toShort()
            runCatching { effect.setBandLevel(bandIndex.toShort(), level) }
                .onFailure { Log.w("TunelystPlayback", "Failed to set equalizer band $bandIndex", it) }
        }
        effect.enabled = audioSettings.equalizerEnabled
    }.onFailure { Log.w("TunelystPlayback", "Failed to apply equalizer settings", it) }
}

internal fun MusicPlaybackService.readAudioSettingsImpl(): SettingsSnapshot {
    val stored = audioSettingsStore.read()
    return SettingsSnapshot(
        equalizerEnabled = stored.equalizerEnabled,
        bandLevelsDb = stored.bandLevelsDb,
        crossfadeSeconds = stored.crossfadeSeconds,
        crossfadeCurve = stored.crossfadeCurve,
        crossfadeAlbumBoundariesOnly = stored.crossfadeAlbumBoundariesOnly,
        manualSkipFadeSeconds = stored.manualSkipFadeSeconds,
        playbackSpeed = stored.playbackSpeed,
        playbackPitch = stored.playbackPitch,
        normalizeVolume = stored.normalizeVolume
    )
}

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.tunelyst.music.ui

import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import com.tunelyst.music.data.AppThemeMode
import com.tunelyst.music.data.CrossfadeCurve
import com.tunelyst.music.data.RepeatAvoidanceLevel
import com.tunelyst.music.data.Song
import com.tunelyst.music.playback.ReplayGainRuntimeCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun MusicViewModel.setEqualizerEnabledImpl(enabled: Boolean) {
    _audioSettings.value = audioSettingsStore.setEqualizerEnabled(enabled)
}

internal fun MusicViewModel.selectEqualizerPresetImpl(preset: String) {
    _audioSettings.value = audioSettingsStore.selectPreset(preset)
}

internal fun MusicViewModel.setEqualizerBandLevelImpl(index: Int, levelDb: Int) {
    _audioSettings.value = audioSettingsStore.setBandLevel(index, levelDb)
}

internal fun MusicViewModel.setCrossfadeSecondsImpl(seconds: Int) {
    _audioSettings.value = audioSettingsStore.setCrossfadeSeconds(seconds)
}

internal fun MusicViewModel.setCrossfadeCurveImpl(curve: CrossfadeCurve) {
    _audioSettings.value = audioSettingsStore.setCrossfadeCurve(curve)
}

internal fun MusicViewModel.setCrossfadeAlbumBoundariesOnlyImpl(enabled: Boolean) {
    _audioSettings.value = audioSettingsStore.setCrossfadeAlbumBoundariesOnly(enabled)
}

internal fun MusicViewModel.setManualSkipFadeSecondsImpl(seconds: Int) {
    _audioSettings.value = audioSettingsStore.setManualSkipFadeSeconds(seconds)
}

internal fun MusicViewModel.setThemeModeImpl(mode: AppThemeMode) {
    _appSettings.value = appSettingsStore.setThemeMode(mode)
}

internal fun MusicViewModel.setDynamicBlurEnabledImpl(enabled: Boolean) {
    _appSettings.value = appSettingsStore.setDynamicBlurEnabled(enabled)
}

internal fun MusicViewModel.setHighContrastEnabledImpl(enabled: Boolean) { _appSettings.value = appSettingsStore.setHighContrastEnabled(enabled) }

internal fun MusicViewModel.setReducedMotionEnabledImpl(enabled: Boolean) { _appSettings.value = appSettingsStore.setReducedMotionEnabled(enabled) }

internal fun MusicViewModel.setLargerTouchTargetsEnabledImpl(enabled: Boolean) { _appSettings.value = appSettingsStore.setLargerTouchTargetsEnabled(enabled) }

internal fun MusicViewModel.setListenThresholdSecondsImpl(seconds: Int) { _appSettings.value = appSettingsStore.setListenThresholdSeconds(seconds) }

internal fun MusicViewModel.setRepeatAvoidanceLevelImpl(level: RepeatAvoidanceLevel) {
    _appSettings.value = appSettingsStore.setRepeatAvoidanceLevel(level)
}

internal fun MusicViewModel.setFewerRepeatsEnabledImpl(enabled: Boolean) {
    _appSettings.value = appSettingsStore.setFewerRepeatsEnabled(enabled)
}

internal fun MusicViewModel.setHomeSectionHiddenImpl(id: String, hidden: Boolean) { _appSettings.value = appSettingsStore.setHomeSectionHidden(id, hidden) }

internal fun MusicViewModel.moveHomeSectionImpl(id: String, direction: Int) { _appSettings.value = appSettingsStore.moveHomeSection(id, direction) }

internal fun MusicViewModel.restoreHomeSectionsImpl() { _appSettings.value = appSettingsStore.restoreHomeSections() }

internal fun MusicViewModel.setPlaybackSpeedImpl(speed: Float) {
    _audioSettings.value = audioSettingsStore.setPlaybackSpeed(speed)
    player?.setPlaybackParameters(PlaybackParameters(speed, _audioSettings.value.playbackPitch))
}

internal fun MusicViewModel.setPlaybackPitchImpl(pitch: Float) {
    _audioSettings.value = audioSettingsStore.setPlaybackPitch(pitch)
    player?.setPlaybackParameters(PlaybackParameters(_audioSettings.value.playbackSpeed, pitch))
}

internal fun MusicViewModel.setNormalizeVolumeImpl(enabled: Boolean) {
    _audioSettings.value = audioSettingsStore.setNormalizeVolume(enabled)
    if (enabled) scheduleEmbeddedReplayGainForCurrentQueue()
}

internal fun MusicViewModel.scheduleEmbeddedReplayGainForCurrentQueueImpl() {
    if (!_audioSettings.value.normalizeVolume) return
    val activePlayer = player ?: return
    val currentIndex = activePlayer.currentMediaItemIndex
    val startIndex = currentIndex.coerceAtLeast(0)
    if (startIndex !in 0 until activePlayer.mediaItemCount) return
    val endIndex = minOf(activePlayer.mediaItemCount, startIndex + MAX_REPLAY_GAIN_LOOKAHEAD)
    val mediaIds = List(endIndex - startIndex) { offset -> activePlayer.getMediaItemAt(startIndex + offset).mediaId }
    val songLookup = cachedSongLookup + sharedSongs
    val queueSongs = mediaIds.mapNotNull { id -> id.toLongOrNull()?.let(songLookup::get) }
    if (queueSongs.size != mediaIds.size) return
    if (queueSongs.none { it.replayGainDb == null && !repository.hasResolvedEmbeddedReplayGain(it.uri) }) return
    replayGainEnrichmentJob?.cancel()
    replayGainEnrichmentJob = viewModelScope.launch {
        val enriched = withContext(Dispatchers.IO) {
            queueSongs.map { song ->
                if (song.replayGainDb != null) song
                else repository.embeddedReplayGainDb(song.uri)?.let { gain -> song.copy(replayGainDb = gain) } ?: song
            }
        }
        withContext(Dispatchers.Main) {
            val currentPlayer = player ?: return@withContext
            if (currentPlayer !== activePlayer || currentPlayer.mediaItemCount < endIndex) return@withContext
            val currentIds = List(mediaIds.size) { offset -> currentPlayer.getMediaItemAt(startIndex + offset).mediaId }
            if (currentIds != mediaIds || enriched == queueSongs) return@withContext
            val currentIndex = currentPlayer.currentMediaItemIndex
            if (currentIndex >= endIndex) return@withContext
            enriched.forEach { song ->
                song.replayGainDb?.let { gain -> ReplayGainRuntimeCache.put(song.id.toString(), gain) }
            }

            // Enrich the currently playing Song in Tunelyst's catalog and hand its gain to the
            // playback service, but never replace its active MediaItem or seek, which would
            // reset and rebuffer the audio pipeline. Upcoming items receive metadata before transition.
            val firstUpcomingIndex = if (currentIndex in startIndex until endIndex) {
                currentIndex + 1
            } else {
                startIndex
            }
            if (firstUpcomingIndex < endIndex) {
                val firstUpcomingOffset = firstUpcomingIndex - startIndex
                val upcomingSongs = enriched.drop(firstUpcomingOffset)
                if (upcomingSongs != queueSongs.drop(firstUpcomingOffset)) {
                    currentPlayer.replaceMediaItems(
                        firstUpcomingIndex,
                        endIndex,
                        upcomingSongs.map(::toMediaItem)
                    )
                }
            }

            val enrichedById = enriched.associateBy(Song::id)
            cachedSongLookup = cachedSongLookup + enrichedById
            enriched.filter { it.id < 0L }.forEach { sharedSongs[it.id] = it }
            _uiState.update { state -> state.copy(songs = state.songs.map { enrichedById[it.id] ?: it }) }
            syncPlayback(currentPlayer)
        }
    }
}

@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.tunelyst.music.ui

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.tunelyst.music.data.Song
import kotlinx.coroutines.flow.update

internal fun MusicViewModel.requestOpenNowPlayingImpl() {
    _openNowPlayingRequest.update { it + 1L }
}

internal fun MusicViewModel.attachPlayerControllerRetryActionImpl(action: (() -> Unit)?) {
    playerConnectionRetryAction = action
}

internal fun MusicViewModel.reportPlayerConnectionFailureImpl() {
    _playerConnectionError.value = "Couldn't connect to playback controls. Audio playback may be unavailable."
}

internal fun MusicViewModel.clearPlayerConnectionErrorImpl() {
    _playerConnectionError.value = null
}

internal fun MusicViewModel.retryPlayerConnectionImpl() {
    _playerConnectionError.value = null
    playerConnectionRetryAction?.invoke()
        ?: run { reportPlayerConnectionFailure() }
}

internal fun MusicViewModel.attachPlayerImpl(newPlayer: Player) {
    _playerConnectionError.value = null
    if (player === newPlayer) return
    player?.let { previous ->
        sampleListeningSession(previous, force = true)
        flushListeningProgress()
        previous.removeListener(playerListener)
    }
    player = newPlayer
    newPlayer.addListener(playerListener)
    val audio = _audioSettings.value
    newPlayer.setPlaybackParameters(PlaybackParameters(audio.playbackSpeed, audio.playbackPitch))
    syncPlayback(newPlayer)
    if (newPlayer.isPlaying) beginListeningSession(newPlayer)
    tryRestorePlaybackSession()
    tryPlayPendingSharedUris()
}

internal fun MusicViewModel.detachPlayerImpl() {
    player?.let { activePlayer ->
        sampleListeningSession(activePlayer, force = true)
        flushListeningProgress()
        persistPlaybackSession(activePlayer, force = true)
        activePlayer.removeListener(playerListener)
    }
    // Keep the active listening session across Activity/controller recreation so it is not
    // counted as a new start when the same track is still playing in the service.
    player = null
    syncPlayback(null)
}

internal fun MusicViewModel.beginListeningSessionImpl(activePlayer: Player) {
    if (!activePlayer.isPlaying) return
    val songId = activePlayer.currentMediaItem?.mediaId?.toLongOrNull()?.takeIf { it >= 0L } ?: return
    val position = activePlayer.currentPosition.coerceAtLeast(0L)
    val duration = listeningDuration(activePlayer, songId)
    val current = listeningSession
    if (current?.songId == songId) {
        current.durationMs = duration.takeIf { it > 0L } ?: current.durationMs
        current.lastPositionMs = position
        lastListeningSampleElapsedMs = SystemClock.elapsedRealtime()
        return
    }
    if (current != null) finishListeningSession(completed = false, skipped = false)
    listeningSession = ActiveListeningSession(songId, duration, position)
    lastListeningSampleElapsedMs = SystemClock.elapsedRealtime()
    store.recordListeningStart(songId)
    _uiState.update { it.copy(recentlyPlayedIds = store.recentlyPlayed()) }
    refreshListeningStats(songId)
}

internal fun MusicViewModel.sampleListeningSessionImpl(
    activePlayer: Player,
    force: Boolean = false,
    allowPausedFinal: Boolean = false
) {
    if (!activePlayer.isPlaying && !allowPausedFinal) {
        listeningSession?.let { session ->
            val id = activePlayer.currentMediaItem?.mediaId?.toLongOrNull()
            if (id == session.songId) session.lastPositionMs = activePlayer.currentPosition.coerceAtLeast(0L)
        }
        lastListeningSampleElapsedMs = SystemClock.elapsedRealtime()
        return
    }
    val songId = activePlayer.currentMediaItem?.mediaId?.toLongOrNull()?.takeIf { it >= 0L } ?: return
    if (listeningSession?.songId != songId) beginListeningSession(activePlayer)
    val session = listeningSession?.takeIf { it.songId == songId } ?: return
    val now = SystemClock.elapsedRealtime()
    val elapsed = (now - lastListeningSampleElapsedMs).coerceAtLeast(0L)
    if (!force && elapsed < LISTENING_SAMPLE_INTERVAL_MS) return
    val position = activePlayer.currentPosition.coerceAtLeast(0L)
    val duration = listeningDuration(activePlayer, songId)
    if (duration > 0L) session.durationMs = duration
    val mediaDelta = position - session.lastPositionMs
    val expectedMax = (elapsed * activePlayer.playbackParameters.speed.coerceIn(0.5f, 2f)).toLong() + LISTENING_SAMPLE_TOLERANCE_MS
    if (mediaDelta > 0L && mediaDelta <= expectedMax) {
        session.listenedMs += mediaDelta
        session.pendingPersistedMs += mediaDelta
    }
    session.lastPositionMs = position
    lastListeningSampleElapsedMs = now
    maybeRecordQualifiedPlay(session)
    if (session.pendingPersistedMs >= LISTENING_PROGRESS_FLUSH_MS) flushListeningProgress()
}

internal fun MusicViewModel.listeningDurationImpl(activePlayer: Player, songId: Long): Long {
    val duration = activePlayer.duration
    return if (duration > 0L && duration != C.TIME_UNSET) duration
    else cachedSongLookup[songId]?.durationMs?.coerceAtLeast(0L) ?: 0L
}

internal fun MusicViewModel.playCountThresholdMsImpl(session: ActiveListeningSession): Long {
    val configuredMs = _appSettings.value.listenThresholdSeconds.coerceIn(5, 120) * 1_000L
    val duration = session.durationMs
    // Short tracks use an 80% threshold so a complete short recording can still count.
    val shortTrackThreshold = if (duration > 0L && duration < configuredMs) (duration * 0.8f).toLong().coerceAtLeast(1L) else configuredMs
    return shortTrackThreshold
}

internal fun MusicViewModel.maybeRecordQualifiedPlayImpl(session: ActiveListeningSession) {
    if (session.qualifiedPlayRecorded || session.listenedMs < playCountThresholdMs(session)) return
    session.qualifiedPlayRecorded = true
    val updatedCounts = store.recordQualifiedPlay(session.songId)
    _uiState.update { it.copy(playCounts = updatedCounts) }
    refreshListeningStats(session.songId)
}

internal fun MusicViewModel.flushListeningProgressImpl() {
    val session = listeningSession ?: return
    val pending = session.pendingPersistedMs
    if (pending <= 0L) return
    val percent = if (session.durationMs > 0L) {
        (session.listenedMs.toDouble() * 100.0 / session.durationMs.toDouble()).toFloat().coerceIn(0f, 100f)
    } else 0f
    store.recordListeningProgress(session.songId, pending, percent)
    session.pendingPersistedMs = 0L
    refreshListeningStats(session.songId)
}

internal fun MusicViewModel.refreshListeningStatsImpl(songId: Long) {
    if (songId < 0L) return
    val latest = store.listeningStatsFor(songId)
    _uiState.update { current ->
        current.copy(listeningStats = current.listeningStats + (songId to latest))
    }
}

internal fun MusicViewModel.finishListeningSessionImpl(completed: Boolean, skipped: Boolean) {
    val session = listeningSession ?: return
    maybeRecordQualifiedPlay(session)
    flushListeningProgress()
    if (completed) store.recordListeningCompletion(session.songId)
    if (skipped) store.recordListeningSkip(session.songId)
    if (completed || skipped) refreshListeningStats(session.songId)
    if (completed || session.qualifiedPlayRecorded) {
        val song = cachedSongLookup[session.songId]
            ?: sharedSongs[session.songId]
            ?: _uiState.value.songs.firstOrNull { it.id == session.songId }
        song?.let(::enqueueAudioFeatureAnalysis)
    }
    listeningSession = null
    lastListeningSampleElapsedMs = 0L
}

internal fun MusicViewModel.enqueueAudioFeatureAnalysisImpl(song: Song) {
    if (lowMemoryDevice || song.id < 0L || audioFeatureStore.featuresFor(song) != null) return
    if (!queuedAudioFeatureIds.add(song.id)) return
    if (audioFeatureRequests.trySend(song).isFailure) queuedAudioFeatureIds.remove(song.id)
}

internal fun MusicViewModel.enqueueShuffleFeatureWarmupImpl(seeds: List<Song>, candidates: List<Song>) {
    (seeds.take(5) + candidates.shuffled().take(5))
        .distinctBy(Song::id)
        .take(MAX_SHUFFLE_FEATURE_WARMUP)
        .forEach(::enqueueAudioFeatureAnalysis)
}

internal fun MusicViewModel.finishCurrentSessionAsSkipImpl(activePlayer: Player? = player) {
    activePlayer?.let { sampleListeningSession(it, force = true, allowPausedFinal = true) }
    finishListeningSession(completed = false, skipped = true)
}

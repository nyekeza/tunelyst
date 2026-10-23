@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.tunelyst.music.ui

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.tunelyst.music.data.RepeatAvoidanceLevel
import com.tunelyst.music.data.Song
import com.tunelyst.music.playback.CrossfadeDisplayState
import com.tunelyst.music.core.logic.queueNextInsertionIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun MusicViewModel.playQueueItemImpl(index: Int) {
    val queue = _playbackState.value.queue
    if (index in queue.indices) {
        playFrom(
            queue,
            index,
            autoShuffleAtEnd = autoShuffleOnCompletion && !smartShuffleActive,
            preserveOrder = preserveCurrentQueueOrder,
            smartShuffle = smartShuffleActive,
            promptForSmartShuffleOnCompletion = promptForSmartShuffleOnCompletion
        )
    }
}

internal fun MusicViewModel.acceptSharedAudioUrisImpl(uris: List<Uri>) {
    pendingSharedUris = uris.distinct()
    tryPlayPendingSharedUris()
}

internal fun MusicViewModel.tryPlayPendingSharedUrisImpl() {
    val uris = pendingSharedUris
    if (uris.isEmpty() || player == null || _uiState.value.isLoading || resolvingSharedUris) return

    val librarySongs = uris.mapNotNull { uri -> _uiState.value.songs.firstOrNull { it.uri == uri } }
    if (librarySongs.size == uris.size) {
        pendingSharedUris = emptyList()
        playFrom(librarySongs, 0, autoShuffleAtEnd = true, preserveOrder = true)
        return
    }

    resolvingSharedUris = true
    viewModelScope.launch(Dispatchers.IO) {
        val resolvedSongs = uris.mapNotNull { uri -> runCatching { repository.songFromUri(uri) }.getOrNull() }
        withContext(Dispatchers.Main) {
            resolvingSharedUris = false
            if (pendingSharedUris != uris) {
                tryPlayPendingSharedUris()
                return@withContext
            }
            val sharedQueue = resolvedSongs.map { resolvedSong ->
                val sharedId = Long.MIN_VALUE + (resolvedSong.uri.toString().hashCode().toLong() and 0x7fffffffL)
                resolvedSong.copy(id = sharedId).also { sharedSongs[sharedId] = it }
            }
            pendingSharedUris = emptyList()
            cachedSongLookup = cachedSongLookup + sharedSongs.values.associateBy(Song::id)
            if (sharedQueue.isNotEmpty()) playFrom(sharedQueue, 0, autoShuffleAtEnd = true, preserveOrder = true)
        }
    }
}

internal fun MusicViewModel.stopShuffleContinuationImpl(activePlayer: Player?) {
    autoShuffleOnCompletion = false
    smartShuffleActive = false
    autoShuffleJob?.cancel()
    autoShuffleJob = null
    if (activePlayer != null) {
        activePlayer.shuffleModeEnabled = false
        syncPlayback(activePlayer)
        persistPlaybackSession(activePlayer, force = true)
    }
}

internal fun MusicViewModel.maybeAutoplayShuffleImpl() {
    if (!autoShuffleOnCompletion || autoShuffleJob?.isActive == true) return
    val generation = playbackGeneration
    autoShuffleJob = viewModelScope.launch {
        delay(150L)
        val activePlayer = player
        if (generation != playbackGeneration || activePlayer == null || activePlayer.playbackState != Player.STATE_ENDED) {
            autoShuffleJob = null
            return@launch
        }
        val endedSongId = activePlayer.currentMediaItem?.mediaId?.toLongOrNull()
        val localSongs = _uiState.value.songs
        val endedSong = localSongs.firstOrNull { it.id == endedSongId }
        val notEnded = localSongs.filter { it.id != endedSongId }
        val queueIds = _playbackState.value.queue.map(Song::id).toSet()
        val recentCooldown = if (_appSettings.value.repeatAvoidanceLevel == RepeatAvoidanceLevel.VERY_FEW) {
            VERY_FEW_RECENT_COOLDOWN_SIZE
        } else {
            AUTO_SHUFFLE_RECENT_COOLDOWN_SIZE
        }
        val recentIds = _uiState.value.recentlyPlayedIds.take(recentCooldown).toSet()
        val outsideCurrentQueue = notEnded.filter { it.id !in queueIds }
        val freshChoices = outsideCurrentQueue.filter { it.id !in recentIds }
        val candidates = freshChoices.ifEmpty { outsideCurrentQueue.ifEmpty { notEnded } }
        if (candidates.isEmpty()) {
            stopShuffleContinuation(activePlayer)
            return@launch
        }
        val smartQueue = buildSmartShuffleQueue(candidates, contextSeed = endedSong)
        if (smartQueue.isEmpty()) {
            stopShuffleContinuation(activePlayer)
            return@launch
        }
        smartShuffleActive = true
        preserveCurrentQueueOrder = true
        playbackGeneration++
        activePlayer.shuffleModeEnabled = false
        activePlayer.setMediaItems(smartQueue.map(::toMediaItem), 0, C.TIME_UNSET)
        activePlayer.prepare()
        activePlayer.play()
        recordPlayed(smartQueue.first().id, countPlay = false)
        syncPlayback(activePlayer)
        scheduleEmbeddedReplayGainForCurrentQueue()
        autoShuffleJob = null
    }
}

internal fun MusicViewModel.playNextImpl(song: Song) {
    val activePlayer = player ?: return
    queuedIndicatorIds.remove(song.id)
    val queueIds = (0 until activePlayer.mediaItemCount).map { index ->
        activePlayer.getMediaItemAt(index).mediaId.toLongOrNull() ?: Long.MIN_VALUE
    }
    val insertAt = queueNextInsertionIndex(
        activePlayer.currentMediaItemIndex,
        queueIds,
        playNextIndicatorIds
    )
    playNextIndicatorIds.add(song.id)
    if (activePlayer.mediaItemCount == 0) {
        activePlayer.setMediaItem(toMediaItem(song))
        activePlayer.prepare()
        activePlayer.play()
    } else {
        activePlayer.addMediaItem(insertAt, toMediaItem(song))
        if (activePlayer.currentMediaItemIndex < 0) {
            activePlayer.seekToDefaultPosition(insertAt)
            activePlayer.prepare()
            activePlayer.play()
        }
    }
    syncPlayback(activePlayer)
    scheduleEmbeddedReplayGainForCurrentQueue()
}

internal fun MusicViewModel.moveQueueItemImpl(fromIndex: Int, toIndex: Int) {
    val activePlayer = player ?: return
    val currentIndex = activePlayer.currentMediaItemIndex
    if (fromIndex !in 0 until activePlayer.mediaItemCount ||
        toIndex !in 0 until activePlayer.mediaItemCount ||
        fromIndex <= currentIndex || toIndex <= currentIndex || fromIndex == toIndex
    ) return
    activePlayer.moveMediaItem(fromIndex, toIndex)
    syncPlayback(activePlayer)
}

internal fun MusicViewModel.removeQueueItemImpl(index: Int) {
    val activePlayer = player ?: return
    if (index !in 0 until activePlayer.mediaItemCount || index <= activePlayer.currentMediaItemIndex) return
    activePlayer.removeMediaItem(index)
    syncPlayback(activePlayer)
}

internal fun MusicViewModel.clearUpcomingQueueImpl() {
    val activePlayer = player ?: return
    val firstUpcoming = (activePlayer.currentMediaItemIndex + 1).coerceAtLeast(0)
    for (index in activePlayer.mediaItemCount - 1 downTo firstUpcoming) {
        activePlayer.removeMediaItem(index)
    }
    syncPlayback(activePlayer)
}

internal fun MusicViewModel.togglePlayPauseImpl() {
    val activePlayer = player ?: return
    if (activePlayer.isPlaying) {
        activePlayer.pause()
    } else {
        if (activePlayer.playbackState == Player.STATE_IDLE) activePlayer.prepare()
        activePlayer.play()
    }
    syncPlayback(activePlayer)
}

internal fun MusicViewModel.skipNextImpl() {
    val activePlayer = player ?: return
    if (activePlayer.hasNextMediaItem()) {
        finishCurrentSessionAsSkip(activePlayer)
        dispatchInQueueTransition(activePlayer, activePlayer.nextMediaItemIndex) {
            activePlayer.seekToNextMediaItem()
        }
    }
    syncPlayback(activePlayer)
}

internal fun MusicViewModel.skipPreviousImpl() {
    val activePlayer = player ?: return
    if (activePlayer.currentPosition > 3_000L) {
        activePlayer.seekTo(0L)
    } else if (activePlayer.hasPreviousMediaItem()) {
        finishCurrentSessionAsSkip(activePlayer)
        dispatchInQueueTransition(activePlayer, activePlayer.previousMediaItemIndex) {
            activePlayer.seekToPreviousMediaItem()
        }
    } else {
        activePlayer.seekTo(0L)
    }
    syncPlayback(activePlayer)
}

internal fun MusicViewModel.seekToImpl(positionMs: Long) {
    player?.seekTo(positionMs.coerceAtLeast(0L))
    syncPlayback(player)
}

internal fun MusicViewModel.toggleShuffleImpl() {
    val activePlayer = player ?: return
    clearSmartShuffleCompletionPrompt()
    if (smartShuffleActive || activePlayer.shuffleModeEnabled) {
        val currentIndex = activePlayer.currentMediaItemIndex
        val itemCount = activePlayer.mediaItemCount
        val alphabeticalUpcoming = if (currentIndex in 0 until itemCount) {
            (currentIndex + 1 until itemCount)
                .map(activePlayer::getMediaItemAt)
                .sortedWith(
                    compareBy<MediaItem> { it.mediaMetadata.title?.toString()?.trim()?.lowercase().orEmpty() }
                        .thenBy { it.mediaMetadata.artist?.toString()?.trim()?.lowercase().orEmpty() }
                        .thenBy { it.mediaMetadata.albumTitle?.toString()?.trim()?.lowercase().orEmpty() }
                )
        } else {
            emptyList()
        }

        smartShuffleActive = false
        preserveCurrentQueueOrder = false
        autoShuffleOnCompletion = false
        autoShuffleJob?.cancel()
        autoShuffleJob = null
        playbackGeneration++
        activePlayer.shuffleModeEnabled = false

        // Keep the playing MediaItem and its position intact. Replacing the full playlist and
        // calling prepare() here can restart/rebuffer the current track on a controller.
        if (currentIndex in 0 until itemCount && currentIndex + 1 < itemCount) {
            activePlayer.removeMediaItems(currentIndex + 1, itemCount)
            if (alphabeticalUpcoming.isNotEmpty()) {
                activePlayer.addMediaItems(currentIndex + 1, alphabeticalUpcoming)
            }
        }
        syncPlayback(activePlayer)
        persistPlaybackSession(activePlayer, force = true)
        scheduleEmbeddedReplayGainForCurrentQueue()
    } else {
        enableSmartShuffleForCurrentTrack(_uiState.value.songs)
    }
}

internal fun MusicViewModel.enableSmartShuffleForCurrentTrackImpl(songs: List<Song>) {
    radioActive = false
    val activePlayer = player ?: run {
        startSmartShuffleNow(songs)
        return
    }
    val currentIndex = activePlayer.currentMediaItemIndex
    val currentId = activePlayer.currentMediaItem?.mediaId?.toLongOrNull()
    val songById = cachedSongLookup.ifEmpty { (_uiState.value.songs + sharedSongs.values).associateBy(Song::id) }
    val currentSong = currentId?.let(songById::get)
    if (currentSong == null || currentIndex !in 0 until activePlayer.mediaItemCount || activePlayer.playbackState == Player.STATE_ENDED) {
        startSmartShuffleNow(songs.ifEmpty { _uiState.value.songs })
        return
    }

    autoShuffleJob?.cancel()
    autoShuffleJob = null
    playbackGeneration++
    val candidates = songs.ifEmpty { _uiState.value.songs }
    val smartUpcoming = buildSmartShuffleQueue(
        candidates,
        excludedIds = setOf(currentSong.id),
        contextSeed = currentSong
    )
    if (smartUpcoming.isEmpty()) {
        // No alternatives: leave the existing queue intact and don't report Shuffle as active.
        stopShuffleContinuation(activePlayer)
        return
    }

    smartShuffleActive = true
    preserveCurrentQueueOrder = true
    autoShuffleOnCompletion = true
    activePlayer.shuffleModeEnabled = false
    activePlayer.repeatMode = Player.REPEAT_MODE_OFF
    if (currentIndex + 1 < activePlayer.mediaItemCount) {
        activePlayer.removeMediaItems(currentIndex + 1, activePlayer.mediaItemCount)
    }
    if (smartUpcoming.isNotEmpty()) {
        activePlayer.addMediaItems(currentIndex + 1, smartUpcoming.map(::toMediaItem))
    }
    syncPlayback(activePlayer)
    persistPlaybackSession(activePlayer, force = true)
    scheduleEmbeddedReplayGainForCurrentQueue()
}

internal fun MusicViewModel.cycleRepeatModeImpl() {
    player?.let { activePlayer ->
        if (smartShuffleActive || autoShuffleOnCompletion || activePlayer.shuffleModeEnabled) {
            // Explicit repeat takes priority over a handoff that depends on STATE_ENDED.
            smartShuffleActive = false
            autoShuffleOnCompletion = false
            autoShuffleJob?.cancel()
            autoShuffleJob = null
            playbackGeneration++
            preserveCurrentQueueOrder = true
            activePlayer.shuffleModeEnabled = false
        }
        activePlayer.repeatMode = when (activePlayer.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        syncPlayback(activePlayer)
        persistPlaybackSession(activePlayer, force = true)
    }
}

internal fun MusicViewModel.restoreSleepTimerUiStateImpl() {
    val schedule = audioSettingsStore.sleepTimerSchedule() ?: return
    if (schedule.endElapsedMs <= SystemClock.elapsedRealtime()) return
    observeSleepTimerUi(schedule.endElapsedMs, schedule.fadeOutSeconds)
}

internal fun MusicViewModel.observeSleepTimerUiImpl(endElapsedMs: Long, fadeOutSeconds: Int) {
    sleepTimerJob?.cancel()
    _sleepTimerFadeOutSeconds.value = fadeOutSeconds.coerceIn(0, 120)
    sleepTimerJob = viewModelScope.launch {
        while (true) {
            val remaining = endElapsedMs - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            _sleepTimerMinutes.value = ((remaining + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
            delay(minOf(1_000L, remaining))
        }
        _sleepTimerMinutes.value = null
        _sleepTimerFadeOutSeconds.value = 0
        sleepTimerJob = null
    }
}

internal fun MusicViewModel.startSleepTimerImpl(minutes: Int, fadeOutSeconds: Int = 0) {
    cancelSleepTimer()
    val safeMinutes = minutes.coerceIn(1, 24 * 60)
    val safeFadeSeconds = fadeOutSeconds.coerceIn(0, 120)
    val endElapsedMs = SystemClock.elapsedRealtime() + safeMinutes * 60_000L
    audioSettingsStore.setSleepTimer(endElapsedMs, safeFadeSeconds)
    observeSleepTimerUi(endElapsedMs, safeFadeSeconds)
}

internal fun MusicViewModel.cancelSleepTimerImpl() {
    sleepTimerJob?.cancel()
    sleepTimerJob = null
    audioSettingsStore.clearSleepTimer()
    _sleepTimerMinutes.value = null
    _sleepTimerFadeOutSeconds.value = 0
}

internal fun MusicViewModel.updatePlaybackProgressImpl(activePlayer: Player) {
    val state = _playbackState.value
    val currentMediaId = activePlayer.currentMediaItem?.mediaId
    val currentIndex = activePlayer.currentMediaItemIndex
    if (state.currentMediaId != currentMediaId || state.currentIndex != currentIndex ||
        state.queue.size != activePlayer.mediaItemCount
    ) {
        syncPlayback(activePlayer)
        return
    }

    val rawDuration = activePlayer.duration
    val duration = if (rawDuration > 0L && rawDuration != C.TIME_UNSET) rawDuration
    else state.currentSong?.durationMs ?: 0L
    val position = activePlayer.currentPosition.coerceAtLeast(0L)
        .coerceAtMost(duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
    val isPlaying = activePlayer.isPlaying
    val isBuffering = activePlayer.playbackState == Player.STATE_BUFFERING
    val isEnded = activePlayer.playbackState == Player.STATE_ENDED
    val parameters = activePlayer.playbackParameters
    if (state.positionMs != position || state.durationMs != duration || state.isPlaying != isPlaying ||
        state.isBuffering != isBuffering || state.isEnded != isEnded ||
        state.playbackSpeed != parameters.speed || state.playbackPitch != parameters.pitch
    ) {
        _playbackState.value = state.copy(
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            isEnded = isEnded,
            positionMs = position,
            durationMs = duration,
            playbackSpeed = parameters.speed,
            playbackPitch = parameters.pitch
        )
    }
    persistPlaybackSession(activePlayer)
}

internal fun MusicViewModel.syncPlaybackImpl(activePlayer: Player?) {
    if (activePlayer == null) {
        playNextIndicatorIds.clear()
        queuedIndicatorIds.clear()
        _playbackState.value = PlaybackUiState()
        return
    }
    val songsById = cachedSongLookup.ifEmpty {
        (rawCatalogSongs + _uiState.value.songs + sharedSongs.values).associateBy(Song::id)
    }
    val currentIndex = activePlayer.currentMediaItemIndex
    val queue = (0 until activePlayer.mediaItemCount).map { index ->
        val item = activePlayer.getMediaItemAt(index)
        item.mediaId.toLongOrNull()?.let(songsById::get) ?: playbackPlaceholder(item, index)
    }
    val currentSong = queue.getOrNull(currentIndex)
    val currentMediaId = activePlayer.currentMediaItem?.mediaId
    val incomingMediaId = CrossfadeDisplayState.incomingMediaId.value
    if (incomingMediaId != null && incomingMediaId == currentMediaId) {
        CrossfadeDisplayState.clearIncoming(incomingMediaId)
    }
    val crossfadeIncomingSong = incomingMediaId
        ?.takeIf { it != currentMediaId }
        ?.toLongOrNull()
        ?.let { incomingId ->
            cachedSongLookup[incomingId]
                ?: sharedSongs[incomingId]
                ?: queue.firstOrNull { it.id == incomingId }
        }
        ?.takeIf { it.id != currentSong?.id }
    val upcomingSongIds = queue.drop((currentIndex + 1).coerceIn(0, queue.size)).map(Song::id).toSet()
    playNextIndicatorIds.retainAll(upcomingSongIds)
    queuedIndicatorIds.retainAll(upcomingSongIds)
    val rawDuration = activePlayer.duration
    val duration = if (rawDuration > 0L && rawDuration != C.TIME_UNSET) rawDuration
    else currentSong?.durationMs ?: 0L
    val position = activePlayer.currentPosition.coerceAtLeast(0L)
    _playbackState.value = PlaybackUiState(
        currentSong = currentSong,
        currentMediaId = currentMediaId,
        crossfadeIncomingSong = crossfadeIncomingSong,
        isPlaying = activePlayer.isPlaying,
        isBuffering = activePlayer.playbackState == Player.STATE_BUFFERING,
        isEnded = activePlayer.playbackState == Player.STATE_ENDED,
        promptForSmartShuffleOnCompletion = promptForSmartShuffleOnCompletion,
        positionMs = position.coerceAtMost(duration.takeIf { it > 0 } ?: Long.MAX_VALUE),
        durationMs = duration,
        shuffleEnabled = activePlayer.shuffleModeEnabled || smartShuffleActive,
        smartShuffleEnabled = smartShuffleActive,
        radioActive = radioActive,
        repeatMode = activePlayer.repeatMode,
        queue = queue,
        currentIndex = currentIndex,
        playNextSongIds = playNextIndicatorIds.toSet(),
        queuedSongIds = queuedIndicatorIds.toSet(),
        playbackSpeed = activePlayer.playbackParameters.speed,
        playbackPitch = activePlayer.playbackParameters.pitch,
        lastPlaybackError = lastPlaybackError
    )
    persistPlaybackSession(activePlayer)
}

internal fun MusicViewModel.playbackPlaceholderImpl(mediaItem: MediaItem, queueIndex: Int): Song {
    val metadata = mediaItem.mediaMetadata
    val uri = mediaItem.localConfiguration?.uri ?: Uri.EMPTY
    val id = mediaItem.mediaId.toLongOrNull()
        ?: (UNKNOWN_PLAYBACK_ID_BASE + queueIndex.toLong())
    val artist = metadata.artist?.toString()?.takeIf(String::isNotBlank) ?: "Unknown artist"
    return Song(
        id = id,
        title = metadata.title?.toString()?.takeIf(String::isNotBlank) ?: "Unavailable track",
        artist = artist,
        album = metadata.albumTitle?.toString().orEmpty(),
        durationMs = 0L,
        uri = uri,
        artworkUri = metadata.artworkUri,
        dateAddedSeconds = 0L,
        trackNumber = metadata.trackNumber ?: 0,
        albumArtist = metadata.albumArtist?.toString()?.takeIf(String::isNotBlank) ?: artist,
        genre = metadata.genre?.toString()?.takeIf(String::isNotBlank) ?: "Unknown genre",
        year = metadata.recordingYear ?: 0,
        discNumber = metadata.discNumber ?: 1,
        mimeType = mediaItem.localConfiguration?.mimeType.orEmpty(),
        fileName = uri.lastPathSegment.orEmpty()
    )
}

internal fun MusicViewModel.toMediaItemImpl(song: Song): MediaItem {
    val mimeType = song.mimeType.trim().takeUnless {
        it.isBlank() || '*' in it || it.equals("application/octet-stream", ignoreCase = true)
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(song.title)
        .setArtist(song.artist)
        .setAlbumTitle(song.album)
        .setAlbumArtist(song.albumArtist)
        .setGenre(song.genre)
        .setTrackNumber(song.trackNumber.takeIf { it > 0 })
        .setDiscNumber(song.discNumber.takeIf { it > 1 })
        .setRecordingYear(song.year.takeIf { it > 0 })
        .apply {
            song.artworkUri?.let { setArtworkUri(it) }
            song.replayGainDb?.let { setExtras(android.os.Bundle().apply { putFloat("replay_gain_db", it) }) }
        }
        .build()
    return MediaItem.Builder()
        .setMediaId(song.id.toString())
        .setUri(song.uri)
        .setMimeType(mimeType)
        .setMediaMetadata(metadata)
        .build()
}

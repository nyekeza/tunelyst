@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.tunelyst.music.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.tunelyst.music.data.CachedHomeMix
import com.tunelyst.music.data.HomeMixCacheSnapshot
import com.tunelyst.music.data.Song
import com.tunelyst.music.data.withOverride
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs

internal fun MusicViewModel.refreshAudioSettingsForOutputDeviceImpl() {
    _audioSettings.value = audioSettingsStore.read()
}

internal fun MusicViewModel.handleMediaStoreChangeImpl() {
    if (!hasAudioReadPermission()) return
    if (!catalogSnapshotLoaded) {
        queuedMediaStoreRefresh = true
        return
    }
    if (libraryScanJob?.isActive == true) {
        queuedMediaStoreRefresh = true
        return
    }
    // The observer itself confirms a library change, so refresh quietly on every supported API.
    startLibraryScan(showLoading = false)
}

internal fun MusicViewModel.hasAudioReadPermissionImpl(): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return getApplication<Application>().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

internal fun MusicViewModel.restoreCachedCatalogImpl() {
    viewModelScope.launch {
        val restored = withContext(Dispatchers.IO) {
            val snapshot = runCatching { catalogCache.readSnapshot() }.getOrNull()
            val cachedSongs = snapshot?.songs.orEmpty()
            val overrides = store.metadataOverrides()
            val excluded = store.excludedFolders()
            val allSongs = cachedSongs.map { song -> song.withOverride(overrides[song.id]) }
            val visibleSongs = allSongs
                .filterNot { song -> repository.isExcludedFolder(song.folderPath, excluded) }
            CachedCatalogRestore(
                snapshot = snapshot,
                songs = visibleSongs,
                allSongs = allSongs,
                excludedFolders = excluded,
                songIds = visibleSongs.mapTo(mutableSetOf(), Song::id),
                songLookup = visibleSongs.associateBy(Song::id),
                homeMixCacheSnapshot = runCatching { homeMixCache.readSnapshot() }.getOrNull()
            )
        }
        val snapshot = restored.snapshot
        rawCatalogSongs = snapshot?.songs.orEmpty()
        val hasPersistedCatalog = (snapshot?.savedAtMs ?: 0L) > 0L
        catalogRevision = snapshot?.mediaStoreRevision
        catalogSavedAtMs = snapshot?.savedAtMs ?: 0L
        catalogGenresLoaded = snapshot?.genresLoaded == true || restored.songs.isEmpty()
        lastSuccessfulSongIds = restored.songIds
        cachedSongLookup = restored.allSongs.associateBy(Song::id) + sharedSongs.values.associateBy(Song::id)
        catalogSnapshotLoaded = true

        val currentState = _uiState.value
        val rotationDay = LocalDate.now().toEpochDay()
        val (cachedRecommendations, freshCacheKey) = withContext(Dispatchers.Default) {
            val inputKey = createHomeRecommendationInputKey(
                songs = restored.songs,
                favorites = currentState.favorites,
                recentlyPlayedIds = currentState.recentlyPlayedIds,
                playCounts = currentState.playCounts,
                lessLikeArtistNames = currentState.lessLikeArtistNames,
                lessLikeGenreNames = currentState.lessLikeGenreNames,
                hiddenHomeSongIds = currentState.hiddenHomeSongIds,
                savedAtMs = catalogSavedAtMs,
                genresLoaded = catalogGenresLoaded,
                rotationDay = rotationDay
            )
            val recommendations = restoreHomeRecommendationsFromCache(
                cache = restored.homeMixCacheSnapshot,
                songLookup = restored.songLookup,
                hiddenHomeSongIds = currentState.hiddenHomeSongIds
            )
            val currentCache = restored.homeMixCacheSnapshot
            val cacheIsCurrent = isHomeRecommendationCacheCurrent(
                cache = currentCache,
                inputKey = inputKey,
                songLookup = restored.songLookup,
                hiddenHomeSongIds = currentState.hiddenHomeSongIds
            )
            recommendations to inputKey.takeIf { cacheIsCurrent && recommendations != null }
        }
        homeRecommendationInputKey = freshCacheKey
        _uiState.update {
            it.copy(
                songs = restored.songs,
                isLoading = !hasPersistedCatalog,
                isLibraryReady = true,
                excludedFolders = restored.excludedFolders,
                allFolders = store.knownFolders(),
                homeRecommendations = cachedRecommendations
            )
        }
        syncPlayback(player)
        tryRestorePlaybackSession()
        tryPlayPendingSharedUris()

        if (queuedLibraryRescan) {
            queuedLibraryRescan = false
            queuedAutomaticLibraryCheck = false
            queuedMediaStoreRefresh = false
            startLibraryScan(showLoading = true)
        } else if (queuedMediaStoreRefresh) {
            queuedMediaStoreRefresh = false
            startLibraryScan(showLoading = false)
        } else if (queuedAutomaticLibraryCheck) {
            queuedAutomaticLibraryCheck = false
            ensureLibraryLoaded()
        }
    }
}

internal fun MusicViewModel.observeHomeRecommendationsImpl() {
    viewModelScope.launch {
        _uiState
            .map { state ->
                HomeRecommendationObservation(
                    state = state,
                    inputs = HomeRecommendationInputSnapshot(
                        isLibraryReady = state.isLibraryReady,
                        songs = state.songs,
                        favorites = state.favorites,
                        recentlyPlayedIds = state.recentlyPlayedIds,
                        playCounts = state.playCounts,
                        lessLikeArtistNames = state.lessLikeArtistNames,
                        lessLikeGenreNames = state.lessLikeGenreNames,
                        hiddenHomeSongIds = state.hiddenHomeSongIds,
                        catalogSavedAtMs = catalogSavedAtMs,
                        genresLoaded = catalogGenresLoaded,
                        rotationDay = LocalDate.now().toEpochDay()
                    )
                )
            }
            .distinctUntilChanged { previous, current ->
                previous.inputs.hasSameInputsAs(current.inputs)
            }
            .flowOn(Dispatchers.Default)
            .collect { observation ->
                if (observation.inputs.isLibraryReady) scheduleHomeRecommendations(observation.state)
            }
    }
}

internal suspend fun MusicViewModel.scheduleHomeRecommendationsImpl(state: MusicUiState) {
    if (!state.isLibraryReady) return
    val rotationDay = LocalDate.now().toEpochDay()
    val savedAtMs = catalogSavedAtMs
    val genresLoaded = catalogGenresLoaded
    val key = withContext(Dispatchers.Default) {
        createHomeRecommendationInputKey(
            songs = state.songs,
            favorites = state.favorites,
            recentlyPlayedIds = state.recentlyPlayedIds,
            playCounts = state.playCounts,
            lessLikeArtistNames = state.lessLikeArtistNames,
            lessLikeGenreNames = state.lessLikeGenreNames,
            hiddenHomeSongIds = state.hiddenHomeSongIds,
            savedAtMs = savedAtMs,
            genresLoaded = genresLoaded,
            rotationDay = rotationDay
        )
    }
    if (key == homeRecommendationInputKey) return
    homeRecommendationInputKey = key
    homeRecommendationsJob?.cancel()

    // Keep any visible shelves, applying Home-only hiding while replacements are computed.
    state.homeRecommendations?.let { current ->
        val hidden = state.hiddenHomeSongIds
        val filtered = current.copy(
            recentlyAdded = current.recentlyAdded.filterNot { it.id in hidden },
            mixes = current.mixes.mapNotNull { mix ->
                mix.copy(songs = mix.songs.filterNot { it.id in hidden }).takeIf { it.songs.isNotEmpty() }
            },
            bestOfArtists = current.bestOfArtists.mapNotNull { mix ->
                mix.copy(songs = mix.songs.filterNot { it.id in hidden }).takeIf { it.songs.isNotEmpty() }
            }
        )
        if (filtered != current) _uiState.update { it.copy(homeRecommendations = filtered) }
    }

    val songs = state.songs
    val favorites = state.favorites
    val recentlyPlayedIds = state.recentlyPlayedIds
    val playCounts = state.playCounts
    val lessLikeArtistNames = state.lessLikeArtistNames
    val lessLikeGenreNames = state.lessLikeGenreNames
    val hiddenHomeSongIds = state.hiddenHomeSongIds
    homeRecommendationsJob = viewModelScope.launch {
        val recommendations = withContext(Dispatchers.Default) {
            buildHomeRecommendations(
                songs = songs,
                favorites = favorites,
                recentlyPlayedIds = recentlyPlayedIds,
                playCounts = playCounts,
                lessLikeArtistNames = lessLikeArtistNames,
                lessLikeGenreNames = lessLikeGenreNames,
                hiddenHomeSongIds = hiddenHomeSongIds,
                rotationDay = rotationDay
            )
        }
        if (homeRecommendationInputKey != key) return@launch
        _uiState.update { current ->
            if (homeRecommendationInputKey == key) current.copy(homeRecommendations = recommendations) else current
        }
        if (homeRecommendationInputKey != key || savedAtMs <= 0L) return@launch
        val cached = HomeMixCacheSnapshot(
            catalogSavedAtMs = savedAtMs,
            genresLoaded = genresLoaded,
            rotationDay = rotationDay,
            favoritesFingerprint = key.favoritesFingerprint,
            recentlyPlayedFingerprint = key.recentlyPlayedFingerprint,
            hiddenSongsFingerprint = key.hiddenSongsFingerprint,
            playCountsFingerprint = key.playCountsFingerprint,
            lessLikeArtistsFingerprint = key.lessLikeArtistsFingerprint,
            lessLikeGenresFingerprint = key.lessLikeGenresFingerprint,
            recentlyAddedSongIds = recommendations.recentlyAdded.map(Song::id),
            mixes = recommendations.mixes.map { mix ->
                CachedHomeMix(mix.title, mix.description, mix.songs.map(Song::id))
            },
            bestOfArtists = recommendations.bestOfArtists.map { mix ->
                CachedHomeMix(mix.title, mix.description, mix.songs.map(Song::id))
            }
        )
        withContext(Dispatchers.IO) {
            runCatching { homeMixCache.writeSnapshot(cached) }
        }
    }
}

internal fun MusicViewModel.isHomeRecommendationCacheCurrentImpl(
    cache: HomeMixCacheSnapshot?,
    inputKey: HomeRecommendationInputKey,
    songLookup: Map<Long, Song>,
    hiddenHomeSongIds: Set<Long>
): Boolean {
    if (cache == null || !cache.hasBestOfArtists || inputKey.catalogSavedAtMs <= 0L ||
        cache.catalogSavedAtMs != inputKey.catalogSavedAtMs ||
        cache.genresLoaded != inputKey.genresLoaded ||
        cache.rotationDay != inputKey.rotationDay ||
        cache.favoritesFingerprint != inputKey.favoritesFingerprint ||
        cache.recentlyPlayedFingerprint != inputKey.recentlyPlayedFingerprint ||
        cache.hiddenSongsFingerprint != inputKey.hiddenSongsFingerprint ||
        cache.playCountsFingerprint != inputKey.playCountsFingerprint ||
        cache.lessLikeArtistsFingerprint != inputKey.lessLikeArtistsFingerprint ||
        cache.lessLikeGenresFingerprint != inputKey.lessLikeGenresFingerprint
    ) return false

    val cachedSongIds = cache.recentlyAddedSongIds +
        cache.mixes.flatMap(CachedHomeMix::songIds) +
        cache.bestOfArtists.flatMap(CachedHomeMix::songIds)
    return cachedSongIds.all { songId -> songId in songLookup && songId !in hiddenHomeSongIds }
}

internal fun MusicViewModel.loadLibraryImpl() {
    if (!catalogSnapshotLoaded) {
        queuedLibraryRescan = true
        return
    }
    startLibraryScan(showLoading = true)
}

internal fun MusicViewModel.ensureLibraryLoadedImpl() {
    if (!catalogSnapshotLoaded) {
        queuedAutomaticLibraryCheck = true
        return
    }
    if (libraryScanJob?.isActive == true || libraryFreshnessCheckJob?.isActive == true) return

    val checkJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
        try {
            val currentRevision = withContext(Dispatchers.IO) { repository.mediaStoreRevision() }
            val hasSnapshot = catalogSavedAtMs > 0L
            val changed = currentRevision != null && currentRevision != catalogRevision
            val expired = currentRevision == null &&
                System.currentTimeMillis() - catalogSavedAtMs >= LIBRARY_CACHE_MAX_AGE_MS
            if (!hasSnapshot || changed || expired) {
                startLibraryScan(showLoading = !hasSnapshot)
            } else {
                ensureGenreMetadata()
            }
        } finally {
            libraryFreshnessCheckJob = null
        }
    }
    libraryFreshnessCheckJob = checkJob
    checkJob.start()
}

internal fun MusicViewModel.startLibraryScanImpl(showLoading: Boolean) {
    if (!catalogSnapshotLoaded) {
        if (showLoading) queuedLibraryRescan = true else queuedAutomaticLibraryCheck = true
        return
    }
    if (libraryScanJob?.isActive == true) {
        if (showLoading) {
            queuedLibraryRescan = true
            _uiState.update { it.copy(isLoading = true) }
        }
        return
    }

    catalogPersistenceJob?.cancel()
    genreEnrichmentJob?.cancel()
    catalogPersistenceJob = null
    genreEnrichmentJob = null
    if (showLoading) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null, removedTrackCount = 0, inaccessibleTrackCount = 0) }
    } else {
        _uiState.update { it.copy(errorMessage = null) }
    }

    val scanJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
        try {
            val previousIds = _uiState.value.songs.map(Song::id).toSet().ifEmpty { lastSuccessfulSongIds }
            val (catalogSongs, scannedSongs, mediaStoreRevision) = withContext(Dispatchers.IO) {
                val rawSongs = repository.scanDevice()
                val overrides = store.metadataOverrides()
                Triple(
                    rawSongs,
                    rawSongs.map { song -> song.withOverride(overrides[song.id]) },
                    repository.mediaStoreRevision()
                )
            }
            val scanSavedAtMs = System.currentTimeMillis()
            rawCatalogSongs = catalogSongs
            store.setKnownFolders(scannedSongs.map(Song::folderPath).filter(String::isNotBlank).distinct())
            val excluded = store.excludedFolders()
            val visibleSongs = scannedSongs.filterNot { repository.isExcludedFolder(it.folderPath, excluded) }
            val catalogSongIds = catalogSongs.mapTo(mutableSetOf(), Song::id)
            val visibleSongIds = visibleSongs.mapTo(mutableSetOf(), Song::id)
            val removedCount = (previousIds - visibleSongIds).size
            val recentlySearchedIds = store.pruneRecentlySearched(catalogSongIds)

            catalogRevision = mediaStoreRevision
            catalogSavedAtMs = scanSavedAtMs
            catalogGenresLoaded = catalogSongs.isEmpty()
            lastSuccessfulSongIds = visibleSongIds
            cachedSongLookup = (scannedSongs + sharedSongs.values).associateBy(Song::id)
            _uiState.update {
                it.copy(
                    songs = visibleSongs,
                    recentlySearchedIds = recentlySearchedIds,
                    isLoading = false,
                    isLibraryReady = true,
                    removedTrackCount = removedCount,
                    inaccessibleTrackCount = 0,
                    excludedFolders = excluded,
                    allFolders = store.knownFolders(),
                    errorMessage = null
                )
            }
            // Persist the visible catalog first; genre membership enrichment runs afterward off the launch path.
            persistCatalogSnapshot(catalogSongs, mediaStoreRevision, scanSavedAtMs, genresLoaded = catalogSongs.isEmpty())
            syncPlayback(player)
            tryRestorePlaybackSession()
            scheduleEmbeddedReplayGainForCurrentQueue()
            tryPlayPendingSharedUris()
        } catch (security: SecurityException) {
            _uiState.update {
                it.copy(isLoading = false, isLibraryReady = true, inaccessibleTrackCount = it.songs.size, errorMessage = "Audio access was denied. Your last library view is kept; allow access in Settings to rescan.")
            }
            tryPlayPendingSharedUris()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _uiState.update {
                it.copy(isLoading = false, isLibraryReady = true, inaccessibleTrackCount = it.songs.size, errorMessage = "Library scan couldn't complete. Your existing tracks were kept. ${error.message ?: "Check storage access and try again."}")
            }
            tryPlayPendingSharedUris()
        } finally {
            libraryScanJob = null
            if (queuedLibraryRescan) {
                runQueuedLibraryRescan()
            } else if (queuedMediaStoreRefresh) {
                queuedMediaStoreRefresh = false
                startLibraryScan(showLoading = false)
            }
        }
    }
    libraryScanJob = scanJob
    scanJob.start()
}

internal fun MusicViewModel.ensureGenreMetadataImpl(rawCatalogSongs: List<Song>? = null) {
    if (catalogGenresLoaded || genreEnrichmentJob?.isActive == true || catalogSavedAtMs <= 0L) return
    val expectedSavedAtMs = catalogSavedAtMs
    val expectedRevision = catalogRevision
    lateinit var job: Job
    job = viewModelScope.launch(start = CoroutineStart.LAZY) {
        try {
            val snapshot = if (rawCatalogSongs == null) {
                withContext(Dispatchers.IO) { catalogCache.readSnapshot() }
            } else null
            if (snapshot?.genresLoaded == true) {
                withContext(Dispatchers.Main) {
                    if (catalogSavedAtMs == expectedSavedAtMs && catalogRevision == expectedRevision) {
                        catalogGenresLoaded = true
                        scheduleHomeRecommendations(_uiState.value)
                    }
                }
                return@launch
            }
            val catalogSongs = rawCatalogSongs ?: snapshot?.songs.orEmpty()
            if (catalogSongs.isEmpty()) {
                withContext(Dispatchers.Main) {
                    if (catalogSavedAtMs == expectedSavedAtMs && catalogRevision == expectedRevision) {
                        catalogGenresLoaded = true
                        scheduleHomeRecommendations(_uiState.value)
                    }
                }
                return@launch
            }
            val genresBySongId = withContext(Dispatchers.IO) { repository.loadGenresBySongId() } ?: return@launch
            val enrichedCatalog = if (genresBySongId.isEmpty()) catalogSongs else catalogSongs.map { song ->
                genresBySongId[song.id]?.let { genre -> song.copy(genre = genre) } ?: song
            }
            val overrides = withContext(Dispatchers.IO) { store.metadataOverrides() }
            val excluded = withContext(Dispatchers.IO) { store.excludedFolders() }
            val allEnrichedSongs = enrichedCatalog.map { song -> song.withOverride(overrides[song.id]) }
            val visibleSongs = allEnrichedSongs
                .filterNot { song -> repository.isExcludedFolder(song.folderPath, excluded) }
            val stillCurrent = withContext(Dispatchers.Main) {
                catalogSavedAtMs == expectedSavedAtMs && catalogRevision == expectedRevision
            }
            if (!stillCurrent) return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    catalogCache.writeSnapshot(enrichedCatalog, expectedRevision, expectedSavedAtMs, genresLoaded = true)
                }
            }
            withContext(Dispatchers.Main) {
                if (catalogSavedAtMs != expectedSavedAtMs || catalogRevision != expectedRevision) return@withContext
                this@ensureGenreMetadataImpl.rawCatalogSongs = enrichedCatalog
                catalogGenresLoaded = true
                if (genresBySongId.isNotEmpty()) {
                    cachedSongLookup = (allEnrichedSongs + sharedSongs.values).associateBy(Song::id)
                    _uiState.update { it.copy(songs = visibleSongs, excludedFolders = excluded) }
                    syncPlayback(player)
                }
                scheduleHomeRecommendations(_uiState.value)
            }
        } finally {
            if (genreEnrichmentJob === job) genreEnrichmentJob = null
        }
    }
    genreEnrichmentJob = job
    job.start()
}

internal fun MusicViewModel.runQueuedLibraryRescanImpl() {
    if (!queuedLibraryRescan || libraryScanJob?.isActive == true) return
    queuedLibraryRescan = false
    startLibraryScan(showLoading = true)
}

internal fun MusicViewModel.tryRestorePlaybackSessionImpl() {
    if (playbackRestoreAttempted || player == null || _uiState.value.songs.isEmpty() || pendingSharedUris.isNotEmpty()) return
    val activePlayer = player ?: return
    if (activePlayer.mediaItemCount > 0) {
        playbackRestoreAttempted = true
        return
    }
    playbackRestoreAttempted = true
    val saved = store.savedPlaybackSession() ?: return
    val localSongs = _uiState.value.songs
    val currentSong = localSongs.firstOrNull { it.id == saved.songIds.firstOrNull() }
    if (currentSong == null) {
        store.clearPlaybackSession()
        return
    }
    val restoredQueue = saved.songIds.mapNotNull { id -> localSongs.firstOrNull { it.id == id } }
    if (restoredQueue.isEmpty()) {
        store.clearPlaybackSession()
        return
    }
    smartShuffleActive = saved.smartShuffleEnabled || saved.shuffleEnabled
    preserveCurrentQueueOrder = saved.preserveQueueOrder || smartShuffleActive
    autoShuffleOnCompletion = smartShuffleActive
    // Migrate an older persisted native-shuffle queue to the Smart Shuffle indicator/continuation.
    activePlayer.shuffleModeEnabled = false
    activePlayer.repeatMode = if (smartShuffleActive) Player.REPEAT_MODE_OFF else {
        saved.repeatMode.coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL)
    }
    val restoredPosition = if (currentSong.durationMs > 0L) saved.positionMs.coerceAtMost(currentSong.durationMs) else saved.positionMs
    activePlayer.setMediaItems(restoredQueue.map(::toMediaItem), 0, restoredPosition)
    activePlayer.prepare()
    activePlayer.pause()
    syncPlayback(activePlayer)
    persistPlaybackSession(activePlayer, force = true)
}

internal fun MusicViewModel.persistPlaybackSessionImpl(activePlayer: Player, force: Boolean = false) {
    if (activePlayer.mediaItemCount == 0 || activePlayer.currentMediaItemIndex !in 0 until activePlayer.mediaItemCount) return
    val now = SystemClock.elapsedRealtime()
    if (!force && now - lastSessionWriteElapsedMs < SESSION_SAVE_INTERVAL_MS) return
    val startIndex = activePlayer.currentMediaItemIndex
    val endIndex = minOf(activePlayer.mediaItemCount, startIndex + MAX_SAVED_QUEUE)
    val ids = ArrayList<Long>(endIndex - startIndex)
    for (index in startIndex until endIndex) {
        val id = activePlayer.getMediaItemAt(index).mediaId.toLongOrNull() ?: return
        // Shared files do not have stable MediaStore IDs; don't retain a stale session that cannot be restored offline.
        if (id < 0L) {
            store.clearPlaybackSession()
            lastSavedSessionIds = emptyList()
            return
        }
        ids += id
    }
    if (ids.isEmpty()) return
    val position = activePlayer.currentPosition.coerceAtLeast(0L)
    if (!force && ids == lastSavedSessionIds &&
        activePlayer.shuffleModeEnabled == lastSavedShuffle &&
        smartShuffleActive == lastSavedSmartShuffle &&
        preserveCurrentQueueOrder == lastSavedPreserveQueueOrder &&
        activePlayer.repeatMode == lastSavedRepeatMode &&
        kotlin.math.abs(position - lastSavedSessionPositionMs) < SESSION_SAVE_POSITION_DELTA_MS
    ) return
    store.savePlaybackSession(
        queueIds = ids,
        currentIndex = 0,
        positionMs = position,
        shuffleEnabled = activePlayer.shuffleModeEnabled,
        repeatMode = activePlayer.repeatMode,
        smartShuffleEnabled = smartShuffleActive,
        preserveQueueOrder = preserveCurrentQueueOrder
    )
    lastSessionWriteElapsedMs = now
    lastSavedSessionIds = ids
    lastSavedSessionPositionMs = position
    lastSavedShuffle = activePlayer.shuffleModeEnabled
    lastSavedSmartShuffle = smartShuffleActive
    lastSavedPreserveQueueOrder = preserveCurrentQueueOrder
    lastSavedRepeatMode = activePlayer.repeatMode
}

internal fun MusicViewModel.refreshSavedStateImpl(includeListeningStats: Boolean = false) {
    _uiState.update { current ->
        current.copy(
            favorites = store.favorites(),
            playCounts = store.playCounts(),
            listeningStats = if (includeListeningStats) store.listeningStats() else current.listeningStats,
            lessLikeArtistNames = store.lessLikeArtistNames(),
            lessLikeGenreNames = store.lessLikeGenreNames(),
            playlists = store.playlists(),
            recentlyPlayedIds = store.recentlyPlayed(),
            recentlySearchedIds = store.recentlySearched(),
            hiddenHomeSongIds = store.hiddenHomeSongIds(),
            hiddenHomeCardIds = store.hiddenHomeCardIds(),
            excludedFolders = store.excludedFolders(),
            allFolders = store.knownFolders(),
            savedQueues = store.savedQueues(),
            smartPlaylists = store.smartPlaylists()
        )
    }
}

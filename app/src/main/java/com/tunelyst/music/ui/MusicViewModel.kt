package com.tunelyst.music.ui

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import com.tunelyst.music.data.AppSettingsState
import com.tunelyst.music.data.AppSettingsStore
import com.tunelyst.music.data.AppThemeMode
import com.tunelyst.music.data.AudioSettingsState
import com.tunelyst.music.data.AudioSettingsStore
import com.tunelyst.music.data.CachedHomeMix
import com.tunelyst.music.data.CrossfadeCurve
import com.tunelyst.music.data.HomeMixCache
import com.tunelyst.music.data.HomeMixCacheSnapshot
import com.tunelyst.music.data.homeMixOrderedIdFingerprint
import com.tunelyst.music.data.homeMixSetFingerprint
import com.tunelyst.music.data.homeMixPlayCountsFingerprint
import com.tunelyst.music.data.homeMixTasteNamesFingerprint
import com.tunelyst.music.data.LibraryCatalogCache
import com.tunelyst.music.data.LibraryCatalogSnapshot
import com.tunelyst.music.data.LibraryStore
import com.tunelyst.music.data.LocalAudioFeatureStore
import com.tunelyst.music.data.LocalAudioFeatures
import com.tunelyst.music.data.PersonalDataBackup
import com.tunelyst.music.data.RepeatAvoidanceLevel
import com.tunelyst.music.data.SavedQueue
import com.tunelyst.music.data.SmartPlaylist
import com.tunelyst.music.data.SongMetadataOverride
import com.tunelyst.music.data.MusicRepository
import com.tunelyst.music.data.Playlist
import com.tunelyst.music.data.Song
import com.tunelyst.music.data.TrackListeningStats
import com.tunelyst.music.data.withOverride
import com.tunelyst.music.playback.CrossfadeDisplayState
import com.tunelyst.music.playback.PendingPlaybackTransitionRequests
import com.tunelyst.music.playback.inQueueTransitionRequest
import com.tunelyst.music.playback.PlaybackTransitionProtocol
import com.tunelyst.music.playback.PlaybackTransitionRequest
import com.tunelyst.music.playback.ReplayGainRuntimeCache
import com.tunelyst.music.core.logic.isTunelystLowMemoryDevice
import com.tunelyst.music.core.logic.matchesGenreRule
import com.tunelyst.music.core.logic.normalizeTasteKey
import com.tunelyst.music.core.logic.normalizedGenreKeys
import com.tunelyst.music.core.logic.queueNextInsertionIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln


@OptIn(UnstableApi::class)
class MusicViewModel(application: Application) : AndroidViewModel(application) {
    internal val repository = MusicRepository(application)
    internal val store = LibraryStore(application)
    internal val catalogCache = LibraryCatalogCache(application)
    internal val homeMixCache = HomeMixCache(application)
    /** Raw MediaStore catalog; unlike the UI list this still includes tracks in excluded folders. */
    internal var rawCatalogSongs: List<Song> = emptyList()
    internal val lowMemoryDevice = run {
        val activityManager = application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        isTunelystLowMemoryDevice(
            platformLowRam = activityManager?.isLowRamDevice == true,
            memoryClassMb = activityManager?.memoryClass ?: Int.MAX_VALUE,
            maxHeapBytes = Runtime.getRuntime().maxMemory()
        )
    }
    internal val audioFeatureStore = LocalAudioFeatureStore(application)
    internal val audioFeatureRequests = Channel<Song>(capacity = AUDIO_FEATURE_QUEUE_CAPACITY)
    internal val queuedAudioFeatureIds = ConcurrentHashMap.newKeySet<Long>()
    internal val backupStore = PersonalDataBackup(application)
    internal val audioSettingsStore = AudioSettingsStore(application)
    internal val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    internal val mediaStoreRefreshHandler = Handler(Looper.getMainLooper())
    internal val mediaStoreRefreshRunnable = Runnable { handleMediaStoreChange() }
    internal val mediaStoreObserver: ContentObserver = object : ContentObserver(mediaStoreRefreshHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            mediaStoreRefreshHandler.removeCallbacks(mediaStoreRefreshRunnable)
            mediaStoreRefreshHandler.postDelayed(mediaStoreRefreshRunnable, MEDIA_STORE_CHANGE_DEBOUNCE_MS)
        }
    }
    internal val audioDeviceCallback: AudioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { refreshAudioSettingsForOutputDevice() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { refreshAudioSettingsForOutputDevice() }
    }
    internal val _audioSettings = MutableStateFlow(audioSettingsStore.read())
    val audioSettings: StateFlow<AudioSettingsState> = _audioSettings.asStateFlow()
    internal val appSettingsStore = AppSettingsStore(application)
    internal val _appSettings = MutableStateFlow(appSettingsStore.read())
    val appSettings: StateFlow<AppSettingsState> = _appSettings.asStateFlow()
    internal val _uiState = MutableStateFlow(MusicUiState())
    val uiState: StateFlow<MusicUiState> = _uiState.asStateFlow()

    internal val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()
    internal val _openNowPlayingRequest = MutableStateFlow(0L)
    val openNowPlayingRequest: StateFlow<Long> = _openNowPlayingRequest.asStateFlow()
    internal val _playerConnectionError = MutableStateFlow<String?>(null)
    val playerConnectionError: StateFlow<String?> = _playerConnectionError.asStateFlow()
    internal var playerConnectionRetryAction: (() -> Unit)? = null
    internal var deviceDeleteCoordinator: DeviceAudioDeleteCoordinator? = null
    internal var deviceDeleteAuthorizationLauncher: ((IntentSenderRequest) -> Unit)? = null
    internal val _deviceDeleteMessage = MutableStateFlow<String?>(null)
    val deviceDeleteMessage: StateFlow<String?> = _deviceDeleteMessage.asStateFlow()
    internal var deviceRenameCoordinator: DeviceAudioRenameCoordinator? = null
    internal var deviceRenameAuthorizationLauncher: ((IntentSenderRequest) -> Unit)? = null
    internal val _titleFixMessage = MutableStateFlow<String?>(null)
    val titleFixMessage: StateFlow<String?> = _titleFixMessage.asStateFlow()
    internal var deviceTagWriteCoordinator: DeviceAudioTagWriteCoordinator? = null
    internal var deviceTagWriteAuthorizationLauncher: ((IntentSenderRequest) -> Unit)? = null
    internal val _embeddedTagWriteMessage = MutableStateFlow<String?>(null)
    val embeddedTagWriteMessage: StateFlow<String?> = _embeddedTagWriteMessage.asStateFlow()

    internal val _sleepTimerMinutes = MutableStateFlow<Int?>(null)
    val sleepTimerMinutes: StateFlow<Int?> = _sleepTimerMinutes.asStateFlow()
    internal val _sleepTimerFadeOutSeconds = MutableStateFlow(0)
    val sleepTimerFadeOutSeconds: StateFlow<Int> = _sleepTimerFadeOutSeconds.asStateFlow()

    internal var player: Player? = null
    internal val playNextIndicatorIds = mutableSetOf<Long>()
    internal val queuedIndicatorIds = mutableSetOf<Long>()
    internal var sleepTimerJob: Job? = null
    internal val sharedSongs = mutableMapOf<Long, Song>()
    internal var pendingSharedUris: List<Uri> = emptyList()
    internal var resolvingSharedUris = false
    internal var autoShuffleOnCompletion = false
    internal var promptForSmartShuffleOnCompletion = false
    internal var radioActive = false
    internal var autoShuffleJob: Job? = null
    internal var replayGainEnrichmentJob: Job? = null
    internal var playbackGeneration = 0L
    internal var smartShuffleActive = false
    internal var preserveCurrentQueueOrder = false
    internal var playbackRestoreAttempted = false
    internal var lastSessionWriteElapsedMs = 0L
    internal var lastSavedSessionIds: List<Long> = emptyList()
    internal var lastSavedSessionPositionMs = -1L
    internal var lastSavedShuffle = false
    internal var lastSavedSmartShuffle = false
    internal var lastSavedPreserveQueueOrder = false
    internal var lastSavedRepeatMode = Player.REPEAT_MODE_OFF
    internal var cachedSongLookup: Map<Long, Song> = emptyMap()
    internal var lastSuccessfulSongIds: Set<Long> = emptySet()
    internal var lastPlaybackError: String? = null
    internal var queuedLibraryRescan = false
    internal var queuedAutomaticLibraryCheck = false
    internal var queuedMediaStoreRefresh = false
    internal var catalogSnapshotLoaded = false
    internal var catalogRevision: String? = null
    internal var catalogSavedAtMs = 0L
    internal var catalogGenresLoaded = false
    internal var libraryScanJob: Job? = null
    internal var libraryFreshnessCheckJob: Job? = null
    internal var catalogPersistenceJob: Job? = null
    internal var genreEnrichmentJob: Job? = null
    internal var homeRecommendationsJob: Job? = null
    internal var homeRecommendationInputKey: HomeRecommendationInputKey? = null
    internal var listeningSession: ActiveListeningSession? = null
    internal var lastListeningSampleElapsedMs = 0L

    private suspend fun awaitAudioAnalysisIdle() {
        while (true) {
            while (_playbackState.value.isPlaying || _playbackState.value.isBuffering) {
                delay(AUDIO_FEATURE_PLAYBACK_POLL_MS)
            }
            delay(AUDIO_FEATURE_IDLE_GRACE_MS)
            if (!_playbackState.value.isPlaying && !_playbackState.value.isBuffering) return
        }
    }

    init {
        viewModelScope.launch {
            CrossfadeDisplayState.incomingMediaId.collect { incomingMediaId ->
                val currentMediaId = player?.currentMediaItem?.mediaId
                if (incomingMediaId != null && incomingMediaId == currentMediaId) {
                    player?.let(::syncPlayback)
                    CrossfadeDisplayState.clearIncoming(incomingMediaId)
                    return@collect
                }
                val currentState = _playbackState.value
                val incomingSong = incomingMediaId
                    ?.takeIf { it != currentMediaId }
                    ?.toLongOrNull()
                    ?.let { incomingId ->
                        cachedSongLookup[incomingId]
                            ?: sharedSongs[incomingId]
                            ?: currentState.queue.firstOrNull { it.id == incomingId }
                    }
                    ?.takeIf { it.id != currentState.currentSong?.id }
                _playbackState.update { it.copy(crossfadeIncomingSong = incomingSong) }
            }
        }
        // The playback service owns timer execution; this only restores the visible countdown.
        restoreSleepTimerUiState()
        runCatching { audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper())) }
        runCatching {
            application.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaStoreObserver
            )
        }
        if (!lowMemoryDevice) {
            viewModelScope.launch(Dispatchers.IO) {
                awaitAudioAnalysisIdle()
                audioFeatureStore.loadCache()
                for (song in audioFeatureRequests) {
                    try {
                        // Feature extraction starts a second decoder and uses a short PCM buffer.
                        // Defer that work while playback is active so it cannot compete with the
                        // foreground MediaSession, and keep the pending work queue strictly bounded.
                        awaitAudioAnalysisIdle()
                        audioFeatureStore.analyze(song)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Unsupported decoders/providers simply leave this track on metadata-based ranking.
                    } finally {
                        queuedAudioFeatureIds.remove(song.id)
                    }
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    internal val playerListener: Player.Listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_REPEAT_MODE_CHANGED) &&
                player.repeatMode != Player.REPEAT_MODE_OFF &&
                (smartShuffleActive || autoShuffleOnCompletion)
            ) {
                // Repeat has been explicitly selected by another controller; let it win cleanly.
                smartShuffleActive = false
                autoShuffleOnCompletion = false
                autoShuffleJob?.cancel()
                autoShuffleJob = null
                playbackGeneration++
                preserveCurrentQueueOrder = true
                player.shuffleModeEnabled = false
            }
            val playbackStateChanged = events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_MEDIA_METADATA_CHANGED) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
                events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                events.contains(Player.EVENT_REPEAT_MODE_CHANGED) ||
                events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)
            if (playbackStateChanged) syncPlayback(player)
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
                events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                events.contains(Player.EVENT_REPEAT_MODE_CHANGED)
            ) persistPlaybackSession(player, force = true)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            val skipped = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            val nextSongId = mediaItem?.mediaId?.toLongOrNull()
            val sameSongQueueUpdate = reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                listeningSession?.songId == nextSongId
            if (sameSongQueueUpdate) {
                player?.let { activePlayer ->
                    listeningSession?.lastPositionMs = activePlayer.currentPosition.coerceAtLeast(0L)
                    lastListeningSampleElapsedMs = SystemClock.elapsedRealtime()
                }
            } else {
                finishListeningSession(completed = completed, skipped = skipped)
                player?.takeIf { it.isPlaying }?.let(::beginListeningSession)
            }
            syncPlayback(player)
            player?.let { persistPlaybackSession(it, force = true) }
            scheduleEmbeddedReplayGainForCurrentQueue()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val activePlayer = player ?: return
            if (isPlaying && lastPlaybackError != null) clearPlaybackError()
            syncPlayback(activePlayer)
            if (isPlaying) beginListeningSession(activePlayer) else {
                sampleListeningSession(activePlayer, force = true, allowPausedFinal = true)
                flushListeningProgress()
            }
        }

        @OptIn(UnstableApi::class)
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) {
                listeningSession?.let { session ->
                    if (newPosition.mediaItem?.mediaId?.toLongOrNull() == session.songId) {
                        // Seeking within a track changes the baseline but is not counted as listening.
                        session.lastPositionMs = newPosition.positionMs.coerceAtLeast(0L)
                        lastListeningSampleElapsedMs = SystemClock.elapsedRealtime()
                    }
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                player?.let { sampleListeningSession(it, force = true, allowPausedFinal = true) }
                finishListeningSession(completed = true, skipped = false)
                if (radioActive) {
                    radioActive = false
                    player?.let(::syncPlayback)
                }
                maybeAutoplayShuffle()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val failed = player?.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty().ifBlank { "This track" }
            val friendly = when (error.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "$failed is missing. Tunelyst will continue to another track if one is queued."
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "$failed is not accessible. Check file permissions; Tunelyst will continue to another track if one is queued."
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "A temporary read error interrupted $failed. Tunelyst is retrying once before continuing."
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "$failed uses an unsupported format. Tunelyst will continue to another track if one is queued."
                else -> "Couldn't play $failed. Tunelyst is attempting recovery; the playback service will keep handling the queue in the background."
            }
            // Error recovery lives in MusicPlaybackService, so it still works after the Activity's
            // controller/ViewModel detaches. Do not seek here as that can double-skip the queue.
            lastPlaybackError = friendly
            _playbackState.update { it.copy(lastPlaybackError = friendly) }
        }
    }


    internal fun refreshAudioSettingsForOutputDevice() = this.refreshAudioSettingsForOutputDeviceImpl()

    internal fun handleMediaStoreChange() = this.handleMediaStoreChangeImpl()

    internal fun hasAudioReadPermission(): Boolean = this.hasAudioReadPermissionImpl()

    init {
        refreshSavedState(includeListeningStats = true)
        observeHomeRecommendations()
        restoreCachedCatalog()
        viewModelScope.launch {
            while (true) {
                val activePlayer = player
                if (activePlayer != null) {
                    updatePlaybackProgress(activePlayer)
                    sampleListeningSession(activePlayer)
                }
                delay(LISTENING_SAMPLE_TICK_MS)
            }
        }
    }

    internal fun restoreCachedCatalog() = this.restoreCachedCatalogImpl()

    internal fun observeHomeRecommendations() = this.observeHomeRecommendationsImpl()

    internal suspend fun scheduleHomeRecommendations(state: MusicUiState) = this.scheduleHomeRecommendationsImpl(state)

    internal fun createHomeRecommendationInputKey(
        songs: List<Song>,
        favorites: Set<Long>,
        recentlyPlayedIds: List<Long>,
        playCounts: Map<Long, Long>,
        lessLikeArtistNames: Set<String>,
        lessLikeGenreNames: Set<String>,
        hiddenHomeSongIds: Set<Long>,
        savedAtMs: Long,
        genresLoaded: Boolean,
        rotationDay: Long
    ) = HomeRecommendationInputKey(
        songs = songs,
        favoritesFingerprint = homeMixSetFingerprint(favorites),
        recentlyPlayedFingerprint = homeMixOrderedIdFingerprint(recentlyPlayedIds),
        hiddenSongsFingerprint = homeMixSetFingerprint(hiddenHomeSongIds),
        playCountsFingerprint = homeMixPlayCountsFingerprint(playCounts),
        lessLikeArtistsFingerprint = homeMixTasteNamesFingerprint(lessLikeArtistNames),
        lessLikeGenresFingerprint = homeMixTasteNamesFingerprint(lessLikeGenreNames),
        catalogSavedAtMs = savedAtMs,
        genresLoaded = genresLoaded,
        rotationDay = rotationDay
    )

    internal fun isHomeRecommendationCacheCurrent(
        cache: HomeMixCacheSnapshot?,
        inputKey: HomeRecommendationInputKey,
        songLookup: Map<Long, Song>,
        hiddenHomeSongIds: Set<Long>
    ): Boolean = this.isHomeRecommendationCacheCurrentImpl(cache, inputKey, songLookup, hiddenHomeSongIds)

    fun requestOpenNowPlaying() = this.requestOpenNowPlayingImpl()

    fun attachPlayerControllerRetryAction(action: (() -> Unit)?) = this.attachPlayerControllerRetryActionImpl(action)

    fun reportPlayerConnectionFailure() = this.reportPlayerConnectionFailureImpl()

    fun clearPlayerConnectionError() = this.clearPlayerConnectionErrorImpl()

    fun retryPlayerConnection() = this.retryPlayerConnectionImpl()

    fun attachPlayer(newPlayer: Player) = this.attachPlayerImpl(newPlayer)

    fun detachPlayer() = this.detachPlayerImpl()

    internal fun beginListeningSession(activePlayer: Player) = this.beginListeningSessionImpl(activePlayer)

    internal fun sampleListeningSession(
        activePlayer: Player,
        force: Boolean = false,
        allowPausedFinal: Boolean = false
    ) = this.sampleListeningSessionImpl(activePlayer, force, allowPausedFinal)

    internal fun listeningDuration(activePlayer: Player, songId: Long): Long = this.listeningDurationImpl(activePlayer, songId)

    internal fun playCountThresholdMs(session: ActiveListeningSession): Long = this.playCountThresholdMsImpl(session)

    internal fun maybeRecordQualifiedPlay(session: ActiveListeningSession) = this.maybeRecordQualifiedPlayImpl(session)

    internal fun flushListeningProgress() = this.flushListeningProgressImpl()

    internal fun refreshListeningStats(songId: Long) = this.refreshListeningStatsImpl(songId)

    internal fun finishListeningSession(completed: Boolean, skipped: Boolean) = this.finishListeningSessionImpl(completed, skipped)

    internal fun enqueueAudioFeatureAnalysis(song: Song) = this.enqueueAudioFeatureAnalysisImpl(song)

    internal fun enqueueShuffleFeatureWarmup(seeds: List<Song>, candidates: List<Song>) = this.enqueueShuffleFeatureWarmupImpl(seeds, candidates)

    internal fun finishCurrentSessionAsSkip(activePlayer: Player? = player) = this.finishCurrentSessionAsSkipImpl(activePlayer)

    /** Explicit refresh, used by the Rescan controls. */
    fun loadLibrary() = this.loadLibraryImpl()

    /** Restores the cached catalog first, then scans only when MediaStore reports changes (or the cache is old). */
    fun ensureLibraryLoaded() = this.ensureLibraryLoadedImpl()

    internal fun startLibraryScan(showLoading: Boolean) = this.startLibraryScanImpl(showLoading)

    internal fun persistCatalogSnapshot(
        songs: List<Song>,
        revision: String?,
        savedAtMs: Long,
        genresLoaded: Boolean
    ) {
        var persistenceJob: Job? = null
        val job = viewModelScope.launch(context = Dispatchers.IO, start = CoroutineStart.LAZY) {
            runCatching { catalogCache.writeSnapshot(songs, revision, savedAtMs, genresLoaded) }
            withContext(Dispatchers.Main) {
                if (catalogPersistenceJob === persistenceJob) catalogPersistenceJob = null
                if (catalogSavedAtMs == savedAtMs && catalogRevision == revision && !catalogGenresLoaded) {
                    ensureGenreMetadata(songs)
                }
            }
        }
        persistenceJob = job
        catalogPersistenceJob = job
        job.start()
    }

    /** Populate genres after songs are already visible; the provider requires a query per genre. */
    internal fun ensureGenreMetadata(rawCatalogSongs: List<Song>? = null) = this.ensureGenreMetadataImpl(rawCatalogSongs)

    internal fun runQueuedLibraryRescan() = this.runQueuedLibraryRescanImpl()

    internal fun tryRestorePlaybackSession() = this.tryRestorePlaybackSessionImpl()

    internal fun persistPlaybackSession(activePlayer: Player, force: Boolean = false) = this.persistPlaybackSessionImpl(activePlayer, force)

    fun refreshSavedState(includeListeningStats: Boolean = false) = this.refreshSavedStateImpl(includeListeningStats)

    fun toggleFavorite(songId: Long) = this.toggleFavoriteImpl(songId)

    fun createPlaylist(name: String): Playlist? = this.createPlaylistImpl(name)

    fun addSongToPlaylist(playlistId: String, songId: Long) = this.addSongToPlaylistImpl(playlistId, songId)

    /** Adds only durable library IDs and returns the number of temporary tracks skipped. */
    fun addSongsToPlaylist(playlistId: String, songIds: Collection<Long>): Int = this.addSongsToPlaylistImpl(playlistId, songIds)

    fun removeSongFromPlaylist(playlistId: String, songId: Long) = this.removeSongFromPlaylistImpl(playlistId, songId)

    fun deletePlaylist(playlistId: String) = this.deletePlaylistImpl(playlistId)

    fun recordPlayed(songId: Long, countPlay: Boolean = false) = this.recordPlayedImpl(songId, countPlay)

    fun recordSearch(songId: Long) = this.recordSearchImpl(songId)

    fun clearRecentlySearched() = this.clearRecentlySearchedImpl()

    fun setSongHiddenFromHome(songId: Long, hidden: Boolean) = this.setSongHiddenFromHomeImpl(songId, hidden)

    fun setHomeCardHidden(cardId: String, hidden: Boolean) = this.setHomeCardHiddenImpl(cardId, hidden)

    fun toggleLessLikeArtist(song: Song) = this.toggleLessLikeArtistImpl(song)

    fun toggleLessLikeGenres(song: Song) = this.toggleLessLikeGenresImpl(song)

    fun removeLessLikeArtist(name: String) = this.removeLessLikeArtistImpl(name)

    fun removeLessLikeGenre(name: String) = this.removeLessLikeGenreImpl(name)

    fun setEqualizerEnabled(enabled: Boolean) = this.setEqualizerEnabledImpl(enabled)

    fun selectEqualizerPreset(preset: String) = this.selectEqualizerPresetImpl(preset)

    fun setEqualizerBandLevel(index: Int, levelDb: Int) = this.setEqualizerBandLevelImpl(index, levelDb)

    fun setCrossfadeSeconds(seconds: Int) = this.setCrossfadeSecondsImpl(seconds)

    fun setCrossfadeCurve(curve: CrossfadeCurve) = this.setCrossfadeCurveImpl(curve)

    fun setCrossfadeAlbumBoundariesOnly(enabled: Boolean) = this.setCrossfadeAlbumBoundariesOnlyImpl(enabled)

    fun setManualSkipFadeSeconds(seconds: Int) = this.setManualSkipFadeSecondsImpl(seconds)

    fun setThemeMode(mode: AppThemeMode) = this.setThemeModeImpl(mode)

    fun setDynamicBlurEnabled(enabled: Boolean) = this.setDynamicBlurEnabledImpl(enabled)

    fun setHighContrastEnabled(enabled: Boolean) = this.setHighContrastEnabledImpl(enabled)
    fun setReducedMotionEnabled(enabled: Boolean) = this.setReducedMotionEnabledImpl(enabled)
    fun setLargerTouchTargetsEnabled(enabled: Boolean) = this.setLargerTouchTargetsEnabledImpl(enabled)
    fun setListenThresholdSeconds(seconds: Int) = this.setListenThresholdSecondsImpl(seconds)
    fun setRepeatAvoidanceLevel(level: RepeatAvoidanceLevel) = this.setRepeatAvoidanceLevelImpl(level)

    /** Compatibility entry point for callers that still expose the former two-state toggle. */
    fun setFewerRepeatsEnabled(enabled: Boolean) = this.setFewerRepeatsEnabledImpl(enabled)
    fun setHomeSectionHidden(id: String, hidden: Boolean) = this.setHomeSectionHiddenImpl(id, hidden)
    fun moveHomeSection(id: String, direction: Int) = this.moveHomeSectionImpl(id, direction)
    fun restoreHomeSections() = this.restoreHomeSectionsImpl()

    fun setPlaybackSpeed(speed: Float) = this.setPlaybackSpeedImpl(speed)

    fun setPlaybackPitch(pitch: Float) = this.setPlaybackPitchImpl(pitch)

    fun setNormalizeVolume(enabled: Boolean) = this.setNormalizeVolumeImpl(enabled)

    internal fun scheduleEmbeddedReplayGainForCurrentQueue() = this.scheduleEmbeddedReplayGainForCurrentQueueImpl()

    fun setFolderExcluded(folder: String, excluded: Boolean) = this.setFolderExcludedImpl(folder, excluded)

    fun artworkOverrideUri(songId: Long): String? = store.metadataOverride(songId)?.artworkUri

    fun saveSongMetadataOverride(value: SongMetadataOverride, startDeviceFileWrite: Boolean = true) = this.saveSongMetadataOverrideImpl(value, startDeviceFileWrite)

    internal fun releaseArtworkGrantIfUnused(artworkUri: String?) = this.releaseArtworkGrantIfUnusedImpl(artworkUri)

    internal fun saveSuggestedTitleFixes(fixes: List<TitleFixRequest>) = this.saveSuggestedTitleFixesImpl(fixes)

    fun saveCurrentQueue(name: String): SavedQueue? = this.saveCurrentQueueImpl(name)

    fun deleteSavedQueue(id: String) = this.deleteSavedQueueImpl(id)

    fun playSavedQueue(queueId: String) = this.playSavedQueueImpl(queueId)

    fun createSmartPlaylist(name: String, kind: String, value: String = ""): SmartPlaylist? = this.createSmartPlaylistImpl(name, kind, value)

    fun deleteSmartPlaylist(id: String) = this.deleteSmartPlaylistImpl(id)

    fun songsForSmartPlaylist(kind: String, value: String = ""): List<Song> = this.songsForSmartPlaylistImpl(kind, value)

    fun attachDeviceDeleteAuthorizationLauncher(launcher: (IntentSenderRequest) -> Unit) = this.attachDeviceDeleteAuthorizationLauncherImpl(launcher)

    fun onDeviceDeleteAuthorizationResult(approved: Boolean) = this.onDeviceDeleteAuthorizationResultImpl(approved)

    fun startDeviceAudioDeletion(uriStrings: List<String>, skippedCount: Int) = this.startDeviceAudioDeletionImpl(uriStrings, skippedCount)

    fun clearDeviceDeleteMessage() = this.clearDeviceDeleteMessageImpl()

    fun attachDeviceRenameAuthorizationLauncher(launcher: (IntentSenderRequest) -> Unit) = this.attachDeviceRenameAuthorizationLauncherImpl(launcher)

    fun onDeviceRenameAuthorizationResult(approved: Boolean) = this.onDeviceRenameAuthorizationResultImpl(approved)

    fun attachDeviceTagWriteAuthorizationLauncher(launcher: (IntentSenderRequest) -> Unit) = this.attachDeviceTagWriteAuthorizationLauncherImpl(launcher)

    fun onDeviceTagWriteAuthorizationResult(approved: Boolean) = this.onDeviceTagWriteAuthorizationResultImpl(approved)

    fun reportDeviceTagWritePermissionDenied() = this.reportDeviceTagWritePermissionDeniedImpl()

    fun startDeviceAudioTagWrite(songId: Long) = this.startDeviceAudioTagWriteImpl(songId)

    fun clearEmbeddedTagWriteMessage(expectedMessage: String) = this.clearEmbeddedTagWriteMessageImpl(expectedMessage)

    internal fun startSuggestedTitleFixes(fixes: List<TitleFixRequest>, legacyStoragePermissionGranted: Boolean = true) = this.startSuggestedTitleFixesImpl(fixes, legacyStoragePermissionGranted)

    fun clearTitleFixMessage() = this.clearTitleFixMessageImpl()

    fun exportBackup(uri: Uri) = this.exportBackupImpl(uri)

    fun importBackup(uri: Uri) = this.importBackupImpl(uri)


    fun clearOperationMessage() = this.clearOperationMessageImpl()
    fun clearPlaybackError() = this.clearPlaybackErrorImpl()

    fun clearSmartShuffleCompletionPrompt() = this.clearSmartShuffleCompletionPromptImpl()

    internal fun dispatchQueueTransition(
        activePlayer: Player,
        request: PlaybackTransitionRequest,
        expectedGeneration: Long,
        fallback: () -> Unit
    ) = this.dispatchQueueTransitionImpl(activePlayer, request, expectedGeneration, fallback)

    internal fun dispatchInQueueTransition(
        activePlayer: Player,
        targetIndex: Int,
        fallback: () -> Unit
    ) = this.dispatchInQueueTransitionImpl(activePlayer, targetIndex, fallback)

    fun playFrom(
        queue: List<Song>,
        index: Int,
        shuffle: Boolean? = null,
        autoShuffleAtEnd: Boolean = false,
        preserveOrder: Boolean = false,
        smartShuffle: Boolean = false,
        radioSession: Boolean = false,
        promptForSmartShuffleOnCompletion: Boolean = false
    ) = this.playFromImpl(queue, index, shuffle, autoShuffleAtEnd, preserveOrder, smartShuffle, radioSession, promptForSmartShuffleOnCompletion)

    /** The legacy Shuffle entry point now uses Tunelyst's local, taste-aware Smart Shuffle. */
    fun playShuffled(songs: List<Song>) = playSmartShuffle(songs)

    /**
     * Activates Smart Shuffle without restarting the current track. The existing song keeps playing;
     * a freshly selected, diverse queue replaces only the items after it.
     */
    fun playSmartShuffle(songs: List<Song>) = this.playSmartShuffleImpl(songs)

    /** Home exposes Smart Shuffle as an on/off control. */
    fun toggleSmartShuffle(songs: List<Song>) = this.toggleSmartShuffleImpl(songs)

    internal fun startSmartShuffleNow(songs: List<Song>) = this.startSmartShuffleNowImpl(songs)

    /**
     * In Very Few mode, defer the last 24 distinct starts whenever any other candidate exists.
     * If the available pool consists entirely of recent tracks, fall back rather than dead-ending.
     */
    internal fun repeatAvoidanceCandidates(
        songs: List<Song>,
        excludedIds: Set<Long> = emptySet()
    ): List<Song> = this.repeatAvoidanceCandidatesImpl(songs, excludedIds)

    internal fun buildSmartShuffleQueue(
        songs: List<Song>,
        excludedIds: Set<Long> = emptySet(),
        contextSeed: Song? = null
    ): List<Song> = this.buildSmartShuffleQueueImpl(songs, excludedIds, contextSeed)

    /** Build an offline radio queue from local metadata, listening history, and cached features. */
    fun startRadio(seed: Song) = startRadioFromLocalSongs(seed, null)

    /** Start a Home station using only the station's on-device track pool. */
    fun startRadioMix(seed: Song, stationSongs: List<Song>) = startRadioFromLocalSongs(seed, stationSongs)

    internal fun startRadioFromLocalSongs(seed: Song, scopedSongs: List<Song>?) = this.startRadioFromLocalSongsImpl(seed, scopedSongs)

    /** Replace only the queue around a radio seed that is already playing, avoiding a re-prepare gap. */
    internal fun replaceRadioQueueKeepingCurrent(activePlayer: Player, suggestions: List<Song>) = this.replaceRadioQueueKeepingCurrentImpl(activePlayer, suggestions)

    /** Turn Radio off and discard its generated upcoming tracks, keeping explicit queued-next adds. */
    fun stopRadio() = this.stopRadioImpl()

    internal fun recentPlayPenalty(rank: Int?): Float = this.recentPlayPenaltyImpl(rank)

    internal fun styleAwareRecentPenalty(rank: Int?): Float = this.styleAwareRecentPenaltyImpl(rank)

    internal fun localAudioTasteProfile(
        state: MusicUiState,
        songsById: Map<Long, Song>
    ): LocalAudioTasteProfile = this.localAudioTasteProfileImpl(state, songsById)

    internal fun audioFeatureSimilarity(
        candidate: LocalAudioFeatures,
        profile: LocalAudioTasteProfile
    ): Float = this.audioFeatureSimilarityImpl(candidate, profile)

    internal fun lifetimePlayPenalty(song: Song): Float = this.lifetimePlayPenaltyImpl(song)

    internal fun localTastePenalty(song: Song): Float = this.localTastePenaltyImpl(song)

    internal fun reservoirSample(songs: List<Song>, excludedIds: Set<Long>, limit: Int): List<Song> = this.reservoirSampleImpl(songs, excludedIds, limit)

    internal fun selectDiverseCandidates(
        candidates: List<Song>,
        limit: Int,
        score: (Song) -> Float
    ): List<Song> = this.selectDiverseCandidatesImpl(candidates, limit, score)

    fun playQueueItem(index: Int) = this.playQueueItemImpl(index)

    fun acceptSharedAudioUri(uri: Uri) = acceptSharedAudioUris(listOf(uri))

    fun acceptSharedAudioUris(uris: List<Uri>) = this.acceptSharedAudioUrisImpl(uris)

    internal fun tryPlayPendingSharedUris() = this.tryPlayPendingSharedUrisImpl()

    internal fun stopShuffleContinuation(activePlayer: Player?) = this.stopShuffleContinuationImpl(activePlayer)

    internal fun maybeAutoplayShuffle() = this.maybeAutoplayShuffleImpl()

    /** Queue after the current song and any earlier Play Next additions, preserving add order. */
    fun playNext(song: Song) = this.playNextImpl(song)

    /** Compatibility entry point: all manual queue additions now use ordered Play Next. */
    fun playLast(song: Song) = playNext(song)

    fun moveQueueItem(fromIndex: Int, toIndex: Int) = this.moveQueueItemImpl(fromIndex, toIndex)

    fun removeQueueItem(index: Int) = this.removeQueueItemImpl(index)

    fun clearUpcomingQueue() = this.clearUpcomingQueueImpl()

    fun togglePlayPause() = this.togglePlayPauseImpl()

    fun skipNext() = this.skipNextImpl()

    fun skipPrevious() = this.skipPreviousImpl()

    fun seekTo(positionMs: Long) = this.seekToImpl(positionMs)

    fun toggleShuffle() = this.toggleShuffleImpl()

    internal fun enableSmartShuffleForCurrentTrack(songs: List<Song>) = this.enableSmartShuffleForCurrentTrackImpl(songs)

    fun cycleRepeatMode() = this.cycleRepeatModeImpl()

    internal fun restoreSleepTimerUiState() = this.restoreSleepTimerUiStateImpl()

    internal fun observeSleepTimerUi(endElapsedMs: Long, fadeOutSeconds: Int) = this.observeSleepTimerUiImpl(endElapsedMs, fadeOutSeconds)

    fun startSleepTimer(minutes: Int, fadeOutSeconds: Int = 0) = this.startSleepTimerImpl(minutes, fadeOutSeconds)

    fun cancelSleepTimer() = this.cancelSleepTimerImpl()

    internal fun updatePlaybackProgress(activePlayer: Player) = this.updatePlaybackProgressImpl(activePlayer)

    internal fun syncPlayback(activePlayer: Player?) = this.syncPlaybackImpl(activePlayer)

    /** Keep every Media3 queue slot represented in UI state, even if its catalog row disappeared. */
    internal fun playbackPlaceholder(mediaItem: MediaItem, queueIndex: Int): Song = this.playbackPlaceholderImpl(mediaItem, queueIndex)

    internal fun toMediaItem(song: Song): MediaItem = this.toMediaItemImpl(song)

    override fun onCleared() {
        player?.let { sampleListeningSession(it, force = true) }
        finishListeningSession(completed = false, skipped = false)
        // Do not cancel/clear the sleep timer here: the MediaSessionService continues playback
        // and owns its deadline/fade even after this Activity-scoped ViewModel is cleared.
        mediaStoreRefreshHandler.removeCallbacks(mediaStoreRefreshRunnable)
        runCatching { getApplication<Application>().contentResolver.unregisterContentObserver(mediaStoreObserver) }
        catalogPersistenceJob?.cancel()
        genreEnrichmentJob?.cancel()
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        deviceDeleteCoordinator?.cancel()
        deviceDeleteCoordinator = null
        deviceDeleteAuthorizationLauncher = null
        deviceRenameCoordinator?.cancel()
        deviceRenameCoordinator = null
        deviceRenameAuthorizationLauncher = null
        deviceTagWriteCoordinator?.cancel()
        deviceTagWriteCoordinator = null
        deviceTagWriteAuthorizationLauncher = null
        audioFeatureRequests.close()
        super.onCleared()
    }


}

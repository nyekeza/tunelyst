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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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
import com.tunelyst.music.data.normalizeTasteKey
import com.tunelyst.music.data.normalizedGenreKeys
import com.tunelyst.music.data.matchesGenreRule
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
import com.tunelyst.music.data.queueNextInsertionIndex
import com.tunelyst.music.data.withOverride
import com.tunelyst.music.playback.ReplayGainRuntimeCache
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
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln


data class MusicUiState(
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    val isLibraryReady: Boolean = false,
    val favorites: Set<Long> = emptySet(),
    val playCounts: Map<Long, Long> = emptyMap(),
    val listeningStats: Map<Long, TrackListeningStats> = emptyMap(),
    val lessLikeArtistNames: Set<String> = emptySet(),
    val lessLikeGenreNames: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val recentlyPlayedIds: List<Long> = emptyList(),
    val recentlySearchedIds: List<Long> = emptyList(),
    val hiddenHomeSongIds: Set<Long> = emptySet(),
    val hiddenHomeCardIds: Set<String> = emptySet(),
    val excludedFolders: Set<String> = emptySet(),
    val allFolders: List<String> = emptyList(),
    val savedQueues: List<SavedQueue> = emptyList(),
    val smartPlaylists: List<SmartPlaylist> = emptyList(),
    val removedTrackCount: Int = 0,
    val inaccessibleTrackCount: Int = 0,
    val homeRecommendations: HomeRecommendations? = null,
    val operationMessage: String? = null,
    val errorMessage: String? = null
)

private data class LocalAudioTasteProfile(
    val tempoBpm: Float?,
    val tempoConfidence: Float,
    val energy: Float?
)

private data class ActiveListeningSession(
    val songId: Long,
    var durationMs: Long,
    var lastPositionMs: Long,
    var listenedMs: Long = 0L,
    var pendingPersistedMs: Long = 0L,
    var qualifiedPlayRecorded: Boolean = false
)

/** UI preview shown immediately during an outgoing manual-skip fade; cleared on player transition. */
private data class PendingSkipPreview(
    val sourceMediaId: String,
    val targetMediaId: String,
    val targetIndex: Int,
    val generation: Long
)

private data class CachedCatalogRestore(
    val snapshot: LibraryCatalogSnapshot?,
    val songs: List<Song>,
    val allSongs: List<Song>,
    val excludedFolders: Set<String>,
    val songIds: Set<Long>,
    val songLookup: Map<Long, Song>,
    val homeMixCacheSnapshot: HomeMixCacheSnapshot?
)

private class HomeRecommendationInputKey(
    val songs: List<Song>,
    val favoritesFingerprint: Long,
    val recentlyPlayedFingerprint: Long,
    val hiddenSongsFingerprint: Long,
    val playCountsFingerprint: Long,
    val lessLikeArtistsFingerprint: Long,
    val lessLikeGenresFingerprint: Long,
    val catalogSavedAtMs: Long,
    val genresLoaded: Boolean,
    val rotationDay: Long
) {
    override fun equals(other: Any?): Boolean = other is HomeRecommendationInputKey &&
        songs === other.songs &&
        favoritesFingerprint == other.favoritesFingerprint &&
        recentlyPlayedFingerprint == other.recentlyPlayedFingerprint &&
        hiddenSongsFingerprint == other.hiddenSongsFingerprint &&
        playCountsFingerprint == other.playCountsFingerprint &&
        lessLikeArtistsFingerprint == other.lessLikeArtistsFingerprint &&
        lessLikeGenresFingerprint == other.lessLikeGenresFingerprint &&
        catalogSavedAtMs == other.catalogSavedAtMs &&
        genresLoaded == other.genresLoaded &&
        rotationDay == other.rotationDay

    override fun hashCode(): Int = System.identityHashCode(songs) * 31 +
        favoritesFingerprint.hashCode() * 17 + recentlyPlayedFingerprint.hashCode() * 13 +
        hiddenSongsFingerprint.hashCode() * 7 + playCountsFingerprint.hashCode() * 5 +
        lessLikeArtistsFingerprint.hashCode() * 3 + lessLikeGenresFingerprint.hashCode() +
        catalogSavedAtMs.hashCode() + genresLoaded.hashCode() + rotationDay.hashCode()
}

private data class HomeRecommendationInputSnapshot(
    val isLibraryReady: Boolean,
    val songs: List<Song>,
    val favorites: Set<Long>,
    val recentlyPlayedIds: List<Long>,
    val playCounts: Map<Long, Long>,
    val lessLikeArtistNames: Set<String>,
    val lessLikeGenreNames: Set<String>,
    val hiddenHomeSongIds: Set<Long>,
    val catalogSavedAtMs: Long,
    val genresLoaded: Boolean,
    val rotationDay: Long
) {
    fun hasSameInputsAs(other: HomeRecommendationInputSnapshot): Boolean =
        isLibraryReady == other.isLibraryReady &&
            songs === other.songs &&
            favorites == other.favorites &&
            recentlyPlayedIds == other.recentlyPlayedIds &&
            playCounts == other.playCounts &&
            lessLikeArtistNames == other.lessLikeArtistNames &&
            lessLikeGenreNames == other.lessLikeGenreNames &&
            hiddenHomeSongIds == other.hiddenHomeSongIds &&
            catalogSavedAtMs == other.catalogSavedAtMs &&
            genresLoaded == other.genresLoaded &&
            rotationDay == other.rotationDay
}

private data class HomeRecommendationObservation(
    val state: MusicUiState,
    val inputs: HomeRecommendationInputSnapshot
)

data class PlaybackUiState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val promptForSmartShuffleOnCompletion: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val smartShuffleEnabled: Boolean = false,
    val radioActive: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val playNextSongIds: Set<Long> = emptySet(),
    val queuedSongIds: Set<Long> = emptySet(),
    val playbackSpeed: Float = 1f,
    val playbackPitch: Float = 1f,
    val lastPlaybackError: String? = null
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MusicRepository(application)
    private val store = LibraryStore(application)
    private val catalogCache = LibraryCatalogCache(application)
    private val homeMixCache = HomeMixCache(application)
    /** Raw MediaStore catalog; unlike the UI list this still includes tracks in excluded folders. */
    private var rawCatalogSongs: List<Song> = emptyList()
    private val lowMemoryDevice = run {
        val activityManager = application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        isTunelystLowMemoryDevice(
            platformLowRam = activityManager?.isLowRamDevice == true,
            memoryClassMb = activityManager?.memoryClass ?: Int.MAX_VALUE,
            maxHeapBytes = Runtime.getRuntime().maxMemory()
        )
    }
    private val audioFeatureStore = LocalAudioFeatureStore(application)
    private val audioFeatureRequests = Channel<Song>(Channel.UNLIMITED)
    private val queuedAudioFeatureIds = ConcurrentHashMap.newKeySet<Long>()
    private val backupStore = PersonalDataBackup(application)
    private val audioSettingsStore = AudioSettingsStore(application)
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mediaStoreRefreshHandler = Handler(Looper.getMainLooper())
    private val mediaStoreRefreshRunnable = Runnable { handleMediaStoreChange() }
    private val mediaStoreObserver = object : ContentObserver(mediaStoreRefreshHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            mediaStoreRefreshHandler.removeCallbacks(mediaStoreRefreshRunnable)
            mediaStoreRefreshHandler.postDelayed(mediaStoreRefreshRunnable, MEDIA_STORE_CHANGE_DEBOUNCE_MS)
        }
    }
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { refreshAudioSettingsForOutputDevice() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { refreshAudioSettingsForOutputDevice() }
    }
    private val _audioSettings = MutableStateFlow(audioSettingsStore.read())
    val audioSettings: StateFlow<AudioSettingsState> = _audioSettings.asStateFlow()
    private val appSettingsStore = AppSettingsStore(application)
    private val _appSettings = MutableStateFlow(appSettingsStore.read())
    val appSettings: StateFlow<AppSettingsState> = _appSettings.asStateFlow()
    private val _uiState = MutableStateFlow(MusicUiState())
    val uiState: StateFlow<MusicUiState> = _uiState.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()
    private val _openNowPlayingRequest = MutableStateFlow(0L)
    val openNowPlayingRequest: StateFlow<Long> = _openNowPlayingRequest.asStateFlow()
    private val _playerConnectionError = MutableStateFlow<String?>(null)
    val playerConnectionError: StateFlow<String?> = _playerConnectionError.asStateFlow()
    private var playerConnectionRetryAction: (() -> Unit)? = null
    private var deviceDeleteCoordinator: DeviceAudioDeleteCoordinator? = null
    private var deviceDeleteAuthorizationLauncher: ((IntentSenderRequest) -> Unit)? = null
    private val _deviceDeleteMessage = MutableStateFlow<String?>(null)
    val deviceDeleteMessage: StateFlow<String?> = _deviceDeleteMessage.asStateFlow()
    private var deviceRenameCoordinator: DeviceAudioRenameCoordinator? = null
    private var deviceRenameAuthorizationLauncher: ((IntentSenderRequest) -> Unit)? = null
    private val _titleFixMessage = MutableStateFlow<String?>(null)
    val titleFixMessage: StateFlow<String?> = _titleFixMessage.asStateFlow()
    private var deviceTagWriteCoordinator: DeviceAudioTagWriteCoordinator? = null
    private var deviceTagWriteAuthorizationLauncher: ((IntentSenderRequest) -> Unit)? = null
    private val _embeddedTagWriteMessage = MutableStateFlow<String?>(null)
    val embeddedTagWriteMessage: StateFlow<String?> = _embeddedTagWriteMessage.asStateFlow()

    private val _sleepTimerMinutes = MutableStateFlow<Int?>(null)
    val sleepTimerMinutes: StateFlow<Int?> = _sleepTimerMinutes.asStateFlow()
    private val _sleepTimerFadeOutSeconds = MutableStateFlow(0)
    val sleepTimerFadeOutSeconds: StateFlow<Int> = _sleepTimerFadeOutSeconds.asStateFlow()

    private var player: Player? = null
    private val playNextIndicatorIds = mutableSetOf<Long>()
    private val queuedIndicatorIds = mutableSetOf<Long>()
    private var sleepTimerJob: Job? = null
    private val sharedSongs = mutableMapOf<Long, Song>()
    private var pendingSharedUris: List<Uri> = emptyList()
    private var resolvingSharedUris = false
    private var autoShuffleOnCompletion = false
    private var promptForSmartShuffleOnCompletion = false
    private var radioActive = false
    private var autoShuffleJob: Job? = null
    private var replayGainEnrichmentJob: Job? = null
    private var playbackGeneration = 0L
    private var smartShuffleActive = false
    private var preserveCurrentQueueOrder = false
    private var playbackRestoreAttempted = false
    private var lastSessionWriteElapsedMs = 0L
    private var lastSavedSessionIds: List<Long> = emptyList()
    private var lastSavedSessionPositionMs = -1L
    private var lastSavedShuffle = false
    private var lastSavedSmartShuffle = false
    private var lastSavedPreserveQueueOrder = false
    private var lastSavedRepeatMode = Player.REPEAT_MODE_OFF
    private var cachedSongLookup: Map<Long, Song> = emptyMap()
    private var lastSuccessfulSongIds: Set<Long> = emptySet()
    private var lastPlaybackError: String? = null
    private var queuedLibraryRescan = false
    private var queuedAutomaticLibraryCheck = false
    private var queuedMediaStoreRefresh = false
    private var catalogSnapshotLoaded = false
    private var catalogRevision: String? = null
    private var catalogSavedAtMs = 0L
    private var catalogGenresLoaded = false
    private var libraryScanJob: Job? = null
    private var libraryFreshnessCheckJob: Job? = null
    private var catalogPersistenceJob: Job? = null
    private var genreEnrichmentJob: Job? = null
    private var homeRecommendationsJob: Job? = null
    private var homeRecommendationInputKey: HomeRecommendationInputKey? = null
    private var manualSkipFadeJob: Job? = null
    private var pendingSkipPreview: PendingSkipPreview? = null
    private var listeningSession: ActiveListeningSession? = null
    private var lastListeningSampleElapsedMs = 0L
    private var manualSkipFadeGeneration = 0L
    private var manualSkipFadeOriginalVolume = 1f

    init {
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
                audioFeatureStore.loadCache()
                for (song in audioFeatureRequests) {
                    try {
                        audioFeatureStore.analyze(song)
                    } catch (_: Exception) {
                        // Unsupported decoders/providers simply leave this track on metadata-based ranking.
                    } finally {
                        queuedAudioFeatureIds.remove(song.id)
                    }
                }
            }
        }
    }

    private val playerListener = object : Player.Listener {
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
            syncPlayback(player)
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

        @UnstableApi
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


    private fun refreshAudioSettingsForOutputDevice() {
        _audioSettings.value = audioSettingsStore.read()
    }

    private fun handleMediaStoreChange() {
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

    private fun hasAudioReadPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return getApplication<Application>().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    init {
        refreshSavedState(includeListeningStats = true)
        observeHomeRecommendations()
        restoreCachedCatalog()
        viewModelScope.launch {
            while (true) {
                val activePlayer = player
                syncPlayback(activePlayer)
                activePlayer?.let(::sampleListeningSession)
                delay(LISTENING_SAMPLE_TICK_MS)
            }
        }
    }

    private fun restoreCachedCatalog() {
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

    private fun observeHomeRecommendations() {
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

    private suspend fun scheduleHomeRecommendations(state: MusicUiState) {
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

    private fun createHomeRecommendationInputKey(
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

    private fun isHomeRecommendationCacheCurrent(
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

    fun requestOpenNowPlaying() {
        _openNowPlayingRequest.update { it + 1L }
    }

    fun attachPlayerControllerRetryAction(action: (() -> Unit)?) {
        playerConnectionRetryAction = action
    }

    fun reportPlayerConnectionFailure() {
        _playerConnectionError.value = "Couldn't connect to playback controls. Audio playback may be unavailable."
    }

    fun clearPlayerConnectionError() {
        _playerConnectionError.value = null
    }

    fun retryPlayerConnection() {
        _playerConnectionError.value = null
        playerConnectionRetryAction?.invoke()
            ?: run { reportPlayerConnectionFailure() }
    }

    fun attachPlayer(newPlayer: Player) {
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

    fun detachPlayer() {
        cancelManualSkipFade(restoreVolume = true)
        player?.let { activePlayer ->
            sampleListeningSession(activePlayer, force = true)
            flushListeningProgress()
            persistPlaybackSession(activePlayer, force = true)
            activePlayer.removeListener(playerListener)
        }
        // Keep the active listening session across Activity/controller recreation so it is not
        // counted as a new start when the same track is still playing in the service.
        player = null
    }

    private fun beginListeningSession(activePlayer: Player) {
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

    private fun sampleListeningSession(
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

    private fun listeningDuration(activePlayer: Player, songId: Long): Long {
        val duration = activePlayer.duration
        return if (duration > 0L && duration != C.TIME_UNSET) duration
        else cachedSongLookup[songId]?.durationMs?.coerceAtLeast(0L) ?: 0L
    }

    private fun playCountThresholdMs(session: ActiveListeningSession): Long {
        val configuredMs = _appSettings.value.listenThresholdSeconds.coerceIn(5, 120) * 1_000L
        val duration = session.durationMs
        // Short tracks use an 80% threshold so a complete short recording can still count.
        val shortTrackThreshold = if (duration > 0L && duration < configuredMs) (duration * 0.8f).toLong().coerceAtLeast(1L) else configuredMs
        return shortTrackThreshold
    }

    private fun maybeRecordQualifiedPlay(session: ActiveListeningSession) {
        if (session.qualifiedPlayRecorded || session.listenedMs < playCountThresholdMs(session)) return
        session.qualifiedPlayRecorded = true
        val updatedCounts = store.recordQualifiedPlay(session.songId)
        _uiState.update { it.copy(playCounts = updatedCounts) }
        refreshListeningStats(session.songId)
    }

    private fun flushListeningProgress() {
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

    private fun refreshListeningStats(songId: Long) {
        if (songId < 0L) return
        val latest = store.listeningStatsFor(songId)
        _uiState.update { current ->
            current.copy(listeningStats = current.listeningStats + (songId to latest))
        }
    }

    private fun finishListeningSession(completed: Boolean, skipped: Boolean) {
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

    private fun enqueueAudioFeatureAnalysis(song: Song) {
        if (lowMemoryDevice || song.id < 0L || audioFeatureStore.featuresFor(song) != null) return
        if (!queuedAudioFeatureIds.add(song.id)) return
        if (audioFeatureRequests.trySend(song).isFailure) queuedAudioFeatureIds.remove(song.id)
    }

    private fun enqueueShuffleFeatureWarmup(seeds: List<Song>, candidates: List<Song>) {
        (seeds.take(5) + candidates.shuffled().take(5))
            .distinctBy(Song::id)
            .take(MAX_SHUFFLE_FEATURE_WARMUP)
            .forEach(::enqueueAudioFeatureAnalysis)
    }

    private fun finishCurrentSessionAsSkip(activePlayer: Player? = player) {
        activePlayer?.let { sampleListeningSession(it, force = true, allowPausedFinal = true) }
        finishListeningSession(completed = false, skipped = true)
    }

    /** Explicit refresh, used by the Rescan controls. */
    fun loadLibrary() {
        if (!catalogSnapshotLoaded) {
            queuedLibraryRescan = true
            return
        }
        startLibraryScan(showLoading = true)
    }

    /** Restores the cached catalog first, then scans only when MediaStore reports changes (or the cache is old). */
    fun ensureLibraryLoaded() {
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

    private fun startLibraryScan(showLoading: Boolean) {
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

    private fun persistCatalogSnapshot(
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
    private fun ensureGenreMetadata(rawCatalogSongs: List<Song>? = null) {
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
                    this@MusicViewModel.rawCatalogSongs = enrichedCatalog
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

    private fun runQueuedLibraryRescan() {
        if (!queuedLibraryRescan || libraryScanJob?.isActive == true) return
        queuedLibraryRescan = false
        startLibraryScan(showLoading = true)
    }

    private fun tryRestorePlaybackSession() {
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

    private fun persistPlaybackSession(activePlayer: Player, force: Boolean = false) {
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

    fun refreshSavedState(includeListeningStats: Boolean = false) {
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

    fun toggleFavorite(songId: Long) {
        val favorites = store.toggleFavorite(songId)
        _uiState.update { it.copy(favorites = favorites) }
    }

    fun createPlaylist(name: String): Playlist? {
        val created = store.createPlaylist(name)
        refreshSavedState()
        return created
    }

    fun addSongToPlaylist(playlistId: String, songId: Long) {
        addSongsToPlaylist(playlistId, listOf(songId))
    }

    /** Adds only durable library IDs and returns the number of temporary tracks skipped. */
    fun addSongsToPlaylist(playlistId: String, songIds: Collection<Long>): Int {
        val distinctIds = songIds.distinct()
        val persistentIds = distinctIds.filter { it >= 0L }
        val skippedCount = distinctIds.size - persistentIds.size
        if (persistentIds.isNotEmpty()) store.addSongsToPlaylist(playlistId, persistentIds)
        refreshSavedState()
        return skippedCount
    }

    fun removeSongFromPlaylist(playlistId: String, songId: Long) {
        store.removeSongFromPlaylist(playlistId, songId)
        refreshSavedState()
    }

    fun deletePlaylist(playlistId: String) {
        store.deletePlaylist(playlistId)
        refreshSavedState()
    }

    fun recordPlayed(songId: Long, countPlay: Boolean = false) {
        store.recordPlayed(songId, incrementCount = countPlay)
        _uiState.update { it.copy(recentlyPlayedIds = store.recentlyPlayed()) }
    }

    fun recordSearch(songId: Long) {
        store.recordSearch(songId)
        _uiState.update { it.copy(recentlySearchedIds = store.recentlySearched()) }
    }

    fun clearRecentlySearched() {
        store.clearRecentlySearched()
        _uiState.update { it.copy(recentlySearchedIds = emptyList()) }
    }

    fun setSongHiddenFromHome(songId: Long, hidden: Boolean) {
        val hiddenIds = store.setSongHiddenFromHome(songId, hidden)
        _uiState.update { it.copy(hiddenHomeSongIds = hiddenIds) }
    }

    fun setHomeCardHidden(cardId: String, hidden: Boolean) {
        val hiddenIds = store.setHomeCardHidden(cardId, hidden)
        _uiState.update { it.copy(hiddenHomeCardIds = hiddenIds) }
    }

    fun toggleLessLikeArtist(song: Song) {
        val names = store.toggleLessLikeArtist(song.artist)
        _uiState.update { it.copy(lessLikeArtistNames = names) }
    }

    fun toggleLessLikeGenres(song: Song) {
        val names = store.toggleLessLikeGenres(normalizedGenreKeys(song.genre))
        _uiState.update { it.copy(lessLikeGenreNames = names) }
    }

    fun removeLessLikeArtist(name: String) {
        _uiState.update { it.copy(lessLikeArtistNames = store.setLessLikeArtist(name, less = false)) }
    }

    fun removeLessLikeGenre(name: String) {
        _uiState.update { it.copy(lessLikeGenreNames = store.setLessLikeGenre(name, less = false)) }
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        _audioSettings.value = audioSettingsStore.setEqualizerEnabled(enabled)
    }

    fun selectEqualizerPreset(preset: String) {
        _audioSettings.value = audioSettingsStore.selectPreset(preset)
    }

    fun setEqualizerBandLevel(index: Int, levelDb: Int) {
        _audioSettings.value = audioSettingsStore.setBandLevel(index, levelDb)
    }

    fun setCrossfadeSeconds(seconds: Int) {
        _audioSettings.value = audioSettingsStore.setCrossfadeSeconds(seconds)
    }

    fun setCrossfadeCurve(curve: CrossfadeCurve) {
        _audioSettings.value = audioSettingsStore.setCrossfadeCurve(curve)
    }

    fun setCrossfadeAlbumBoundariesOnly(enabled: Boolean) {
        _audioSettings.value = audioSettingsStore.setCrossfadeAlbumBoundariesOnly(enabled)
    }

    fun setManualSkipFadeSeconds(seconds: Int) {
        _audioSettings.value = audioSettingsStore.setManualSkipFadeSeconds(seconds)
        if (seconds <= 0) {
            cancelManualSkipFade(restoreVolume = true)
            syncPlayback(player)
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        _appSettings.value = appSettingsStore.setThemeMode(mode)
    }

    fun setDynamicBlurEnabled(enabled: Boolean) {
        _appSettings.value = appSettingsStore.setDynamicBlurEnabled(enabled)
    }

    fun setHighContrastEnabled(enabled: Boolean) { _appSettings.value = appSettingsStore.setHighContrastEnabled(enabled) }
    fun setReducedMotionEnabled(enabled: Boolean) { _appSettings.value = appSettingsStore.setReducedMotionEnabled(enabled) }
    fun setLargerTouchTargetsEnabled(enabled: Boolean) { _appSettings.value = appSettingsStore.setLargerTouchTargetsEnabled(enabled) }
    fun setListenThresholdSeconds(seconds: Int) { _appSettings.value = appSettingsStore.setListenThresholdSeconds(seconds) }
    fun setRepeatAvoidanceLevel(level: RepeatAvoidanceLevel) {
        _appSettings.value = appSettingsStore.setRepeatAvoidanceLevel(level)
    }

    /** Compatibility entry point for callers that still expose the former two-state toggle. */
    fun setFewerRepeatsEnabled(enabled: Boolean) {
        _appSettings.value = appSettingsStore.setFewerRepeatsEnabled(enabled)
    }
    fun setHomeSectionHidden(id: String, hidden: Boolean) { _appSettings.value = appSettingsStore.setHomeSectionHidden(id, hidden) }
    fun moveHomeSection(id: String, direction: Int) { _appSettings.value = appSettingsStore.moveHomeSection(id, direction) }
    fun restoreHomeSections() { _appSettings.value = appSettingsStore.restoreHomeSections() }

    fun setPlaybackSpeed(speed: Float) {
        _audioSettings.value = audioSettingsStore.setPlaybackSpeed(speed)
        player?.setPlaybackParameters(PlaybackParameters(speed, _audioSettings.value.playbackPitch))
    }

    fun setPlaybackPitch(pitch: Float) {
        _audioSettings.value = audioSettingsStore.setPlaybackPitch(pitch)
        player?.setPlaybackParameters(PlaybackParameters(_audioSettings.value.playbackSpeed, pitch))
    }

    fun setNormalizeVolume(enabled: Boolean) {
        _audioSettings.value = audioSettingsStore.setNormalizeVolume(enabled)
        if (enabled) scheduleEmbeddedReplayGainForCurrentQueue()
    }

    private fun scheduleEmbeddedReplayGainForCurrentQueue() {
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

    fun setFolderExcluded(folder: String, excluded: Boolean) {
        val updated = store.setFolderExcluded(folder, excluded)
        _uiState.update { it.copy(excludedFolders = updated) }
        loadLibrary()
    }

    fun artworkOverrideUri(songId: Long): String? = store.metadataOverride(songId)?.artworkUri

    fun saveSongMetadataOverride(value: SongMetadataOverride, startDeviceFileWrite: Boolean = true) {
        // ReplayGain and artwork are not editable as part of this metadata form unless a new cover
        // was explicitly chosen. Preserve existing app-local values when the form supplies null.
        val existingOverride = store.metadataOverride(value.songId)
        val previousArtworkUri = existingOverride?.artworkUri
        val preservedValue = value.copy(
            replayGainDb = value.replayGainDb ?: existingOverride?.replayGainDb,
            artworkUri = value.artworkUri ?: existingOverride?.artworkUri
        )
        store.saveMetadataOverride(preservedValue)
        if (previousArtworkUri != preservedValue.artworkUri) releaseArtworkGrantIfUnused(previousArtworkUri)
        val updatedSongs = _uiState.value.songs.map { song ->
            if (song.id == preservedValue.songId) song.withOverride(preservedValue) else song
        }
        cachedSongLookup = cachedSongLookup + updatedSongs.associateBy(Song::id) + sharedSongs.values.associateBy(Song::id)
        _uiState.update { it.copy(songs = updatedSongs) }
        syncPlayback(player)
        if (startDeviceFileWrite) {
            startDeviceAudioTagWrite(preservedValue.songId)
        } else {
            _embeddedTagWriteMessage.value =
                "Correction saved in Tunelyst. Android storage permission is needed to update the device audio file."
        }
    }

    private fun releaseArtworkGrantIfUnused(artworkUri: String?) {
        val uriString = artworkUri?.takeIf(String::isNotBlank) ?: return
        if (store.metadataOverrides().values.any { it.artworkUri == uriString }) return
        runCatching {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun saveSuggestedTitleFixes(fixes: List<TitleFixRequest>) {
        val overrides = fixes.distinctBy(TitleFixRequest::songId).associate { fix ->
            val existing = store.metadataOverride(fix.songId) ?: SongMetadataOverride(fix.songId)
            existing.copy(
                title = fix.suggestedTitle,
                artist = fix.suggestedArtist?.takeIf(String::isNotBlank) ?: existing.artist
            ).also(store::saveMetadataOverride).let { fix.songId to it }
        }
        if (overrides.isEmpty()) return
        val updatedSongs = _uiState.value.songs.map { song -> overrides[song.id]?.let(song::withOverride) ?: song }
        cachedSongLookup = cachedSongLookup + updatedSongs.associateBy(Song::id) + sharedSongs.values.associateBy(Song::id)
        val count = overrides.size
        _uiState.update {
            it.copy(
                songs = updatedSongs,
                operationMessage = "Applied reviewed cleanup suggestions to $count ${if (count == 1) "track" else "tracks"} in Tunelyst."
            )
        }
        syncPlayback(player)
    }

    fun saveCurrentQueue(name: String): SavedQueue? {
        val playback = _playbackState.value
        val ids = playback.queue.drop(playback.currentIndex.coerceAtLeast(0)).map(Song::id)
        // Temporary shared tracks have no stable library identity; never save a partial queue silently.
        if (ids.any { it < 0L }) return null
        val saved = store.saveQueue(name, ids)
        if (saved != null) refreshSavedState()
        return saved
    }

    fun deleteSavedQueue(id: String) {
        store.deleteSavedQueue(id)
        refreshSavedState()
    }

    fun playSavedQueue(queueId: String) {
        val saved = store.savedQueues().firstOrNull { it.id == queueId } ?: return
        val byId = cachedSongLookup + _uiState.value.songs.associateBy(Song::id)
        val resolved = saved.songIds.mapNotNull(byId::get)
        if (resolved.isNotEmpty()) playFrom(resolved, 0, preserveOrder = true)
    }

    fun createSmartPlaylist(name: String, kind: String, value: String = ""): SmartPlaylist? {
        val saved = store.saveSmartPlaylist(name, kind, value)
        refreshSavedState()
        return saved
    }

    fun deleteSmartPlaylist(id: String) {
        store.deleteSmartPlaylist(id)
        refreshSavedState()
    }

    fun songsForSmartPlaylist(kind: String, value: String = ""): List<Song> {
        val songs = _uiState.value.songs
        val counts = store.playCounts()
        return when (kind) {
            "recently_added" -> songs.sortedByDescending(Song::dateAddedSeconds).take(100)
            "most_played" -> songs.sortedByDescending { counts[it.id] ?: 0L }.filter { (counts[it.id] ?: 0L) > 0L }.take(100)
            "never_played" -> songs.filter { (counts[it.id] ?: 0L) == 0L }.sortedByDescending(Song::dateAddedSeconds).take(100)
            "artist" -> songs.filter { it.artist.equals(value, ignoreCase = true) }
            "year" -> songs.filter { it.year.toString() == value }
            "genre" -> songs.filter { song -> matchesGenreRule(song.genre, value) }
            else -> emptyList()
        }
    }

    fun attachDeviceDeleteAuthorizationLauncher(launcher: (IntentSenderRequest) -> Unit) {
        deviceDeleteAuthorizationLauncher = launcher
        deviceDeleteCoordinator?.updateAuthorizationLauncher(launcher)
    }

    fun onDeviceDeleteAuthorizationResult(approved: Boolean) {
        deviceDeleteCoordinator?.onAuthorizationResult(approved)
    }

    fun startDeviceAudioDeletion(uriStrings: List<String>, skippedCount: Int) {
        deviceDeleteCoordinator?.cancel()
        _deviceDeleteMessage.value = null
        val uris = uriStrings.map(Uri::parse).distinct()
        val coordinator = DeviceAudioDeleteCoordinator(
            resolver = getApplication<Application>().contentResolver,
            launchAuthorization = { request ->
                deviceDeleteAuthorizationLauncher?.invoke(request)
                    ?: error("Android approval launcher is not attached.")
            },
            onComplete = { result ->
                deviceDeleteCoordinator = null
                loadLibrary()
                _deviceDeleteMessage.value = when {
                    result.cancelled && result.deletedCount == 0 -> "Deletion was cancelled. No audio files were removed."
                    result.cancelled -> "Deletion was cancelled after ${result.deletedCount} audio ${if (result.deletedCount == 1) "file was" else "files were"} removed."
                    result.failedCount == 0 && skippedCount == 0 -> "Permanently deleted ${result.deletedCount} audio ${if (result.deletedCount == 1) "file" else "files"} from this device."
                    else -> buildString {
                        append("Deleted ${result.deletedCount} audio ${if (result.deletedCount == 1) "file" else "files"}.")
                        if (result.failedCount > 0) append(" ${result.failedCount} could not be deleted or verified.")
                        if (skippedCount > 0) append(" $skippedCount shared or unsupported ${if (skippedCount == 1) "track was" else "tracks were"} skipped.")
                    }
                }
            }
        )
        deviceDeleteCoordinator = coordinator
        coordinator.start(uris)
    }

    fun clearDeviceDeleteMessage() {
        _deviceDeleteMessage.value = null
    }

    fun attachDeviceRenameAuthorizationLauncher(launcher: (IntentSenderRequest) -> Unit) {
        deviceRenameAuthorizationLauncher = launcher
        deviceRenameCoordinator?.updateAuthorizationLauncher(launcher)
    }

    fun onDeviceRenameAuthorizationResult(approved: Boolean) {
        deviceRenameCoordinator?.onAuthorizationResult(approved)
    }

    fun attachDeviceTagWriteAuthorizationLauncher(launcher: (IntentSenderRequest) -> Unit) {
        deviceTagWriteAuthorizationLauncher = launcher
        deviceTagWriteCoordinator?.updateAuthorizationLauncher(launcher)
    }

    fun onDeviceTagWriteAuthorizationResult(approved: Boolean) {
        deviceTagWriteCoordinator?.onAuthorizationResult(approved)
    }

    fun reportDeviceTagWritePermissionDenied() {
        _embeddedTagWriteMessage.value =
            "Correction saved in Tunelyst only. Android storage permission was denied, so the audio file wasn't changed."
    }

    fun startDeviceAudioTagWrite(songId: Long) {
        val song = _uiState.value.songs.firstOrNull { it.id == songId }
        val override = store.metadataOverride(songId)
        if (song == null || override == null) {
            _embeddedTagWriteMessage.value =
                "Correction saved in Tunelyst only. The device audio file couldn't be found for tag writing."
            return
        }
        if (song.id < 0L || song.uri.scheme != "content" || song.uri.authority != "media") {
            _embeddedTagWriteMessage.value =
                "Correction saved in Tunelyst only. This track isn't an Android MediaStore audio item, so its device file can't be written directly."
            return
        }

        val originalSong = rawCatalogSongs.firstOrNull { it.id == songId }
        val artworkUri = override.artworkUri
            ?.takeIf { originalSong == null || it != originalSong.artworkUri?.toString() }
            ?.let(Uri::parse)

        deviceTagWriteCoordinator?.cancel()
        _embeddedTagWriteMessage.value = "Writing tag correction to the device audio file…"
        lateinit var coordinator: DeviceAudioTagWriteCoordinator
        coordinator = DeviceAudioTagWriteCoordinator(
            resolver = getApplication<Application>().contentResolver,
            song = song,
            catalogSong = originalSong,
            metadataOverride = override,
            replacementArtworkUri = artworkUri,
            launchAuthorization = { request ->
                deviceTagWriteAuthorizationLauncher?.invoke(request)
                    ?: error("Android write-approval launcher is not attached.")
            },
            onComplete = { outcome ->
                if (deviceTagWriteCoordinator === coordinator) deviceTagWriteCoordinator = null
                _embeddedTagWriteMessage.value = outcome.message
                if (outcome.fileChanged) loadLibrary()
            }
        )
        deviceTagWriteCoordinator = coordinator
        coordinator.start()
    }

    fun clearEmbeddedTagWriteMessage(expectedMessage: String) {
        if (_embeddedTagWriteMessage.value == expectedMessage) _embeddedTagWriteMessage.value = null
    }

    internal fun startSuggestedTitleFixes(fixes: List<TitleFixRequest>, legacyStoragePermissionGranted: Boolean = true) {
        val uniqueFixes = fixes.distinctBy(TitleFixRequest::songId)
        if (uniqueFixes.isEmpty()) return
        deviceRenameCoordinator?.cancel()
        _titleFixMessage.value = null
        val legacyDeviceWriteNeeded = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            uniqueFixes.any { it.mediaUriString != null }
        if (legacyDeviceWriteNeeded && !legacyStoragePermissionGranted) {
            saveSuggestedTitleFixes(uniqueFixes)
            loadLibrary()
            _titleFixMessage.value = "Applied reviewed cleanup suggestions to ${uniqueFixes.size} ${if (uniqueFixes.size == 1) "track" else "tracks"} in Tunelyst. Android storage permission was not granted, so embedded tags and device filenames were left unchanged."
            return
        }

        val coordinator = DeviceAudioRenameCoordinator(
            resolver = getApplication<Application>().contentResolver,
            launchAuthorization = { request ->
                deviceRenameAuthorizationLauncher?.invoke(request)
                    ?: error("Android write-approval launcher is not attached.")
            },
            onComplete = { result ->
                deviceRenameCoordinator = null
                saveSuggestedTitleFixes(uniqueFixes)
                loadLibrary()
                val deviceFileCount = uniqueFixes.count { it.mediaUriString != null }
                val renameableCount = uniqueFixes.count { fix ->
                    fix.mediaUriString != null && fix.currentFileName != fix.newFileName
                }
                _titleFixMessage.value = buildString {
                    append("Applied reviewed cleanup suggestions to ${uniqueFixes.size} ${if (uniqueFixes.size == 1) "track" else "tracks"} in Tunelyst.")
                    if (deviceFileCount > 0) {
                        append(" Wrote reviewed TITLE/ARTIST tags to ${result.titleTagWrittenCount} of $deviceFileCount device ${if (deviceFileCount == 1) "audio file" else "audio files"}.")
                        if (result.titleTagUnsupportedCount > 0) append(" ${result.titleTagUnsupportedCount} file ${if (result.titleTagUnsupportedCount == 1) "format doesn't" else "formats don't"} support embedded metadata writing.")
                        if (result.titleTagFailedCount > 0) append(" ${result.titleTagFailedCount} embedded metadata ${if (result.titleTagFailedCount == 1) "could" else "could"} not be saved or verified.")
                    }
                    if (renameableCount > 0) {
                        append(" Renamed ${result.renamedCount} of $renameableCount device ${if (renameableCount == 1) "file" else "files"}.")
                        if (result.renameFailedCount > 0) append(" ${result.renameFailedCount} ${if (result.renameFailedCount == 1) "filename could" else "filenames could"} not be changed.")
                    } else if (result.unchangedCount > 0) {
                        append(" ${result.unchangedCount} device ${if (result.unchangedCount == 1) "file already had" else "files already had"} the suggested filename.")
                    }
                    if (result.cancelled) append(" Android write approval was declined; the remaining device files were left unchanged.")
                    if (result.appOnlyCount > 0) {
                        append(" ${result.appOnlyCount} shared or unsupported ${if (result.appOnlyCount == 1) "track received" else "tracks received"} an app-only metadata correction; embedded tags and filenames couldn't be changed.")
                    }
                }
            }
        )
        deviceRenameCoordinator = coordinator
        coordinator.start(uniqueFixes)
    }

    fun clearTitleFixMessage() {
        _titleFixMessage.value = null
    }

    fun exportBackup(uri: Uri) {
        val songsSnapshot = rawCatalogSongs.ifEmpty { _uiState.value.songs }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val json = backupStore.exportJson(songsSnapshot)
                getApplication<Application>().contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
                    ?: error("Couldn't open the selected backup destination.")
            }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(operationMessage = result.fold({ "Backup saved. Audio, device-specific folder paths, and picked artwork images were not copied." }, { "Backup failed: ${it.message ?: "storage is unavailable"}" })) }
            }
        }
    }

    fun importBackup(uri: Uri) {
        val songsSnapshot = rawCatalogSongs.ifEmpty { _uiState.value.songs }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val oldArtworkUris = store.metadataOverrides().values.mapNotNull(SongMetadataOverride::artworkUri).toSet()
                val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= PersonalDataBackup.MAX_BACKUP_BYTES) { "The selected backup is too large." }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                } ?: error("Couldn't read the selected backup file.")
                val imported = backupStore.importJson(text, songsSnapshot)
                oldArtworkUris.forEach(::releaseArtworkGrantIfUnused)
                imported
            }
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    refreshSavedState(includeListeningStats = true)
                    loadLibrary()
                }
                val message = result.fold(
                    { imported ->
                        buildString {
                            append("Personal data restored. Audio files were not copied; folder exclusions and picked artwork URIs were not restored.")
                            if (imported.unmatchedTrackCount > 0) append(" ${imported.unmatchedTrackCount} track references could not be matched to this library.")
                            if (imported.usedLegacyMediaStoreIds) append(" This older backup uses device-local IDs; verify its track links.")
                        }
                    },
                    { "Restore failed: ${it.message ?: "invalid backup"}" }
                )
                _uiState.update { it.copy(operationMessage = message) }
                _appSettings.value = appSettingsStore.read()
                _audioSettings.value = audioSettingsStore.read()
                player?.setPlaybackParameters(PlaybackParameters(_audioSettings.value.playbackSpeed, _audioSettings.value.playbackPitch))
                if (_audioSettings.value.normalizeVolume) scheduleEmbeddedReplayGainForCurrentQueue()
            }
        }
    }


    fun clearOperationMessage() { _uiState.update { it.copy(operationMessage = null) } }
    fun clearPlaybackError() {
        lastPlaybackError = null
        _playbackState.update { it.copy(lastPlaybackError = null) }
    }

    fun clearSmartShuffleCompletionPrompt() {
        promptForSmartShuffleOnCompletion = false
        _playbackState.update { it.copy(promptForSmartShuffleOnCompletion = false) }
    }

    fun playFrom(
        queue: List<Song>,
        index: Int,
        shuffle: Boolean? = null,
        autoShuffleAtEnd: Boolean = false,
        preserveOrder: Boolean = false,
        smartShuffle: Boolean = false,
        radioSession: Boolean = false,
        promptForSmartShuffleOnCompletion: Boolean = false
    ) {
        if (queue.isEmpty()) return
        cancelManualSkipFade(restoreVolume = true)
        radioActive = radioSession && !smartShuffle
        playNextIndicatorIds.clear()
        queuedIndicatorIds.clear()
        if (shuffle == true) {
            playSmartShuffle(queue)
            return
        }
        clearPlaybackError()
        autoShuffleJob?.cancel()
        autoShuffleJob = null
        playbackGeneration++
        smartShuffleActive = smartShuffle
        preserveCurrentQueueOrder = preserveOrder
        autoShuffleOnCompletion = autoShuffleAtEnd || smartShuffle
        this.promptForSmartShuffleOnCompletion = promptForSmartShuffleOnCompletion && !autoShuffleOnCompletion && !smartShuffle
        val safeIndex = index.coerceIn(0, queue.lastIndex)
        val target = queue[safeIndex]
        if (listeningSession?.songId?.let { it != target.id } == true) finishCurrentSessionAsSkip()
        if (target.id < 0L) store.clearPlaybackSession()
        val alphabeticalOrder = compareBy<Song> { it.title.trim().lowercase() }
            .thenBy { it.artist.trim().lowercase() }
            .thenBy { it.album.trim().lowercase() }
        val expandedQueue = if (queue.size == 1 && !preserveOrder) {
            val remaining = _uiState.value.songs.distinctBy(Song::id)
                .filterNot { it.id == target.id }
                .sortedWith(alphabeticalOrder)
            listOf(target) + remaining
        } else queue
        val playbackQueue = if (preserveOrder) expandedQueue else expandedQueue.sortedWith(alphabeticalOrder)
        val playbackIndex = if (queue.size == 1 && !preserveOrder) 0 else
            playbackQueue.indexOfFirst { it.id == target.id }.takeIf { it >= 0 } ?: safeIndex
        val items = playbackQueue.map(::toMediaItem)
        player?.let { activePlayer ->
            activePlayer.shuffleModeEnabled = false
            // Repeat-one/all would prevent STATE_ENDED, so the Search/share auto-shuffle handoff
            // could never run. These one-shot queues must finish before Smart Shuffle takes over.
            if (autoShuffleOnCompletion || radioActive) activePlayer.repeatMode = Player.REPEAT_MODE_OFF
            activePlayer.setMediaItems(items, playbackIndex, C.TIME_UNSET)
            activePlayer.prepare()
            activePlayer.play()
            recordPlayed(target.id, countPlay = false)
            syncPlayback(activePlayer)
            persistPlaybackSession(activePlayer, force = true)
        }
        scheduleEmbeddedReplayGainForCurrentQueue()
    }

    /** The legacy Shuffle entry point now uses Tunelyst's local, taste-aware Smart Shuffle. */
    fun playShuffled(songs: List<Song>) = playSmartShuffle(songs)

    /**
     * Activates Smart Shuffle without restarting the current track. The existing song keeps playing;
     * a freshly selected, diverse queue replaces only the items after it.
     */
    fun playSmartShuffle(songs: List<Song>) {
        cancelManualSkipFade(restoreVolume = true)
        clearSmartShuffleCompletionPrompt()
        radioActive = false
        playNextIndicatorIds.clear()
        queuedIndicatorIds.clear()
        val catalog = songs.ifEmpty { _uiState.value.songs }
        if (catalog.isEmpty()) return
        val activePlayer = player
        val currentIndex = activePlayer?.currentMediaItemIndex ?: C.INDEX_UNSET
        val hasCurrentTrack = activePlayer != null &&
            currentIndex in 0 until activePlayer.mediaItemCount &&
            activePlayer.playbackState != Player.STATE_ENDED
        if (hasCurrentTrack) {
            enableSmartShuffleForCurrentTrack(catalog)
        } else {
            startSmartShuffleNow(catalog)
        }
    }

    /** Home exposes Smart Shuffle as an on/off control. */
    fun toggleSmartShuffle(songs: List<Song>) {
        if (smartShuffleActive || player?.shuffleModeEnabled == true) toggleShuffle()
        else playSmartShuffle(songs)
    }

    private fun startSmartShuffleNow(songs: List<Song>) {
        val ordered = buildSmartShuffleQueue(songs)
        if (ordered.isEmpty()) {
            stopShuffleContinuation(player)
            return
        }
        playFrom(ordered, 0, shuffle = false, autoShuffleAtEnd = true, preserveOrder = true, smartShuffle = true)
    }

    /**
     * In Very Few mode, defer the last 24 distinct starts whenever any other candidate exists.
     * If the available pool consists entirely of recent tracks, fall back rather than dead-ending.
     */
    private fun repeatAvoidanceCandidates(
        songs: List<Song>,
        excludedIds: Set<Long> = emptySet()
    ): List<Song> {
        val available = songs.distinctBy(Song::id).filterNot { it.id in excludedIds }
        if (_appSettings.value.repeatAvoidanceLevel != RepeatAvoidanceLevel.VERY_FEW) return available
        val recentIds = _uiState.value.recentlyPlayedIds.take(VERY_FEW_RECENT_COOLDOWN_SIZE).toSet()
        val notRecentlyPlayed = available.filterNot { it.id in recentIds }
        return notRecentlyPlayed.ifEmpty { available }
    }

    private fun buildSmartShuffleQueue(
        songs: List<Song>,
        excludedIds: Set<Long> = emptySet(),
        contextSeed: Song? = null
    ): List<Song> {
        if (songs.isEmpty()) return emptyList()
        val state = _uiState.value
        val candidateSongs = repeatAvoidanceCandidates(songs, excludedIds)
        if (candidateSongs.isEmpty()) return emptyList()
        val songsById = candidateSongs.associateBy(Song::id)
        val tasteSongsById = (state.songs + songs + listOfNotNull(contextSeed)).associateBy(Song::id)
        val recentRanks = state.recentlyPlayedIds.withIndex().associate { it.value to it.index }
        val favoriteSongs = candidateSongs.filter { it.id in state.favorites }
        // Favorites can inform taste, but must not occupy most of Smart Shuffle's candidate pool.
        // A small rotating sample leaves room for discovery from the full local library.
        val favoriteSeedSongs = reservoirSample(
            favoriteSongs,
            excludedIds,
            minOf(MAX_FAVORITE_SHUFFLE_SEEDS, favoriteSongs.size)
        )
        val recentSeedSongs = state.recentlyPlayedIds.take(24).mapNotNull(songsById::get)
        val seedSongs = (listOfNotNull(contextSeed?.takeIf { it.id in songsById }) + recentSeedSongs + favoriteSeedSongs)
            .filterNot { it.id in excludedIds }
            .distinctBy(Song::id)
        val discoveryPool = reservoirSample(
            candidateSongs,
            excludedIds + seedSongs.map(Song::id).toSet(),
            (MAX_DISCOVERY_CANDIDATES - seedSongs.size.coerceAtMost(MAX_DISCOVERY_CANDIDATES)).coerceAtLeast(0)
        )
        val candidates = (seedSongs.take(MAX_DISCOVERY_CANDIDATES) + discoveryPool)
            .distinctBy(Song::id)
            .take(MAX_DISCOVERY_CANDIDATES)
        enqueueShuffleFeatureWarmup(listOfNotNull(contextSeed) + seedSongs, discoveryPool)
        val featuresById = candidates.mapNotNull { song ->
            audioFeatureStore.featuresFor(song)?.let { song.id to it }
        }.toMap()
        val audioProfile = localAudioTasteProfile(state, tasteSongsById)
        val contextProfile = contextSeed?.let(audioFeatureStore::featuresFor)?.let {
            LocalAudioTasteProfile(it.tempoBpm, it.tempoConfidence, it.energy)
        }
        val contextArtistKey = contextSeed?.let { normalizeTasteKey(it.artist) }
        val contextGenres = contextSeed?.let { normalizedGenreKeys(it.genre).toSet() }.orEmpty()
        val artistAffinity = HashMap<String, Float>()
        val genreAffinity = HashMap<String, Float>()
        state.recentlyPlayedIds.take(40).forEachIndexed { index, id ->
            val seed = tasteSongsById[id] ?: return@forEachIndexed
            val weight = 5.5f / (1f + index * 0.24f)
            val artistKey = normalizeTasteKey(seed.artist)
            artistAffinity[artistKey] = (artistAffinity[artistKey] ?: 0f) + weight
            normalizedGenreKeys(seed.genre).forEach { genre ->
                genreAffinity[genre] = (genreAffinity[genre] ?: 0f) + weight * 0.65f
            }
        }
        favoriteSeedSongs.forEach { seed ->
            val artistKey = normalizeTasteKey(seed.artist)
            artistAffinity[artistKey] = (artistAffinity[artistKey] ?: 0f) + 0.45f
            normalizedGenreKeys(seed.genre).forEach { genre ->
                genreAffinity[genre] = (genreAffinity[genre] ?: 0f) + 0.28f
            }
        }
        return selectDiverseCandidates(candidates, SMART_SHUFFLE_QUEUE_SIZE) { song ->
            val artistKey = normalizeTasteKey(song.artist)
            val songGenres = normalizedGenreKeys(song.genre)
            val artistAffinityScore = artistAffinity[artistKey] ?: 0f
            val genreAffinityScore = songGenres.sumOf { (genreAffinity[it] ?: 0f).toDouble() }.toFloat()
            val acousticMatch = featuresById[song.id]?.let { audioFeatureSimilarity(it, audioProfile) } ?: 0f
            val contextAcousticMatch = contextProfile?.let { profile ->
                featuresById[song.id]?.let { audioFeatureSimilarity(it, profile) } ?: 0f
            } ?: 0f
            val contextArtistMatch = if (contextArtistKey != null && artistKey == contextArtistKey) 2.2f else 0f
            val contextGenreMatch = songGenres.count { it in contextGenres } * 0.8f
            (if (song.id in state.favorites) 0.75f else 0f) +
                contextArtistMatch + contextGenreMatch + contextAcousticMatch * 2.2f +
                artistAffinityScore * 0.42f + genreAffinityScore * 0.30f + acousticMatch * 2.8f -
                styleAwareRecentPenalty(recentRanks[song.id]) +
                lifetimePlayPenalty(song) + localTastePenalty(song) +
                kotlin.random.Random.nextFloat() * 1.5f
        }
    }

    /** Build an offline radio queue from local metadata, listening history, and cached features. */
    fun startRadio(seed: Song) = startRadioFromLocalSongs(seed, null)

    /** Start a Home station using only the station's on-device track pool. */
    fun startRadioMix(seed: Song, stationSongs: List<Song>) = startRadioFromLocalSongs(seed, stationSongs)

    private fun startRadioFromLocalSongs(seed: Song, scopedSongs: List<Song>?) {
        cancelManualSkipFade(restoreVolume = true)
        val librarySongs = _uiState.value.songs
        val stationPool = scopedSongs.orEmpty().distinctBy(Song::id)
        val localSongs = if (stationPool.size >= 2 && stationPool.any { it.id == seed.id }) stationPool else librarySongs
        val radioSeed = localSongs.firstOrNull { it.id == seed.id } ?: seed
        val sameArtist = localSongs.asSequence()
            .filter { it.id != radioSeed.id && it.artist.equals(radioSeed.artist, ignoreCase = true) }
            .take(MAX_RADIO_ARTIST_CANDIDATES)
            .toList()
        val includedIds = (sameArtist.map(Song::id) + radioSeed.id).toSet()
        val sampled = reservoirSample(localSongs, includedIds, MAX_DISCOVERY_CANDIDATES - sameArtist.size)
        val candidates = repeatAvoidanceCandidates((sameArtist + sampled).distinctBy(Song::id))
        val recentRanks = _uiState.value.recentlyPlayedIds.withIndex().associate { it.value to it.index }
        val favorites = _uiState.value.favorites
        enqueueShuffleFeatureWarmup(listOf(radioSeed), candidates)
        val seedFeatures = audioFeatureStore.featuresFor(radioSeed)
        val candidateFeatures = candidates.mapNotNull { song ->
            audioFeatureStore.featuresFor(song)?.let { song.id to it }
        }.toMap()
        val seedProfile = seedFeatures?.let { LocalAudioTasteProfile(it.tempoBpm, it.tempoConfidence, it.energy) }
        val artistKey = normalizeTasteKey(radioSeed.artist)
        val albumKey = normalizeTasteKey(radioSeed.album)
        val suggestions = selectDiverseCandidates(candidates, RADIO_QUEUE_SIZE - 1) { song ->
            val acousticMatch = if (seedProfile == null) 0f else {
                candidateFeatures[song.id]?.let { audioFeatureSimilarity(it, seedProfile) } ?: 0f
            }
            (if (normalizeTasteKey(song.artist) == artistKey) 8f else 0f) +
                (if (normalizeTasteKey(song.album) == albumKey) 4f else 0f) +
                acousticMatch * 3.2f +
                (if (song.id in favorites) 2.5f else 0f) -
                styleAwareRecentPenalty(recentRanks[song.id]) +
                lifetimePlayPenalty(song) + localTastePenalty(song) +
                kotlin.random.Random.nextFloat() * 1.25f
        }
        val radioQueue = listOf(radioSeed) + suggestions
        val activePlayer = player
        val currentIndex = activePlayer?.currentMediaItemIndex ?: C.INDEX_UNSET
        val isAlreadyPlayingSeed = activePlayer != null &&
            currentIndex in 0 until activePlayer.mediaItemCount &&
            activePlayer.currentMediaItem?.mediaId?.toLongOrNull() == radioSeed.id
        if (isAlreadyPlayingSeed) {
            replaceRadioQueueKeepingCurrent(activePlayer!!, suggestions)
        } else {
            playFrom(radioQueue, 0, shuffle = false, preserveOrder = true, radioSession = true)
        }
    }

    /** Replace only the queue around a radio seed that is already playing, avoiding a re-prepare gap. */
    private fun replaceRadioQueueKeepingCurrent(activePlayer: Player, suggestions: List<Song>) {
        val currentIndex = activePlayer.currentMediaItemIndex
        if (currentIndex !in 0 until activePlayer.mediaItemCount) return
        cancelManualSkipFade(restoreVolume = true)
        clearSmartShuffleCompletionPrompt()
        radioActive = true
        smartShuffleActive = false
        preserveCurrentQueueOrder = true
        autoShuffleOnCompletion = false
        autoShuffleJob?.cancel()
        autoShuffleJob = null
        playbackGeneration++
        playNextIndicatorIds.clear()
        queuedIndicatorIds.clear()
        activePlayer.shuffleModeEnabled = false
        activePlayer.repeatMode = Player.REPEAT_MODE_OFF
        if (currentIndex > 0) activePlayer.removeMediaItems(0, currentIndex)
        val preservedIndex = activePlayer.currentMediaItemIndex
        if (preservedIndex !in 0 until activePlayer.mediaItemCount) return
        if (preservedIndex + 1 < activePlayer.mediaItemCount) {
            activePlayer.removeMediaItems(preservedIndex + 1, activePlayer.mediaItemCount)
        }
        if (suggestions.isNotEmpty()) {
            activePlayer.addMediaItems(preservedIndex + 1, suggestions.map(::toMediaItem))
        }
        activePlayer.play()
        syncPlayback(activePlayer)
        persistPlaybackSession(activePlayer, force = true)
        scheduleEmbeddedReplayGainForCurrentQueue()
    }

    /** Turn Radio off and discard its generated upcoming tracks, keeping explicit queued-next adds. */
    fun stopRadio() {
        cancelManualSkipFade(restoreVolume = true)
        radioActive = false
        val activePlayer = player
        if (activePlayer == null) {
            _playbackState.update { it.copy(radioActive = false) }
            return
        }
        val currentIndex = activePlayer.currentMediaItemIndex
        if (currentIndex in 0 until activePlayer.mediaItemCount) {
            val keepIds = playNextIndicatorIds + queuedIndicatorIds
            for (index in activePlayer.mediaItemCount - 1 downTo currentIndex + 1) {
                val songId = activePlayer.getMediaItemAt(index).mediaId.toLongOrNull()
                if (songId == null || songId !in keepIds) activePlayer.removeMediaItem(index)
            }
        }
        syncPlayback(activePlayer)
        persistPlaybackSession(activePlayer, force = true)
    }

    private fun recentPlayPenalty(rank: Int?): Float {
        val recentRank = rank ?: return 0f
        return if (recentRank < RECENT_TRACK_PENALTY_LIMIT) {
            (RECENT_TRACK_PENALTY_LIMIT - recentRank) * RECENT_TRACK_PENALTY_STEP
        } else 0f
    }

    private fun styleAwareRecentPenalty(rank: Int?): Float {
        val recentRank = rank ?: return 0f
        return when (_appSettings.value.repeatAvoidanceLevel) {
            RepeatAvoidanceLevel.BALANCED -> recentPlayPenalty(recentRank) * 0.38f
            RepeatAvoidanceLevel.FEWER -> recentPlayPenalty(recentRank)
            RepeatAvoidanceLevel.VERY_FEW -> if (recentRank < VERY_FEW_RECENT_PENALTY_LIMIT) {
                (VERY_FEW_RECENT_PENALTY_LIMIT - recentRank) * VERY_FEW_RECENT_PENALTY_STEP
            } else {
                0f
            }
        }
    }

    private fun localAudioTasteProfile(
        state: MusicUiState,
        songsById: Map<Long, Song>
    ): LocalAudioTasteProfile {
        val weighted = ArrayList<Pair<LocalAudioFeatures, Float>>()
        state.recentlyPlayedIds.take(24).forEachIndexed { index, songId ->
            val song = songsById[songId] ?: return@forEachIndexed
            val features = audioFeatureStore.featuresFor(song) ?: return@forEachIndexed
            weighted += features to (1f / (1f + index * 0.24f))
        }
        state.favorites.forEach { songId ->
            val song = songsById[songId] ?: return@forEach
            val features = audioFeatureStore.featuresFor(song) ?: return@forEach
            weighted += features to 0.12f
        }
        if (weighted.isEmpty()) return LocalAudioTasteProfile(null, 0f, null)
        val weightSum = weighted.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(0.0001f)
        val energy = weighted.sumOf { (features, weight) -> features.energy * weight.toDouble() }.toFloat() / weightSum
        val tempoItems = weighted.filter { (features, _) ->
            features.tempoBpm != null && features.tempoConfidence >= MIN_AUDIO_TEMPO_CONFIDENCE
        }
        val tempoWeight = tempoItems.sumOf { (features, weight) ->
            weight * features.tempoConfidence.toDouble()
        }.toFloat()
        val tempo = if (tempoWeight > 0f) {
            tempoItems.sumOf { (features, weight) ->
                features.tempoBpm!!.toDouble() * weight * features.tempoConfidence
            }.toFloat() / tempoWeight
        } else null
        val confidence = if (tempoWeight > 0f) {
            tempoItems.sumOf { (features, weight) ->
                (weight * features.tempoConfidence * features.tempoConfidence).toDouble()
            }.toFloat() / tempoWeight
        } else 0f
        return LocalAudioTasteProfile(tempo, confidence.coerceIn(0f, 1f), energy.coerceIn(0f, 1f))
    }

    private fun audioFeatureSimilarity(
        candidate: LocalAudioFeatures,
        profile: LocalAudioTasteProfile
    ): Float {
        var weightedScore = 0f
        var totalWeight = 0f
        val targetEnergy = profile.energy
        if (targetEnergy != null) {
            val energySimilarity = 1f - abs(candidate.energy - targetEnergy).coerceIn(0f, 1f)
            weightedScore += energySimilarity * 0.42f
            totalWeight += 0.42f
        }
        val targetTempo = profile.tempoBpm
        val candidateTempo = candidate.tempoBpm
        if (targetTempo != null && targetTempo > 0f && candidateTempo != null && candidateTempo > 0f &&
            candidate.tempoConfidence >= MIN_AUDIO_TEMPO_CONFIDENCE
        ) {
            val ratio = candidateTempo / targetTempo
            val octaveAdjustedDistance = minOf(
                abs(ln(ratio)),
                abs(ln(ratio * 2f)),
                abs(ln(ratio * 0.5f))
            )
            val tempoSimilarity = exp(-octaveAdjustedDistance / ln(1.5f)).coerceIn(0f, 1f)
            val confidenceWeight = 0.58f * candidate.tempoConfidence * profile.tempoConfidence
            weightedScore += tempoSimilarity * confidenceWeight
            totalWeight += confidenceWeight
        }
        return if (totalWeight > 0f) (weightedScore / totalWeight).coerceIn(0f, 1f) else 0f
    }

    private fun lifetimePlayPenalty(song: Song): Float {
        val plays = (_uiState.value.playCounts[song.id] ?: 0L).coerceAtLeast(0L)
        val strength = when (_appSettings.value.repeatAvoidanceLevel) {
            RepeatAvoidanceLevel.BALANCED -> 0.38f
            RepeatAvoidanceLevel.FEWER -> 0.9f
            RepeatAvoidanceLevel.VERY_FEW -> 1.5f
        }
        return -ln(1.0 + plays).toFloat() * strength
    }

    private fun localTastePenalty(song: Song): Float {
        val state = _uiState.value
        val artistKey = normalizeTasteKey(song.artist)
        val genreKeys = normalizedGenreKeys(song.genre)
        val dislikesArtist = state.lessLikeArtistNames.any { normalizeTasteKey(it) == artistKey }
        val dislikedGenreCount = genreKeys.count { genre ->
            state.lessLikeGenreNames.any { normalizeTasteKey(it) == genre }
        }
        return (if (dislikesArtist) -32f else 0f) - dislikedGenreCount * 18f
    }

    private fun reservoirSample(songs: List<Song>, excludedIds: Set<Long>, limit: Int): List<Song> {
        if (limit <= 0) return emptyList()
        val sample = ArrayList<Song>(minOf(limit, songs.size))
        var seen = 0
        songs.forEach { song ->
            if (song.id in excludedIds) return@forEach
            seen++
            if (sample.size < limit) sample += song
            else {
                val replacement = kotlin.random.Random.nextInt(seen)
                if (replacement < limit) sample[replacement] = song
            }
        }
        return sample
    }

    private fun selectDiverseCandidates(
        candidates: List<Song>,
        limit: Int,
        score: (Song) -> Float
    ): List<Song> {
        data class RankedSong(val song: Song, val score: Float)
        val remaining = ArrayList<RankedSong>(minOf(candidates.size, MAX_DISCOVERY_CANDIDATES))
        val seen = HashSet<Long>()
        candidates.forEach { song ->
            if (seen.add(song.id)) remaining += RankedSong(song, score(song))
        }
        remaining.sortByDescending(RankedSong::score)
        val chosen = ArrayList<Song>(minOf(limit, remaining.size))
        val artistCounts = HashMap<String, Int>()
        val albumCounts = HashMap<String, Int>()
        val genreCounts = HashMap<String, Int>()
        val repeatBias = when (_appSettings.value.repeatAvoidanceLevel) {
            RepeatAvoidanceLevel.BALANCED -> 0.55f
            RepeatAvoidanceLevel.FEWER -> 1.65f
            RepeatAvoidanceLevel.VERY_FEW -> 3.1f
        }
        while (chosen.size < limit && remaining.isNotEmpty()) {
            val windowSize = minOf(SMART_SHUFFLE_LOOKAHEAD, remaining.size)
            var bestIndex = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (index in 0 until windowSize) {
                val candidate = remaining[index]
                val artist = normalizeTasteKey(candidate.song.artist)
                val album = normalizeTasteKey(candidate.song.album)
                val genreRepeatCount = normalizedGenreKeys(candidate.song.genre).sumOf { genreCounts[it] ?: 0 }
                val diversityPenalty = (artistCounts[artist] ?: 0) * 2.2f * repeatBias +
                    (albumCounts[album] ?: 0) * 1.0f * repeatBias + genreRepeatCount * 1.2f * repeatBias
                val adjustedScore = candidate.score - diversityPenalty
                if (adjustedScore > bestScore) {
                    bestScore = adjustedScore
                    bestIndex = index
                }
            }
            val next = remaining.removeAt(bestIndex).song
            chosen += next
            val artist = normalizeTasteKey(next.artist)
            val album = normalizeTasteKey(next.album)
            artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
            albumCounts[album] = (albumCounts[album] ?: 0) + 1
            normalizedGenreKeys(next.genre).forEach { genre -> genreCounts[genre] = (genreCounts[genre] ?: 0) + 1 }
        }
        return chosen
    }

    fun playQueueItem(index: Int) {
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

    fun acceptSharedAudioUri(uri: Uri) = acceptSharedAudioUris(listOf(uri))

    fun acceptSharedAudioUris(uris: List<Uri>) {
        pendingSharedUris = uris.distinct()
        tryPlayPendingSharedUris()
    }

    private fun tryPlayPendingSharedUris() {
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

    private fun stopShuffleContinuation(activePlayer: Player?) {
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

    private fun maybeAutoplayShuffle() {
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

    /** Queue after the current song and any earlier Play Next additions, preserving add order. */
    fun playNext(song: Song) {
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

    /** Compatibility entry point: all manual queue additions now use ordered Play Next. */
    fun playLast(song: Song) = playNext(song)

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val activePlayer = player ?: return
        val currentIndex = activePlayer.currentMediaItemIndex
        if (fromIndex !in 0 until activePlayer.mediaItemCount ||
            toIndex !in 0 until activePlayer.mediaItemCount ||
            fromIndex <= currentIndex || toIndex <= currentIndex || fromIndex == toIndex
        ) return
        activePlayer.moveMediaItem(fromIndex, toIndex)
        syncPlayback(activePlayer)
    }

    fun removeQueueItem(index: Int) {
        val activePlayer = player ?: return
        if (index !in 0 until activePlayer.mediaItemCount || index <= activePlayer.currentMediaItemIndex) return
        activePlayer.removeMediaItem(index)
        syncPlayback(activePlayer)
    }

    fun clearUpcomingQueue() {
        val activePlayer = player ?: return
        val firstUpcoming = (activePlayer.currentMediaItemIndex + 1).coerceAtLeast(0)
        for (index in activePlayer.mediaItemCount - 1 downTo firstUpcoming) {
            activePlayer.removeMediaItem(index)
        }
        syncPlayback(activePlayer)
    }

    fun togglePlayPause() {
        val activePlayer = player ?: return
        cancelManualSkipFade(restoreVolume = true)
        if (activePlayer.isPlaying) {
            activePlayer.pause()
        } else {
            if (activePlayer.playbackState == Player.STATE_IDLE) activePlayer.prepare()
            activePlayer.play()
        }
        syncPlayback(activePlayer)
    }

    fun skipNext() {
        val activePlayer = player ?: return
        if (activePlayer.hasNextMediaItem()) {
            performManualSkip(activePlayer, activePlayer.nextMediaItemIndex) { activePlayer.seekToNextMediaItem() }
        } else {
            cancelManualSkipFade(restoreVolume = true)
        }
        syncPlayback(activePlayer)
    }

    fun skipPrevious() {
        val activePlayer = player ?: return
        if (activePlayer.currentPosition > 3_000L) {
            cancelManualSkipFade(restoreVolume = true)
            activePlayer.seekTo(0L)
        } else if (activePlayer.hasPreviousMediaItem()) {
            performManualSkip(activePlayer, activePlayer.previousMediaItemIndex) { activePlayer.seekToPreviousMediaItem() }
        } else {
            cancelManualSkipFade(restoreVolume = true)
            activePlayer.seekTo(0L)
        }
        syncPlayback(activePlayer)
    }

    private fun performManualSkip(activePlayer: Player, targetIndex: Int, skip: () -> Unit) {
        cancelManualSkipFade(restoreVolume = true)
        val generation = manualSkipFadeGeneration
        val sourceMediaId = activePlayer.currentMediaItem?.mediaId
        val targetMediaId = targetIndex.takeIf { it in 0 until activePlayer.mediaItemCount }
            ?.let(activePlayer::getMediaItemAt)
            ?.mediaId
        pendingSkipPreview = if (
            sourceMediaId != null && targetMediaId != null && sourceMediaId != targetMediaId
        ) {
            PendingSkipPreview(sourceMediaId, targetMediaId, targetIndex, generation)
        } else {
            null
        }

        val settings = _audioSettings.value
        val durationMs = settings.manualSkipFadeSeconds.coerceIn(0, 5) * 1_000L
        val originalVolume = activePlayer.volume.coerceIn(0f, 1f)
        if (durationMs <= 0L || !activePlayer.isPlaying || originalVolume < 0.98f) {
            finishCurrentSessionAsSkip()
            skip()
            return
        }

        manualSkipFadeOriginalVolume = originalVolume
        manualSkipFadeJob = viewModelScope.launch {
            var transitioned = false
            try {
                val steps = (durationMs / 25L).toInt().coerceAtLeast(1)
                val stepDelay = (durationMs / steps).coerceAtLeast(1L)
                repeat(steps) { index ->
                    if (generation != manualSkipFadeGeneration || player !== activePlayer) return@launch
                    val progress = (index + 1).toFloat() / steps.toFloat()
                    val gain = when (settings.crossfadeCurve) {
                        com.tunelyst.music.data.CrossfadeCurve.EQUAL_POWER -> cos(progress * (PI.toFloat() / 2f)).coerceIn(0f, 1f)
                        com.tunelyst.music.data.CrossfadeCurve.LINEAR -> 1f - progress
                    }
                    activePlayer.volume = originalVolume * gain
                    delay(stepDelay)
                }
                if (generation != manualSkipFadeGeneration || player !== activePlayer) return@launch
                val previousIndex = activePlayer.currentMediaItemIndex
                val previousId = activePlayer.currentMediaItem?.mediaId
                finishCurrentSessionAsSkip()
                skip()
                transitioned = withTimeoutOrNull(1_500L) {
                    while (generation == manualSkipFadeGeneration && player === activePlayer &&
                        activePlayer.currentMediaItemIndex == previousIndex && activePlayer.currentMediaItem?.mediaId == previousId
                    ) delay(20L)
                    activePlayer.currentMediaItemIndex != previousIndex || activePlayer.currentMediaItem?.mediaId != previousId
                } ?: false
                if (!transitioned && generation == manualSkipFadeGeneration && player === activePlayer) {
                    activePlayer.volume = originalVolume
                }
            } finally {
                if (generation == manualSkipFadeGeneration) {
                    manualSkipFadeJob = null
                    if (player === activePlayer) {
                        if (!transitioned) activePlayer.volume = originalVolume
                        if (pendingSkipPreview?.generation == generation) pendingSkipPreview = null
                        syncPlayback(activePlayer)
                    }
                }
            }
        }
    }

    private fun cancelManualSkipFade(restoreVolume: Boolean) {
        val hadActiveFade = manualSkipFadeJob?.isActive == true
        manualSkipFadeGeneration += 1L
        manualSkipFadeJob?.cancel()
        manualSkipFadeJob = null
        pendingSkipPreview = null
        if (restoreVolume && hadActiveFade) player?.volume = manualSkipFadeOriginalVolume
    }

    fun seekTo(positionMs: Long) {
        cancelManualSkipFade(restoreVolume = true)
        player?.seekTo(positionMs.coerceAtLeast(0L))
        syncPlayback(player)
    }

    fun toggleShuffle() {
        val activePlayer = player ?: return
        cancelManualSkipFade(restoreVolume = true)
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

    private fun enableSmartShuffleForCurrentTrack(songs: List<Song>) {
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

    fun cycleRepeatMode() {
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

    private fun restoreSleepTimerUiState() {
        val schedule = audioSettingsStore.sleepTimerSchedule() ?: return
        if (schedule.endElapsedMs <= SystemClock.elapsedRealtime()) return
        observeSleepTimerUi(schedule.endElapsedMs, schedule.fadeOutSeconds)
    }

    private fun observeSleepTimerUi(endElapsedMs: Long, fadeOutSeconds: Int) {
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

    fun startSleepTimer(minutes: Int, fadeOutSeconds: Int = 0) {
        cancelSleepTimer()
        val safeMinutes = minutes.coerceIn(1, 24 * 60)
        val safeFadeSeconds = fadeOutSeconds.coerceIn(0, 120)
        val endElapsedMs = SystemClock.elapsedRealtime() + safeMinutes * 60_000L
        audioSettingsStore.setSleepTimer(endElapsedMs, safeFadeSeconds)
        observeSleepTimerUi(endElapsedMs, safeFadeSeconds)
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        audioSettingsStore.clearSleepTimer()
        _sleepTimerMinutes.value = null
        _sleepTimerFadeOutSeconds.value = 0
    }

    private fun syncPlayback(activePlayer: Player?) {
        if (activePlayer == null) {
            pendingSkipPreview = null
            playNextIndicatorIds.clear()
            queuedIndicatorIds.clear()
            _playbackState.value = PlaybackUiState()
            return
        }
        val songsById = cachedSongLookup.ifEmpty {
            (rawCatalogSongs + _uiState.value.songs + sharedSongs.values).associateBy(Song::id)
        }
        val actualMediaItem = activePlayer.currentMediaItem
        val actualMediaId = actualMediaItem?.mediaId
        pendingSkipPreview?.let { preview ->
            val targetStillQueued = preview.targetIndex in 0 until activePlayer.mediaItemCount &&
                activePlayer.getMediaItemAt(preview.targetIndex).mediaId == preview.targetMediaId
            if (actualMediaId != preview.sourceMediaId || !targetStillQueued) pendingSkipPreview = null
        }
        val preview = pendingSkipPreview
        val effectiveCurrentIndex = preview?.targetIndex ?: activePlayer.currentMediaItemIndex
        val queue = (0 until activePlayer.mediaItemCount).map { index ->
            val item = activePlayer.getMediaItemAt(index)
            item.mediaId.toLongOrNull()?.let(songsById::get) ?: playbackPlaceholder(item, index)
        }
        val currentSong = queue.getOrNull(effectiveCurrentIndex)
        val upcomingSongIds = queue.drop((effectiveCurrentIndex + 1).coerceIn(0, queue.size)).map(Song::id).toSet()
        playNextIndicatorIds.retainAll(upcomingSongIds)
        queuedIndicatorIds.retainAll(upcomingSongIds)
        val rawDuration = activePlayer.duration
        val duration = if (preview == null && rawDuration > 0L && rawDuration != C.TIME_UNSET) rawDuration
        else currentSong?.durationMs ?: 0L
        val position = if (preview != null) 0L else activePlayer.currentPosition.coerceAtLeast(0L)
        _playbackState.value = PlaybackUiState(
            currentSong = currentSong,
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
            currentIndex = effectiveCurrentIndex,
            playNextSongIds = playNextIndicatorIds.toSet(),
            queuedSongIds = queuedIndicatorIds.toSet(),
            playbackSpeed = activePlayer.playbackParameters.speed,
            playbackPitch = activePlayer.playbackParameters.pitch,
            lastPlaybackError = lastPlaybackError
        )
        persistPlaybackSession(activePlayer)
    }

    /** Keep every Media3 queue slot represented in UI state, even if its catalog row disappeared. */
    private fun playbackPlaceholder(mediaItem: MediaItem, queueIndex: Int): Song {
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

    private fun toMediaItem(song: Song): MediaItem {
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

    override fun onCleared() {
        player?.let { sampleListeningSession(it, force = true) }
        finishListeningSession(completed = false, skipped = false)
        cancelManualSkipFade(restoreVolume = true)
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

    private companion object {
        const val LIBRARY_CACHE_MAX_AGE_MS = 12L * 60L * 60L * 1000L
        const val MEDIA_STORE_CHANGE_DEBOUNCE_MS = 1_200L
        const val UNKNOWN_PLAYBACK_ID_BASE = Long.MIN_VALUE + 2_147_483_648L
        const val SESSION_SAVE_INTERVAL_MS = 5_000L
        const val SESSION_SAVE_POSITION_DELTA_MS = 4_000L
        const val LISTENING_SAMPLE_TICK_MS = 400L
        const val LISTENING_SAMPLE_INTERVAL_MS = 900L
        const val LISTENING_SAMPLE_TOLERANCE_MS = 1_500L
        const val LISTENING_PROGRESS_FLUSH_MS = 5_000L
        const val MAX_SAVED_QUEUE = 100
        const val MAX_DISCOVERY_CANDIDATES = 500
        const val MAX_FAVORITE_SHUFFLE_SEEDS = 16
        const val MAX_RADIO_ARTIST_CANDIDATES = 120
        const val SMART_SHUFFLE_QUEUE_SIZE = 40
        const val SMART_SHUFFLE_LOOKAHEAD = 64
        const val MAX_SHUFFLE_FEATURE_WARMUP = 8
        const val MIN_AUDIO_TEMPO_CONFIDENCE = 0.12f
        const val RADIO_QUEUE_SIZE = 30
        const val MAX_REPLAY_GAIN_LOOKAHEAD = 64
        const val RECENT_TRACK_PENALTY_LIMIT = 12
        const val RECENT_TRACK_PENALTY_STEP = 0.6f
        const val VERY_FEW_RECENT_COOLDOWN_SIZE = 24
        const val VERY_FEW_RECENT_PENALTY_LIMIT = 60
        const val VERY_FEW_RECENT_PENALTY_STEP = 0.12f
        const val AUTO_SHUFFLE_RECENT_COOLDOWN_SIZE = 8
    }
}

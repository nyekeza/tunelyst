package com.tunelyst.music.ui

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.tunelyst.music.data.HomeMixCacheSnapshot
import com.tunelyst.music.data.LibraryCatalogSnapshot
import com.tunelyst.music.data.SavedQueue
import com.tunelyst.music.data.SmartPlaylist
import com.tunelyst.music.data.Playlist
import com.tunelyst.music.data.Song
import com.tunelyst.music.data.TrackListeningStats

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

internal data class LocalAudioTasteProfile(
    val tempoBpm: Float?,
    val tempoConfidence: Float,
    val energy: Float?
)

internal data class ActiveListeningSession(
    val songId: Long,
    var durationMs: Long,
    var lastPositionMs: Long,
    var listenedMs: Long = 0L,
    var pendingPersistedMs: Long = 0L,
    var qualifiedPlayRecorded: Boolean = false
)

internal data class CachedCatalogRestore(
    val snapshot: LibraryCatalogSnapshot?,
    val songs: List<Song>,
    val allSongs: List<Song>,
    val excludedFolders: Set<String>,
    val songIds: Set<Long>,
    val songLookup: Map<Long, Song>,
    val homeMixCacheSnapshot: HomeMixCacheSnapshot?
)

internal class HomeRecommendationInputKey(
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

internal data class HomeRecommendationInputSnapshot(
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

internal data class HomeRecommendationObservation(
    val state: MusicUiState,
    val inputs: HomeRecommendationInputSnapshot
)

@OptIn(UnstableApi::class)
data class PlaybackUiState(
    val currentSong: Song? = null,
    val currentMediaId: String? = null,
    /** The next item whose audio is already audible during a two-player crossfade. */
    val crossfadeIncomingSong: Song? = null,
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
) {
    /** Track identity shown in the app; playback controls still target [currentSong] until the fade ends. */
    val displaySong: Song?
        get() = crossfadeIncomingSong ?: currentSong

    /** Queue position shown alongside [displaySong], without changing the actual control target. */
    val displayCurrentIndex: Int
        get() {
            val incoming = crossfadeIncomingSong ?: return currentIndex
            return queue.indices.firstOrNull { it > currentIndex && queue[it].id == incoming.id }
                ?: queue.indices.firstOrNull { it != currentIndex && queue[it].id == incoming.id }
                ?: currentIndex
        }
}

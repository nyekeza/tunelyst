@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.tunelyst.music.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.tunelyst.music.data.SavedQueue
import com.tunelyst.music.data.SmartPlaylist
import com.tunelyst.music.data.SongMetadataOverride
import com.tunelyst.music.data.Playlist
import com.tunelyst.music.data.Song
import com.tunelyst.music.data.withOverride
import com.tunelyst.music.core.logic.matchesGenreRule
import com.tunelyst.music.core.logic.normalizedGenreKeys
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

internal fun MusicViewModel.toggleFavoriteImpl(songId: Long) {
    val favorites = store.toggleFavorite(songId)
    _uiState.update { it.copy(favorites = favorites) }
}

internal fun MusicViewModel.createPlaylistImpl(name: String): Playlist? {
    val created = store.createPlaylist(name)
    refreshSavedState()
    return created
}

internal fun MusicViewModel.addSongToPlaylistImpl(playlistId: String, songId: Long) {
    addSongsToPlaylist(playlistId, listOf(songId))
}

internal fun MusicViewModel.addSongsToPlaylistImpl(playlistId: String, songIds: Collection<Long>): Int {
    val distinctIds = songIds.distinct()
    val persistentIds = distinctIds.filter { it >= 0L }
    val skippedCount = distinctIds.size - persistentIds.size
    if (persistentIds.isNotEmpty()) store.addSongsToPlaylist(playlistId, persistentIds)
    refreshSavedState()
    return skippedCount
}

internal fun MusicViewModel.removeSongFromPlaylistImpl(playlistId: String, songId: Long) {
    store.removeSongFromPlaylist(playlistId, songId)
    refreshSavedState()
}

internal fun MusicViewModel.deletePlaylistImpl(playlistId: String) {
    store.deletePlaylist(playlistId)
    refreshSavedState()
}

internal fun MusicViewModel.recordPlayedImpl(songId: Long, countPlay: Boolean = false) {
    store.recordPlayed(songId, incrementCount = countPlay)
    _uiState.update { it.copy(recentlyPlayedIds = store.recentlyPlayed()) }
}

internal fun MusicViewModel.recordSearchImpl(songId: Long) {
    store.recordSearch(songId)
    _uiState.update { it.copy(recentlySearchedIds = store.recentlySearched()) }
}

internal fun MusicViewModel.clearRecentlySearchedImpl() {
    store.clearRecentlySearched()
    _uiState.update { it.copy(recentlySearchedIds = emptyList()) }
}

internal fun MusicViewModel.setSongHiddenFromHomeImpl(songId: Long, hidden: Boolean) {
    val hiddenIds = store.setSongHiddenFromHome(songId, hidden)
    _uiState.update { it.copy(hiddenHomeSongIds = hiddenIds) }
}

internal fun MusicViewModel.setHomeCardHiddenImpl(cardId: String, hidden: Boolean) {
    val hiddenIds = store.setHomeCardHidden(cardId, hidden)
    _uiState.update { it.copy(hiddenHomeCardIds = hiddenIds) }
}

internal fun MusicViewModel.toggleLessLikeArtistImpl(song: Song) {
    val names = store.toggleLessLikeArtist(song.artist)
    _uiState.update { it.copy(lessLikeArtistNames = names) }
}

internal fun MusicViewModel.toggleLessLikeGenresImpl(song: Song) {
    val names = store.toggleLessLikeGenres(normalizedGenreKeys(song.genre))
    _uiState.update { it.copy(lessLikeGenreNames = names) }
}

internal fun MusicViewModel.removeLessLikeArtistImpl(name: String) {
    _uiState.update { it.copy(lessLikeArtistNames = store.setLessLikeArtist(name, less = false)) }
}

internal fun MusicViewModel.removeLessLikeGenreImpl(name: String) {
    _uiState.update { it.copy(lessLikeGenreNames = store.setLessLikeGenre(name, less = false)) }
}

internal fun MusicViewModel.setFolderExcludedImpl(folder: String, excluded: Boolean) {
    val updated = store.setFolderExcluded(folder, excluded)
    _uiState.update { it.copy(excludedFolders = updated) }
    loadLibrary()
}

internal fun MusicViewModel.saveSongMetadataOverrideImpl(value: SongMetadataOverride, startDeviceFileWrite: Boolean = true) {
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

internal fun MusicViewModel.releaseArtworkGrantIfUnusedImpl(artworkUri: String?) {
    val uriString = artworkUri?.takeIf(String::isNotBlank) ?: return
    if (store.metadataOverrides().values.any { it.artworkUri == uriString }) return
    runCatching {
        getApplication<Application>().contentResolver.releasePersistableUriPermission(
            Uri.parse(uriString),
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
}

internal fun MusicViewModel.saveSuggestedTitleFixesImpl(fixes: List<TitleFixRequest>) {
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

internal fun MusicViewModel.saveCurrentQueueImpl(name: String): SavedQueue? {
    val playback = _playbackState.value
    val ids = playback.queue.drop(playback.currentIndex.coerceAtLeast(0)).map(Song::id)
    // Temporary shared tracks have no stable library identity; never save a partial queue silently.
    if (ids.any { it < 0L }) return null
    val saved = store.saveQueue(name, ids)
    if (saved != null) refreshSavedState()
    return saved
}

internal fun MusicViewModel.deleteSavedQueueImpl(id: String) {
    store.deleteSavedQueue(id)
    refreshSavedState()
}

internal fun MusicViewModel.playSavedQueueImpl(queueId: String) {
    val saved = store.savedQueues().firstOrNull { it.id == queueId } ?: return
    val byId = cachedSongLookup + _uiState.value.songs.associateBy(Song::id)
    val resolved = saved.songIds.mapNotNull(byId::get)
    if (resolved.isNotEmpty()) playFrom(resolved, 0, preserveOrder = true)
}

internal fun MusicViewModel.createSmartPlaylistImpl(name: String, kind: String, value: String = ""): SmartPlaylist? {
    val saved = store.saveSmartPlaylist(name, kind, value)
    refreshSavedState()
    return saved
}

internal fun MusicViewModel.deleteSmartPlaylistImpl(id: String) {
    store.deleteSmartPlaylist(id)
    refreshSavedState()
}

internal fun MusicViewModel.songsForSmartPlaylistImpl(kind: String, value: String = ""): List<Song> {
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

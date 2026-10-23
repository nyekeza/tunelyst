package com.tunelyst.music.ui

import com.tunelyst.music.data.Song

internal data class ArtistGroup(val artist: String, val songs: List<Song>)

internal data class LibraryGroups(
    val albums: List<AlbumGroup>,
    val artists: List<ArtistGroup>,
    val folders: Map<String, List<Song>>
)

internal fun buildLibraryGroups(songs: List<Song>): LibraryGroups {
    val albums = songs.groupBy { it.album to it.albumArtist }.map { (key, tracks) ->
        AlbumGroup(
            key.first,
            key.second,
            tracks.sortedWith(compareBy<Song> { it.discNumber }
                .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                .thenBy { it.title.lowercase() }),
            tracks.maxOfOrNull(Song::year) ?: 0
        )
    }.sortedBy { it.album.lowercase() }
    val artists = songs.groupBy(Song::artist).map { (artist, tracks) ->
        ArtistGroup(artist, tracks.sortedBy { it.title.lowercase() })
    }.sortedBy { it.artist.lowercase() }
    val folders = songs.groupBy { it.folderPath.ifBlank { "Device root" } }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    return LibraryGroups(albums, artists, folders)
}

internal fun sortLibrarySongs(songs: List<Song>, sortMode: String): List<Song> = when (sortMode) {
    "Recently added" -> songs.sortedByDescending(Song::dateAddedSeconds)
    "Artist A–Z" -> songs.sortedBy { it.artist.lowercase() + it.title.lowercase() }
    "Album A–Z" -> songs.sortedBy { it.albumArtist.lowercase() + it.album.lowercase() + it.discNumber + it.trackNumber }
    "Duration" -> songs.sortedByDescending(Song::durationMs)
    else -> songs.sortedBy { it.title.lowercase() }
}

package com.tunelyst.music.ui

import androidx.compose.foundation.layout.size
import com.tunelyst.music.data.Song
import java.util.Locale

data class AlbumGroup(val album: String, val artist: String, val songs: List<Song>, val year: Int = 0)

internal data class AlbumTrackMetadata(
    val id: Long,
    val album: String,
    val artist: String,
    val albumArtist: String,
    val isCompilation: Boolean = false
)

internal fun albumGroupTrackIds(songs: List<AlbumTrackMetadata>, songId: Long): Set<Long> {
    val selected = songs.firstOrNull { it.id == songId } ?: return emptySet()
    val selectedAlbum = normalizeAlbumGroupKey(selected.album)
    val sameAlbum = songs.filter { normalizeAlbumGroupKey(it.album) == selectedAlbum }
    val selectedAlbumArtist = normalizeAlbumGroupKey(selected.albumArtist)
    val selectedArtist = normalizeAlbumGroupKey(selected.artist)
    val sameAlbumArtist = sameAlbum.filter { normalizeAlbumGroupKey(it.albumArtist) == selectedAlbumArtist }
    val sameTrackArtist = sameAlbum.filter { normalizeAlbumGroupKey(it.artist) == selectedArtist }
    val isVariousArtistRelease = selectedAlbumArtist in setOf("various artists", "various")
    val matchingUnknownArtistTags = sameAlbum.filter {
        normalizeAlbumGroupKey(it.albumArtist) in setOf("", "unknown", "unknown artist", "unknown album artist") &&
            normalizeAlbumGroupKey(it.artist) == selectedArtist
    }
    val albumTracks = when {
        selected.isCompilation || isVariousArtistRelease -> sameAlbum
        sameAlbumArtist.size > 1 -> sameAlbumArtist + matchingUnknownArtistTags
        sameTrackArtist.size > 1 -> sameTrackArtist
        sameAlbumArtist.isNotEmpty() -> sameAlbumArtist
        sameTrackArtist.isNotEmpty() -> sameTrackArtist
        else -> listOf(selected)
    }
    return albumTracks.mapTo(LinkedHashSet(), AlbumTrackMetadata::id)
}

private fun normalizeAlbumGroupKey(value: String): String =
    value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

fun albumGroupFor(songs: List<Song>, songId: Long): AlbumGroup? {
    val selected = songs.firstOrNull { it.id == songId } ?: return null
    val selectedTrackIds = albumGroupTrackIds(
        songs.map { AlbumTrackMetadata(it.id, it.album, it.artist, it.albumArtist, it.isCompilation) },
        songId
    )
    val tracks = songs.filter { it.id in selectedTrackIds }
        .sortedWith(compareBy<Song> { it.discNumber }.thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }.thenBy { it.title.lowercase() })
    return AlbumGroup(
        selected.album,
        selected.albumArtist.ifBlank { selected.artist },
        tracks,
        tracks.maxOfOrNull(Song::year) ?: selected.year
    )
}

package com.tunelyst.music.data

import android.net.Uri

/** A local audio file indexed by Android's MediaStore. */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri,
    val artworkUri: Uri?,
    val dateAddedSeconds: Long,
    val trackNumber: Int = 0,
    val albumArtist: String = artist,
    val genre: String = "Unknown genre",
    val year: Int = 0,
    val discNumber: Int = 1,
    val mimeType: String = "",
    val folderPath: String = "",
    val fileName: String = "",
    val sizeBytes: Long = 0L,
    /** Optional ReplayGain track correction in dB; custom values remain app-private. */
    val replayGainDb: Float? = null,
    val isCompilation: Boolean = false
) {
    val formatLabel: String
        get() = mimeType.substringAfter('/', "").substringBefore(';').uppercase().ifBlank {
            fileName.substringAfterLast('.', "").uppercase().ifBlank { "UNKNOWN" }
        }
}

/** Aggregated, device-local listening signals for one MediaStore track. */
data class TrackListeningStats(
    val starts: Long = 0L,
    val listenedMs: Long = 0L,
    val completions: Long = 0L,
    val skips: Long = 0L,
    val qualifiedPlays: Long = 0L,
    val lastListenedPercent: Float = 0f,
    val lastStartedAtMs: Long = 0L
)


data class Playlist(
    val id: String,
    val name: String,
    val songIds: List<Long>
)

data class SavedQueue(
    val id: String,
    val name: String,
    val songIds: List<Long>,
    val savedAtMs: Long
)

data class SmartPlaylist(
    val id: String,
    val name: String,
    val kind: String,
    val value: String = ""
)

data class SongMetadataOverride(
    val songId: Long,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val artworkUri: String? = null,
    val replayGainDb: Float? = null,
    val isCompilation: Boolean? = null
)

fun Song.withOverride(override: SongMetadataOverride?): Song {
    if (override == null) return this
    return copy(
        title = override.title?.takeIf(String::isNotBlank) ?: title,
        artist = override.artist?.takeIf(String::isNotBlank) ?: artist,
        album = override.album?.takeIf(String::isNotBlank) ?: album,
        albumArtist = override.albumArtist?.takeIf(String::isNotBlank) ?: if (override.isCompilation == true) "Various Artists" else albumArtist,
        genre = override.genre?.takeIf(String::isNotBlank) ?: genre,
        year = override.year?.takeIf { it > 0 } ?: year,
        discNumber = override.discNumber?.takeIf { it > 0 } ?: discNumber,
        trackNumber = override.trackNumber?.takeIf { it > 0 } ?: trackNumber,
        artworkUri = override.artworkUri?.let(Uri::parse) ?: artworkUri,
        replayGainDb = override.replayGainDb ?: replayGainDb,
        isCompilation = override.isCompilation ?: isCompilation
    )
}

fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val minutes = seconds / 60L
    val remainingSeconds = seconds % 60L
    return "%d:%02d".format(minutes, remainingSeconds)
}

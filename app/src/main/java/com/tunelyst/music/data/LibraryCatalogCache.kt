package com.tunelyst.music.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/** Compact, app-private snapshot of the last successful MediaStore scan. */
data class LibraryCatalogSnapshot(
    val songs: List<Song>,
    val mediaStoreRevision: String?,
    val savedAtMs: Long,
    val genresLoaded: Boolean
)

/**
 * Keeps a local catalog snapshot so launch can render the last library immediately,
 * without waiting for a new MediaStore scan. The cache contains metadata only.
 */
class LibraryCatalogCache(context: Context) {
    private val atomicFile = AtomicFile(File(context.applicationContext.cacheDir, "tunelyst-library-catalog.bin"))
    private var latestWriteAtMs = 0L
    private var latestGenresLoaded = false

    @Synchronized
    fun readSnapshot(): LibraryCatalogSnapshot? {
        if (!atomicFile.baseFile.exists()) return null
        return runCatching {
            DataInputStream(BufferedInputStream(atomicFile.openRead())).use { input ->
                require(input.readInt() == MAGIC)
                require(input.readInt() == SCHEMA_VERSION)
                val savedAtMs = input.readLong().coerceAtLeast(0L)
                val mediaStoreRevision = if (input.readBoolean()) input.readUTF().takeIf(String::isNotBlank) else null
                val genresLoaded = input.readBoolean()
                val count = input.readInt()
                require(count in 0..MAX_CACHED_SONGS)
                val songs = ArrayList<Song>(count)
                repeat(count) {
                    val id = input.readLong()
                    val title = input.readUTF()
                    val artist = input.readUTF()
                    val album = input.readUTF()
                    val durationMs = input.readLong()
                    val uri = Uri.parse(input.readUTF())
                    val artworkUri = if (input.readBoolean()) Uri.parse(input.readUTF()) else null
                    val dateAddedSeconds = input.readLong()
                    val trackNumber = input.readInt()
                    val albumArtist = input.readUTF()
                    val genre = input.readUTF()
                    val year = input.readInt()
                    val discNumber = input.readInt()
                    val mimeType = input.readUTF()
                    val folderPath = input.readUTF()
                    val fileName = input.readUTF()
                    val sizeBytes = input.readLong()
                    val replayGainDb = if (input.readBoolean()) input.readFloat() else null
                    val isCompilation = input.readBoolean()
                    if (id >= 0L) {
                        songs += Song(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = durationMs,
                            uri = uri,
                            artworkUri = artworkUri,
                            dateAddedSeconds = dateAddedSeconds,
                            trackNumber = trackNumber,
                            albumArtist = albumArtist,
                            genre = genre,
                            year = year,
                            discNumber = discNumber,
                            mimeType = mimeType,
                            folderPath = folderPath,
                            fileName = fileName,
                            sizeBytes = sizeBytes,
                            replayGainDb = replayGainDb,
                            isCompilation = isCompilation
                        )
                    }
                }
                LibraryCatalogSnapshot(songs, mediaStoreRevision, savedAtMs, genresLoaded)
            }
        }.getOrNull()
    }

    @Synchronized
    fun writeSnapshot(songs: List<Song>, mediaStoreRevision: String?, savedAtMs: Long, genresLoaded: Boolean = false) {
        if (savedAtMs < latestWriteAtMs || (savedAtMs == latestWriteAtMs && latestGenresLoaded && !genresLoaded)) return
        var stream: FileOutputStream? = null
        try {
            val outputStream = atomicFile.startWrite()
            stream = outputStream
            val output = DataOutputStream(BufferedOutputStream(outputStream))
            val localSongs = songs.asSequence().filter { it.id >= 0L }.distinctBy(Song::id).take(MAX_CACHED_SONGS).toList()
            output.writeInt(MAGIC)
            output.writeInt(SCHEMA_VERSION)
            output.writeLong(savedAtMs.coerceAtLeast(0L))
            output.writeBoolean(!mediaStoreRevision.isNullOrBlank())
            if (!mediaStoreRevision.isNullOrBlank()) output.writeUTF(mediaStoreRevision)
            output.writeBoolean(genresLoaded)
            output.writeInt(localSongs.size)
            localSongs.forEach { song ->
                output.writeLong(song.id)
                output.writeUTF(song.title)
                output.writeUTF(song.artist)
                output.writeUTF(song.album)
                output.writeLong(song.durationMs)
                output.writeUTF(song.uri.toString())
                output.writeBoolean(song.artworkUri != null)
                song.artworkUri?.let { output.writeUTF(it.toString()) }
                output.writeLong(song.dateAddedSeconds)
                output.writeInt(song.trackNumber)
                output.writeUTF(song.albumArtist)
                output.writeUTF(song.genre)
                output.writeInt(song.year)
                output.writeInt(song.discNumber)
                output.writeUTF(song.mimeType)
                output.writeUTF(song.folderPath)
                output.writeUTF(song.fileName)
                output.writeLong(song.sizeBytes)
                output.writeBoolean(song.replayGainDb != null)
                song.replayGainDb?.let(output::writeFloat)
                output.writeBoolean(song.isCompilation)
            }
            output.flush()
            atomicFile.finishWrite(outputStream)
            stream = null
            latestWriteAtMs = savedAtMs
            latestGenresLoaded = genresLoaded
        } catch (error: Exception) {
            stream?.let(atomicFile::failWrite)
            throw error
        }
    }

    private companion object {
        const val MAGIC = 0x54554E45 // TUNE
        const val SCHEMA_VERSION = 2
        const val MAX_CACHED_SONGS = 100_000
    }
}

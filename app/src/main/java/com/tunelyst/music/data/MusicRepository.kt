package com.tunelyst.music.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Reads the indexed library and resolves audio explicitly shared through Android providers. */
class MusicRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val replayGainCache = ConcurrentHashMap<String, Float>()
    private val replayGainMisses = ConcurrentHashMap.newKeySet<String>()

    /** Resolve a shared audio URI from MediaStore and embedded tags, including the file's own cover art. */
    fun songFromUri(uri: Uri): Song? {
        val indexedSong = try {
            val projection = standardProjection(includeOptionalColumns = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.toSong(uri, emptyMap()) else null
            }
        } catch (_: Exception) {
            // Some providers expose shared audio but not the full MediaStore projection.
            null
        }

        val embedded = extractSharedMetadata(uri)
        if (indexedSong == null) return songFromSharedMetadata(uri, embedded)
        if (embedded == null) {
            return indexedSong.copy(
                artworkUri = cacheArtworkFromUri(indexedSong.artworkUri) ?: indexedSong.artworkUri
            )
        }

        return indexedSong.copy(
            title = embedded.title ?: indexedSong.title,
            artist = embedded.artist ?: indexedSong.artist,
            album = embedded.album ?: indexedSong.album,
            durationMs = embedded.durationMs ?: indexedSong.durationMs,
            artworkUri = embedded.artworkUri
                ?: cacheArtworkFromUri(indexedSong.artworkUri)
                ?: indexedSong.artworkUri,
            trackNumber = embedded.trackNumber ?: indexedSong.trackNumber,
            albumArtist = embedded.albumArtist ?: indexedSong.albumArtist,
            genre = embedded.genre ?: indexedSong.genre,
            year = embedded.year ?: indexedSong.year,
            mimeType = embedded.mimeType ?: indexedSong.mimeType
        )
    }

    private data class SharedAudioMetadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val durationMs: Long?,
        val trackNumber: Int?,
        val genre: String?,
        val year: Int?,
        val mimeType: String?,
        val artworkUri: Uri?
    )

    private fun extractSharedMetadata(uri: Uri): SharedAudioMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            fun metadata(key: Int): String? = runCatching { retriever.extractMetadata(key) }
                .getOrNull()?.trim()?.takeIf {
                    it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) && !it.equals("unknown", ignoreCase = true)
                }
            val trackNumber = metadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.toIntOrNull()?.takeIf { it > 0 }
            val artworkUri = runCatching { retriever.embeddedPicture }.getOrNull()
                ?.takeIf { it.isNotEmpty() && it.size <= MAX_SHARED_ARTWORK_BYTES }
                ?.let(::cacheArtworkBytes)
            SharedAudioMetadata(
                title = metadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = metadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = metadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = metadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                durationMs = metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0L },
                trackNumber = trackNumber,
                genre = metadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year = metadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull()?.takeIf { it > 0 },
                mimeType = metadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?: runCatching { resolver.getType(uri) }.getOrNull()?.takeIf(String::isNotBlank),
                artworkUri = artworkUri
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun songFromSharedMetadata(uri: Uri, metadata: SharedAudioMetadata?): Song {
        val fileName = sharedFileName(uri).orEmpty()
        val fallbackTitle = fileName.substringAfterLast('/').substringBeforeLast('.')
            .ifBlank { uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty() }
            .ifBlank { "Shared track" }
        val artist = metadata?.artist ?: "Unknown artist"
        return Song(
            id = sharedId(uri),
            title = metadata?.title ?: fallbackTitle,
            artist = artist,
            album = metadata?.album ?: "Unknown album",
            durationMs = metadata?.durationMs ?: 0L,
            uri = uri,
            artworkUri = metadata?.artworkUri,
            dateAddedSeconds = 0L,
            trackNumber = metadata?.trackNumber ?: 0,
            albumArtist = metadata?.albumArtist ?: artist,
            genre = metadata?.genre ?: "Unknown genre",
            year = metadata?.year ?: 0,
            mimeType = metadata?.mimeType ?: runCatching { resolver.getType(uri).orEmpty() }.getOrDefault(""),
            fileName = fileName.ifBlank { fallbackTitle }
        )
    }

    private fun sharedFileName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }.getOrNull()?.takeIf(String::isNotBlank)

    /** Copies art into the app cache so Coil and the MediaSession can read it for a shared content URI. */
    private fun cacheArtworkFromUri(uri: Uri?): Uri? {
        if (uri == null) return null
        return runCatching {
            val input = resolver.openInputStream(uri) ?: return null
            input.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_SHARED_ARTWORK_BYTES) return null
                    output.write(buffer, 0, count)
                }
                cacheArtworkBytes(output.toByteArray())
            }
        }.getOrNull()
    }

    private fun cacheArtworkBytes(bytes: ByteArray): Uri? {
        if (bytes.isEmpty() || bytes.size > MAX_SHARED_ARTWORK_BYTES) return null
        return runCatching {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val directory = java.io.File(appContext.cacheDir, "shared-audio-artwork")
            if (!directory.exists() && !directory.mkdirs()) return null
            val extension = when {
                bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
                bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "png"
                bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
                else -> "img"
            }
            val target = java.io.File(directory, "$hash.$extension")
            synchronized(sharedArtworkCacheLock) {
                if (!target.isFile || target.length() != bytes.size.toLong()) {
                    val temporary = java.io.File.createTempFile("shared-art-", ".tmp", directory)
                    try {
                        java.io.FileOutputStream(temporary).use { it.write(bytes) }
                        if (!temporary.renameTo(target)) temporary.copyTo(target, overwrite = true)
                    } finally {
                        temporary.delete()
                    }
                }
            }
            Uri.fromFile(target)
        }.getOrNull()
    }

    private val sharedArtworkCacheLock = Any()

    private fun sharedId(uri: Uri): Long = Long.MIN_VALUE + (uri.toString().hashCode().toLong() and 0x7fffffffL)

    /** A cheap MediaStore change token used to avoid rescanning an unchanged library on launch. */
    fun mediaStoreRevision(): String? {
        // API 29's opaque version does not change for ordinary file additions. API 30+ adds
        // per-volume generation numbers, which reliably detect added or edited media.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val volumes = MediaStore.getExternalVolumeNames(appContext).sorted()
            if (volumes.isEmpty()) return@runCatching null
            volumes.joinToString("|") { volume ->
                val version = MediaStore.getVersion(appContext, volume)
                val generation = MediaStore.getGeneration(appContext, volume)
                "$volume:$version:$generation"
            }
        }.getOrNull()
    }

    fun scanDevice(excludedFolders: Set<String> = emptySet()): List<Song> {
        val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.VOLUME_EXTERNAL else "external"
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(volume)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = standardProjection(includeOptionalColumns = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val songs = ArrayList<Song>()

        val cursor = try {
            resolver.query(collection, projection, selection, null, sortOrder)
        } catch (_: IllegalArgumentException) {
            // Older/vendor MediaStore providers sometimes reject newer optional columns.
            resolver.query(collection, standardProjection(includeOptionalColumns = false), selection, null, sortOrder)
        } ?: throw IllegalStateException("Android couldn't open the music library. Check audio access and try again.")
        cursor.use {
            while (cursor.moveToNext()) {
                val song = cursor.toSong(collection, emptyMap())
                if (isExcludedFolder(song.folderPath, excludedFolders)) continue
                songs += song
            }
        }
        return songs
    }

    /** Returns true only when the provider confirms this stored content URI is still readable. */
    fun canRead(uri: Uri): Boolean = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } ?: false
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }

    /** Reads a small tag window on demand (only when the user enables loudness normalization). */
    fun embeddedReplayGainDb(uri: Uri): Float? {
        val key = uri.toString()
        replayGainCache[key]?.let { return it }
        if (replayGainMisses.contains(key)) return null
        val value = EmbeddedReplayGainReader.read(appContext, uri)
        if (value != null) replayGainCache[key] = value else replayGainMisses += key
        return value
    }

    fun hasResolvedEmbeddedReplayGain(uri: Uri): Boolean {
        val key = uri.toString()
        return replayGainCache.containsKey(key) || replayGainMisses.contains(key)
    }

    private fun standardProjection(includeOptionalColumns: Boolean): Array<String> {
        val base = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE
        )
        if (includeOptionalColumns) {
            base += listOf(COLUMN_ALBUM_ARTIST, COLUMN_DISC_NUMBER, COLUMN_RELATIVE_PATH, COLUMN_COMPILATION)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Keep actual library folder paths available even when a vendor rejects optional tag columns.
            base += COLUMN_RELATIVE_PATH
        } else {
            base += COLUMN_DATA
        }
        return base.distinct().toTypedArray()
    }

    private fun Cursor.toSong(uri: Uri, genreBySongId: Map<Long, String>): Song {
        fun string(column: String, fallback: String = ""): String {
            val index = getColumnIndex(column)
            return if (index >= 0 && !isNull(index)) getString(index).orEmpty() else fallback
        }
        fun long(column: String, fallback: Long = 0L): Long {
            val index = getColumnIndex(column)
            return if (index >= 0 && !isNull(index)) getLong(index) else fallback
        }
        fun int(column: String, fallback: Int = 0): Int {
            val index = getColumnIndex(column)
            return if (index >= 0 && !isNull(index)) getInt(index) else fallback
        }
        val id = long(MediaStore.Audio.Media._ID, sharedId(uri))
        val albumId = long(MediaStore.Audio.Media.ALBUM_ID)
        val rawTitle = string(MediaStore.Audio.Media.TITLE).trim()
        val rawArtist = cleanTag(string(MediaStore.Audio.Media.ARTIST), "Unknown artist")
        val rawAlbum = cleanTag(string(MediaStore.Audio.Media.ALBUM), "Unknown album")
        val compilation = int(COLUMN_COMPILATION) != 0
        val dataPath = string(COLUMN_DATA)
        val relativePath = string(COLUMN_RELATIVE_PATH)
        val folderPath = relativePath.ifBlank { dataPath.substringBeforeLast('/', "") }
        val rawFileName = string(MediaStore.Audio.Media.DISPLAY_NAME).ifBlank {
            dataPath.substringAfterLast('/').ifBlank { uri.lastPathSegment.orEmpty() }
        }
        val fileName = rawFileName.replace('\\', '/').substringAfterLast('/').trim()
        val mimeType = string(MediaStore.Audio.Media.MIME_TYPE).ifBlank { resolver.getType(uri).orEmpty() }
        val artwork = albumId.takeIf { it > 0L }?.let { Uri.parse("content://media/external/audio/albumart/$it") }
        return Song(
            id = id,
            title = cleanTrackDisplayName(rawTitle, fileName),
            artist = rawArtist,
            album = rawAlbum,
            durationMs = long(MediaStore.Audio.Media.DURATION).coerceAtLeast(0L),
            // Collection URIs need the row ID appended; item URIs (such as a shared
            // content://media/.../audio/media/123) already identify the row and must be kept intact.
            uri = if (uri == MediaStore.Audio.Media.EXTERNAL_CONTENT_URI ||
                uri == MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            ) ContentUris.withAppendedId(uri, id) else uri,
            artworkUri = artwork,
            dateAddedSeconds = long(MediaStore.Audio.Media.DATE_ADDED),
            trackNumber = int(MediaStore.Audio.Media.TRACK) % 1000,
            albumArtist = cleanTag(string(COLUMN_ALBUM_ARTIST), if (compilation) "Various Artists" else rawArtist),
            genre = genreBySongId[id] ?: "Unknown genre",
            year = int(MediaStore.Audio.Media.YEAR).coerceIn(0, 9999),
            discNumber = int(COLUMN_DISC_NUMBER).takeIf { it > 0 } ?: 1,
            mimeType = mimeType,
            folderPath = normalizeFolder(folderPath),
            fileName = fileName,
            sizeBytes = long(MediaStore.Audio.Media.SIZE).coerceAtLeast(0L),
            isCompilation = compilation
        )
    }

    /** Genre membership uses multiple provider queries, so load it after the cached song list is visible. */
    fun loadGenresBySongId(): Map<Long, String>? {
        val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.VOLUME_EXTERNAL else "external"
        return loadGenreMap(volume)
    }

    private fun loadGenreMap(volume: String): Map<Long, String>? = runCatching {
        val genresBySongId = HashMap<Long, LinkedHashSet<String>>()
        val genresUri = MediaStore.Audio.Genres.getContentUri(volume)
        resolver.query(genresUri, arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME), null, null, null)?.use { genres ->
            val idColumn = genres.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
            val nameColumn = genres.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME)
            while (genres.moveToNext()) {
                val genreId = genres.getLong(idColumn)
                val name = genres.getString(nameColumn)?.trim().orEmpty()
                if (name.isBlank()) continue
                val members = MediaStore.Audio.Genres.Members.getContentUri(volume, genreId)
                resolver.query(members, arrayOf(COLUMN_AUDIO_ID), null, null, null)?.use { tracks ->
                    val audioIdColumn = tracks.getColumnIndex(COLUMN_AUDIO_ID)
                    if (audioIdColumn >= 0) while (tracks.moveToNext()) {
                        genresBySongId.getOrPut(tracks.getLong(audioIdColumn)) { linkedSetOf() }.add(name)
                    }
                }
            }
        }
        genresBySongId.mapValues { (_, names) -> names.joinToString(", ") }
    }.getOrNull()

    private fun cleanTag(value: String, fallback: String): String =
        value.trim().takeUnless { it.isBlank() || it.equals("<unknown>", true) } ?: fallback

    fun isExcludedFolder(path: String, excludedFolders: Set<String>): Boolean {
        if (path.isBlank()) return false
        val normalized = normalizeFolder(path).lowercase(Locale.ROOT)
        return excludedFolders.any { excluded ->
            val safe = normalizeFolder(excluded).lowercase(Locale.ROOT)
            safe.isNotBlank() && (normalized == safe || normalized.startsWith("$safe/"))
        }
    }

    private fun normalizeFolder(path: String): String = path.trim().replace('\\', '/').trim('/').ifBlank { "" }

    private companion object {
        const val MAX_SHARED_ARTWORK_BYTES = 20 * 1024 * 1024
        const val COLUMN_ALBUM_ARTIST = "album_artist"
        const val COLUMN_DISC_NUMBER = "disc_number"
        const val COLUMN_RELATIVE_PATH = "relative_path"
        const val COLUMN_DATA = "_data"
        const val COLUMN_COMPILATION = "is_compilation"
        const val COLUMN_AUDIO_ID = "audio_id"
    }
}

package com.tunelyst.music.data

import android.content.Context
import android.util.AtomicFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/** Minimal on-device cache for prebuilt Home mixes; only local track IDs and labels are stored. */
data class CachedHomeMix(
    val title: String,
    val description: String,
    val songIds: List<Long>
)

data class HomeMixCacheSnapshot(
    val catalogSavedAtMs: Long,
    val genresLoaded: Boolean,
    val rotationDay: Long,
    val favoritesFingerprint: Long,
    val recentlyPlayedFingerprint: Long,
    val hiddenSongsFingerprint: Long,
    val playCountsFingerprint: Long,
    val lessLikeArtistsFingerprint: Long,
    val lessLikeGenresFingerprint: Long,
    val recentlyAddedSongIds: List<Long>,
    val mixes: List<CachedHomeMix>,
    val bestOfArtists: List<CachedHomeMix> = emptyList(),
    /** False only when loading a cache written before best-of-artist mixes were saved. */
    val hasBestOfArtists: Boolean = true
)

/** A tiny, crash-safe cache so Home can render its existing shelves before recomputing them. */
class HomeMixCache(context: Context) {
    private val atomicFile = AtomicFile(File(context.applicationContext.cacheDir, "tunelyst-home-mixes.bin"))

    @Synchronized
    fun readSnapshot(): HomeMixCacheSnapshot? {
        if (!atomicFile.baseFile.exists()) return null
        return runCatching {
            DataInputStream(BufferedInputStream(atomicFile.openRead())).use(HomeMixCacheCodec::read)
        }.getOrNull()
    }

    @Synchronized
    fun writeSnapshot(snapshot: HomeMixCacheSnapshot) {
        var stream: FileOutputStream? = null
        try {
            val outputStream = atomicFile.startWrite()
            stream = outputStream
            val output = DataOutputStream(BufferedOutputStream(outputStream))
            HomeMixCacheCodec.write(output, snapshot)
            output.flush()
            atomicFile.finishWrite(outputStream)
            stream = null
        } catch (error: Exception) {
            stream?.let(atomicFile::failWrite)
            throw error
        }
    }
}

/** Pure serializer kept separate so the cache format can be tested without Android storage APIs. */
internal object HomeMixCacheCodec {
    private const val MAGIC = 0x4C4D4D58 // LMMX
    private const val PREVIOUS_SCHEMA_VERSION = 3
    private const val SCHEMA_VERSION = 4
    private const val MAX_MIXES = 12
    private const val MAX_BEST_OF_ARTISTS = 8
    private const val MAX_TRACKS_PER_MIX = 64
    private const val MAX_RECENTLY_ADDED = 16
    private const val MAX_TEXT_LENGTH = 512

    fun read(input: DataInputStream): HomeMixCacheSnapshot {
        require(input.readInt() == MAGIC)
        val schemaVersion = input.readInt()
        require(schemaVersion in PREVIOUS_SCHEMA_VERSION..SCHEMA_VERSION)
        val catalogSavedAtMs = input.readLong().coerceAtLeast(0L)
        val genresLoaded = input.readBoolean()
        val rotationDay = input.readLong()
        val favoritesFingerprint = input.readLong()
        val recentlyPlayedFingerprint = input.readLong()
        val hiddenSongsFingerprint = input.readLong()
        val playCountsFingerprint = input.readLong()
        val lessLikeArtistsFingerprint = input.readLong()
        val lessLikeGenresFingerprint = input.readLong()
        val recentlyAddedCount = input.readInt()
        require(recentlyAddedCount in 0..MAX_RECENTLY_ADDED)
        val recentlyAdded = List(recentlyAddedCount) { input.readLong() }
        val mixes = readMixes(input, MAX_MIXES)
        val hasBestOfArtists = schemaVersion >= SCHEMA_VERSION && input.readBoolean()
        val bestOfArtists = if (schemaVersion >= SCHEMA_VERSION) {
            readMixes(input, MAX_BEST_OF_ARTISTS)
        } else {
            emptyList()
        }

        return HomeMixCacheSnapshot(
            catalogSavedAtMs = catalogSavedAtMs,
            genresLoaded = genresLoaded,
            rotationDay = rotationDay,
            favoritesFingerprint = favoritesFingerprint,
            recentlyPlayedFingerprint = recentlyPlayedFingerprint,
            hiddenSongsFingerprint = hiddenSongsFingerprint,
            playCountsFingerprint = playCountsFingerprint,
            lessLikeArtistsFingerprint = lessLikeArtistsFingerprint,
            lessLikeGenresFingerprint = lessLikeGenresFingerprint,
            recentlyAddedSongIds = recentlyAdded,
            mixes = mixes,
            bestOfArtists = bestOfArtists,
            hasBestOfArtists = hasBestOfArtists
        )
    }

    fun write(output: DataOutputStream, snapshot: HomeMixCacheSnapshot) {
        val recentlyAdded = snapshot.recentlyAddedSongIds.distinct().take(MAX_RECENTLY_ADDED)
        output.writeInt(MAGIC)
        output.writeInt(SCHEMA_VERSION)
        output.writeLong(snapshot.catalogSavedAtMs.coerceAtLeast(0L))
        output.writeBoolean(snapshot.genresLoaded)
        output.writeLong(snapshot.rotationDay)
        output.writeLong(snapshot.favoritesFingerprint)
        output.writeLong(snapshot.recentlyPlayedFingerprint)
        output.writeLong(snapshot.hiddenSongsFingerprint)
        output.writeLong(snapshot.playCountsFingerprint)
        output.writeLong(snapshot.lessLikeArtistsFingerprint)
        output.writeLong(snapshot.lessLikeGenresFingerprint)
        output.writeInt(recentlyAdded.size)
        recentlyAdded.forEach(output::writeLong)
        writeMixes(output, snapshot.mixes, MAX_MIXES)
        output.writeBoolean(snapshot.hasBestOfArtists)
        writeMixes(output, snapshot.bestOfArtists, MAX_BEST_OF_ARTISTS)
    }

    private fun readMixes(input: DataInputStream, maximumCount: Int): List<CachedHomeMix> {
        val mixCount = input.readInt()
        require(mixCount in 0..maximumCount)
        return buildList(mixCount) {
            repeat(mixCount) {
                val title = input.readUTF()
                val description = input.readUTF()
                val songCount = input.readInt()
                require(songCount in 0..MAX_TRACKS_PER_MIX)
                val songIds = List(songCount) { input.readLong() }.distinct()
                if (title.isNotBlank() && songIds.isNotEmpty()) {
                    add(CachedHomeMix(title, description, songIds))
                }
            }
        }
    }

    private fun writeMixes(output: DataOutputStream, values: List<CachedHomeMix>, maximumCount: Int) {
        val mixes = values.asSequence()
            .filter { it.title.isNotBlank() }
            .distinctBy(CachedHomeMix::title)
            .take(maximumCount)
            .map { mix ->
                mix.copy(
                    title = mix.title.take(MAX_TEXT_LENGTH),
                    description = mix.description.take(MAX_TEXT_LENGTH),
                    songIds = mix.songIds.distinct().take(MAX_TRACKS_PER_MIX)
                )
            }
            .filter { it.songIds.isNotEmpty() }
            .toList()

        output.writeInt(mixes.size)
        mixes.forEach { mix ->
            output.writeUTF(mix.title)
            output.writeUTF(mix.description)
            output.writeInt(mix.songIds.size)
            mix.songIds.forEach(output::writeLong)
        }
    }
}

/** Order-independent fingerprint for sets such as favorites and Home-hidden song IDs. */
fun homeMixSetFingerprint(ids: Collection<Long>): Long {
    var xor = 0L
    var sum = 0L
    var squares = 0L
    ids.forEach { id ->
        val mixed = mixHomeId(id)
        xor = xor xor mixed
        sum += mixed
        squares += mixed * mixed
    }
    return mixHomeId(xor xor java.lang.Long.rotateLeft(sum, 19) xor java.lang.Long.rotateLeft(squares, 41) xor ids.size.toLong())
}

/** Order-sensitive fingerprint for the user's most-recently-played sequence. */
fun homeMixOrderedIdFingerprint(ids: List<Long>): Long {
    var hash = -3750763034362895579L
    ids.forEachIndexed { index, id ->
        hash = (hash xor mixHomeId(id + index * -7046029254386353131L)) * 1099511628211L
    }
    return mixHomeId(hash xor ids.size.toLong())
}

/** Fingerprints aggregate listen counts without making Home cache keys depend on map iteration order. */
fun homeMixPlayCountsFingerprint(values: Map<Long, Long>): Long = homeMixSetFingerprint(
    values.map { (songId, count) -> songId xor java.lang.Long.rotateLeft(count, 23) }
)

/** Case/spacing-insensitive fingerprint for local artist/genre feedback preferences. */
fun homeMixTasteNamesFingerprint(values: Collection<String>): Long = homeMixSetFingerprint(
    values.map { normalizeTasteKey(it).hashCode().toLong() }
)

private fun mixHomeId(value: Long): Long {
    var mixed = value
    mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
    mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
    return mixed xor (mixed ushr 31)
}

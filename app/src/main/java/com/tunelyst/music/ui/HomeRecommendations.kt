package com.tunelyst.music.ui

import com.tunelyst.music.data.CachedHomeMix
import com.tunelyst.music.data.HomeMixCacheSnapshot
import com.tunelyst.music.data.Song
import com.tunelyst.music.core.logic.normalizeTasteKey
import com.tunelyst.music.core.logic.normalizedGenreKeys
import java.time.LocalDate
import java.util.Locale
import kotlin.math.ln

/** One personalized, on-device mix shown in the Home "Made for you" shelf. */
data class MadeForYouMix(
    val title: String,
    val description: String,
    val songs: List<Song>
)

data class HomeRecommendations(
    val recentlyAdded: List<Song>,
    val mixes: List<MadeForYouMix>,
    val bestOfArtists: List<MadeForYouMix> = emptyList()
)

/** Restores a cached shelf from current library entries without rebuilding recommendation rankings. */
fun restoreHomeRecommendationsFromCache(
    cache: HomeMixCacheSnapshot?,
    songLookup: Map<Long, Song>,
    hiddenHomeSongIds: Set<Long>
): HomeRecommendations? {
    if (cache == null || songLookup.isEmpty()) return null

    fun resolve(ids: List<Long>): List<Song> = ids.distinct().mapNotNull { id ->
        if (id in hiddenHomeSongIds) null else songLookup[id]
    }

    fun restoreMixes(cachedMixes: List<CachedHomeMix>): List<MadeForYouMix> = cachedMixes.mapNotNull { cachedMix ->
        resolve(cachedMix.songIds)
            .takeIf { it.isNotEmpty() }
            ?.let { MadeForYouMix(cachedMix.title, cachedMix.description, it) }
    }

    val recommendations = HomeRecommendations(
        recentlyAdded = resolve(cache.recentlyAddedSongIds),
        mixes = restoreMixes(cache.mixes),
        bestOfArtists = restoreMixes(cache.bestOfArtists)
    )
    return recommendations.takeIf {
        it.recentlyAdded.isNotEmpty() || it.mixes.isNotEmpty() || it.bestOfArtists.isNotEmpty()
    }
}

fun homeMixCardId(title: String): String = "mix:$title"

/** Builds a local-only, qualified-play-ranked artist shelf from visible library tracks. */
fun buildBestOfArtistMixes(
    songs: List<Song>,
    playCounts: Map<Long, Long>,
    hiddenHomeSongIds: Set<Long> = emptySet(),
    recentlyPlayedIds: List<Long> = emptyList()
): List<MadeForYouMix> {
    if (songs.isEmpty() || playCounts.isEmpty()) return emptyList()

    data class ArtistPlays(
        val key: String,
        val name: String,
        val songs: MutableList<Song> = mutableListOf(),
        var totalPlays: Long = 0L
    )
    data class RankedArtist(
        val artist: ArtistPlays,
        val score: Double,
        val topFiveAverage: Double
    )

    val recentRank = recentlyPlayedIds.distinct().withIndex().associate { it.value to it.index }
    val artists = LinkedHashMap<String, ArtistPlays>()
    songs.asSequence()
        .filterNot { it.id in hiddenHomeSongIds }
        .distinctBy(Song::id)
        .forEach { song ->
            val identity = bestOfArtistIdentity(song) ?: return@forEach
            val plays = playCounts[song.id]?.coerceAtLeast(0L) ?: return@forEach
            if (plays == 0L) return@forEach
            val artist = artists.getOrPut(identity.key) { ArtistPlays(identity.key, identity.name) }
            artist.songs += song
            artist.totalPlays = if (Long.MAX_VALUE - artist.totalPlays < plays) {
                Long.MAX_VALUE
            } else {
                artist.totalPlays + plays
            }
        }

    return artists.values.asSequence()
        .map { artist ->
            val topFiveCounts = artist.songs
                .map { playCounts[it.id]?.coerceAtLeast(0L) ?: 0L }
                .sortedDescending()
                .take(5)
            val topFiveAverage = topFiveCounts.average()
            val recentSignal = artist.songs
                .mapNotNull { recentRank[it.id] }
                .sorted()
                .take(5)
                .sumOf { rank -> 1.0 / (1.0 + rank * 0.12) }
            // A few top tracks matter more than raw catalog size; recent activity is a capped nudge.
            RankedArtist(artist, topFiveAverage * (1.0 + recentSignal * 0.025), topFiveAverage)
        }
        .sortedWith(
            compareByDescending<RankedArtist> { it.score }
                .thenByDescending { it.topFiveAverage }
                .thenByDescending { it.artist.totalPlays }
                .thenByDescending { it.artist.songs.size }
                .thenBy { it.artist.key }
        )
        .take(MAX_BEST_OF_ARTISTS)
        .map { ranked ->
            val artist = ranked.artist
            val popularityOrder = artist.songs.sortedWith(
                compareByDescending<Song> { playCounts[it.id]?.coerceAtLeast(0L) ?: 0L }
                    .thenBy { recentRank[it.id] ?: Int.MAX_VALUE }
                    .thenByDescending(Song::dateAddedSeconds)
                    .thenBy { it.title.lowercase(Locale.ROOT) }
                    .thenBy(Song::id)
            )
            val topSongs = selectDiverseMix(
                candidates = popularityOrder,
                limit = MAX_BEST_OF_ARTIST_SONGS,
                genresBySongId = emptyMap(),
                artistPenalty = 0f,
                albumPenalty = BEST_OF_ARTIST_ALBUM_SPACING_PENALTY,
                genrePenalty = 0f,
                score = { song ->
                    val plays = (playCounts[song.id] ?: 0L).coerceAtLeast(0L).toFloat()
                    val recentBonus = recentRank[song.id]?.let { 0.35f / (1f + it * 0.1f) } ?: 0f
                    plays + recentBonus
                }
            )
            MadeForYouMix(
                title = "Best of ${artist.name}",
                description = "Your most-played local songs by ${artist.name}",
                songs = topSongs
            )
        }
        .toList()
}

private data class BestOfArtistIdentity(val key: String, val name: String)

private val FEATURED_ARTIST_SUFFIX = Regex("\\s+(?:feat(?:uring)?\\.?|ft\\.?)\\s+.*$", RegexOption.IGNORE_CASE)
private val NON_ARTIST_LABELS = setOf("unknown", "unknown artist", "unknown album artist", "n/a", "none", "various artists", "various", "compilation")
private val UNKNOWN_ARTIST_ALBUM_LABELS = setOf("unknown", "unknown artist", "unknown album artist", "n/a", "none")
private val UNKNOWN_ALBUM_LABELS = setOf("unknown", "unknown album", "n/a", "none")

/** Prefer a meaningful album-artist tag and collapse common featured-artist suffixes. */
private fun bestOfArtistIdentity(song: Song): BestOfArtistIdentity? {
    fun clean(value: String) = value.trim().replace(FEATURED_ARTIST_SUFFIX, "").trim().trimEnd(',', ';')
    fun usable(value: String): Boolean {
        val key = normalizeTasteKey(value)
        return key.isNotBlank() && key !in NON_ARTIST_LABELS && !key.startsWith("various artist")
    }

    val albumArtist = clean(song.albumArtist)
    val trackArtist = clean(song.artist)
    val name = albumArtist.takeIf(::usable) ?: trackArtist.takeIf(::usable) ?: return null
    return BestOfArtistIdentity(normalizeTasteKey(name), name)
}

/**
 * Builds diverse offline mixes from the local catalog and listening signals. Core discovery cards
 * avoid unnecessary overlap; selected taste-focused cards may intentionally share useful tracks.
 * Best-of artist cards are ranked separately using qualified local-play signals.
 */
fun buildHomeRecommendations(
    songs: List<Song>,
    favorites: Set<Long>,
    recentlyPlayedIds: List<Long>,
    playCounts: Map<Long, Long> = emptyMap(),
    lessLikeArtistNames: Set<String> = emptySet(),
    lessLikeGenreNames: Set<String> = emptySet(),
    hiddenHomeSongIds: Set<Long> = emptySet(),
    rotationDay: Long = LocalDate.now().toEpochDay()
): HomeRecommendations {
    val library = songs.asSequence()
        .filterNot { it.id in hiddenHomeSongIds }
        .distinctBy(Song::id)
        .toList()
    if (library.isEmpty()) return HomeRecommendations(emptyList(), emptyList(), emptyList())

    val bestOfArtists = buildBestOfArtistMixes(
        songs = library,
        playCounts = playCounts,
        recentlyPlayedIds = recentlyPlayedIds
    )
    val songsById = library.associateBy(Song::id)
    val recentIds = recentlyPlayedIds.distinct()
    val recent = recentIds.mapNotNull(songsById::get)
    val recentRank = recent.withIndex().associate { (index, song) -> song.id to index }
    val favoriteSongs = library.filter { it.id in favorites }
    val lessLikeArtists = lessLikeArtistNames.map(::normalizeTasteKey).filter(String::isNotBlank).toSet()
    val lessLikeGenres = lessLikeGenreNames.map(::normalizeTasteKey).filter(String::isNotBlank).toSet()
    val newestFirst = library.sortedWith(
        compareByDescending<Song> { it.dateAddedSeconds }
            .thenBy { it.title.lowercase(Locale.ROOT) }
            .thenBy(Song::id)
    )
    val addedRank = newestFirst.withIndex().associate { (index, song) -> song.id to index }
    val genresBySongId = library.associate { song -> song.id to normalizedGenreKeys(song.genre) }
    val hasListeningSignals = recent.isNotEmpty() || favoriteSongs.isNotEmpty() || playCounts.values.any { it > 0L }
    val artistAffinity = HashMap<String, Float>()
    val albumAffinity = HashMap<String, Float>()
    val genreAffinity = HashMap<String, Float>()

    recent.forEachIndexed { index, song ->
        val weight = 8.5f / (1f + index * 0.28f)
        artistAffinity.add(mixArtistKey(song), weight)
        albumAffinity.add(mixAlbumKey(song), weight * 0.52f)
        genresBySongId[song.id].orEmpty().forEach { genreAffinity.add(it, weight * 0.46f) }
    }
    favoriteSongs.forEach { song ->
        artistAffinity.add(mixArtistKey(song), 3.8f)
        albumAffinity.add(mixAlbumKey(song), 1.8f)
        genresBySongId[song.id].orEmpty().forEach { genreAffinity.add(it, 2.3f) }
    }

    fun freshness(song: Song): Float {
        val rank = addedRank[song.id] ?: return 0f
        return 7f / (1f + rank * 0.34f)
    }

    fun recentScore(song: Song): Float = recentRank[song.id]?.let { 13f / (1f + it * 0.42f) } ?: 0f
    fun favoriteScore(song: Song): Float = if (song.id in favorites) 8.5f else 0f
    fun lifetimePlayPenalty(song: Song): Float = -ln(1.0 + (playCounts[song.id] ?: 0L).coerceAtLeast(0L)).toFloat() * 0.38f
    fun preferencePenalty(song: Song): Float {
        val artistPenalty = if (normalizeTasteKey(song.artist) in lessLikeArtists) -22f else 0f
        val genrePenalty = if (genresBySongId[song.id].orEmpty().any { it in lessLikeGenres }) -14f else 0f
        return artistPenalty + genrePenalty
    }
    fun noise(song: Song, salt: Long): Float = deterministicMixNoise(song.id, rotationDay xor salt)
    fun tasteScore(song: Song): Float =
        recentScore(song) * 0.48f + favoriteScore(song) +
            artistAffinity.getOrDefault(mixArtistKey(song), 0f) * 0.42f +
            albumAffinity.getOrDefault(mixAlbumKey(song), 0f) * 0.30f +
            genresBySongId[song.id].orEmpty().sumOf { genreAffinity.getOrDefault(it, 0f).toDouble() }.toFloat() * 0.42f +
            freshness(song) * (if (hasListeningSignals) 0.14f else 0.5f) +
            noise(song, 0x1D4D4D49584C)

    val mixSize = minOf(MAX_MIX_SIZE, maxOf(8, library.size / 8))
    val minimumMixSize = minOf(MIN_MIX_SIZE, library.size)
    val assignedIds = HashSet<Long>(library.size)
    val usedTitles = HashSet<String>()
    val mixes = ArrayList<MadeForYouMix>(MAX_MIX_COUNT)

    fun addMix(
        title: String,
        description: String,
        candidates: List<Song>,
        artistPenalty: Float = 2.1f,
        albumPenalty: Float = 0.95f,
        genrePenalty: Float = 0.28f,
        allowOverlap: Boolean = false,
        score: (Song) -> Float
    ): Boolean {
        if (mixes.size >= MAX_MIX_COUNT) return false
        val available = candidates.asSequence()
            .filter { allowOverlap || it.id !in assignedIds }
            .distinctBy(Song::id)
            .toList()
        if (available.size < minimumMixSize) return false
        val selection = selectDiverseMix(
            candidates = available,
            limit = mixSize,
            genresBySongId = genresBySongId,
            artistPenalty = artistPenalty,
            albumPenalty = albumPenalty,
            genrePenalty = genrePenalty,
            score = { song -> score(song) + preferencePenalty(song) + lifetimePlayPenalty(song) }
        )
        if (selection.size < minimumMixSize) return false
        val uniqueTitle = uniqueMixTitle(title, usedTitles)
        selection.forEach { assignedIds += it.id }
        mixes += MadeForYouMix(uniqueTitle, description, selection)
        return true
    }

    // Reserve the strongest recent-listening signal first, then present the daily mix above it.
    if (recent.size >= minimumMixSize) {
        addMix(
            title = "On Repeat",
            description = "Your recent listening, refreshed with space for favorite artists",
            candidates = (recent + favoriteSongs).distinctBy(Song::id),
            artistPenalty = 2.5f,
            albumPenalty = 0.9f,
            score = { song ->
                recentScore(song) * 1.25f + favoriteScore(song) * 0.45f +
                    artistAffinity.getOrDefault(mixArtistKey(song), 0f) * 0.14f + noise(song, 0x4F4E524550454154)
            }
        )
    }

    addMix(
        title = if (hasListeningSignals) "Daily Mix" else "Library Mix",
        description = if (hasListeningSignals) {
            "A personal blend of listening, favorites, familiar sounds and new turns"
        } else {
            "A balanced, artist-spaced tour through your local collection"
        },
        candidates = library,
        artistPenalty = 2.3f,
        albumPenalty = 1.0f,
        allowOverlap = true,
        score = if (hasListeningSignals) ::tasteScore else { song ->
            freshness(song) * 0.25f + noise(song, 0x4C494252415259)
        }
    )

    addMix(
        title = "Fresh Finds",
        description = "The newest additions, spaced out across artists and albums",
        candidates = newestFirst,
        artistPenalty = 2.4f,
        albumPenalty = 1.15f,
        score = { song -> freshness(song) * 2.4f + noise(song, 0x465245534846494E) }
    )

    if (favoriteSongs.size >= minimumMixSize) {
        addMix(
            title = "Favorites Flow",
            description = "Your saved tracks, arranged into a varied, easy-flowing run",
            candidates = favoriteSongs,
            artistPenalty = 2.2f,
            albumPenalty = 0.9f,
            allowOverlap = true,
            score = { song -> favoriteScore(song) + recentScore(song) * 0.22f + noise(song, 0x4641564F52495445) }
        )
    }

    val undiscovered = library.filter { it.id !in recentRank && it.id !in favorites }
    addMix(
        title = "Discovery Mix",
        description = if (hasListeningSignals) {
            "Unplayed tracks that connect with artists and styles you already enjoy"
        } else {
            "A varied first listen through less-familiar parts of your library"
        },
        candidates = undiscovered.ifEmpty { library },
        artistPenalty = 2.6f,
        albumPenalty = 0.8f,
        genrePenalty = 0.16f,
        allowOverlap = true,
        score = { song ->
            artistAffinity.getOrDefault(mixArtistKey(song), 0f) * 1.05f +
                genresBySongId[song.id].orEmpty().sumOf { genreAffinity.getOrDefault(it, 0f).toDouble() }.toFloat() * 0.92f +
                albumAffinity.getOrDefault(mixAlbumKey(song), 0f) * 0.34f +
                freshness(song) * 0.12f + noise(song, 0x444953434F564552)
        }
    )

    addMix(
        title = "Deep Cuts",
        description = "Less-played tracks and older corners of your collection",
        candidates = library,
        artistPenalty = 2.0f,
        albumPenalty = 0.8f,
        score = { song ->
            val plays = (playCounts[song.id] ?: 0L).coerceAtLeast(0L)
            val rarity = 6f / (1f + ln(1.0 + plays).toFloat())
            val recentPenalty = recentRank[song.id]?.let { 4f / (1f + it * 0.22f) } ?: 0f
            rarity - recentPenalty - freshness(song) * 0.28f + noise(song, 0x4445455043555453)
        }
    )

    val genreGroups = library.asSequence()
        .flatMap { song -> genresBySongId[song.id].orEmpty().asSequence().map { it to song } }
        .filter { (genre, _) -> genre !in lessLikeGenres }
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<String, List<Song>>> { genreAffinity.getOrDefault(it.key, 0f) }
                .thenByDescending { it.value.size }
                .thenBy { it.key }
        )
    var addedGenreMixes = 0
    genreGroups.forEach { (genre, tracks) ->
        if (addedGenreMixes >= MAX_GENRE_MIXES || mixes.size >= MAX_MIX_COUNT) return@forEach
        val title = "${genre.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }} Mix"
        val added = addMix(
            title = title,
            description = "A focused $genre blend with artists and albums kept in rotation",
            candidates = tracks,
            artistPenalty = 2.0f,
            albumPenalty = 0.65f,
            genrePenalty = 0f,
            score = { song ->
                artistAffinity.getOrDefault(mixArtistKey(song), 0f) * 0.24f +
                    freshness(song) * 0.08f + noise(song, genre.hashCode().toLong())
            }
        )
        if (added) addedGenreMixes++
    }

    val currentYear = LocalDate.now().year
    val decades = library.asSequence()
        .filter { it.year in 1950..currentYear }
        .groupBy { (it.year / 10) * 10 }
        .entries
        .sortedWith(compareByDescending<Map.Entry<Int, List<Song>>> { it.value.size }.thenByDescending { it.key })
    var addedTimeCapsules = 0
    decades.forEach { (decade, tracks) ->
        if (addedTimeCapsules >= MAX_TIME_CAPSULES || mixes.size >= MAX_MIX_COUNT) return@forEach
        val added = addMix(
            title = "${decade}s Time Capsule",
            description = "A dedicated run from the $decade-era tracks in your collection",
            candidates = tracks,
            artistPenalty = 2.3f,
            albumPenalty = 0.9f,
            score = { song ->
                freshness(song) * 0.05f + recentScore(song) * 0.12f + noise(song, decade.toLong())
            }
        )
        if (added) addedTimeCapsules++
    }

    if (mixes.size < MAX_MIX_COUNT) {
        addMix(
            title = "Library Discovery",
            description = "A fresh, well-spaced route through tracks left on the shelf",
            candidates = library,
            allowOverlap = true,
            score = { song -> tasteScore(song) * 0.18f + noise(song, 0x4C49425241525944) }
        )
    }

    val displayOrder = mapOf(
        "Daily Mix" to 0,
        "Library Mix" to 0,
        "On Repeat" to 1,
        "Fresh Finds" to 2,
        "Discovery Mix" to 3,
        "Favorites Flow" to 4,
        "Deep Cuts" to 5
    )
    val displayedMixes = mixes.withIndex()
        .sortedWith(compareBy<IndexedValue<MadeForYouMix>> { displayOrder[it.value.title] ?: 6 }.thenBy { it.index })
        .map(IndexedValue<MadeForYouMix>::value)

    return HomeRecommendations(
        recentlyAdded = newestFirst.take(8),
        mixes = displayedMixes,
        bestOfArtists = bestOfArtists
    )
}

private fun selectDiverseMix(
    candidates: List<Song>,
    limit: Int,
    genresBySongId: Map<Long, List<String>>,
    artistPenalty: Float,
    albumPenalty: Float,
    genrePenalty: Float,
    score: (Song) -> Float
): List<Song> {
    data class RankedSong(val song: Song, val baseScore: Float)

    val remaining = candidates.distinctBy(Song::id)
        .map { song -> RankedSong(song, score(song)) }
        .toMutableList()
    val selected = ArrayList<Song>(minOf(limit, remaining.size))
    val artistCounts = HashMap<String, Int>()
    val albumCounts = HashMap<String, Int>()
    val genreCounts = HashMap<String, Int>()

    while (selected.size < limit && remaining.isNotEmpty()) {
        var bestIndex = 0
        var bestScore = Float.NEGATIVE_INFINITY
        remaining.forEachIndexed { index, ranked ->
            val song = ranked.song
            val repeatsByGenre = genresBySongId[song.id].orEmpty().sumOf { genreCounts.getOrDefault(it, 0) }
            val adjustedScore = ranked.baseScore -
                artistCounts.getOrDefault(mixArtistKey(song), 0) * artistPenalty -
                albumCounts.getOrDefault(mixAlbumKey(song), 0) * albumPenalty -
                repeatsByGenre * genrePenalty
            if (adjustedScore > bestScore) {
                bestScore = adjustedScore
                bestIndex = index
            }
        }
        val song = remaining.removeAt(bestIndex).song
        selected += song
        artistCounts.increment(mixArtistKey(song))
        albumCounts.increment(mixAlbumKey(song))
        genresBySongId[song.id].orEmpty().forEach { genre -> genreCounts.increment(genre) }
    }
    return selected
}

private fun mixArtistKey(song: Song): String = mixGroupKey(song.artist, song.id)

private fun mixAlbumKey(song: Song): String {
    val album = normalizeTasteKey(song.album)
    if (album.isBlank() || album in UNKNOWN_ALBUM_LABELS) return "track:${song.id}"
    val albumArtist = normalizeTasteKey(song.albumArtist)
    val artist = if (albumArtist.isBlank() || albumArtist in UNKNOWN_ARTIST_ALBUM_LABELS) {
        normalizeTasteKey(song.artist)
    } else {
        albumArtist
    }
    return if (artist.isBlank() || artist in UNKNOWN_ARTIST_ALBUM_LABELS) {
        "$album:${song.id}"
    } else {
        "$artist|$album"
    }
}

private fun mixGroupKey(value: String, songId: Long): String {
    val normalized = normalizeTasteKey(value)
    return if (normalized.isBlank() || normalized == "unknown artist" || normalized == "unknown album") {
        "track:$songId"
    } else {
        normalized
    }
}

private fun MutableMap<String, Float>.add(key: String, amount: Float) {
    this[key] = getOrDefault(key, 0f) + amount
}

private fun MutableMap<String, Int>.increment(key: String) {
    this[key] = getOrDefault(key, 0) + 1
}

private fun uniqueMixTitle(title: String, usedTitles: MutableSet<String>): String {
    if (usedTitles.add(title)) return title
    var suffix = 2
    while (!usedTitles.add("$title $suffix")) suffix++
    return "$title $suffix"
}

private fun deterministicMixNoise(songId: Long, salt: Long): Float {
    var value = songId xor salt
    value = (value xor (value ushr 30)) * -4658895280553007687L
    value = (value xor (value ushr 27)) * -7723592293110705685L
    value = value xor (value ushr 31)
    return ((value ushr 40) and 0xFFFF).toFloat() / 65535f * 0.38f
}

private const val MAX_MIX_COUNT = 10
private const val MAX_MIX_SIZE = 24
private const val MIN_MIX_SIZE = 6
private const val MAX_BEST_OF_ARTISTS = 8
private const val MAX_BEST_OF_ARTIST_SONGS = 24
private const val BEST_OF_ARTIST_ALBUM_SPACING_PENALTY = 1.5f
private const val MAX_GENRE_MIXES = 2
private const val MAX_TIME_CAPSULES = 2

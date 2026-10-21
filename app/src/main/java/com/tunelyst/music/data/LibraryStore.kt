package com.tunelyst.music.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun boundedUniqueIds(
    values: List<Long>,
    limit: Int,
    validIds: Set<Long>? = null
): List<Long> {
    if (limit <= 0) return emptyList()
    return values.asSequence()
        .filter { id -> id >= 0L && (validIds == null || id in validIds) }
        .distinct()
        .take(limit)
        .toList()
}

internal fun prependBoundedUniqueId(values: List<Long>, id: Long, limit: Int): List<Long> =
    boundedUniqueIds(listOf(id) + values.filterNot { it == id }, limit)

data class SavedPlaybackSession(
    val songIds: List<Long>,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: Int,
    val smartShuffleEnabled: Boolean = false,
    val preserveQueueOrder: Boolean = false
)

/** Small private, on-device store for personal library data; no automatic sync or network transfer. */
class LibraryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("tunelyst_library", Context.MODE_PRIVATE)

    @Synchronized fun favorites(): Set<Long> = readIds(KEY_FAVORITES).toSet()

    @Synchronized
    fun toggleFavorite(songId: Long): Set<Long> {
        val updated = readIds(KEY_FAVORITES).toMutableSet().apply { if (!add(songId)) remove(songId) }
        writeIds(KEY_FAVORITES, updated.toList())
        return updated
    }

    @Synchronized
    fun savePlaybackSession(
        queueIds: List<Long>,
        currentIndex: Int,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: Int,
        smartShuffleEnabled: Boolean = false,
        preserveQueueOrder: Boolean = false
    ) {
        if (currentIndex !in queueIds.indices) return
        val resumableIds = queueIds.drop(currentIndex).take(MAX_SAVED_QUEUE)
        if (resumableIds.isEmpty() || resumableIds.any { it < 0L }) return
        val session = JSONObject()
            .put("songs", JSONArray().apply { resumableIds.forEach { put(it) } })
            .put("position_ms", positionMs.coerceAtLeast(0L))
            .put("shuffle", shuffleEnabled)
            .put("smart_shuffle", smartShuffleEnabled)
            .put("preserve_order", preserveQueueOrder)
            .put("repeat", repeatMode.coerceIn(0, 2))
        preferences.edit().putString(KEY_PLAYBACK_SESSION, session.toString()).apply()
    }

    @Synchronized
    fun savedPlaybackSession(): SavedPlaybackSession? {
        val raw = preferences.getString(KEY_PLAYBACK_SESSION, null) ?: return null
        return runCatching {
            val session = JSONObject(raw)
            val ids = session.optJSONArray("songs").toLongList().filter { it >= 0L }.take(MAX_SAVED_QUEUE)
            ids.takeIf { it.isNotEmpty() }?.let {
                SavedPlaybackSession(
                    songIds = it,
                    positionMs = session.optLong("position_ms", 0L).coerceAtLeast(0L),
                    shuffleEnabled = session.optBoolean("shuffle", false),
                    repeatMode = session.optInt("repeat", 0).coerceIn(0, 2),
                    smartShuffleEnabled = session.optBoolean("smart_shuffle", false),
                    preserveQueueOrder = session.optBoolean("preserve_order", false)
                )
            }
        }.getOrNull()
    }

    @Synchronized fun clearPlaybackSession() { preferences.edit().remove(KEY_PLAYBACK_SESSION).apply() }

    @Synchronized fun recentlyPlayed(): List<Long> = readIds(KEY_RECENTS)
    @Synchronized fun recentlySearched(): List<Long> {
        val raw = readIds(KEY_SEARCHED)
        val bounded = boundedUniqueIds(raw, MAX_SEARCH_HISTORY)
        if (bounded != raw) writeIds(KEY_SEARCHED, bounded)
        return bounded
    }

    /** Remove stale MediaStore IDs after a successful full catalog scan, keeping the newest valid searches. */
    @Synchronized
    fun pruneRecentlySearched(validSongIds: Set<Long>): List<Long> {
        val raw = readIds(KEY_SEARCHED)
        val bounded = boundedUniqueIds(raw, MAX_SEARCH_HISTORY, validSongIds)
        if (bounded != raw) writeIds(KEY_SEARCHED, bounded)
        return bounded
    }

    @Synchronized fun clearRecentlySearched() { writeIds(KEY_SEARCHED, emptyList()) }

    @Synchronized
    fun recordPlayed(songId: Long, incrementCount: Boolean = false) {
        if (songId < 0L) return
        writeIds(KEY_RECENTS, (listOf(songId) + readIds(KEY_RECENTS).filterNot { it == songId }).take(MAX_RECENTS))
        if (incrementCount) {
            val counts = readLongMap(KEY_PLAY_COUNTS)
            counts[songId] = (counts[songId] ?: 0L) + 1L
            writeLongMap(KEY_PLAY_COUNTS, counts)
            preferences.edit().putLong(KEY_LAST_PLAYED_AT, System.currentTimeMillis()).apply()
        }
    }

    @Synchronized fun playCounts(): Map<Long, Long> = readLongMap(KEY_PLAY_COUNTS)

    /** Starts are recorded only when playback actually begins, not when a queue is merely built. */
    @Synchronized
    fun recordListeningStart(songId: Long) {
        if (songId < 0L) return
        recordPlayed(songId, incrementCount = false)
        val current = readListeningStats(songId)
        writeListeningStats(
            songId,
            current.copy(starts = current.starts + 1L, lastStartedAtMs = System.currentTimeMillis())
        )
    }

    /** Persist only the newly heard portion; callers batch this instead of writing on every UI tick. */
    @Synchronized
    fun recordListeningProgress(songId: Long, listenedMsDelta: Long, sessionPercent: Float) {
        if (songId < 0L) return
        val current = readListeningStats(songId)
        writeListeningStats(
            songId,
            current.copy(
                listenedMs = current.listenedMs + listenedMsDelta.coerceAtLeast(0L),
                lastListenedPercent = sessionPercent.takeIf(Float::isFinite)?.coerceIn(0f, 100f)
                    ?: current.lastListenedPercent
            )
        )
    }

    @Synchronized
    fun recordListeningCompletion(songId: Long) {
        if (songId < 0L) return
        val current = readListeningStats(songId)
        writeListeningStats(
            songId,
            current.copy(completions = current.completions + 1L, lastListenedPercent = 100f)
        )
    }

    @Synchronized
    fun recordListeningSkip(songId: Long) {
        if (songId < 0L) return
        val current = readListeningStats(songId)
        writeListeningStats(songId, current.copy(skips = current.skips + 1L))
    }

    /** A play count is awarded once per listening session after its local threshold is reached. */
    @Synchronized
    fun recordQualifiedPlay(songId: Long): Map<Long, Long> {
        if (songId < 0L) return playCounts()
        val counts = readLongMap(KEY_PLAY_COUNTS)
        counts[songId] = (counts[songId] ?: 0L) + 1L
        writeLongMap(KEY_PLAY_COUNTS, counts)
        val current = readListeningStats(songId)
        writeListeningStats(songId, current.copy(qualifiedPlays = current.qualifiedPlays + 1L))
        preferences.edit().putLong(KEY_LAST_PLAYED_AT, System.currentTimeMillis()).apply()
        return counts
    }

    @Synchronized
    fun listeningStats(): Map<Long, TrackListeningStats> = buildMap {
        preferences.all.keys.forEach { key ->
            if (!key.startsWith(KEY_LISTENING_STATS_PREFIX)) return@forEach
            val songId = key.removePrefix(KEY_LISTENING_STATS_PREFIX).toLongOrNull()?.takeIf { it >= 0L }
                ?: return@forEach
            put(songId, readListeningStats(songId))
        }
    }

    /** Read one updated track record without rescanning every per-track SharedPreferences entry. */
    @Synchronized
    fun listeningStatsFor(songId: Long): TrackListeningStats =
        if (songId >= 0L) readListeningStats(songId) else TrackListeningStats()

    @Synchronized fun lessLikeArtistNames(): Set<String> = readTasteNames(KEY_LESS_LIKE_ARTISTS)
    @Synchronized fun lessLikeGenreNames(): Set<String> = readTasteNames(KEY_LESS_LIKE_GENRES)

    @Synchronized
    fun toggleLessLikeArtist(name: String): Set<String> {
        val normalized = normalizeTasteKey(name)
        if (normalized.isBlank()) return lessLikeArtistNames()
        val current = lessLikeArtistNames()
        val existing = current.firstOrNull { normalizeTasteKey(it) == normalized }
        return updateTasteNames(KEY_LESS_LIKE_ARTISTS, listOf(name), add = existing == null)
    }

    @Synchronized
    fun setLessLikeArtist(name: String, less: Boolean): Set<String> =
        updateTasteNames(KEY_LESS_LIKE_ARTISTS, listOf(name), add = less)

    @Synchronized
    fun toggleLessLikeGenres(names: Collection<String>): Set<String> {
        val clean = names.map(String::trim).filter(String::isNotBlank).distinctBy(::normalizeTasteKey)
        if (clean.isEmpty()) return lessLikeGenreNames()
        val currentKeys = lessLikeGenreNames().map(::normalizeTasteKey).toSet()
        val add = !clean.all { normalizeTasteKey(it) in currentKeys }
        return updateTasteNames(KEY_LESS_LIKE_GENRES, clean, add)
    }

    @Synchronized
    fun setLessLikeGenre(name: String, less: Boolean): Set<String> =
        updateTasteNames(KEY_LESS_LIKE_GENRES, listOf(name), add = less)

    @Synchronized
    fun recordSearch(songId: Long) {
        if (songId < 0L) return
        writeIds(KEY_SEARCHED, prependBoundedUniqueId(readIds(KEY_SEARCHED), songId, MAX_SEARCH_HISTORY))
    }

    @Synchronized fun hiddenHomeSongIds(): Set<Long> = readIds(KEY_HIDDEN_HOME_SONGS).toSet()

    @Synchronized
    fun setSongHiddenFromHome(songId: Long, hidden: Boolean): Set<Long> {
        val updated = hiddenHomeSongIds().toMutableSet().apply { if (hidden) add(songId) else remove(songId) }
        writeIds(KEY_HIDDEN_HOME_SONGS, updated.toList())
        return updated
    }

    @Synchronized fun hiddenHomeCardIds(): Set<String> = preferences.getStringSet(KEY_HIDDEN_HOME_CARDS, emptySet())?.toSet().orEmpty()

    @Synchronized
    fun setHomeCardHidden(cardId: String, hidden: Boolean): Set<String> {
        val updated = hiddenHomeCardIds().toMutableSet().apply { if (hidden) add(cardId) else remove(cardId) }
        preferences.edit().putStringSet(KEY_HIDDEN_HOME_CARDS, updated).apply()
        return updated
    }

    @Synchronized fun excludedFolders(): Set<String> = preferences.getStringSet(KEY_EXCLUDED_FOLDERS, emptySet())?.toSet().orEmpty()
    @Synchronized fun knownFolders(): List<String> = preferences.getStringSet(KEY_KNOWN_FOLDERS, emptySet())?.toList()?.sorted().orEmpty()
    @Synchronized fun setKnownFolders(folders: Collection<String>) {
        val clean = folders.map(::normalizeFolder).filter(String::isNotBlank).toSet()
        preferences.edit().putStringSet(KEY_KNOWN_FOLDERS, clean).apply()
    }

    @Synchronized
    fun setFolderExcluded(folder: String, excluded: Boolean): Set<String> {
        val normalized = normalizeFolder(folder)
        val updated = excludedFolders().toMutableSet().apply { if (excluded) add(normalized) else remove(normalized) }
        preferences.edit().putStringSet(KEY_EXCLUDED_FOLDERS, updated).apply()
        return updated
    }

    @Synchronized
    fun metadataOverride(songId: Long): SongMetadataOverride? = metadataOverrides()[songId]

    @Synchronized
    fun metadataOverrides(): Map<Long, SongMetadataOverride> = runCatching {
        val array = JSONArray(preferences.getString(KEY_METADATA_OVERRIDES, "[]") ?: "[]")
        buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optLong("id", -1L)
                if (id < 0L) continue
                put(id, SongMetadataOverride(
                    songId = id,
                    title = item.optNullableString("title"),
                    artist = item.optNullableString("artist"),
                    album = item.optNullableString("album"),
                    albumArtist = item.optNullableString("album_artist"),
                    genre = item.optNullableString("genre"),
                    year = item.optInt("year", 0).takeIf { it > 0 },
                    discNumber = item.optInt("disc", 0).takeIf { it > 0 },
                    trackNumber = item.optInt("track", 0).takeIf { it > 0 },
                    artworkUri = item.optNullableString("artwork"),
                    replayGainDb = item.optDouble("replay_gain", Double.NaN).takeIf { it.isFinite() }?.toFloat(),
                    isCompilation = if (item.has("is_compilation")) item.optBoolean("is_compilation") else null
                ))
            }
        }
    }.getOrDefault(emptyMap())

    @Synchronized
    fun saveMetadataOverride(value: SongMetadataOverride) {
        val updated = metadataOverrides().toMutableMap()
        updated[value.songId] = value
        preferences.edit().putString(KEY_METADATA_OVERRIDES, JSONArray().apply {
            updated.values.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.songId)
                    item.title?.let { put("title", it) }
                    item.artist?.let { put("artist", it) }
                    item.album?.let { put("album", it) }
                    item.albumArtist?.let { put("album_artist", it) }
                    item.genre?.let { put("genre", it) }
                    item.year?.let { put("year", it) }
                    item.discNumber?.let { put("disc", it) }
                    item.trackNumber?.let { put("track", it) }
                    item.artworkUri?.let { put("artwork", it) }
                    item.replayGainDb?.let { put("replay_gain", it.toDouble()) }
                    item.isCompilation?.let { put("is_compilation", it) }
                })
            }
        }.toString()).apply()
    }

    @Synchronized fun playlists(): List<Playlist> = readPlaylists()

    @Synchronized
    fun createPlaylist(name: String): Playlist? {
        val cleanName = name.trim().take(48)
        if (cleanName.isBlank()) return null
        val playlist = Playlist(UUID.randomUUID().toString(), cleanName, emptyList())
        savePlaylists(playlists() + playlist)
        return playlist
    }

    @Synchronized
    fun addSongToPlaylist(playlistId: String, songId: Long) {
        if (songId < 0L) return
        savePlaylists(playlists().map { playlist ->
            if (playlist.id == playlistId && songId !in playlist.songIds) playlist.copy(songIds = playlist.songIds + songId) else playlist
        })
    }

    @Synchronized
    fun addSongsToPlaylist(playlistId: String, songIds: Collection<Long>) {
        val additions = songIds.filter { it >= 0L }.toSet()
        if (additions.isEmpty()) return
        savePlaylists(playlists().map { playlist ->
            if (playlist.id == playlistId) playlist.copy(songIds = (playlist.songIds + additions).distinct()) else playlist
        })
    }

    @Synchronized
    fun removeSongFromPlaylist(playlistId: String, songId: Long) {
        savePlaylists(playlists().map { playlist ->
            if (playlist.id == playlistId) playlist.copy(songIds = playlist.songIds.filterNot { it == songId }) else playlist
        })
    }

    @Synchronized fun deletePlaylist(playlistId: String) { savePlaylists(playlists().filterNot { it.id == playlistId }) }

    @Synchronized fun savedQueues(): List<SavedQueue> = readSavedQueues()

    @Synchronized
    fun saveQueue(name: String, songIds: List<Long>): SavedQueue? {
        val safeName = name.trim().take(48)
        val distinctIds = songIds.distinct()
        // Reject the entire save rather than silently dropping temporary shared-track IDs.
        if (distinctIds.any { it < 0L }) return null
        val safeIds = distinctIds.take(MAX_SAVED_QUEUE)
        if (safeName.isBlank() || safeIds.isEmpty()) return null
        val queue = SavedQueue(UUID.randomUUID().toString(), safeName, safeIds, System.currentTimeMillis())
        writeSavedQueues(savedQueues() + queue)
        return queue
    }

    @Synchronized fun deleteSavedQueue(queueId: String) { writeSavedQueues(savedQueues().filterNot { it.id == queueId }) }

    @Synchronized fun smartPlaylists(): List<SmartPlaylist> = runCatching {
        val array = JSONArray(preferences.getString(KEY_SMART_PLAYLISTS, "[]") ?: "[]")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(SmartPlaylist(item.optString("id"), item.optString("name"), item.optString("kind"), item.optString("value")))
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun saveSmartPlaylist(name: String, kind: String, value: String = ""): SmartPlaylist? {
        val cleanName = name.trim().take(48)
        if (cleanName.isBlank() || kind !in ALLOWED_SMART_KINDS) return null
        val item = SmartPlaylist(UUID.randomUUID().toString(), cleanName, kind, value.trim().take(80))
        writeSmartPlaylists(smartPlaylists() + item)
        return item
    }

    @Synchronized fun deleteSmartPlaylist(id: String) { writeSmartPlaylists(smartPlaylists().filterNot { it.id == id }) }

    /** Portable app data only: no audio bytes or original files are read or copied. */
    @Synchronized
    fun exportPortableData(): JSONObject = JSONObject()
        .put("favorites", JSONArray(favorites().toList()))
        .put("recently_played", JSONArray(recentlyPlayed()))
        .put("recently_searched", JSONArray(recentlySearched()))
        .put("hidden_home_songs", JSONArray(hiddenHomeSongIds().toList()))
        .put("hidden_home_cards", JSONArray(hiddenHomeCardIds().toList()))
        .put("excluded_folders", JSONArray(excludedFolders().toList()))
        .put("known_folders", JSONArray(knownFolders()))
        .put("playlists", playlistsJson(playlists()))
        .put("saved_queues", savedQueuesJson(savedQueues()))
        .put("smart_playlists", smartPlaylistsJson(smartPlaylists()))
        .put("play_counts", longMapJson(playCounts()))
        .put("listening_stats", JSONArray().apply {
            listeningStats().forEach { (songId, stats) -> put(listeningStatsJson(songId, stats)) }
        })
        .put("less_like_artists", JSONArray(lessLikeArtistNames().sorted()))
        .put("less_like_genres", JSONArray(lessLikeGenreNames().sorted()))
        .put("last_played_at_ms", preferences.getLong(KEY_LAST_PLAYED_AT, 0L))
        .put("metadata_overrides", runCatching { JSONArray(preferences.getString(KEY_METADATA_OVERRIDES, "[]") ?: "[]") }.getOrDefault(JSONArray()))

    @Synchronized
    fun importPortableData(data: JSONObject) {
        fun ids(key: String): List<Long> = data.optJSONArray(key)?.toLongList()?.filter { it >= 0L }.orEmpty()
        val editor = preferences.edit()
            .putString(KEY_FAVORITES, ids("favorites").distinct().joinToString(","))
            .putString(KEY_RECENTS, ids("recently_played").distinct().take(MAX_RECENTS).joinToString(","))
            .putString(KEY_SEARCHED, ids("recently_searched").distinct().take(MAX_SEARCH_HISTORY).joinToString(","))
            .putString(KEY_HIDDEN_HOME_SONGS, ids("hidden_home_songs").distinct().joinToString(","))
            .putStringSet(KEY_HIDDEN_HOME_CARDS, data.optJSONArray("hidden_home_cards").toStringList().toSet())
            .putStringSet(KEY_LESS_LIKE_ARTISTS, data.optJSONArray("less_like_artists").toStringList().filter(String::isNotBlank).toSet())
            .putStringSet(KEY_LESS_LIKE_GENRES, data.optJSONArray("less_like_genres").toStringList().filter(String::isNotBlank).toSet())
            .putStringSet(KEY_EXCLUDED_FOLDERS, data.optJSONArray("excluded_folders").toStringList().map(::normalizeFolder).toSet())
            .putStringSet(KEY_KNOWN_FOLDERS, data.optJSONArray("known_folders").toStringList().map(::normalizeFolder).filter(String::isNotBlank).toSet())
            .putString(KEY_PLAYLISTS, playlistsJson(parsePlaylists(data.optJSONArray("playlists"))).toString())
            .putString(KEY_SAVED_QUEUES, savedQueuesJson(parseSavedQueues(data.optJSONArray("saved_queues"))).toString())
            .putString(KEY_SMART_PLAYLISTS, smartPlaylistsJson(parseSmartPlaylists(data.optJSONArray("smart_playlists"))).toString())
            .putString(KEY_PLAY_COUNTS, data.optJSONObject("play_counts")?.toString() ?: "{}")
            .putLong(KEY_LAST_PLAYED_AT, data.optLong("last_played_at_ms", 0L).coerceAtLeast(0L))
            .putString(KEY_METADATA_OVERRIDES, data.optJSONArray("metadata_overrides")?.toString() ?: "[]")
        preferences.all.keys.filter { it.startsWith(KEY_LISTENING_STATS_PREFIX) }.forEach(editor::remove)
        val listeningStats = data.optJSONArray("listening_stats")
        for (index in 0 until (listeningStats?.length() ?: 0)) {
            val item = listeningStats?.optJSONObject(index) ?: continue
            val songId = item.optLong("id", -1L)
            if (songId >= 0L) editor.putString(listeningStatsKey(songId), statsPayload(item).toString())
        }
        editor.apply()
    }

    private fun readListeningStats(songId: Long): TrackListeningStats = runCatching {
        val json = JSONObject(preferences.getString(listeningStatsKey(songId), "{}") ?: "{}")
        TrackListeningStats(
            starts = json.optLong("starts", 0L).coerceAtLeast(0L),
            listenedMs = json.optLong("listened_ms", 0L).coerceAtLeast(0L),
            completions = json.optLong("completions", 0L).coerceAtLeast(0L),
            skips = json.optLong("skips", 0L).coerceAtLeast(0L),
            qualifiedPlays = json.optLong("qualified_plays", 0L).coerceAtLeast(0L),
            lastListenedPercent = json.optDouble("last_percent", 0.0).toFloat().coerceIn(0f, 100f),
            lastStartedAtMs = json.optLong("last_started_at_ms", 0L).coerceAtLeast(0L)
        )
    }.getOrDefault(TrackListeningStats())

    private fun writeListeningStats(songId: Long, stats: TrackListeningStats) {
        preferences.edit().putString(
            listeningStatsKey(songId),
            JSONObject()
                .put("starts", stats.starts.coerceAtLeast(0L))
                .put("listened_ms", stats.listenedMs.coerceAtLeast(0L))
                .put("completions", stats.completions.coerceAtLeast(0L))
                .put("skips", stats.skips.coerceAtLeast(0L))
                .put("qualified_plays", stats.qualifiedPlays.coerceAtLeast(0L))
                .put("last_percent", stats.lastListenedPercent.takeIf(Float::isFinite)?.coerceIn(0f, 100f) ?: 0f)
                .put("last_started_at_ms", stats.lastStartedAtMs.coerceAtLeast(0L))
                .toString()
        ).apply()
    }

    private fun listeningStatsJson(songId: Long, stats: TrackListeningStats): JSONObject = JSONObject()
        .put("id", songId)
        .put("starts", stats.starts)
        .put("listened_ms", stats.listenedMs)
        .put("completions", stats.completions)
        .put("skips", stats.skips)
        .put("qualified_plays", stats.qualifiedPlays)
        .put("last_percent", stats.lastListenedPercent)
        .put("last_started_at_ms", stats.lastStartedAtMs)

    private fun statsPayload(item: JSONObject): JSONObject = JSONObject()
        .put("starts", item.optLong("starts", 0L).coerceAtLeast(0L))
        .put("listened_ms", item.optLong("listened_ms", 0L).coerceAtLeast(0L))
        .put("completions", item.optLong("completions", 0L).coerceAtLeast(0L))
        .put("skips", item.optLong("skips", 0L).coerceAtLeast(0L))
        .put("qualified_plays", item.optLong("qualified_plays", 0L).coerceAtLeast(0L))
        .put("last_percent", item.optDouble("last_percent", 0.0).takeIf(Double::isFinite)?.coerceIn(0.0, 100.0) ?: 0.0)
        .put("last_started_at_ms", item.optLong("last_started_at_ms", 0L).coerceAtLeast(0L))

    private fun listeningStatsKey(songId: Long): String = KEY_LISTENING_STATS_PREFIX + songId

    private fun readTasteNames(key: String): Set<String> = preferences.getStringSet(key, emptySet())
        ?.map(String::trim)?.filter(String::isNotBlank)?.toSet().orEmpty()

    private fun updateTasteNames(key: String, names: Collection<String>, add: Boolean): Set<String> {
        val updated = readTasteNames(key).toMutableSet()
        names.map(String::trim).filter(String::isNotBlank).forEach { name ->
            val normalized = normalizeTasteKey(name)
            val existing = updated.firstOrNull { normalizeTasteKey(it) == normalized }
            if (add && existing == null) updated += name
            else if (!add && existing != null) updated -= existing
        }
        preferences.edit().putStringSet(key, updated).apply()
        return updated
    }

    private fun readIds(key: String): List<Long> = preferences.getString(key, "").orEmpty().split(',').mapNotNull(String::toLongOrNull)
    private fun writeIds(key: String, values: List<Long>) { preferences.edit().putString(key, values.joinToString(",")).apply() }
    private fun readLongMap(key: String): MutableMap<Long, Long> = runCatching {
        val json = JSONObject(preferences.getString(key, "{}") ?: "{}")
        buildMap { json.keys().forEach { key -> key.toLongOrNull()?.let { put(it, json.optLong(key, 0L)) } } }.toMutableMap()
    }.getOrDefault(mutableMapOf())
    private fun writeLongMap(key: String, values: Map<Long, Long>) {
        preferences.edit().putString(key, JSONObject().apply { values.forEach { (id, count) -> put(id.toString(), count) } }.toString()).apply()
    }

    private fun readPlaylists(): List<Playlist> = parsePlaylists(runCatching {
        JSONArray(preferences.getString(KEY_PLAYLISTS, "[]") ?: "[]")
    }.getOrDefault(JSONArray()))
    private fun parsePlaylists(array: JSONArray?): List<Playlist> = buildList {
        for (index in 0 until (array?.length() ?: 0)) {
            val item = array?.optJSONObject(index) ?: continue
            add(Playlist(item.optString("id"), item.optString("name", "Playlist"), item.optJSONArray("songs").toLongList().filter { it >= 0L }))
        }
    }
    private fun savePlaylists(values: List<Playlist>) { preferences.edit().putString(KEY_PLAYLISTS, playlistsJson(values).toString()).apply() }
    private fun playlistsJson(values: List<Playlist>) = JSONArray().apply {
        values.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("songs", JSONArray(it.songIds))) }
    }

    private fun readSavedQueues(): List<SavedQueue> = parseSavedQueues(runCatching {
        JSONArray(preferences.getString(KEY_SAVED_QUEUES, "[]") ?: "[]")
    }.getOrDefault(JSONArray()))
    private fun parseSavedQueues(array: JSONArray?): List<SavedQueue> = buildList {
        for (index in 0 until (array?.length() ?: 0)) {
            val item = array?.optJSONObject(index) ?: continue
            add(SavedQueue(item.optString("id"), item.optString("name", "Queue"), item.optJSONArray("songs").toLongList().filter { it >= 0L }.take(MAX_SAVED_QUEUE), item.optLong("saved_at", 0L)))
        }
    }
    private fun writeSavedQueues(values: List<SavedQueue>) { preferences.edit().putString(KEY_SAVED_QUEUES, savedQueuesJson(values).toString()).apply() }
    private fun savedQueuesJson(values: List<SavedQueue>) = JSONArray().apply {
        values.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("songs", JSONArray(it.songIds)).put("saved_at", it.savedAtMs)) }
    }

    private fun parseSmartPlaylists(array: JSONArray?): List<SmartPlaylist> = buildList {
        for (index in 0 until (array?.length() ?: 0)) {
            val item = array?.optJSONObject(index) ?: continue
            val kind = item.optString("kind")
            if (kind in ALLOWED_SMART_KINDS) add(SmartPlaylist(item.optString("id"), item.optString("name"), kind, item.optString("value")))
        }
    }
    private fun writeSmartPlaylists(values: List<SmartPlaylist>) { preferences.edit().putString(KEY_SMART_PLAYLISTS, smartPlaylistsJson(values).toString()).apply() }
    private fun smartPlaylistsJson(values: List<SmartPlaylist>) = JSONArray().apply {
        values.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind).put("value", it.value)) }
    }
    private fun longMapJson(values: Map<Long, Long>) = JSONObject().apply { values.forEach { (id, count) -> put(id.toString(), count) } }

    private fun normalizeFolder(path: String): String = path.trim().replace('\\', '/').trim('/')
    private fun JSONArray?.toLongList(): List<Long> = if (this == null) emptyList() else buildList {
        for (index in 0 until length()) { val value = optLong(index, -1L); if (value >= 0L) add(value) }
    }
    private fun JSONArray?.toStringList(): List<String> = if (this == null) emptyList() else buildList {
        for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
    }
    private fun JSONObject.optNullableString(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

    private companion object {
        const val KEY_FAVORITES = "favorite_ids"
        const val KEY_RECENTS = "recent_song_ids"
        const val KEY_SEARCHED = "recent_search_song_ids"
        const val KEY_HIDDEN_HOME_SONGS = "hidden_home_song_ids"
        const val KEY_HIDDEN_HOME_CARDS = "hidden_home_card_ids"
        const val KEY_PLAYLISTS = "playlists_json"
        const val KEY_PLAYBACK_SESSION = "playback_session_json"
        const val KEY_PLAY_COUNTS = "play_counts_json"
        const val KEY_LAST_PLAYED_AT = "last_played_at"
        const val KEY_LISTENING_STATS_PREFIX = "listening_stats_"
        const val KEY_LESS_LIKE_ARTISTS = "less_like_artists"
        const val KEY_LESS_LIKE_GENRES = "less_like_genres"
        const val KEY_EXCLUDED_FOLDERS = "excluded_folders"
        const val KEY_KNOWN_FOLDERS = "known_folders"
        const val KEY_METADATA_OVERRIDES = "metadata_overrides_json"
        const val KEY_SAVED_QUEUES = "saved_queues_json"
        const val KEY_SMART_PLAYLISTS = "smart_playlists_json"
        const val MAX_SAVED_QUEUE = 100
        const val MAX_RECENTS = 60
        const val MAX_SEARCH_HISTORY = 100
        val ALLOWED_SMART_KINDS = setOf("recently_added", "most_played", "never_played", "artist", "year", "genre")
    }
}

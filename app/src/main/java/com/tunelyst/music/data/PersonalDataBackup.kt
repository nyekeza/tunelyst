package com.tunelyst.music.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

data class BackupImportResult(
    val unmatchedTrackCount: Int = 0,
    val usedLegacyMediaStoreIds: Boolean = false
)

/** JSON backup of app-owned metadata/preferences only. Audio files are never opened or copied. */
class PersonalDataBackup(context: Context) {
    private val library = LibraryStore(context)
    private val appSettings = AppSettingsStore(context)
    private val audioSettings = AudioSettingsStore(context)

    fun exportJson(songs: List<Song> = emptyList()): String {
        val portableLibrary = library.exportPortableData().apply {
            // Filesystem paths and picked artwork URIs are local to the source device.
            put("excluded_folders", JSONArray())
            put("known_folders", JSONArray())
            optJSONArray("metadata_overrides")?.let { overrides ->
                for (index in 0 until overrides.length()) overrides.optJSONObject(index)?.remove("artwork")
            }
        }
        val json = JSONObject()
            .put("format", CURRENT_FORMAT)
            .put("version", CURRENT_VERSION)
            .put("created_at_ms", System.currentTimeMillis())
            .put("notes", "Audio files, device-specific folder paths, and picked artwork URIs are not included.")
            .put("library", portableLibrary)
            .put("song_catalog", catalogFor(songs))
            .put("appearance", appSettings.exportPortableData())
            .put("audio", audioSettings.exportPortableData())
            .toString(2)
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) {
            "The exported backup exceeds the 32 MiB restore limit."
        }
        return json
    }

    /**
     * Validates the envelope before writing and remaps version-2+ track references by stable
     * file/tag fingerprints. Ambiguous matches are deliberately left unresolved instead of
     * attaching personal data to a possibly unrelated song.
     */
    fun importJson(json: String, currentSongs: List<Song> = emptyList()): BackupImportResult {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) {
            "The selected backup exceeds the 32 MiB restore limit."
        }
        val root = JSONObject(json)
        require(root.optString("format") in ACCEPTED_FORMATS) { "This file is not a Tunelyst personal-data backup." }
        val version = root.optInt("version", -1)
        require(version in 1..CURRENT_VERSION) { "This backup version is not supported." }
        require(!root.has("appearance") || root.optJSONObject("appearance") != null) { "The appearance section is invalid." }
        require(!root.has("audio") || root.optJSONObject("audio") != null) { "The audio-settings section is invalid." }
        val sourceLibrary = root.optJSONObject("library")
            ?: throw IllegalArgumentException("The backup does not contain a valid library section.")
        validateLibrarySection(sourceLibrary)
        val libraryToImport = JSONObject(sourceLibrary.toString())
        var result = BackupImportResult(usedLegacyMediaStoreIds = version == 1)

        if (version >= 2) {
            val catalog = root.optJSONArray("song_catalog")
                ?: throw IllegalArgumentException("This backup is missing its track-matching catalog.")
            validateCatalog(catalog)
            require(currentSongs.isNotEmpty() || catalog.length() == 0) {
                "Scan your music library before restoring this portable backup."
            }
            val remap = buildTrackIdRemap(catalog, currentSongs)
            val unresolved = remapPortableReferences(libraryToImport, remap)
            result = BackupImportResult(unmatchedTrackCount = unresolved.size)
        }
        libraryToImport.put("excluded_folders", JSONArray())
            .put("known_folders", JSONArray())
        libraryToImport.optJSONArray("metadata_overrides")?.let { overrides ->
            for (index in 0 until overrides.length()) overrides.optJSONObject(index)?.remove("artwork")
        }

        val appearance = root.optJSONObject("appearance")
        val audio = root.optJSONObject("audio")
        // Keep snapshots so a malformed section or failed preference write cannot leave a partial restore.
        val oldLibrary = library.exportPortableData()
        val oldAppearance = appSettings.exportPortableData()
        val oldAudio = audioSettings.exportPortableData()
        try {
            library.importPortableData(libraryToImport)
            appearance?.let(appSettings::importPortableData)
            audio?.let(audioSettings::importPortableData)
        } catch (failure: Exception) {
            runCatching { library.importPortableData(oldLibrary) }
            runCatching { appSettings.importPortableData(oldAppearance) }
            runCatching { audioSettings.importPortableData(oldAudio) }
            throw failure
        }
        return result
    }

    private fun validateLibrarySection(data: JSONObject) {
        val arrayKeys = listOf(
            "favorites", "recently_played", "recently_searched", "hidden_home_songs",
            "hidden_home_cards", "excluded_folders", "known_folders", "playlists",
            "saved_queues", "smart_playlists", "metadata_overrides", "listening_stats",
            "less_like_artists", "less_like_genres"
        )
        arrayKeys.forEach { key ->
            require(!data.has(key) || data.optJSONArray(key) != null) { "The backup library field '$key' is invalid." }
        }
        require(!data.has("play_counts") || data.optJSONObject("play_counts") != null) {
            "The backup play-count data is invalid."
        }
        listOf("playlists", "saved_queues", "smart_playlists", "metadata_overrides", "listening_stats").forEach { key ->
            val entries = data.optJSONArray(key) ?: return@forEach
            for (index in 0 until entries.length()) {
                require(entries.optJSONObject(index) != null) { "An entry in '$key' is invalid." }
            }
        }
        listOf("playlists", "saved_queues").forEach { key ->
            val entries = data.optJSONArray(key) ?: return@forEach
            for (index in 0 until entries.length()) {
                require(entries.optJSONObject(index)?.optJSONArray("songs") != null) {
                    "A queue in '$key' is missing its track list."
                }
            }
        }
        data.optJSONArray("listening_stats")?.let { entries ->
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index)
                require(item != null && item.optLong("id", -1L) >= 0L) {
                    "A listening-history entry is invalid."
                }
                require(item.optLong("starts", 0L) >= 0L && item.optLong("listened_ms", 0L) >= 0L &&
                    item.optLong("completions", 0L) >= 0L && item.optLong("skips", 0L) >= 0L &&
                    item.optLong("qualified_plays", 0L) >= 0L
                ) { "A listening-history entry contains an invalid count." }
            }
        }
    }

    private fun validateCatalog(catalog: JSONArray) {
        for (index in 0 until catalog.length()) {
            val item = catalog.optJSONObject(index)
                ?: throw IllegalArgumentException("A track-matching entry in this backup is invalid.")
            require(item.optLong("id", -1L) >= 0L &&
                item.optString("exact_key").isNotBlank() && item.optString("tag_key").isNotBlank()
            ) { "A track-matching entry in this backup is incomplete." }
        }
    }

    private fun catalogFor(songs: List<Song>): JSONArray = JSONArray().apply {
        songs.asSequence().filter { it.id >= 0L }.distinctBy(Song::id).forEach { song ->
            put(
                JSONObject()
                    .put("id", song.id)
                    .put("exact_key", exactKey(song))
                    .put("tag_key", tagKey(song))
            )
        }
    }

    private fun buildTrackIdRemap(catalog: JSONArray, songs: List<Song>): Map<Long, Long> {
        val localSongs = songs.filter { it.id >= 0L }.distinctBy(Song::id)
        val byExactKey = localSongs.groupBy(::exactKey)
        val byTagKey = localSongs.groupBy(::tagKey)
        val remap = LinkedHashMap<Long, Long>()
        val claimedLocalIds = HashSet<Long>()
        for (index in 0 until catalog.length()) {
            val source = catalog.optJSONObject(index) ?: continue
            val oldId = source.optLong("id", -1L)
            if (oldId < 0L) continue
            val exact = source.optString("exact_key").takeIf(String::isNotBlank)
            val tag = source.optString("tag_key").takeIf(String::isNotBlank)
            val exactMatches = exact?.let(byExactKey::get).orEmpty()
            val candidates = when {
                exactMatches.size == 1 -> exactMatches
                exactMatches.size > 1 -> emptyList()
                else -> tag?.let(byTagKey::get).orEmpty().takeIf { it.size == 1 }.orEmpty()
            }
            val candidate = candidates.singleOrNull() ?: continue
            if (claimedLocalIds.add(candidate.id)) remap[oldId] = candidate.id
        }
        return remap
    }

    private fun remapPortableReferences(data: JSONObject, remap: Map<Long, Long>): Set<Long> {
        val unresolved = linkedSetOf<Long>()
        fun remapIds(array: JSONArray?): JSONArray = JSONArray().apply {
            for (index in 0 until (array?.length() ?: 0)) {
                val oldId = array?.optLong(index, -1L) ?: -1L
                if (oldId < 0L) continue
                val newId = remap[oldId]
                if (newId != null) put(newId) else unresolved += oldId
            }
        }

        listOf("favorites", "recently_played", "recently_searched", "hidden_home_songs").forEach { key ->
            if (data.has(key)) data.put(key, remapIds(data.optJSONArray(key)))
        }
        listOf("playlists", "saved_queues").forEach { key ->
            val entries = data.optJSONArray(key) ?: return@forEach
            val transformed = JSONArray()
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val copy = JSONObject(item.toString())
                copy.put("songs", remapIds(item.optJSONArray("songs")))
                transformed.put(copy)
            }
            data.put(key, transformed)
        }

        data.optJSONArray("listening_stats")?.let { entries ->
            val transformed = JSONArray()
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val oldId = item.optLong("id", -1L)
                val newId = remap[oldId]
                if (newId != null) transformed.put(JSONObject(item.toString()).put("id", newId))
                else if (oldId >= 0L) unresolved += oldId
            }
            data.put("listening_stats", transformed)
        }

        data.optJSONObject("play_counts")?.let { counts ->
            val transformed = JSONObject()
            val keys = counts.keys()
            while (keys.hasNext()) {
                val oldId = keys.next().toLongOrNull() ?: continue
                if (oldId < 0L) continue
                val newId = remap[oldId]
                if (newId != null) transformed.put(newId.toString(), counts.optLong(oldId.toString(), 0L))
                else unresolved += oldId
            }
            data.put("play_counts", transformed)
        }

        data.optJSONArray("metadata_overrides")?.let { overrides ->
            val transformed = JSONArray()
            for (index in 0 until overrides.length()) {
                val item = overrides.optJSONObject(index) ?: continue
                val oldId = item.optLong("id", -1L)
                val newId = remap[oldId]
                if (newId != null) transformed.put(JSONObject(item.toString()).put("id", newId))
                else if (oldId >= 0L) unresolved += oldId
            }
            data.put("metadata_overrides", transformed)
        }
        return unresolved
    }

    private fun exactKey(song: Song): String = hashKey(
        listOf(
            normalize(song.fileName),
            song.sizeBytes.coerceAtLeast(0L).toString(),
            (song.durationMs.coerceAtLeast(0L) / DURATION_BUCKET_MS).toString()
        ).joinToString("|")
    )

    private fun tagKey(song: Song): String = hashKey(
        listOf(
            normalize(song.title),
            normalize(song.artist),
            normalize(song.albumArtist),
            normalize(song.album),
            (song.durationMs.coerceAtLeast(0L) / DURATION_BUCKET_MS).toString(),
            song.trackNumber.toString(),
            song.discNumber.toString()
        ).joinToString("|")
    )

    private fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE, " ")

    private fun hashKey(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
        private const val CURRENT_VERSION = 3
        private const val CURRENT_FORMAT = "tunelyst-personal-data"
        private val ACCEPTED_FORMATS = setOf(CURRENT_FORMAT, "luma-personal-data")
        private const val DURATION_BUCKET_MS = 2_000L
        private val WHITESPACE = Regex("\\s+")
    }
}

package com.tunelyst.music.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Per-track listening history persistence, kept separate from the rest of the library store. */
internal class ListeningStatsStore(private val preferences: SharedPreferences) {
    @Synchronized
    fun recordListeningStart(songId: Long) {
        if (songId < 0L) return
        val current = readListeningStats(songId)
        writeListeningStats(songId, current.copy(starts = current.starts + 1L, lastStartedAtMs = System.currentTimeMillis()))
    }

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
        writeListeningStats(songId, current.copy(completions = current.completions + 1L, lastListenedPercent = 100f))
    }

    @Synchronized
    fun recordListeningSkip(songId: Long) {
        if (songId < 0L) return
        val current = readListeningStats(songId)
        writeListeningStats(songId, current.copy(skips = current.skips + 1L))
    }

    @Synchronized
    fun recordQualifiedPlay(songId: Long) {
        if (songId < 0L) return
        val current = readListeningStats(songId)
        writeListeningStats(songId, current.copy(qualifiedPlays = current.qualifiedPlays + 1L))
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

    @Synchronized
    fun listeningStatsFor(songId: Long): TrackListeningStats =
        if (songId >= 0L) readListeningStats(songId) else TrackListeningStats()

    @Synchronized
    fun exportPortableStats(): JSONArray = JSONArray().apply {
        listeningStats().forEach { (songId, stats) -> put(listeningStatsJson(songId, stats)) }
    }

    @Synchronized
    fun addPortableImport(editor: SharedPreferences.Editor, items: JSONArray?) {
        preferences.all.keys.filter { it.startsWith(KEY_LISTENING_STATS_PREFIX) }.forEach(editor::remove)
        for (index in 0 until (items?.length() ?: 0)) {
            val item = items?.optJSONObject(index) ?: continue
            val songId = item.optLong("id", -1L)
            if (songId >= 0L) editor.putString(listeningStatsKey(songId), statsPayload(item).toString())
        }
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
    private companion object {
        const val KEY_LISTENING_STATS_PREFIX = "listening_stats_"
    }
}

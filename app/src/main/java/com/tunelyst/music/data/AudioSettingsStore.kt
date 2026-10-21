package com.tunelyst.music.data

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

enum class CrossfadeCurve(val storageValue: String, val title: String) {
    EQUAL_POWER("equal_power", "Equal power"),
    LINEAR("linear", "Linear");

    companion object {
        fun fromStorage(value: String?): CrossfadeCurve = entries.firstOrNull { it.storageValue == value } ?: EQUAL_POWER
    }
}

data class SleepTimerFadeWindow(val startElapsedMs: Long, val endElapsedMs: Long)

data class SleepTimerSchedule(val endElapsedMs: Long, val fadeOutSeconds: Int)

/**
 * Resolves a persisted sleep timer against the current boot. The monotonic deadline is authoritative
 * while the device has not rebooted; after reboot, the saved wall deadline is rebased onto the new
 * elapsed-realtime clock. Invalid or stale schedules fail closed rather than extending playback.
 */
internal fun resolveSleepTimerDeadline(
    storedEndElapsedMs: Long,
    storedEndWallClockMs: Long,
    storedDurationMs: Long,
    storedBootCount: Int,
    currentBootCount: Int,
    storedBootEpochEstimateMs: Long,
    currentBootEpochEstimateMs: Long,
    nowElapsedMs: Long,
    nowWallClockMs: Long,
    maxDurationMs: Long
): Long? {
    if (maxDurationMs <= 0L || storedEndElapsedMs <= 0L || storedEndWallClockMs <= 0L ||
        storedDurationMs !in 1L..maxDurationMs || nowElapsedMs < 0L || nowWallClockMs <= 0L
    ) return null

    val sameBoot = if (storedBootCount >= 0 && currentBootCount >= 0) {
        storedBootCount == currentBootCount
    } else {
        storedBootEpochEstimateMs > 0L && currentBootEpochEstimateMs > 0L &&
            kotlin.math.abs(currentBootEpochEstimateMs - storedBootEpochEstimateMs) <= BOOT_EPOCH_TOLERANCE_MS
    }

    if (sameBoot) {
        val remainingElapsedMs = storedEndElapsedMs - nowElapsedMs
        return storedEndElapsedMs.takeIf { remainingElapsedMs in 1L..storedDurationMs }
    }

    val remainingWallClockMs = storedEndWallClockMs - nowWallClockMs
    if (remainingWallClockMs !in 1L..storedDurationMs || nowElapsedMs > Long.MAX_VALUE - remainingWallClockMs) return null
    return nowElapsedMs + remainingWallClockMs
}

private const val BOOT_EPOCH_TOLERANCE_MS = 60_000L

/** Media3 requires finite playback rates; malformed persisted/imported floats fall back to normal speed. */
internal fun sanitizePlaybackParameter(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0.5f, 2f) else 1f

/** Shared on-device audio settings consumed by both the UI and playback service. */
data class AudioSettingsState(
    val equalizerEnabled: Boolean = false,
    val equalizerPreset: String = AudioSettingsContract.PRESET_FLAT,
    val bandLevelsDb: List<Int> = AudioSettingsContract.FLAT_LEVELS,
    val crossfadeSeconds: Int = 0,
    val crossfadeCurve: CrossfadeCurve = CrossfadeCurve.EQUAL_POWER,
    val crossfadeAlbumBoundariesOnly: Boolean = false,
    val manualSkipFadeSeconds: Int = 0,
    val playbackSpeed: Float = 1f,
    val playbackPitch: Float = 1f,
    val normalizeVolume: Boolean = false,
    val equalizerDeviceName: String = "Built-in speaker"
)

object AudioSettingsContract {
    const val PREFERENCES_NAME = "tunelyst_audio_settings"
    const val KEY_EQUALIZER_ENABLED = "equalizer_enabled"
    const val KEY_EQUALIZER_PRESET = "equalizer_preset"
    const val KEY_CROSSFADE_SECONDS = "crossfade_seconds"
    const val KEY_CROSSFADE_CURVE = "crossfade_curve"
    const val KEY_CROSSFADE_ALBUM_BOUNDARIES_ONLY = "crossfade_album_boundaries_only"
    const val KEY_MANUAL_SKIP_FADE_SECONDS = "manual_skip_fade_seconds"
    const val KEY_EQ_DEVICE_PROFILES = "equalizer_device_profiles_v1"
    const val KEY_PLAYBACK_SPEED = "playback_speed"
    const val KEY_PLAYBACK_PITCH = "playback_pitch"
    const val KEY_NORMALIZE_VOLUME = "normalize_volume"
    const val KEY_SLEEP_FADE_START_ELAPSED_MS = "sleep_fade_start_elapsed_ms"
    const val KEY_SLEEP_FADE_END_ELAPSED_MS = "sleep_fade_end_elapsed_ms"
    const val KEY_SLEEP_TIMER_END_ELAPSED_MS = "sleep_timer_end_elapsed_ms"
    const val KEY_SLEEP_TIMER_END_WALL_CLOCK_MS = "sleep_timer_end_wall_clock_ms"
    const val KEY_SLEEP_TIMER_DURATION_MS = "sleep_timer_duration_ms"
    const val KEY_SLEEP_TIMER_BOOT_COUNT = "sleep_timer_boot_count"
    const val KEY_SLEEP_TIMER_BOOT_EPOCH_ESTIMATE_MS = "sleep_timer_boot_epoch_estimate_ms"
    const val KEY_SLEEP_TIMER_FADE_OUT_SECONDS = "sleep_timer_fade_out_seconds"
    const val BAND_KEY_PREFIX = "equalizer_band_v2_"
    const val BAND_COUNT = 10
    const val MIN_BAND_DB = -12
    const val MAX_BAND_DB = 12
    const val MAX_CROSSFADE_SECONDS = 30
    const val MAX_MANUAL_SKIP_FADE_SECONDS = 5

    const val PRESET_FLAT = "Flat"
    const val PRESET_BASS = "Bass Boost"
    const val PRESET_ROCK = "Rock"
    const val PRESET_POP = "Pop"
    const val PRESET_CLASSICAL = "Classical"
    const val PRESET_JAZZ = "Jazz"
    const val PRESET_HIPHOP = "Hip-Hop"
    const val PRESET_VOCAL = "Vocal"
    const val PRESET_TREBLE = "Treble"
    const val PRESET_CUSTOM = "Custom"

    val FLAT_LEVELS = listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    val PRESET_LEVELS = mapOf(
        PRESET_FLAT to FLAT_LEVELS,
        PRESET_BASS to listOf(6, 8, 7, 5, 3, 1, 0, 0, 1, 2),
        PRESET_ROCK to listOf(-2, 3, 5, 3, -1, 2, 4, 6, 5, 4),
        PRESET_POP to listOf(-1, 2, 4, 5, 3, 0, -1, -1, 2, 3),
        PRESET_CLASSICAL to listOf(4, 3, 2, 1, -1, -1, 0, 2, 3, 4),
        PRESET_JAZZ to listOf(3, 4, 5, 3, -1, -2, 0, 2, 3, 4),
        PRESET_HIPHOP to listOf(6, 8, 6, 2, 0, 1, 3, 4, 2, 1),
        PRESET_VOCAL to listOf(-3, -2, 0, 3, 6, 7, 5, 3, 1, 0),
        PRESET_TREBLE to listOf(-4, -3, -1, 0, 2, 4, 6, 8, 9, 10)
    )

    internal const val LEGACY_BAND_KEY_PREFIX = "equalizer_band_"
    internal const val KEY_EQ_BANDS_MIGRATED = "equalizer_bands_v2_migrated"
}

class AudioSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        AudioSettingsContract.PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    init {
        migrateLegacyBands()
        seedCurrentEqualizerProfileIfNeeded()
    }

    @Synchronized
    fun read(): AudioSettingsState {
        val deviceProfile = currentOutputProfile()
        val equalizer = readEqualizerProfile(deviceProfile)
        return AudioSettingsState(
            equalizerEnabled = equalizer.enabled,
            equalizerPreset = equalizer.preset,
            bandLevelsDb = equalizer.bandLevelsDb,
            crossfadeSeconds = preferences.getInt(AudioSettingsContract.KEY_CROSSFADE_SECONDS, 0)
                .coerceIn(0, AudioSettingsContract.MAX_CROSSFADE_SECONDS),
            crossfadeCurve = CrossfadeCurve.fromStorage(
                preferences.getString(AudioSettingsContract.KEY_CROSSFADE_CURVE, CrossfadeCurve.EQUAL_POWER.storageValue)
            ),
            crossfadeAlbumBoundariesOnly = preferences.getBoolean(AudioSettingsContract.KEY_CROSSFADE_ALBUM_BOUNDARIES_ONLY, false),
            manualSkipFadeSeconds = preferences.getInt(AudioSettingsContract.KEY_MANUAL_SKIP_FADE_SECONDS, 0)
                .coerceIn(0, AudioSettingsContract.MAX_MANUAL_SKIP_FADE_SECONDS),
            playbackSpeed = sanitizePlaybackParameter(preferences.getFloat(AudioSettingsContract.KEY_PLAYBACK_SPEED, 1f)),
            playbackPitch = sanitizePlaybackParameter(preferences.getFloat(AudioSettingsContract.KEY_PLAYBACK_PITCH, 1f)),
            normalizeVolume = preferences.getBoolean(AudioSettingsContract.KEY_NORMALIZE_VOLUME, false),
            equalizerDeviceName = deviceProfile.displayName
        )
    }

    @Synchronized
    fun setEqualizerEnabled(enabled: Boolean): AudioSettingsState = updateEqualizerProfile {
        it.copy(enabled = enabled)
    }

    @Synchronized
    fun selectPreset(preset: String): AudioSettingsState {
        val safePreset = preset.takeIf { it in AudioSettingsContract.PRESET_LEVELS } ?: AudioSettingsContract.PRESET_FLAT
        val levels = AudioSettingsContract.PRESET_LEVELS.getValue(safePreset)
        return updateEqualizerProfile { it.copy(enabled = true, preset = safePreset, bandLevelsDb = levels) }
    }

    @Synchronized
    fun setBandLevel(index: Int, levelDb: Int): AudioSettingsState {
        if (index !in 0 until AudioSettingsContract.BAND_COUNT) return read()
        val safeLevel = levelDb.coerceIn(AudioSettingsContract.MIN_BAND_DB, AudioSettingsContract.MAX_BAND_DB)
        return updateEqualizerProfile { current ->
            current.copy(
                enabled = true,
                preset = AudioSettingsContract.PRESET_CUSTOM,
                bandLevelsDb = current.bandLevelsDb.toMutableList().also { it[index] = safeLevel }
            )
        }
    }

    @Synchronized
    fun setCrossfadeSeconds(seconds: Int): AudioSettingsState = save {
        putInt(
            AudioSettingsContract.KEY_CROSSFADE_SECONDS,
            seconds.coerceIn(0, AudioSettingsContract.MAX_CROSSFADE_SECONDS)
        )
    }

    @Synchronized
    fun setCrossfadeCurve(curve: CrossfadeCurve): AudioSettingsState = save {
        putString(AudioSettingsContract.KEY_CROSSFADE_CURVE, curve.storageValue)
    }

    @Synchronized
    fun setCrossfadeAlbumBoundariesOnly(enabled: Boolean): AudioSettingsState = save {
        putBoolean(AudioSettingsContract.KEY_CROSSFADE_ALBUM_BOUNDARIES_ONLY, enabled)
    }

    @Synchronized
    fun setManualSkipFadeSeconds(seconds: Int): AudioSettingsState = save {
        putInt(
            AudioSettingsContract.KEY_MANUAL_SKIP_FADE_SECONDS,
            seconds.coerceIn(0, AudioSettingsContract.MAX_MANUAL_SKIP_FADE_SECONDS)
        )
    }

    @Synchronized
    fun setPlaybackSpeed(speed: Float): AudioSettingsState = save {
        putFloat(AudioSettingsContract.KEY_PLAYBACK_SPEED, sanitizePlaybackParameter(speed))
    }

    @Synchronized
    fun setPlaybackPitch(pitch: Float): AudioSettingsState = save {
        putFloat(AudioSettingsContract.KEY_PLAYBACK_PITCH, sanitizePlaybackParameter(pitch))
    }

    @Synchronized
    fun setNormalizeVolume(enabled: Boolean): AudioSettingsState = save {
        putBoolean(AudioSettingsContract.KEY_NORMALIZE_VOLUME, enabled)
    }

    /** Persist a monotonic deadline plus a wall-clock fallback for reboot-safe restoration. */
    @Synchronized
    fun setSleepTimer(endElapsedMs: Long, fadeOutSeconds: Int) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val nowWallClockMs = System.currentTimeMillis()
        if (endElapsedMs <= nowElapsedMs) {
            clearSleepTimer()
            return
        }
        val remainingMs = (endElapsedMs - nowElapsedMs).coerceAtMost(MAX_SLEEP_TIMER_DURATION_MS)
        if (nowElapsedMs > Long.MAX_VALUE - remainingMs || nowWallClockMs <= 0L ||
            nowWallClockMs > Long.MAX_VALUE - remainingMs
        ) {
            clearSleepTimer()
            return
        }
        val safeEndElapsedMs = nowElapsedMs + remainingMs
        val safeEndWallClockMs = nowWallClockMs + remainingMs
        val safeFadeSeconds = fadeOutSeconds.coerceIn(0, MAX_SLEEP_TIMER_FADE_SECONDS)
        val fadeStartElapsedMs = (safeEndElapsedMs - safeFadeSeconds * 1_000L).coerceAtLeast(1L)
        val editor = preferences.edit()
            .putLong(AudioSettingsContract.KEY_SLEEP_TIMER_END_ELAPSED_MS, safeEndElapsedMs)
            .putLong(AudioSettingsContract.KEY_SLEEP_TIMER_END_WALL_CLOCK_MS, safeEndWallClockMs)
            .putLong(AudioSettingsContract.KEY_SLEEP_TIMER_DURATION_MS, remainingMs)
            .putInt(AudioSettingsContract.KEY_SLEEP_TIMER_BOOT_COUNT, currentBootCount())
            .putLong(AudioSettingsContract.KEY_SLEEP_TIMER_BOOT_EPOCH_ESTIMATE_MS, nowWallClockMs - nowElapsedMs)
            .putInt(AudioSettingsContract.KEY_SLEEP_TIMER_FADE_OUT_SECONDS, safeFadeSeconds)
        if (safeFadeSeconds > 0) {
            editor.putLong(AudioSettingsContract.KEY_SLEEP_FADE_START_ELAPSED_MS, fadeStartElapsedMs)
                .putLong(AudioSettingsContract.KEY_SLEEP_FADE_END_ELAPSED_MS, safeEndElapsedMs)
        } else {
            editor.remove(AudioSettingsContract.KEY_SLEEP_FADE_START_ELAPSED_MS)
                .remove(AudioSettingsContract.KEY_SLEEP_FADE_END_ELAPSED_MS)
        }
        editor.apply()
    }

    @Synchronized
    fun sleepTimerSchedule(): SleepTimerSchedule? {
        val endElapsedMs = preferences.getLong(AudioSettingsContract.KEY_SLEEP_TIMER_END_ELAPSED_MS, 0L)
        if (endElapsedMs <= 0L) return null

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val nowWallClockMs = System.currentTimeMillis()
        val currentBootCount = currentBootCount()
        val currentBootEpochEstimateMs = nowWallClockMs - nowElapsedMs
        val resolvedEndElapsedMs = resolveSleepTimerDeadline(
            storedEndElapsedMs = endElapsedMs,
            storedEndWallClockMs = preferences.getLong(AudioSettingsContract.KEY_SLEEP_TIMER_END_WALL_CLOCK_MS, 0L),
            storedDurationMs = preferences.getLong(AudioSettingsContract.KEY_SLEEP_TIMER_DURATION_MS, 0L),
            storedBootCount = preferences.getInt(AudioSettingsContract.KEY_SLEEP_TIMER_BOOT_COUNT, -1),
            currentBootCount = currentBootCount,
            storedBootEpochEstimateMs = preferences.getLong(AudioSettingsContract.KEY_SLEEP_TIMER_BOOT_EPOCH_ESTIMATE_MS, 0L),
            currentBootEpochEstimateMs = currentBootEpochEstimateMs,
            nowElapsedMs = nowElapsedMs,
            nowWallClockMs = nowWallClockMs,
            maxDurationMs = MAX_SLEEP_TIMER_DURATION_MS
        )
        if (resolvedEndElapsedMs == null) {
            // Legacy elapsed-only deadlines cannot be safely interpreted after a reboot.
            clearSleepTimer()
            return null
        }

        if (resolvedEndElapsedMs != endElapsedMs ||
            preferences.getInt(AudioSettingsContract.KEY_SLEEP_TIMER_BOOT_COUNT, -1) != currentBootCount
        ) {
            val fadeSeconds = preferences.getInt(AudioSettingsContract.KEY_SLEEP_TIMER_FADE_OUT_SECONDS, 0)
                .coerceIn(0, MAX_SLEEP_TIMER_FADE_SECONDS)
            val fadeStartElapsedMs = (resolvedEndElapsedMs - fadeSeconds * 1_000L).coerceAtLeast(1L)
            val editor = preferences.edit()
                .putLong(AudioSettingsContract.KEY_SLEEP_TIMER_END_ELAPSED_MS, resolvedEndElapsedMs)
                .putInt(AudioSettingsContract.KEY_SLEEP_TIMER_BOOT_COUNT, currentBootCount)
                .putLong(AudioSettingsContract.KEY_SLEEP_TIMER_BOOT_EPOCH_ESTIMATE_MS, currentBootEpochEstimateMs)
            if (fadeSeconds > 0) {
                editor.putLong(AudioSettingsContract.KEY_SLEEP_FADE_START_ELAPSED_MS, fadeStartElapsedMs)
                    .putLong(AudioSettingsContract.KEY_SLEEP_FADE_END_ELAPSED_MS, resolvedEndElapsedMs)
            } else {
                editor.remove(AudioSettingsContract.KEY_SLEEP_FADE_START_ELAPSED_MS)
                    .remove(AudioSettingsContract.KEY_SLEEP_FADE_END_ELAPSED_MS)
            }
            editor.apply()
        }

        return SleepTimerSchedule(
            endElapsedMs = resolvedEndElapsedMs,
            fadeOutSeconds = preferences.getInt(AudioSettingsContract.KEY_SLEEP_TIMER_FADE_OUT_SECONDS, 0)
                .coerceIn(0, MAX_SLEEP_TIMER_FADE_SECONDS)
        )
    }

    @Synchronized
    fun clearSleepTimer() {
        preferences.edit()
            .remove(AudioSettingsContract.KEY_SLEEP_TIMER_END_ELAPSED_MS)
            .remove(AudioSettingsContract.KEY_SLEEP_TIMER_END_WALL_CLOCK_MS)
            .remove(AudioSettingsContract.KEY_SLEEP_TIMER_DURATION_MS)
            .remove(AudioSettingsContract.KEY_SLEEP_TIMER_BOOT_COUNT)
            .remove(AudioSettingsContract.KEY_SLEEP_TIMER_BOOT_EPOCH_ESTIMATE_MS)
            .remove(AudioSettingsContract.KEY_SLEEP_TIMER_FADE_OUT_SECONDS)
            .remove(AudioSettingsContract.KEY_SLEEP_FADE_START_ELAPSED_MS)
            .remove(AudioSettingsContract.KEY_SLEEP_FADE_END_ELAPSED_MS)
            .apply()
    }

    @Synchronized
    fun setSleepTimerFadeWindow(startElapsedMs: Long, endElapsedMs: Long) {
        if (startElapsedMs <= 0L || endElapsedMs <= startElapsedMs) {
            clearSleepTimerFadeWindow()
            return
        }
        preferences.edit()
            .putLong(AudioSettingsContract.KEY_SLEEP_FADE_START_ELAPSED_MS, startElapsedMs)
            .putLong(AudioSettingsContract.KEY_SLEEP_FADE_END_ELAPSED_MS, endElapsedMs)
            .apply()
    }

    @Synchronized
    fun sleepTimerFadeWindow(): SleepTimerFadeWindow {
        val start = preferences.getLong(AudioSettingsContract.KEY_SLEEP_FADE_START_ELAPSED_MS, 0L)
        val end = preferences.getLong(AudioSettingsContract.KEY_SLEEP_FADE_END_ELAPSED_MS, 0L)
        if (start > 0L && end > start) return SleepTimerFadeWindow(start, end)
        return SleepTimerFadeWindow(0L, 0L)
    }

    @Synchronized
    fun clearSleepTimerFadeWindow() {
        preferences.edit()
            .remove(AudioSettingsContract.KEY_SLEEP_FADE_START_ELAPSED_MS)
            .remove(AudioSettingsContract.KEY_SLEEP_FADE_END_ELAPSED_MS)
            .apply()
    }

    @Synchronized
    fun exportPortableData(): JSONObject {
        val value = read()
        return JSONObject()
            .put("eq_enabled", value.equalizerEnabled)
            .put("eq_preset", value.equalizerPreset)
            .put("eq_bands", JSONArray(value.bandLevelsDb))
            .put("crossfade_seconds", value.crossfadeSeconds)
            .put("crossfade_curve", value.crossfadeCurve.storageValue)
            .put("crossfade_album_boundaries_only", value.crossfadeAlbumBoundariesOnly)
            .put("manual_skip_fade_seconds", value.manualSkipFadeSeconds)
            .put("eq_device_profiles", readDeviceProfiles())
            .put("playback_speed", value.playbackSpeed.toDouble())
            .put("playback_pitch", value.playbackPitch.toDouble())
            .put("normalize_volume", value.normalizeVolume)
    }

    @Synchronized
    fun importPortableData(json: JSONObject): AudioSettingsState {
        val importedEnabled = json.optBoolean("eq_enabled", false)
        val importedPreset = json.optString("eq_preset", AudioSettingsContract.PRESET_FLAT)
            .takeIf { it in AudioSettingsContract.PRESET_LEVELS || it == AudioSettingsContract.PRESET_CUSTOM }
            ?: AudioSettingsContract.PRESET_FLAT
        val importedBands = json.optJSONArray("eq_bands")
        val importedLevels = (0 until AudioSettingsContract.BAND_COUNT).map { index ->
            (importedBands?.optInt(index, 0) ?: 0)
                .coerceIn(AudioSettingsContract.MIN_BAND_DB, AudioSettingsContract.MAX_BAND_DB)
        }
        val profileValues = JSONObject()
            .put("enabled", importedEnabled)
            .put("preset", importedPreset)
            .put("bands", JSONArray(importedLevels))
        val profiles = runCatching {
            json.optJSONObject("eq_device_profiles")?.let { JSONObject(it.toString()) }
        }.getOrNull() ?: JSONObject()
        val activeProfile = currentOutputProfile()
        if (!profiles.has(activeProfile.key)) profiles.put(activeProfile.key, profileValues)
        return save {
            putBoolean(AudioSettingsContract.KEY_EQUALIZER_ENABLED, importedEnabled)
            putString(AudioSettingsContract.KEY_EQUALIZER_PRESET, importedPreset)
            importedLevels.forEachIndexed { index, value -> putInt(AudioSettingsContract.BAND_KEY_PREFIX + index, value) }
            putString(AudioSettingsContract.KEY_EQ_DEVICE_PROFILES, profiles.toString())
            putInt(AudioSettingsContract.KEY_CROSSFADE_SECONDS, json.optInt("crossfade_seconds", 0).coerceIn(0, AudioSettingsContract.MAX_CROSSFADE_SECONDS))
            putString(AudioSettingsContract.KEY_CROSSFADE_CURVE, CrossfadeCurve.fromStorage(json.optString("crossfade_curve")).storageValue)
            putBoolean(AudioSettingsContract.KEY_CROSSFADE_ALBUM_BOUNDARIES_ONLY, json.optBoolean("crossfade_album_boundaries_only", false))
            putInt(AudioSettingsContract.KEY_MANUAL_SKIP_FADE_SECONDS, json.optInt("manual_skip_fade_seconds", 0).coerceIn(0, AudioSettingsContract.MAX_MANUAL_SKIP_FADE_SECONDS))
            putFloat(AudioSettingsContract.KEY_PLAYBACK_SPEED, sanitizePlaybackParameter(json.optDouble("playback_speed", 1.0).toFloat()))
            putFloat(AudioSettingsContract.KEY_PLAYBACK_PITCH, sanitizePlaybackParameter(json.optDouble("playback_pitch", 1.0).toFloat()))
            putBoolean(AudioSettingsContract.KEY_NORMALIZE_VOLUME, json.optBoolean("normalize_volume", false))
        }
    }

    private fun migrateLegacyBands() {
        if (preferences.getBoolean(AudioSettingsContract.KEY_EQ_BANDS_MIGRATED, false)) return
        val editor = preferences.edit()
        val presetName = preferences.getString(AudioSettingsContract.KEY_EQUALIZER_PRESET, AudioSettingsContract.PRESET_FLAT)
        val selectedPreset = AudioSettingsContract.PRESET_LEVELS[presetName]
        if (selectedPreset != null) {
            selectedPreset.forEachIndexed { index, value ->
                editor.putInt(AudioSettingsContract.BAND_KEY_PREFIX + index, value)
            }
        } else {
            // Preserve a custom legacy five-band curve by interpolating it across the new frequencies.
            val oldToNewIndices = listOf(1, 3, 5, 7, 9)
            val hasLegacyLevels = oldToNewIndices.indices.any { oldIndex ->
                preferences.contains(AudioSettingsContract.LEGACY_BAND_KEY_PREFIX + oldIndex)
            }
            if (hasLegacyLevels) {
                val legacyLevels = oldToNewIndices.indices.map { oldIndex ->
                    preferences.getInt(AudioSettingsContract.LEGACY_BAND_KEY_PREFIX + oldIndex, 0)
                        .coerceIn(AudioSettingsContract.MIN_BAND_DB, AudioSettingsContract.MAX_BAND_DB)
                }
                for (newIndex in 0 until AudioSettingsContract.BAND_COUNT) {
                    val lower = oldToNewIndices.indexOfLast { it <= newIndex }.coerceAtLeast(0)
                    val upper = oldToNewIndices.indexOfFirst { it >= newIndex }
                        .takeIf { it >= 0 } ?: oldToNewIndices.lastIndex
                    val lowPosition = oldToNewIndices[lower]
                    val highPosition = oldToNewIndices[upper]
                    val fraction = if (lower == upper) 0f else
                        (newIndex - lowPosition).toFloat() / (highPosition - lowPosition).toFloat()
                    val level = (legacyLevels[lower] + (legacyLevels[upper] - legacyLevels[lower]) * fraction).roundToInt()
                    editor.putInt(AudioSettingsContract.BAND_KEY_PREFIX + newIndex, level)
                }
            }
        }
        editor.putBoolean(AudioSettingsContract.KEY_EQ_BANDS_MIGRATED, true).apply()
    }

    private fun currentBootCount(): Int = runCatching {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    private companion object {
        const val MAX_SLEEP_TIMER_FADE_SECONDS = 120
        const val MAX_SLEEP_TIMER_DURATION_MS = 24L * 60L * 60L * 1_000L
    }

    private data class EqualizerDeviceProfile(val key: String, val displayName: String)

    private data class EqualizerProfileValues(
        val enabled: Boolean,
        val preset: String,
        val bandLevelsDb: List<Int>
    )

    private fun readEqualizerProfile(profile: EqualizerDeviceProfile): EqualizerProfileValues {
        val profiles = readDeviceProfiles()
        val stored = profiles.optJSONObject(profile.key)
        if (stored == null) {
            return if (profiles.length() == 0) readLegacyEqualizerValues() else {
                EqualizerProfileValues(
                    enabled = false,
                    preset = AudioSettingsContract.PRESET_FLAT,
                    bandLevelsDb = AudioSettingsContract.FLAT_LEVELS
                )
            }
        }
        val fallback = readLegacyEqualizerValues()
        val preset = stored.optString("preset", fallback.preset)
            .takeIf { it in AudioSettingsContract.PRESET_LEVELS || it == AudioSettingsContract.PRESET_CUSTOM }
            ?: AudioSettingsContract.PRESET_FLAT
        val bands = stored.optJSONArray("bands")
        return EqualizerProfileValues(
            enabled = stored.optBoolean("enabled", fallback.enabled),
            preset = preset,
            bandLevelsDb = (0 until AudioSettingsContract.BAND_COUNT).map { index ->
                (bands?.optInt(index, fallback.bandLevelsDb[index]) ?: fallback.bandLevelsDb[index])
                    .coerceIn(AudioSettingsContract.MIN_BAND_DB, AudioSettingsContract.MAX_BAND_DB)
            }
        )
    }

    private fun readLegacyEqualizerValues(): EqualizerProfileValues {
        val preset = preferences.getString(AudioSettingsContract.KEY_EQUALIZER_PRESET, AudioSettingsContract.PRESET_FLAT)
            ?.takeIf { it in AudioSettingsContract.PRESET_LEVELS || it == AudioSettingsContract.PRESET_CUSTOM }
            ?: AudioSettingsContract.PRESET_FLAT
        return EqualizerProfileValues(
            enabled = preferences.getBoolean(AudioSettingsContract.KEY_EQUALIZER_ENABLED, false),
            preset = preset,
            bandLevelsDb = (0 until AudioSettingsContract.BAND_COUNT).map { index ->
                preferences.getInt(AudioSettingsContract.BAND_KEY_PREFIX + index, 0)
                    .coerceIn(AudioSettingsContract.MIN_BAND_DB, AudioSettingsContract.MAX_BAND_DB)
            }
        )
    }

    private fun updateEqualizerProfile(update: (EqualizerProfileValues) -> EqualizerProfileValues): AudioSettingsState {
        val deviceProfile = currentOutputProfile()
        val current = readEqualizerProfile(deviceProfile)
        val requested = update(current)
        val updated = requested.copy(
            bandLevelsDb = current.bandLevelsDb.indices.map { index ->
                requested.bandLevelsDb.getOrElse(index) { 0 }
                    .coerceIn(AudioSettingsContract.MIN_BAND_DB, AudioSettingsContract.MAX_BAND_DB)
            }
        )
        val profileJson = JSONObject()
            .put("enabled", updated.enabled)
            .put("preset", updated.preset)
            .put("bands", JSONArray(updated.bandLevelsDb))
        val profiles = readDeviceProfiles().put(deviceProfile.key, profileJson)
        val editor = preferences.edit()
            .putBoolean(AudioSettingsContract.KEY_EQUALIZER_ENABLED, updated.enabled)
            .putString(AudioSettingsContract.KEY_EQUALIZER_PRESET, updated.preset)
            .putString(AudioSettingsContract.KEY_EQ_DEVICE_PROFILES, profiles.toString())
        updated.bandLevelsDb.forEachIndexed { index, value ->
            editor.putInt(AudioSettingsContract.BAND_KEY_PREFIX + index, value)
        }
        editor.apply()
        return read()
    }

    private fun seedCurrentEqualizerProfileIfNeeded() {
        if (readDeviceProfiles().length() > 0) return
        val profile = currentOutputProfile()
        val values = readLegacyEqualizerValues()
        val json = JSONObject()
            .put("enabled", values.enabled)
            .put("preset", values.preset)
            .put("bands", JSONArray(values.bandLevelsDb))
        preferences.edit()
            .putString(AudioSettingsContract.KEY_EQ_DEVICE_PROFILES, JSONObject().put(profile.key, json).toString())
            .apply()
    }

    private fun readDeviceProfiles(): JSONObject = runCatching {
        JSONObject(preferences.getString(AudioSettingsContract.KEY_EQ_DEVICE_PROFILES, "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    private fun currentOutputProfile(): EqualizerDeviceProfile {
        val manager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return EqualizerDeviceProfile("builtin_speaker", "Built-in speaker")
        val device = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { outputDevicePriority(it.type) > 0 }
                .maxByOrNull { outputDevicePriority(it.type) }
        }.getOrNull() ?: return EqualizerDeviceProfile("builtin_speaker", "Built-in speaker")
        val category = when {
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            isBluetoothOutputType(device.type) -> "Bluetooth"
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio"
            device.type == AudioDeviceInfo.TYPE_HDMI || device.type == AudioDeviceInfo.TYPE_HDMI_ARC ||
                device.type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
                device.type == AudioDeviceInfo.TYPE_LINE_DIGITAL ||
                device.type == AudioDeviceInfo.TYPE_AUX_LINE ||
                device.type == AudioDeviceInfo.TYPE_DOCK -> "External audio"
            else -> "Audio output"
        }
        val productName = device.productName.toString().trim().ifBlank { category }
        val displayName = if (category == "Built-in speaker") category else "$category · $productName"
        val deviceAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address else ""
        val identity = listOf(deviceAddress.trim(), productName).filter(String::isNotBlank).joinToString("_")
        val safeIdentity = identity.lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
            .trim('_')
            .take(72)
            .ifBlank { "output" }
        return EqualizerDeviceProfile("out_${device.type}_$safeIdentity", displayName)
    }

    private fun isBluetoothOutputType(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_HEARING_AID) {
            return true
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
    }

    private fun outputDevicePriority(type: Int): Int = when {
        isBluetoothOutputType(type) -> 6
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 5
        type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY -> 4
        type == AudioDeviceInfo.TYPE_HDMI || type == AudioDeviceInfo.TYPE_HDMI_ARC ||
            type == AudioDeviceInfo.TYPE_LINE_ANALOG || type == AudioDeviceInfo.TYPE_LINE_DIGITAL ||
            type == AudioDeviceInfo.TYPE_AUX_LINE || type == AudioDeviceInfo.TYPE_DOCK -> 3
        type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1
        else -> 0
    }

    private fun save(block: android.content.SharedPreferences.Editor.() -> Unit): AudioSettingsState {
        val editor = preferences.edit()
        block(editor)
        editor.apply()
        return read()
    }
}

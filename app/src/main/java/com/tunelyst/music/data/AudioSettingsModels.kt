package com.tunelyst.music.data

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

package com.tunelyst.music.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Device-local appearance, accessibility, playback-counting and Home-layout preferences. */
enum class AppThemeMode(val storageValue: String, val title: String) {
    PITCH_BLACK("pitch_black", "Pitch Black"),
    DEVICE_COLORS("device_colors", "Adaptive device colors");

    companion object {
        fun fromStorage(value: String?): AppThemeMode = when (value) {
            "white" -> PITCH_BLACK // Legacy saved White themes now fall back to Pitch Black.
            "device_colors" -> DEVICE_COLORS
            else -> PITCH_BLACK
        }
    }
}

enum class RepeatAvoidanceLevel(
    val storageValue: String,
    val title: String,
    val subtitle: String
) {
    BALANCED("balanced", "Balanced", "Allow familiar tracks to return sooner."),
    FEWER("fewer", "Fewer repeats", "Strongly space out recently played tracks."),
    VERY_FEW("very_few", "Very few repeats", "Hold back up to 24 recent tracks when alternatives are available.");

    companion object {
        fun fromStorage(value: String?): RepeatAvoidanceLevel? =
            entries.firstOrNull { it.storageValue == value }
    }
}

data class AppSettingsState(
    val themeMode: AppThemeMode = AppThemeMode.PITCH_BLACK,
    val dynamicBlurEnabled: Boolean = true,
    val highContrastEnabled: Boolean = false,
    val reducedMotionEnabled: Boolean = false,
    val largerTouchTargetsEnabled: Boolean = false,
    val listenThresholdSeconds: Int = 30,
    val repeatAvoidanceLevel: RepeatAvoidanceLevel = RepeatAvoidanceLevel.VERY_FEW,
    val hiddenHomeSections: Set<String> = emptySet(),
    val homeSectionOrder: List<String> = AppSettingsStore.DEFAULT_HOME_SECTIONS
) {
    /** Kept as a compatibility view for older backup fields and call sites. */
    val fewerRepeatsEnabled: Boolean get() = repeatAvoidanceLevel != RepeatAvoidanceLevel.BALANCED
}

class AppSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun read(): AppSettingsState {
        val savedOrder = preferences.getString(KEY_HOME_SECTION_ORDER, null)
            ?.split('|')?.filter(String::isNotBlank).orEmpty()
        val validOrder = (savedOrder + DEFAULT_HOME_SECTIONS).distinct().filter { it in DEFAULT_HOME_SECTIONS }
        val savedHiddenSections = preferences.getStringSet(KEY_HIDDEN_HOME_SECTIONS, emptySet()).orEmpty()
        val validHiddenSections = savedHiddenSections.mapNotNull { sectionId ->
            when (sectionId) {
                "fresh_finds", "deep_cuts", "on_repeat" -> "mixes"
                in DEFAULT_HOME_SECTIONS -> sectionId
                else -> null
            }
        }.toSet()
        return AppSettingsState(
            themeMode = AppThemeMode.fromStorage(preferences.getString(KEY_THEME_MODE, AppThemeMode.PITCH_BLACK.storageValue)),
            dynamicBlurEnabled = preferences.getBoolean(KEY_DYNAMIC_BLUR, true),
            highContrastEnabled = preferences.getBoolean(KEY_HIGH_CONTRAST, false),
            reducedMotionEnabled = preferences.getBoolean(KEY_REDUCED_MOTION, false),
            largerTouchTargetsEnabled = preferences.getBoolean(KEY_LARGER_TOUCH_TARGETS, false),
            listenThresholdSeconds = preferences.getInt(KEY_LISTEN_THRESHOLD_SECONDS, DEFAULT_LISTEN_THRESHOLD_SECONDS)
                .coerceIn(MIN_LISTEN_THRESHOLD_SECONDS, MAX_LISTEN_THRESHOLD_SECONDS),
            repeatAvoidanceLevel = RepeatAvoidanceLevel.fromStorage(
                preferences.getString(KEY_REPEAT_AVOIDANCE_LEVEL, null)
            ) ?: if (preferences.getBoolean(KEY_FEWER_REPEATS, true)) {
                RepeatAvoidanceLevel.VERY_FEW
            } else {
                RepeatAvoidanceLevel.BALANCED
            },
            hiddenHomeSections = validHiddenSections,
            homeSectionOrder = validOrder
        )
    }

    @Synchronized fun setThemeMode(mode: AppThemeMode): AppSettingsState = save { putString(KEY_THEME_MODE, mode.storageValue) }
    @Synchronized fun setDynamicBlurEnabled(enabled: Boolean): AppSettingsState = save { putBoolean(KEY_DYNAMIC_BLUR, enabled) }
    @Synchronized fun setHighContrastEnabled(enabled: Boolean): AppSettingsState = save { putBoolean(KEY_HIGH_CONTRAST, enabled) }
    @Synchronized fun setReducedMotionEnabled(enabled: Boolean): AppSettingsState = save { putBoolean(KEY_REDUCED_MOTION, enabled) }
    @Synchronized fun setLargerTouchTargetsEnabled(enabled: Boolean): AppSettingsState = save { putBoolean(KEY_LARGER_TOUCH_TARGETS, enabled) }
    @Synchronized fun setListenThresholdSeconds(seconds: Int): AppSettingsState = save {
        putInt(KEY_LISTEN_THRESHOLD_SECONDS, seconds.coerceIn(MIN_LISTEN_THRESHOLD_SECONDS, MAX_LISTEN_THRESHOLD_SECONDS))
    }
    @Synchronized
    fun setRepeatAvoidanceLevel(level: RepeatAvoidanceLevel): AppSettingsState = save {
        putString(KEY_REPEAT_AVOIDANCE_LEVEL, level.storageValue)
        putBoolean(KEY_FEWER_REPEATS, level != RepeatAvoidanceLevel.BALANCED)
    }

    /** Compatibility setter: the former toggle maps to Balanced or Fewer. */
    @Synchronized
    fun setFewerRepeatsEnabled(enabled: Boolean): AppSettingsState = setRepeatAvoidanceLevel(
        if (enabled) RepeatAvoidanceLevel.FEWER else RepeatAvoidanceLevel.BALANCED
    )

    @Synchronized
    fun setHomeSectionHidden(sectionId: String, hidden: Boolean): AppSettingsState {
        val updated = read().hiddenHomeSections.toMutableSet().apply {
            if (hidden) add(sectionId) else remove(sectionId)
        }
        return save { putStringSet(KEY_HIDDEN_HOME_SECTIONS, updated) }
    }

    @Synchronized
    fun moveHomeSection(sectionId: String, direction: Int): AppSettingsState {
        val current = read().homeSectionOrder.toMutableList()
        val index = current.indexOf(sectionId)
        val destination = index + direction.coerceIn(-1, 1)
        if (index >= 0 && destination in current.indices) {
            val moved = current.removeAt(index)
            current.add(destination, moved)
        }
        return save { putString(KEY_HOME_SECTION_ORDER, current.joinToString("|")) }
    }

    @Synchronized
    fun restoreHomeSections(): AppSettingsState = save {
        putString(KEY_HOME_SECTION_ORDER, DEFAULT_HOME_SECTIONS.joinToString("|"))
        putStringSet(KEY_HIDDEN_HOME_SECTIONS, emptySet())
    }

    @Synchronized
    fun exportPortableData(): JSONObject {
        val state = read()
        return JSONObject()
            .put("theme", state.themeMode.storageValue)
            .put("dynamic_blur", state.dynamicBlurEnabled)
            .put("high_contrast", state.highContrastEnabled)
            .put("reduced_motion", state.reducedMotionEnabled)
            .put("larger_touch_targets", state.largerTouchTargetsEnabled)
            .put("listen_threshold_seconds", state.listenThresholdSeconds)
            .put("repeat_avoidance", state.repeatAvoidanceLevel.storageValue)
            .put("fewer_repeats", state.fewerRepeatsEnabled)
            .put("hidden_home_sections", JSONArray(state.hiddenHomeSections.toList()))
            .put("home_section_order", JSONArray(state.homeSectionOrder))
    }

    @Synchronized
    fun importPortableData(json: JSONObject): AppSettingsState = restore(
        AppSettingsState(
            themeMode = AppThemeMode.fromStorage(json.optString("theme", AppThemeMode.PITCH_BLACK.storageValue)),
            dynamicBlurEnabled = json.optBoolean("dynamic_blur", true),
            highContrastEnabled = json.optBoolean("high_contrast", false),
            reducedMotionEnabled = json.optBoolean("reduced_motion", false),
            largerTouchTargetsEnabled = json.optBoolean("larger_touch_targets", false),
            listenThresholdSeconds = json.optInt("listen_threshold_seconds", DEFAULT_LISTEN_THRESHOLD_SECONDS)
                .coerceIn(MIN_LISTEN_THRESHOLD_SECONDS, MAX_LISTEN_THRESHOLD_SECONDS),
            repeatAvoidanceLevel = RepeatAvoidanceLevel.fromStorage(
                json.optString("repeat_avoidance", "")
            ) ?: if (json.optBoolean("fewer_repeats", true)) {
                RepeatAvoidanceLevel.VERY_FEW
            } else {
                RepeatAvoidanceLevel.BALANCED
            },
            hiddenHomeSections = json.optJSONArray("hidden_home_sections").stringValues().toSet(),
            homeSectionOrder = json.optJSONArray("home_section_order").stringValues()
        )
    )

    /** Used only by the on-device settings backup importer. */
    @Synchronized
    fun restore(state: AppSettingsState): AppSettingsState = save {
        putString(KEY_THEME_MODE, state.themeMode.storageValue)
        putBoolean(KEY_DYNAMIC_BLUR, state.dynamicBlurEnabled)
        putBoolean(KEY_HIGH_CONTRAST, state.highContrastEnabled)
        putBoolean(KEY_REDUCED_MOTION, state.reducedMotionEnabled)
        putBoolean(KEY_LARGER_TOUCH_TARGETS, state.largerTouchTargetsEnabled)
        putInt(KEY_LISTEN_THRESHOLD_SECONDS, state.listenThresholdSeconds.coerceIn(MIN_LISTEN_THRESHOLD_SECONDS, MAX_LISTEN_THRESHOLD_SECONDS))
        putString(KEY_REPEAT_AVOIDANCE_LEVEL, state.repeatAvoidanceLevel.storageValue)
        putBoolean(KEY_FEWER_REPEATS, state.fewerRepeatsEnabled)
        putStringSet(KEY_HIDDEN_HOME_SECTIONS, state.hiddenHomeSections)
        putString(KEY_HOME_SECTION_ORDER, state.homeSectionOrder.joinToString("|"))
    }

    private fun save(block: android.content.SharedPreferences.Editor.() -> Unit): AppSettingsState {
        val editor = preferences.edit()
        block(editor)
        editor.apply()
        return read()
    }

    companion object {
        val DEFAULT_HOME_SECTIONS = listOf("listen_again", "mixes")
        private const val PREFERENCES_NAME = "tunelyst_app_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_BLUR = "dynamic_blur_background"
        private const val KEY_HIGH_CONTRAST = "high_contrast"
        private const val KEY_REDUCED_MOTION = "reduced_motion"
        private const val KEY_LARGER_TOUCH_TARGETS = "larger_touch_targets"
        private const val KEY_LISTEN_THRESHOLD_SECONDS = "listen_threshold_seconds"
        private const val KEY_REPEAT_AVOIDANCE_LEVEL = "repeat_avoidance_level"
        private const val KEY_FEWER_REPEATS = "fewer_repeats"
        const val DEFAULT_LISTEN_THRESHOLD_SECONDS = 30
        const val MIN_LISTEN_THRESHOLD_SECONDS = 5
        const val MAX_LISTEN_THRESHOLD_SECONDS = 120
        private const val KEY_HIDDEN_HOME_SECTIONS = "hidden_home_sections"
        private const val KEY_HOME_SECTION_ORDER = "home_section_order"
    }
}

private fun JSONArray?.stringValues(): List<String> = if (this == null) emptyList() else buildList {
    for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
}

package com.tunelyst.music.playback

import com.tunelyst.music.data.CrossfadeCurve

internal data class AppliedReplayGain(val mediaId: String?, val gainMilliBel: Int, val enabled: Boolean)

internal enum class CrossfadeKind { AUTOMATIC, MANUAL }

internal data class SettingsSnapshot(
    val equalizerEnabled: Boolean,
    val bandLevelsDb: List<Int>,
    val crossfadeSeconds: Int,
    val crossfadeCurve: CrossfadeCurve,
    val crossfadeAlbumBoundariesOnly: Boolean,
    val manualSkipFadeSeconds: Int,
    val playbackSpeed: Float,
    val playbackPitch: Float,
    val normalizeVolume: Boolean
)

package com.tunelyst.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunelyst.music.data.AudioSettingsContract
import com.tunelyst.music.data.AudioSettingsState
import com.tunelyst.music.data.CrossfadeCurve
import kotlin.math.roundToInt

@Composable
fun AudioSettingsPanel(
    settings: AudioSettingsState,
    onEqualizerEnabledChanged: (Boolean) -> Unit,
    onSelectPreset: (String) -> Unit,
    onBandLevelChanged: (Int, Int) -> Unit,
    onCrossfadeChanged: (Int) -> Unit,
    onCrossfadeCurveChanged: (CrossfadeCurve) -> Unit,
    onCrossfadeAlbumBoundariesOnlyChanged: (Boolean) -> Unit,
    onManualSkipFadeChanged: (Int) -> Unit
) {
    var equalizerExpanded by rememberSaveable { mutableStateOf(false) }
    var crossfadeExpanded by rememberSaveable { mutableStateOf(false) }
    val equalizerSummary = buildString {
        append(settings.equalizerDeviceName)
        append(" · ${settings.equalizerPreset}")
        append(if (settings.equalizerEnabled) " · On" else " · Off")
    }
    val crossfadeSummary = buildString {
        append("Auto ")
        append(if (settings.crossfadeSeconds == 0) "off" else "${settings.crossfadeSeconds}s")
        append(" · skip ${if (settings.manualSkipFadeSeconds == 0) "off" else "${settings.manualSkipFadeSeconds}s"}")
        append(" · ${settings.crossfadeCurve.title}")
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AudioSettingsDropdownCard(
            title = "Equalizer",
            summary = equalizerSummary,
            expanded = equalizerExpanded,
            onToggle = { equalizerExpanded = !equalizerExpanded }
        ) {
            EqualizerControls(
                settings = settings,
                onEnabledChanged = onEqualizerEnabledChanged,
                onSelectPreset = onSelectPreset,
                onBandLevelChanged = onBandLevelChanged
            )
        }

        AudioSettingsDropdownCard(
            title = "Crossfade",
            summary = crossfadeSummary,
            expanded = crossfadeExpanded,
            onToggle = { crossfadeExpanded = !crossfadeExpanded }
        ) {
            CrossfadeControls(
                settings = settings,
                onSecondsChanged = onCrossfadeChanged,
                onCurveChanged = onCrossfadeCurveChanged,
                onAlbumBoundariesOnlyChanged = onCrossfadeAlbumBoundariesOnlyChanged,
                onManualSkipFadeChanged = onManualSkipFadeChanged
            )
        }

    }
}

@Composable
fun PlaybackSettingsPanel(
    settings: AudioSettingsState,
    onPlaybackSpeedChanged: (Float) -> Unit,
    onPlaybackPitchChanged: (Float) -> Unit,
    onNormalizeVolumeChanged: (Boolean) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    AudioSettingsDropdownCard(
        title = "Speed, pitch & ReplayGain",
        summary = "${"%.2f".format(settings.playbackSpeed)}× speed · ${"%.2f".format(settings.playbackPitch)}× pitch${if (settings.normalizeVolume) " · normalization on" else ""}",
        expanded = expanded,
        onToggle = { expanded = !expanded }
    ) {
        PlaybackParameterControls(settings, onPlaybackSpeedChanged, onPlaybackPitchChanged, onNormalizeVolumeChanged)
    }
}

@Composable
private fun AudioSettingsDropdownCard(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val motionEnabled = LocalTunelystMotionEnabled.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = TunelystColors.Surface,
        border = BorderStroke(1.dp, TunelystColors.Border.copy(alpha = 0.58f))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        role = Role.Button
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    }
                    .springyClickable(pressedScale = 0.99f, onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(title, color = TunelystColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        summary,
                        color = TunelystColors.Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 3.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ExpressiveToggleIcon(
                    selected = expanded,
                    selectedIcon = Icons.Rounded.KeyboardArrowUp,
                    unselectedIcon = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse $title settings" else "Expand $title settings",
                    tint = TunelystColors.Primary,
                    label = "settings-$title-chevron"
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = if (motionEnabled) expandVertically() + fadeIn() else EnterTransition.None,
                exit = if (motionEnabled) shrinkVertically() + fadeOut() else ExitTransition.None
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 17.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun PlaybackParameterControls(
    settings: AudioSettingsState,
    onSpeedChanged: (Float) -> Unit,
    onPitchChanged: (Float) -> Unit,
    onNormalizeVolumeChanged: (Boolean) -> Unit
) {
    val normalizeInteraction = remember { MutableInteractionSource() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Playback speed · ${"%.2f".format(settings.playbackSpeed)}×", color = TunelystColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Slider(value = settings.playbackSpeed, onValueChange = onSpeedChanged, valueRange = 0.5f..2f)
        Text("Pitch · ${"%.2f".format(settings.playbackPitch)}×", color = TunelystColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Slider(value = settings.playbackPitch, onValueChange = onPitchChanged, valueRange = 0.5f..2f)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("ReplayGain normalization", color = TunelystColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Uses local ReplayGain tags.", color = TunelystColors.Muted, fontSize = 11.sp)
            }
            Switch(
                checked = settings.normalizeVolume,
                onCheckedChange = onNormalizeVolumeChanged,
                modifier = Modifier.springyPress(normalizeInteraction),
                interactionSource = normalizeInteraction
            )
        }
    }
}

@Composable
private fun EqualizerControls(
    settings: AudioSettingsState,
    onEnabledChanged: (Boolean) -> Unit,
    onSelectPreset: (String) -> Unit,
    onBandLevelChanged: (Int, Int) -> Unit
) {
    val bandLabels = listOf("31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz")
    val presetNames = AudioSettingsContract.PRESET_LEVELS.keys.toList()
    val equalizerInteraction = remember { MutableInteractionSource() }
    var presetMenuExpanded by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("10-band equalizer", color = TunelystColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Separate profile · ${settings.equalizerDeviceName}", color = TunelystColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Switch(
            checked = settings.equalizerEnabled,
            onCheckedChange = onEnabledChanged,
            modifier = Modifier.springyPress(equalizerInteraction),
            interactionSource = equalizerInteraction
        )
    }

    Column {
        Surface(
            modifier = Modifier.fillMaxWidth().springyClickable { presetMenuExpanded = true },
            shape = RoundedCornerShape(14.dp),
            color = TunelystColors.BackgroundSoft.copy(alpha = 0.76f),
            border = BorderStroke(1.dp, TunelystColors.Border)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Preset", color = TunelystColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Text(settings.equalizerPreset, color = TunelystColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                }
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Choose equalizer preset", tint = TunelystColors.Primary)
            }
        }
        DropdownMenu(
            expanded = presetMenuExpanded,
            onDismissRequest = { presetMenuExpanded = false }
        ) {
            presetNames.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset) },
                    onClick = {
                        onSelectPreset(preset)
                        presetMenuExpanded = false
                    }
                )
            }
        }
    }

    Spacer(Modifier.height(9.dp))
    bandLabels.forEachIndexed { index, label ->
        val value = settings.bandLevelsDb.getOrElse(index) { 0 }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TunelystColors.Text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(48.dp))
            Slider(
                value = value.toFloat(),
                onValueChange = { onBandLevelChanged(index, it.roundToInt()) },
                valueRange = AudioSettingsContract.MIN_BAND_DB.toFloat()..AudioSettingsContract.MAX_BAND_DB.toFloat(),
                steps = AudioSettingsContract.MAX_BAND_DB - AudioSettingsContract.MIN_BAND_DB - 1,
                enabled = settings.equalizerEnabled,
                colors = SliderDefaults.colors(
                    thumbColor = TunelystColors.Primary,
                    activeTrackColor = TunelystColors.Primary
                ),
                modifier = Modifier.weight(1f).height(38.dp)
            )
            Text(
                text = if (value > 0) "+$value" else "$value",
                color = if (settings.equalizerEnabled) TunelystColors.Text else TunelystColors.Subtle,
                fontSize = 10.sp,
                modifier = Modifier.width(31.dp),
                maxLines = 1
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("−12 dB", color = TunelystColors.Subtle, fontSize = 10.sp)
        Text("0 dB", color = TunelystColors.Subtle, fontSize = 10.sp)
        Text("+12 dB", color = TunelystColors.Subtle, fontSize = 10.sp)
    }
}

@Composable
private fun CrossfadeControls(
    settings: AudioSettingsState,
    onSecondsChanged: (Int) -> Unit,
    onCurveChanged: (CrossfadeCurve) -> Unit,
    onAlbumBoundariesOnlyChanged: (Boolean) -> Unit,
    onManualSkipFadeChanged: (Int) -> Unit
) {
    var curveMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val albumBoundaryInteraction = remember { MutableInteractionSource() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Automatic track crossfade", color = TunelystColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Text(
            if (settings.crossfadeSeconds == 0) "Off" else "${settings.crossfadeSeconds}s",
            color = TunelystColors.Primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
    Slider(
        value = settings.crossfadeSeconds.toFloat(),
        onValueChange = { onSecondsChanged(it.roundToInt()) },
        valueRange = 0f..AudioSettingsContract.MAX_CROSSFADE_SECONDS.toFloat(),
        steps = AudioSettingsContract.MAX_CROSSFADE_SECONDS - 1,
        colors = SliderDefaults.colors(
            thumbColor = TunelystColors.Primary,
            activeTrackColor = TunelystColors.Primary
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Off", color = TunelystColors.Subtle, fontSize = 10.sp)
        Text("15 seconds", color = TunelystColors.Subtle, fontSize = 10.sp)
        Text("30 seconds", color = TunelystColors.Subtle, fontSize = 10.sp)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Manual skip fade", color = TunelystColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Text(
            if (settings.manualSkipFadeSeconds == 0) "Off" else "${settings.manualSkipFadeSeconds}s",
            color = TunelystColors.Primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
    Slider(
        value = settings.manualSkipFadeSeconds.toFloat(),
        onValueChange = { onManualSkipFadeChanged(it.roundToInt()) },
        valueRange = 0f..AudioSettingsContract.MAX_MANUAL_SKIP_FADE_SECONDS.toFloat(),
        steps = AudioSettingsContract.MAX_MANUAL_SKIP_FADE_SECONDS - 1,
        colors = SliderDefaults.colors(thumbColor = TunelystColors.Primary, activeTrackColor = TunelystColors.Primary),
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Off", color = TunelystColors.Subtle, fontSize = 10.sp)
        Text("2 seconds", color = TunelystColors.Subtle, fontSize = 10.sp)
        Text("5 seconds", color = TunelystColors.Subtle, fontSize = 10.sp)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Only between albums", color = TunelystColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(
            checked = settings.crossfadeAlbumBoundariesOnly,
            onCheckedChange = onAlbumBoundariesOnlyChanged,
            modifier = Modifier.springyPress(albumBoundaryInteraction),
            interactionSource = albumBoundaryInteraction
        )
    }

    Spacer(Modifier.height(13.dp))
    Text("Fade curve", color = TunelystColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().springyClickable { curveMenuExpanded = true },
            shape = RoundedCornerShape(14.dp),
            color = TunelystColors.BackgroundSoft.copy(alpha = 0.76f),
            border = BorderStroke(1.dp, TunelystColors.Border)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(settings.crossfadeCurve.title, color = TunelystColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Choose crossfade curve", tint = TunelystColors.Primary)
            }
        }
        DropdownMenu(
            expanded = curveMenuExpanded,
            onDismissRequest = { curveMenuExpanded = false }
        ) {
            CrossfadeCurve.entries.forEach { curve ->
                DropdownMenuItem(
                    text = { Text(curve.title) },
                    onClick = {
                        onCurveChanged(curve)
                        curveMenuExpanded = false
                    }
                )
            }
        }
    }
}

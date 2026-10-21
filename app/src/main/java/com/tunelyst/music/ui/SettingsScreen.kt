package com.tunelyst.music.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunelyst.music.data.AppSettingsState
import com.tunelyst.music.data.AppThemeMode
import com.tunelyst.music.data.AudioSettingsState
import com.tunelyst.music.data.CrossfadeCurve
import com.tunelyst.music.data.RepeatAvoidanceLevel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    appSettings: AppSettingsState,
    audioSettings: AudioSettingsState,
    songCount: Int,
    isLoading: Boolean,
    folders: List<String>,
    excludedFolders: Set<String>,
    hiddenHomeCardIds: Set<String>,
    removedTrackCount: Int,
    inaccessibleTrackCount: Int,
    libraryErrorMessage: String?,
    operationMessage: String?,
    onSetHomeCardHidden: (String, Boolean) -> Unit,
    onFolderExcluded: (String, Boolean) -> Unit,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    onDynamicBlurChanged: (Boolean) -> Unit,
    onListenThresholdChanged: (Int) -> Unit,
    onRepeatAvoidanceChanged: (RepeatAvoidanceLevel) -> Unit,
    onEqualizerEnabledChanged: (Boolean) -> Unit,
    onSelectEqualizerPreset: (String) -> Unit,
    onEqualizerBandLevelChanged: (Int, Int) -> Unit,
    onCrossfadeChanged: (Int) -> Unit,
    onCrossfadeCurveChanged: (CrossfadeCurve) -> Unit,
    onCrossfadeAlbumBoundariesOnlyChanged: (Boolean) -> Unit,
    onManualSkipFadeChanged: (Int) -> Unit,
    onPlaybackSpeedChanged: (Float) -> Unit,
    onPlaybackPitchChanged: (Float) -> Unit,
    onNormalizeVolumeChanged: (Boolean) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onSaveQueue: () -> Unit,
    onClearOperationMessage: () -> Unit,
    onRescanLibrary: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Text(
            "Settings",
            color = TunelystColors.Text,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 20.dp, bottom = 14.dp)
        )

        SettingsSectionLabel("APPEARANCE")
        AppearanceSettingsPanel(
            settings = appSettings,
            onThemeModeChanged = onThemeModeChanged,
            onDynamicBlurChanged = onDynamicBlurChanged
        )
        if (hiddenHomeCardIds.isNotEmpty()) {
            SettingsSectionLabel("HOME CARDS")
            HiddenHomeCardsPanel(hiddenHomeCardIds) { cardId -> onSetHomeCardHidden(cardId, false) }
        }

        SettingsSectionLabel("PLAYBACK")
        PlaybackSettingsPanel(
            settings = audioSettings,
            onPlaybackSpeedChanged = onPlaybackSpeedChanged,
            onPlaybackPitchChanged = onPlaybackPitchChanged,
            onNormalizeVolumeChanged = onNormalizeVolumeChanged
        )

        SettingsSectionLabel("LOCAL LISTENING")
        LocalListeningSettingsPanel(
            settings = appSettings,
            onThresholdChanged = onListenThresholdChanged,
            onRepeatAvoidanceChanged = onRepeatAvoidanceChanged
        )

        SettingsSectionLabel("SOUND & EFFECTS")
        AudioSettingsPanel(
            settings = audioSettings,
            onEqualizerEnabledChanged = onEqualizerEnabledChanged,
            onSelectPreset = onSelectEqualizerPreset,
            onBandLevelChanged = onEqualizerBandLevelChanged,
            onCrossfadeChanged = onCrossfadeChanged,
            onCrossfadeCurveChanged = onCrossfadeCurveChanged,
            onCrossfadeAlbumBoundariesOnlyChanged = onCrossfadeAlbumBoundariesOnlyChanged,
            onManualSkipFadeChanged = onManualSkipFadeChanged
        )

        SettingsSectionLabel("LIBRARY")
        LibrarySettingsPanel(
            songCount = songCount,
            isLoading = isLoading,
            folders = folders,
            excludedFolders = excludedFolders,
            removedTrackCount = removedTrackCount,
            inaccessibleTrackCount = inaccessibleTrackCount,
            libraryErrorMessage = libraryErrorMessage,
            onFolderExcluded = onFolderExcluded,
            onRescan = onRescanLibrary
        )
        SettingsSectionLabel("DATA & QUEUES")
        BackupSettingsPanel(
            onExportBackup = onExportBackup,
            onImportBackup = onImportBackup,
            onSaveQueue = onSaveQueue
        )
        operationMessage?.let { message ->
            Surface(color = TunelystColors.Surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = TunelystColors.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearOperationMessage) { Text("Dismiss", color = TunelystColors.Primary) }
                }
            }
        }


        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        color = TunelystColors.Primary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 9.dp)
    )
}

@Composable
private fun HiddenHomeCardsPanel(cardIds: Set<String>, onRestore: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = TunelystColors.Surface,
        border = BorderStroke(1.dp, TunelystColors.Border.copy(alpha = 0.58f))
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
            cardIds.sorted().forEach { cardId ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        cardId.removePrefix("mix:"),
                        color = TunelystColors.Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onRestore(cardId) }) { Text("Restore", color = TunelystColors.Primary) }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSettingsPanel(
    settings: AppSettingsState,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    onDynamicBlurChanged: (Boolean) -> Unit
) {
    val adaptivePaletteInteraction = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = TunelystColors.Surface,
        border = BorderStroke(1.dp, TunelystColors.Border.copy(alpha = 0.58f))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Theme", color = TunelystColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            val pitchBlackSelected = settings.themeMode == AppThemeMode.PITCH_BLACK
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).springyClickable(pressedScale = 0.97f) {
                    onThemeModeChanged(AppThemeMode.PITCH_BLACK)
                },
                shape = RoundedCornerShape(16.dp),
                color = if (pitchBlackSelected) TunelystColors.Primary.copy(alpha = 0.16f) else TunelystColors.BackgroundSoft.copy(alpha = 0.70f),
                border = BorderStroke(1.dp, if (pitchBlackSelected) TunelystColors.Primary.copy(alpha = 0.86f) else TunelystColors.Border)
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pitch Black", color = if (pitchBlackSelected) TunelystColors.Primary else TunelystColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("OLED", color = TunelystColors.Muted, fontSize = 11.sp)
                    }
                }
            }
            Button(
                onClick = { onThemeModeChanged(AppThemeMode.DEVICE_COLORS) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).springyPress(adaptivePaletteInteraction),
                interactionSource = adaptivePaletteInteraction,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (settings.themeMode == AppThemeMode.DEVICE_COLORS) {
                        TunelystColors.Primary.copy(alpha = 0.20f)
                    } else {
                        TunelystColors.BackgroundSoft.copy(alpha = 0.76f)
                    },
                    contentColor = if (settings.themeMode == AppThemeMode.DEVICE_COLORS) TunelystColors.Primary else TunelystColors.Text
                )
            ) {
                AdaptivePaletteGlyph()
                Text("Adaptive device colors", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(11.dp))
            SettingsToggleRow(
                title = "Dynamic blurred artwork",
                checked = settings.dynamicBlurEnabled,
                onCheckedChange = onDynamicBlurChanged
            )
        }
    }
}

@Composable
private fun LocalListeningSettingsPanel(
    settings: AppSettingsState,
    onThresholdChanged: (Int) -> Unit,
    onRepeatAvoidanceChanged: (RepeatAvoidanceLevel) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = TunelystColors.Surface,
        border = BorderStroke(1.dp, TunelystColors.Border.copy(alpha = 0.58f))
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)) {
            Text("Qualified-play threshold · ${settings.listenThresholdSeconds} sec", color = TunelystColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Counts a play after this much audio; short tracks qualify at 80%.",
                color = TunelystColors.Muted,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
            Slider(
                value = settings.listenThresholdSeconds.toFloat(),
                onValueChange = { onThresholdChanged((it / 5f).roundToInt() * 5) },
                valueRange = 5f..120f,
                steps = 22,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)
            )
            Text(
                "Repeat avoidance",
                color = TunelystColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "Choose how strongly Shuffle and local radio defer tracks you recently played.",
                color = TunelystColors.Muted,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 3.dp)
            )
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                RepeatAvoidanceChoice(RepeatAvoidanceLevel.BALANCED, settings.repeatAvoidanceLevel == RepeatAvoidanceLevel.BALANCED) {
                    onRepeatAvoidanceChanged(RepeatAvoidanceLevel.BALANCED)
                }
                RepeatAvoidanceChoice(RepeatAvoidanceLevel.FEWER, settings.repeatAvoidanceLevel == RepeatAvoidanceLevel.FEWER) {
                    onRepeatAvoidanceChanged(RepeatAvoidanceLevel.FEWER)
                }
                RepeatAvoidanceChoice(RepeatAvoidanceLevel.VERY_FEW, settings.repeatAvoidanceLevel == RepeatAvoidanceLevel.VERY_FEW) {
                    onRepeatAvoidanceChanged(RepeatAvoidanceLevel.VERY_FEW)
                }
            }
            Text(
                "Listening data stays on this device; only a manual Tunelyst backup can export it.",
                color = TunelystColors.Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun RepeatAvoidanceChoice(
    level: RepeatAvoidanceLevel,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(level.title, color = TunelystColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(level.subtitle, color = TunelystColors.Muted, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun AdaptivePaletteGlyph() {
    val dotColors = listOf(TunelystColors.Primary, TunelystColors.Rose, TunelystColors.Lime, TunelystColors.PrimaryDeep)
    Canvas(Modifier.padding(end = 8.dp).width(24.dp).height(24.dp)) {
        val radius = size.minDimension * 0.20f
        val points = listOf(
            Offset(size.width * 0.32f, size.height * 0.32f),
            Offset(size.width * 0.68f, size.height * 0.32f),
            Offset(size.width * 0.32f, size.height * 0.68f),
            Offset(size.width * 0.68f, size.height * 0.68f)
        )
        points.forEachIndexed { index, point -> drawCircle(dotColors[index], radius, point) }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val switchInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .springyToggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = TunelystColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = TunelystColors.Muted, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        val lightPalette = LocalTunelystPalette.current.isLight
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.springyPress(switchInteraction),
            interactionSource = switchInteraction,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TunelystColors.OnPrimary,
                checkedTrackColor = TunelystColors.Primary,
                checkedBorderColor = TunelystColors.PrimaryDeep,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = if (lightPalette) Color(0xFFD7DEE3) else Color(0xFF36464F),
                uncheckedBorderColor = if (lightPalette) Color(0xFF65737C) else Color(0xFF91A1AA)
            )
        )
    }
}

@Composable
private fun LibrarySettingsPanel(
    songCount: Int,
    isLoading: Boolean,
    folders: List<String>,
    excludedFolders: Set<String>,
    removedTrackCount: Int,
    inaccessibleTrackCount: Int,
    libraryErrorMessage: String?,
    onFolderExcluded: (String, Boolean) -> Unit,
    onRescan: () -> Unit
) {
    var foldersExpanded by remember { mutableStateOf(false) }
    var folderQuery by remember { mutableStateOf("") }
    val rescanInteraction = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = TunelystColors.Surface,
        border = BorderStroke(1.dp, TunelystColors.Border.copy(alpha = 0.58f))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("On-device library", color = TunelystColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("$songCount tracks · app-only folder rules", color = TunelystColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    if (removedTrackCount > 0) Text("$removedTrackCount tracks no longer appear in the latest successful scan.", color = TunelystColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                    if (inaccessibleTrackCount > 0) Text("The last scan could not access $inaccessibleTrackCount previously listed tracks; they were kept in your library view.", color = TunelystColors.Rose, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                    libraryErrorMessage?.let { Text(it, color = TunelystColors.Rose, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 5.dp)) }
                }
                Button(enabled = !isLoading, onClick = onRescan, modifier = Modifier.springyPress(rescanInteraction), interactionSource = rescanInteraction,
                    colors = ButtonDefaults.buttonColors(containerColor = TunelystColors.Primary.copy(alpha = 0.19f), contentColor = TunelystColors.Primary), shape = RoundedCornerShape(14.dp)) {
                    Text(if (isLoading) "Scanning…" else "Rescan", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            TextButton(onClick = { foldersExpanded = !foldersExpanded }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(if (foldersExpanded) "Hide folder rules" else "Browse / exclude folders (${folders.size}) · ${excludedFolders.size} excluded", color = TunelystColors.Primary, modifier = Modifier.weight(1f))
            }
            if (foldersExpanded) {
                if (folders.isEmpty()) {
                    Text("Folder paths appear after a successful library scan.", color = TunelystColors.Muted, fontSize = 12.sp)
                } else {
                    OutlinedTextField(
                        value = folderQuery,
                        onValueChange = { folderQuery = it.take(120) },
                        label = { Text("Filter folder paths") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    )
                    val matchingFolders = folders.filter { it.contains(folderQuery.trim(), ignoreCase = true) }
                    matchingFolders.take(100).forEach { folder ->
                        SettingsToggleRow(folder.ifBlank { "Device root" }, "Exclude this folder and its subfolders from Tunelyst", folder in excludedFolders) { onFolderExcluded(folder, it) }
                    }
                    if (matchingFolders.size > 100) Text("Showing 100 of ${matchingFolders.size} matches. Type more to narrow the list.", color = TunelystColors.Muted, fontSize = 11.sp)
                    if (matchingFolders.isEmpty()) Text("No matching folders.", color = TunelystColors.Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun BackupSettingsPanel(
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onSaveQueue: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = TunelystColors.Surface,
        border = BorderStroke(1.dp, TunelystColors.Border.copy(alpha = 0.58f))) {
        Column(Modifier.padding(16.dp)) {
            Text("Personal data only", color = TunelystColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Includes favorites, playlists, listening history, Home choices, tag corrections, saved queues and settings. Audio, device-specific folder paths and picked artwork are not copied; track links are matched on restore where possible.", color = TunelystColors.Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onExportBackup) { Text("Export backup", color = TunelystColors.Primary) }
                TextButton(onClick = onImportBackup) { Text("Restore backup", color = TunelystColors.Primary) }
            }
            TextButton(onClick = onSaveQueue) { Text("Save current queue", color = TunelystColors.Primary) }
        }
    }
}

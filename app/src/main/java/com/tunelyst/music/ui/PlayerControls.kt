package com.tunelyst.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Context
import android.media.AudioManager
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunelyst.music.data.Song
import com.tunelyst.music.data.formatTime
import kotlin.math.roundToInt

@Composable
internal fun SongInformationDialog(song: Song, onDismiss: () -> Unit) {
    val fields = buildList {
        add("Title" to song.title)
        add("Artist" to song.artist)
        add("Album" to song.album)
        if (song.albumArtist.isNotBlank() && !song.albumArtist.equals(song.artist, ignoreCase = true)) {
            add("Album artist" to song.albumArtist)
        }
        song.genre.takeUnless { it.isBlank() || it.equals("Unknown genre", ignoreCase = true) }?.let { add("Genre" to it) }
        song.year.takeIf { it > 0 }?.let { add("Year" to it.toString()) }
        song.discNumber.takeIf { it > 1 }?.let { add("Disc" to it.toString()) }
        song.trackNumber.takeIf { it > 0 }?.let { add("Track" to it.toString()) }
        add("Duration" to formatTime(song.durationMs))
        song.formatLabel.takeUnless { it == "UNKNOWN" }?.let { add("Format" to it) }
        song.fileName.takeIf(String::isNotBlank)?.let { add("File" to it) }
        song.folderPath.takeIf(String::isNotBlank)?.let { add("Folder" to it) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AlbumArtwork(song.artworkUri, song.title, Modifier.size(48.dp), RoundedCornerShape(12.dp))
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Song information", color = TunelystColors.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(song.title, color = TunelystColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                fields.forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(label, color = TunelystColors.Muted, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                        Text(value, color = TunelystColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = TunelystColors.Primary) } },
        containerColor = TunelystColors.Surface,
        shape = RoundedCornerShape(26.dp)
    )
}

@Composable
internal fun SkipTrackControl(
    modifier: Modifier,
    onClick: () -> Unit,
    contentDescription: String,
    forward: Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        shape = CircleShape,
        modifier = modifier
            .springyPress(interactionSource, pressedScale = 0.88f)
            .semantics { this.contentDescription = contentDescription },
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = TunelystColors.Text
        ),
        interactionSource = interactionSource
    ) {
        Icon(
            imageVector = if (forward) Icons.Rounded.SkipNext else Icons.Rounded.SkipPrevious,
            contentDescription = null,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
internal fun PlayerModeControl(
    modifier: Modifier,
    onClick: () -> Unit,
    contentDescription: String,
    stateDescription: String,
    active: Boolean,
    accent: Color,
    icon: ImageVector
) {
    val interactionSource = remember { MutableInteractionSource() }
    val motionEnabled = LocalTunelystMotionEnabled.current
    val iconScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (active) 1.08f else 1f,
        animationSpec = if (motionEnabled) TunelystMotionSpecs.fastSpatial<Float>() else snap(),
        label = "media-mode-icon-scale"
    )
    val spatialSpec = if (motionEnabled) TunelystMotionSpecs.fastSpatial<Float>() else snap()
    val effectsSpec = if (motionEnabled) TunelystMotionSpecs.fastEffects<Float>() else snap()
    FilledTonalIconToggleButton(
        checked = active,
        onCheckedChange = { onClick() },
        shape = CircleShape,
        modifier = modifier
            .springyPress(interactionSource, pressedScale = 0.86f)
            .semantics {
                this.contentDescription = contentDescription
                this.stateDescription = stateDescription
            },
        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
            containerColor = TunelystColors.Text.copy(alpha = 0.08f),
            contentColor = TunelystColors.Text,
            checkedContainerColor = accent.copy(alpha = 0.25f),
            checkedContentColor = accent
        ),
        interactionSource = interactionSource
    ) {
        AnimatedContent(
            targetState = icon,
            transitionSpec = {
                (scaleIn(initialScale = 0.78f, animationSpec = spatialSpec) +
                    fadeIn(animationSpec = effectsSpec)) togetherWith
                    (scaleOut(targetScale = 0.78f, animationSpec = spatialSpec) +
                        fadeOut(animationSpec = effectsSpec))
            },
            label = "media-mode-icon"
        ) { displayedIcon ->
            Icon(
                imageVector = displayedIcon,
                contentDescription = null,
                modifier = Modifier.size(22.dp).graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
            )
        }
    }
}

@Composable
internal fun PlayPauseControl(
    modifier: Modifier,
    isPlaying: Boolean,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val motionEnabled = LocalTunelystMotionEnabled.current
    val spatialSpec = if (motionEnabled) TunelystMotionSpecs.fastSpatial<Float>() else snap()
    val effectsSpec = if (motionEnabled) TunelystMotionSpecs.fastEffects<Float>() else snap()
    FilledIconButton(
        onClick = onClick,
        shape = CircleShape,
        modifier = modifier
            .springyPress(interactionSource, pressedScale = 0.88f)
            .semantics { contentDescription = if (isPlaying) "Pause" else "Play" },
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        interactionSource = interactionSource
    ) {
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                (scaleIn(initialScale = 0.72f, animationSpec = spatialSpec) +
                    fadeIn(animationSpec = effectsSpec)) togetherWith
                    (scaleOut(targetScale = 0.72f, animationSpec = spatialSpec) +
                        fadeOut(animationSpec = effectsSpec))
            },
            label = "play-pause-icon"
        ) { playing ->
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

@Composable
internal fun PlayerDockIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int? = null,
    selected: Boolean = false,
    accent: Color = TunelystColors.Primary
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier.size(52.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    if (selected) accent.copy(alpha = 0.20f) else TunelystColors.Text.copy(alpha = 0.075f)
                )
                .semantics(mergeDescendants = true) {
                    this.contentDescription = if (badgeCount == null) {
                        contentDescription
                    } else {
                        "$contentDescription, count $badgeCount"
                    }
                }
                .springyClickable(
                    pressedScale = 0.88f,
                    role = androidx.compose.ui.semantics.Role.Button,
                    interactionSource = interactionSource,
                    onClick = onClick
                )
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) accent else TunelystColors.Text,
            modifier = Modifier.align(Alignment.Center).size(22.dp)
        )
        badgeCount?.let { count ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = count.toString(),
                    color = artworkContentColor(accent),
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun VolumeGlyph(loud: Boolean, modifier: Modifier = Modifier) {
    val iconColor = TunelystColors.Muted
    Canvas(
        modifier = modifier.semantics {
            contentDescription = if (loud) "Loud end of media volume slider" else "Quiet end of media volume slider"
        }
    ) {
        val scale = size.minDimension / 24f
        val left = (size.width - 24f * scale) / 2f
        val top = (size.height - 24f * scale) / 2f
        fun x(value: Float) = left + value * scale
        fun y(value: Float) = top + value * scale
        val color = iconColor
        val speaker = Path().apply {
            moveTo(x(3f), y(9f))
            lineTo(x(7f), y(9f))
            lineTo(x(12f), y(5f))
            lineTo(x(12f), y(19f))
            lineTo(x(7f), y(15f))
            lineTo(x(3f), y(15f))
            close()
        }
        drawPath(speaker, color)
        val innerWave = Path().apply {
            moveTo(x(15f), y(8f))
            cubicTo(x(18.5f), y(10f), x(18.5f), y(14f), x(15f), y(16f))
        }
        drawPath(innerWave, color, style = Stroke(width = 1.8f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (loud) {
            val outerWave = Path().apply {
                moveTo(x(18f), y(5.5f))
                cubicTo(x(23f), y(9f), x(23f), y(15f), x(18f), y(18.5f))
            }
            drawPath(outerWave, color, style = Stroke(width = 1.8f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
internal fun AppleVolumeSlider(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var volume by remember(audioManager, maxVolume) {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVolume).toFloat())
    }
    Row(modifier = modifier.height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        VolumeGlyph(loud = false, modifier = Modifier.size(20.dp))
        AppleMusicSeekBar(
            progress = (volume / maxVolume).coerceIn(0f, 1f),
            modifier = Modifier.weight(1f),
            enabled = true,
            activeColor = TunelystColors.Text.copy(alpha = 0.82f),
            inactiveColor = TunelystColors.Text.copy(alpha = 0.28f),
            progressDescription = "Media volume",
            onProgressChange = { fraction, _ ->
                val newVolume = (fraction * maxVolume).roundToInt().coerceIn(0, maxVolume)
                volume = newVolume.toFloat()
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
            },
            onProgressChangeFinished = {}
        )
        VolumeGlyph(loud = true, modifier = Modifier.size(20.dp))
    }
}

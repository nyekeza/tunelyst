package com.tunelyst.music.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow

internal val NowPlayingPalettes = listOf(
    listOf(Color(0xFF594066), Color(0xFF2A182D)),
    listOf(Color(0xFF254B57), Color(0xFF142831)),
    listOf(Color(0xFF674842), Color(0xFF2B1D29)),
    listOf(Color(0xFF3B4C39), Color(0xFF172A27)),
    listOf(Color(0xFF50446B), Color(0xFF241D3D))
)

/** Samples local album art and keeps the last palette visible until a new sample is ready. */
@Composable
internal fun rememberArtworkPalette(artworkUri: Uri?, fallbackSeed: String): List<Color> {
    val context = LocalContext.current
    val fallback = remember(fallbackSeed) {
        val colors = NowPlayingPalettes[(fallbackSeed.hashCode().ushr(1)) % NowPlayingPalettes.size]
        listOf(colors.first(), blendArtworkColors(colors.first(), colors.last(), 0.5f), colors.last())
    }
    // Keep the previous artwork colors while a new local image is sampled. Resetting to
    // the fallback on every URI change creates a visible color flash in the expanded player.
    val paletteState = remember { mutableStateOf(fallback) }
    LaunchedEffect(artworkUri, fallbackSeed.takeIf { artworkUri == null }) {
        val extracted = withContext(Dispatchers.IO) {
            artworkUri?.let { extractArtworkPalette(context, it) }
        }
        paletteState.value = extracted ?: fallback
    }
    return paletteState.value
}

private fun extractArtworkPalette(context: Context, artworkUri: Uri): List<Color>? = try {
    val options = BitmapFactory.Options().apply {
        inSampleSize = 4
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }
    val bitmap = context.contentResolver.openInputStream(artworkUri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    } ?: return null
    try {
        val palette = Palette.from(bitmap).maximumColorCount(16).generate()
        val dominant = palette.dominantSwatch?.rgb ?: palette.mutedSwatch?.rgb ?: return null
        val accent = palette.vibrantSwatch?.rgb
            ?: palette.lightVibrantSwatch?.rgb
            ?: palette.darkVibrantSwatch?.rgb
            ?: dominant
        val shadow = palette.darkMutedSwatch?.rgb ?: palette.darkVibrantSwatch?.rgb ?: dominant
        listOf(Color(dominant), Color(accent), Color(shadow))
    } finally {
        bitmap.recycle()
    }
} catch (_: Exception) {
    null
}

internal fun blendArtworkColors(from: Color, to: Color, amount: Float): Color {
    val fraction = amount.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * fraction,
        green = from.green + (to.green - from.green) * fraction,
        blue = from.blue + (to.blue - from.blue) * fraction,
        alpha = from.alpha + (to.alpha - from.alpha) * fraction
    )
}

internal fun artworkContentColor(source: Color): Color {
    fun linearize(channel: Float): Float =
        if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    val luminance = 0.2126f * linearize(source.red) +
        0.7152f * linearize(source.green) +
        0.0722f * linearize(source.blue)
    val whiteContrast = 1.05f / (luminance + 0.05f)
    val darkContrast = (luminance + 0.05f) / 0.05f
    return if (darkContrast >= whiteContrast) Color(0xFF17151B) else Color.White
}

/** Thin shared track thickness for the Now Playing seek and volume bars. */
internal val NowPlayingTrackThickness = 3.dp

/** Rounded, thin scrubber with a visible thumb and a generous touch target. */
@Composable
internal fun AppleMusicSeekBar(
    progress: Float,
    modifier: Modifier = Modifier,
    enabled: Boolean = false,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.34f),
    progressDescription: String? = null,
    onProgressChange: (Float, Boolean) -> Unit = { _, _ -> },
    onProgressChangeFinished: () -> Unit = {}
) {
    val normalizedProgress = progress.coerceIn(0f, 1f)
    val latestOnProgressChange by rememberUpdatedState(onProgressChange)
    val latestOnProgressFinished by rememberUpdatedState(onProgressChangeFinished)
    val horizontalInset = 6.dp

    fun fractionFromX(x: Float, width: Float, inset: Float): Float =
        ((x - inset) / (width - inset * 2f).coerceAtLeast(1f)).coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .semantics {
                contentDescription = progressDescription ?: if (enabled) "Playback position" else "Track progress"
                progressBarRangeInfo = ProgressBarRangeInfo(normalizedProgress, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        latestOnProgressChange(target.coerceIn(0f, 1f), true)
                        latestOnProgressFinished()
                        true
                    }
                }
            }
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures { offset ->
                        latestOnProgressChange(fractionFromX(offset.x, size.width.toFloat(), horizontalInset.toPx()), true)
                        latestOnProgressFinished()
                    }
                }
            }
            .pointerInput(enabled) {
                if (enabled) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            latestOnProgressChange(fractionFromX(offset.x, size.width.toFloat(), horizontalInset.toPx()), true)
                        },
                        onDragEnd = { latestOnProgressFinished() },
                        onDragCancel = { latestOnProgressFinished() }
                    ) { change, _ ->
                        change.consume()
                        latestOnProgressChange(fractionFromX(change.position.x, size.width.toFloat(), horizontalInset.toPx()), true)
                    }
                }
            }
    ) {
        val insetPx = horizontalInset.toPx()
        val startX = insetPx
        val trackWidth = (size.width - insetPx * 2f).coerceAtLeast(1f)
        val centerY = size.height / 2f
        val trackHeight = NowPlayingTrackThickness.toPx()
        val radius = CornerRadius(trackHeight / 2f)
        val playedWidth = trackWidth * normalizedProgress
        drawRoundRect(
            color = inactiveColor,
            topLeft = Offset(startX, centerY - trackHeight / 2f),
            size = Size(trackWidth, trackHeight),
            cornerRadius = radius
        )
        if (playedWidth > 0f) {
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(startX, centerY - trackHeight / 2f),
                size = Size(playedWidth, trackHeight),
                cornerRadius = radius
            )
        }
        val thumbCenter = Offset(startX + playedWidth, centerY)
        val thumbRadius = 6.dp.toPx()
        drawCircle(
            color = Color.Black.copy(alpha = 0.24f),
            radius = thumbRadius + 1.25.dp.toPx(),
            center = thumbCenter
        )
        drawCircle(color = activeColor, radius = thumbRadius, center = thumbCenter)
    }
}

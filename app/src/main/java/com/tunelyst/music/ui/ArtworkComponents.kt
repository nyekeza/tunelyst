package com.tunelyst.music.ui

import android.graphics.Bitmap
import android.net.Uri

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision

private val CoverPalettes = listOf(
    listOf(Color(0xFF6B4FD3), Color(0xFFE780B9)),
    listOf(Color(0xFF247C85), Color(0xFFB5D67D)),
    listOf(Color(0xFFDB795B), Color(0xFFFFC56D)),
    listOf(Color(0xFF57467E), Color(0xFF80A9CD)),
    listOf(Color(0xFF7D3E68), Color(0xFFD9838A))
)

@Composable
fun AlbumArtwork(
    artworkUri: Uri?,
    title: String,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    crossfade: Boolean = true
) {
    val palette = remember(title) { CoverPalettes[(title.hashCode().ushr(1)) % CoverPalettes.size] }
    val motionEnabled = LocalTunelystMotionEnabled.current
    val lowMemoryDevice = LocalTunelystLowMemoryDevice.current
    val context = LocalContext.current
    val artworkRequest = remember(artworkUri, context, crossfade, motionEnabled, lowMemoryDevice) {
        artworkUri?.let {
            ImageRequest.Builder(context)
                .data(it)
                .precision(Precision.INEXACT)
                .allowHardware(!lowMemoryDevice)
                .bitmapConfig(if (lowMemoryDevice) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
                .crossfade(crossfade && motionEnabled && !lowMemoryDevice)
                .build()
        }
    }
    var artworkFailed by remember(artworkUri) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(palette))
    ) {
        if (artworkRequest != null && !artworkFailed) {
            AsyncImage(
                model = artworkRequest,
                contentDescription = "$title album artwork",
                contentScale = ContentScale.Crop,
                filterQuality = if (lowMemoryDevice) FilterQuality.Low else FilterQuality.Medium,
                onError = { artworkFailed = true },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            GeometricArtworkPlaceholder(palette = palette, seed = title.hashCode())
        }
    }
}

@Composable
private fun GeometricArtworkPlaceholder(palette: List<Color>, seed: Int) {
    Canvas(Modifier.fillMaxSize()) {
        val side = minOf(size.width, size.height)
        val first = palette.first()
        val second = palette.last()
        drawRect(Brush.linearGradient(listOf(first, second), start = Offset.Zero, end = Offset(size.width, size.height)))
        drawCircle(
            color = Color.White.copy(alpha = 0.13f),
            radius = side * 0.43f,
            center = Offset(size.width * 0.84f, size.height * 0.20f)
        )
        drawCircle(
            color = first.copy(alpha = 0.42f),
            radius = side * 0.40f,
            center = Offset(size.width * 0.10f, size.height * 0.93f)
        )
        val diagonal = Path().apply {
            moveTo(size.width * 0.50f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height * 0.52f)
            lineTo(size.width * 0.26f, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(diagonal, color = Color.Black.copy(alpha = 0.19f))
        val tile = side * 0.26f
        val tileColor = Color.White.copy(alpha = if ((seed and 1) == 0) 0.21f else 0.15f)
        drawRoundRect(
            color = tileColor,
            topLeft = Offset(size.width * 0.12f, size.height * 0.18f),
            size = Size(tile, tile),
            cornerRadius = CornerRadius(tile * 0.12f)
        )
        if ((seed and 1) == 0) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.10f),
                topLeft = Offset(size.width * 0.49f, size.height * 0.59f),
                size = Size(tile * 0.64f, tile * 0.64f),
                cornerRadius = CornerRadius(tile * 0.16f)
            )
        } else {
            val shard = Path().apply {
                moveTo(size.width * 0.58f, size.height * 0.40f)
                lineTo(size.width * 0.92f, size.height * 0.62f)
                lineTo(size.width * 0.58f, size.height * 0.82f)
                close()
            }
            drawPath(shard, color = Color.White.copy(alpha = 0.12f))
        }
    }
}

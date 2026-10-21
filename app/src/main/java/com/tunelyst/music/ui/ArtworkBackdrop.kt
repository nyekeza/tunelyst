package com.tunelyst.music.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** Shared full-screen artwork wash shown behind every app destination. */
@Composable
fun GlobalArtworkBackdrop(
    artworkUri: Uri?,
    dynamicBlurEnabled: Boolean,
    modifier: Modifier = Modifier,
    blendArtworkAtBottom: Boolean = false
) {
    val palette = LocalTunelystPalette.current
    val motionEnabled = LocalTunelystMotionEnabled.current
    val lowMemoryDevice = LocalTunelystLowMemoryDevice.current
    val context = LocalContext.current
    val visibleArtwork = artworkUri.takeIf { dynamicBlurEnabled }
    val topShade = if (palette.isLight) 0.52f else 0.26f
    val midShade = if (palette.isLight) 0.70f else 0.53f
    val bottomShade = when {
        !blendArtworkAtBottom && palette.isLight -> 0.91f
        !blendArtworkAtBottom -> 0.88f
        palette.isLight -> 0.82f
        else -> 0.66f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        AnimatedContent(
            targetState = visibleArtwork,
            transitionSpec = {
                val enter = if (motionEnabled && !lowMemoryDevice) fadeIn(tween(640)) else EnterTransition.None
                val exit = if (motionEnabled && !lowMemoryDevice) fadeOut(tween(440)) else ExitTransition.None
                enter togetherWith exit
            },
            label = "global-artwork-backdrop"
        ) { imageUri ->
            val imageRequest = remember(imageUri, context, motionEnabled, lowMemoryDevice) {
                imageUri?.let {
                    ImageRequest.Builder(context)
                        .data(it)
                        .allowHardware(!lowMemoryDevice)
                        .bitmapConfig(if (lowMemoryDevice) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
                        .crossfade(motionEnabled && !lowMemoryDevice)
                        .build()
                }
            }
            if (imageUri != null && imageRequest != null) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            alpha = if (palette.isLight) 0.48f else 0.40f,
                            scaleX = if (lowMemoryDevice) 1.08f else 1.22f,
                            scaleY = if (lowMemoryDevice) 1.08f else 1.22f
                        )
                        .then(if (lowMemoryDevice) Modifier else Modifier.blur(64.dp))
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            palette.background.copy(alpha = topShade),
                            palette.background.copy(alpha = midShade),
                            palette.background.copy(alpha = bottomShade)
                        )
                    )
                )
        )
    }
}

/** Soft edge-to-edge fade under the transparent status bar; safeDrawing still protects controls. */
@Composable
internal fun StatusBarEdgeFade(modifier: Modifier = Modifier) {
    val palette = LocalTunelystPalette.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val fadeHeight = statusBarHeight + 12.dp
    val solidAlpha = if (palette.isLight) 0.56f else 0.40f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(fadeHeight)
            .background(
                Brush.verticalGradient(
                    0f to palette.background.copy(alpha = solidAlpha),
                    0.68f to palette.background.copy(alpha = solidAlpha * 0.56f),
                    1f to Color.Transparent
                )
            )
    )
}

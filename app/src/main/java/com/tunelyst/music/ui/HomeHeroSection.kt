package com.tunelyst.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.tunelyst.music.R
import com.tunelyst.music.data.Song

private const val BRAND_MARK_LOGO_FRACTION = 0.76f
private val BRAND_MARK_GRADIENT = Brush.linearGradient(
    colors = listOf(Color(0xFFFCF61C), Color(0xFFFBA707))
)

@Composable
internal fun TunelystBrandMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(BRAND_MARK_GRADIENT),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.tunelyst_waveform_mark),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(BRAND_MARK_LOGO_FRACTION)
        )
    }
}

@Composable
internal fun HomeQuickAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(13.dp),
        modifier = modifier.height(40.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) TunelystColors.Primary.copy(alpha = 0.25f) else TunelystColors.Primary.copy(alpha = 0.12f),
            contentColor = TunelystColors.Primary
        ),
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp)
    ) {
        ExpressiveToggleIcon(
            selected = selected,
            selectedIcon = icon,
            unselectedIcon = icon,
            contentDescription = null,
            tint = TunelystColors.Primary,
            size = 16.dp,
            label = "home-quick-action-icon"
        )
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HeroLibraryCard(
    songCount: Int,
    featuredSong: Song?,
    nowPlaying: Song?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    isBuffering: Boolean,
    playbackSpeed: Float,
    isLoading: Boolean,
    isLibraryReady: Boolean,
    compactLayout: Boolean,
    veryCompactLayout: Boolean,
    statusBarTopInset: Dp,
    onTogglePlayback: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onRescan: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenNavigationDrawer: () -> Unit
) {
    val artworkSong = nowPlaying ?: featuredSong
    val shape = RoundedCornerShape(0.dp)
    val expressiveMotionEnabled = LocalTunelystMotionEnabled.current
    val progressRingStrokePx = with(LocalDensity.current) { 3.dp.toPx() }
    val liveProgress = rememberHomePlaybackProgress(
        trackId = nowPlaying?.id,
        positionMs = positionMs,
        durationMs = durationMs,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        playbackSpeed = playbackSpeed
    )
    val waveSpeedScale = playbackSpeed.takeIf(Float::isFinite)?.coerceIn(0.5f, 2f) ?: 1f
    val heroHeight = statusBarTopInset + when {
        veryCompactLayout -> 184.dp
        compactLayout -> 216.dp
        else -> 276.dp
    }
    val cardModifier = Modifier
        .fillMaxWidth()
        .height(heroHeight)
        .then(if (nowPlaying != null) Modifier.springyClickable(onClick = onOpenNowPlaying) else Modifier)

    Surface(color = Color(0xFF18212A), shape = shape, modifier = cardModifier) {
        Box(Modifier.fillMaxSize()) {
            if (artworkSong != null) {
                AlbumArtwork(
                    artworkUri = artworkSong.artworkUri,
                    title = artworkSong.title,
                    modifier = Modifier.fillMaxSize(),
                    shape = shape
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(Color(0xFF153D48), Color(0xFF222540), Color(0xFF44324E)))
                    )
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0x82000000), Color(0x10000000), Color(0xF0000000)))
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .padding(start = 20.dp, end = 20.dp, top = statusBarTopInset + 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .springyClickable(role = Role.Button, pressedScale = 0.94f, onClick = onOpenNavigationDrawer)
                        .semantics { contentDescription = "Open Tunelyst navigation drawer" },
                    contentAlignment = Alignment.Center
                ) {
                    TunelystBrandMark(Modifier.fillMaxSize())
                }
            }

            if (nowPlaying != null) {
                Column(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(
                        start = if (compactLayout) 18.dp else 22.dp,
                        end = if (compactLayout) 78.dp else 92.dp,
                        bottom = if (veryCompactLayout) 16.dp else if (compactLayout) 22.dp else 26.dp
                    )
                ) {
                    if (!veryCompactLayout) {
                        Text("NOW PLAYING", color = Color.White.copy(alpha = 0.82f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.35.sp)
                    }
                    AnimatedContent(
                        targetState = nowPlaying.title,
                        transitionSpec = {
                            if (expressiveMotionEnabled) {
                                (fadeIn(tween(120)) + scaleIn(initialScale = 0.97f, animationSpec = spring(dampingRatio = 0.78f, stiffness = 500f))) togetherWith fadeOut(tween(80))
                            } else EnterTransition.None togetherWith ExitTransition.None
                        },
                        label = "home-now-playing-title"
                    ) { title ->
                        Text(
                            title,
                            color = Color.White,
                            fontSize = if (compactLayout) 21.sp else 24.sp,
                            lineHeight = if (compactLayout) 24.sp else 28.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.fillMaxWidth().padding(top = if (veryCompactLayout) 0.dp else 4.dp).then(
                                if (expressiveMotionEnabled) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
                            )
                        )
                    }
                    AnimatedContent(
                        targetState = nowPlaying.artist,
                        transitionSpec = {
                            if (expressiveMotionEnabled) fadeIn(tween(110)) togetherWith fadeOut(tween(80))
                            else EnterTransition.None togetherWith ExitTransition.None
                        },
                        label = "home-now-playing-artist"
                    ) { artist ->
                        Text(
                            artist,
                            color = Color.White.copy(alpha = 0.82f),
                            fontSize = if (compactLayout) 12.sp else 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(end = if (compactLayout) 16.dp else 20.dp, bottom = if (veryCompactLayout) 14.dp else if (compactLayout) 18.dp else 22.dp)
                        .size(if (veryCompactLayout) 48.dp else if (compactLayout) 54.dp else 62.dp)
                        .springyClickable(onClick = onTogglePlayback),
                    contentAlignment = Alignment.Center
                ) {
                    CircularWavyProgressIndicator(
                        progress = { liveProgress },
                        modifier = Modifier.fillMaxSize(),
                        color = TunelystColors.Primary,
                        trackColor = Color.White.copy(alpha = 0.48f),
                        stroke = Stroke(width = progressRingStrokePx, cap = StrokeCap.Round),
                        trackStroke = Stroke(width = progressRingStrokePx, cap = StrokeCap.Round),
                        // Keep the contour wavy while paused; only the wave motion stops with playback.
                        amplitude = { _ -> 0.58f },
                        wavelength = 7.dp,
                        waveSpeed = if (expressiveMotionEnabled && isPlaying && !isBuffering) 7.dp * waveSpeedScale else 0.dp
                    )
                    Box(
                        modifier = Modifier.size(if (veryCompactLayout) 34.dp else if (compactLayout) 38.dp else 42.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(1.dp, Color.Black.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = isPlaying,
                            transitionSpec = {
                                if (expressiveMotionEnabled) {
                                    (scaleIn(initialScale = 0.72f, animationSpec = spring(dampingRatio = 0.46f, stiffness = 560f)) + fadeIn(tween(100))) togetherWith
                                        (scaleOut(targetScale = 0.70f, animationSpec = tween(100)) + fadeOut(tween(80)))
                                } else EnterTransition.None togetherWith ExitTransition.None
                            },
                            label = "home-play-pause"
                        ) { playing ->
                            Icon(
                                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                tint = Color(0xFF17151B),
                                modifier = Modifier.size(if (veryCompactLayout) 18.dp else if (compactLayout) 20.dp else 22.dp)
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(
                        start = if (veryCompactLayout) 16.dp else if (compactLayout) 18.dp else 22.dp,
                        end = if (veryCompactLayout) 16.dp else if (compactLayout) 18.dp else 22.dp,
                        bottom = if (veryCompactLayout) 14.dp else if (compactLayout) 20.dp else 24.dp
                    ),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(Modifier.weight(1f)) {
                        if (!veryCompactLayout) {
                            Text("YOUR MUSIC", color = Color.White.copy(alpha = 0.78f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.45.sp)
                        }
                        Text(
                            when {
                                veryCompactLayout && songCount > 0 -> "Your library"
                                veryCompactLayout -> "Find music"
                                songCount > 0 -> "Your collection,\nready to play."
                                else -> "Music you own,\nall in one place."
                            },
                            color = Color.White,
                            fontSize = if (compactLayout) 21.sp else 24.sp,
                            lineHeight = if (compactLayout) 24.sp else 28.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = if (veryCompactLayout) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = if (veryCompactLayout) 0.dp else 5.dp)
                        )
                        Text(
                            if (songCount > 0) "$songCount tracks on this device" else if (!isLibraryReady) "Loading your library…" else if (isLoading) "Scanning this device…" else "Find music stored on this phone.",
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    if (songCount > 0) {
                        IconButton(
                            onClick = onOpenLibrary,
                            modifier = Modifier.size(if (veryCompactLayout) 44.dp else 48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.17f))
                        ) {
                            Icon(Icons.Rounded.ArrowForward, contentDescription = "Open library", tint = Color.White)
                        }
                    } else {
                        AccentButton(text = if (isLoading) "Scanning…" else "Scan music", onClick = onRescan)
                    }
                }
            }
        }
    }
}

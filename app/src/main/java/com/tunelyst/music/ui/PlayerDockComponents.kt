package com.tunelyst.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.tunelyst.music.data.Song

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onOpen: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    blendArtworkBackdrop: Boolean = false
) {
    val palette = LocalTunelystPalette.current
    val compactLayout = isTunelystCompactViewport()
    val veryCompactLayout = isTunelystVeryCompactViewport()
    val targetProgress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val expressiveMotionEnabled = LocalTunelystMotionEnabled.current
    val progress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = if (expressiveMotionEnabled) tween(180) else snap(),
        label = "mini-player-progress"
    )
    val playInteraction = remember(song.id) { MutableInteractionSource() }
    val nextInteraction = remember(song.id) { MutableInteractionSource() }
    Surface(
        color = if (blendArtworkBackdrop) {
            TunelystColors.SurfaceRaised.copy(alpha = if (palette.isLight) 0.84f else 0.70f)
        } else {
            TunelystColors.SurfaceRaised
        },
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(
            start = if (compactLayout) 8.dp else 12.dp,
            top = if (compactLayout) 4.dp else 6.dp,
            end = if (compactLayout) 8.dp else 12.dp,
            bottom = 0.dp
        )
    ) {
        Box {
            Row(
                modifier = Modifier.fillMaxWidth().springyClickable(onClick = onOpen).padding(
                    start = if (compactLayout) 7.dp else 9.dp,
                    end = 4.dp,
                    top = if (compactLayout) 5.dp else 8.dp,
                    bottom = if (compactLayout) 5.dp else 8.dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedContent(
                    targetState = song,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        if (expressiveMotionEnabled) {
                            (scaleIn(initialScale = 0.96f, animationSpec = spring(dampingRatio = 0.76f, stiffness = 480f)) + fadeIn(tween(130))) togetherWith
                                (scaleOut(targetScale = 0.97f, animationSpec = tween(100)) + fadeOut(tween(90)))
                        } else EnterTransition.None togetherWith ExitTransition.None
                    },
                    label = "mini-player-track-change"
                ) { displayedSong ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AlbumArtwork(
                            displayedSong.artworkUri,
                            displayedSong.title,
                            Modifier.size(if (veryCompactLayout) 34.dp else if (compactLayout) 38.dp else 43.dp),
                            RoundedCornerShape(11.dp),
                            crossfade = expressiveMotionEnabled
                        )
                        Column(modifier = Modifier.weight(1f).padding(horizontal = if (compactLayout) 8.dp else 10.dp)) {
                            Text(
                                displayedSong.title,
                                color = TunelystColors.Text,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip,
                                modifier = if (expressiveMotionEnabled) Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE) else Modifier.fillMaxWidth()
                            )
                            Text(displayedSong.artist, color = TunelystColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                IconButton(
                    onClick = onTogglePlayback,
                    modifier = Modifier.size(42.dp).springyPress(playInteraction),
                    interactionSource = playInteraction
                ) {
                    AnimatedContent(
                        targetState = isPlaying,
                        transitionSpec = {
                            if (expressiveMotionEnabled) {
                                (scaleIn(initialScale = 0.74f, animationSpec = spring(dampingRatio = 0.46f, stiffness = 560f)) + fadeIn(tween(100))) togetherWith
                                    (scaleOut(targetScale = 0.70f, animationSpec = tween(100)) + fadeOut(tween(80)))
                            } else {
                                EnterTransition.None togetherWith ExitTransition.None
                            }
                        },
                        label = "mini-player-play-pause"
                    ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = TunelystColors.Text,
                            modifier = Modifier.size(25.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.size(40.dp).springyPress(nextInteraction),
                    interactionSource = nextInteraction
                ) {
                    ExpressiveStateIcon(
                        icon = Icons.Rounded.SkipNext,
                        contentDescription = "Next track",
                        tint = TunelystColors.Text,
                        size = 22.dp,
                        label = "mini-player-next-icon"
                    )
                }
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(2.dp).background(TunelystColors.Primary))
        }
    }
}

@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = TunelystColors.Primary,
            contentColor = TunelystColors.OnPrimary
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(7.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun IconRoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = TunelystColors.Text,
    size: Dp = 48.dp
) {
    FilledTonalIconButton(
        onClick = onClick,
        shape = CircleShape,
        modifier = modifier.size(size),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = TunelystColors.SurfaceRaised,
            contentColor = tint
        )
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(size * 0.46f))
    }
}

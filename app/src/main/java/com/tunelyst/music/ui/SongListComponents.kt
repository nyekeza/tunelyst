package com.tunelyst.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.tunelyst.music.data.Song
import com.tunelyst.music.data.formatTime

@Composable
fun SectionHeading(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val compactSpacing = LocalDensity.current.fontScale >= 1.25f ||
        configuration.screenWidthDp < 360 || configuration.screenHeightDp < 650
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = if (compactSpacing) 7.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = TunelystColors.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (actionLabel != null && onAction != null) {
            Surface(
                modifier = Modifier.springyClickable(onClick = onAction),
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(1.dp, TunelystColors.Subtle.copy(alpha = 0.4f))
            ) {
                Text(
                    text = actionLabel,
                    color = TunelystColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
fun SongRow(
    song: Song,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayLast: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    isHiddenFromHome: Boolean = false,
    onToggleHomeVisibility: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    showAlbum: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: (() -> Unit)? = null,
    onEditMetadata: (() -> Unit)? = null
) {
    var menuExpanded by remember(song.id) { mutableStateOf(false) }
    val actionController = LocalSongActionController.current
    val isMarked = song.id in (actionController?.markedSongIds ?: emptySet())
    val motionEnabled = LocalTunelystMotionEnabled.current
    val compactLayout = isTunelystCompactViewport()
    val artworkSize = if (compactLayout) 46.dp else 54.dp
    val titleMaxLines = if (LocalDensity.current.fontScale >= 1.3f) 3 else 2
    val isCurrentlyPlaying = actionController?.currentlyPlayingSongId == song.id
    val favoriteInteraction = remember(song.id) { MutableInteractionSource() }
    val optionsInteraction = remember(song.id) { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compactLayout) 64.dp else 72.dp)
.springyCombinedClickable(
                pressedScale = 0.965f,
                onClick = {
                    when {
                        onToggleSelection != null -> onToggleSelection()
                        actionController != null && actionController.markedSongIds.isNotEmpty() -> actionController.onToggleMark(song)
                        else -> onClick()
                    }
                },
                onLongClick = { menuExpanded = true }
            )
            .padding(horizontal = 2.dp, vertical = if (compactLayout) 4.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(artworkSize)) {
            AlbumArtwork(
                artworkUri = song.artworkUri,
                title = song.title,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(13.dp)
            )
            AnimatedContent(
                targetState = isMarked,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                transitionSpec = {
                    if (motionEnabled) {
                        (scaleIn(animationSpec = spring(dampingRatio = 0.45f, stiffness = 520f)) + fadeIn(tween(100))) togetherWith
                            (scaleOut(animationSpec = tween(90)) + fadeOut(tween(70)))
                    } else EnterTransition.None togetherWith ExitTransition.None
                },
                label = "track-selection-badge"
            ) { marked ->
                if (marked) {
                    Box(Modifier.size(19.dp).clip(CircleShape).background(TunelystColors.Primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = TunelystColors.OnPrimary, modifier = Modifier.size(13.dp))
                    }
                } else Spacer(Modifier.size(0.dp))
            }
        }
        Spacer(Modifier.width(if (compactLayout) 9.dp else 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.title,
                    color = if (isCurrentlyPlaying) TunelystColors.Lime else TunelystColors.Text,
                    fontSize = 15.sp,
                    fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = titleMaxLines,
                    lineHeight = 18.sp,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                SongStatusIndicator(song.id, actionController)
            }
            Text(
                text = if (showAlbum) "${song.artist}  ·  ${song.album}" else song.artist,
                color = TunelystColors.Muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        if (onToggleSelection != null) {
            Checkbox(checked = isSelected, onCheckedChange = { onToggleSelection() })
        }
        Text(
            text = formatTime(song.durationMs),
            color = TunelystColors.Subtle,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        if (isFavorite) {
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(38.dp).springyPress(favoriteInteraction),
                interactionSource = favoriteInteraction
            ) {
                ExpressiveStateIcon(
                    icon = Icons.Rounded.Favorite,
                    contentDescription = "Remove from Liked",
                    tint = TunelystColors.Rose,
                    size = 19.dp,
                    label = "track-favorite-icon"
                )
            }
        }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(34.dp).springyPress(optionsInteraction),
                interactionSource = optionsInteraction
            ) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More options", tint = TunelystColors.Muted, modifier = Modifier.size(20.dp))
            }
        }
    }
    if (menuExpanded) {
        SongActionDialog(
            song = song,
            isFavorite = isFavorite,
            isMarked = isMarked,
            isHiddenFromHome = isHiddenFromHome || song.id in (actionController?.hiddenHomeSongIds ?: emptySet()),
            onDismiss = { menuExpanded = false },
            callbacks = SongActionCallbacks(
                onPlayNow = if (onToggleSelection != null) {
                    { actionController?.onPlay(song) ?: onClick() }
                } else onClick,
                onPlayNext = onPlayNext,
                onAddToQueue = onPlayLast,
                onAddToPlaylist = onAddToPlaylist,
                onToggleMark = actionController?.let { { it.onToggleMark(song) } },
                onToggleFavorite = onToggleFavorite,
                onShare = actionController?.let { { it.onShare(song) } },
                onStartRadio = actionController?.let { { it.onStartRadio(song) } },
                onOpenAlbum = actionController?.let { { it.onOpenAlbum(song) } },
                onOpenArtist = actionController?.let { { it.onOpenArtist(song) } },
                onEditMetadata = onEditMetadata ?: actionController?.let { { it.onEditMetadata(song) } },
                onToggleHomeVisibility = onToggleHomeVisibility ?: actionController?.let { { it.onToggleHomeVisibility(song) } },
                onRemoveFromPlaylist = onRemoveFromPlaylist,
                onDeleteFromDevice = actionController?.let { { it.onDeleteFromDevice(song) } }
            )
        )
    }
}

@Composable
fun SongCard(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 148.dp,
    isHiddenFromHome: Boolean = false,
    onToggleHomeVisibility: (() -> Unit)? = null
) {
    var menuExpanded by remember(song.id) { mutableStateOf(false) }
    val actionController = LocalSongActionController.current
    val isFavorite = song.id in (actionController?.favoriteSongIds ?: emptySet())
    val isMarked = song.id in (actionController?.markedSongIds ?: emptySet())
    val isCurrentlyPlaying = actionController?.currentlyPlayingSongId == song.id
    val titleMaxLines = if (LocalDensity.current.fontScale >= 1.3f) 3 else 2
    val motionEnabled = LocalTunelystMotionEnabled.current
    Box(modifier = modifier.width(width)) {
        Column(
            modifier = Modifier.fillMaxWidth().springyCombinedClickable(
                pressedScale = 0.965f,
                onClick = {
                    if (actionController != null && actionController.markedSongIds.isNotEmpty()) {
                        actionController.onToggleMark(song)
                    } else {
                        onClick()
                    }
                },
                onLongClick = { menuExpanded = true }
            )
        ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            AlbumArtwork(
                artworkUri = song.artworkUri,
                title = song.title,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(22.dp)
            )
            AnimatedContent(
                targetState = isMarked,
                modifier = Modifier.align(Alignment.TopEnd).padding(7.dp),
                transitionSpec = {
                    if (motionEnabled) {
                        (scaleIn(animationSpec = spring(dampingRatio = 0.45f, stiffness = 520f)) + fadeIn(tween(110))) togetherWith
                            (scaleOut(animationSpec = tween(90)) + fadeOut(tween(70)))
                    } else EnterTransition.None togetherWith ExitTransition.None
                },
                label = "card-selection-badge"
            ) { marked ->
                if (marked) {
                    Box(Modifier.size(23.dp).clip(CircleShape).background(TunelystColors.Primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = TunelystColors.OnPrimary, modifier = Modifier.size(16.dp))
                    }
                } else Spacer(Modifier.size(0.dp))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = song.title,
                color = if (isCurrentlyPlaying) TunelystColors.Lime else TunelystColors.Text,
                fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            SongStatusIndicator(song.id, actionController)
        }
        Text(
            text = song.artist,
            color = TunelystColors.Muted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp)
        )
        }
    }
    if (menuExpanded) {
        SongActionDialog(
            song = song,
            isFavorite = isFavorite,
            isMarked = isMarked,
            isHiddenFromHome = isHiddenFromHome || song.id in (actionController?.hiddenHomeSongIds ?: emptySet()),
            onDismiss = { menuExpanded = false },
            callbacks = SongActionCallbacks(
                onPlayNow = onClick,
                onPlayNext = actionController?.let { { it.onPlayNext(song) } },
                onAddToQueue = actionController?.let { { it.onAddToQueue(song) } },
                onAddToPlaylist = actionController?.let { { it.onAddToPlaylist(song) } },
                onToggleMark = actionController?.let { { it.onToggleMark(song) } },
                onToggleFavorite = actionController?.let { { it.onToggleFavorite(song) } },
                onShare = actionController?.let { { it.onShare(song) } },
                onStartRadio = actionController?.let { { it.onStartRadio(song) } },
                onOpenAlbum = actionController?.let { { it.onOpenAlbum(song) } },
                onOpenArtist = actionController?.let { { it.onOpenArtist(song) } },
                onEditMetadata = actionController?.let { { it.onEditMetadata(song) } },
                onToggleHomeVisibility = onToggleHomeVisibility ?: actionController?.let { { it.onToggleHomeVisibility(song) } },
                onDeleteFromDevice = actionController?.let { { it.onDeleteFromDevice(song) } }
            )
        )
    }
}

@Composable
internal fun SongStatusIndicator(songId: Long, controller: SongActionController?) {
    if (controller == null) return
    when {
        controller.currentlyPlayingSongId == songId -> {
            if (controller.isPlaying) {
                AnimatedNowPlayingBars()
            } else {
                Icon(
                    imageVector = Icons.Rounded.Pause,
                    contentDescription = "Now playing, paused",
                    tint = TunelystColors.Lime,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
        songId in controller.playNextSongIds -> Icon(
            imageVector = Icons.Rounded.SkipNext,
            contentDescription = "Added to Play Next",
            tint = TunelystColors.Lime,
            modifier = Modifier.size(18.dp)
        )
        songId in controller.queuedSongIds -> Icon(
            imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
            contentDescription = "Added to queue",
            tint = TunelystColors.Primary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun AnimatedNowPlayingBars() {
    val motionEnabled = LocalTunelystMotionEnabled.current
    if (!motionEnabled) {
        NowPlayingBars(listOf(0.36f, 0.78f, 0.5f))
        return
    }
    val transition = rememberInfiniteTransition(label = "track-now-playing-bars")
    val barOne by transition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.86f,
        animationSpec = infiniteRepeatable(tween(430, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "track-now-playing-bar-one"
    )
    val barTwo by transition.animateFloat(
        initialValue = 0.84f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(510, delayMillis = 95, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "track-now-playing-bar-two"
    )
    val barThree by transition.animateFloat(
        initialValue = 0.34f,
        targetValue = 0.96f,
        animationSpec = infiniteRepeatable(tween(470, delayMillis = 185, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "track-now-playing-bar-three"
    )
    NowPlayingBars(listOf(barOne, barTwo, barThree))
}

@Composable
private fun NowPlayingBars(heights: List<Float>) {
    Row(
        modifier = Modifier.width(14.dp).height(18.dp).semantics { contentDescription = "Now playing" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        heights.forEach { fraction ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(18.dp * fraction.coerceIn(0.2f, 1f))
                    .clip(CircleShape)
                    .background(TunelystColors.Lime)
            )
        }
    }
}

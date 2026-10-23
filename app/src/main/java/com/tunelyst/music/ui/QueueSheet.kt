package com.tunelyst.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tunelyst.music.data.Song
import kotlin.math.roundToInt

@Composable
internal fun QueueSheet(
    queue: List<Song>,
    currentIndex: Int,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    onPlay: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val upcomingStart = (currentIndex + 1).coerceIn(0, queue.size)
    val currentSong = queue.getOrNull(currentIndex)
    val upcoming = queue.drop(upcomingStart)
    val upcomingKeys = remember(upcoming) {
        val occurrences = mutableMapOf<Long, Int>()
        upcoming.map { song ->
            val occurrence = occurrences.getOrDefault(song.id, 0)
            occurrences[song.id] = occurrence + 1
            "${song.id}:$occurrence"
        }
    }
    val density = LocalDensity.current
    var queueRowHeightPx by remember(density) { mutableFloatStateOf(with(density) { 76.dp.toPx() }) }
    val hapticFeedback = LocalHapticFeedback.current
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragDistance by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.52f)).clickable(onClick = onDismiss))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                    .background(Brush.verticalGradient(listOf(TunelystColors.SurfaceRaised, TunelystColors.Background)))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(38.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(TunelystColors.Text.copy(alpha = 0.23f))
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Queue", color = TunelystColors.Text, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        text = "${upcoming.size} ${if (upcoming.size == 1) "song" else "songs"}",
                        color = TunelystColors.Muted,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                }

                if (currentSong != null) {
                    Text("NOW PLAYING", color = TunelystColors.Subtle, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 11.dp, bottom = 7.dp))
                    QueueNowPlayingRow(currentSong, isPlaying, onTogglePlayback)
                    Spacer(Modifier.height(18.dp))
                }

                Row(modifier = Modifier.fillMaxWidth().padding(start = 5.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("UP NEXT", color = TunelystColors.Subtle, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
                    if (upcoming.isNotEmpty()) Text("HOLD ≡ TO REORDER", color = TunelystColors.Subtle, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                }

                if (upcoming.isEmpty()) {
                    Surface(color = TunelystColors.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(17.dp)) {
                            Text("Nothing queued after this song", color = TunelystColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Add tracks from your library to queue them next in the order you choose.", color = TunelystColors.Muted, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 15.dp)
                    ) {
                        itemsIndexed(
                            items = upcoming,
                            key = { index, _ -> upcomingKeys[index] }
                        ) { relativeIndex, song ->
                            val queueIndex = upcomingStart + relativeIndex
                            QueueTrackRow(
                                modifier = Modifier.animateItem(),
                                song = song,
                                queueIndex = queueIndex,
                                accent = TunelystColors.Primary,
                                isDragging = draggingIndex == queueIndex,
                                dragDistance = dragDistance,
                                onPlay = { onPlay(queueIndex) },
                                onRemove = { onRemoveQueueItem(queueIndex) },
                                onMoveToTop = { onMoveQueueItem(queueIndex, upcomingStart) },
                                onDragStart = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    draggingIndex = queueIndex
                                    dragDistance = 0f
                                },
                                onDrag = { amount ->
                                    if (draggingIndex == queueIndex) dragDistance += amount
                                },
                                onDragEnd = {
                                    if (draggingIndex == queueIndex) {
                                        val rowHeightPx = queueRowHeightPx.coerceAtLeast(1f)
                                        val deltaRows = (dragDistance / rowHeightPx).roundToInt()
                                        val destination = (queueIndex + deltaRows).coerceIn(upcomingStart, queue.lastIndex)
                                        if (destination != queueIndex) {
                                            onMoveQueueItem(queueIndex, destination)
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        draggingIndex = -1
                                        dragDistance = 0f
                                    }
                                },
                                onDragCancel = {
                                    if (draggingIndex == queueIndex) {
                                        draggingIndex = -1
                                        dragDistance = 0f
                                    }
                                },
                                onHeightChanged = { queueRowHeightPx = it.coerceAtLeast(1f) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueNowPlayingRow(song: Song, isPlaying: Boolean, onTogglePlayback: () -> Unit) {
    val motionEnabled = LocalTunelystMotionEnabled.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(TunelystColors.Surface)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArtwork(song.artworkUri, song.title, Modifier.size(47.dp), RoundedCornerShape(10.dp))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(
                song.title,
                color = TunelystColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                lineHeight = 17.sp,
                overflow = TextOverflow.Clip,
                modifier = if (motionEnabled) Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE) else Modifier.fillMaxWidth()
            )
            Text(song.artist, color = TunelystColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlayPauseControl(
            modifier = Modifier.size(40.dp),
            isPlaying = isPlaying,
            containerColor = TunelystColors.Primary.copy(alpha = 0.18f),
            contentColor = TunelystColors.Primary,
            onClick = onTogglePlayback
        )
    }
}

@Composable
private fun QueueTrackRow(
    song: Song,
    queueIndex: Int,
    accent: Color,
    isDragging: Boolean,
    dragDistance: Float,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMoveToTop: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onHeightChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember(song.id, queueIndex) { mutableStateOf(false) }
    val menuInteraction = remember(song.id, queueIndex) { MutableInteractionSource() }
    val motionEnabled = LocalTunelystMotionEnabled.current
    val dragScale by animateFloatAsState(
        targetValue = if (motionEnabled && isDragging) 1.025f else 1f,
        animationSpec = if (motionEnabled) spring(dampingRatio = 0.72f, stiffness = 480f) else snap(),
        label = "queue-row-drag-scale"
    )
    val handleColor by animateColorAsState(
        targetValue = if (isDragging) accent else TunelystColors.Subtle,
        animationSpec = if (motionEnabled) tween(120) else snap(),
        label = "queue-reorder-handle-color"
    )
    val actionController = LocalSongActionController.current
    val compactLayout = isTunelystCompactViewport()
    val titleMaxLines = if (LocalDensity.current.fontScale >= 1.3f) 3 else 2
    val isMarked = song.id in (actionController?.markedSongIds ?: emptySet())
    val isFavorite = song.id in (actionController?.favoriteSongIds ?: emptySet())
    val isHiddenFromHome = song.id in (actionController?.hiddenHomeSongIds ?: emptySet())

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compactLayout) 68.dp else 76.dp)
            .onSizeChanged { onHeightChanged(it.height.toFloat()) }
            .graphicsLayer {
                scaleX = dragScale
                scaleY = dragScale
                if (isDragging) {
                    translationY = dragDistance
                    shadowElevation = 12.dp.toPx()
                }
            }
            .clip(RoundedCornerShape(15.dp))
            .background(if (isDragging) TunelystColors.Primary.copy(alpha = 0.14f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .springyCombinedClickable(
                    pressedScale = 0.965f,
                    onClick = {
                        if (actionController != null && actionController.markedSongIds.isNotEmpty()) {
                            actionController.onToggleMark(song)
                        } else {
                            onPlay()
                        }
                    },
                    onLongClick = { menuExpanded = true }
                )
                .padding(start = 5.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(if (compactLayout) 43.dp else 49.dp)) {
                AlbumArtwork(song.artworkUri, song.title, Modifier.fillMaxSize(), RoundedCornerShape(10.dp))
                AnimatedContent(
                    targetState = isMarked,
                    modifier = Modifier.align(Alignment.TopEnd).padding(1.dp),
                    transitionSpec = {
                        if (motionEnabled) {
                            (scaleIn(animationSpec = spring(dampingRatio = 0.45f, stiffness = 520f)) + fadeIn(tween(100))) togetherWith
                                (scaleOut(animationSpec = tween(90)) + fadeOut(tween(70)))
                        } else androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                    },
                    label = "queue-selection-badge"
                ) { marked ->
                    if (marked) {
                        Box(Modifier.size(17.dp).clip(CircleShape).background(TunelystColors.Primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = TunelystColors.OnPrimary, modifier = Modifier.size(12.dp))
                        }
                    } else Spacer(Modifier.size(0.dp))
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = if (compactLayout) 8.dp else 10.dp)) {
                Text(song.title, color = TunelystColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = titleMaxLines, lineHeight = 17.sp, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = TunelystColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(
            onClick = { menuExpanded = true },
            modifier = Modifier.size(36.dp).springyPress(menuInteraction),
            interactionSource = menuInteraction
        ) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Queue options", tint = TunelystColors.Muted, modifier = Modifier.size(19.dp))
        }
        Box(
            modifier = Modifier
                .size(39.dp)
                .semantics { contentDescription = "Hold to reorder ${song.title}" }
                .pointerInput(queueIndex) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.y)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text("≡", color = handleColor, fontSize = if (isDragging) 24.sp else 22.sp, fontWeight = FontWeight.Medium)
        }
    }
    if (menuExpanded) {
        SongActionDialog(
            song = song,
            isFavorite = isFavorite,
            isMarked = isMarked,
            isHiddenFromHome = isHiddenFromHome,
            onDismiss = { menuExpanded = false },
            callbacks = SongActionCallbacks(
                onPlayNow = onPlay,
                onPlayNext = onMoveToTop,
                onAddToPlaylist = actionController?.let { { it.onAddToPlaylist(song) } },
                onToggleMark = actionController?.let { { it.onToggleMark(song) } },
                onToggleFavorite = actionController?.let { { it.onToggleFavorite(song) } },
                onShare = actionController?.let { { it.onShare(song) } },
                onStartRadio = actionController?.let { { it.onStartRadio(song) } },
                onOpenAlbum = actionController?.let { { it.onOpenAlbum(song) } },
                onOpenArtist = actionController?.let { { it.onOpenArtist(song) } },
                onEditMetadata = actionController?.let { { it.onEditMetadata(song) } },
                onToggleHomeVisibility = actionController?.let { { it.onToggleHomeVisibility(song) } },
                onRemoveFromQueue = onRemove,
                onDeleteFromDevice = actionController?.let { { it.onDeleteFromDevice(song) } }
            )
        )
    }
}

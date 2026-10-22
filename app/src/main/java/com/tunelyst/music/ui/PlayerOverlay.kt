@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.tunelyst.music.data.Song
import com.tunelyst.music.data.formatTime
import kotlin.math.roundToInt

@UnstableApi
@Composable
fun FullPlayerDialog(
    song: Song,
    playback: PlaybackUiState,
    ambientColors: List<Color>,
    isFavorite: Boolean,
    sleepTimerMinutes: Int?,
    sleepTimerFadeOutSeconds: Int,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (Song) -> Unit,
    onStartRadio: (Song) -> Unit,
    onTogglePlayback: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onPlayQueueItem: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onStartSleepTimer: (Int, Int) -> Unit,
    onCancelSleepTimer: () -> Unit
) {
    var queueOpen by remember { mutableStateOf(false) }
    var timerOpen by remember { mutableStateOf(false) }
    var playerMenuOpen by remember { mutableStateOf(false) }
    var songInfoOpen by remember(song.id) { mutableStateOf(false) }
    var dragging by remember(song.id) { mutableStateOf(false) }
    var sliderPosition by remember(song.id) { mutableFloatStateOf(playback.positionMs.toFloat()) }
    val maxPosition = playback.durationMs.coerceAtLeast(1L).toFloat()
    val upcomingCount = (playback.queue.size - playback.currentIndex - 1).coerceAtLeast(0)
    val palette = LocalTunelystPalette.current
    val motionEnabled = LocalTunelystMotionEnabled.current
    val lowMemoryDevice = LocalTunelystLowMemoryDevice.current
    val artworkSwipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    val dialogContext = LocalContext.current
    val ambientArtworkRequest = remember(song.artworkUri, dialogContext, motionEnabled, lowMemoryDevice) {
        song.artworkUri?.let { artworkUri ->
            coil.request.ImageRequest.Builder(dialogContext)
                .data(artworkUri)
                .allowHardware(!lowMemoryDevice)
                .bitmapConfig(if (lowMemoryDevice) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
                .crossfade(motionEnabled && !lowMemoryDevice)
                .build()
        }
    }
    val dismissInteraction = remember { MutableInteractionSource() }
    val moreInteraction = remember { MutableInteractionSource() }
    val favoriteInteraction = remember(song.id) { MutableInteractionSource() }
    val colorMotion = if (motionEnabled) tween<Color>(500) else snap()
    val ambientStart by animateColorAsState(ambientColors.getOrElse(0) { Color(0xFF282433) }, animationSpec = colorMotion, label = "ambient-start")
    val ambientAccent by animateColorAsState(ambientColors.getOrElse(1) { TunelystColors.Primary }, animationSpec = colorMotion, label = "ambient-accent")
    val ambientShadow by animateColorAsState(ambientColors.getOrElse(2) { Color(0xFF111116) }, animationSpec = colorMotion, label = "ambient-shadow")
    val accent = TunelystColors.Primary
    LaunchedEffect(playback.positionMs, dragging, maxPosition) {
        if (!dragging) sliderPosition = playback.positionMs.toFloat().coerceIn(0f, maxPosition)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val dialogView = LocalView.current
        val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { window ->
                // Avoid the platform decor fade/scale flashing over the artwork-driven Compose surface.
                window.setWindowAnimations(0)
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = palette.isLight
                    isAppearanceLightNavigationBars = palette.isLight
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TunelystColors.Background)
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            ambientStart.copy(alpha = if (palette.isLight) 0.24f else 0.72f),
                            ambientAccent.copy(alpha = if (palette.isLight) 0.16f else 0.47f),
                            ambientShadow.copy(alpha = if (palette.isLight) 0.11f else 0.32f),
                            TunelystColors.Background
                        )
                    )
                )
            )
            ambientArtworkRequest?.let { request ->
                coil.compose.AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            alpha = if (palette.isLight) 0.34f else 0.46f,
                            scaleX = if (lowMemoryDevice) 1.08f else 1.16f,
                            scaleY = if (lowMemoryDevice) 1.08f else 1.16f
                        )
                        .then(if (lowMemoryDevice) Modifier else Modifier.blur(54.dp))
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                TunelystColors.Background.copy(alpha = if (palette.isLight) 0.54f else 0.24f),
                                TunelystColors.Background.copy(alpha = if (palette.isLight) 0.60f else 0.61f),
                                TunelystColors.Background.copy(alpha = if (palette.isLight) 0.86f else 0.91f)
                            )
                        )
                    )
            )

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val fontScale = LocalDensity.current.fontScale
                val layoutGap = when {
                    maxHeight < 620.dp || fontScale >= 1.5f -> 8.dp
                    maxHeight < 760.dp || fontScale >= 1.25f -> 10.dp
                    else -> 14.dp
                }
                val volumeSpacerHeight = if (maxHeight < 700.dp || fontScale >= 1.25f) 6.dp else 10.dp
                val outerBottomPadding = layoutGap
                val dockBottomPadding = layoutGap
                val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
                val navigationBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val dockHeight = 52.dp + dockBottomPadding + navigationBarBottomPadding
                val mainControlsMaxHeight = (
                    maxHeight - safeDrawingPadding.calculateTopPadding() - layoutGap - outerBottomPadding -
                        46.dp - 38.dp - dockHeight - layoutGap * 3f - volumeSpacerHeight * 2f
                    ).coerceAtLeast(0.dp)
                // Match the app's shared content column; only the artwork shrinks when screen
                // height, rather than width, becomes the limiting dimension.
                val playerColumnWidth = maxWidth.coerceAtMost(760.dp)
                val contentWidth = (playerColumnWidth - 40.dp).coerceAtLeast(0.dp)
                val ultraNarrowControls = contentWidth < 260.dp
                val veryNarrowControls = contentWidth < 300.dp
                val narrowControls = contentWidth < 350.dp
                val sideControlSize = when {
                    ultraNarrowControls -> 32.dp
                    veryNarrowControls -> 36.dp
                    narrowControls -> 40.dp
                    else -> 42.dp
                }
                val skipControlSize = when {
                    ultraNarrowControls -> 38.dp
                    veryNarrowControls -> 42.dp
                    narrowControls -> 48.dp
                    else -> 52.dp
                }
                val playControlSize = when {
                    ultraNarrowControls -> 54.dp
                    veryNarrowControls -> 60.dp
                    narrowControls -> 68.dp
                    else -> 74.dp
                }
                val transportGap = when {
                    ultraNarrowControls -> 2.dp
                    veryNarrowControls -> 4.dp
                    narrowControls -> 8.dp
                    else -> 12.dp
                }
                val modeGap = when {
                    ultraNarrowControls -> 6.dp
                    veryNarrowControls -> 10.dp
                    narrowControls -> 16.dp
                    else -> 24.dp
                }
                val artworkFloor = minOf(contentWidth, 144.dp)
                val transportHeight = maxOf(sideControlSize, skipControlSize, playControlSize)
                val otherControlsHeight =
                    transportHeight + 30.dp + 14.dp * fontScale + 96.dp * fontScale + layoutGap * 3f
                val artworkHeightLimit = (mainControlsMaxHeight - otherControlsHeight).coerceAtLeast(artworkFloor)
                val artworkSize = minOf(contentWidth, artworkHeightLimit)
                Column(
                    modifier = Modifier
                        .width(playerColumnWidth)
                        .fillMaxHeight()
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 20.dp)
                        .padding(top = layoutGap, bottom = outerBottomPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(layoutGap)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.28f)).springyPress(dismissInteraction),
                            interactionSource = dismissInteraction
                        ) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Minimize player", tint = TunelystColors.Text, modifier = Modifier.size(28.dp))
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text("NOW PLAYING", color = TunelystColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                            Text(
                                text = "RADIO · ${if (playback.radioActive) "ON" else "OFF"}",
                                color = if (playback.radioActive) accent else TunelystColors.Subtle,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.1.sp
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { playerMenuOpen = true },
                                modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.28f)).springyPress(moreInteraction),
                                interactionSource = moreInteraction
                            ) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Player options", tint = TunelystColors.Text)
                            }
                            DropdownMenu(
                                expanded = playerMenuOpen,
                                onDismissRequest = { playerMenuOpen = false },
                                containerColor = TunelystColors.SurfaceRaised.copy(alpha = 0.86f)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Song information") },
                                    onClick = { playerMenuOpen = false; songInfoOpen = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Start radio from this song") },
                                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                                    onClick = { playerMenuOpen = false; onStartRadio(song) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Open album") },
                                    onClick = { playerMenuOpen = false; onOpenAlbum(song) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Open artist") },
                                    onClick = { playerMenuOpen = false; onOpenArtist(song.artist) }
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = mainControlsMaxHeight)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(layoutGap)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(artworkSize)
                                    .aspectRatio(1f)
                                    .combinedClickable(
                                        onClick = { /* Single tap is intentionally reserved for artwork gestures. */ },
                                        onDoubleClick = onTogglePlayback,
                                        onLongClick = { songInfoOpen = true }
                                    )
                                    .semantics {
                                        contentDescription = "Artwork for ${song.title}. Double tap to pause or play, swipe to change songs, or press and hold for song information."
                                    }
                                    .pointerInput(song.id, artworkSwipeThresholdPx, onPrevious, onNext) {
                                        var horizontalDrag = 0f
                                        detectHorizontalDragGestures(
                                            onDragStart = { horizontalDrag = 0f },
                                            onHorizontalDrag = { change, amount ->
                                                horizontalDrag += amount
                                                change.consume()
                                            },
                                            onDragEnd = {
                                                when {
                                                    horizontalDrag >= artworkSwipeThresholdPx -> onPrevious()
                                                    horizontalDrag <= -artworkSwipeThresholdPx -> onNext()
                                                }
                                            },
                                            onDragCancel = { horizontalDrag = 0f }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .padding(20.dp)
                                        .then(if (lowMemoryDevice) Modifier else Modifier.blur(24.dp))
                                        .background(accent.copy(alpha = 0.43f), RoundedCornerShape(36.dp))
                                )
                                AlbumArtwork(
                                    artworkUri = song.artworkUri,
                                    title = song.title,
                                    modifier = Modifier.fillMaxSize(),
                                    shape = RoundedCornerShape(28.dp),
                                    crossfade = true
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        color = TunelystColors.Text,
                                        fontSize = 23.sp,
                                        lineHeight = 28.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Clip,
                                        modifier = if (motionEnabled) Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE) else Modifier.fillMaxWidth()
                                    )
                                    val albumTitle = song.album.takeUnless { it.isBlank() || it.equals("Unknown album", ignoreCase = true) }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val artistWidth = song.artist.length.coerceIn(8, 36).toFloat()
                                        Text(
                                            text = song.artist,
                                            color = TunelystColors.Muted,
                                            fontSize = 15.sp,
                                            modifier = Modifier.weight(if (albumTitle == null) 1f else artistWidth, fill = false)
                                                .springyClickable(role = androidx.compose.ui.semantics.Role.Button) {
                                                    onOpenArtist(song.artist)
                                                }
                                                .semantics { contentDescription = "Open artist ${song.artist}" },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        albumTitle?.let { album ->
                                            Text("•", color = TunelystColors.Muted, fontSize = 17.sp, fontWeight = FontWeight.Black)
                                            Text(
                                                text = album,
                                                color = TunelystColors.Muted,
                                                fontSize = 15.sp,
                                                modifier = Modifier.weight(album.length.coerceIn(8, 36).toFloat(), fill = false)
                                                    .springyClickable(role = androidx.compose.ui.semantics.Role.Button) {
                                                        onOpenAlbum(song)
                                                    }
                                                    .semantics { contentDescription = "Open album $album" },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                IconButton(
                                    onClick = onToggleFavorite,
                                    modifier = Modifier.size(48.dp).clip(CircleShape).background(TunelystColors.SurfaceRaised.copy(alpha = 0.72f)).springyPress(favoriteInteraction),
                                    interactionSource = favoriteInteraction
                                ) {
                                    ExpressiveToggleIcon(
                                        selected = isFavorite,
                                        selectedIcon = Icons.Rounded.Favorite,
                                        unselectedIcon = Icons.Rounded.FavoriteBorder,
                                        contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                                        tint = if (isFavorite) TunelystColors.Rose else TunelystColors.Text,
                                        size = 22.dp,
                                        label = "now-playing-favorite-icon"
                                    )
                                }
                            }

                            Column(modifier = Modifier.fillMaxWidth()) {
                                val displayedPositionMs = if (dragging) sliderPosition.toLong() else playback.positionMs
                                AppleMusicSeekBar(
                                    progress = (sliderPosition / maxPosition).coerceIn(0f, 1f),
                                    activeColor = TunelystColors.Text,
                                    inactiveColor = TunelystColors.Text.copy(alpha = if (palette.isLight) 0.30f else 0.35f),
                                    modifier = Modifier.fillMaxWidth().height(30.dp),
                                    enabled = true,
                                    onProgressChange = { fraction, isDragging ->
                                        dragging = isDragging
                                        sliderPosition = fraction * maxPosition
                                    },
                                    onProgressChangeFinished = {
                                        onSeek(sliderPosition.toLong())
                                        dragging = false
                                    }
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(formatTime(displayedPositionMs), color = TunelystColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text("−${formatTime((playback.durationMs - displayedPositionMs).coerceAtLeast(0L))}", color = TunelystColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PlayerModeControl(
                                    modifier = Modifier.size(sideControlSize),
                                    onClick = onToggleShuffle,
                                    contentDescription = "Shuffle",
                                    stateDescription = if (playback.shuffleEnabled) "On" else "Off",
                                    active = playback.shuffleEnabled,
                                    accent = accent,
                                    icon = Icons.Rounded.Shuffle
                                )
                                Spacer(Modifier.width(modeGap))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(transportGap),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SkipTrackControl(
                                        modifier = Modifier.size(skipControlSize),
                                        onClick = onPrevious,
                                        contentDescription = "Previous track",
                                        forward = false
                                    )
                                    PlayPauseControl(
                                        modifier = Modifier.size(playControlSize),
                                        isPlaying = playback.isPlaying,
                                        containerColor = ambientAccent,
                                        contentColor = artworkContentColor(ambientAccent),
                                        onClick = onTogglePlayback
                                    )
                                    SkipTrackControl(
                                        modifier = Modifier.size(skipControlSize),
                                        onClick = onNext,
                                        contentDescription = "Next track",
                                        forward = true
                                    )
                                }
                                Spacer(Modifier.width(modeGap))
                                PlayerModeControl(
                                    modifier = Modifier.size(sideControlSize),
                                    onClick = onCycleRepeat,
                                    contentDescription = "Repeat",
                                    stateDescription = when (playback.repeatMode) {
                                        Player.REPEAT_MODE_ONE -> "Repeat one"
                                        Player.REPEAT_MODE_ALL -> "Repeat all"
                                        else -> "Off"
                                    },
                                    active = playback.repeatMode != Player.REPEAT_MODE_OFF,
                                    accent = accent,
                                    icon = if (playback.repeatMode == Player.REPEAT_MODE_ONE) {
                                        Icons.Rounded.RepeatOne
                                    } else {
                                        Icons.Rounded.Repeat
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(volumeSpacerHeight))
                    AppleVolumeSlider(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(volumeSpacerHeight))
                    Row(
                        // Keep the compact action dock clear of the system navigation area.
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                            .padding(bottom = dockBottomPadding),
                        horizontalArrangement = Arrangement.spacedBy(layoutGap, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerDockIconButton(
                            icon = Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = "Open queue with $upcomingCount upcoming tracks",
                            badgeCount = upcomingCount,
                            accent = accent,
                            onClick = { queueOpen = true }
                        )
                        PlayerDockIconButton(
                            icon = Icons.Rounded.Add,
                            contentDescription = "Add ${song.title} to a playlist",
                            onClick = onAddToPlaylist
                        )
                        PlayerDockIconButton(
                            icon = Icons.Rounded.Bedtime,
                            contentDescription = if (sleepTimerMinutes == null) {
                                "Open sleep timer"
                            } else {
                                "Sleep timer set for $sleepTimerMinutes minutes"
                            },
                            selected = sleepTimerMinutes != null,
                            accent = accent,
                            onClick = { timerOpen = true }
                        )
                    }
                }
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = dockHeight + 8.dp)
                )
            }
        }
    }

    if (songInfoOpen) {
        SongInformationDialog(song = song, onDismiss = { songInfoOpen = false })
    }
    if (queueOpen) {
        QueueSheet(
            queue = playback.queue,
            currentIndex = playback.currentIndex,
            isPlaying = playback.isPlaying,
            onTogglePlayback = onTogglePlayback,
            onPlay = { index -> queueOpen = false; onPlayQueueItem(index) },
            onMoveQueueItem = onMoveQueueItem,
            onRemoveQueueItem = onRemoveQueueItem,
            onDismiss = { queueOpen = false }
        )
    }
    if (timerOpen) {
        SleepTimerDialog(
            currentMinutes = sleepTimerMinutes,
            currentFadeOutSeconds = sleepTimerFadeOutSeconds,
            onSelect = { minutes, fadeSeconds -> onStartSleepTimer(minutes, fadeSeconds); timerOpen = false },
            onCancel = { onCancelSleepTimer(); timerOpen = false },
            onDismiss = { timerOpen = false }
        )
    }
}

@Composable
private fun SongInformationDialog(song: Song, onDismiss: () -> Unit) {
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
private fun SkipTrackControl(
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
private fun PlayerModeControl(
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
private fun PlayPauseControl(
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
private fun PlayerDockIconButton(
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
private fun AppleVolumeSlider(modifier: Modifier = Modifier) {
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

@Composable
private fun QueueSheet(
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

@Composable
private fun SleepTimerDialog(
    currentMinutes: Int?,
    currentFadeOutSeconds: Int,
    onSelect: (Int, Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var customMinutes by remember { mutableStateOf("") }
    var fadeSeconds by remember(currentFadeOutSeconds) { mutableIntStateOf(currentFadeOutSeconds) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text("Pause playback after…", color = TunelystColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                listOf(15, 30, 45, 60).forEach { minutes ->
                    TextButton(onClick = { onSelect(minutes, fadeSeconds) }, modifier = Modifier.fillMaxWidth()) {
                        Text("$minutes minutes", color = TunelystColors.Text, modifier = Modifier.weight(1f))
                        if (minutes == currentMinutes) Text("ACTIVE", color = TunelystColors.Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedTextField(
                    value = customMinutes,
                    onValueChange = { customMinutes = it.filter(Char::isDigit).take(4) },
                    label = { Text("Custom minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
                )
                Text("Fade out · ${fadeSeconds}s", color = TunelystColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                Slider(value = fadeSeconds.toFloat(), onValueChange = { fadeSeconds = it.roundToInt() }, valueRange = 0f..60f, steps = 11)
                if (fadeSeconds > 0) Text("The fade runs at the end; crossfade is paused briefly so the volume curves don't conflict.", color = TunelystColors.Subtle, fontSize = 11.sp, lineHeight = 14.sp)
                val customValue = customMinutes.toIntOrNull()?.takeIf { it in 1..1_440 }
                TextButton(enabled = customValue != null, onClick = { customValue?.let { onSelect(it, fadeSeconds) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Set custom timer", color = TunelystColors.Primary, modifier = Modifier.weight(1f))
                    if (customValue == currentMinutes) Text("ACTIVE", color = TunelystColors.Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                if (currentMinutes != null) {
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Close, contentDescription = null, tint = TunelystColors.Rose)
                        Text("Cancel timer", color = TunelystColors.Rose, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        shape = RoundedCornerShape(26.dp),
        containerColor = TunelystColors.Surface
    )
}

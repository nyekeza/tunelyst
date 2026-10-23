package com.tunelyst.music.ui

import android.graphics.Bitmap

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player

import com.tunelyst.music.data.Song
import com.tunelyst.music.data.formatTime



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
    val upcomingCount = (playback.queue.size - playback.displayCurrentIndex - 1).coerceAtLeast(0)
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
                    FullPlayerHeader(
                        radioActive = playback.radioActive,
                        accent = accent,
                        playerMenuOpen = playerMenuOpen,
                        dismissInteraction = dismissInteraction,
                        moreInteraction = moreInteraction,
                        onDismiss = onDismiss,
                        onOpenPlayerMenu = { playerMenuOpen = true },
                        onDismissPlayerMenu = { playerMenuOpen = false },
                        onShowSongInformation = { playerMenuOpen = false; songInfoOpen = true },
                        onStartRadio = { playerMenuOpen = false; onStartRadio(song) },
                        onOpenAlbum = { playerMenuOpen = false; onOpenAlbum(song) },
                        onOpenArtist = { playerMenuOpen = false; onOpenArtist(song.artist) }
                    )
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
                            FullPlayerArtworkAndMetadata(
                                song = song,
                                artworkSize = artworkSize,
                                layoutGap = layoutGap,
                                lowMemoryDevice = lowMemoryDevice,
                                accent = accent,
                                artworkSwipeThresholdPx = artworkSwipeThresholdPx,
                                motionEnabled = motionEnabled,
                                isFavorite = isFavorite,
                                favoriteInteraction = favoriteInteraction,
                                onTogglePlayback = onTogglePlayback,
                                onShowSongInformation = { songInfoOpen = true },
                                onPrevious = onPrevious,
                                onNext = onNext,
                                onOpenArtist = onOpenArtist,
                                onOpenAlbum = onOpenAlbum,
                                onToggleFavorite = onToggleFavorite
                            )

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
            currentIndex = playback.displayCurrentIndex,
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

package com.tunelyst.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.tunelyst.music.R
import com.tunelyst.music.data.LibraryCleanupAnalysis
import com.tunelyst.music.data.LibraryCleanupSuggestion
import com.tunelyst.music.data.LibraryCleanupSuggestionKind
import com.tunelyst.music.data.canDeleteSelectedDuplicateTracks
import com.tunelyst.music.data.resolveCurrentDuplicateGroups
import com.tunelyst.music.data.Playlist
import com.tunelyst.music.data.SavedQueue
import com.tunelyst.music.data.SmartPlaylist
import com.tunelyst.music.data.Song
import com.tunelyst.music.data.analyzeLibraryCleanup
import com.tunelyst.music.data.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun HomeScreen(
    state: MusicUiState,
    playback: PlaybackUiState,
    isSmartShuffleActive: Boolean,
    activeQueueSongIds: List<Long>,
    onTogglePlayback: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onPlayRecommendationMix: (List<Song>, Int) -> Unit,
    onSmartShuffle: () -> Unit,
    onStartRadio: (Song) -> Unit,
    onStopRadio: () -> Unit,
    homeSectionOrder: List<String>,
    hiddenHomeSections: Set<String>,
    onToggleFavorite: (Long) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onPlayLast: (Song) -> Unit,
    onSetSongHiddenFromHome: (Long, Boolean) -> Unit,
    onSetHomeCardHidden: (String, Boolean) -> Unit,
    onRescan: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenNavigationDrawer: () -> Unit
) {
    val expressiveMotionEnabled = LocalTunelystMotionEnabled.current
    val homeListState = rememberLazyListState()
    val statusBarTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val compactHomeLayout = isTunelystCompactViewport()
    val veryCompactHomeLayout = isTunelystVeryCompactViewport()
    val horizontalCardWidth = when {
        veryCompactHomeLayout -> 136.dp
        compactHomeLayout -> 118.dp
        else -> 148.dp
    }
    val mixCardWidth = when {
        veryCompactHomeLayout -> 160.dp
        compactHomeLayout -> 140.dp
        else -> 176.dp
    }
    val homeSongs = remember(state.songs, state.hiddenHomeSongIds) {
        state.songs.filterNot { it.id in state.hiddenHomeSongIds }
    }
    val songsById = remember(homeSongs) { homeSongs.associateBy(Song::id) }
    val recentlyPlayed = remember(homeSongs, state.recentlyPlayedIds) {
        state.recentlyPlayedIds.mapNotNull(songsById::get)
    }
    val homeRecommendations = state.homeRecommendations
    val recentlyAdded = homeRecommendations?.recentlyAdded.orEmpty()
    val listenAgain = recentlyPlayed.ifEmpty { recentlyAdded }
    val madeForYou = homeRecommendations?.mixes.orEmpty()
        .filterNot { homeMixCardId(it.title) in state.hiddenHomeCardIds }
    val bestOfArtists = homeRecommendations?.bestOfArtists.orEmpty()
        .filterNot { homeMixCardId(it.title) in state.hiddenHomeCardIds }
    var openedMixTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var openedBestOfArtistTitle by rememberSaveable { mutableStateOf<String?>(null) }
    val openedMix = madeForYou.firstOrNull { it.title == openedMixTitle }
    val openedBestOfArtist = bestOfArtists.firstOrNull { it.title == openedBestOfArtistTitle }
    val openedRecommendation = openedMix ?: openedBestOfArtist
    BackHandler(enabled = openedRecommendation != null) {
        openedMixTitle = null
        openedBestOfArtistTitle = null
    }

    AnimatedVisibility(
        visible = openedRecommendation != null,
        enter = if (expressiveMotionEnabled) fadeIn(tween(140)) + scaleIn(initialScale = 0.97f, animationSpec = spring(dampingRatio = 0.78f, stiffness = 460f)) else EnterTransition.None,
        exit = ExitTransition.None
    ) {
        openedRecommendation?.let { mix -> MadeForYouMixDetailScreen(
            mix = mix,
            sectionLabel = if (openedBestOfArtist != null) "BEST OF ARTISTS" else "MADE FOR YOU",
            favorites = state.favorites,
            currentSong = playback.currentSong,
            isPlaying = playback.isPlaying,
            activeQueueSongIds = activeQueueSongIds,
            playbackHasEnded = playback.isEnded,
            hiddenHomeSongIds = state.hiddenHomeSongIds,
            onSetSongHiddenFromHome = onSetSongHiddenFromHome,
            onBack = {
                openedMixTitle = null
                openedBestOfArtistTitle = null
            },
            onPlay = onPlayRecommendationMix,
            onTogglePlayback = onTogglePlayback,
            onToggleFavorite = onToggleFavorite,
            onAddToPlaylist = onAddToPlaylist,
            onPlayNext = onPlayNext,
            onPlayLast = onPlayLast
        ) }
    }
    AnimatedVisibility(
        visible = openedRecommendation == null,
        enter = if (expressiveMotionEnabled) fadeIn(tween(140)) + scaleIn(initialScale = 0.985f, animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f)) else EnterTransition.None,
        exit = if (expressiveMotionEnabled) fadeOut(tween(90)) else ExitTransition.None
    ) {
    LazyColumn(
        state = homeListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 0.dp,
            bottom = if (compactHomeLayout) 20.dp else 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(if (compactHomeLayout) 12.dp else 18.dp)
    ) {
        item {
            HeroLibraryCard(
                songCount = state.songs.size,
                featuredSong = listenAgain.firstOrNull() ?: homeSongs.firstOrNull(),
                nowPlaying = playback.currentSong,
                isPlaying = playback.isPlaying,
                positionMs = playback.positionMs,
                durationMs = playback.durationMs,
                isBuffering = playback.isBuffering,
                playbackSpeed = playback.playbackSpeed,
                isLoading = state.isLoading,
                isLibraryReady = state.isLibraryReady,
                compactLayout = compactHomeLayout,
                veryCompactLayout = veryCompactHomeLayout,
                statusBarTopInset = statusBarTopInset,
                onTogglePlayback = onTogglePlayback,
                onOpenNowPlaying = onOpenNowPlaying,
                onRescan = onRescan,
                onOpenLibrary = onOpenLibrary,
                onOpenNavigationDrawer = onOpenNavigationDrawer
            )
        }
        if (state.songs.isNotEmpty()) {
            item {
                val radioSeed = playback.currentSong ?: listenAgain.firstOrNull() ?: homeSongs.firstOrNull()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = if (compactHomeLayout) 16.dp else 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (compactHomeLayout) 7.dp else 9.dp)
                ) {
                    HomeQuickAction(
                        label = when {
                            compactHomeLayout && isSmartShuffleActive -> "Shuffle · On"
                            compactHomeLayout -> "Shuffle"
                            isSmartShuffleActive -> "Smart Shuffle · On"
                            else -> "Smart Shuffle"
                        },
                        icon = Icons.Rounded.Shuffle,
                        selected = isSmartShuffleActive,
                        onClick = onSmartShuffle
                    )
                    if (radioSeed != null) {
                        HomeQuickAction(
                            label = if (playback.radioActive) "Radio · On" else "Radio · Off",
                            icon = Icons.Rounded.Radio,
                            selected = playback.radioActive
                        ) {
                            if (playback.radioActive) onStopRadio() else onStartRadio(radioSeed)
                        }
                    }
                }
            }
        }
        val mixSectionIds = setOf("mixes")
        var madeForYouSectionAdded = false
        homeSectionOrder.forEach { sectionId ->
            if (sectionId in mixSectionIds) {
                if (sectionId !in hiddenHomeSections && !madeForYouSectionAdded) {
                    madeForYouSectionAdded = true
                    when {
                        madeForYou.isNotEmpty() || bestOfArtists.isNotEmpty() -> item {
                            HomeRecommendationShelves(
                                madeForYou = madeForYou,
                                bestOfArtists = bestOfArtists,
                                showLoading = false,
                                cardWidth = mixCardWidth,
                                compactLayout = compactHomeLayout,
                                onOpenMix = { mix ->
                                    openedBestOfArtistTitle = null
                                    openedMixTitle = mix.title
                                },
                                onOpenBestOfArtist = { mix ->
                                    openedMixTitle = null
                                    openedBestOfArtistTitle = mix.title
                                },
                                onHideCard = { mix -> onSetHomeCardHidden(homeMixCardId(mix.title), true) }
                            )
                        }
                        state.songs.isNotEmpty() && homeRecommendations == null -> item {
                            HomeRecommendationShelves(
                                madeForYou = emptyList(),
                                bestOfArtists = emptyList(),
                                showLoading = true,
                                cardWidth = mixCardWidth,
                                compactLayout = compactHomeLayout,
                                onOpenMix = {},
                                onOpenBestOfArtist = {},
                                onHideCard = {}
                            )
                        }
                    }
                }
                return@forEach
            }
            if (sectionId in hiddenHomeSections) return@forEach
            when (sectionId) {
                // Liked tracks stay in the dedicated Liked tab instead of taking a Home shelf.
                "favorites" -> Unit
                "listen_again" -> item {
                    Column {
                        SectionHeading(
                            title = "Instant Vibes",
                            actionLabel = if (listenAgain.isNotEmpty()) "Play all" else null,
                            onAction = { if (listenAgain.isNotEmpty()) onPlay(listenAgain, 0) },
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        when {
                            !state.isLibraryReady -> Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { LoadingCard("Loading saved library…") }
                            state.isLoading && state.songs.isEmpty() -> Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { LoadingCard() }
                            state.songs.isNotEmpty() && homeRecommendations == null && recentlyPlayed.isEmpty() -> Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { LoadingCard("Preparing your home mix…") }
                            listenAgain.isEmpty() -> Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { EmptyLibraryCard(onRescan = onRescan, errorMessage = state.errorMessage) }
                            else -> QuickPickCarousel(
                                songs = listenAgain.take(12),
                                playCounts = state.playCounts,
                                compactLayout = compactHomeLayout,
                                onPlay = { song -> onPlay(listenAgain, listenAgain.indexOf(song)) },
                                onHideFromHome = { song -> onSetSongHiddenFromHome(song.id, true) }
                            )
                        }
                    }
                }
            }
        }
        if (!madeForYouSectionAdded && "mixes" !in hiddenHomeSections) {
            when {
                madeForYou.isNotEmpty() || bestOfArtists.isNotEmpty() -> item {
                    HomeRecommendationShelves(
                        madeForYou = madeForYou,
                        bestOfArtists = bestOfArtists,
                        showLoading = false,
                        cardWidth = mixCardWidth,
                        compactLayout = compactHomeLayout,
                        onOpenMix = { mix ->
                            openedBestOfArtistTitle = null
                            openedMixTitle = mix.title
                        },
                        onOpenBestOfArtist = { mix ->
                            openedMixTitle = null
                            openedBestOfArtistTitle = mix.title
                        },
                        onHideCard = { mix -> onSetHomeCardHidden(homeMixCardId(mix.title), true) }
                    )
                }
                state.songs.isNotEmpty() && homeRecommendations == null -> item {
                    HomeRecommendationShelves(
                        madeForYou = emptyList(),
                        bestOfArtists = emptyList(),
                        showLoading = true,
                        cardWidth = mixCardWidth,
                        compactLayout = compactHomeLayout,
                        onOpenMix = {},
                        onOpenBestOfArtist = {},
                        onHideCard = {}
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun HomeRecommendationShelves(
    madeForYou: List<MadeForYouMix>,
    bestOfArtists: List<MadeForYouMix>,
    showLoading: Boolean,
    cardWidth: Dp,
    compactLayout: Boolean,
    onOpenMix: (MadeForYouMix) -> Unit,
    onOpenBestOfArtist: (MadeForYouMix) -> Unit,
    onHideCard: (MadeForYouMix) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compactLayout) 12.dp else 16.dp)) {
        if (madeForYou.isNotEmpty()) {
            HomeMixCarousel(
                heading = "Made for you",
                mixes = madeForYou,
                cardWidth = cardWidth,
                compactLayout = compactLayout,
                onOpen = onOpenMix,
                onHideCard = onHideCard
            )
        } else if (showLoading) {
            Column {
                SectionHeading("Made for you", modifier = Modifier.padding(horizontal = 20.dp))
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    LoadingCard("Warming up your local mixes…")
                }
            }
        }
        if (bestOfArtists.isNotEmpty()) {
            HomeMixCarousel(
                heading = "Best of artists",
                mixes = bestOfArtists,
                cardWidth = cardWidth,
                compactLayout = compactLayout,
                onOpen = onOpenBestOfArtist,
                onHideCard = onHideCard
            )
        }
    }
}

@Composable
private fun HomeMixCarousel(
    heading: String,
    mixes: List<MadeForYouMix>,
    cardWidth: Dp,
    compactLayout: Boolean,
    onOpen: (MadeForYouMix) -> Unit,
    onHideCard: (MadeForYouMix) -> Unit
) {
    Column {
        SectionHeading(heading, modifier = Modifier.padding(horizontal = 20.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compactLayout) 10.dp else 14.dp)
        ) {
            items(mixes, key = MadeForYouMix::title) { mix ->
                MadeForYouCard(
                    mix = mix,
                    width = cardWidth,
                    modifier = Modifier.animateItem(),
                    onOpen = { onOpen(mix) },
                    onHideFromHome = { onHideCard(mix) }
                )
            }
        }
    }
}

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
private fun HomeQuickAction(
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

@Composable
private fun MixArtworkCollage(
    songs: List<Song>,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(19.dp)
) {
    val covers = songs.take(4)
    val tileShape = RoundedCornerShape(3.dp)
    Box(
        modifier = modifier.clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF155C69), Color(0xFF242A43), Color(0xFF382444))))
    ) {
        when (covers.size) {
            1 -> AlbumArtwork(covers[0].artworkUri, covers[0].title, Modifier.fillMaxSize(), shape)
            2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                covers.forEach { song ->
                    AlbumArtwork(song.artworkUri, song.title, Modifier.weight(1f).fillMaxHeight(), tileShape)
                }
            }
            3 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                AlbumArtwork(covers[0].artworkUri, covers[0].title, Modifier.weight(1f).fillMaxHeight(), tileShape)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AlbumArtwork(covers[1].artworkUri, covers[1].title, Modifier.weight(1f).fillMaxWidth(), tileShape)
                    AlbumArtwork(covers[2].artworkUri, covers[2].title, Modifier.weight(1f).fillMaxWidth(), tileShape)
                }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    AlbumArtwork(covers[0].artworkUri, covers[0].title, Modifier.weight(1f).fillMaxHeight(), tileShape)
                    AlbumArtwork(covers[1].artworkUri, covers[1].title, Modifier.weight(1f).fillMaxHeight(), tileShape)
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    AlbumArtwork(covers[2].artworkUri, covers[2].title, Modifier.weight(1f).fillMaxHeight(), tileShape)
                    AlbumArtwork(covers[3].artworkUri, covers[3].title, Modifier.weight(1f).fillMaxHeight(), tileShape)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeroLibraryCard(
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

@Composable
private fun QuickPickCarousel(
    songs: List<Song>,
    playCounts: Map<Long, Long>,
    compactLayout: Boolean,
    onPlay: (Song) -> Unit,
    onHideFromHome: (Song) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        val columnsPerPage = when {
            maxWidth >= 1_000.dp && fontScale < 1.65f -> 3
            maxWidth >= 650.dp && fontScale < 1.55f -> 2
            else -> 1
        }
        val pageWidth = (maxWidth - 52.dp).coerceAtLeast(180.dp)
        val columnWidth = (
            (pageWidth - 12.dp * (columnsPerPage - 1).toFloat()) / columnsPerPage.toFloat()
        ).coerceAtLeast(180.dp)
        val songsPerPage = columnsPerPage * 4
        val songPages = remember(songs, columnsPerPage) { songs.chunked(songsPerPage) }
        val pagerState = rememberPagerState(pageCount = { songPages.size })
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            pageSize = PageSize.Fixed(pageWidth),
            contentPadding = PaddingValues(horizontal = 20.dp),
            pageSpacing = 12.dp,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                pagerSnapDistance = PagerSnapDistance.atMost(1)
            ),
            key = { page -> "quick-picks-${songPages[page].first().id}" },
            snapPosition = SnapPosition.Start
        ) { page ->
            val columns = songPages[page].chunked(4)
            Row(
                modifier = Modifier.width(pageWidth),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                columns.forEach { column ->
                    Column(
                        modifier = Modifier.width(columnWidth),
                        verticalArrangement = Arrangement.spacedBy(if (compactLayout) 4.dp else 5.dp)
                    ) {
                        column.forEach { song ->
                            QuickPickTrackRow(
                                song = song,
                                playCount = playCounts[song.id] ?: 0L,
                                compactLayout = compactLayout,
                                onPlay = { onPlay(song) },
                                onHideFromHome = { onHideFromHome(song) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickPickTrackRow(
    song: Song,
    playCount: Long,
    compactLayout: Boolean,
    onPlay: () -> Unit,
    onHideFromHome: () -> Unit
) {
    var menuExpanded by remember(song.id) { mutableStateOf(false) }
    val actionController = LocalSongActionController.current
    val isFavorite = song.id in (actionController?.favoriteSongIds ?: emptySet())
    val isMarked = song.id in (actionController?.markedSongIds ?: emptySet())
    val isCurrentlyPlaying = actionController?.currentlyPlayingSongId == song.id
    val titleMaxLines = if (LocalDensity.current.fontScale >= 1.3f) 3 else 2
    val subtitle = buildString {
        append(song.artist)
        if (playCount > 0L) {
            append("  ·  ")
            append(compactPlayCount(playCount))
            append(if (playCount == 1L) " play" else " plays")
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (compactLayout) 64.dp else 72.dp)
            .springyCombinedClickable(
                pressedScale = 0.985f,
                onClick = {
                    if (actionController != null && actionController.markedSongIds.isNotEmpty()) {
                        actionController.onToggleMark(song)
                    } else {
                        onPlay()
                    }
                },
                onLongClick = { menuExpanded = true }
            )
            .padding(horizontal = 2.dp, vertical = if (compactLayout) 3.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(if (compactLayout) 48.dp else 56.dp)) {
            AlbumArtwork(song.artworkUri, song.title, Modifier.fillMaxSize(), RoundedCornerShape(9.dp))
            if (isMarked) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(3.dp).size(if (compactLayout) 16.dp else 18.dp).clip(CircleShape).background(TunelystColors.Primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = TunelystColors.OnPrimary, modifier = Modifier.size(if (compactLayout) 12.dp else 13.dp))
                }
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp, end = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.title,
                    color = if (isCurrentlyPlaying) TunelystColors.Lime else TunelystColors.Text,
                    fontSize = 15.sp,
                    fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.SemiBold,
                    lineHeight = 18.sp,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                SongStatusIndicator(song.id, actionController)
            }
            Text(
                text = subtitle,
                color = TunelystColors.Muted,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        IconButton(
            onClick = { menuExpanded = true },
            modifier = Modifier.size(42.dp)
        ) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More options for ${song.title}", tint = TunelystColors.Muted, modifier = Modifier.size(23.dp))
        }
    }
    if (menuExpanded) {
        SongActionDialog(
            song = song,
            isFavorite = isFavorite,
            isMarked = isMarked,
            isHiddenFromHome = false,
            onDismiss = { menuExpanded = false },
            callbacks = SongActionCallbacks(
                onPlayNow = onPlay,
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
                onToggleHomeVisibility = onHideFromHome,
                onDeleteFromDevice = actionController?.let { { it.onDeleteFromDevice(song) } }
            )
        )
    }
}

private fun compactPlayCount(count: Long): String = when {
    count >= 1_000_000L -> String.format(Locale.getDefault(), "%.1fM", count / 1_000_000.0).removeSuffix(".0M")
    count >= 1_000L -> String.format(Locale.getDefault(), "%.1fK", count / 1_000.0).removeSuffix(".0K")
    else -> count.coerceAtLeast(0L).toString()
}

@Composable
private fun MadeForYouMixDetailScreen(
    mix: MadeForYouMix,
    sectionLabel: String,
    favorites: Set<Long>,
    currentSong: Song?,
    isPlaying: Boolean,
    activeQueueSongIds: List<Long>,
    playbackHasEnded: Boolean,
    hiddenHomeSongIds: Set<Long>,
    onSetSongHiddenFromHome: (Long, Boolean) -> Unit,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onTogglePlayback: () -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onPlayLast: (Song) -> Unit
) {
    val songs = mix.songs
    val mixSongIds = remember(songs) { songs.map(Song::id) }
    val activeMixQueue = activeQueueSongIds == mixSongIds && pageContainsCurrentSong(songs, currentSong)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            ImmersiveDetailArtworkHeader(
                sectionLabel = sectionLabel,
                title = mix.title,
                subtitle = "${songs.size} songs · ${mix.description}",
                artworkSong = songs.firstOrNull(),
                artworkSongs = songs,
                onBack = onBack,
                titleAction = {
                    IconRoundButton(
                        icon = if (activeMixQueue && isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = when {
                            activeMixQueue && isPlaying -> "Pause ${mix.title}"
                            activeMixQueue && !playbackHasEnded -> "Resume ${mix.title}"
                            else -> "Play ${mix.title}"
                        },
                        onClick = {
                            if (activeMixQueue && !playbackHasEnded) onTogglePlayback()
                            else onPlay(songs, 0)
                        },
                        tint = Color.White
                    )
                }
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                SectionHeading("Tracks", modifier = Modifier.padding(top = 12.dp))
            }
        }
        items(songs, key = Song::id) { song ->
            SongRow(
                song = song,
                modifier = Modifier.padding(horizontal = 20.dp).animateItem(),
                isFavorite = song.id in favorites,
                onClick = { onPlay(songs, songs.indexOf(song)) },
                onToggleFavorite = { onToggleFavorite(song.id) },
                onAddToPlaylist = { onAddToPlaylist(song) },
                onPlayNext = { onPlayNext(song) },
                onPlayLast = { onPlayLast(song) },
                isHiddenFromHome = song.id in hiddenHomeSongIds,
                onToggleHomeVisibility = { onSetSongHiddenFromHome(song.id, song.id !in hiddenHomeSongIds) }
            )
        }
    }
}

@Composable
private fun MadeForYouCard(
    mix: MadeForYouMix,
    onOpen: () -> Unit,
    onHideFromHome: () -> Unit,
    width: Dp = 176.dp,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(19.dp)
    var menuExpanded by remember(mix.title) { mutableStateOf(false) }
    Box(modifier = modifier.width(width)) {
        Column(
            modifier = Modifier.fillMaxWidth().springyCombinedClickable(
                onClick = onOpen,
                onLongClick = { menuExpanded = true }
            )
        ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(shape),
            contentAlignment = Alignment.BottomEnd
        ) {
            MixArtworkCollage(mix.songs, Modifier.fillMaxSize(), shape)
            Box(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x88000000))))
            )
            Surface(
                modifier = Modifier.padding(11.dp).size(42.dp).springyClickable(onClick = onOpen),
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 7.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ArrowForward, contentDescription = "Open ${mix.title}", tint = Color(0xFF141218), modifier = Modifier.size(22.dp))
                }
            }
        }
        Text(mix.title, color = TunelystColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Hide card from Home") },
                leadingIcon = { Icon(Icons.Rounded.Close, contentDescription = null) },
                onClick = { menuExpanded = false; onHideFromHome() }
            )
        }
    }
}

@Composable
private fun LoadingCard(message: String = "Looking for music on this device…") {
    val motionEnabled = LocalTunelystMotionEnabled.current
    Surface(color = TunelystColors.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (motionEnabled) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = TunelystColors.Primary, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = TunelystColors.Primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(message, color = TunelystColors.Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun EmptyLibraryCard(onRescan: () -> Unit, errorMessage: String? = null) {
    val hasError = !errorMessage.isNullOrBlank()
    val motionEnabled = LocalTunelystMotionEnabled.current
    Surface(color = TunelystColors.Surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.Start) {
            ExpressiveToggleIcon(
                selected = hasError,
                selectedIcon = Icons.Rounded.Refresh,
                unselectedIcon = Icons.Rounded.MusicNote,
                contentDescription = if (hasError) "Rescan library" else "Music library",
                tint = if (hasError) TunelystColors.Rose else TunelystColors.Primary,
                size = 30.dp,
                label = "library-empty-state-icon"
            )
            AnimatedContent(
                targetState = hasError,
                transitionSpec = {
                    if (motionEnabled) fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                    else EnterTransition.None togetherWith ExitTransition.None
                },
                label = "library-empty-state-title"
            ) { error ->
                Text(
                    if (error) "Couldn't scan for songs" else "No tracks found just yet",
                    color = TunelystColors.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 9.dp)
                )
            }
            Text(
                errorMessage ?: "Add audio files to your device, then scan again. Tunelyst plays music stored locally — no streaming or account needed.",
                color = TunelystColors.Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                if (hasError) "Try scanning again  ↗" else "Scan again  ↗",
                color = TunelystColors.Primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.springyClickable(onClick = onRescan).padding(top = 14.dp)
            )
        }
    }
}

@Composable
fun SearchScreen(
    state: MusicUiState,
    nowPlayingVisible: Boolean,
    onPlay: (List<Song>, Int) -> Unit,
    onPlaySelectedSong: (Song) -> Unit,
    onRecordSearch: (Long) -> Unit,
    onClearRecentlySearched: () -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onSetSongHiddenFromHome: (Long, Boolean) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onPlayLast: (Song) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchScope by rememberSaveable { mutableStateOf("Songs") }
    val recentSearchListState = rememberLazyListState()
    val recentHeaderVisible by remember {
        derivedStateOf {
            recentSearchListState.firstVisibleItemIndex == 0 && recentSearchListState.firstVisibleItemScrollOffset == 0
        }
    }
    var playerWasOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val expressiveMotionEnabled = LocalTunelystMotionEnabled.current
    val compactSearchLayout = isTunelystCompactViewport()
    val searchFieldMultiline = LocalDensity.current.fontScale >= 1.35f
    LaunchedEffect(nowPlayingVisible) {
        if (nowPlayingVisible) {
            playerWasOpen = true
            // Release the Search text field itself; clearing only the Activity focus can be
            // undone when Android restores window focus after the fullscreen dialog closes.
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        } else if (playerWasOpen) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            // Dialog window teardown can restore the old IME connection one frame later.
            // Hide it again after that hand-off, without re-requesting Search focus.
            delay(160L)
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            playerWasOpen = false
        }
    }
    val normalizedQuery = query.trim().lowercase()
    var results by remember { mutableStateOf(emptyList<Song>()) }
    var searchUpdating by remember { mutableStateOf(false) }
    LaunchedEffect(state.songs, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            results = emptyList()
            searchUpdating = false
        } else {
            searchUpdating = true
            try {
                delay(120L)
                results = emptyList()
                results = withContext(Dispatchers.Default) {
                    state.songs.filter { song ->
                        song.title.contains(normalizedQuery, ignoreCase = true) ||
                            song.artist.contains(normalizedQuery, ignoreCase = true) ||
                            song.albumArtist.contains(normalizedQuery, ignoreCase = true) ||
                            song.album.contains(normalizedQuery, ignoreCase = true) ||
                            song.genre.contains(normalizedQuery, ignoreCase = true) ||
                            song.year.toString().contains(normalizedQuery) ||
                            song.folderPath.contains(normalizedQuery, ignoreCase = true)
                    }
                }
            } finally {
                searchUpdating = false
            }
        }
    }
    val filteredResults = results
    val artistResults = remember(filteredResults) {
        filteredResults.groupBy(Song::artist).map { (artist, tracks) ->
            ArtistGroup(artist, tracks.sortedBy { it.title.lowercase() })
        }.sortedBy { it.artist.lowercase() }
    }
    val albumResults = remember(filteredResults) {
        filteredResults.groupBy { Triple(it.album, it.albumArtist, it.year) }.map { (key, tracks) ->
            AlbumGroup(key.first, key.second, tracks.sortedWith(compareBy<Song> { it.discNumber }.thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }.thenBy { it.title.lowercase() }), key.third)
        }.sortedBy { it.album.lowercase() }
    }
    val playlistResults = remember(state.playlists, normalizedQuery) {
        state.playlists.filter { normalizedQuery.isNotBlank() && it.name.contains(normalizedQuery, ignoreCase = true) }
    }
    val suggestions = remember(results, normalizedQuery) {
        if (normalizedQuery.length < 2) emptyList() else (results.asSequence()
            .flatMap { sequenceOf(it.artist, it.albumArtist, it.album, it.genre) }
            .filter { it.isNotBlank() && it.contains(normalizedQuery, ignoreCase = true) }
            .distinct().take(8).toList())
    }
    val recentlySearched = remember(state.songs, state.recentlySearchedIds) {
        val songById = state.songs.associateBy(Song::id)
        state.recentlySearchedIds.mapNotNull(songById::get)
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = tunelystHorizontalContentInset())) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth()
                .padding(top = if (compactSearchLayout) 8.dp else 10.dp)
                .height(if (searchFieldMultiline) 68.dp else 52.dp),
            singleLine = !searchFieldMultiline,
            maxLines = if (searchFieldMultiline) 2 else 1,
            shape = CircleShape,
            trailingIcon = {
                AnimatedVisibility(
                    visible = query.isNotBlank(),
                    enter = if (expressiveMotionEnabled) fadeIn(tween(120)) + scaleIn(animationSpec = spring(dampingRatio = 0.55f, stiffness = 520f)) else EnterTransition.None,
                    exit = if (expressiveMotionEnabled) fadeOut(tween(90)) + scaleOut(animationSpec = tween(90)) else ExitTransition.None
                ) {
                    IconButton(onClick = { query = "" }) {
                        ExpressiveStateIcon(Icons.Rounded.Close, "Clear search", TunelystColors.Muted, size = 19.dp, label = "search-clear-icon")
                    }
                }
            },
            placeholder = {
                Text(
                    "What do you want to listen to?",
                    color = TunelystColors.Subtle,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    maxLines = if (searchFieldMultiline) 2 else 1,
                    softWrap = searchFieldMultiline,
                    overflow = TextOverflow.Clip
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = TunelystColors.Surface,
                unfocusedContainerColor = TunelystColors.Surface,
                focusedBorderColor = TunelystColors.Primary.copy(alpha = 0.65f),
                unfocusedBorderColor = TunelystColors.Border,
                focusedTextColor = TunelystColors.Text,
                unfocusedTextColor = TunelystColors.Text,
                cursorColor = TunelystColors.Primary
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
        if (!state.isLibraryReady) {
            LoadingCard("Loading saved library…")
        } else if (normalizedQuery.isBlank()) {
            LaunchedEffect(recentlySearched) {
                if (recentlySearched.isEmpty()) recentSearchListState.scrollToItem(0)
            }
            AnimatedVisibility(
                visible = recentHeaderVisible,
                enter = if (expressiveMotionEnabled) fadeIn(tween(100)) + expandVertically() else EnterTransition.None,
                exit = if (expressiveMotionEnabled) fadeOut(tween(80)) + shrinkVertically() else ExitTransition.None,
                label = "recents-header"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = if (compactSearchLayout) 7.dp else 10.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Recents", color = TunelystColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(
                        enabled = recentlySearched.isNotEmpty(),
                        onClick = onClearRecentlySearched,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text("Clear", color = if (recentlySearched.isNotEmpty()) TunelystColors.Primary else TunelystColors.Subtle, fontSize = 13.sp) }
                }
            }
            if (recentlySearched.isEmpty()) {
                Text("No recent searches yet.", color = TunelystColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            } else {
                LazyColumn(state = recentSearchListState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(recentlySearched, key = Song::id) { song ->
                        SongRow(
                            song = song,
                            modifier = Modifier.animateItem(),
                            isFavorite = song.id in state.favorites,
                            onClick = {
                                onRecordSearch(song.id)
                                onPlaySelectedSong(song)
                            },
                            onToggleFavorite = { onToggleFavorite(song.id) },
                            onAddToPlaylist = { onAddToPlaylist(song) },
                            onPlayNext = { onPlayNext(song) },
                            onPlayLast = { onPlayLast(song) },
                            isHiddenFromHome = song.id in state.hiddenHomeSongIds,
                            onToggleHomeVisibility = { onSetSongHiddenFromHome(song.id, song.id !in state.hiddenHomeSongIds) }
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = if (compactSearchLayout) 10.dp else 15.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Songs", "Artists", "Albums", "Playlists").forEach { scope ->
                    val selected = searchScope == scope
                    FilterChip(
                        selected = selected,
                        onClick = { searchScope = scope },
                        label = { Text(scope, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        leadingIcon = {
                            AnimatedVisibility(
                                visible = selected,
                                enter = if (expressiveMotionEnabled) scaleIn(animationSpec = spring(dampingRatio = 0.46f, stiffness = 500f)) + fadeIn(tween(90)) else EnterTransition.None,
                                exit = if (expressiveMotionEnabled) scaleOut(animationSpec = tween(85)) + fadeOut(tween(70)) else ExitTransition.None
                            ) {
                                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(17.dp))
                            }
                        }
                    )
                }
            }
            if (suggestions.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = if (compactSearchLayout) 6.dp else 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    suggestions.forEach { suggestion ->
                        Surface(color = TunelystColors.Surface, shape = CircleShape, modifier = Modifier.springyClickable { query = suggestion }) {
                            Text(suggestion, color = TunelystColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp), maxLines = 1)
                        }
                    }
                }
            }
            val resultCount = when (searchScope) {
                "Artists" -> artistResults.size
                "Albums" -> albumResults.size
                "Playlists" -> playlistResults.size
                else -> filteredResults.size
            }
            AnimatedContent(
                targetState = resultCount,
                modifier = Modifier.padding(top = if (compactSearchLayout) 3.dp else 6.dp, bottom = if (compactSearchLayout) 3.dp else 6.dp),
                transitionSpec = {
                    if (expressiveMotionEnabled) {
                        (fadeIn(tween(110)) + scaleIn(initialScale = 0.96f, animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f))) togetherWith
                            fadeOut(tween(80))
                    } else EnterTransition.None togetherWith ExitTransition.None
                },
                label = "search-result-count"
            ) { count ->
                Text(
                    "$count ${if (count == 1) "result" else "results"}",
                    color = TunelystColors.Muted,
                    fontSize = 13.sp
                )
            }
            AnimatedVisibility(
                visible = resultCount == 0,
                enter = if (expressiveMotionEnabled) fadeIn(tween(130)) + scaleIn(initialScale = 0.97f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 430f)) else EnterTransition.None,
                exit = if (expressiveMotionEnabled) fadeOut(tween(80)) else ExitTransition.None
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (searchUpdating && expressiveMotionEnabled) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = TunelystColors.Primary, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = TunelystColors.Subtle, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        if (searchUpdating) "Searching your library…" else "No matches. Try another title, artist or album.",
                        color = TunelystColors.Muted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
            if (resultCount > 0) {
                when (searchScope) {
                    "Playlists" -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(playlistResults, key = Playlist::id) { playlist ->
                            PlaylistRow(
                                playlist = playlist,
                                allSongs = state.songs,
                                onClick = { onPlay(playlist.songIds.mapNotNull { id -> state.songs.firstOrNull { it.id == id } }, 0) },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                    "Artists" -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(artistResults, key = ArtistGroup::artist) { artist ->
                            AlbumOrArtistRow(
                                title = artist.artist,
                                subtitle = "${artist.songs.size} ${if (artist.songs.size == 1) "song" else "songs"}",
                                songs = artist.songs,
                                onClick = { onPlay(artist.songs, 0) },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                    "Albums" -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(albumResults, key = { "${it.album}-${it.artist}" }) { album ->
                            AlbumOrArtistRow(
                                title = album.album,
                                subtitle = "${album.artist} · ${album.songs.size} ${if (album.songs.size == 1) "song" else "songs"}",
                                songs = album.songs,
                                onClick = { onPlay(album.songs, 0) },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(filteredResults, key = Song::id) { song ->
                            SongRow(
                                song = song,
                                modifier = Modifier.animateItem(),
                                isFavorite = song.id in state.favorites,
                                onClick = {
                                    onRecordSearch(song.id)
                                    onPlaySelectedSong(song)
                                },
                                onToggleFavorite = { onToggleFavorite(song.id) },
                                onAddToPlaylist = { onAddToPlaylist(song) },
                                onPlayNext = { onPlayNext(song) },
                                onPlayLast = { onPlayLast(song) },
                                isHiddenFromHome = song.id in state.hiddenHomeSongIds,
                                onToggleHomeVisibility = { onSetSongHiddenFromHome(song.id, song.id !in state.hiddenHomeSongIds) }
                            )
                        }
                    }
                }
            }
        }
    }
}

data class AlbumGroup(val album: String, val artist: String, val songs: List<Song>, val year: Int = 0)

internal data class AlbumTrackMetadata(
    val id: Long,
    val album: String,
    val artist: String,
    val albumArtist: String,
    val isCompilation: Boolean = false
)

internal fun albumGroupTrackIds(songs: List<AlbumTrackMetadata>, songId: Long): Set<Long> {
    val selected = songs.firstOrNull { it.id == songId } ?: return emptySet()
    val selectedAlbum = normalizeAlbumGroupKey(selected.album)
    val sameAlbum = songs.filter { normalizeAlbumGroupKey(it.album) == selectedAlbum }
    val selectedAlbumArtist = normalizeAlbumGroupKey(selected.albumArtist)
    val selectedArtist = normalizeAlbumGroupKey(selected.artist)
    val sameAlbumArtist = sameAlbum.filter { normalizeAlbumGroupKey(it.albumArtist) == selectedAlbumArtist }
    val sameTrackArtist = sameAlbum.filter { normalizeAlbumGroupKey(it.artist) == selectedArtist }
    val isVariousArtistRelease = selectedAlbumArtist in setOf("various artists", "various")
    val matchingUnknownArtistTags = sameAlbum.filter {
        normalizeAlbumGroupKey(it.albumArtist) in setOf("", "unknown", "unknown artist", "unknown album artist") &&
            normalizeAlbumGroupKey(it.artist) == selectedArtist
    }
    val albumTracks = when {
        selected.isCompilation || isVariousArtistRelease -> sameAlbum
        sameAlbumArtist.size > 1 -> sameAlbumArtist + matchingUnknownArtistTags
        sameTrackArtist.size > 1 -> sameTrackArtist
        sameAlbumArtist.isNotEmpty() -> sameAlbumArtist
        sameTrackArtist.isNotEmpty() -> sameTrackArtist
        else -> listOf(selected)
    }
    return albumTracks.mapTo(LinkedHashSet(), AlbumTrackMetadata::id)
}

private fun normalizeAlbumGroupKey(value: String): String =
    value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

fun albumGroupFor(songs: List<Song>, songId: Long): AlbumGroup? {
    val selected = songs.firstOrNull { it.id == songId } ?: return null
    val selectedTrackIds = albumGroupTrackIds(
        songs.map { AlbumTrackMetadata(it.id, it.album, it.artist, it.albumArtist, it.isCompilation) },
        songId
    )
    val tracks = songs.filter { it.id in selectedTrackIds }
        .sortedWith(compareBy<Song> { it.discNumber }.thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }.thenBy { it.title.lowercase() })
    return AlbumGroup(
        selected.album,
        selected.albumArtist.ifBlank { selected.artist },
        tracks,
        tracks.maxOfOrNull(Song::year) ?: selected.year
    )
}

private data class ArtistGroup(val artist: String, val songs: List<Song>)

private data class LibraryGroups(
    val albums: List<AlbumGroup>,
    val artists: List<ArtistGroup>,
    val folders: Map<String, List<Song>>
)

private fun buildLibraryGroups(songs: List<Song>): LibraryGroups {
    val albums = songs.groupBy { it.album to it.albumArtist }.map { (key, tracks) ->
        AlbumGroup(
            key.first,
            key.second,
            tracks.sortedWith(compareBy<Song> { it.discNumber }
                .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                .thenBy { it.title.lowercase() }),
            tracks.maxOfOrNull(Song::year) ?: 0
        )
    }.sortedBy { it.album.lowercase() }
    val artists = songs.groupBy(Song::artist).map { (artist, tracks) ->
        ArtistGroup(artist, tracks.sortedBy { it.title.lowercase() })
    }.sortedBy { it.artist.lowercase() }
    val folders = songs.groupBy { it.folderPath.ifBlank { "Device root" } }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    return LibraryGroups(albums, artists, folders)
}

private fun sortLibrarySongs(songs: List<Song>, sortMode: String): List<Song> = when (sortMode) {
    "Recently added" -> songs.sortedByDescending(Song::dateAddedSeconds)
    "Artist A–Z" -> songs.sortedBy { it.artist.lowercase() + it.title.lowercase() }
    "Album A–Z" -> songs.sortedBy { it.albumArtist.lowercase() + it.album.lowercase() + it.discNumber + it.trackNumber }
    "Duration" -> songs.sortedByDescending(Song::durationMs)
    else -> songs.sortedBy { it.title.lowercase() }
}

@Composable
fun LibraryScreen(
    state: MusicUiState,
    onOpenSettings: () -> Unit,
    selectedSection: Int,
    onSelectedSectionChange: (Int) -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onSetSongHiddenFromHome: (Long, Boolean) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onAddSelectedToPlaylist: (List<Song>) -> Unit,
    onDeleteDeviceAudio: (String, List<Song>) -> Unit,
    onConfirmDeleteDeviceAudio: (String, List<Song>) -> Unit,
    onPlayNext: (Song) -> Unit,
    onPlayLast: (Song) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (Song) -> Unit,
    onOpenFolder: (String) -> Unit,
    onEditSong: (Song) -> Unit,
    onApplyTitleFixes: (List<TitleFixRequest>) -> Unit,
    onPlaySmartPlaylist: (String, String) -> Unit,
    onSaveSmartPlaylist: (String, String, String) -> Unit,
    onDeleteSmartPlaylist: (String) -> Unit,
    onPlaySavedQueue: (String) -> Unit,
    onDeleteSavedQueue: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onRescan: () -> Unit
) {
    var libraryViewOptionsOpen by remember { mutableStateOf(false) }
    var sortMode by rememberSaveable { mutableStateOf("Title A–Z") }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val songsListState = rememberLazyListState()
    val songToolbarAtTop by remember {
        derivedStateOf {
            songsListState.firstVisibleItemIndex == 0 && songsListState.firstVisibleItemScrollOffset == 0
        }
    }
    val actionController = LocalSongActionController.current
    val compactLibraryLayout = isTunelystCompactViewport()
    val libraryContentInset = tunelystHorizontalContentInset()
    var smartEditorOpen by rememberSaveable { mutableStateOf(false) }
    var cleanupDialogOpen by rememberSaveable { mutableStateOf(false) }
    var cleanupReport by remember { mutableStateOf(LibraryCleanupAnalysis()) }
    val labels = listOf("Songs", "Albums", "Artists", "Folders", "Playlists", "Smart Lists", "Saved Queues", "Liked")
    var libraryGroups by remember { mutableStateOf<LibraryGroups?>(null) }
    LaunchedEffect(state.songs) {
        libraryGroups = withContext(Dispatchers.Default) { buildLibraryGroups(state.songs) }
    }
    val albums = libraryGroups?.albums.orEmpty()
    val artists = libraryGroups?.artists.orEmpty()
    val folderGroups = libraryGroups?.folders.orEmpty()
    var sortedSongs by remember { mutableStateOf(emptyList<Song>()) }
    LaunchedEffect(state.songs, sortMode) {
        sortedSongs = withContext(Dispatchers.Default) { sortLibrarySongs(state.songs, sortMode) }
    }
    var libraryFilter by rememberSaveable { mutableStateOf("All songs") }
    val filterOptions = listOf("All songs", "Never played", "Hidden from Home")
    val filteredSongs = remember(sortedSongs, state.playCounts, state.hiddenHomeSongIds, libraryFilter) {
        when (libraryFilter) {
            "Never played" -> sortedSongs.filter { (state.playCounts[it.id] ?: 0L) <= 0L }
            "Hidden from Home" -> sortedSongs.filter { it.id in state.hiddenHomeSongIds }
            else -> sortedSongs
        }
    }
    val favoriteSongs = sortedSongs.filter { it.id in state.favorites }
    LaunchedEffect(state.songs) {
        cleanupReport = withContext(Dispatchers.Default) { analyzeLibraryCleanup(state.songs) }
    }
    BackHandler(enabled = smartEditorOpen || cleanupDialogOpen) {
        if (cleanupDialogOpen) cleanupDialogOpen = false else smartEditorOpen = false
    }
    val isLikedSection = selectedSection == 7
    val visibleCount = if (isLikedSection) favoriteSongs.size else state.songs.size
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = if (compactLibraryLayout) 54.dp else 58.dp).padding(horizontal = libraryContentInset),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLikedSection) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Liked", color = TunelystColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "$visibleCount ${if (visibleCount == 1) "song" else "songs"}",
                        color = TunelystColors.Subtle,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Column(Modifier.weight(1f)) {
                    Text("Library", color = TunelystColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("$visibleCount ${if (visibleCount == 1) "track" else "tracks"}", color = TunelystColors.Subtle, fontSize = 11.sp)
                }
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(horizontal = 10.dp).size(20.dp), color = TunelystColors.Primary, strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRescan, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Rescan device", tint = TunelystColors.Muted)
                    }
                }
                IconButton(onClick = onCreatePlaylist, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.Add, contentDescription = "Create playlist", tint = TunelystColors.Muted)
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = TunelystColors.Muted)
                }
            }
        }
        Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = libraryContentInset)) {
            if (isLikedSection) {
                Spacer(Modifier.height(8.dp))
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(top = if (compactLibraryLayout) 7.dp else 10.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    labels.forEachIndexed { index, label ->
                        val selected = selectedSection == index
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectedSectionChange(index) },
                            label = { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(17.dp)) }
                            } else null
                        )
                    }
                }
                Spacer(Modifier.height(if (compactLibraryLayout) 8.dp else 13.dp))
            }
            when (selectedSection) {
                0 -> {
                    if (!state.isLibraryReady) LoadingCard("Loading saved library…")
                    else if (state.isLoading && state.songs.isEmpty()) LoadingCard()
                    else if (state.songs.isNotEmpty() && sortedSongs.isEmpty()) LoadingCard("Preparing your songs…")
                    else if (filteredSongs.isEmpty() && libraryFilter != "All songs") {
                        Surface(color = TunelystColors.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No tracks match $libraryFilter.", color = TunelystColors.Text, fontSize = 14.sp)
                                TextButton(onClick = { libraryFilter = "All songs"; selectedIds = emptySet() }) { Text("Clear filter", color = TunelystColors.Primary) }
                            }
                        }
                    }
                    else if (filteredSongs.isEmpty()) EmptyLibraryCard(onRescan, state.errorMessage) else {
                        AnimatedVisibility(
                            visible = songToolbarAtTop || selectionMode,
                            enter = if (LocalTunelystMotionEnabled.current) fadeIn(tween(100)) + expandVertically() else EnterTransition.None,
                            exit = if (LocalTunelystMotionEnabled.current) fadeOut(tween(80)) + shrinkVertically() else ExitTransition.None,
                            label = "library-song-toolbar"
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (songToolbarAtTop && (cleanupReport.suggestions.isNotEmpty() || cleanupReport.likelyDuplicates.isNotEmpty())) {
                                    TextButton(
                                        onClick = { cleanupDialogOpen = true },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                    ) {
                                        val findingCount = cleanupReport.suggestions.size + cleanupReport.likelyDuplicates.size
                                        Text("Cleanup review · $findingCount", color = TunelystColors.Primary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                                TextButton(onClick = { selectionMode = !selectionMode; selectedIds = emptySet() }) {
                                    Text(if (selectionMode) "Done" else "Select", color = TunelystColors.Primary, fontSize = 12.sp)
                                }
                                AnimatedVisibility(visible = songToolbarAtTop) {
                                    TextButton(
                                        onClick = { libraryViewOptionsOpen = true },
                                        modifier = Modifier.semantics {
                                            contentDescription = "Song view options. Sort: $sortMode. Filter: $libraryFilter"
                                        }
                                    ) {
                                        Text(if (libraryFilter == "All songs") "Sort" else "Filter", color = TunelystColors.Primary, fontSize = 12.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                        if (libraryFilter != "All songs") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Showing: $libraryFilter", color = TunelystColors.Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                TextButton(onClick = { libraryFilter = "All songs"; selectedIds = emptySet() }) {
                                    Text("Clear", color = TunelystColors.Primary, fontSize = 12.sp)
                                }
                            }
                        }
                        if (selectionMode) {
                            val selectedTracks = filteredSongs.filter { it.id in selectedIds }
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${selectedIds.size} selected", color = TunelystColors.Muted, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { selectedIds = if (selectedIds.size == filteredSongs.size) emptySet() else filteredSongs.map(Song::id).toSet() }) { Text("Select all", color = TunelystColors.Primary) }
                                    TextButton(enabled = selectedTracks.isNotEmpty(), onClick = { if (selectedTracks.isNotEmpty()) onPlay(selectedTracks, 0) }) { Text("Play", color = TunelystColors.Primary) }
                                    TextButton(enabled = selectedTracks.isNotEmpty(), onClick = { actionController?.onPlayNextTracks(selectedTracks) }) { Text("Next", color = TunelystColors.Primary) }
                                }
                                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(enabled = selectedTracks.isNotEmpty(), onClick = {
                                        selectedTracks.filter { it.id !in state.favorites }.forEach { onToggleFavorite(it.id) }
                                    }) { Text("Like", color = TunelystColors.Primary) }
                                    TextButton(enabled = selectedTracks.isNotEmpty(), onClick = { onAddSelectedToPlaylist(selectedTracks) }) { Text("Add to playlist", color = TunelystColors.Primary) }
                                    TextButton(enabled = selectedTracks.isNotEmpty(), onClick = { actionController?.onAddTracksToQueue(selectedTracks) }) { Text("Add to queue", color = TunelystColors.Primary) }
                                    TextButton(enabled = selectedTracks.isNotEmpty(), onClick = { actionController?.onShareTracks(selectedTracks) }) { Text("Share", color = TunelystColors.Primary) }
                                    TextButton(enabled = selectedTracks.isNotEmpty(), onClick = {
                                        onDeleteDeviceAudio("the selected ${selectedTracks.size} ${if (selectedTracks.size == 1) "track" else "tracks"}", selectedTracks)
                                    }) { Text("Delete audio", color = TunelystColors.Rose) }
                                }
                            }
                        }
                        LazyColumn(state = songsListState, contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(filteredSongs, key = Song::id) { song ->
                                SongRow(
                                    song = song,
                                    modifier = Modifier.animateItem(),
                                    isFavorite = song.id in state.favorites,
                                    isSelected = song.id in selectedIds,
                                    onToggleSelection = if (selectionMode) { { selectedIds = if (song.id in selectedIds) selectedIds - song.id else selectedIds + song.id } } else null,
                                    onClick = { if (selectionMode) selectedIds = if (song.id in selectedIds) selectedIds - song.id else selectedIds + song.id else onPlay(filteredSongs, filteredSongs.indexOf(song)) },
                                    onToggleFavorite = { onToggleFavorite(song.id) },
                                    onAddToPlaylist = { onAddToPlaylist(song) },
                                    onPlayNext = { onPlayNext(song) },
                                    onPlayLast = { onPlayLast(song) },
                                    onEditMetadata = { onEditSong(song) },
                                    isHiddenFromHome = song.id in state.hiddenHomeSongIds,
                                    onToggleHomeVisibility = { onSetSongHiddenFromHome(song.id, song.id !in state.hiddenHomeSongIds) }
                                )
                            }
                        }
                    }
                }
                1 -> if (!state.isLibraryReady) LoadingCard("Loading saved library…") else if (state.isLoading && state.songs.isEmpty()) LoadingCard() else if (libraryGroups == null) LoadingCard("Preparing albums…") else if (albums.isEmpty()) EmptyLibraryCard(onRescan, state.errorMessage) else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(albums, key = { "${it.album}-${it.artist}-${it.year}" }) { album ->
                        AlbumOrArtistRow(
                            title = album.album,
                            subtitle = "${album.artist}${if (album.year > 0) " · ${album.year}" else ""} · ${album.songs.size} tracks",
                            songs = album.songs,
                            onClick = { album.songs.firstOrNull()?.let(onOpenAlbum) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
                2 -> if (!state.isLibraryReady) LoadingCard("Loading saved library…") else if (state.isLoading && state.songs.isEmpty()) LoadingCard() else if (libraryGroups == null) LoadingCard("Preparing artists…") else if (artists.isEmpty()) EmptyLibraryCard(onRescan, state.errorMessage) else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(artists, key = ArtistGroup::artist) { artist ->
                        AlbumOrArtistRow(
                            title = artist.artist,
                            subtitle = "${artist.songs.size} tracks",
                            songs = artist.songs,
                            onClick = { onOpenArtist(artist.artist) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
                3 -> if (!state.isLibraryReady) LoadingCard("Loading saved library…") else if (state.isLoading && state.songs.isEmpty()) LoadingCard() else if (libraryGroups == null) LoadingCard("Preparing folders…") else if (folderGroups.isEmpty()) EmptyLibraryCard(onRescan, state.errorMessage) else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(folderGroups.toList(), key = { it.first }) { (folder, songs) ->
                        val folderName = folder.substringAfterLast('/').ifBlank { folder }
                        val parentPath = folder.substringBeforeLast('/', "")
                        AlbumOrArtistRow(
                            title = folderName,
                            subtitle = "${songs.size} ${if (songs.size == 1) "track" else "tracks"}${if (parentPath.isBlank()) " · folder" else " · $parentPath"}",
                            songs = songs,
                            titleMaxLines = 1,
                            actionDescription = "Open folder $folderName",
                            onClick = { onOpenFolder(folder) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
                4 -> if (state.playlists.isEmpty()) PlaylistEmptyState(onCreatePlaylist) else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Text("MADE BY YOU", color = TunelystColors.Subtle, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp) }
                    items(state.playlists, key = Playlist::id) { playlist ->
                        val songs = playlist.songIds.mapNotNull { id -> state.songs.firstOrNull { it.id == id } }
                        PlaylistRow(playlist, state.songs, onClick = { onOpenPlaylist(playlist) }, modifier = Modifier.animateItem())
                    }
                }
                5 -> {
                    val predefined = listOf("recently_added" to "Recently added", "most_played" to "Most played", "never_played" to "Never played")
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Offline smart lists", color = TunelystColors.Text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { smartEditorOpen = true }) { Text("Create rule", color = TunelystColors.Primary) }
                            }
                        }
                        items(predefined, key = { it.first }) { (kind, title) ->
                            SmartPlaylistRow(title, "Local rule · updates from your library", onClick = { onPlaySmartPlaylist(kind, "") }, modifier = Modifier.animateItem())
                        }
                        items(state.smartPlaylists, key = SmartPlaylist::id) { smart ->
                            SmartPlaylistRow(smart.name, "${smart.kind.replace('_', ' ')}${if (smart.value.isNotBlank()) " · ${smart.value}" else ""}",
                                onClick = { onPlaySmartPlaylist(smart.kind, smart.value) }, onDelete = { onDeleteSmartPlaylist(smart.id) },
                                modifier = Modifier.animateItem())
                        }
                    }
                }
                6 -> if (state.savedQueues.isEmpty()) {
                    Text("Save the current queue from Settings or Now Playing to keep it here.", color = TunelystColors.Muted, fontSize = 13.sp)
                } else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.savedQueues, key = SavedQueue::id) { saved ->
                        SmartPlaylistRow(saved.name, "${saved.songIds.size} tracks · saved queue", onClick = { onPlaySavedQueue(saved.id) }, onDelete = { onDeleteSavedQueue(saved.id) }, modifier = Modifier.animateItem())
                    }
                }
                else -> if (favoriteSongs.isEmpty()) {
                    Surface(color = TunelystColors.Surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text("Your liked tracks live here.", color = TunelystColors.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Tap the heart on any song to save it to this list.", color = TunelystColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp))
                        }
                    }
                } else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(favoriteSongs, key = Song::id) { song ->
                        SongRow(song = song, modifier = Modifier.animateItem(), isFavorite = true, onClick = { onPlay(favoriteSongs, favoriteSongs.indexOf(song)) },
                            onToggleFavorite = { onToggleFavorite(song.id) }, onAddToPlaylist = { onAddToPlaylist(song) },
                            onPlayNext = { onPlayNext(song) }, onPlayLast = { onPlayLast(song) }, onEditMetadata = { onEditSong(song) },
                            isHiddenFromHome = song.id in state.hiddenHomeSongIds,
                            onToggleHomeVisibility = { onSetSongHiddenFromHome(song.id, song.id !in state.hiddenHomeSongIds) })
                    }
                }
            }
        }
    }
    if (libraryViewOptionsOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { libraryViewOptionsOpen = false },
            title = { Text("Song view", color = TunelystColors.Text, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text("SORT BY", color = TunelystColors.Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    listOf("Title A–Z", "Artist A–Z", "Album A–Z", "Recently added", "Duration").forEach { option ->
                        TextButton(onClick = { sortMode = option }, modifier = Modifier.fillMaxWidth()) {
                            Text(option, color = TunelystColors.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (sortMode == option) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = TunelystColors.Primary, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("FILTER", color = TunelystColors.Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    filterOptions.forEach { option ->
                        TextButton(
                            onClick = { libraryFilter = option; selectedIds = emptySet() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(option, color = TunelystColors.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (libraryFilter == option) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = TunelystColors.Primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { libraryViewOptionsOpen = false }) { Text("Done", color = TunelystColors.Primary) } },
            containerColor = TunelystColors.Surface,
            shape = RoundedCornerShape(24.dp)
        )
    }
    if (smartEditorOpen) SmartPlaylistEditorDialog(onDismiss = { smartEditorOpen = false }, onSave = { name, kind, value ->
        onSaveSmartPlaylist(name, kind, value)
        smartEditorOpen = false
    })
    if (cleanupDialogOpen) {
        LibraryCleanupDialog(
            analysis = cleanupReport,
            songs = state.songs,
            onApplyTitleFixes = { fixes ->
                cleanupDialogOpen = false
                if (fixes.isNotEmpty()) onApplyTitleFixes(fixes)
            },
            onDeleteDuplicates = { duplicateSongs ->
                cleanupDialogOpen = false
                if (duplicateSongs.isNotEmpty()) {
                    onConfirmDeleteDeviceAudio(
                        "the selected ${duplicateSongs.size} possible duplicate ${if (duplicateSongs.size == 1) "track" else "tracks"}",
                        duplicateSongs
                    )
                }
            },
            onDismiss = { cleanupDialogOpen = false }
        )
    }
}

@Composable
private fun LibraryCleanupDialog(
    analysis: LibraryCleanupAnalysis,
    songs: List<Song>,
    onApplyTitleFixes: (List<TitleFixRequest>) -> Unit,
    onDeleteDuplicates: (List<Song>) -> Unit,
    onDismiss: () -> Unit
) {
    val songsById = remember(songs) { songs.associateBy(Song::id) }
    val suggestions = remember(analysis, songsById) {
        analysis.suggestions.filter { it.songId in songsById }
    }
    val resolvedDuplicateAnalysisGroups = remember(analysis, songsById) {
        resolveCurrentDuplicateGroups(analysis.likelyDuplicates, songsById.keys)
    }
    val duplicateGroups = remember(resolvedDuplicateAnalysisGroups, songsById) {
        resolvedDuplicateAnalysisGroups.mapNotNull { group ->
            val groupSongs = group.songIds.mapNotNull(songsById::get)
            if (groupSongs.size >= 2) group to groupSongs else null
        }
    }
    val allDuplicateIds = remember(duplicateGroups) {
        duplicateGroups.flatMap { it.second }.map(Song::id).toSet()
    }
    var selectedIds by remember(analysis) { mutableStateOf(emptySet<Long>()) }
    var selectedDuplicateIds by remember(analysis, allDuplicateIds) { mutableStateOf(emptySet<Long>()) }
    var selectedTab by remember(analysis) {
        mutableStateOf(if (suggestions.isEmpty() && duplicateGroups.isNotEmpty()) 1 else 0)
    }
    var confirmingTitleFixes by remember(analysis) { mutableStateOf(false) }
    var confirmingDuplicateDelete by remember(analysis, allDuplicateIds) { mutableStateOf(false) }
    val selectedSuggestions = suggestions.filter { it.songId in selectedIds }
    val selectedFixes = remember(selectedSuggestions, songs) { planTitleFixes(selectedSuggestions, songs) }
    val selectedDuplicateSongs = remember(duplicateGroups, selectedDuplicateIds) {
        duplicateGroups.flatMap { it.second }
            .filter { it.id in selectedDuplicateIds }
            .distinctBy(Song::id)
    }
    val duplicateGroupWouldLoseEveryCopy = duplicateGroups.any { (_, groupSongs) ->
        groupSongs.isNotEmpty() && groupSongs.all { it.id in selectedDuplicateIds }
    }
    val deletableDuplicateIds = selectedDuplicateSongs.filter { song ->
        song.id >= 0L && song.uri.scheme == "content" && song.uri.authority == "media"
    }.map(Song::id).toSet()
    val deletableDuplicateCount = deletableDuplicateIds.size
    val skippedDuplicateCount = selectedDuplicateSongs.size - deletableDuplicateCount
    val canDeleteDuplicates = canDeleteSelectedDuplicateTracks(
        duplicateGroups = duplicateGroups.map { it.first },
        selectedSongIds = selectedDuplicateIds,
        deletableSongIds = deletableDuplicateIds
    )

    BackHandler(enabled = confirmingTitleFixes || confirmingDuplicateDelete) {
        confirmingTitleFixes = false
        confirmingDuplicateDelete = false
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Library cleanup", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 500.dp)) {
                when {
                    confirmingTitleFixes -> {
                        Text(
                            "Selected changes · ${selectedFixes.size}",
                            color = TunelystColors.Primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(7.dp))
                        LazyColumn(
                            modifier = Modifier.heightIn(min = 72.dp, max = 315.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(selectedFixes, key = TitleFixRequest::songId) { fix ->
                                Surface(
                                    color = TunelystColors.SurfaceRaised.copy(alpha = 0.38f),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                        Text(
                                            "Title: “${fix.currentTitle.ifBlank { "Current title" }}” → “${fix.suggestedTitle}”",
                                            color = TunelystColors.Text,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp
                                        )
                                        if (fix.suggestedArtist != null) {
                                            Text(
                                                "Artist: “${fix.currentArtist.ifBlank { "Current artist" }}” → “${fix.suggestedArtist}”",
                                                color = TunelystColors.Text,
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp,
                                                modifier = Modifier.padding(top = 3.dp)
                                            )
                                        }
                                        if (fix.currentFileName != fix.newFileName) {
                                            Text(
                                                "File: ${fix.currentFileName} → ${fix.newFileName}",
                                                color = TunelystColors.Muted,
                                                fontSize = 10.sp,
                                                lineHeight = 14.sp,
                                                modifier = Modifier.padding(top = 3.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        val hasDeviceFiles = selectedFixes.any { it.mediaUriString != null }
                        Text(
                            if (hasDeviceFiles) {
                                "The corrections will be saved in Tunelyst. Android may ask before device tags or filenames are changed."
                            } else {
                                "The corrections will be saved in Tunelyst."
                            },
                            color = TunelystColors.Muted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    confirmingDuplicateDelete -> {
                        Text(
                            "Delete ${selectedDuplicateSongs.size} selected ${if (selectedDuplicateSongs.size == 1) "track" else "tracks"}?",
                            color = TunelystColors.Rose,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            buildString {
                                append("This permanently removes the checked device audio. Leave at least one version unchecked in every group. Android approval is required.")
                                if (skippedDuplicateCount > 0) {
                                    append(" $skippedDuplicateCount shared or unsupported ${if (skippedDuplicateCount == 1) "track will" else "tracks will"} be skipped.")
                                }
                            },
                            color = TunelystColors.Muted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 5.dp, bottom = 8.dp)
                        )
                        LazyColumn(
                            modifier = Modifier.heightIn(min = 72.dp, max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            items(selectedDuplicateSongs, key = Song::id) { song ->
                                Surface(
                                    color = TunelystColors.SurfaceRaised.copy(alpha = 0.38f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(9.dp)) {
                                        Text(
                                            "${song.title} — ${song.artist}",
                                            color = TunelystColors.Text,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            song.fileName.ifBlank { song.folderPath.ifBlank { "Device audio" } },
                                            color = TunelystColors.Subtle,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                label = { Text("Suggested changes · ${suggestions.size}", fontSize = 12.sp) }
                            )
                            FilterChip(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                label = { Text("Possible duplicates · ${duplicateGroups.size}", fontSize = 12.sp) }
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        if (selectedTab == 0) {
                            if (suggestions.isEmpty()) {
                                Text("No title or metadata corrections are suggested.", color = TunelystColors.Muted, fontSize = 13.sp)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${selectedSuggestions.size} of ${suggestions.size} selected",
                                        color = TunelystColors.Muted,
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    val allSuggestionsSelected = selectedIds.containsAll(suggestions.map { it.songId })
                                    TextButton(onClick = {
                                        selectedIds = if (allSuggestionsSelected) emptySet() else suggestions.map { it.songId }.toSet()
                                    }) {
                                        Text(if (allSuggestionsSelected) "Clear" else "Select all", color = TunelystColors.Primary)
                                    }
                                }
                                LazyColumn(
                                    modifier = Modifier.heightIn(min = 96.dp, max = 340.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(suggestions, key = LibraryCleanupSuggestion::songId) { suggestion ->
                                        val checked = suggestion.songId in selectedIds
                                        Surface(
                                            color = TunelystColors.SurfaceRaised.copy(alpha = 0.38f),
                                            shape = RoundedCornerShape(15.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = checked,
                                                    onCheckedChange = { selected ->
                                                        selectedIds = if (selected) selectedIds + suggestion.songId else selectedIds - suggestion.songId
                                                    }
                                                )
                                                Column(Modifier.weight(1f).padding(start = 3.dp, end = 4.dp)) {
                                                    Text(
                                                        if (suggestion.kind == LibraryCleanupSuggestionKind.POSSIBLE_TITLE_ARTIST_SWAP) "Possible title/artist mix-up"
                                                        else "Clean up title",
                                                        color = TunelystColors.Primary,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        "Title: “${suggestion.originalTitle}” → “${suggestion.suggestedTitle}”",
                                                        color = TunelystColors.Text,
                                                        fontSize = 12.sp,
                                                        lineHeight = 16.sp
                                                    )
                                                    if (suggestion.suggestedArtist != null) {
                                                        Text(
                                                            "Artist: “${suggestion.originalArtist}” → “${suggestion.suggestedArtist}”",
                                                            color = TunelystColors.Text,
                                                            fontSize = 12.sp,
                                                            lineHeight = 16.sp
                                                        )
                                                    } else if (suggestion.originalArtist.isNotBlank()) {
                                                        Text(
                                                            suggestion.originalArtist,
                                                            color = TunelystColors.Muted,
                                                            fontSize = 11.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Text(
                                                        suggestion.reason,
                                                        color = TunelystColors.Muted,
                                                        fontSize = 10.sp,
                                                        lineHeight = 14.sp,
                                                        modifier = Modifier.padding(top = 3.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (duplicateGroups.isEmpty()) {
                            Text("No likely duplicate groups were found.", color = TunelystColors.Muted, fontSize = 13.sp)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${selectedDuplicateIds.size} selected",
                                    color = TunelystColors.Muted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                val allDuplicatesSelected = allDuplicateIds.isNotEmpty() && selectedDuplicateIds.containsAll(allDuplicateIds)
                                TextButton(onClick = {
                                    selectedDuplicateIds = if (allDuplicatesSelected) emptySet() else allDuplicateIds
                                }) {
                                    Text(if (allDuplicatesSelected) "Clear" else "Select all", color = TunelystColors.Primary)
                                }
                            }
                            if (duplicateGroupWouldLoseEveryCopy) {
                                Text(
                                    "Keep at least one unchecked copy in each group.",
                                    color = TunelystColors.Rose,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            if (selectedDuplicateSongs.isNotEmpty() && skippedDuplicateCount > 0) {
                                Text(
                                    "$skippedDuplicateCount shared or unsupported ${if (skippedDuplicateCount == 1) "track" else "tracks"} cannot be deleted here.",
                                    color = TunelystColors.Rose,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            LazyColumn(
                                modifier = Modifier.heightIn(min = 96.dp, max = 330.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                items(duplicateGroups, key = { it.first.songIds.first() }) { (group, groupSongs) ->
                                    Surface(
                                        color = TunelystColors.SurfaceRaised.copy(alpha = 0.38f),
                                        shape = RoundedCornerShape(15.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(11.dp)) {
                                            Text(
                                                "Possible duplicate · ${groupSongs.size} tracks",
                                                color = TunelystColors.Primary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                "${group.representativeTitle} — ${group.representativeArtist}",
                                                color = TunelystColors.Text,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                            Text(
                                                group.reason,
                                                color = TunelystColors.Muted,
                                                fontSize = 10.sp,
                                                lineHeight = 14.sp,
                                                modifier = Modifier.padding(top = 3.dp, bottom = 4.dp)
                                            )
                                            groupSongs.forEach { song ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Checkbox(
                                                        checked = song.id in selectedDuplicateIds,
                                                        onCheckedChange = { checked ->
                                                            selectedDuplicateIds = if (checked) selectedDuplicateIds + song.id else selectedDuplicateIds - song.id
                                                        }
                                                    )
                                                    Column(Modifier.weight(1f).padding(start = 3.dp)) {
                                                        Text(
                                                            "${formatTime(song.durationMs)} · ${song.title} — ${song.artist}",
                                                            color = TunelystColors.Text,
                                                            fontSize = 11.sp,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            song.fileName.ifBlank { song.folderPath.ifBlank { "Device audio" } },
                                                            color = TunelystColors.Subtle,
                                                            fontSize = 10.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                confirmingTitleFixes -> TextButton(
                    enabled = selectedFixes.isNotEmpty(),
                    onClick = { onApplyTitleFixes(selectedFixes) }
                ) { Text("Apply corrections", color = TunelystColors.Primary) }
                confirmingDuplicateDelete -> TextButton(
                    enabled = canDeleteDuplicates,
                    onClick = { onDeleteDuplicates(selectedDuplicateSongs) }
                ) { Text("Continue to Android approval", color = TunelystColors.Rose) }
                selectedTab == 0 -> TextButton(
                    enabled = selectedFixes.isNotEmpty(),
                    onClick = { confirmingTitleFixes = true }
                ) { Text("Clean selected · ${selectedFixes.size}", color = TunelystColors.Primary) }
                else -> TextButton(
                    enabled = canDeleteDuplicates,
                    onClick = { confirmingDuplicateDelete = true }
                ) { Text("Delete selected · ${selectedDuplicateSongs.size}", color = TunelystColors.Rose) }
            }
        },
        dismissButton = {
            when {
                confirmingTitleFixes -> TextButton(onClick = { confirmingTitleFixes = false }) { Text("Back", color = TunelystColors.Muted) }
                confirmingDuplicateDelete -> TextButton(onClick = { confirmingDuplicateDelete = false }) { Text("Back", color = TunelystColors.Muted) }
                else -> TextButton(onClick = onDismiss) { Text("Done", color = TunelystColors.Muted) }
            }
        },
        containerColor = TunelystColors.Surface
    )
}

@Composable
private fun SmartPlaylistRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(color = TunelystColors.Surface, shape = RoundedCornerShape(17.dp), modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).springyClickable(onClick = onClick).padding(vertical = 6.dp)) {
                Text(title, color = TunelystColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TunelystColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
            TextButton(onClick = onClick) { Text("Play", color = TunelystColors.Primary) }
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Rounded.Close, contentDescription = "Delete", tint = TunelystColors.Subtle) }
        }
    }
}

@Composable
private fun SmartPlaylistEditorDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("artist") }
    var value by rememberSaveable { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    val kinds = listOf("artist", "year", "genre")
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create smart list", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("The rule is evaluated locally whenever you open the list.", color = TunelystColors.Muted, fontSize = 12.sp)
                OutlinedTextField(value = name, onValueChange = { name = it.take(48) }, label = { Text("List name") }, singleLine = true)
                Box {
                    TextButton(onClick = { menuExpanded = true }) { Text("Rule · ${kind.replaceFirstChar(Char::uppercase)}", color = TunelystColors.Primary) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        kinds.forEach { option -> DropdownMenuItem(text = { Text(option.replaceFirstChar(Char::uppercase)) }, onClick = { kind = option; menuExpanded = false }) }
                    }
                }
                OutlinedTextField(value = value, onValueChange = { value = it.take(80) }, label = { Text("${kind.replaceFirstChar(Char::uppercase)} value") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && value.isNotBlank(), onClick = { onSave(name.trim(), kind, value.trim()) }) { Text("Save rule", color = TunelystColors.Primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = TunelystColors.Surface
    )
}

@Composable
private fun AlbumOrArtistRow(
    title: String,
    subtitle: String,
    songs: List<Song>,
    titleMaxLines: Int = 1,
    actionDescription: String = "Play",
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val compactLayout = isTunelystCompactViewport()
    val artworkSize = if (compactLayout) 50.dp else 58.dp
    Row(
        modifier = modifier.fillMaxWidth().springyClickable(onClick = onClick).padding(vertical = if (compactLayout) 6.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArtwork(songs.firstOrNull()?.artworkUri, title, Modifier.size(artworkSize), RoundedCornerShape(if (compactLayout) 13.dp else 15.dp))
        Column(modifier = Modifier.weight(1f).padding(horizontal = if (compactLayout) 10.dp else 13.dp)) {
            Text(title, color = TunelystColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TunelystColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = if (compactLayout) 3.dp else 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ArrowForward, contentDescription = actionDescription, tint = TunelystColors.Subtle)
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, allSongs: List<Song>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val compactLayout = isTunelystCompactViewport()
    val artworkSize = if (compactLayout) 50.dp else 60.dp
    val songById = remember(allSongs) { allSongs.associateBy(Song::id) }
    val cover = playlist.songIds.firstNotNullOfOrNull(songById::get)
    Surface(color = TunelystColors.Surface, shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(if (compactLayout) 8.dp else 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f).springyClickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
                if (cover != null) AlbumArtwork(cover.artworkUri, cover.title, Modifier.size(artworkSize), RoundedCornerShape(if (compactLayout) 13.dp else 15.dp))
                else Box(Modifier.size(artworkSize).clip(RoundedCornerShape(if (compactLayout) 13.dp else 15.dp)).background(TunelystColors.Primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = TunelystColors.Primary)
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = if (compactLayout) 9.dp else 13.dp)) {
                    Text(playlist.name, color = TunelystColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${playlist.songIds.size} tracks · Playlist", color = TunelystColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Icon(Icons.Rounded.ArrowForward, contentDescription = "Open playlist", tint = TunelystColors.Subtle)
            }
        }
    }
}

@Composable
private fun PlaylistEmptyState(onCreatePlaylist: () -> Unit) {
    Surface(color = TunelystColors.Surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Your first playlist is a blank canvas.", color = TunelystColors.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Gather the songs you love into a mix that feels like you.", color = TunelystColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp))
            Text("＋  Create a playlist", color = TunelystColors.Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.springyClickable(onClick = onCreatePlaylist).padding(top = 15.dp))
        }
    }
}

private fun pageContainsCurrentSong(songs: List<Song>, currentSong: Song?): Boolean =
    currentSong != null && songs.any { it.id == currentSong.id }

private fun performPagePlaybackAction(
    songs: List<Song>,
    currentSong: Song?,
    onPlay: (List<Song>, Int) -> Unit,
    onTogglePlayback: () -> Unit
) {
    if (pageContainsCurrentSong(songs, currentSong)) onTogglePlayback()
    else if (songs.isNotEmpty()) onPlay(songs, 0)
}

private fun pagePlaybackIcon(songs: List<Song>, currentSong: Song?, isPlaying: Boolean) =
    if (pageContainsCurrentSong(songs, currentSong) && isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow

@Composable
private fun ArtworkActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.42f))
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun PlaylistDetailScreen(
    playlist: Playlist,
    allSongs: List<Song>,
    favorites: Set<Long>,
    currentSong: Song?,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    hiddenHomeSongIds: Set<Long>,
    onSetSongHiddenFromHome: (Long, Boolean) -> Unit,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onPlayLast: (Song) -> Unit,
    onRemoveFromPlaylist: (Song) -> Unit,
    onDelete: () -> Unit
) {
    val songById = remember(allSongs) { allSongs.associateBy(Song::id) }
    val playlistSongs = remember(playlist.songIds, allSongs) { playlist.songIds.mapNotNull(songById::get) }
    var deleteMenu by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            ImmersiveDetailArtworkHeader(
                sectionLabel = "PLAYLIST",
                title = playlist.name,
                subtitle = "${playlistSongs.size} ${if (playlistSongs.size == 1) "track" else "tracks"} · Stored on this device",
                artworkSong = playlistSongs.firstOrNull(),
                artworkSongs = playlistSongs,
                onBack = onBack,
                titleAction = {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        ArtworkActionIconButton(
                            icon = pagePlaybackIcon(playlistSongs, currentSong, isPlaying),
                            contentDescription = when {
                                pageContainsCurrentSong(playlistSongs, currentSong) && isPlaying -> "Pause playlist"
                                pageContainsCurrentSong(playlistSongs, currentSong) -> "Resume playlist"
                                else -> "Play playlist"
                            },
                            onClick = { performPagePlaybackAction(playlistSongs, currentSong, onPlay, onTogglePlayback) }
                        )
                        Box {
                            ArtworkActionIconButton(Icons.Rounded.MoreVert, "Playlist options", onClick = { deleteMenu = true })
                            DropdownMenu(expanded = deleteMenu, onDismissRequest = { deleteMenu = false }) {
                                DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { deleteMenu = false; onDelete() })
                            }
                        }
                    }
                }
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                SectionHeading("In this mix", modifier = Modifier.padding(top = 14.dp))
            }
        }
        if (playlistSongs.isEmpty()) {
            item {
                Text(
                    "Add songs from Search or your library using the ··· menu.",
                    color = TunelystColors.Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                )
            }
        } else {
            items(playlistSongs, key = Song::id) { song ->
                SongRow(
                    song = song,
                    modifier = Modifier.padding(horizontal = 20.dp).animateItem(),
                    isFavorite = song.id in favorites,
                    onClick = { onPlay(playlistSongs, playlistSongs.indexOf(song)) },
                    onToggleFavorite = { onToggleFavorite(song.id) },
                    onAddToPlaylist = { onAddToPlaylist(song) },
                    onPlayNext = { onPlayNext(song) },
                    onPlayLast = { onPlayLast(song) },
                    isHiddenFromHome = song.id in hiddenHomeSongIds,
                    onToggleHomeVisibility = { onSetSongHiddenFromHome(song.id, song.id !in hiddenHomeSongIds) },
                    onRemoveFromPlaylist = { onRemoveFromPlaylist(song) }
                )
            }
        }
    }
}

@Composable
private fun ImmersiveDetailArtworkHeader(
    sectionLabel: String,
    title: String,
    subtitle: String,
    artworkSong: Song?,
    onBack: () -> Unit,
    artworkSongs: List<Song>? = null,
    showText: Boolean = true,
    titleOverline: String? = null,
    titleOverlineAction: (() -> Unit)? = null,
    titleAction: (@Composable () -> Unit)? = null
) {
    val configuration = LocalConfiguration.current
    val palette = LocalTunelystPalette.current
    val compact = configuration.screenHeightDp < 700 || LocalDensity.current.fontScale >= 1.25f
    val statusBarTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val height = statusBarTopInset + if (compact) 226.dp else 252.dp
    Box(Modifier.fillMaxWidth().height(height)) {
        when {
            artworkSongs != null -> MixArtworkCollage(
                songs = artworkSongs,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(0.dp)
            )
            artworkSong != null -> AlbumArtwork(
                artworkSong.artworkUri,
                title,
                Modifier.fillMaxSize(),
                RoundedCornerShape(0.dp)
            )
            else -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF183D4A), Color(0xFF29243F), Color(0xFF493451)))))
        }
        // Full-bleed artwork has no card corners; its lower edge fades into the already-blurred
        // global artwork wash so detail pages feel continuous with the rest of the app.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.42f),
                    0.42f to Color.Black.copy(alpha = 0.08f),
                    0.72f to palette.background.copy(alpha = 0.34f),
                    1f to palette.background.copy(alpha = 0.96f)
                )
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .padding(start = 20.dp, end = 20.dp, top = statusBarTopInset + 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.32f))
            ) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back to library", tint = Color.White)
            }
            Text(sectionLabel, color = Color.White.copy(alpha = 0.90f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 10.dp))
        }
        if (showText) {
            Column(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                titleOverline?.takeIf(String::isNotBlank)?.let { overline ->
                    Text(
                        overline,
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (titleOverlineAction != null) {
                            Modifier.springyClickable(role = androidx.compose.ui.semantics.Role.Button, onClick = titleOverlineAction)
                        } else Modifier
                    )
                }
                if (titleAction == null) {
                    Text(title, color = palette.text, fontSize = if (compact) 24.sp else 28.sp, lineHeight = if (compact) 28.sp else 32.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            title,
                            color = palette.text,
                            fontSize = if (compact) 24.sp else 28.sp,
                            lineHeight = if (compact) 28.sp else 32.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        titleAction()
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
}

@Composable
fun AlbumDetailScreen(
    album: AlbumGroup,
    favorites: Set<Long>,
    currentSong: Song?,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    hiddenHomeSongIds: Set<Long>,
    onSetSongHiddenFromHome: (Long, Boolean) -> Unit,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onPlayLast: (Song) -> Unit,
    onOpenArtist: (String) -> Unit,
    onEditSong: (Song) -> Unit
) {
    val songs = album.songs
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            ImmersiveDetailArtworkHeader(
                sectionLabel = "ALBUM",
                title = album.album,
                subtitle = "${songs.size} ${if (songs.size == 1) "track" else "tracks"} · Stored on this device",
                artworkSong = songs.firstOrNull(),
                onBack = onBack,
                titleOverline = album.artist,
                titleOverlineAction = { onOpenArtist(album.artist) },
                titleAction = {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        ArtworkActionIconButton(
                            icon = pagePlaybackIcon(songs, currentSong, isPlaying),
                            contentDescription = when {
                                pageContainsCurrentSong(songs, currentSong) && isPlaying -> "Pause album"
                                pageContainsCurrentSong(songs, currentSong) -> "Resume album"
                                else -> "Play album"
                            },
                            onClick = { performPagePlaybackAction(songs, currentSong, onPlay, onTogglePlayback) }
                        )
                        ArtworkActionIconButton(Icons.Rounded.LibraryMusic, "All albums", onClick = onBack)
                    }
                }
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                SectionHeading("Tracks", modifier = Modifier.padding(top = 14.dp))
            }
        }
        items(songs, key = Song::id) { song ->
            SongRow(
                song = song,
                modifier = Modifier.padding(horizontal = 20.dp).animateItem(),
                isFavorite = song.id in favorites,
                onClick = { onPlay(songs, songs.indexOf(song)) },
                onToggleFavorite = { onToggleFavorite(song.id) },
                onAddToPlaylist = { onAddToPlaylist(song) },
                onPlayNext = { onPlayNext(song) },
                onPlayLast = { onPlayLast(song) },
                onEditMetadata = { onEditSong(song) },
                isHiddenFromHome = song.id in hiddenHomeSongIds,
                onToggleHomeVisibility = { onSetSongHiddenFromHome(song.id, song.id !in hiddenHomeSongIds) }
            )
        }
    }
}

@Composable
fun FolderDetailScreen(
    folderPath: String,
    songs: List<Song>,
    favorites: Set<Long>,
    currentSong: Song?,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    hiddenHomeSongIds: Set<Long>,
    onSetSongHiddenFromHome: (Long, Boolean) -> Unit,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onPlayLast: (Song) -> Unit,
    onEditSong: (Song) -> Unit
) {
    val folderName = folderPath.substringAfterLast('/').ifBlank { folderPath }
    val parentPath = folderPath.substringBeforeLast('/', "")
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            ImmersiveDetailArtworkHeader(
                sectionLabel = "FOLDER",
                title = folderName,
                subtitle = "${songs.size} ${if (songs.size == 1) "track" else "tracks"} in this folder${if (parentPath.isBlank()) "" else " · $parentPath"}",
                artworkSong = songs.firstOrNull(),
                onBack = onBack,
                titleAction = {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        ArtworkActionIconButton(
                            icon = pagePlaybackIcon(songs, currentSong, isPlaying),
                            contentDescription = when {
                                pageContainsCurrentSong(songs, currentSong) && isPlaying -> "Pause folder"
                                pageContainsCurrentSong(songs, currentSong) -> "Resume folder"
                                else -> "Play folder"
                            },
                            onClick = { performPagePlaybackAction(songs, currentSong, onPlay, onTogglePlayback) }
                        )
                        ArtworkActionIconButton(Icons.Rounded.FolderOpen, "All folders", onClick = onBack)
                    }
                }
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                SectionHeading("Tracks", modifier = Modifier.padding(top = 14.dp))
            }
        }
        if (songs.isEmpty()) {
            item {
                Text(
                    "No local tracks were found in this folder.",
                    color = TunelystColors.Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                )
            }
        } else {
            items(songs, key = Song::id) { song ->
                SongRow(
                    song = song,
                    modifier = Modifier.padding(horizontal = 20.dp).animateItem(),
                    isFavorite = song.id in favorites,
                    onClick = { onPlay(songs, songs.indexOf(song)) },
                    onToggleFavorite = { onToggleFavorite(song.id) },
                    onAddToPlaylist = { onAddToPlaylist(song) },
                    onPlayNext = { onPlayNext(song) },
                    onPlayLast = { onPlayLast(song) },
                    onEditMetadata = { onEditSong(song) },
                    isHiddenFromHome = song.id in hiddenHomeSongIds,
                    onToggleHomeVisibility = { onSetSongHiddenFromHome(song.id, song.id !in hiddenHomeSongIds) }
                )
            }
        }
    }
}

@Composable
fun ArtistDetailScreen(
    artist: String,
    allSongs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    favorites: Set<Long>,
    hiddenHomeSongIds: Set<Long>,
    onSetSongHiddenFromHome: (Long, Boolean) -> Unit,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onPlayLast: (Song) -> Unit
) {
    val artistSongs = remember(artist, allSongs, currentSong) {
        (allSongs.filter { it.artist.equals(artist, ignoreCase = true) || it.albumArtist.equals(artist, ignoreCase = true) } +
            listOfNotNull(currentSong?.takeIf { it.artist.equals(artist, ignoreCase = true) || it.albumArtist.equals(artist, ignoreCase = true) }))
            .distinctBy(Song::id)
            .sortedWith(
                compareBy<Song> { it.album.lowercase() }
                    .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                    .thenBy { it.title.lowercase() }
            )
    }
    val albumCount = artistSongs.map(Song::album).distinct().size
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            ImmersiveDetailArtworkHeader(
                sectionLabel = "ARTIST",
                title = artist,
                subtitle = "$albumCount ${if (albumCount == 1) "album" else "albums"} · ${artistSongs.size} ${if (artistSongs.size == 1) "track" else "tracks"} on this device",
                artworkSong = artistSongs.firstOrNull(),
                onBack = onBack,
                titleAction = {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        ArtworkActionIconButton(
                            icon = pagePlaybackIcon(artistSongs, currentSong, isPlaying),
                            contentDescription = when {
                                pageContainsCurrentSong(artistSongs, currentSong) && isPlaying -> "Pause songs by $artist"
                                pageContainsCurrentSong(artistSongs, currentSong) -> "Resume songs by $artist"
                                else -> "Play songs by $artist"
                            },
                            onClick = { performPagePlaybackAction(artistSongs, currentSong, onPlay, onTogglePlayback) }
                        )
                        ArtworkActionIconButton(Icons.Rounded.Person, "All artists", onClick = onBack)
                    }
                }
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                SectionHeading("Songs", modifier = Modifier.padding(top = 12.dp))
            }
        }
        if (artistSongs.isEmpty()) {
            item {
                Text(
                    "No local tracks were found for this artist.",
                    color = TunelystColors.Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                )
            }
        } else {
            items(artistSongs, key = Song::id) { song ->
                SongRow(
                    song = song,
                    modifier = Modifier.padding(horizontal = 20.dp).animateItem(),
                    isFavorite = song.id in favorites,
                    onClick = { onPlay(artistSongs, artistSongs.indexOf(song)) },
                    onToggleFavorite = { onToggleFavorite(song.id) },
                    onAddToPlaylist = { onAddToPlaylist(song) },
                    onPlayNext = { onPlayNext(song) },
                    onPlayLast = { onPlayLast(song) },
                    isHiddenFromHome = song.id in hiddenHomeSongIds,
                    onToggleHomeVisibility = { onSetSongHiddenFromHome(song.id, song.id !in hiddenHomeSongIds) }
                )
            }
        }
    }
}

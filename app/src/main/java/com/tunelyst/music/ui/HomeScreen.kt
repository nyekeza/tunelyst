package com.tunelyst.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tunelyst.music.data.Song

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
            currentSong = playback.displaySong,
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
                nowPlaying = playback.displaySong,
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
                val radioSeed = playback.displaySong ?: listenAgain.firstOrNull() ?: homeSongs.firstOrNull()
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

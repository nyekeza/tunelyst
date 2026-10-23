package com.tunelyst.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.tunelyst.music.data.Song

import java.util.Locale

@Composable
internal fun HomeRecommendationShelves(
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
internal fun HomeMixCarousel(
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

@Composable
internal fun QuickPickCarousel(
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
internal fun MadeForYouMixDetailScreen(
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

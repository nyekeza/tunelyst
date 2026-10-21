package com.tunelyst.music.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunelyst.music.data.Song
import com.tunelyst.music.data.TrackListeningStats

@Composable
fun ListeningStatsScreen(
    state: MusicUiState,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onPlayLast: (Song) -> Unit
) {
    val totals = remember(state.listeningStats) {
        state.listeningStats.values.fold(TrackListeningStats()) { total, track ->
            total.copy(
                listenedMs = total.listenedMs + track.listenedMs.coerceAtLeast(0L),
                completions = total.completions + track.completions.coerceAtLeast(0L),
                skips = total.skips + track.skips.coerceAtLeast(0L),
                qualifiedPlays = total.qualifiedPlays + track.qualifiedPlays.coerceAtLeast(0L)
            )
        }
    }
    val tracksHeard = remember(state.listeningStats) {
        state.listeningStats.values.count { it.listenedMs > 0L }
    }
    val topTracks = remember(state.songs, state.listeningStats) {
        val songsById = state.songs.associateBy(Song::id)
        state.listeningStats.entries
            .asSequence()
            .filter { it.value.listenedMs > 0L }
            .sortedByDescending { it.value.listenedMs }
            .mapNotNull { (songId, stats) -> songsById[songId]?.let { it to stats } }
            .take(8)
            .toList()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HistoryPageHeading(
                title = "Listening stats",
                subtitle = "Your listening history · all time",
                onBack = onBack
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ListeningStatCard(
                        label = "Time listened",
                        value = formatListeningDuration(totals.listenedMs),
                        modifier = Modifier.weight(1f)
                    )
                    ListeningStatCard(
                        label = "Plays",
                        value = totals.qualifiedPlays.toString(),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ListeningStatCard(
                        label = "Tracks heard",
                        value = tracksHeard.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    ListeningStatCard(
                        label = "Completions",
                        value = totals.completions.toString(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (topTracks.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(top = 6.dp, bottom = 1.dp)) {
                    Text("TOP TRACKS", color = TunelystColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "By listening time",
                        color = TunelystColors.Subtle,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
            itemsIndexed(topTracks, key = { _, entry -> entry.first.id }) { index, entry ->
                val (song, stats) = entry
                ListeningStatsTrackRow(
                    rank = index + 1,
                    song = song,
                    stats = stats,
                    favorites = state.favorites,
                    onPlay = { onPlay(topTracks.map { it.first }, index) },
                    onToggleFavorite = { onToggleFavorite(song.id) },
                    onAddToPlaylist = { onAddToPlaylist(song) },
                    onPlayNext = { onPlayNext(song) },
                    onPlayLast = { onPlayLast(song) }
                )
            }
        } else {
            item {
                HistoryEmptyState(
                    title = when {
                        !state.isLibraryReady -> "Loading your library…"
                        tracksHeard > 0 -> "No top tracks in this library"
                        else -> "Listening stats are just getting started"
                    },
                    detail = when {
                        !state.isLibraryReady -> "Your local listening history will appear when the library is ready."
                        tracksHeard > 0 -> "Your saved totals include tracks that are no longer available in the current library."
                        else -> "Tunelyst keeps track of your listening time, plays, and completed songs on this device."
                    }
                )
            }
        }
        item {
            Surface(
                color = TunelystColors.Surface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, TunelystColors.Border.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            ) {
                Text(
                    "Your listening history stays on this device. A play is counted after the listening time set in Settings.",
                    color = TunelystColors.Muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun ListeningStatsTrackRow(
    rank: Int,
    song: Song,
    stats: TrackListeningStats,
    favorites: Set<Long>,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayLast: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                rank.toString(),
                color = TunelystColors.Subtle,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.size(width = 22.dp, height = 32.dp).padding(top = 7.dp)
            )
            SongRow(
                song = song,
                isFavorite = song.id in favorites,
                onClick = onPlay,
                onToggleFavorite = onToggleFavorite,
                onAddToPlaylist = onAddToPlaylist,
                onPlayNext = onPlayNext,
                onPlayLast = onPlayLast,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "${formatListeningDuration(stats.listenedMs)} listened · ${stats.qualifiedPlays} plays",
            color = TunelystColors.Subtle,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 76.dp, end = 8.dp, bottom = 6.dp)
        )
    }
}

@Composable
private fun ListeningStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = TunelystColors.Surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, TunelystColors.Border.copy(alpha = 0.62f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(13.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(label, color = TunelystColors.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                value,
                color = TunelystColors.Primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

@Composable
private fun HistoryPageHeading(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back to Home", tint = TunelystColors.Text)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 5.dp)) {
            Text(title, color = TunelystColors.Text, fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = TunelystColors.Muted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun HistoryEmptyState(title: String, detail: String) {
    Surface(
        color = TunelystColors.Surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, TunelystColors.Border.copy(alpha = 0.58f)),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = TunelystColors.Primary, modifier = Modifier.size(30.dp))
            Text(title, color = TunelystColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = TunelystColors.Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

private fun formatListeningDuration(listenedMs: Long): String {
    val totalMinutes = listenedMs.coerceAtLeast(0L) / 60_000L
    if (totalMinutes == 0L) return if (listenedMs > 0L) "<1 min" else "0 min"
    val totalHours = totalMinutes / 60L
    val days = totalHours / 24L
    return when {
        days > 0L -> "${days}d ${totalHours % 24L}h"
        totalHours > 0L && totalMinutes % 60L > 0L -> "${totalHours}h ${totalMinutes % 60L}m"
        totalHours > 0L -> "${totalHours}h"
        else -> "$totalMinutes min"
    }
}

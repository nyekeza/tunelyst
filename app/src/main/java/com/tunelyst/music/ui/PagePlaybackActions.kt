package com.tunelyst.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.tunelyst.music.data.Song

internal fun pageContainsCurrentSong(songs: List<Song>, currentSong: Song?): Boolean =
    currentSong != null && songs.any { it.id == currentSong.id }

internal fun performPagePlaybackAction(
    songs: List<Song>,
    currentSong: Song?,
    onPlay: (List<Song>, Int) -> Unit,
    onTogglePlayback: () -> Unit
) {
    if (pageContainsCurrentSong(songs, currentSong)) onTogglePlayback()
    else if (songs.isNotEmpty()) onPlay(songs, 0)
}

internal fun pagePlaybackIcon(songs: List<Song>, currentSong: Song?, isPlaying: Boolean) =
    if (pageContainsCurrentSong(songs, currentSong) && isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow

@Composable
internal fun ArtworkActionIconButton(
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

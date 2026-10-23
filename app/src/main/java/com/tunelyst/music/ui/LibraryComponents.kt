package com.tunelyst.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunelyst.music.data.Playlist
import com.tunelyst.music.data.Song

@Composable
internal fun SmartPlaylistRow(
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
internal fun SmartPlaylistEditorDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
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
internal fun AlbumOrArtistRow(
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
internal fun PlaylistRow(playlist: Playlist, allSongs: List<Song>, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
internal fun PlaylistEmptyState(onCreatePlaylist: () -> Unit) {
    Surface(color = TunelystColors.Surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Your first playlist is a blank canvas.", color = TunelystColors.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Gather the songs you love into a mix that feels like you.", color = TunelystColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp))
            Text("＋  Create a playlist", color = TunelystColors.Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.springyClickable(onClick = onCreatePlaylist).padding(top = 15.dp))
        }
    }
}

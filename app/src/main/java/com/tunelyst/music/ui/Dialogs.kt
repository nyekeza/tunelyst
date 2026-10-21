package com.tunelyst.music.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunelyst.music.data.Playlist
import com.tunelyst.music.data.Song
import com.tunelyst.music.data.SongMetadataOverride

@Composable
fun PlaylistPickerDialog(
    playlists: List<Playlist>,
    onChoose: (Playlist) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist", fontWeight = FontWeight.Bold) },
        text = {
            if (playlists.isEmpty()) {
                Text("Create a playlist to keep your music organised.", color = TunelystColors.Muted)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                    items(playlists, key = Playlist::id) { playlist ->
                        Row(
                            modifier = Modifier.fillMaxWidth().springyClickable { onChoose(playlist) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = TunelystColors.Primary)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(playlist.name, fontWeight = FontWeight.SemiBold)
                                Text("${playlist.songIds.size} tracks", color = TunelystColors.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = TunelystColors.Muted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreateNew) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Text("New playlist", modifier = Modifier.padding(start = 6.dp))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(26.dp),
        containerColor = TunelystColors.Surface
    )
}

@Composable
fun CreatePlaylistDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create playlist", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(48) },
                label = { Text("Playlist name") },
                singleLine = true,
                shape = RoundedCornerShape(15.dp)
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()) }) {
                Icon(Icons.Rounded.Check, contentDescription = null)
                Text("Create", modifier = Modifier.padding(start = 5.dp))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(26.dp),
        containerColor = TunelystColors.Surface
    )
}

@Composable
private fun MetadataField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp)
    )
}

@Composable
fun EditSongMetadataDialog(
    song: Song,
    existingArtworkOverride: String?,
    onSave: (SongMetadataOverride) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var title by remember(song.id) { mutableStateOf(song.title) }
    var artist by remember(song.id) { mutableStateOf(song.artist) }
    var album by remember(song.id) { mutableStateOf(song.album) }
    var albumArtist by remember(song.id) { mutableStateOf(song.albumArtist) }
    var genre by remember(song.id) { mutableStateOf(song.genre.takeUnless { it == "Unknown genre" }.orEmpty()) }
    var year by remember(song.id) { mutableStateOf(song.year.takeIf { it > 0 }?.toString().orEmpty()) }
    var disc by remember(song.id) { mutableStateOf(song.discNumber.takeIf { it > 0 }?.toString().orEmpty()) }
    var track by remember(song.id) { mutableStateOf(song.trackNumber.takeIf { it > 0 }?.toString().orEmpty()) }
    var replacementArtworkUri by remember(song.id) { mutableStateOf<String?>(null) }
    var artworkPermissionError by remember(song.id) { mutableStateOf<String?>(null) }
    var isCompilation by remember(song.id) { mutableStateOf(song.isCompilation) }
    val artworkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            replacementArtworkUri = uri.toString()
            artworkPermissionError = null
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit local tags", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                MetadataField("Title", title, { title = it })
                MetadataField("Track artist / featured artists", artist, { artist = it })
                MetadataField("Album", album, { album = it })
                MetadataField("Album artist", albumArtist, { albumArtist = it })
                MetadataField("Genre", genre, { genre = it })
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    MetadataField("Year", year, { year = it.filter(Char::isDigit).take(4) }, KeyboardType.Number, Modifier.weight(1f))
                    MetadataField("Disc", disc, { disc = it.filter(Char::isDigit).take(2) }, KeyboardType.Number, Modifier.weight(1f))
                    MetadataField("Track", track, { track = it.filter(Char::isDigit).take(3) }, KeyboardType.Number, Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isCompilation, onCheckedChange = { enabled ->
                        isCompilation = enabled
                        if (enabled && albumArtist == song.albumArtist) albumArtist = "Various Artists"
                    })
                    Text("Compilation / various artists", color = TunelystColors.Text, fontSize = 13.sp)
                }
                TextButton(onClick = { artworkPicker.launch(arrayOf("image/*")) }) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text(if (replacementArtworkUri == null) "Choose replacement artwork" else "Change replacement artwork", modifier = Modifier.padding(start = 6.dp))
                }
                Text(
                    if (replacementArtworkUri != null) "New replacement cover selected; access is saved only when you save this correction."
                    else if (song.artworkUri != null) "Current cover will be kept unless you choose a replacement."
                    else "No replacement cover selected.",
                    color = TunelystColors.Muted,
                    fontSize = 11.sp
                )
                if (replacementArtworkUri != null) {
                    TextButton(onClick = {
                        replacementArtworkUri = null
                        artworkPermissionError = null
                    }) { Text("Keep current cover", color = TunelystColors.Muted) }
                }
                artworkPermissionError?.let { message ->
                    Text(message, color = TunelystColors.Rose, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val newArtworkUri = replacementArtworkUri?.let(Uri::parse)
                if (newArtworkUri != null && replacementArtworkUri != existingArtworkOverride) {
                    val grantResult = runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            newArtworkUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    if (grantResult.isFailure) {
                        artworkPermissionError = "Android couldn't keep access to that image. Choose another image or save without replacing the cover."
                        return@TextButton
                    }
                }
                onSave(SongMetadataOverride(
                    songId = song.id,
                    title = title.trim(), artist = artist.trim(), album = album.trim(), albumArtist = albumArtist.trim(),
                    genre = genre.trim(), year = year.toIntOrNull(), discNumber = disc.toIntOrNull(), trackNumber = track.toIntOrNull(),
                    artworkUri = replacementArtworkUri ?: existingArtworkOverride,
                    isCompilation = isCompilation
                ))
            }) { Text("Save correction", color = TunelystColors.Primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(26.dp),
        containerColor = TunelystColors.Surface
    )
}

@Composable
fun SaveQueueDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save current queue", fontWeight = FontWeight.Bold) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(48) }, label = { Text("Queue name") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Save", color = TunelystColors.Primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = TunelystColors.Surface,
        shape = RoundedCornerShape(24.dp)
    )
}

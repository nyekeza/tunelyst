package com.tunelyst.music.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tunelyst.music.data.Song
import com.tunelyst.music.core.logic.normalizeTasteKey
import com.tunelyst.music.core.logic.normalizedGenreKeys

internal data class SongActionController(
    val markedSongIds: Set<Long>,
    val favoriteSongIds: Set<Long>,
    val hiddenHomeSongIds: Set<Long>,
    val lessLikeArtistNames: Set<String>,
    val lessLikeGenreNames: Set<String>,
    val playNextSongIds: Set<Long>,
    val queuedSongIds: Set<Long>,
    val currentlyPlayingSongId: Long?,
    val isPlaying: Boolean,
    val onToggleLessLikeArtist: (Song) -> Unit,
    val onToggleLessLikeGenres: (Song) -> Unit,
    val onToggleMark: (Song) -> Unit,
    val onToggleHomeVisibility: (Song) -> Unit,
    val onPlay: (Song) -> Unit,
    val onPlayNext: (Song) -> Unit,
    val onAddToQueue: (Song) -> Unit,
    val onAddToPlaylist: (Song) -> Unit,
    val onToggleFavorite: (Song) -> Unit,
    val onDeleteFromDevice: (Song) -> Unit,
    val onShare: (Song) -> Unit,
    val onPlayTracks: (List<Song>) -> Unit,
    val onPlayNextTracks: (List<Song>) -> Unit,
    val onAddTracksToQueue: (List<Song>) -> Unit,
    val onAddTracksToPlaylist: (List<Song>) -> Unit,
    val onDeleteTracks: (List<Song>) -> Unit,
    val onShareTracks: (List<Song>) -> Unit,
    val onStartRadio: (Song) -> Unit,
    val onOpenAlbum: (Song) -> Unit,
    val onOpenArtist: (Song) -> Unit,
    val onEditMetadata: (Song) -> Unit
)

internal val LocalSongActionController = staticCompositionLocalOf<SongActionController?> { null }

internal data class SongActionCallbacks(
    val onPlayNow: () -> Unit,
    val onPlayNext: (() -> Unit)? = null,
    val onAddToQueue: (() -> Unit)? = null,
    val onAddToPlaylist: (() -> Unit)? = null,
    val onToggleMark: (() -> Unit)? = null,
    val onToggleFavorite: (() -> Unit)? = null,
    val onShare: (() -> Unit)? = null,
    val onStartRadio: (() -> Unit)? = null,
    val onOpenAlbum: (() -> Unit)? = null,
    val onOpenArtist: (() -> Unit)? = null,
    val onEditMetadata: (() -> Unit)? = null,
    val onToggleHomeVisibility: (() -> Unit)? = null,
    val onRemoveFromPlaylist: (() -> Unit)? = null,
    val onMoveToTop: (() -> Unit)? = null,
    val onRemoveFromQueue: (() -> Unit)? = null,
    val onDeleteFromDevice: (() -> Unit)? = null
)

private data class SongActionOption(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val selected: Boolean = false,
    val onClick: () -> Unit
)

@Composable
internal fun SongActionDialog(
    song: Song,
    isFavorite: Boolean,
    isMarked: Boolean,
    isHiddenFromHome: Boolean,
    onDismiss: () -> Unit,
    callbacks: SongActionCallbacks
) {
    val actionController = LocalSongActionController.current
    val artistKey = normalizeTasteKey(song.artist)
    val isLessLikeArtist = actionController?.lessLikeArtistNames?.any { normalizeTasteKey(it) == artistKey } == true
    val genres = normalizedGenreKeys(song.genre)
    val lessLikeGenreKeys = actionController?.lessLikeGenreNames?.map(::normalizeTasteKey)?.toSet().orEmpty()
    val allGenresMarkedLess = genres.isNotEmpty() && genres.all { it in lessLikeGenreKeys }
    val options = buildList {
        add(SongActionOption("Play now", "Start this track in the current context", Icons.Rounded.PlayArrow, onClick = callbacks.onPlayNow))
        callbacks.onPlayNext?.let { add(SongActionOption("Play next", "Place it after the current track", Icons.Rounded.SkipNext, onClick = it)) }
        callbacks.onMoveToTop?.let { add(SongActionOption("Move to top of queue", "Keep this queued track, but play it next", Icons.Rounded.VerticalAlignTop, onClick = it)) }
        callbacks.onAddToQueue?.let { add(SongActionOption("Add to queue", "Place it after the current song and earlier queue-next additions", Icons.AutoMirrored.Rounded.QueueMusic, onClick = it)) }
        callbacks.onAddToPlaylist?.let { add(SongActionOption("Add to playlist", "Choose one of your playlists", Icons.AutoMirrored.Rounded.PlaylistAdd, onClick = it)) }
        callbacks.onToggleMark?.let {
            add(
                SongActionOption(
                    title = if (isMarked) "Deselect song" else "Select song",
                    description = if (isMarked) "Remove this track from the multi-song selection" else "Add this track to the multi-song selection",
                    icon = if (isMarked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircle,
                    selected = isMarked,
                    onClick = it
                )
            )
        }
        callbacks.onToggleFavorite?.let {
            add(SongActionOption(if (isFavorite) "Remove from Liked" else "Add to Liked", "Update your saved tracks", if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, onClick = it))
        }
        callbacks.onShare?.let { add(SongActionOption("Share audio", "Send the local audio file to another app", Icons.Rounded.Share, onClick = it)) }
        callbacks.onOpenAlbum?.let { add(SongActionOption("Open album", "Browse the rest of this album", Icons.Rounded.Album, onClick = it)) }
        callbacks.onOpenArtist?.let { add(SongActionOption("Open artist", "Browse this artist’s tracks", Icons.Rounded.Person, onClick = it)) }
        callbacks.onStartRadio?.let { add(SongActionOption("Start radio", "Build a listening queue from this track", Icons.Rounded.Radio, onClick = it)) }
        if (actionController != null && artistKey.isNotBlank() && artistKey != "unknown artist") {
            add(
                SongActionOption(
                    title = if (isLessLikeArtist) "Show more from this artist" else "Less like this artist",
                    description = if (isLessLikeArtist) "Remove this reversible on-device preference" else "Reduce this artist in local mixes, radio, and Smart Shuffle",
                    icon = if (isLessLikeArtist) Icons.Rounded.AddCircle else Icons.Rounded.RemoveCircleOutline,
                    selected = isLessLikeArtist,
                    onClick = { actionController.onToggleLessLikeArtist(song) }
                )
            )
        }
        if (actionController != null && genres.isNotEmpty()) {
            add(
                SongActionOption(
                    title = if (allGenresMarkedLess) "Show more from these genres" else "Less like this genre",
                    description = if (allGenresMarkedLess) "Remove this reversible on-device genre preference" else "Reduce ${genres.joinToString()} in local mixes, radio, and Smart Shuffle",
                    icon = if (allGenresMarkedLess) Icons.Rounded.AddCircle else Icons.Rounded.RemoveCircleOutline,
                    selected = allGenresMarkedLess,
                    onClick = { actionController.onToggleLessLikeGenres(song) }
                )
            )
        }
        callbacks.onEditMetadata?.let { add(SongActionOption("Edit local tags", "Correct local tags and ReplayGain for this device", Icons.Rounded.Edit, onClick = it)) }
        callbacks.onToggleHomeVisibility?.let {
            add(SongActionOption(if (isHiddenFromHome) "Show on Home" else "Hide from Home", "Change Home recommendations only", if (isHiddenFromHome) Icons.Rounded.Home else Icons.Rounded.VisibilityOff, onClick = it))
        }
        callbacks.onRemoveFromPlaylist?.let { add(SongActionOption("Remove from this playlist", "Keep the audio file on this device", Icons.Rounded.RemoveCircleOutline, onClick = it)) }
        callbacks.onRemoveFromQueue?.let { add(SongActionOption("Remove from queue", "Keep the audio file on this device", Icons.Rounded.RemoveCircleOutline, onClick = it)) }
        callbacks.onDeleteFromDevice?.let {
            add(SongActionOption("Delete audio", "Remove this audio file; Android will ask you to confirm", Icons.Rounded.DeleteOutline, destructive = true, onClick = it))
        }
    }
    val outsideInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.56f))
                .clickable(interactionSource = outsideInteraction, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp)
                    .padding(horizontal = 18.dp)
                    .heightIn(min = 250.dp, max = 560.dp)
                    .clickable(interactionSource = cardInteraction, indication = null, onClick = {}),
                shape = RoundedCornerShape(26.dp),
                color = TunelystColors.Surface,
                border = BorderStroke(1.dp, TunelystColors.Text.copy(alpha = 0.08f)),
                shadowElevation = 8.dp
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(TunelystColors.Surface).padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArtwork(
                            artworkUri = song.artworkUri,
                            title = song.title,
                            modifier = Modifier.size(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Column(Modifier.weight(1f).padding(start = 11.dp, end = 8.dp)) {
                            Text("SONG ACTIONS", color = TunelystColors.Primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                            Text(song.title, color = TunelystColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                            Text(song.artist, color = TunelystColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                        }
                        FilledTonalIconButton(
                            onClick = onDismiss,
                            shape = CircleShape,
                            modifier = Modifier.size(40.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = TunelystColors.Text.copy(alpha = 0.09f),
                                contentColor = TunelystColors.Text
                            )
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close song actions", modifier = Modifier.size(19.dp))
                        }
                    }
                    Column(
                        modifier = Modifier
                            .heightIn(max = 455.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        options.forEach { option ->
                            SongActionListItem(option) {
                                onDismiss()
                                option.onClick()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongActionListItem(option: SongActionOption, onAction: () -> Unit) {
    val accent = if (option.destructive) TunelystColors.Rose else TunelystColors.Primary
    val container = when {
        option.destructive -> TunelystColors.Rose.copy(alpha = 0.13f)
        option.selected -> TunelystColors.Primary.copy(alpha = 0.22f)
        else -> TunelystColors.Surface
    }
    FilledTonalButton(
        onClick = onAction,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .semantics {
                role = Role.Button
                contentDescription = "${option.title}. ${option.description}"
            },
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = container,
            contentColor = if (option.destructive) TunelystColors.Rose else TunelystColors.Text
        ),
        border = if (option.selected) BorderStroke(1.dp, TunelystColors.Primary.copy(alpha = 0.58f)) else null,
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp)
    ) {
        Icon(option.icon, contentDescription = null, tint = accent, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            option.title,
            modifier = Modifier.weight(1f),
            color = if (option.destructive) TunelystColors.Rose else TunelystColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = accent.copy(alpha = 0.72f), modifier = Modifier.size(19.dp))
    }
}

@Composable
internal fun SelectedSongsActionCard(
    count: Int,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        color = TunelystColors.Surface,
        border = BorderStroke(1.dp, TunelystColors.Text.copy(alpha = 0.08f)),
        shadowElevation = 0.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 7.dp, end = 7.dp, bottom = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 7.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$count selected", color = TunelystColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.semantics {
                        role = Role.Button
                        contentDescription = "Clear selected songs"
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Clear", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BatchActionButton("Play", Icons.Rounded.PlayArrow, TunelystColors.Primary, onPlay, Modifier.weight(1f))
                BatchActionButton("Next", Icons.Rounded.SkipNext, TunelystColors.Primary, onPlayNext, Modifier.weight(1f))
                BatchActionButton("Queue", Icons.AutoMirrored.Rounded.QueueMusic, TunelystColors.Primary, onAddToQueue, Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BatchActionButton("Playlist", Icons.AutoMirrored.Rounded.PlaylistAdd, TunelystColors.Primary, onAddToPlaylist, Modifier.weight(1f))
                BatchActionButton("Share", Icons.Rounded.Share, TunelystColors.Primary, onShare, Modifier.weight(1f))
                BatchActionButton("Delete", Icons.Rounded.DeleteOutline, TunelystColors.Rose, onDelete, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BatchActionButton(
    title: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                role = Role.Button
                contentDescription = "$title selected songs"
            },
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = accent.copy(alpha = 0.15f),
            contentColor = accent
        ),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

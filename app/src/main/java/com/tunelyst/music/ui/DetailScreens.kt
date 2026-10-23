package com.tunelyst.music.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunelyst.music.data.Playlist
import com.tunelyst.music.data.Song

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
internal fun ImmersiveDetailArtworkHeader(
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

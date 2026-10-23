package com.tunelyst.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunelyst.music.data.LibraryCleanupAnalysis
import com.tunelyst.music.data.Playlist
import com.tunelyst.music.data.SavedQueue
import com.tunelyst.music.data.SmartPlaylist
import com.tunelyst.music.data.Song
import com.tunelyst.music.data.analyzeLibraryCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

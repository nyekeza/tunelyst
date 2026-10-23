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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.tunelyst.music.data.Playlist
import com.tunelyst.music.data.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

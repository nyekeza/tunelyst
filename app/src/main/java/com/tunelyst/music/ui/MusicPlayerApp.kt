package com.tunelyst.music.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.tunelyst.music.data.Song

import kotlinx.coroutines.launch


@Composable
fun TunelystMusicApp(viewModel: MusicViewModel) {
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    TunelystTheme(
        themeMode = appSettings.themeMode,
        highContrastEnabled = appSettings.highContrastEnabled,
        reducedMotionEnabled = appSettings.reducedMotionEnabled
    ) {
        val context = LocalContext.current
        val view = LocalView.current
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        val palette = LocalTunelystPalette.current
        val motionEnabled = LocalTunelystMotionEnabled.current
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                val useDarkSystemIcons = palette.isLight
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = useDarkSystemIcons
                    isAppearanceLightNavigationBars = useDarkSystemIcons
                }
            }
        }
        val library by viewModel.uiState.collectAsStateWithLifecycle()
        val currentLibrarySongs by rememberUpdatedState(library.songs)
        val playback by viewModel.playbackState.collectAsStateWithLifecycle()
        val openNowPlayingRequest by viewModel.openNowPlayingRequest.collectAsStateWithLifecycle()
        val playerConnectionError by viewModel.playerConnectionError.collectAsStateWithLifecycle()
        val sleepMinutes by viewModel.sleepTimerMinutes.collectAsStateWithLifecycle()
        val sleepFadeSeconds by viewModel.sleepTimerFadeOutSeconds.collectAsStateWithLifecycle()
        val audioSettings by viewModel.audioSettings.collectAsStateWithLifecycle()
        val deviceDeleteMessage by viewModel.deviceDeleteMessage.collectAsStateWithLifecycle()
        val titleFixMessage by viewModel.titleFixMessage.collectAsStateWithLifecycle()
        val embeddedTagWriteMessage by viewModel.embeddedTagWriteMessage.collectAsStateWithLifecycle()
        val appSnackbarHostState = remember { SnackbarHostState() }
        val shouldPromptForSmartShuffle = playback.isEnded && playback.promptForSmartShuffleOnCompletion
        AppSnackbarEffects(
            viewModel = viewModel,
            embeddedTagWriteMessage = embeddedTagWriteMessage,
            playerConnectionError = playerConnectionError,
            shouldPromptForSmartShuffle = shouldPromptForSmartShuffle,
            currentLibrarySongs = currentLibrarySongs,
            snackbarHostState = appSnackbarHostState
        )

        var hasAudioPermission by remember { mutableStateOf(hasAudioPermission(context)) }
        var hasAskedAudioPermission by rememberSaveable { mutableStateOf(false) }
        var deviceDeleteConfirmation by rememberSaveable(stateSaver = pendingDeviceDeletionSaver) { mutableStateOf<PendingDeviceAudioDeletion?>(null) }
        var activeDeviceDeletion by rememberSaveable(stateSaver = pendingDeviceDeletionSaver) { mutableStateOf<PendingDeviceAudioDeletion?>(null) }
        var deviceDeleteFeedback by rememberSaveable { mutableStateOf<String?>(null) }
        var activeTitleFixes by rememberSaveable(stateSaver = titleFixRequestsSaver) { mutableStateOf(emptyList<TitleFixRequest>()) }
        var pendingTagWriteSongId by rememberSaveable { mutableStateOf<Long?>(null) }
        var pendingBackupImportUri by rememberSaveable { mutableStateOf<Uri?>(null) }
        val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasAskedAudioPermission = true
            hasAudioPermission = hasAudioPermission(context)
        }
        val deviceDeleteApprovalLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            viewModel.onDeviceDeleteAuthorizationResult(result.resultCode == Activity.RESULT_OK)
        }
        val deviceRenameApprovalLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            viewModel.onDeviceRenameAuthorizationResult(result.resultCode == Activity.RESULT_OK)
        }
        val deviceTagWriteApprovalLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            viewModel.onDeviceTagWriteAuthorizationResult(result.resultCode == Activity.RESULT_OK)
        }
        SideEffect {
            viewModel.attachDeviceDeleteAuthorizationLauncher { request -> deviceDeleteApprovalLauncher.launch(request) }
            viewModel.attachDeviceRenameAuthorizationLauncher { request -> deviceRenameApprovalLauncher.launch(request) }
            viewModel.attachDeviceTagWriteAuthorizationLauncher { request -> deviceTagWriteApprovalLauncher.launch(request) }
        }

        fun startDeviceDeletion(request: PendingDeviceAudioDeletion) {
            activeDeviceDeletion = null
            viewModel.startDeviceAudioDeletion(request.uriStrings, request.skippedCount)
        }

        val legacyDeletePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val request = activeDeviceDeletion
            if (request != null) {
                if (granted) startDeviceDeletion(request) else {
                    activeDeviceDeletion = null
                    deviceDeleteFeedback = "Storage permission is required to delete audio on this Android version."
                }
            }
        }
        val legacyTitleRenamePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val fixes = activeTitleFixes
            activeTitleFixes = emptyList()
            if (fixes.isNotEmpty()) viewModel.startSuggestedTitleFixes(fixes, legacyStoragePermissionGranted = granted)
        }
        val legacyTagWritePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val songId = pendingTagWriteSongId
            pendingTagWriteSongId = null
            if (songId != null) {
                if (granted) viewModel.startDeviceAudioTagWrite(songId)
                else viewModel.reportDeviceTagWritePermissionDenied()
            }
        }

        fun confirmSuggestedTitleFixes(fixes: List<TitleFixRequest>) {
            val uniqueFixes = fixes.distinctBy(TitleFixRequest::songId)
            if (uniqueFixes.isEmpty()) return
            val legacyDeviceWriteNeeded = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                uniqueFixes.any { it.mediaUriString != null }
            if (legacyDeviceWriteNeeded &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                activeTitleFixes = uniqueFixes
                legacyTitleRenamePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                viewModel.startSuggestedTitleFixes(uniqueFixes)
            }
        }

        fun makeDeviceDeletionRequest(description: String, songs: List<Song>): PendingDeviceAudioDeletion? {
            val uniqueSongs = songs.distinctBy(Song::id)
            val deletable = uniqueSongs.filter { song ->
                song.id >= 0L && song.uri.scheme == "content" && song.uri.authority == "media"
            }
            if (deletable.isEmpty()) {
                deviceDeleteFeedback = "These shared or unsupported tracks cannot be permanently deleted through Android's media library."
                return null
            }
            return PendingDeviceAudioDeletion(
                description = description,
                uriStrings = deletable.map { it.uri.toString() },
                skippedCount = uniqueSongs.size - deletable.size
            )
        }

        fun requestDeviceDeletion(description: String, songs: List<Song>) {
            deviceDeleteFeedback = null
            deviceDeleteConfirmation = makeDeviceDeletionRequest(description, songs)
        }

        fun confirmDeviceDeletion(request: PendingDeviceAudioDeletion) {
            deviceDeleteConfirmation = null
            activeDeviceDeletion = request
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                legacyDeletePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                startDeviceDeletion(request)
            }
        }

        fun confirmCleanupDuplicateDeletion(description: String, songs: List<Song>) {
            deviceDeleteFeedback = null
            makeDeviceDeletionRequest(description, songs)?.let(::confirmDeviceDeletion)
        }
        val exportBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { selectedUri ->
                runCatching { context.contentResolver.takePersistableUriPermission(selectedUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                viewModel.exportBackup(selectedUri)
            }
        }
        val importBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { selectedUri ->
                runCatching { context.contentResolver.takePersistableUriPermission(selectedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                pendingBackupImportUri = selectedUri
            }
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(hasAudioPermission) {
            if (hasAudioPermission) viewModel.ensureLibraryLoaded()
        }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasAudioPermission = hasAudioPermission(context)
                    if (hasAudioPermission) viewModel.ensureLibraryLoaded()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        if (!hasAudioPermission) {
            PermissionScreen(
                onRequestPermission = {
                    hasAskedAudioPermission = true
                    audioPermissionLauncher.launch(arrayOf(audioPermissionForDevice()))
                },
                showOpenSettings = hasAskedAudioPermission,
                onOpenSettings = {
                    (context as? Activity)?.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }
            )
        } else {
            var markedSongIds by remember { mutableStateOf(emptySet<Long>()) }
            val markedTracks = library.songs.filter { it.id in markedSongIds }
            val alphabeticalLibraryQueue = remember(library.songs) {
                library.songs.sortedWith(compareBy<Song> { it.title.trim().lowercase() }.thenBy { it.artist.trim().lowercase() }.thenBy { it.album.trim().lowercase() })
            }
            LaunchedEffect(library.songs) {
                val existingIds = library.songs.mapTo(mutableSetOf(), Song::id)
                markedSongIds = markedSongIds.intersect(existingIds)
            }
            var selectedTab by rememberSaveable { mutableStateOf(AppTab.HOME) }
            val homeDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val drawerScope = rememberCoroutineScope()
            var selectedLibrarySection by rememberSaveable { mutableStateOf(0) }
            var selectedPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
            var selectedFolderPath by rememberSaveable { mutableStateOf<String?>(null) }
            var selectedAlbumSongId by rememberSaveable { mutableStateOf<Long?>(null) }
            var skipAlbumEntryTransition by remember { mutableStateOf<String?>(null) }
            var selectedArtistName by rememberSaveable { mutableStateOf<String?>(null) }
            var playerExpanded by rememberSaveable { mutableStateOf(false) }
            var pendingAddSongIds by remember { mutableStateOf(emptyList<Long>()) }
            var pendingUnsupportedPlaylistCount by remember { mutableStateOf(0) }
            var pickerOpen by rememberSaveable { mutableStateOf(false) }
            var createPlaylistOpen by rememberSaveable { mutableStateOf(false) }
            var playlistToDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
            var editingSongId by rememberSaveable { mutableStateOf<Long?>(null) }
            var saveQueueDialogOpen by rememberSaveable { mutableStateOf(false) }

            fun navigateFromHomeDrawer(destination: AppTab) {
                drawerScope.launch {
                    homeDrawerState.close()
                    selectedTab = destination
                    selectedPlaylistId = null
                    selectedFolderPath = null
                    selectedArtistName = null
                    selectedAlbumSongId = null
                }
            }

            val selectedPlaylist = library.playlists.firstOrNull { it.id == selectedPlaylistId }
            val currentSong = playback.displaySong
            val playerArtworkPalette = rememberArtworkPalette(
                currentSong?.artworkUri,
                currentSong?.title ?: "home"
            )

            fun closePlayer() {
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
                playerExpanded = false
            }

            fun openNowPlaying() {
                // Close Search's input method before the fullscreen player appears; window focus
                // restoration on dismissal must not reopen the keyboard behind the player.
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                playerExpanded = true
            }

            LaunchedEffect(openNowPlayingRequest) {
                if (openNowPlayingRequest > 0L) openNowPlaying()
            }

            fun openArtist(artist: String) {
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
                selectedTab = AppTab.LIBRARY
                selectedLibrarySection = 2
                selectedPlaylistId = null
                selectedFolderPath = null
                selectedAlbumSongId = null
                selectedArtistName = artist
                playerExpanded = false
            }

            fun openAlbum(song: Song) {
                if (library.songs.none { it.id == song.id }) return
                val openedFromNowPlaying = playerExpanded
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
                selectedTab = AppTab.LIBRARY
                selectedLibrarySection = 1
                selectedPlaylistId = null
                selectedFolderPath = null
                selectedArtistName = null
                selectedAlbumSongId = song.id
                skipAlbumEntryTransition = if (openedFromNowPlaying) "album:${song.id}" else null
                playerExpanded = false
            }

            fun openFolder(folderPath: String) {
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
                selectedTab = AppTab.LIBRARY
                selectedLibrarySection = 3
                selectedPlaylistId = null
                selectedArtistName = null
                selectedAlbumSongId = null
                selectedFolderPath = folderPath
                playerExpanded = false
            }

            BackHandler(enabled = playerExpanded || selectedArtistName != null || selectedAlbumSongId != null || selectedPlaylistId != null || selectedFolderPath != null || selectedTab != AppTab.HOME) {
                when {
                    playerExpanded -> closePlayer()
                    selectedArtistName != null -> selectedArtistName = null
                    selectedAlbumSongId != null -> selectedAlbumSongId = null
                    selectedPlaylistId != null -> selectedPlaylistId = null
                    selectedFolderPath != null -> selectedFolderPath = null
                    else -> selectedTab = AppTab.HOME
                }
            }

            fun showPlaylistAddFeedback(skippedCount: Int) {
                val message = if (skippedCount > 0) {
                    "$skippedCount shared or unavailable ${if (skippedCount == 1) "track could" else "tracks could"} not be saved to playlists."
                } else {
                    "Added to playlist."
                }
                drawerScope.launch { appSnackbarHostState.showSnackbar(message) }
            }

            fun openPlaylistPicker(songs: List<Song>) {
                val distinctSongs = songs.distinctBy(Song::id)
                val persistentSongs = distinctSongs.filter { it.id >= 0L }
                pendingAddSongIds = persistentSongs.map(Song::id)
                pendingUnsupportedPlaylistCount = distinctSongs.size - persistentSongs.size
                pickerOpen = pendingAddSongIds.isNotEmpty()
                if (!pickerOpen && pendingUnsupportedPlaylistCount > 0) {
                    showPlaylistAddFeedback(pendingUnsupportedPlaylistCount)
                    pendingUnsupportedPlaylistCount = 0
                }
            }

            fun openPlaylistPicker(song: Song) {
                openPlaylistPicker(listOf(song))
            }

            fun shareTracks(songs: List<Song>) {
                val tracks = songs.distinctBy(Song::uri)
                val uris = tracks.map(Song::uri)
                if (uris.isEmpty()) return
                val sendIntent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "audio/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (uris.size == 1) {
                        putExtra(Intent.EXTRA_STREAM, uris.first())
                    } else {
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    }
                    clipData = ClipData.newUri(context.contentResolver, tracks.first().title, uris.first()).also { data ->
                        uris.drop(1).forEach { data.addItem(ClipData.Item(it)) }
                    }
                }
                runCatching {
                    context.startActivity(Intent.createChooser(sendIntent, if (uris.size == 1) "Share audio" else "Share audio tracks"))
                }
            }

            val songActionController = SongActionController(
                markedSongIds = markedSongIds,
                favoriteSongIds = library.favorites,
                hiddenHomeSongIds = library.hiddenHomeSongIds,
                lessLikeArtistNames = library.lessLikeArtistNames,
                lessLikeGenreNames = library.lessLikeGenreNames,
                playNextSongIds = playback.playNextSongIds,
                queuedSongIds = playback.queuedSongIds,
                currentlyPlayingSongId = playback.displaySong?.id,
                isPlaying = playback.isPlaying,
                onToggleLessLikeArtist = viewModel::toggleLessLikeArtist,
                onToggleLessLikeGenres = viewModel::toggleLessLikeGenres,
                onToggleMark = { song -> markedSongIds = if (song.id in markedSongIds) markedSongIds - song.id else markedSongIds + song.id },
                onToggleHomeVisibility = { song -> viewModel.setSongHiddenFromHome(song.id, song.id !in library.hiddenHomeSongIds) },
                onPlay = { song ->
                    val queue = alphabeticalLibraryQueue
                    val index = queue.indexOfFirst { it.id == song.id }
                    if (index >= 0) viewModel.playFrom(queue, index, preserveOrder = true)
                    else viewModel.playFrom(listOf(song), 0, preserveOrder = true)
                },
                onPlayNext = viewModel::playNext,
                onAddToQueue = viewModel::playNext,
                onAddToPlaylist = { song -> openPlaylistPicker(song) },
                onToggleFavorite = { song -> viewModel.toggleFavorite(song.id) },
                onDeleteFromDevice = { song -> requestDeviceDeletion("‘${song.title}’", listOf(song)) },
                onShare = { song -> shareTracks(listOf(song)) },
                onPlayTracks = { tracks -> if (tracks.isNotEmpty()) viewModel.playFrom(tracks, 0, preserveOrder = true) },
                onPlayNextTracks = { tracks -> tracks.distinctBy(Song::id).forEach(viewModel::playNext) },
                onAddTracksToQueue = { tracks -> tracks.distinctBy(Song::id).forEach(viewModel::playNext) },
                onAddTracksToPlaylist = { tracks -> openPlaylistPicker(tracks) },
                onDeleteTracks = { tracks ->
                    val uniqueTracks = tracks.distinctBy(Song::id)
                    if (uniqueTracks.isNotEmpty()) requestDeviceDeletion(
                        if (uniqueTracks.size == 1) "‘${uniqueTracks.first().title}’" else "the selected ${uniqueTracks.size} tracks",
                        uniqueTracks
                    )
                },
                onShareTracks = ::shareTracks,
                onStartRadio = viewModel::startRadio,
                onOpenAlbum = ::openAlbum,
                onOpenArtist = { song -> openArtist(song.artist) },
                onEditMetadata = { song -> editingSongId = song.id }
            )

            val destination = when {
                selectedAlbumSongId != null -> "album:$selectedAlbumSongId"
                selectedArtistName != null -> "artist:$selectedArtistName"
                selectedFolderPath != null -> "folder:$selectedFolderPath"
                selectedPlaylist != null -> "playlist:${selectedPlaylist.id}"
                else -> selectedTab.name
            }
            LaunchedEffect(destination, skipAlbumEntryTransition) {
                val pendingImmediateAlbum = skipAlbumEntryTransition
                if (pendingImmediateAlbum != null) {
                    if (destination == pendingImmediateAlbum) withFrameNanos { }
                    if (skipAlbumEntryTransition == pendingImmediateAlbum) {
                        skipAlbumEntryTransition = null
                    }
                }
            }
            val mainTabArtworkBlend = destination == AppTab.HOME.name ||
                destination == AppTab.SEARCH.name || destination == AppTab.LIBRARY.name ||
                destination == AppTab.LIKED.name
            val immersiveArtworkDestination = destination == AppTab.HOME.name ||
                destination.startsWith("album:") || destination.startsWith("artist:") ||
                destination.startsWith("playlist:") || destination.startsWith("folder:")
            SideEffect {
                (view.context as? Activity)?.window?.let { window ->
                    WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
                        if (immersiveArtworkDestination) false else palette.isLight
                }
            }
            CompositionLocalProvider(LocalSongActionController provides songActionController) {
            TunelystMainDrawer(
                drawerState = homeDrawerState,
                gesturesEnabled = selectedTab == AppTab.HOME && selectedPlaylistId == null &&
                    selectedFolderPath == null && selectedArtistName == null && selectedAlbumSongId == null,
                onOpenSettings = { navigateFromHomeDrawer(AppTab.SETTINGS) },
                onOpenListeningStats = { navigateFromHomeDrawer(AppTab.LISTENING_STATS) }
            ) {
            Box(Modifier.fillMaxSize()) {
                GlobalArtworkBackdrop(
                    artworkUri = currentSong?.artworkUri,
                    dynamicBlurEnabled = appSettings.dynamicBlurEnabled,
                    blendArtworkAtBottom = mainTabArtworkBlend
                )
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentWindowInsets = if (immersiveArtworkDestination) {
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                    } else WindowInsets.safeDrawing,
                    snackbarHost = {
                        if (!playerExpanded || currentSong == null) SnackbarHost(appSnackbarHostState)
                    },
                    bottomBar = {
                        Column {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = markedTracks.isNotEmpty(),
                                enter = if (LocalTunelystMotionEnabled.current) {
                                    androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically()
                                } else androidx.compose.animation.EnterTransition.None,
                                exit = if (LocalTunelystMotionEnabled.current) {
                                    androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
                                } else androidx.compose.animation.ExitTransition.None,
                                label = "selected-actions-enter"
                            ) {
                                SelectedSongsActionCard(
                                    count = markedTracks.size,
                                    onPlay = { songActionController.onPlayTracks(markedTracks) },
                                    onPlayNext = { songActionController.onPlayNextTracks(markedTracks) },
                                    onAddToQueue = { songActionController.onAddTracksToQueue(markedTracks) },
                                    onAddToPlaylist = { songActionController.onAddTracksToPlaylist(markedTracks) },
                                    onShare = { songActionController.onShareTracks(markedTracks) },
                                    onDelete = { songActionController.onDeleteTracks(markedTracks) },
                                    onClear = { markedSongIds = emptySet() }
                                )
                            }
                            currentSong?.let { song ->
                                MiniPlayer(
                                    song = song,
                                    isPlaying = playback.isPlaying,
                                    positionMs = playback.positionMs,
                                    durationMs = playback.durationMs,
                                    onOpen = ::openNowPlaying,
                                    onTogglePlayback = viewModel::togglePlayPause,
                                    onNext = viewModel::skipNext,
                                    blendArtworkBackdrop = mainTabArtworkBlend
                                )
                            }
                            NavigationBar(
                                modifier = Modifier.height(58.dp),
                                containerColor = TunelystColors.Background.copy(
                                    alpha = if (mainTabArtworkBlend) {
                                        if (palette.isLight) 0.78f else 0.58f
                                    } else 0.88f
                                ),
                                contentColor = TunelystColors.Text
                            ) {
                                NavigationBarItem(
                                    selected = (selectedTab == AppTab.HOME || selectedTab == AppTab.LISTENING_STATS) && selectedPlaylistId == null,
                                    onClick = { selectedTab = AppTab.HOME; selectedPlaylistId = null; selectedFolderPath = null; selectedArtistName = null; selectedAlbumSongId = null },
                                    icon = {
                                        ExpressiveToggleIcon(
                                            selected = (selectedTab == AppTab.HOME || selectedTab == AppTab.LISTENING_STATS) && selectedPlaylistId == null,
                                            selectedIcon = Icons.Rounded.Home,
                                            unselectedIcon = Icons.Rounded.Home,
                                            contentDescription = "Home",
                                            tint = LocalContentColor.current,
                                            size = 28.dp,
                                            label = "navigation-home-icon"
                                        )
                                    },
                                    label = null,
                                    colors = navItemColors()
                                )
                                NavigationBarItem(
                                    selected = selectedTab == AppTab.SEARCH && selectedPlaylistId == null,
                                    onClick = { selectedTab = AppTab.SEARCH; selectedPlaylistId = null; selectedFolderPath = null; selectedArtistName = null; selectedAlbumSongId = null },
                                    icon = {
                                        ExpressiveToggleIcon(
                                            selected = selectedTab == AppTab.SEARCH && selectedPlaylistId == null,
                                            selectedIcon = Icons.Rounded.Search,
                                            unselectedIcon = Icons.Rounded.Search,
                                            contentDescription = "Search",
                                            tint = LocalContentColor.current,
                                            size = 28.dp,
                                            label = "navigation-search-icon"
                                        )
                                    },
                                    label = null,
                                    colors = navItemColors()
                                )
                                NavigationBarItem(
                                    selected = selectedTab == AppTab.LIBRARY,
                                    onClick = { selectedTab = AppTab.LIBRARY; selectedLibrarySection = 0; selectedPlaylistId = null; selectedFolderPath = null; selectedArtistName = null; selectedAlbumSongId = null },
                                    icon = { MusicLibraryGlyph(selected = selectedTab == AppTab.LIBRARY) },
                                    label = null,
                                    colors = navItemColors()
                                )
                                NavigationBarItem(
                                    selected = selectedTab == AppTab.LIKED,
                                    onClick = { selectedTab = AppTab.LIKED; selectedLibrarySection = 7; selectedPlaylistId = null; selectedFolderPath = null; selectedArtistName = null; selectedAlbumSongId = null },
                                    icon = {
                                        ExpressiveToggleIcon(
                                            selected = selectedTab == AppTab.LIKED,
                                            selectedIcon = Icons.Rounded.Favorite,
                                            unselectedIcon = Icons.Rounded.FavoriteBorder,
                                            contentDescription = "Liked",
                                            tint = LocalContentColor.current,
                                            size = 28.dp,
                                            label = "navigation-liked-icon"
                                        )
                                    },
                                    label = null,
                                    colors = navItemColors()
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    androidx.compose.animation.AnimatedContent(
                        targetState = destination,
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        transitionSpec = {
                            if (!motionEnabled || targetState == skipAlbumEntryTransition) {
                                androidx.compose.animation.EnterTransition.None togetherWith
                                    androidx.compose.animation.ExitTransition.None
                            } else {
                                (androidx.compose.animation.fadeIn(animationSpec = TunelystMotionSpecs.defaultEffects<Float>()) +
                                    androidx.compose.animation.slideInHorizontally(
                                        initialOffsetX = { fullWidth -> fullWidth / 14 },
                                        animationSpec = TunelystMotionSpecs.fastSpatial<IntOffset>()
                                    )) togetherWith
                                    (androidx.compose.animation.fadeOut(animationSpec = TunelystMotionSpecs.fastEffects<Float>()) +
                                        androidx.compose.animation.slideOutHorizontally(
                                            targetOffsetX = { fullWidth -> -fullWidth / 28 },
                                            animationSpec = TunelystMotionSpecs.fastSpatial<IntOffset>()
                                        ))
                            }
                        },
                        label = "destination-motion"
                    ) { destinationKey ->
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val pageWidth = if (immersiveArtworkDestination) maxWidth else minOf(maxWidth, 760.dp)
                            Box(Modifier.width(pageWidth).fillMaxHeight().align(androidx.compose.ui.Alignment.TopCenter)) {
                        val displayedPlaylistId = destinationKey
                            .removePrefix("playlist:")
                            .takeIf { destinationKey.startsWith("playlist:") }
                        val displayedPlaylist = library.playlists.firstOrNull { it.id == displayedPlaylistId }
                        val displayedFolderPath = destinationKey.removePrefix("folder:").takeIf { destinationKey.startsWith("folder:") }
                        val displayedAlbumSongId = destinationKey.removePrefix("album:").toLongOrNull().takeIf { destinationKey.startsWith("album:") }
                        val displayedAlbum = displayedAlbumSongId?.let { albumGroupFor(library.songs, it) }
                        val displayedArtistName = destinationKey
                            .removePrefix("artist:")
                            .takeIf { destinationKey.startsWith("artist:") }
                        when {
                            displayedAlbum != null -> AlbumDetailScreen(
                                album = displayedAlbum,
                                favorites = library.favorites,
                                currentSong = currentSong,
                                isPlaying = playback.isPlaying,
                                onTogglePlayback = viewModel::togglePlayPause,
                                hiddenHomeSongIds = library.hiddenHomeSongIds,
                                onSetSongHiddenFromHome = viewModel::setSongHiddenFromHome,
                                onBack = { selectedAlbumSongId = null },
                                onPlay = { songs, index -> viewModel.playFrom(songs, index, preserveOrder = true) },
                                onToggleFavorite = viewModel::toggleFavorite,
                                onAddToPlaylist = ::openPlaylistPicker,
                                onPlayNext = viewModel::playNext,
                                onPlayLast = viewModel::playLast,
                                onOpenArtist = ::openArtist,
                                onEditSong = { editingSongId = it.id }
                            )
                            displayedArtistName != null -> ArtistDetailScreen(
                                artist = displayedArtistName,
                                allSongs = library.songs,
                                currentSong = currentSong,
                                isPlaying = playback.isPlaying,
                                onTogglePlayback = viewModel::togglePlayPause,
                                favorites = library.favorites,
                                hiddenHomeSongIds = library.hiddenHomeSongIds,
                                onSetSongHiddenFromHome = viewModel::setSongHiddenFromHome,
                                onBack = { selectedArtistName = null },
                                onPlay = { songs, index -> viewModel.playFrom(songs, index, preserveOrder = true) },
                                onToggleFavorite = viewModel::toggleFavorite,
                                onAddToPlaylist = ::openPlaylistPicker,
                                onPlayNext = viewModel::playNext,
                                onPlayLast = viewModel::playLast
                            )
                            displayedFolderPath != null -> FolderDetailScreen(
                                folderPath = displayedFolderPath,
                                songs = library.songs.filter { (it.folderPath.ifBlank { "Device root" }) == displayedFolderPath },
                                favorites = library.favorites,
                                currentSong = currentSong,
                                isPlaying = playback.isPlaying,
                                onTogglePlayback = viewModel::togglePlayPause,
                                hiddenHomeSongIds = library.hiddenHomeSongIds,
                                onSetSongHiddenFromHome = viewModel::setSongHiddenFromHome,
                                onBack = { selectedFolderPath = null },
                                onPlay = { songs, index -> viewModel.playFrom(songs, index, preserveOrder = true) },
                                onToggleFavorite = viewModel::toggleFavorite,
                                onAddToPlaylist = ::openPlaylistPicker,
                                onPlayNext = viewModel::playNext,
                                onPlayLast = viewModel::playLast,
                                onEditSong = { editingSongId = it.id }
                            )
                            displayedPlaylist != null -> PlaylistDetailScreen(
                                playlist = displayedPlaylist,
                                allSongs = library.songs,
                                favorites = library.favorites,
                                currentSong = currentSong,
                                isPlaying = playback.isPlaying,
                                onTogglePlayback = viewModel::togglePlayPause,
                                hiddenHomeSongIds = library.hiddenHomeSongIds,
                                onSetSongHiddenFromHome = viewModel::setSongHiddenFromHome,
                                onBack = { selectedPlaylistId = null },
                                onPlay = { songs, index -> viewModel.playFrom(songs, index, preserveOrder = true) },
                                onToggleFavorite = viewModel::toggleFavorite,
                                onAddToPlaylist = ::openPlaylistPicker,
                                onPlayNext = viewModel::playNext,
                                onPlayLast = viewModel::playLast,
                                onRemoveFromPlaylist = { song -> viewModel.removeSongFromPlaylist(displayedPlaylist.id, song.id) },
                                onDelete = { playlistToDeleteId = displayedPlaylist.id }
                            )
                            destinationKey == AppTab.HOME.name -> HomeScreen(
                                state = library,
                                playback = playback,
                                isSmartShuffleActive = playback.smartShuffleEnabled,
                                activeQueueSongIds = playback.queue.map(Song::id),
                                onTogglePlayback = viewModel::togglePlayPause,
                                onOpenNowPlaying = ::openNowPlaying,
                                onPlay = { songs, index -> viewModel.playFrom(songs, index, preserveOrder = true) },
                                onPlayRecommendationMix = { songs, index ->
                                    viewModel.playFrom(
                                        songs,
                                        index,
                                        preserveOrder = true,
                                        promptForSmartShuffleOnCompletion = true
                                    )
                                },
                                onSmartShuffle = { viewModel.toggleSmartShuffle(library.songs) },
                                onStartRadio = viewModel::startRadio,
                                onStopRadio = viewModel::stopRadio,
                                homeSectionOrder = appSettings.homeSectionOrder,
                                hiddenHomeSections = appSettings.hiddenHomeSections,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onAddToPlaylist = ::openPlaylistPicker,
                                onPlayNext = viewModel::playNext,
                                onPlayLast = viewModel::playLast,
                                onSetSongHiddenFromHome = viewModel::setSongHiddenFromHome,
                                onSetHomeCardHidden = viewModel::setHomeCardHidden,
                                onRescan = viewModel::loadLibrary,
                                onOpenLibrary = { selectedTab = AppTab.LIBRARY; selectedLibrarySection = 0; selectedPlaylistId = null; selectedFolderPath = null; selectedArtistName = null },
                                onOpenNavigationDrawer = { drawerScope.launch { homeDrawerState.open() } }
                            )
                            destinationKey == AppTab.SEARCH.name -> SearchScreen(
                                state = library,
                                nowPlayingVisible = playerExpanded,
                                onPlay = { songs, index -> viewModel.playFrom(songs, index, autoShuffleAtEnd = true, preserveOrder = true) },
                                onPlaySelectedSong = { song -> viewModel.playFrom(listOf(song), 0, autoShuffleAtEnd = true, preserveOrder = true) },
                                onRecordSearch = viewModel::recordSearch,
                                onClearRecentlySearched = viewModel::clearRecentlySearched,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onSetSongHiddenFromHome = viewModel::setSongHiddenFromHome,
                                onAddToPlaylist = ::openPlaylistPicker,
                                onPlayNext = viewModel::playNext,
                                onPlayLast = viewModel::playLast
                            )
                            destinationKey == AppTab.LIBRARY.name || destinationKey == AppTab.LIKED.name -> LibraryScreen(
                                state = library,
                                onOpenSettings = { selectedTab = AppTab.SETTINGS; selectedPlaylistId = null; selectedFolderPath = null; selectedArtistName = null },
                                selectedSection = if (destinationKey == AppTab.LIKED.name) 7 else selectedLibrarySection,
                                onSelectedSectionChange = { selectedLibrarySection = it },
                                onPlay = { songs, index -> viewModel.playFrom(songs, index, preserveOrder = true) },
                                onToggleFavorite = viewModel::toggleFavorite,
                                onSetSongHiddenFromHome = viewModel::setSongHiddenFromHome,
                                onAddToPlaylist = ::openPlaylistPicker,
                                onAddSelectedToPlaylist = ::openPlaylistPicker,
                                onDeleteDeviceAudio = ::requestDeviceDeletion,
                                onConfirmDeleteDeviceAudio = ::confirmCleanupDuplicateDeletion,
                                onPlayNext = viewModel::playNext,
                                onPlayLast = viewModel::playLast,
                                onOpenPlaylist = { playlist -> selectedArtistName = null; selectedFolderPath = null; selectedAlbumSongId = null; selectedPlaylistId = playlist.id },
                                onOpenArtist = ::openArtist,
                                onOpenAlbum = ::openAlbum,
                                onOpenFolder = ::openFolder,
                                onEditSong = { editingSongId = it.id },
                                onApplyTitleFixes = ::confirmSuggestedTitleFixes,
                                onPlaySmartPlaylist = { kind, value -> viewModel.playFrom(viewModel.songsForSmartPlaylist(kind, value), 0, preserveOrder = true) },
                                onSaveSmartPlaylist = viewModel::createSmartPlaylist,
                                onDeleteSmartPlaylist = viewModel::deleteSmartPlaylist,
                                onPlaySavedQueue = viewModel::playSavedQueue,
                                onDeleteSavedQueue = viewModel::deleteSavedQueue,
                                onCreatePlaylist = { createPlaylistOpen = true },
                                onRescan = viewModel::loadLibrary
                            )
                            destinationKey == AppTab.LISTENING_STATS.name -> ListeningStatsScreen(
                                state = library,
                                onBack = { selectedTab = AppTab.HOME },
                                onPlay = { songs, index -> viewModel.playFrom(songs, index, preserveOrder = true) },
                                onToggleFavorite = viewModel::toggleFavorite,
                                onAddToPlaylist = ::openPlaylistPicker,
                                onPlayNext = viewModel::playNext,
                                onPlayLast = viewModel::playLast
                            )
                            else -> SettingsScreen(
                                appSettings = appSettings,
                                audioSettings = audioSettings,
                                songCount = library.songs.size,
                                isLoading = library.isLoading,
                                folders = library.allFolders,
                                excludedFolders = library.excludedFolders,
                                hiddenHomeCardIds = library.hiddenHomeCardIds,
                                removedTrackCount = library.removedTrackCount,
                                inaccessibleTrackCount = library.inaccessibleTrackCount,
                                libraryErrorMessage = library.errorMessage,
                                operationMessage = library.operationMessage,
                                onSetHomeCardHidden = viewModel::setHomeCardHidden,
                                onFolderExcluded = viewModel::setFolderExcluded,
                                onThemeModeChanged = viewModel::setThemeMode,
                                onDynamicBlurChanged = viewModel::setDynamicBlurEnabled,
                                onListenThresholdChanged = viewModel::setListenThresholdSeconds,
                                onRepeatAvoidanceChanged = viewModel::setRepeatAvoidanceLevel,
                                onEqualizerEnabledChanged = viewModel::setEqualizerEnabled,
                                onSelectEqualizerPreset = viewModel::selectEqualizerPreset,
                                onEqualizerBandLevelChanged = viewModel::setEqualizerBandLevel,
                                onCrossfadeChanged = viewModel::setCrossfadeSeconds,
                                onCrossfadeCurveChanged = viewModel::setCrossfadeCurve,
                                onCrossfadeAlbumBoundariesOnlyChanged = viewModel::setCrossfadeAlbumBoundariesOnly,
                                onManualSkipFadeChanged = viewModel::setManualSkipFadeSeconds,
                                onPlaybackSpeedChanged = viewModel::setPlaybackSpeed,
                                onPlaybackPitchChanged = viewModel::setPlaybackPitch,
                                onNormalizeVolumeChanged = viewModel::setNormalizeVolume,
                                onExportBackup = { exportBackupLauncher.launch("tunelyst-personal-data.json") },
                                onImportBackup = { importBackupLauncher.launch(arrayOf("application/json", "text/*")) },
                                onSaveQueue = { saveQueueDialogOpen = true },
                                onClearOperationMessage = viewModel::clearOperationMessage,
                                onRescanLibrary = viewModel::loadLibrary
                            )
                        }
                            }
                        }
                    }
                }
                StatusBarEdgeFade(Modifier.align(androidx.compose.ui.Alignment.TopCenter))
            }
            }

            if (playerExpanded && currentSong != null) {
                FullPlayerDialog(
                    song = currentSong,
                    playback = playback,
                    ambientColors = playerArtworkPalette,
                    isFavorite = currentSong.id in library.favorites,
                    sleepTimerMinutes = sleepMinutes,
                    sleepTimerFadeOutSeconds = sleepFadeSeconds,
                    snackbarHostState = appSnackbarHostState,
                    onDismiss = ::closePlayer,
                    onOpenArtist = ::openArtist,
                    onOpenAlbum = ::openAlbum,
                    onStartRadio = viewModel::startRadio,
                    onTogglePlayback = viewModel::togglePlayPause,
                    onPrevious = viewModel::skipPrevious,
                    onNext = viewModel::skipNext,
                    onSeek = viewModel::seekTo,
                    onToggleFavorite = { viewModel.toggleFavorite(currentSong.id) },
                    onAddToPlaylist = { openPlaylistPicker(currentSong) },
                    onToggleShuffle = viewModel::toggleShuffle,
                    onCycleRepeat = viewModel::cycleRepeatMode,
                    onPlayQueueItem = viewModel::playQueueItem,
                    onMoveQueueItem = viewModel::moveQueueItem,
                    onRemoveQueueItem = viewModel::removeQueueItem,
                    onStartSleepTimer = viewModel::startSleepTimer,
                    onCancelSleepTimer = viewModel::cancelSleepTimer
                )
            }

            if (pickerOpen) {
                PlaylistPickerDialog(
                    playlists = library.playlists,
                    onChoose = { playlist ->
                        val skippedCount = viewModel.addSongsToPlaylist(playlist.id, pendingAddSongIds)
                        pendingAddSongIds = emptyList()
                        pickerOpen = false
                        showPlaylistAddFeedback(pendingUnsupportedPlaylistCount + skippedCount)
                        pendingUnsupportedPlaylistCount = 0
                    },
                    onCreateNew = {
                        pickerOpen = false
                        createPlaylistOpen = true
                    },
                    onDismiss = {
                        pendingAddSongIds = emptyList()
                        pendingUnsupportedPlaylistCount = 0
                        pickerOpen = false
                    }
                )
            }

            if (createPlaylistOpen) {
                CreatePlaylistDialog(
                    onCreate = { name ->
                        val playlist = viewModel.createPlaylist(name)
                        val skippedCount = if (playlist != null && pendingAddSongIds.isNotEmpty()) {
                            viewModel.addSongsToPlaylist(playlist.id, pendingAddSongIds)
                        } else 0
                        if (playlist != null && pendingAddSongIds.isNotEmpty()) {
                            showPlaylistAddFeedback(pendingUnsupportedPlaylistCount + skippedCount)
                        }
                        pendingAddSongIds = emptyList()
                        pendingUnsupportedPlaylistCount = 0
                        createPlaylistOpen = false
                        pickerOpen = false
                    },
                    onDismiss = {
                        createPlaylistOpen = false
                        if (pendingAddSongIds.isNotEmpty()) pickerOpen = true
                    }
                )
            }

            val editingSong = library.songs.firstOrNull { it.id == editingSongId }
            if (editingSong != null) {
                EditSongMetadataDialog(
                    song = editingSong,
                    existingArtworkOverride = viewModel.artworkOverrideUri(editingSong.id),
                    onSave = { override ->
                        val needsLegacyWritePermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) != PackageManager.PERMISSION_GRANTED
                        if (needsLegacyWritePermission) {
                            pendingTagWriteSongId = override.songId
                            viewModel.saveSongMetadataOverride(override, startDeviceFileWrite = false)
                            legacyTagWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            viewModel.saveSongMetadataOverride(override)
                        }
                        editingSongId = null
                    },
                    onDismiss = { editingSongId = null }
                )
            }
            if (saveQueueDialogOpen) {
                SaveQueueDialog(
                    onSave = { name ->
                        val currentQueue = playback.queue.drop(playback.currentIndex.coerceAtLeast(0))
                        val hasSharedTracks = currentQueue.any { it.id < 0L }
                        val savedQueue = viewModel.saveCurrentQueue(name)
                        saveQueueDialogOpen = false
                        val message = when {
                            savedQueue != null -> "Queue saved."
                            hasSharedTracks -> "Queue not saved: shared or temporary tracks cannot be saved yet."
                            currentQueue.isEmpty() -> "Queue not saved: there are no tracks to save."
                            else -> "Queue could not be saved. Check the queue name and try again."
                        }
                        drawerScope.launch { appSnackbarHostState.showSnackbar(message) }
                    },
                    onDismiss = { saveQueueDialogOpen = false }
                )
            }

            AppFeedbackDialogs(
                viewModel = viewModel,
                backupImportUri = pendingBackupImportUri,
                onDismissBackupImport = { pendingBackupImportUri = null },
                titleFixMessage = titleFixMessage,
                deviceDeleteConfirmation = deviceDeleteConfirmation,
                onDismissDeviceDeleteConfirmation = { deviceDeleteConfirmation = null },
                onConfirmDeviceDeletion = ::confirmDeviceDeletion,
                deviceDeleteFeedback = deviceDeleteFeedback,
                onDismissDeviceDeleteFeedback = { deviceDeleteFeedback = null },
                deviceDeleteMessage = deviceDeleteMessage,
                lastPlaybackError = playback.lastPlaybackError,
                playlistToDeleteId = playlistToDeleteId,
                playlists = library.playlists,
                onDismissPlaylistDelete = { playlistToDeleteId = null },
                onDeletePlaylist = { playlistId ->
                    viewModel.deletePlaylist(playlistId)
                    playlistToDeleteId = null
                    selectedPlaylistId = null
                }
            )
            }
        }
    }
}

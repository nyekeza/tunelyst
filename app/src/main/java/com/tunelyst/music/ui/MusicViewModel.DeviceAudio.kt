@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.tunelyst.music.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.flow.map

internal fun MusicViewModel.attachDeviceDeleteAuthorizationLauncherImpl(launcher: (IntentSenderRequest) -> Unit) {
    deviceDeleteAuthorizationLauncher = launcher
    deviceDeleteCoordinator?.updateAuthorizationLauncher(launcher)
}

internal fun MusicViewModel.onDeviceDeleteAuthorizationResultImpl(approved: Boolean) {
    deviceDeleteCoordinator?.onAuthorizationResult(approved)
}

internal fun MusicViewModel.startDeviceAudioDeletionImpl(uriStrings: List<String>, skippedCount: Int) {
    deviceDeleteCoordinator?.cancel()
    _deviceDeleteMessage.value = null
    val uris = uriStrings.map(Uri::parse).distinct()
    val coordinator = DeviceAudioDeleteCoordinator(
        resolver = getApplication<Application>().contentResolver,
        launchAuthorization = { request ->
            deviceDeleteAuthorizationLauncher?.invoke(request)
                ?: error("Android approval launcher is not attached.")
        },
        onComplete = { result ->
            deviceDeleteCoordinator = null
            loadLibrary()
            _deviceDeleteMessage.value = when {
                result.cancelled && result.deletedCount == 0 -> "Deletion was cancelled. No audio files were removed."
                result.cancelled -> "Deletion was cancelled after ${result.deletedCount} audio ${if (result.deletedCount == 1) "file was" else "files were"} removed."
                result.failedCount == 0 && skippedCount == 0 -> "Permanently deleted ${result.deletedCount} audio ${if (result.deletedCount == 1) "file" else "files"} from this device."
                else -> buildString {
                    append("Deleted ${result.deletedCount} audio ${if (result.deletedCount == 1) "file" else "files"}.")
                    if (result.failedCount > 0) append(" ${result.failedCount} could not be deleted or verified.")
                    if (skippedCount > 0) append(" $skippedCount shared or unsupported ${if (skippedCount == 1) "track was" else "tracks were"} skipped.")
                }
            }
        }
    )
    deviceDeleteCoordinator = coordinator
    coordinator.start(uris)
}

internal fun MusicViewModel.clearDeviceDeleteMessageImpl() {
    _deviceDeleteMessage.value = null
}

internal fun MusicViewModel.attachDeviceRenameAuthorizationLauncherImpl(launcher: (IntentSenderRequest) -> Unit) {
    deviceRenameAuthorizationLauncher = launcher
    deviceRenameCoordinator?.updateAuthorizationLauncher(launcher)
}

internal fun MusicViewModel.onDeviceRenameAuthorizationResultImpl(approved: Boolean) {
    deviceRenameCoordinator?.onAuthorizationResult(approved)
}

internal fun MusicViewModel.attachDeviceTagWriteAuthorizationLauncherImpl(launcher: (IntentSenderRequest) -> Unit) {
    deviceTagWriteAuthorizationLauncher = launcher
    deviceTagWriteCoordinator?.updateAuthorizationLauncher(launcher)
}

internal fun MusicViewModel.onDeviceTagWriteAuthorizationResultImpl(approved: Boolean) {
    deviceTagWriteCoordinator?.onAuthorizationResult(approved)
}

internal fun MusicViewModel.reportDeviceTagWritePermissionDeniedImpl() {
    _embeddedTagWriteMessage.value =
        "Correction saved in Tunelyst only. Android storage permission was denied, so the audio file wasn't changed."
}

internal fun MusicViewModel.startDeviceAudioTagWriteImpl(songId: Long) {
    val song = _uiState.value.songs.firstOrNull { it.id == songId }
    val override = store.metadataOverride(songId)
    if (song == null || override == null) {
        _embeddedTagWriteMessage.value =
            "Correction saved in Tunelyst only. The device audio file couldn't be found for tag writing."
        return
    }
    if (song.id < 0L || song.uri.scheme != "content" || song.uri.authority != "media") {
        _embeddedTagWriteMessage.value =
            "Correction saved in Tunelyst only. This track isn't an Android MediaStore audio item, so its device file can't be written directly."
        return
    }

    val originalSong = rawCatalogSongs.firstOrNull { it.id == songId }
    val artworkUri = override.artworkUri
        ?.takeIf { originalSong == null || it != originalSong.artworkUri?.toString() }
        ?.let(Uri::parse)

    deviceTagWriteCoordinator?.cancel()
    _embeddedTagWriteMessage.value = "Writing tag correction to the device audio file…"
    lateinit var coordinator: DeviceAudioTagWriteCoordinator
    coordinator = DeviceAudioTagWriteCoordinator(
        resolver = getApplication<Application>().contentResolver,
        song = song,
        catalogSong = originalSong,
        metadataOverride = override,
        replacementArtworkUri = artworkUri,
        launchAuthorization = { request ->
            deviceTagWriteAuthorizationLauncher?.invoke(request)
                ?: error("Android write-approval launcher is not attached.")
        },
        onComplete = { outcome ->
            if (deviceTagWriteCoordinator === coordinator) deviceTagWriteCoordinator = null
            _embeddedTagWriteMessage.value = outcome.message
            if (outcome.fileChanged) loadLibrary()
        }
    )
    deviceTagWriteCoordinator = coordinator
    coordinator.start()
}

internal fun MusicViewModel.clearEmbeddedTagWriteMessageImpl(expectedMessage: String) {
    if (_embeddedTagWriteMessage.value == expectedMessage) _embeddedTagWriteMessage.value = null
}

internal fun MusicViewModel.startSuggestedTitleFixesImpl(fixes: List<TitleFixRequest>, legacyStoragePermissionGranted: Boolean = true) {
    val uniqueFixes = fixes.distinctBy(TitleFixRequest::songId)
    if (uniqueFixes.isEmpty()) return
    deviceRenameCoordinator?.cancel()
    _titleFixMessage.value = null
    val legacyDeviceWriteNeeded = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        uniqueFixes.any { it.mediaUriString != null }
    if (legacyDeviceWriteNeeded && !legacyStoragePermissionGranted) {
        saveSuggestedTitleFixes(uniqueFixes)
        loadLibrary()
        _titleFixMessage.value = "Applied reviewed cleanup suggestions to ${uniqueFixes.size} ${if (uniqueFixes.size == 1) "track" else "tracks"} in Tunelyst. Android storage permission was not granted, so embedded tags and device filenames were left unchanged."
        return
    }

    val coordinator = DeviceAudioRenameCoordinator(
        resolver = getApplication<Application>().contentResolver,
        launchAuthorization = { request ->
            deviceRenameAuthorizationLauncher?.invoke(request)
                ?: error("Android write-approval launcher is not attached.")
        },
        onComplete = { result ->
            deviceRenameCoordinator = null
            saveSuggestedTitleFixes(uniqueFixes)
            loadLibrary()
            val deviceFileCount = uniqueFixes.count { it.mediaUriString != null }
            val renameableCount = uniqueFixes.count { fix ->
                fix.mediaUriString != null && fix.currentFileName != fix.newFileName
            }
            _titleFixMessage.value = buildString {
                append("Applied reviewed cleanup suggestions to ${uniqueFixes.size} ${if (uniqueFixes.size == 1) "track" else "tracks"} in Tunelyst.")
                if (deviceFileCount > 0) {
                    append(" Wrote reviewed TITLE/ARTIST tags to ${result.titleTagWrittenCount} of $deviceFileCount device ${if (deviceFileCount == 1) "audio file" else "audio files"}.")
                    if (result.titleTagUnsupportedCount > 0) append(" ${result.titleTagUnsupportedCount} file ${if (result.titleTagUnsupportedCount == 1) "format doesn't" else "formats don't"} support embedded metadata writing.")
                    if (result.titleTagFailedCount > 0) append(" ${result.titleTagFailedCount} embedded metadata ${if (result.titleTagFailedCount == 1) "could" else "could"} not be saved or verified.")
                }
                if (renameableCount > 0) {
                    append(" Renamed ${result.renamedCount} of $renameableCount device ${if (renameableCount == 1) "file" else "files"}.")
                    if (result.renameFailedCount > 0) append(" ${result.renameFailedCount} ${if (result.renameFailedCount == 1) "filename could" else "filenames could"} not be changed.")
                } else if (result.unchangedCount > 0) {
                    append(" ${result.unchangedCount} device ${if (result.unchangedCount == 1) "file already had" else "files already had"} the suggested filename.")
                }
                if (result.cancelled) append(" Android write approval was declined; the remaining device files were left unchanged.")
                if (result.appOnlyCount > 0) {
                    append(" ${result.appOnlyCount} shared or unsupported ${if (result.appOnlyCount == 1) "track received" else "tracks received"} an app-only metadata correction; embedded tags and filenames couldn't be changed.")
                }
            }
        }
    )
    deviceRenameCoordinator = coordinator
    coordinator.start(uniqueFixes)
}

internal fun MusicViewModel.clearTitleFixMessageImpl() {
    _titleFixMessage.value = null
}

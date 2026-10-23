package com.tunelyst.music.ui

import android.net.Uri

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight


@Composable
internal fun AppFeedbackDialogs(
    viewModel: MusicViewModel,
    backupImportUri: Uri?,
    onDismissBackupImport: () -> Unit,
    titleFixMessage: String?,
    deviceDeleteConfirmation: PendingDeviceAudioDeletion?,
    onDismissDeviceDeleteConfirmation: () -> Unit,
    onConfirmDeviceDeletion: (PendingDeviceAudioDeletion) -> Unit,
    deviceDeleteFeedback: String?,
    onDismissDeviceDeleteFeedback: () -> Unit,
    deviceDeleteMessage: String?,
    lastPlaybackError: String?,
    playlistToDeleteId: String?,
    playlists: List<com.tunelyst.music.data.Playlist>,
    onDismissPlaylistDelete: () -> Unit,
    onDeletePlaylist: (String) -> Unit
) {
    backupImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { onDismissBackupImport() },
            title = { Text("Restore personal data?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This replaces Tunelyst's personal library data and settings. Audio files are not copied or changed; device-specific folder exclusions and picked artwork are not restored. Track links are matched to this device's library where possible.",
                    color = TunelystColors.Muted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDismissBackupImport()
                    viewModel.importBackup(uri)
                }) { Text("Restore", color = TunelystColors.Rose) }
            },
            dismissButton = {
                TextButton(onClick = { onDismissBackupImport() }) { Text("Cancel", color = TunelystColors.Muted) }
            },
            containerColor = TunelystColors.Surface
        )
    }

    titleFixMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearTitleFixMessage,
            title = { Text("Title fixes", fontWeight = FontWeight.Bold) },
            text = { Text(message, color = TunelystColors.Muted) },
            confirmButton = { TextButton(onClick = viewModel::clearTitleFixMessage) { Text("OK", color = TunelystColors.Primary) } },
            containerColor = TunelystColors.Surface
        )
    }

    deviceDeleteConfirmation?.let { request ->
        val fileCount = request.uriStrings.size
        AlertDialog(
            onDismissRequest = { onDismissDeviceDeleteConfirmation() },
            title = { Text("Permanently delete audio?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will permanently delete $fileCount ${if (fileCount == 1) "audio file" else "audio files"} from your device (${request.description}). This cannot be undone. Continue to the required Android approval or storage-permission step. Empty folders may remain if they contain other files." +
                        if (request.skippedCount > 0) " ${request.skippedCount} shared or unsupported ${if (request.skippedCount == 1) "track is" else "tracks are"} not included." else "",
                    color = TunelystColors.Muted
                )
            },
            confirmButton = {
                TextButton(onClick = { onConfirmDeviceDeletion(request) }) { Text("Continue", color = TunelystColors.Rose) }
            },
            dismissButton = {
                TextButton(onClick = { onDismissDeviceDeleteConfirmation() }) { Text("Cancel", color = TunelystColors.Muted) }
            },
            containerColor = TunelystColors.Surface
        )
    }

    deviceDeleteFeedback?.let { message ->
        AlertDialog(
            onDismissRequest = { onDismissDeviceDeleteFeedback() },
            title = { Text("Device audio", fontWeight = FontWeight.Bold) },
            text = { Text(message, color = TunelystColors.Muted) },
            confirmButton = { TextButton(onClick = { onDismissDeviceDeleteFeedback() }) { Text("OK", color = TunelystColors.Primary) } },
            containerColor = TunelystColors.Surface
        )
    }

    deviceDeleteMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearDeviceDeleteMessage,
            title = { Text("Device audio", fontWeight = FontWeight.Bold) },
            text = { Text(message, color = TunelystColors.Muted) },
            confirmButton = { TextButton(onClick = viewModel::clearDeviceDeleteMessage) { Text("OK", color = TunelystColors.Primary) } },
            containerColor = TunelystColors.Surface
        )
    }

    lastPlaybackError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearPlaybackError,
            title = { Text("Playback issue", fontWeight = FontWeight.Bold) },
            text = { Text(message, color = TunelystColors.Muted) },
            confirmButton = { TextButton(onClick = viewModel::clearPlaybackError) { Text("OK", color = TunelystColors.Primary) } },
            containerColor = TunelystColors.Surface
        )
    }

    playlistToDeleteId?.let { playlistId ->
        val playlistName = playlists.firstOrNull { it.id == playlistId }?.name ?: "this playlist"
        AlertDialog(
            onDismissRequest = { onDismissPlaylistDelete() },
            title = { Text("Delete playlist?", fontWeight = FontWeight.Bold) },
            text = { Text("\"$playlistName\" will be removed from this device. Your audio files will not be deleted.", color = TunelystColors.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePlaylist(playlistId)
                }) { Text("Delete", color = TunelystColors.Rose) }
            },
            dismissButton = { TextButton(onClick = { onDismissPlaylistDelete() }) { Text("Cancel") } },
            containerColor = TunelystColors.Surface
        )
    }
}

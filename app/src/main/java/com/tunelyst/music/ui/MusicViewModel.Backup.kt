@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.tunelyst.music.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackParameters
import com.tunelyst.music.data.PersonalDataBackup
import com.tunelyst.music.data.SongMetadataOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun MusicViewModel.exportBackupImpl(uri: Uri) {
    val songsSnapshot = rawCatalogSongs.ifEmpty { _uiState.value.songs }
    viewModelScope.launch(Dispatchers.IO) {
        val result = runCatching {
            val json = backupStore.exportJson(songsSnapshot)
            getApplication<Application>().contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
                ?: error("Couldn't open the selected backup destination.")
        }
        withContext(Dispatchers.Main) {
            _uiState.update { it.copy(operationMessage = result.fold({ "Backup saved. Audio, device-specific folder paths, and picked artwork images were not copied." }, { "Backup failed: ${it.message ?: "storage is unavailable"}" })) }
        }
    }
}

internal fun MusicViewModel.importBackupImpl(uri: Uri) {
    val songsSnapshot = rawCatalogSongs.ifEmpty { _uiState.value.songs }
    viewModelScope.launch(Dispatchers.IO) {
        val result = runCatching {
            val oldArtworkUris = store.metadataOverrides().values.mapNotNull(SongMetadataOverride::artworkUri).toSet()
            val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= PersonalDataBackup.MAX_BACKUP_BYTES) { "The selected backup is too large." }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            } ?: error("Couldn't read the selected backup file.")
            val imported = backupStore.importJson(text, songsSnapshot)
            oldArtworkUris.forEach(::releaseArtworkGrantIfUnused)
            imported
        }
        withContext(Dispatchers.Main) {
            if (result.isSuccess) {
                refreshSavedState(includeListeningStats = true)
                loadLibrary()
            }
            val message = result.fold(
                { imported ->
                    buildString {
                        append("Personal data restored. Audio files were not copied; folder exclusions and picked artwork URIs were not restored.")
                        if (imported.unmatchedTrackCount > 0) append(" ${imported.unmatchedTrackCount} track references could not be matched to this library.")
                        if (imported.usedLegacyMediaStoreIds) append(" This older backup uses device-local IDs; verify its track links.")
                    }
                },
                { "Restore failed: ${it.message ?: "invalid backup"}" }
            )
            _uiState.update { it.copy(operationMessage = message) }
            _appSettings.value = appSettingsStore.read()
            _audioSettings.value = audioSettingsStore.read()
            player?.setPlaybackParameters(PlaybackParameters(_audioSettings.value.playbackSpeed, _audioSettings.value.playbackPitch))
            if (_audioSettings.value.normalizeVolume) scheduleEmbeddedReplayGainForCurrentQueue()
        }
    }
}

internal fun MusicViewModel.clearOperationMessageImpl() { _uiState.update { it.copy(operationMessage = null) } }

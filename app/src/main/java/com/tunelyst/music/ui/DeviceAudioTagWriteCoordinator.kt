package com.tunelyst.music.ui

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import com.tunelyst.music.data.Song
import com.tunelyst.music.data.SongMetadataOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

internal data class EmbeddedTagWriteOutcome(
    val message: String,
    val fileChanged: Boolean
)

/** Writes the existing local-tag editor's values through a MediaStore file descriptor. */
internal class DeviceAudioTagWriteCoordinator(
    private val resolver: ContentResolver,
    private val song: Song,
    private val catalogSong: Song?,
    private val metadataOverride: SongMetadataOverride,
    private val replacementArtworkUri: Uri?,
    launchAuthorization: (IntentSenderRequest) -> Unit,
    private val onComplete: (EmbeddedTagWriteOutcome) -> Unit
) {
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var launchAuthorization: ((IntentSenderRequest) -> Unit)? = launchAuthorization
    @Volatile private var waitingForAuthorization = false
    @Volatile private var recoverableAuthorizationRequested = false
    @Volatile private var finished = false
    private var pendingMetadataUpdates: Map<String, String> = emptyMap()

    fun start() {
        if (finished) return
        pendingMetadataUpdates = try {
            metadataUpdates()
        } catch (failure: IllegalArgumentException) {
            finish(
                "Correction saved in Tunelyst only. A tag value couldn't be written: ${failure.message ?: "invalid value"}.",
                fileChanged = false
            )
            return
        }
        if (pendingMetadataUpdates.isEmpty() && replacementArtworkUri == null) {
            finish("Correction saved in Tunelyst. No embedded tag changes were needed.", fileChanged = false)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val request = MediaStore.createWriteRequest(resolver, listOf(song.uri))
                waitingForAuthorization = true
                currentAuthorizationLauncher()(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (_: Exception) {
                finish(
                    "Correction saved in Tunelyst, but Android couldn't request permission to write this audio file.",
                    fileChanged = false
                )
            }
        } else {
            writeToDeviceFile()
        }
    }

    fun updateAuthorizationLauncher(launcher: (IntentSenderRequest) -> Unit) {
        launchAuthorization = launcher
    }

    fun onAuthorizationResult(approved: Boolean) {
        if (finished || !waitingForAuthorization) return
        waitingForAuthorization = false
        if (!approved) {
            finish(
                "Correction saved in Tunelyst only. Android write access was declined, so the device audio file wasn't changed.",
                fileChanged = false
            )
        } else {
            writeToDeviceFile()
        }
    }

    fun cancel() {
        if (finished) return
        finished = true
        workerScope.cancel()
    }

    private fun writeToDeviceFile() {
        if (finished) return
        workerScope.launch {
            tagFileWriteMutex.withLock {
                if (finished) return@withLock
                val artwork = try {
                    replacementArtworkUri?.let(::readArtwork)
                } catch (failure: ArtworkReadException) {
                    withContext(Dispatchers.Main.immediate) {
                        finish(
                            "Correction saved in Tunelyst, but the selected artwork couldn't be read: ${failure.message}",
                            fileChanged = false
                        )
                    }
                    return@launch
                }

                try {
                    val descriptor = resolver.openFileDescriptor(song.uri, "rw")
                        ?: throw FileNotFoundException("Android didn't provide a writable audio file descriptor.")
                    val result = descriptor.use { pfd ->
                        EmbeddedAudioTagFileWriter.write(
                            pfd,
                            pendingMetadataUpdates,
                            artwork?.bytes,
                            artwork?.mimeType
                        )
                    }
                    val outcome = resultMessage(result)
                    withContext(Dispatchers.Main.immediate) { finish(outcome.message, outcome.fileChanged) }
                } catch (security: SecurityException) {
                    val approvalSender = recoverableApprovalSender(security)
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
                        approvalSender != null && !recoverableAuthorizationRequested
                    ) {
                        val launched = withContext(Dispatchers.Main.immediate) {
                            waitingForAuthorization = true
                            recoverableAuthorizationRequested = true
                            runCatching {
                                currentAuthorizationLauncher()(IntentSenderRequest.Builder(approvalSender).build())
                            }.isSuccess
                        }
                        if (!launched) {
                            withContext(Dispatchers.Main.immediate) {
                                finish(
                                    "Correction saved in Tunelyst, but Android's write-approval screen couldn't be opened.",
                                    fileChanged = false
                                )
                            }
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main.immediate) {
                        finish(
                            "Correction saved in Tunelyst only. Android denied write access to the device audio file.",
                            fileChanged = false
                        )
                    }
                } catch (linkage: LinkageError) {
                    withContext(Dispatchers.Main.immediate) {
                        finish(
                            "Correction saved in Tunelyst only. The embedded tag writer couldn't be loaded on this device.",
                            fileChanged = false
                        )
                    }
                } catch (failure: Exception) {
                    withContext(Dispatchers.Main.immediate) {
                        finish(
                            "Correction saved in Tunelyst, but writing the device audio file failed: ${failure.message ?: "I/O error"}.",
                            fileChanged = false
                        )
                    }
                }
            }
        }
    }

    /** Avoid writing unchanged MediaStore fallbacks (such as a filename used as a title) into tags. */
    private fun metadataUpdates(): Map<String, String> {
        val updates = linkedMapOf<String, String>()
        fun addTextIfChanged(key: String, desired: String?, existing: String?) {
            val value = desired?.trim()?.takeIf(String::isNotBlank) ?: return
            if (catalogSong == null || value != existing) updates[key] = value
        }

        addTextIfChanged("TITLE", metadataOverride.title, catalogSong?.title)
        addTextIfChanged("ARTIST", metadataOverride.artist, catalogSong?.artist)
        addTextIfChanged("ALBUM", metadataOverride.album, catalogSong?.album)
        addTextIfChanged("ALBUMARTIST", metadataOverride.albumArtist, catalogSong?.albumArtist)
        addTextIfChanged("GENRE", metadataOverride.genre, catalogSong?.genre)

        metadataOverride.year?.takeIf { it > 0 }?.let { value ->
            if (catalogSong == null || value != catalogSong.year) updates["DATE"] = value.toString()
        }
        metadataOverride.discNumber?.takeIf { it > 0 }?.let { value ->
            if (catalogSong == null || value != catalogSong.discNumber) updates["DISCNUMBER"] = value.toString()
        }
        metadataOverride.trackNumber?.takeIf { it > 0 }?.let { value ->
            if (catalogSong == null || value != catalogSong.trackNumber) updates["TRACKNUMBER"] = value.toString()
        }
        metadataOverride.isCompilation?.let { value ->
            if (catalogSong == null || value != catalogSong.isCompilation) {
                updates["COMPILATION"] = if (value) "1" else ""
            }
        }
        return updates
    }

    private fun resultMessage(result: EmbeddedAudioTagFileWriter.Result): EmbeddedTagWriteOutcome {
        if (!result.supportedFormat) {
            return EmbeddedTagWriteOutcome(
                "Correction saved in Tunelyst only. The ${song.formatLabel} audio format isn't supported for embedded tag writing.",
                fileChanged = false
            )
        }
        if (!result.metadataSaved) {
            val message = if (result.fileMayHaveChanged) {
                "Correction saved in Tunelyst, but the device-file tag write didn't complete; the file may have been partly changed."
            } else {
                "Correction saved in Tunelyst, but the tag writer couldn't save metadata to the device audio file."
            }
            return EmbeddedTagWriteOutcome(message, fileChanged = result.fileMayHaveChanged)
        }

        val limitations = mutableListOf<String>()
        if (result.unsupportedMetadataKeys.isNotEmpty()) {
            val fields = result.unsupportedMetadataKeys.distinct().map(::fieldLabel)
            limitations += "This format couldn't write: ${fields.joinToString()}."
        }
        if (!result.metadataVerified) {
            limitations += "Tunelyst couldn't verify all embedded tag values."
        }
        if (result.artworkAttempted && !result.artworkSaved) {
            limitations += "The replacement artwork couldn't be confirmed; the device file may have been partly changed."
        }

        val metadataWasAttempted = pendingMetadataUpdates.isNotEmpty()
        val message = when {
            limitations.isEmpty() && metadataWasAttempted -> "Tag correction saved to the device audio file."
            limitations.isEmpty() -> "Replacement artwork saved to the device audio file."
            metadataWasAttempted -> "Tag data write completed with limitations: ${limitations.joinToString(" ")}"
            else -> "Artwork update completed with limitations: ${limitations.joinToString(" ")}"
        }
        return EmbeddedTagWriteOutcome(message, fileChanged = result.fileMayHaveChanged)
    }

    private fun readArtwork(uri: Uri): ArtworkBytes {
        val output = ByteArrayOutputStream()
        try {
            val input = resolver.openInputStream(uri)
                ?: throw ArtworkReadException("Android couldn't open the selected image.")
            input.use { stream ->
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_ARTWORK_BYTES) {
                        throw ArtworkReadException("the selected image is larger than 20 MB.")
                    }
                    output.write(buffer, 0, read)
                }
            }
        } catch (failure: SecurityException) {
            throw ArtworkReadException("Android didn't grant access to the selected image.", failure)
        } catch (failure: IOException) {
            if (failure is ArtworkReadException) throw failure
            throw ArtworkReadException("Android couldn't read the selected image.", failure)
        }

        val bytes = output.toByteArray()
        if (bytes.isEmpty()) throw ArtworkReadException("the selected image is empty.")
        val mimeType = try {
            resolver.getType(uri)?.takeIf { it.startsWith("image/") }
                ?: inferImageMimeType(bytes)
                ?: throw ArtworkReadException("the selected file isn't a supported image.")
        } catch (failure: ArtworkReadException) {
            throw failure
        } catch (failure: SecurityException) {
            throw ArtworkReadException("Android didn't grant access to the selected image.", failure)
        } catch (failure: Exception) {
            throw ArtworkReadException("Android couldn't identify the selected image.", failure)
        }
        return ArtworkBytes(bytes, mimeType)
    }

    private fun inferImageMimeType(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        ) -> "image/png"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        bytes.size >= 6 && (String(bytes, 0, 6, Charsets.US_ASCII) == "GIF87a" ||
            String(bytes, 0, 6, Charsets.US_ASCII) == "GIF89a") -> "image/gif"
        else -> null
    }

    private fun fieldLabel(key: String): String = when (key) {
        "TITLE" -> "title"
        "ARTIST" -> "track artist"
        "ALBUM" -> "album"
        "ALBUMARTIST" -> "album artist"
        "GENRE" -> "genre"
        "DATE" -> "year"
        "DISCNUMBER" -> "disc number"
        "TRACKNUMBER" -> "track number"
        "COMPILATION" -> "compilation flag"
        else -> key
    }

    private fun recoverableApprovalSender(exception: SecurityException): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            exception.javaClass.name != "android.app.RecoverableSecurityException"
        ) return null
        return runCatching {
            val userAction = exception.javaClass.getMethod("getUserAction").invoke(exception)
            val actionPendingIntent = userAction.javaClass.getMethod("getActionIntent")
                .invoke(userAction) as? PendingIntent
            actionPendingIntent?.intentSender
        }.getOrNull()
    }

    private fun currentAuthorizationLauncher(): (IntentSenderRequest) -> Unit =
        launchAuthorization ?: error("Android write-approval launcher is not attached.")

    private fun finish(message: String, fileChanged: Boolean) {
        if (finished) return
        finished = true
        onComplete(EmbeddedTagWriteOutcome(message, fileChanged))
        workerScope.cancel()
    }

    private data class ArtworkBytes(val bytes: ByteArray, val mimeType: String)

    private class ArtworkReadException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private companion object {
        const val MAX_ARTWORK_BYTES = 20 * 1024 * 1024
        val tagFileWriteMutex = Mutex()
    }
}

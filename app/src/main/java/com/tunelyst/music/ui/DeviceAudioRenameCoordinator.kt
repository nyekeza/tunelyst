package com.tunelyst.music.ui

import android.content.ContentResolver
import android.content.ContentValues
import android.content.IntentSender
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

internal data class DeviceAudioRenameResult(
    val renamedCount: Int,
    val unchangedCount: Int,
    val renameFailedCount: Int,
    val appOnlyCount: Int,
    val titleTagWrittenCount: Int,
    val titleTagFailedCount: Int,
    val titleTagUnsupportedCount: Int,
    val cancelled: Boolean
)

/** Saves reviewed title/artist corrections into embedded tags and renames eligible MediaStore audio files. */
internal class DeviceAudioRenameCoordinator(
    private val resolver: ContentResolver,
    launchAuthorization: (IntentSenderRequest) -> Unit,
    private val onComplete: (DeviceAudioRenameResult) -> Unit
) {
    private class PendingFix(val request: TitleFixRequest, val uri: Uri) {
        var titleTagFinished = false
        var renameFinished = request.currentFileName == request.newFileName
    }

    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var launchAuthorization: ((IntentSenderRequest) -> Unit)? = launchAuthorization
    private var requests: List<TitleFixRequest> = emptyList()
    private var eligible: List<PendingFix> = emptyList()
    private var nextIndex = 0
    private var renamedCount = 0
    private var unchangedCount = 0
    private var renameFailedCount = 0
    private var appOnlyCount = 0
    private var titleTagWrittenCount = 0
    private var titleTagFailedCount = 0
    private var titleTagUnsupportedCount = 0
    private var waitingForAuthorization = false
    private var finished = false
    private var batchAuthorization = false

    fun start(fixes: List<TitleFixRequest>) {
        if (finished) return
        requests = fixes.distinctBy(TitleFixRequest::songId)
        eligible = requests.mapNotNull { fix ->
            fix.mediaUriString?.takeIf(String::isNotBlank)?.let { PendingFix(fix, Uri.parse(it)) }
        }
        appOnlyCount = requests.size - eligible.size
        unchangedCount = eligible.count { it.request.currentFileName == it.request.newFileName }
        if (eligible.isEmpty()) {
            finish(cancelled = false)
            return
        }

        // Android 11+ uses one system approval for the complete set of files. The same approval
        // covers both the embedded TITLE write and the optional MediaStore filename update.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val request = MediaStore.createWriteRequest(resolver, eligible.map { it.uri })
                waitingForAuthorization = true
                batchAuthorization = true
                currentAuthorizationLauncher()(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (_: Exception) {
                markRemainingDeviceFailures()
                finish(cancelled = false)
            }
        } else {
            processSequentially()
        }
    }

    fun updateAuthorizationLauncher(launcher: (IntentSenderRequest) -> Unit) {
        launchAuthorization = launcher
    }

    fun cancel() {
        if (finished) return
        finished = true
        workerScope.cancel()
    }

    fun onAuthorizationResult(approved: Boolean) {
        if (finished || !waitingForAuthorization) return
        waitingForAuthorization = false
        if (!approved) {
            markRemainingDeviceFailures()
            finish(cancelled = true)
            return
        }
        if (batchAuthorization) {
            processApprovedBatch()
        } else {
            // Android 10's recoverable exception approves retrying the current MediaStore item.
            processSequentially()
        }
    }

    private fun currentAuthorizationLauncher(): (IntentSenderRequest) -> Unit =
        launchAuthorization ?: error("Android write-approval launcher is not attached.")

    private fun processApprovedBatch() {
        workerScope.launch {
            while (nextIndex < eligible.size) {
                val pending = eligible[nextIndex]
                try {
                    processOne(pending)
                } catch (_: Exception) {
                    markDeviceFailure(pending)
                }
                nextIndex++
            }
            withContext(Dispatchers.Main.immediate) { finish(cancelled = false) }
        }
    }

    private fun processSequentially() {
        workerScope.launch {
            while (nextIndex < eligible.size) {
                val pending = eligible[nextIndex]
                try {
                    processOne(pending)
                    nextIndex++
                } catch (security: SecurityException) {
                    val approvalSender = recoverableApprovalSender(security)
                    if (approvalSender == null) {
                        markDeviceFailure(pending)
                        nextIndex++
                        continue
                    }
                    val launched = withContext(Dispatchers.Main.immediate) {
                        waitingForAuthorization = true
                        batchAuthorization = false
                        runCatching {
                            currentAuthorizationLauncher()(IntentSenderRequest.Builder(approvalSender).build())
                        }.isSuccess
                    }
                    if (!launched) {
                        withContext(Dispatchers.Main.immediate) {
                            markRemainingDeviceFailures()
                            finish(cancelled = false)
                        }
                    }
                    return@launch
                } catch (_: Exception) {
                    markDeviceFailure(pending)
                    nextIndex++
                }
            }
            withContext(Dispatchers.Main.immediate) { finish(cancelled = false) }
        }
    }

    /** Tag write is attempted first; if Android requests Q-era authorization, retry is idempotent. */
    private fun processOne(pending: PendingFix) {
        val fix = pending.request
        if (!pending.titleTagFinished) {
            try {
                val descriptor = resolver.openFileDescriptor(pending.uri, "rw")
                    ?: throw FileNotFoundException("Android didn't provide a writable audio file descriptor.")
                val result = descriptor.use { pfd ->
                    val textTags = buildMap {
                        put("TITLE", fix.suggestedTitle.trim())
                        fix.suggestedArtist?.trim()?.takeIf(String::isNotBlank)?.let { put("ARTIST", it) }
                    }
                    EmbeddedAudioTagFileWriter.write(pfd, textTags, null, null)
                }
                when {
                    !result.supportedFormat -> titleTagUnsupportedCount++
                    result.metadataSaved && result.metadataVerified && result.unsupportedMetadataKeys.isEmpty() -> titleTagWrittenCount++
                    else -> titleTagFailedCount++
                }
                pending.titleTagFinished = true
            } catch (security: SecurityException) {
                throw security
            } catch (_: LinkageError) {
                titleTagFailedCount++
                pending.titleTagFinished = true
            } catch (_: Exception) {
                titleTagFailedCount++
                pending.titleTagFinished = true
            }
        }

        if (!pending.renameFinished) {
            try {
                if (renameOne(pending.uri, fix)) renamedCount++ else renameFailedCount++
                pending.renameFinished = true
            } catch (security: SecurityException) {
                throw security
            } catch (_: Exception) {
                renameFailedCount++
                pending.renameFinished = true
            }
        }
    }

    private fun markDeviceFailure(pending: PendingFix) {
        if (!pending.titleTagFinished) {
            titleTagFailedCount++
            pending.titleTagFinished = true
        }
        if (!pending.renameFinished) {
            renameFailedCount++
            pending.renameFinished = true
        }
    }

    private fun markRemainingDeviceFailures() {
        eligible.drop(nextIndex).forEach(::markDeviceFailure)
    }

    private fun renameOne(uri: Uri, fix: TitleFixRequest): Boolean {
        val newDisplayName = fix.newFileName
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val parentPath = fix.folderPath.replace('\\', '/').trim('/')
            if (parentPath.isBlank() || parentPath.equals("Device root", ignoreCase = true)) return false
            values.put(MediaStore.MediaColumns.DATA, "/$parentPath/$newDisplayName")
        }
        return try {
            resolver.update(uri, values, null, null) > 0 || hasDisplayName(uri, newDisplayName)
        } catch (security: SecurityException) {
            throw security
        } catch (_: Exception) {
            false
        }
    }

    private fun hasDisplayName(uri: Uri, expected: String): Boolean = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor: Cursor ->
            cursor.moveToFirst() && cursor.getString(0).equals(expected, ignoreCase = false)
        } ?: false
    }.getOrDefault(false)

    private fun recoverableApprovalSender(exception: SecurityException): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            exception.javaClass.name != "android.app.RecoverableSecurityException"
        ) return null
        return runCatching {
            val userAction = exception.javaClass.getMethod("getUserAction").invoke(exception)
            val actionPendingIntent = userAction.javaClass.getMethod("getActionIntent").invoke(userAction) as? android.app.PendingIntent
            actionPendingIntent?.intentSender
        }.getOrNull()
    }

    private fun finish(cancelled: Boolean) {
        if (finished) return
        finished = true
        onComplete(
            DeviceAudioRenameResult(
                renamedCount = renamedCount,
                unchangedCount = unchangedCount,
                renameFailedCount = renameFailedCount,
                appOnlyCount = appOnlyCount,
                titleTagWrittenCount = titleTagWrittenCount,
                titleTagFailedCount = titleTagFailedCount,
                titleTagUnsupportedCount = titleTagUnsupportedCount,
                cancelled = cancelled
            )
        )
        workerScope.cancel()
    }
}

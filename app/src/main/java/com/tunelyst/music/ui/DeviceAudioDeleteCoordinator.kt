package com.tunelyst.music.ui

import android.content.ContentResolver
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DeviceDeleteResult(
    val deletedCount: Int,
    val failedCount: Int,
    val cancelled: Boolean
)

/** Coordinates MediaStore deletion with Android's required approval UI for files the app doesn't own. */
internal class DeviceAudioDeleteCoordinator(
    private val resolver: ContentResolver,
    launchAuthorization: (IntentSenderRequest) -> Unit,
    private val onComplete: (DeviceDeleteResult) -> Unit
) {
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var launchAuthorization: ((IntentSenderRequest) -> Unit)? = launchAuthorization
    private var uris: List<Uri> = emptyList()
    private var nextIndex = 0
    private var deletedCount = 0
    private var failedCount = 0
    private var waitingForAuthorization = false
    private var finished = false

    fun start(audioUris: List<Uri>) {
        if (finished) return
        uris = audioUris.distinct()
        if (uris.isEmpty()) {
            finish(cancelled = false)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val request = MediaStore.createDeleteRequest(resolver, uris)
                waitingForAuthorization = true
                currentAuthorizationLauncher()(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (_: Exception) {
                failedCount = uris.size
                finish(cancelled = false)
            }
        } else {
            deleteWithRecoverableApproval()
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

    private fun currentAuthorizationLauncher(): (IntentSenderRequest) -> Unit =
        launchAuthorization ?: error("Android approval launcher is not attached.")

    fun onAuthorizationResult(approved: Boolean) {
        if (finished || !waitingForAuthorization) return
        waitingForAuthorization = false
        if (!approved) {
            failedCount += (uris.size - nextIndex).coerceAtLeast(0)
            finish(cancelled = true)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            verifyApprovedDeletion()
        } else {
            // On Android 10, approval authorizes retrying the operation that raised the exception.
            deleteWithRecoverableApproval()
        }
    }

    private fun verifyApprovedDeletion() {
        workerScope.launch {
            var remaining = uris
            for (attempt in 0..2) {
                remaining = remaining.filter { uri ->
                    runCatching {
                        val cursor = resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                            ?: return@runCatching true
                        cursor.use { it.moveToFirst() }
                    }.getOrDefault(true)
                }
                if (remaining.isEmpty() || attempt == 2) break
                delay(180L)
            }
            withContext(Dispatchers.Main.immediate) {
                deletedCount = uris.size - remaining.size
                failedCount = remaining.size
                finish(cancelled = false)
            }
        }
    }

    private fun deleteWithRecoverableApproval() {
        workerScope.launch {
            while (nextIndex < uris.size) {
                try {
                    if (resolver.delete(uris[nextIndex], null, null) > 0) deletedCount++ else failedCount++
                    nextIndex++
                } catch (security: SecurityException) {
                    val approvalSender = recoverableApprovalSender(security)
                    if (approvalSender == null) {
                        failedCount++
                        nextIndex++
                        continue
                    }
                    val launched = withContext(Dispatchers.Main.immediate) {
                        waitingForAuthorization = true
                        runCatching {
                            currentAuthorizationLauncher()(IntentSenderRequest.Builder(approvalSender).build())
                        }.isSuccess
                    }
                    if (!launched) {
                        withContext(Dispatchers.Main.immediate) {
                            failedCount += (uris.size - nextIndex).coerceAtLeast(0)
                            finish(cancelled = false)
                        }
                    }
                    return@launch
                } catch (_: Exception) {
                    failedCount++
                    nextIndex++
                }
            }
            withContext(Dispatchers.Main.immediate) { finish(cancelled = false) }
        }
    }

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
        onComplete(DeviceDeleteResult(deletedCount, failedCount, cancelled))
        workerScope.cancel()
    }
}

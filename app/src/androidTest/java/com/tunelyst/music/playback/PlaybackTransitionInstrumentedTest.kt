package com.tunelyst.music.playback

import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.tunelyst.music.data.AudioSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class PlaybackTransitionInstrumentedTest {
    @OptIn(UnstableApi::class)
    @Test
    fun manualNextAndQueueReplacementOverlapBeforeTheSessionSwapsTracks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = AudioSettingsStore(context)
        val originalSettings = settings.read()
        val wavFiles = listOf(
            createWaveFile(context.cacheDir, "transition-source", 261.63),
            createWaveFile(context.cacheDir, "transition-next", 329.63),
            createWaveFile(context.cacheDir, "transition-selected", 392.00)
        )
        // Configure service-owned preferences before requesting the service; otherwise a fast
        // emulator can create the service with the old settings before the test writes them.
        settings.setCrossfadeSeconds(0)
        settings.setManualSkipFadeSeconds(1)
        val controllerFuture = MediaController.Builder(
            context,
            androidx.media3.session.SessionToken(
                context,
                ComponentName(context, MusicPlaybackService::class.java)
            )
        ).buildAsync()
        var controller: MediaController? = null

        try {
            val connectedController = controllerFuture.get(30, TimeUnit.SECONDS)
            controller = connectedController
            val activeController = connectedController
            val initialStandbyGeneration = standbyGeneration(transitionDebugState(instrumentation, activeController))
            runOnMainSync(instrumentation) {
                activeController.stop()
                activeController.repeatMode = Player.REPEAT_MODE_OFF
                activeController.shuffleModeEnabled = false
                activeController.setMediaItems(
                    listOf(
                        mediaItem("transition-source", wavFiles[0]),
                        mediaItem("transition-next", wavFiles[1])
                    ),
                    0,
                    0L
                )
                activeController.prepare()
                activeController.play()
            }
            awaitCondition(
                instrumentation,
                "source track or its next queue item did not become available",
                diagnostics = {
                    runOnMainSync(instrumentation) {
                        "id=${activeController.currentMediaItem?.mediaId}, " +
                            "playWhenReady=${activeController.playWhenReady}, " +
                            "isPlaying=${activeController.isPlaying}, " +
                            "playbackState=${activeController.playbackState}, " +
                            "position=${activeController.currentPosition}, " +
                            "itemCount=${activeController.mediaItemCount}, " +
                            "currentIndex=${activeController.currentMediaItemIndex}, " +
                            "nextIndex=${activeController.nextMediaItemIndex}"
                    }
                }
            ) {
                runOnMainSync(instrumentation) {
                    activeController.currentMediaItem?.mediaId == "transition-source" &&
                        activeController.isPlaying &&
                        activeController.mediaItemCount == 2 &&
                        activeController.nextMediaItemIndex == 1
                }
            }

            val nextRequest = runOnMainSync(instrumentation) {
                inQueueTransitionRequest(activeController, activeController.nextMediaItemIndex)
            } ?: error("next transition request could not be created")
            val nextCommandFuture = AtomicReference<ListenableFuture<SessionResult>>()
            runOnMainSync(instrumentation) {
                nextCommandFuture.set(sendTransitionRequest(activeController, nextRequest))
            }
            assertEquals(
                SessionResult.RESULT_SUCCESS,
                nextCommandFuture.get().get(15, TimeUnit.SECONDS).resultCode
            )
            SystemClock.sleep(200L)
            assertEquals("transition-source", currentMediaId(instrumentation, activeController))
            awaitCondition(
                instrumentation,
                "manual next did not finish",
                diagnostics = {
                    val controllerState = runOnMainSync(instrumentation) {
                        "mediaId=${activeController.currentMediaItem?.mediaId}, " +
                            "playWhenReady=${activeController.playWhenReady}, " +
                            "isPlaying=${activeController.isPlaying}, " +
                            "playbackState=${activeController.playbackState}, " +
                            "position=${activeController.currentPosition}, " +
                            "currentIndex=${activeController.currentMediaItemIndex}, " +
                            "nextIndex=${activeController.nextMediaItemIndex}"
                    }
                    "$controllerState, service=${transitionDebugState(instrumentation, activeController)}"
                }
            ) {
                currentMediaId(instrumentation, activeController) == "transition-next"
            }
            val incomingPosition = currentPosition(instrumentation, activeController)
            assertTrue(
                "incoming track position should show that it played during the crossfade (position=$incomingPosition)",
                incomingPosition >= 500L
            )
            val afterManualNextGeneration = standbyGeneration(transitionDebugState(instrumentation, activeController))
            assertTrue(
                "the standby player should be recreated after manual crossfade",
                afterManualNextGeneration > initialStandbyGeneration
            )

            val replacementItems = listOf(
                mediaItem("transition-source", wavFiles[0]),
                mediaItem("transition-selected", wavFiles[2])
            )
            val replacementRequest = PlaybackTransitionRequest(
                mediaItems = replacementItems,
                startIndex = 1,
                startPositionMs = 0L,
                repeatMode = Player.REPEAT_MODE_OFF,
                shuffleModeEnabled = false,
                playWhenReady = true
            )
            val commandFuture = AtomicReference<ListenableFuture<SessionResult>>()
            runOnMainSync(instrumentation) {
                commandFuture.set(sendTransitionRequest(activeController, replacementRequest))
            }
            val commandResult = commandFuture.get().get(15, TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS, commandResult.resultCode)
            awaitCondition(instrumentation, "queue replacement did not finish") {
                currentMediaId(instrumentation, activeController) == "transition-selected"
            }
            assertTrue(
                "replacement track position should show that it played during the crossfade",
                currentPosition(instrumentation, activeController) >= 500L
            )
            val afterReplacementGeneration = standbyGeneration(transitionDebugState(instrumentation, activeController))
            assertTrue(
                "the standby player should be recreated after queue-replacement crossfade",
                afterReplacementGeneration > afterManualNextGeneration
            )
        } finally {
            controller?.let { activeController ->
                runOnMainSync(instrumentation) {
                    activeController.stop()
                    activeController.clearMediaItems()
                }
            }
            runOnMainSync(instrumentation) { MediaController.releaseFuture(controllerFuture) }
            settings.setCrossfadeSeconds(originalSettings.crossfadeSeconds)
            settings.setManualSkipFadeSeconds(originalSettings.manualSkipFadeSeconds)
            wavFiles.forEach(File::delete)
        }
    }

    private fun sendTransitionRequest(
        controller: MediaController,
        request: PlaybackTransitionRequest
    ): ListenableFuture<SessionResult> {
        val requestId = PendingPlaybackTransitionRequests.register(request)
        val args = Bundle().apply {
            putString(PlaybackTransitionProtocol.EXTRA_REQUEST_ID, requestId)
        }
        return try {
            controller.sendCustomCommand(PlaybackTransitionProtocol.transitionToQueueCommand, args)
        } catch (error: Throwable) {
            PendingPlaybackTransitionRequests.discard(requestId)
            throw error
        }
    }

    private fun transitionDebugState(
        instrumentation: android.app.Instrumentation,
        controller: MediaController
    ): String {
        val future = AtomicReference<ListenableFuture<SessionResult>>()
        runOnMainSync(instrumentation) {
            future.set(
                controller.sendCustomCommand(
                    PlaybackTransitionProtocol.debugTransitionStateCommand,
                    Bundle.EMPTY
                )
            )
        }
        return future.get().get(5, TimeUnit.SECONDS).extras
            .getString(PlaybackTransitionProtocol.EXTRA_DEBUG_STATE)
            .orEmpty()
    }

    private fun mediaItem(id: String, file: File): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(android.net.Uri.fromFile(file))
        .build()

    private fun createWaveFile(directory: File, prefix: String, frequencyHz: Double): File {
        val file = File.createTempFile(prefix, ".wav", directory)
        val sampleRate = 44_100
        // Leave slow emulators enough time to expose the full queue before the source clip ends.
        val durationSeconds = 30
        val sampleCount = sampleRate * durationSeconds
        val pcm = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(sampleCount) { index ->
            val phase = 2.0 * PI * frequencyHz * index / sampleRate
            val sample = (sin(phase) * Short.MAX_VALUE * 0.15).roundToInt().toShort()
            pcm.putShort(sample)
        }
        val pcmBytes = pcm.array()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(StandardCharsets.US_ASCII))
        header.putInt(36 + pcmBytes.size)
        header.put("WAVE".toByteArray(StandardCharsets.US_ASCII))
        header.put("fmt ".toByteArray(StandardCharsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(StandardCharsets.US_ASCII))
        header.putInt(pcmBytes.size)
        FileOutputStream(file).use { stream ->
            stream.write(header.array())
            stream.write(pcmBytes)
        }
        return file
    }

    private fun standbyGeneration(debugState: String): Int =
        debugState.split(';')
            .firstOrNull { it.startsWith("standbyGeneration=") }
            ?.substringAfter('=')
            ?.toIntOrNull()
            ?: error("standby player generation missing from debug state: $debugState")

    private fun currentMediaId(instrumentation: android.app.Instrumentation, controller: MediaController): String? =
        runOnMainSync(instrumentation) { controller.currentMediaItem?.mediaId }

    private fun isPlaying(instrumentation: android.app.Instrumentation, controller: MediaController): Boolean =
        runOnMainSync(instrumentation) { controller.isPlaying }

    private fun currentPosition(instrumentation: android.app.Instrumentation, controller: MediaController): Long =
        runOnMainSync(instrumentation) { controller.currentPosition }

    private fun awaitCondition(
        instrumentation: android.app.Instrumentation,
        failureMessage: String,
        diagnostics: (() -> String)? = null,
        condition: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(40L)
        }
        val details = diagnostics?.invoke()?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
        assertTrue("$failureMessage$details", condition())
    }

    private fun <T> runOnMainSync(instrumentation: android.app.Instrumentation, block: () -> T): T {
        val value = AtomicReference<Any?>()
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {
            try {
                value.set(block())
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }
        failure.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value.get() as T
    }
}

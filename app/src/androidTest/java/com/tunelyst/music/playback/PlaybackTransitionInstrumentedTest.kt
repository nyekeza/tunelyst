package com.tunelyst.music.playback

import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
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
    fun ordinaryTrackSwitchesDoNotAttachDisabledAudioEffects() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = AudioSettingsStore(context)
        val originalSettings = settings.read()
        val wavFiles = listOf(
            createWaveFile(context.cacheDir, "ordinary-source", 261.63, durationSeconds = 180, sampleRate = 16_000),
            createWaveFile(context.cacheDir, "ordinary-next", 329.63, durationSeconds = 180, sampleRate = 16_000),
            createWaveFile(context.cacheDir, "ordinary-final", 392.00, durationSeconds = 180, sampleRate = 16_000)
        )
        settings.setCrossfadeSeconds(0)
        settings.setManualSkipFadeSeconds(0)
        settings.setEqualizerEnabled(false)
        settings.setNormalizeVolume(false)
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
            runOnMainSync(instrumentation) {
                connectedController.stop()
                connectedController.repeatMode = Player.REPEAT_MODE_ALL
                connectedController.shuffleModeEnabled = false
                connectedController.setMediaItems(
                    listOf(
                        mediaItem("ordinary-0", wavFiles[0]),
                        mediaItem("ordinary-1", wavFiles[1]),
                        mediaItem("ordinary-2", wavFiles[2])
                    ),
                    0,
                    0L
                )
                connectedController.prepare()
                connectedController.play()
            }
            awaitCondition(
                instrumentation,
                "ordinary source track did not start",
                diagnostics = {
                    "service=${transitionDebugState(instrumentation, connectedController)}"
                }
            ) {
                runOnMainSync(instrumentation) {
                    connectedController.currentMediaItem?.mediaId == "ordinary-0" && connectedController.isPlaying
                }
            }
            awaitCondition(
                instrumentation,
                "ordinary source track did not create an audio session",
                diagnostics = {
                    "service=${transitionDebugState(instrumentation, connectedController)}"
                }
            ) {
                hasActiveAudioSession(transitionDebugState(instrumentation, connectedController))
            }
            assertAudioEffectsDetached(instrumentation, connectedController)

            repeat(12) { switchIndex ->
                val (fromId, expectedId) = runOnMainSync(instrumentation) {
                    val currentId = connectedController.currentMediaItem?.mediaId
                        ?: error("ordinary playback lost its current item")
                    val nextIndex = connectedController.nextMediaItemIndex
                    check(nextIndex in 0 until connectedController.mediaItemCount) {
                        "repeat-all queue did not expose a next item: index=$nextIndex, " +
                            "count=${connectedController.mediaItemCount}"
                    }
                    val nextId = connectedController.getMediaItemAt(nextIndex).mediaId
                    connectedController.seekToNextMediaItem()
                    currentId to nextId
                }
                assertTrue("track switch ${switchIndex + 1} should select a different item", fromId != expectedId)
                awaitCondition(
                    instrumentation,
                    "repeated ordinary track switch ${switchIndex + 1} ($fromId -> $expectedId) did not finish",
                    diagnostics = { "service=${transitionDebugState(instrumentation, connectedController)}" }
                ) {
                    runOnMainSync(instrumentation) {
                        connectedController.currentMediaItem?.mediaId == expectedId && connectedController.isPlaying
                    }
                }
                assertAudioEffectsDetached(instrumentation, connectedController)
            }
        } finally {
            controller?.let { activeController ->
                runCatching {
                    runOnMainSync(instrumentation) {
                        activeController.stop()
                        activeController.clearMediaItems()
                    }
                }
            }
            runCatching { runOnMainSync(instrumentation) { MediaController.releaseFuture(controllerFuture) } }
            runCatching { settings.setCrossfadeSeconds(originalSettings.crossfadeSeconds) }
            runCatching { settings.setManualSkipFadeSeconds(originalSettings.manualSkipFadeSeconds) }
            runCatching { settings.setEqualizerEnabled(originalSettings.equalizerEnabled) }
            runCatching { settings.setNormalizeVolume(originalSettings.normalizeVolume) }
            wavFiles.forEach { runCatching { it.delete() } }
        }
    }

    @OptIn(UnstableApi::class)
    @Test
    fun memoryGuardForegroundPlayerReplacementPreservesPlaybackState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = AudioSettingsStore(context)
        val originalSettings = settings.read()
        val wavFiles = listOf(
            createWaveFile(context.cacheDir, "memory-guard-0", 261.63),
            createWaveFile(context.cacheDir, "memory-guard-1", 329.63),
            createWaveFile(context.cacheDir, "memory-guard-2", 392.00)
        )
        settings.setCrossfadeSeconds(0)
        settings.setManualSkipFadeSeconds(0)
        settings.setEqualizerEnabled(false)
        settings.setNormalizeVolume(false)
        val controllerFuture = MediaController.Builder(
            context,
            androidx.media3.session.SessionToken(
                context,
                ComponentName(context, MusicPlaybackService::class.java)
            )
        ).buildAsync()
        var controller: MediaController? = null
        val expectedPlaybackParameters = PlaybackParameters(1.25f, 0.9f)
        val expectedTrackSelectionParameters = AtomicReference<TrackSelectionParameters?>()

        try {
            val connectedController = controllerFuture.get(30, TimeUnit.SECONDS)
            controller = connectedController
            runOnMainSync(instrumentation) {
                connectedController.stop()
                connectedController.clearMediaItems()
                connectedController.repeatMode = Player.REPEAT_MODE_ALL
                connectedController.shuffleModeEnabled = true
                val selectedTrackParameters = connectedController.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                connectedController.trackSelectionParameters = selectedTrackParameters
                expectedTrackSelectionParameters.set(selectedTrackParameters)
                connectedController.setPlaybackParameters(expectedPlaybackParameters)
                connectedController.setMediaItems(
                    listOf(
                        mediaItem("memory-guard-0", wavFiles[0]),
                        mediaItem("memory-guard-1", wavFiles[1]),
                        mediaItem("memory-guard-2", wavFiles[2])
                    ),
                    0,
                    0L
                )
                connectedController.prepare()
                connectedController.play()
            }
            awaitCondition(
                instrumentation,
                "memory-guard source track did not start",
                diagnostics = { "service=${transitionDebugState(instrumentation, connectedController)}" }
            ) {
                runOnMainSync(instrumentation) {
                    connectedController.currentMediaItem?.mediaId == "memory-guard-0" &&
                        connectedController.isPlaying && connectedController.mediaItemCount == 3
                }
            }
            assertAudioEffectsDetached(instrumentation, connectedController)
            runOnMainSync(instrumentation) { connectedController.seekTo(5_000L) }
            awaitCondition(instrumentation, "memory-guard test seek did not finish") {
                currentPosition(instrumentation, connectedController) >= 5_000L &&
                    isPlaying(instrumentation, connectedController)
            }
            val positionBeforeRecycle = currentPosition(instrumentation, connectedController)
            val (currentIndexBeforeRecycle, queueIdsBeforeRecycle) = runOnMainSync(instrumentation) {
                connectedController.currentMediaItemIndex to
                    (0 until connectedController.mediaItemCount).map { connectedController.getMediaItemAt(it).mediaId }
            }
            val initialDebugState = transitionDebugState(instrumentation, connectedController)
            val initialRecycleCount = memoryRecycleCount(initialDebugState)
            val initialForegroundGeneration = foregroundPlayerGeneration(initialDebugState)
            val initialForegroundPlayerIdentity = foregroundPlayerIdentity(initialDebugState)
            val recycleFuture = AtomicReference<ListenableFuture<SessionResult>>()
            runOnMainSync(instrumentation) {
                recycleFuture.set(
                    connectedController.sendCustomCommand(
                        PlaybackTransitionProtocol.debugRecyclePlaybackCommand,
                        Bundle.EMPTY
                    )
                )
            }
            assertEquals(
                SessionResult.RESULT_SUCCESS,
                recycleFuture.get().get(15, TimeUnit.SECONDS).resultCode
            )
            awaitCondition(
                instrumentation,
                "memory-guard replacement did not restore the active session",
                diagnostics = { "service=${transitionDebugState(instrumentation, connectedController)}" },
                timeoutMs = 20_000L
            ) {
                val state = transitionDebugState(instrumentation, connectedController)
                runOnMainSync(instrumentation) {
                    connectedController.currentMediaItem?.mediaId == "memory-guard-0" &&
                        connectedController.currentMediaItemIndex == currentIndexBeforeRecycle &&
                        connectedController.isPlaying && connectedController.mediaItemCount == queueIdsBeforeRecycle.size &&
                        (0 until connectedController.mediaItemCount).map { connectedController.getMediaItemAt(it).mediaId } ==
                            queueIdsBeforeRecycle &&
                        connectedController.repeatMode == Player.REPEAT_MODE_ALL &&
                        connectedController.shuffleModeEnabled &&
                        connectedController.playbackParameters == expectedPlaybackParameters &&
                        connectedController.trackSelectionParameters == expectedTrackSelectionParameters.get()
                } && memoryRecycleCount(state) > initialRecycleCount &&
                    foregroundPlayerGeneration(state) > initialForegroundGeneration &&
                    foregroundPlayerIdentity(state) != initialForegroundPlayerIdentity
            }

            val positionAfterRecycle = currentPosition(instrumentation, connectedController)
            assertTrue(
                "foreground player replacement should resume near the saved position " +
                    "($positionBeforeRecycle -> $positionAfterRecycle)",
                positionAfterRecycle >= positionBeforeRecycle - 1_000L
            )
            assertAudioEffectsDetached(instrumentation, connectedController)
        } finally {
            controller?.let { activeController ->
                runCatching {
                    runOnMainSync(instrumentation) {
                        activeController.stop()
                        activeController.clearMediaItems()
                    }
                }
            }
            runCatching { runOnMainSync(instrumentation) { MediaController.releaseFuture(controllerFuture) } }
            runCatching { settings.setCrossfadeSeconds(originalSettings.crossfadeSeconds) }
            runCatching { settings.setManualSkipFadeSeconds(originalSettings.manualSkipFadeSeconds) }
            runCatching { settings.setEqualizerEnabled(originalSettings.equalizerEnabled) }
            runCatching { settings.setNormalizeVolume(originalSettings.normalizeVolume) }
            wavFiles.forEach { runCatching { it.delete() } }
        }
    }

    @OptIn(UnstableApi::class)
    @Test
    fun manualAndAutomaticCrossfadesRecycleTheStandbyPlayerAcrossQueueTransitions() {
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
        settings.setEqualizerEnabled(false)
        settings.setNormalizeVolume(false)
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
            assertAudioEffectsDetached(instrumentation, activeController)

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
            assertAudioEffectsDetached(instrumentation, activeController)

            // Also cover automatic crossfades. Long clips plus an explicit seek give the standby
            // decoder time to prepare before the fade window, even on slow API 36 emulators.
            runOnMainSync(instrumentation) {
                settings.setCrossfadeSeconds(4)
                settings.setManualSkipFadeSeconds(0)
                activeController.stop()
                activeController.clearMediaItems()
                activeController.repeatMode = Player.REPEAT_MODE_OFF
                activeController.shuffleModeEnabled = false
                activeController.setMediaItems(
                    listOf(
                        mediaItem("automatic-0", wavFiles[0]),
                        mediaItem("automatic-1", wavFiles[1]),
                        mediaItem("automatic-2", wavFiles[2])
                    ),
                    0,
                    0L
                )
                activeController.prepare()
                activeController.play()
            }
            awaitCondition(
                instrumentation,
                "automatic source track or its next queue item did not become available",
                diagnostics = {
                    runOnMainSync(instrumentation) {
                        "id=${activeController.currentMediaItem?.mediaId}, " +
                            "isPlaying=${activeController.isPlaying}, " +
                            "position=${activeController.currentPosition}, " +
                            "itemCount=${activeController.mediaItemCount}, " +
                            "nextIndex=${activeController.nextMediaItemIndex}"
                    }
                }
            ) {
                runOnMainSync(instrumentation) {
                    activeController.currentMediaItem?.mediaId == "automatic-0" &&
                        activeController.isPlaying &&
                        activeController.mediaItemCount == 3 &&
                        activeController.nextMediaItemIndex == 1
                }
            }
            val initialAutomaticGeneration = standbyGeneration(
                transitionDebugState(instrumentation, activeController)
            )
            runOnMainSync(instrumentation) {
                activeController.seekTo(activeController.currentMediaItemIndex, 23_000L)
            }
            awaitCondition(
                instrumentation,
                "first automatic crossfade did not recycle the standby player",
                diagnostics = {
                    "mediaId=${currentMediaId(instrumentation, activeController)}, " +
                        "position=${currentPosition(instrumentation, activeController)}, " +
                        "service=${transitionDebugState(instrumentation, activeController)}"
                },
                timeoutMs = 25_000L
            ) {
                val state = transitionDebugState(instrumentation, activeController)
                currentMediaId(instrumentation, activeController) == "automatic-1" &&
                    currentPosition(instrumentation, activeController) >= 500L &&
                    standbyGeneration(state) > initialAutomaticGeneration
            }
            val afterFirstAutomaticGeneration = standbyGeneration(
                transitionDebugState(instrumentation, activeController)
            )
            assertTrue(
                "the standby player should be recreated after the first automatic crossfade",
                afterFirstAutomaticGeneration > initialAutomaticGeneration
            )
            assertAudioEffectsDetached(instrumentation, activeController)

            runOnMainSync(instrumentation) {
                activeController.seekTo(activeController.currentMediaItemIndex, 23_000L)
            }
            awaitCondition(
                instrumentation,
                "second automatic crossfade did not recycle the standby player",
                diagnostics = {
                    "mediaId=${currentMediaId(instrumentation, activeController)}, " +
                        "position=${currentPosition(instrumentation, activeController)}, " +
                        "service=${transitionDebugState(instrumentation, activeController)}"
                },
                timeoutMs = 25_000L
            ) {
                val state = transitionDebugState(instrumentation, activeController)
                currentMediaId(instrumentation, activeController) == "automatic-2" &&
                    currentPosition(instrumentation, activeController) >= 500L &&
                    standbyGeneration(state) > afterFirstAutomaticGeneration
            }
            val afterSecondAutomaticGeneration = standbyGeneration(
                transitionDebugState(instrumentation, activeController)
            )
            assertTrue(
                "the standby player should be recreated after the second automatic crossfade",
                afterSecondAutomaticGeneration > afterFirstAutomaticGeneration
            )
            assertAudioEffectsDetached(instrumentation, activeController)
            assertTrue("playback should continue on the final track", isPlaying(instrumentation, activeController))
        } finally {
            controller?.let { activeController ->
                runCatching {
                    runOnMainSync(instrumentation) {
                        activeController.stop()
                        activeController.clearMediaItems()
                    }
                }
            }
            runCatching { runOnMainSync(instrumentation) { MediaController.releaseFuture(controllerFuture) } }
            runCatching { settings.setCrossfadeSeconds(originalSettings.crossfadeSeconds) }
            runCatching { settings.setManualSkipFadeSeconds(originalSettings.manualSkipFadeSeconds) }
            runCatching { settings.setEqualizerEnabled(originalSettings.equalizerEnabled) }
            runCatching { settings.setNormalizeVolume(originalSettings.normalizeVolume) }
            wavFiles.forEach { runCatching { it.delete() } }
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

    private fun hasActiveAudioSession(debugState: String): Boolean = debugState
        .substringAfter("sessions=", "")
        .substringBefore(';')
        .substringBefore('/')
        .toIntOrNull()
        ?.let { it > 0 }
        ?: false

    @OptIn(UnstableApi::class)
    private fun assertAudioEffectsDetached(
        instrumentation: android.app.Instrumentation,
        controller: MediaController
    ) {
        val debugState = transitionDebugState(instrumentation, controller)
        assertTrue(
            "disabled equalizer/loudness effects should not own native instances: $debugState",
            debugState.contains("effects=eq0/le0")
        )
        assertTrue(
            "audio-only playback must keep video tracks disabled: $debugState",
            debugState.contains("videoDisabled=true/")
        )
        if (!debugState.contains("standby=uninitialized")) {
            assertTrue(
                "an initialized standby player must also keep video tracks disabled: $debugState",
                debugState.contains("videoDisabled=true/true")
            )
        }
        assertTrue(
            "the active MediaController must also report video disabled",
            runOnMainSync(instrumentation) {
                C.TRACK_TYPE_VIDEO in controller.trackSelectionParameters.disabledTrackTypes
            }
        )
    }

    private fun mediaItem(id: String, file: File): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(android.net.Uri.fromFile(file))
        .build()

    private fun createWaveFile(
        directory: File,
        prefix: String,
        frequencyHz: Double,
        durationSeconds: Int = 30,
        sampleRate: Int = 44_100
    ): File {
        val file = File.createTempFile(prefix, ".wav", directory)
        require(durationSeconds > 0 && sampleRate > 0)
        // The ordinary-switch regression uses long clips so slow emulators cannot race natural EOS.
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

    private fun foregroundPlayerGeneration(debugState: String): Int =
        debugState.split(';')
            .firstOrNull { it.startsWith("foregroundGeneration=") }
            ?.substringAfter('=')
            ?.toIntOrNull()
            ?: error("foreground player generation missing from debug state: $debugState")

    private fun foregroundPlayerIdentity(debugState: String): Int =
        debugState.split(';')
            .firstOrNull { it.startsWith("foregroundPlayerIdentity=") }
            ?.substringAfter('=')
            ?.toIntOrNull()
            ?: error("foreground player identity missing from debug state: $debugState")

    private fun memoryRecycleCount(debugState: String): Int =
        debugState.substringAfter("memory=", "")
            .substringBefore(';')
            .split('/')
            .getOrNull(3)
            ?.toIntOrNull()
            ?: error("memory guard recycle count missing from debug state: $debugState")

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
        timeoutMs: Long = 15_000L,
        condition: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
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

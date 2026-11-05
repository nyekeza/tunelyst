package com.tunelyst.music.playback

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tunelyst.music.MainActivity
import com.tunelyst.music.core.logic.isTunelystLowMemoryDevice
import com.tunelyst.music.data.AudioSettingsContract
import com.tunelyst.music.data.AudioSettingsStore
import com.tunelyst.music.data.CrossfadeCurve
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** Owns playback outside the Activity so music and media controls survive navigation/backgrounding. */
@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    // The foreground slot is the single service-owned player used for ordinary track changes.
    // A second player is created only when a crossfade needs an overlapping decode.
    internal var foregroundPlayerRef: ExoPlayer? = null
    internal val foregroundPlayer: ExoPlayer
        get() = checkNotNull(foregroundPlayerRef) { "Foreground player is not initialized" }
    internal var standbyPlayerRef: ExoPlayer? = null
    internal val standbyPlayer: ExoPlayer
        get() = checkNotNull(standbyPlayerRef) { "Standby player is not initialized" }
    internal lateinit var mediaSession: MediaSession
    internal lateinit var audioPreferences: SharedPreferences
    internal lateinit var audioSettingsStore: AudioSettingsStore
    internal lateinit var audioManager: AudioManager
    internal var standbyPlayerGeneration = 0
    internal var foregroundPlayerGeneration = 0
    internal var memoryGuardThresholdBytes = 0L
    internal var memoryGuardRecoveryBaselinePressureBytes = 0L
    internal var memoryGuardHighSamples = 0
    internal var memoryGuardLastSampleElapsedMs = 0L
    internal var memoryGuardLastRecoveryAttemptElapsedMs = 0L
    internal var memoryGuardLastNativePssBytes = 0L
    internal var memoryGuardLastTotalPssBytes = 0L
    internal var memoryGuardLastNativeAllocatedBytes = 0L
    internal var memoryGuardLastPressureBytes = 0L
    internal var memoryGuardRecoveryAttemptCount = 0
    internal var memoryGuardRecycleCount = 0

    internal fun isForegroundPlayerInitialized(): Boolean = foregroundPlayerRef != null
    internal fun isStandbyPlayerInitialized(): Boolean = standbyPlayerRef != null
    internal fun isAudioSettingsStoreInitialized(): Boolean = ::audioSettingsStore.isInitialized
    internal fun isAudioSettingsInitialized(): Boolean = ::audioSettings.isInitialized

    internal val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    internal val isDebuggableBuild: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    internal val musicAudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }

    internal val playerListeners = mutableMapOf<ExoPlayer, Player.Listener>()
    internal val pendingPlaybackErrorRecoveries = mutableMapOf<ExoPlayer, Runnable>()
    internal val appliedReplayGainByPlayer = mutableMapOf<ExoPlayer, AppliedReplayGain>()
    internal val audioSessionIds = mutableMapOf<ExoPlayer, Int>()
    internal val equalizers = mutableMapOf<ExoPlayer, Equalizer>()
    internal val equalizerSessionIds = mutableMapOf<ExoPlayer, Int>()
    internal val loudnessEnhancers = mutableMapOf<ExoPlayer, LoudnessEnhancer>()
    internal val loudnessEnhancerSessionIds = mutableMapOf<ExoPlayer, Int>()
    internal lateinit var audioSettings: SettingsSnapshot
    internal var crossfading = false
    internal var crossfadeKind: CrossfadeKind? = null
    internal var preparedNextIndex = C.INDEX_UNSET
    internal var targetMediaId: String? = null
    internal var fadeSourceIndex = C.INDEX_UNSET
    internal var fadeStartPositionMs = 0L
    internal var fadeDurationMs = 0L
    internal var pendingManualTransition: PlaybackTransitionRequest? = null
    internal var activeManualTransition: PlaybackTransitionRequest? = null
    internal var manualTransitionSourceMediaId: String? = null
    internal var manualTransitionSourceQueue: List<MediaItem>? = null
    internal var manualTransitionSourceRepeatMode: Int? = null
    internal var manualTransitionSourceShuffleEnabled: Boolean? = null
    internal var manualFadeElapsedMs = 0L
    internal var manualFadeStartedAtElapsedMs = 0L
    internal var manualTransitionTimeout: Runnable? = null
    internal var lastManualTransitionDebugEvent = "idle"
    internal var sleepTimerEndElapsedMs = 0L
    internal var sleepTimerFadeOutMs = 0L
    internal var sleepFadeWindowStartElapsedMs = 0L
    internal var sleepFadeWindowEndElapsedMs = 0L
    internal var sleepTimerFadeActive = false
    internal var sleepTimerVolumeApplied = false
    internal var crossfadeCancelRestoreStartVolume: Float? = null
    internal var crossfadeCancelRestoreStartedAtMs = 0L
    internal var transientRetryAttemptedMediaId: String? = null
    internal var unexpectedPauseStartedAtElapsedMs = 0L
    internal var lastUnexpectedPauseRecoveryAtElapsedMs = 0L

    internal val mediaSessionCallback: MediaSession.Callback = object : MediaSession.Callback {
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.ConnectionResult> {
            val defaults = if (controller.isTrusted) {
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
            } else {
                MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_COMMANDS
            }
            val connection = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (controller.packageName == packageName) {
                val commands = defaults.buildUpon().add(PlaybackTransitionProtocol.transitionToQueueCommand)
                if (isDebuggableBuild) {
                    commands.add(PlaybackTransitionProtocol.debugTransitionStateCommand)
                    commands.add(PlaybackTransitionProtocol.debugRecyclePlaybackCommand)
                }
                connection.setAvailableSessionCommands(commands.build())
            }
            return Futures.immediateFuture(connection.build())
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            if (isDebuggableBuild &&
                customCommand.customAction == PlaybackTransitionProtocol.ACTION_GET_DEBUG_STATE
            ) {
                val extras = Bundle().apply {
                    putString(
                        PlaybackTransitionProtocol.EXTRA_DEBUG_STATE,
                        manualTransitionDebugState()
                    )
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, extras))
            }
            if (isDebuggableBuild &&
                customCommand.customAction == PlaybackTransitionProtocol.ACTION_DEBUG_RECYCLE_PLAYBACK
            ) {
                val recycled = recyclePlaybackPipelineForMemoryPressure()
                val resultCode = if (recycled) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE
                return Futures.immediateFuture(SessionResult(resultCode))
            }
            if (customCommand.customAction != PlaybackTransitionProtocol.ACTION_TRANSITION_TO_QUEUE) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            val requestId = args.getString(PlaybackTransitionProtocol.EXTRA_REQUEST_ID).orEmpty()
            val request = PendingPlaybackTransitionRequests.take(requestId)
            val accepted = request?.let { runCatching { transitionToQueue(it) }.getOrDefault(false) } == true
            val resultCode = if (accepted) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE
            return Futures.immediateFuture(SessionResult(resultCode))
        }
    }

    internal val outputDeviceCallback: AudioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { refreshDeviceEqualizerProfile() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { refreshDeviceEqualizerProfile() }
    }

    internal val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val previousCrossfade = audioSettings.crossfadeSeconds
        val previousManualFade = audioSettings.manualSkipFadeSeconds
        audioSettings = readAudioSettings()
        refreshSleepTimerSchedule()
        applyEqualizerSettings()
        applyPlaybackParameters()
        applyLoudnessSettings()
        if (audioSettings.crossfadeSeconds == 0 && previousCrossfade != 0 && crossfadeKind == CrossfadeKind.AUTOMATIC) {
            cancelCrossfade(restoreVolume = true, smoothRestore = true)
        }
        if (audioSettings.manualSkipFadeSeconds == 0 && previousManualFade != 0) {
            val manualRequest = pendingManualTransition ?: activeManualTransition
            if (manualRequest != null) {
                cancelCrossfade(restoreVolume = !sleepTimerFadeActive)
                applyManualTransitionImmediately(manualRequest, restoreVolume = !sleepTimerFadeActive)
            }
        }
        if (audioSettings.crossfadeSeconds == 0 && audioSettings.manualSkipFadeSeconds == 0 &&
            !crossfading && pendingManualTransition == null && activeManualTransition == null
        ) {
            releaseStandbyPlayer("crossfades disabled")
        }
    }

    internal val crossfadeTicker: Runnable = object : Runnable {
        override fun run() {
            if (isForegroundPlayerInitialized()) {
                updateSleepTimer()
                recoverUnexpectedReadyPause()
                applyReplayGain(foregroundPlayer, foregroundPlayer.currentMediaItem)
                if (isStandbyPlayerInitialized()) {
                    applyReplayGain(standbyPlayer, standbyPlayer.currentMediaItem)
                }
                updateCrossfade()
                updatePlaybackMemoryGuard()
            }
            val nextDelayMs = when {
                crossfadeCancelRestoreStartVolume != null -> CROSSFADE_CANCEL_RESTORE_TICK_MS
                crossfading -> TICK_INTERVAL_MS
                sleepTimerEndElapsedMs > 0L || audioSettings.crossfadeSeconds > 0 ||
                    pendingManualTransition != null -> ACTIVE_FEATURE_TICK_INTERVAL_MS
                else -> IDLE_TICK_INTERVAL_MS
            }
            mainHandler.postDelayed(this, nextDelayMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        var initialPlayer: ExoPlayer? = null
        try {
            audioPreferences = getSharedPreferences(AudioSettingsContract.PREFERENCES_NAME, MODE_PRIVATE)
            audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioSettingsStore = AudioSettingsStore(this)
            audioSettings = readAudioSettings()
            refreshSleepTimerSchedule()
            audioPreferences.registerOnSharedPreferenceChangeListener(settingsListener)

            val player = createPlayer(handleAudioFocus = true)
            initialPlayer = player
            foregroundPlayerRef = player
            foregroundPlayerGeneration = 1
            installListener(player)
            applyPlaybackParameters()
            applyLoudnessSettings()
            val sessionActivity = PendingIntent.getActivity(
                this,
                SESSION_ACTIVITY_REQUEST_CODE,
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_NOW_PLAYING)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            mediaSession = MediaSession.Builder(this, createSessionPlayer(player))
                .setCallback(mediaSessionCallback)
                .setSessionActivity(sessionActivity)
                .build()
            runCatching { audioManager.registerAudioDeviceCallback(outputDeviceCallback, mainHandler) }
            initializePlaybackMemoryGuard()
            mainHandler.post(crossfadeTicker)
        } catch (failure: Throwable) {
            mainHandler.removeCallbacksAndMessages(null)
            CrossfadeDisplayState.clearIncoming()
            if (::mediaSession.isInitialized) runCatching { mediaSession.release() }
            if (::audioManager.isInitialized) {
                runCatching { audioManager.unregisterAudioDeviceCallback(outputDeviceCallback) }
            }
            if (::audioPreferences.isInitialized) {
                runCatching { audioPreferences.unregisterOnSharedPreferenceChangeListener(settingsListener) }
            }
            foregroundPlayerRef = null
            standbyPlayerRef = null
            initialPlayer?.let { releaseDetachedPlayerImpl(it, "service initialization failed") }
            throw failure
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    internal fun manualTransitionDebugState(): String = this.manualTransitionDebugStateImpl()

    override fun onTaskRemoved(rootIntent: Intent?) {
        val state = if (isForegroundPlayerInitialized()) foregroundPlayer.playbackState else Player.STATE_IDLE
        val playbackShouldContinue = isForegroundPlayerInitialized() &&
            (foregroundPlayer.isPlaying ||
                (foregroundPlayer.playWhenReady && state != Player.STATE_IDLE && state != Player.STATE_ENDED))
        if (playbackShouldContinue) {
            // Keep media playback alive when the user dismisses the app task; the MediaSessionService
            // remains the owner of playback even though the Activity/controller is gone.
            Log.d("TunelystPlayback", "Keeping the playback service alive after task removal")
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        CrossfadeDisplayState.clearIncoming()
        manualTransitionTimeout = null
        pendingManualTransition = null
        activeManualTransition = null
        manualTransitionSourceQueue = null
        if (::audioManager.isInitialized) runCatching { audioManager.unregisterAudioDeviceCallback(outputDeviceCallback) }
        if (::audioPreferences.isInitialized) audioPreferences.unregisterOnSharedPreferenceChangeListener(settingsListener)

        // Release the MediaSession first so it stops forwarding to either player. Null service
        // references before release so no later recovery path can accidentally reuse a dead player.
        if (::mediaSession.isInitialized) runCatching { mediaSession.release() }
        val playersToRelease = (
            listOfNotNull(foregroundPlayerRef, standbyPlayerRef) +
                playerListeners.keys + equalizers.keys + equalizerSessionIds.keys +
                loudnessEnhancers.keys + loudnessEnhancerSessionIds.keys + audioSessionIds.keys +
                appliedReplayGainByPlayer.keys + pendingPlaybackErrorRecoveries.keys
            ).distinct()
        foregroundPlayerRef = null
        standbyPlayerRef = null
        playersToRelease.forEach { releaseDetachedPlayer(it, "playback service destroyed") }
        pendingPlaybackErrorRecoveries.clear()
        playerListeners.clear()
        equalizers.clear()
        equalizerSessionIds.clear()
        audioSessionIds.clear()
        loudnessEnhancers.clear()
        loudnessEnhancerSessionIds.clear()
        appliedReplayGainByPlayer.clear()
        super.onDestroy()
    }

    internal fun createPlayer(handleAudioFocus: Boolean): ExoPlayer {
        val bufferPolicy = PlaybackBufferPolicy.forLowMemoryDevice(isLowMemoryDevice())
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(
                bufferPolicy.minBufferMs,
                bufferPolicy.maxBufferMs,
                bufferPolicy.bufferForPlaybackMs,
                bufferPolicy.bufferForPlaybackAfterRebufferMs
            )
            .setBufferDurationsMsForLocalPlayback(
                bufferPolicy.minBufferMs,
                bufferPolicy.maxBufferMs,
                bufferPolicy.bufferForPlaybackMs,
                bufferPolicy.bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(bufferPolicy.targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholdsForStreaming(false)
            .setPrioritizeTimeOverSizeThresholdsForLocalPlayback(false)
            .setBackBuffer(0, false)
            .build()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(musicAudioAttributes, handleAudioFocus)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            // Keep the CPU awake while local files are decoded and transitions are serviced in background.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        try {
            // Tunelyst is an audio-only player. Files with a video stream (for example, music videos
            // indexed as audio) must not create a native video decoder when no video surface exists.
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
            return player
        } catch (failure: Throwable) {
            runCatching { player.release() }
            throw failure
        }
    }

    private fun isLowMemoryDevice(): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        return isTunelystLowMemoryDevice(
            platformLowRam = activityManager?.isLowRamDevice == true,
            memoryClassMb = activityManager?.memoryClass ?: Int.MAX_VALUE,
            maxHeapBytes = Runtime.getRuntime().maxMemory()
        )
    }

    /** Route controller track changes through the service so it can own both players' gain curves. */
    internal fun createSessionPlayer(target: ExoPlayer): Player = object : ForwardingPlayer(target) {
        override fun seekToNextMediaItem() {
            if (target !== foregroundPlayer) {
                if (foregroundPlayer.hasNextMediaItem()) {
                    requestManualTransitionToIndex(foregroundPlayer.nextMediaItemIndex)
                }
                return
            }
            if (advancePastCurrentManualTransition()) return
            if (!target.hasNextMediaItem()) {
                super.seekToNextMediaItem()
                return
            }
            requestManualTransitionToIndex(target.nextMediaItemIndex)
        }

        override fun seekToNext() {
            if (target !== foregroundPlayer) {
                if (foregroundPlayer.hasNextMediaItem()) {
                    requestManualTransitionToIndex(foregroundPlayer.nextMediaItemIndex)
                }
                return
            }
            if (advancePastCurrentManualTransition()) return
            if (!target.hasNextMediaItem()) {
                super.seekToNext()
                return
            }
            requestManualTransitionToIndex(target.nextMediaItemIndex)
        }

        override fun seekToPreviousMediaItem() {
            if (target !== foregroundPlayer || !target.hasPreviousMediaItem()) {
                super.seekToPreviousMediaItem()
                return
            }
            requestManualTransitionToIndex(target.previousMediaItemIndex)
        }

        override fun seekToPrevious() {
            if (target !== foregroundPlayer) {
                super.seekToPrevious()
                return
            }
            if (target.currentPosition > target.maxSeekToPreviousPosition || !target.hasPreviousMediaItem()) {
                target.seekTo(0L)
            } else {
                requestManualTransitionToIndex(target.previousMediaItemIndex)
            }
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (target !== foregroundPlayer || mediaItemIndex == target.currentMediaItemIndex ||
                mediaItemIndex !in 0 until target.mediaItemCount
            ) {
                super.seekTo(mediaItemIndex, positionMs)
                return
            }
            requestManualTransitionToIndex(mediaItemIndex, positionMs)
        }

        override fun seekToDefaultPosition(mediaItemIndex: Int) {
            if (target !== foregroundPlayer || mediaItemIndex == target.currentMediaItemIndex ||
                mediaItemIndex !in 0 until target.mediaItemCount
            ) {
                super.seekToDefaultPosition(mediaItemIndex)
                return
            }
            requestManualTransitionToIndex(mediaItemIndex)
        }
    }

    /** Recover only a genuine stuck-ready state; never override a user/system pause or audio-focus suppression. */
    internal fun recoverUnexpectedReadyPause() = this.recoverUnexpectedReadyPauseImpl()

    internal fun initializePlaybackMemoryGuard() = this.initializePlaybackMemoryGuardImpl()

    internal fun updatePlaybackMemoryGuard() = this.updatePlaybackMemoryGuardImpl()

    internal fun recyclePlaybackPipelineForMemoryPressure(): Boolean =
        this.recyclePlaybackPipelineForMemoryPressureImpl()

    internal fun ensureStandbyPlayer(): ExoPlayer = this.ensureStandbyPlayerImpl()

    internal fun recycleStandbyPlayer() = this.recycleStandbyPlayerImpl()

    internal fun releaseStandbyPlayer(reason: String) = this.releaseStandbyPlayerImpl(reason)

    internal fun releaseDetachedPlayer(target: ExoPlayer, reason: String) =
        this.releaseDetachedPlayerImpl(target, reason)

    @OptIn(UnstableApi::class)
    internal fun installListener(target: ExoPlayer) = this.installListenerImpl(target)

    /**
     * Recovery belongs in the playback service, not the Activity-scoped ViewModel: the latter may
     * be detached while audio is playing in the background. Retry one transient read failure, then
     * advance once so a bad file cannot leave an otherwise playable queue stranded.
     */
    internal fun recoverPlaybackAfterError(target: ExoPlayer, error: PlaybackException) = this.recoverPlaybackAfterErrorImpl(target, error)

    /** The service owns the deadline and fade so Activity/ViewModel teardown cannot cancel playback automation. */
    internal fun refreshSleepTimerSchedule() = this.refreshSleepTimerScheduleImpl()

    internal fun updateSleepTimerFadeState() = this.updateSleepTimerFadeStateImpl()

    internal fun updateSleepTimer() = this.updateSleepTimerImpl()

    internal fun updateCrossfade() = this.updateCrossfadeImpl()

    internal fun applyCrossfadeGains(progress: Float) = this.applyCrossfadeGainsImpl(progress)

    internal fun updateManualCrossfade() = this.updateManualCrossfadeImpl()

    internal fun sameAlbum(first: MediaItem?, second: MediaItem?): Boolean = this.sameAlbumImpl(first, second)

    internal fun advancePastCurrentManualTransition(): Boolean = this.advancePastCurrentManualTransitionImpl()

    internal fun requestManualTransitionToIndex(index: Int, positionMs: Long = C.TIME_UNSET) = this.requestManualTransitionToIndexImpl(index, positionMs)

    /** Starts a true two-player overlap when the manual fade setting is enabled. */
    internal fun transitionToQueue(request: PlaybackTransitionRequest): Boolean = this.transitionToQueueImpl(request)

    internal fun queuesHaveSameIds(items: List<MediaItem>, source: ExoPlayer): Boolean = this.queuesHaveSameIdsImpl(items, source)

    internal fun maybeBeginManualTransition() = this.maybeBeginManualTransitionImpl()

    internal fun scheduleManualTransitionTimeout(timeoutMs: Long = MANUAL_PREPARE_TIMEOUT_MS) = this.scheduleManualTransitionTimeoutImpl(timeoutMs)

    internal fun clearManualTransitionTimeout() = this.clearManualTransitionTimeoutImpl()

    internal fun applyPendingManualTransitionImmediately() = this.applyPendingManualTransitionImmediatelyImpl()

    /** Complete a manual skip without a fade if the active overlap stalls before its fade clock ends. */
    internal fun applyActiveManualTransitionImmediately() = this.applyActiveManualTransitionImmediatelyImpl()

    internal fun applyQueueToForeground(request: PlaybackTransitionRequest, restoreVolume: Boolean) = this.applyQueueToForegroundImpl(request, restoreVolume)

    /** Apply a fallback without reviving playback if the user paused while the incoming item loaded. */
    internal fun applyManualTransitionImmediately(request: PlaybackTransitionRequest, restoreVolume: Boolean) = this.applyManualTransitionImmediatelyImpl(request, restoreVolume)

    internal fun resumeManualFadeClockIfPlaying() = this.resumeManualFadeClockIfPlayingImpl()

    internal fun pauseManualFadeClock() = this.pauseManualFadeClockImpl()

    internal fun refreshDeviceEqualizerProfile() = this.refreshDeviceEqualizerProfileImpl()

    @OptIn(UnstableApi::class)
    internal fun prepareNextPlayer(nextIndex: Int) = this.prepareNextPlayerImpl(nextIndex)

    internal fun beginCrossfade(startPositionMs: Long, durationMs: Long, nextIndex: Int) = this.beginCrossfadeImpl(startPositionMs, durationMs, nextIndex)

    internal fun completeCrossfade() = this.completeCrossfadeImpl()

    internal fun cancelCrossfade(
        restoreVolume: Boolean,
        smoothRestore: Boolean = false,
        debugReason: String = "unspecified"
    ) = this.cancelCrossfadeImpl(restoreVolume, smoothRestore, debugReason)

    internal fun updateCrossfadeCancelVolumeRestore(): Boolean = this.updateCrossfadeCancelVolumeRestoreImpl()

    internal fun clearCrossfadeCancelVolumeRestore() = this.clearCrossfadeCancelVolumeRestoreImpl()

    internal fun clearPreparedPlayer() = this.clearPreparedPlayerImpl()

    internal fun attachEqualizer(target: ExoPlayer, audioSessionId: Int) = this.attachEqualizerImpl(target, audioSessionId)

    internal fun releaseEqualizer(target: ExoPlayer) = this.releaseEqualizerImpl(target)

    internal fun ensureEqualizerAttached(target: ExoPlayer) = this.ensureEqualizerAttachedImpl(target)

    internal fun attachLoudnessEnhancer(target: ExoPlayer, audioSessionId: Int) = this.attachLoudnessEnhancerImpl(target, audioSessionId)

    internal fun releaseLoudnessEnhancer(target: ExoPlayer) = this.releaseLoudnessEnhancerImpl(target)

    internal fun ensureLoudnessEnhancerAttached(target: ExoPlayer) = this.ensureLoudnessEnhancerAttachedImpl(target)

    internal fun applyEqualizerSettings() = this.applyEqualizerSettingsImpl()

    internal fun applyPlaybackParameters() = this.applyPlaybackParametersImpl()

    internal fun applyLoudnessSettings() = this.applyLoudnessSettingsImpl()

    internal fun applyReplayGain(target: ExoPlayer, item: MediaItem?) = this.applyReplayGainImpl(target, item)

    internal fun applyEqualizer(effect: Equalizer) = this.applyEqualizerImpl(effect)

    internal fun readAudioSettings(): SettingsSnapshot = this.readAudioSettingsImpl()


}

package com.tunelyst.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tunelyst.music.BuildConfig
import com.tunelyst.music.MainActivity
import com.tunelyst.music.data.AudioSettingsContract
import com.tunelyst.music.data.AudioSettingsStore
import com.tunelyst.music.data.CrossfadeCurve
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** Owns playback outside the Activity so music and media controls survive navigation/backgrounding. */
@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private lateinit var foregroundPlayer: ExoPlayer
    private lateinit var standbyPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var audioPreferences: SharedPreferences
    private lateinit var audioSettingsStore: AudioSettingsStore
    private lateinit var audioManager: AudioManager

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val musicAudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }
    private data class AppliedReplayGain(val mediaId: String?, val gainMilliBel: Int, val enabled: Boolean)

    private val playerListeners = mutableMapOf<ExoPlayer, Player.Listener>()
    private val pendingPlaybackErrorRecoveries = mutableSetOf<Runnable>()
    private val appliedReplayGainByPlayer = mutableMapOf<ExoPlayer, AppliedReplayGain>()
    private val audioSessionIds = mutableMapOf<ExoPlayer, Int>()
    private val equalizers = mutableMapOf<ExoPlayer, Equalizer>()
    private val equalizerSessionIds = mutableMapOf<ExoPlayer, Int>()
    private val loudnessEnhancers = mutableMapOf<ExoPlayer, LoudnessEnhancer>()
    private lateinit var audioSettings: SettingsSnapshot
    private enum class CrossfadeKind { AUTOMATIC, MANUAL }
    private var crossfading = false
    private var crossfadeKind: CrossfadeKind? = null
    private var preparedNextIndex = C.INDEX_UNSET
    private var targetMediaId: String? = null
    private var fadeSourceIndex = C.INDEX_UNSET
    private var fadeStartPositionMs = 0L
    private var fadeDurationMs = 0L
    private var pendingManualTransition: PlaybackTransitionRequest? = null
    private var activeManualTransition: PlaybackTransitionRequest? = null
    private var manualTransitionSourceMediaId: String? = null
    private var manualTransitionSourceQueue: List<MediaItem>? = null
    private var manualTransitionSourceRepeatMode: Int? = null
    private var manualTransitionSourceShuffleEnabled: Boolean? = null
    private var manualFadeElapsedMs = 0L
    private var manualFadeStartedAtElapsedMs = 0L
    private var manualTransitionTimeout: Runnable? = null
    private var lastManualTransitionDebugEvent = "idle"
    private var sleepTimerEndElapsedMs = 0L
    private var sleepTimerFadeOutMs = 0L
    private var sleepFadeWindowStartElapsedMs = 0L
    private var sleepFadeWindowEndElapsedMs = 0L
    private var sleepTimerFadeActive = false
    private var sleepTimerVolumeApplied = false
    private var crossfadeCancelRestoreStartVolume: Float? = null
    private var crossfadeCancelRestoreStartedAtMs = 0L
    private var transientRetryAttemptedMediaId: String? = null
    private var unexpectedPauseStartedAtElapsedMs = 0L
    private var lastUnexpectedPauseRecoveryAtElapsedMs = 0L

    private val mediaSessionCallback = object : MediaSession.Callback {
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
                if (BuildConfig.DEBUG) commands.add(PlaybackTransitionProtocol.debugTransitionStateCommand)
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
            if (BuildConfig.DEBUG &&
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

    private val outputDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { refreshDeviceEqualizerProfile() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { refreshDeviceEqualizerProfile() }
    }

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
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
    }

    private val crossfadeTicker = object : Runnable {
        override fun run() {
            if (::foregroundPlayer.isInitialized) {
                updateSleepTimer()
                recoverUnexpectedReadyPause()
                applyReplayGain(foregroundPlayer, foregroundPlayer.currentMediaItem)
                if (::standbyPlayer.isInitialized) {
                    applyReplayGain(standbyPlayer, standbyPlayer.currentMediaItem)
                }
                updateCrossfade()
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
        audioPreferences = getSharedPreferences(AudioSettingsContract.PREFERENCES_NAME, MODE_PRIVATE)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioSettingsStore = AudioSettingsStore(this)
        audioSettings = readAudioSettings()
        refreshSleepTimerSchedule()
        audioPreferences.registerOnSharedPreferenceChangeListener(settingsListener)

        foregroundPlayer = createPlayer(handleAudioFocus = true)
        installListener(foregroundPlayer)
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
        mediaSession = MediaSession.Builder(this, createSessionPlayer(foregroundPlayer))
            .setCallback(mediaSessionCallback)
            .setSessionActivity(sessionActivity)
            .build()
        runCatching { audioManager.registerAudioDeviceCallback(outputDeviceCallback, mainHandler) }
        mainHandler.post(crossfadeTicker)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    private fun manualTransitionDebugState(): String {
        val source = foregroundPlayer
        val standby = if (::standbyPlayer.isInitialized) standbyPlayer else null
        val standbyState = standby?.let {
            "${it.currentMediaItem?.mediaId}/${it.playbackState}/${it.playWhenReady}/${it.isPlaying}/${it.currentPosition}"
        } ?: "uninitialized"
        return "event=$lastManualTransitionDebugEvent;pending=${pendingManualTransition != null};" +
            "active=${activeManualTransition != null};crossfade=$crossfading/$crossfadeKind;" +
            "source=${source.currentMediaItem?.mediaId}/${source.currentMediaItemIndex}/" +
            "${source.playbackState}/${source.playWhenReady}/${source.isPlaying}/${source.currentPosition};" +
            "standby=$standbyState;fade=$manualFadeElapsedMs/$fadeDurationMs;" +
            "started=$manualFadeStartedAtElapsedMs;timeout=${manualTransitionTimeout != null};" +
            "prepared=$preparedNextIndex"
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val state = if (::foregroundPlayer.isInitialized) foregroundPlayer.playbackState else Player.STATE_IDLE
        val playbackShouldContinue = ::foregroundPlayer.isInitialized &&
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
        mainHandler.removeCallbacks(crossfadeTicker)
        manualTransitionTimeout?.let(mainHandler::removeCallbacks)
        manualTransitionTimeout = null
        pendingManualTransition = null
        activeManualTransition = null
        pendingPlaybackErrorRecoveries.forEach { mainHandler.removeCallbacks(it) }
        pendingPlaybackErrorRecoveries.clear()
        if (::audioManager.isInitialized) runCatching { audioManager.unregisterAudioDeviceCallback(outputDeviceCallback) }
        if (::audioPreferences.isInitialized) audioPreferences.unregisterOnSharedPreferenceChangeListener(settingsListener)
        equalizers.values.forEach { runCatching { it.release() } }
        equalizers.clear()
        equalizerSessionIds.clear()
        audioSessionIds.clear()
        loudnessEnhancers.values.forEach { runCatching { it.release() } }
        loudnessEnhancers.clear()
        appliedReplayGainByPlayer.clear()
        if (::mediaSession.isInitialized) mediaSession.release()
        if (::foregroundPlayer.isInitialized) foregroundPlayer.release()
        if (::standbyPlayer.isInitialized) standbyPlayer.release()
        playerListeners.clear()
        super.onDestroy()
    }

    private fun createPlayer(handleAudioFocus: Boolean): ExoPlayer = ExoPlayer.Builder(this)
        .setAudioAttributes(musicAudioAttributes, handleAudioFocus)
        .setHandleAudioBecomingNoisy(true)
        // Keep the CPU awake while local files are decoded and transitions are serviced in background.
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()

    /** Route controller track changes through the service so it can own both players' gain curves. */
    private fun createSessionPlayer(target: ExoPlayer): Player = object : ForwardingPlayer(target) {
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
    private fun recoverUnexpectedReadyPause() {
        if (!::foregroundPlayer.isInitialized) return
        val player = foregroundPlayer
        val now = SystemClock.elapsedRealtime()
        val shouldBeAudiblyPlaying = player.playWhenReady &&
            player.playbackState == Player.STATE_READY &&
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            !player.isPlaying
        if (!shouldBeAudiblyPlaying) {
            unexpectedPauseStartedAtElapsedMs = 0L
            return
        }
        if (unexpectedPauseStartedAtElapsedMs == 0L) {
            unexpectedPauseStartedAtElapsedMs = now
            return
        }
        val stuckForMs = now - unexpectedPauseStartedAtElapsedMs
        val sinceLastAttemptMs = now - lastUnexpectedPauseRecoveryAtElapsedMs
        if (stuckForMs < UNEXPECTED_PAUSE_RECOVERY_DELAY_MS ||
            sinceLastAttemptMs < UNEXPECTED_PAUSE_RECOVERY_COOLDOWN_MS
        ) return

        Log.w(
            "TunelystPlayback",
            "Recovering a ready player that remained paused without user intent or audio-focus suppression"
        )
        lastUnexpectedPauseRecoveryAtElapsedMs = now
        player.play()
    }

    private fun ensureStandbyPlayer(): ExoPlayer {
        if (!::standbyPlayer.isInitialized) {
            standbyPlayer = createPlayer(handleAudioFocus = false)
            installListener(standbyPlayer)
            if (::audioSettings.isInitialized) {
                standbyPlayer.setPlaybackParameters(PlaybackParameters(audioSettings.playbackSpeed, audioSettings.playbackPitch))
                applyReplayGain(standbyPlayer, standbyPlayer.currentMediaItem)
            }
        }
        return standbyPlayer
    }

    @OptIn(UnstableApi::class)
    private fun installListener(target: ExoPlayer) {
        val listener = object : Player.Listener {
            @OptIn(UnstableApi::class)
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId > 0) audioSessionIds[target] = audioSessionId
                else audioSessionIds.remove(target)
                attachEqualizer(target, audioSessionId)
                attachLoudnessEnhancer(target, audioSessionId)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && audioSettings.equalizerEnabled) {
                    ensureEqualizerAttached(target)
                }
                if (::standbyPlayer.isInitialized && target === standbyPlayer && playbackState == Player.STATE_READY) {
                    if (pendingManualTransition != null) maybeBeginManualTransition()
                    else if (!crossfading && preparedNextIndex != C.INDEX_UNSET) updateCrossfade()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                applyReplayGain(target, mediaItem)
                if (target !== foregroundPlayer) return
                if (mediaItem?.mediaId != transientRetryAttemptedMediaId) transientRetryAttemptedMediaId = null
                val expectedAutoTransition = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                val manualRequest = pendingManualTransition ?: activeManualTransition
                if (manualRequest != null) {
                    cancelCrossfade(restoreVolume = !sleepTimerFadeActive)
                    applyManualTransitionImmediately(manualRequest, restoreVolume = !sleepTimerFadeActive)
                } else if (crossfading && expectedAutoTransition && mediaItem?.mediaId == targetMediaId) {
                    completeCrossfade()
                } else if (crossfading || preparedNextIndex != C.INDEX_UNSET) {
                    cancelCrossfade(restoreVolume = !sleepTimerFadeActive, smoothRestore = true)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                recoverPlaybackAfterError(target, error)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                applyReplayGain(target, target.currentMediaItem)
                if (target === foregroundPlayer && reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    transientRetryAttemptedMediaId = null
                }
                val manualTransitionIsUnchanged = (pendingManualTransition != null ||
                    crossfading && crossfadeKind == CrossfadeKind.MANUAL) &&
                    manualTransitionSourceQueue?.let { queuesHaveSameIds(it, target) } == true &&
                    manualTransitionSourceRepeatMode == target.repeatMode &&
                    manualTransitionSourceShuffleEnabled == target.shuffleModeEnabled
                if (target === foregroundPlayer &&
                    (crossfading || preparedNextIndex != C.INDEX_UNSET || pendingManualTransition != null) &&
                    !manualTransitionIsUnchanged
                ) {
                    cancelCrossfade(
                        restoreVolume = !sleepTimerFadeActive,
                        smoothRestore = true,
                        debugReason = "timeline-changed"
                    )
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                val manualRequest = pendingManualTransition
                    ?: activeManualTransition.takeIf { crossfadeKind == CrossfadeKind.MANUAL }
                val manualSourceIsStillCurrent = manualRequest != null &&
                    target.currentMediaItemIndex == fadeSourceIndex &&
                    target.currentMediaItem?.mediaId == manualTransitionSourceMediaId
                if (target === foregroundPlayer && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                    (crossfading || pendingManualTransition != null) && !manualSourceIsStillCurrent
                ) {
                    cancelCrossfade(
                        restoreVolume = !sleepTimerFadeActive,
                        smoothRestore = true,
                        debugReason = "position-discontinuity-$reason"
                    )
                } else if (target === foregroundPlayer && !crossfading && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                    pendingManualTransition == null
                ) {
                    clearPreparedPlayer()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && audioSettings.equalizerEnabled) ensureEqualizerAttached(target)
                if (target === foregroundPlayer && crossfading && ::standbyPlayer.isInitialized) {
                    if (isPlaying) {
                        standbyPlayer.play()
                        resumeManualFadeClockIfPlaying()
                    } else {
                        pauseManualFadeClock()
                        standbyPlayer.pause()
                    }
                } else if (::standbyPlayer.isInitialized && target === standbyPlayer &&
                    crossfading && crossfadeKind == CrossfadeKind.MANUAL
                ) {
                    if (isPlaying) resumeManualFadeClockIfPlaying() else pauseManualFadeClock()
                }
                if (target === foregroundPlayer && !isPlaying && pendingManualTransition != null &&
                    ::standbyPlayer.isInitialized && standbyPlayer.playbackState == Player.STATE_READY
                ) {
                    applyPendingManualTransitionImmediately()
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                val manualTransitionIsUnchanged = (pendingManualTransition != null ||
                    crossfading && crossfadeKind == CrossfadeKind.MANUAL) &&
                    manualTransitionSourceShuffleEnabled == shuffleModeEnabled
                if (target === foregroundPlayer &&
                    (crossfading || preparedNextIndex != C.INDEX_UNSET || pendingManualTransition != null) &&
                    !manualTransitionIsUnchanged
                ) {
                    cancelCrossfade(
                        restoreVolume = !sleepTimerFadeActive,
                        smoothRestore = true,
                        debugReason = "shuffle-changed"
                    )
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                val manualTransitionIsUnchanged = (pendingManualTransition != null ||
                    crossfading && crossfadeKind == CrossfadeKind.MANUAL) &&
                    manualTransitionSourceRepeatMode == repeatMode
                if (target === foregroundPlayer &&
                    (crossfading || preparedNextIndex != C.INDEX_UNSET || pendingManualTransition != null) &&
                    !manualTransitionIsUnchanged
                ) {
                    cancelCrossfade(
                        restoreVolume = !sleepTimerFadeActive,
                        smoothRestore = true,
                        debugReason = "repeat-changed"
                    )
                }
            }
        }
        playerListeners[target] = listener
        target.addListener(listener)
    }

    /**
     * Recovery belongs in the playback service, not the Activity-scoped ViewModel: the latter may
     * be detached while audio is playing in the background. Retry one transient read failure, then
     * advance once so a bad file cannot leave an otherwise playable queue stranded.
     */
    private fun recoverPlaybackAfterError(target: ExoPlayer, error: PlaybackException) {
        val failedItemId = target.currentMediaItem?.mediaId
        if (target !== foregroundPlayer) {
            Log.w("TunelystPlayback", "Standby player failed for $failedItemId; preserving foreground playback", error)
            if (::standbyPlayer.isInitialized && target === standbyPlayer) {
                val manualRequest = pendingManualTransition ?: activeManualTransition
                if (manualRequest != null) {
                    cancelCrossfade(restoreVolume = !sleepTimerFadeActive, smoothRestore = true)
                    applyManualTransitionImmediately(manualRequest, restoreVolume = false)
                } else if (crossfading) {
                    cancelCrossfade(restoreVolume = !sleepTimerFadeActive)
                } else if (preparedNextIndex != C.INDEX_UNSET) {
                    clearPreparedPlayer()
                }
            }
            return
        }

        Log.e("TunelystPlayback", "Foreground playback failed for $failedItemId (code ${error.errorCode})", error)
        val manualRequest = pendingManualTransition ?: activeManualTransition
        if (manualRequest != null) {
            cancelCrossfade(restoreVolume = !sleepTimerFadeActive)
            applyManualTransitionImmediately(manualRequest, restoreVolume = !sleepTimerFadeActive)
            return
        }
        if (crossfading || preparedNextIndex != C.INDEX_UNSET) {
            cancelCrossfade(restoreVolume = !sleepTimerFadeActive)
        }
        if (!target.playWhenReady) return

        val transientReadFailure = error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        if (transientReadFailure && failedItemId != null && transientRetryAttemptedMediaId != failedItemId) {
            transientRetryAttemptedMediaId = failedItemId
            val failedIndex = target.currentMediaItemIndex
            lateinit var retry: Runnable
            retry = Runnable {
                pendingPlaybackErrorRecoveries.remove(retry)
                val retryStillApplies = ::foregroundPlayer.isInitialized && target === foregroundPlayer &&
                    target.currentMediaItemIndex == failedIndex && target.currentMediaItem?.mediaId == failedItemId &&
                    target.playWhenReady && target.playbackState == Player.STATE_IDLE
                if (retryStillApplies) {
                    target.prepare()
                    target.play()
                }
            }
            pendingPlaybackErrorRecoveries += retry
            mainHandler.postDelayed(retry, TRANSIENT_PLAYBACK_RETRY_DELAY_MS)
            return
        }

        val restoreRepeatOne = target.repeatMode == Player.REPEAT_MODE_ONE
        if (restoreRepeatOne) target.repeatMode = Player.REPEAT_MODE_OFF
        if (target.hasNextMediaItem()) {
            transientRetryAttemptedMediaId = null
            target.seekToNextMediaItem()
            target.prepare()
            target.play()
        }
        if (restoreRepeatOne) target.repeatMode = Player.REPEAT_MODE_ONE
    }

    /** The service owns the deadline and fade so Activity/ViewModel teardown cannot cancel playback automation. */
    private fun refreshSleepTimerSchedule() {
        val schedule = audioSettingsStore.sleepTimerSchedule()
        sleepTimerEndElapsedMs = schedule?.endElapsedMs ?: 0L
        sleepTimerFadeOutMs = (schedule?.fadeOutSeconds ?: 0).coerceAtLeast(0) * 1_000L
        val fadeStart = if (sleepTimerFadeOutMs > 0L && sleepTimerEndElapsedMs > 0L) {
            (sleepTimerEndElapsedMs - sleepTimerFadeOutMs).coerceAtLeast(1L)
        } else 0L
        sleepFadeWindowStartElapsedMs = fadeStart
        sleepFadeWindowEndElapsedMs = if (sleepFadeWindowStartElapsedMs > 0L) sleepTimerEndElapsedMs else 0L
        if (schedule == null) {
            // Remove legacy fade-only state left by versions that did not persist a service-owned deadline.
            audioSettingsStore.clearSleepTimerFadeWindow()
        }
        updateSleepTimerFadeState()
    }

    private fun updateSleepTimerFadeState() {
        val now = SystemClock.elapsedRealtime()
        val active = sleepFadeWindowStartElapsedMs > 0L &&
            sleepFadeWindowEndElapsedMs > sleepFadeWindowStartElapsedMs &&
            now >= sleepFadeWindowStartElapsedMs && now < sleepFadeWindowEndElapsedMs
        if (active == sleepTimerFadeActive) return
        val wasActive = sleepTimerFadeActive
        sleepTimerFadeActive = active
        if (active && ::foregroundPlayer.isInitialized) {
            val manualRequest = pendingManualTransition ?: activeManualTransition
            if (manualRequest != null) {
                cancelCrossfade(restoreVolume = false)
                // Preserve the requested skip while leaving the sleep timer in control of volume.
                applyManualTransitionImmediately(manualRequest, restoreVolume = false)
            } else if (crossfading || preparedNextIndex != C.INDEX_UNSET || crossfadeCancelRestoreStartVolume != null) {
                // Sleep-timer gain is the active owner of the foreground volume; never restore it to 1 here.
                cancelCrossfade(restoreVolume = false)
            }
        } else if (!active && wasActive && sleepTimerVolumeApplied && ::foregroundPlayer.isInitialized) {
            foregroundPlayer.volume = 1f
            sleepTimerVolumeApplied = false
        }
    }

    private fun updateSleepTimer() {
        if (sleepTimerEndElapsedMs <= 0L || !::foregroundPlayer.isInitialized) return
        val remainingMs = sleepTimerEndElapsedMs - SystemClock.elapsedRealtime()
        if (sleepTimerFadeOutMs > 0L && remainingMs in 1L..sleepTimerFadeOutMs) {
            updateSleepTimerFadeState()
            foregroundPlayer.volume = (remainingMs.toFloat() / sleepTimerFadeOutMs.toFloat()).coerceIn(0f, 1f)
            sleepTimerVolumeApplied = true
        }
        if (remainingMs > 0L) return

        if (crossfading || preparedNextIndex != C.INDEX_UNSET || pendingManualTransition != null) {
            cancelCrossfade(restoreVolume = false)
        }
        if (::standbyPlayer.isInitialized) {
            standbyPlayer.pause()
            standbyPlayer.volume = 1f
        }
        foregroundPlayer.pause()
        foregroundPlayer.volume = 1f
        sleepTimerVolumeApplied = false
        sleepTimerEndElapsedMs = 0L
        sleepTimerFadeOutMs = 0L
        sleepFadeWindowStartElapsedMs = 0L
        sleepFadeWindowEndElapsedMs = 0L
        updateSleepTimerFadeState()
        audioSettingsStore.clearSleepTimer()
    }

    private fun updateCrossfade() {
        if (!::foregroundPlayer.isInitialized) return
        updateSleepTimerFadeState()
        if (sleepTimerFadeActive) {
            if (pendingManualTransition != null) {
                applyPendingManualTransitionImmediately()
            } else if (crossfading && crossfadeKind == CrossfadeKind.MANUAL && activeManualTransition != null) {
                applyActiveManualTransitionImmediately()
            } else if (crossfading || preparedNextIndex != C.INDEX_UNSET || crossfadeCancelRestoreStartVolume != null) {
                cancelCrossfade(restoreVolume = false, debugReason = "sleep-timer")
            }
            return
        }
        if (updateCrossfadeCancelVolumeRestore()) return
        if (pendingManualTransition != null) {
            maybeBeginManualTransition()
            return
        }
        if (crossfading && crossfadeKind == CrossfadeKind.MANUAL) {
            updateManualCrossfade()
            return
        }

        val player = foregroundPlayer
        if (!player.isPlaying || player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            if (crossfading && !player.playWhenReady && ::standbyPlayer.isInitialized) standbyPlayer.pause()
            return
        }
        val requestedMs = audioSettings.crossfadeSeconds * 1_000L
        if (requestedMs <= 0L || player.repeatMode == Player.REPEAT_MODE_ONE || !player.hasNextMediaItem()) {
            if (crossfading || preparedNextIndex != C.INDEX_UNSET) cancelCrossfade(restoreVolume = true)
            return
        }
        val durationMs = player.duration
        if (durationMs <= 0L || durationMs == C.TIME_UNSET) return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until player.mediaItemCount) return
        if (audioSettings.crossfadeAlbumBoundariesOnly && sameAlbum(player.currentMediaItem, player.getMediaItemAt(nextIndex))) {
            if (crossfading || preparedNextIndex != C.INDEX_UNSET) cancelCrossfade(restoreVolume = true)
            return
        }

        val effectiveFadeMs = minOf(requestedMs, (durationMs / 2L).coerceAtLeast(1L))
        val remainingMs = (durationMs - player.currentPosition).coerceAtLeast(0L)
        if (remainingMs <= effectiveFadeMs + PRELOAD_LEAD_MS && preparedNextIndex != nextIndex && !crossfading) {
            prepareNextPlayer(nextIndex)
        }
        if (!crossfading && remainingMs in 1L..effectiveFadeMs && preparedNextIndex == nextIndex &&
            ::standbyPlayer.isInitialized && standbyPlayer.playbackState == Player.STATE_READY
        ) {
            beginCrossfade(player.currentPosition, minOf(effectiveFadeMs, remainingMs), nextIndex)
        }
        if (crossfading && crossfadeKind == CrossfadeKind.AUTOMATIC) {
            if (player.currentMediaItemIndex != fadeSourceIndex || player.nextMediaItemIndex != preparedNextIndex) {
                cancelCrossfade(restoreVolume = true, smoothRestore = true)
                return
            }
            val elapsed = (player.currentPosition - fadeStartPositionMs).coerceAtLeast(0L)
            val progress = (elapsed.toFloat() / fadeDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            applyCrossfadeGains(progress)
        }
    }

    private fun applyCrossfadeGains(progress: Float) {
        if (!::standbyPlayer.isInitialized || !::foregroundPlayer.isInitialized) return
        val gains = crossfadeGains(progress, audioSettings.crossfadeCurve)
        foregroundPlayer.volume = gains.outgoing
        standbyPlayer.volume = gains.incoming
    }

    private fun updateManualCrossfade() {
        if (!::standbyPlayer.isInitialized || !foregroundPlayer.isPlaying || !standbyPlayer.isPlaying) return
        resumeManualFadeClockIfPlaying()
        if (manualFadeStartedAtElapsedMs == 0L) return
        val elapsedMs = manualFadeElapsedMs +
            (SystemClock.elapsedRealtime() - manualFadeStartedAtElapsedMs).coerceAtLeast(0L)
        val progress = (elapsedMs.toFloat() / fadeDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        applyCrossfadeGains(progress)
        if (progress >= 1f) completeCrossfade()
    }

    private fun sameAlbum(first: MediaItem?, second: MediaItem?): Boolean {
        val firstMetadata = first?.mediaMetadata ?: return false
        val secondMetadata = second?.mediaMetadata ?: return false
        val firstAlbum = firstMetadata.albumTitle?.toString()?.trim().orEmpty()
        val secondAlbum = secondMetadata.albumTitle?.toString()?.trim().orEmpty()
        if (firstAlbum.isBlank() || secondAlbum.isBlank() || !firstAlbum.equals(secondAlbum, ignoreCase = true)) return false
        val firstArtist = firstMetadata.albumArtist?.toString()?.trim().orEmpty()
        val secondArtist = secondMetadata.albumArtist?.toString()?.trim().orEmpty()
        return firstArtist.isBlank() || secondArtist.isBlank() || firstArtist.equals(secondArtist, ignoreCase = true)
    }

    private fun advancePastCurrentManualTransition(): Boolean {
        if (pendingManualTransition == null &&
            !(crossfading && crossfadeKind == CrossfadeKind.MANUAL)
        ) return false

        if (crossfading && crossfadeKind == CrossfadeKind.MANUAL) {
            completeCrossfade()
        } else {
            applyPendingManualTransitionImmediately()
        }
        val active = foregroundPlayer
        if (active.hasNextMediaItem()) requestManualTransitionToIndex(active.nextMediaItemIndex)
        return true
    }

    private fun requestManualTransitionToIndex(index: Int, positionMs: Long = C.TIME_UNSET) {
        val request = inQueueTransitionRequest(foregroundPlayer, index, positionMs) ?: return
        transitionToQueue(request)
    }

    /** Starts a true two-player overlap when the manual fade setting is enabled. */
    private fun transitionToQueue(request: PlaybackTransitionRequest): Boolean {
        if (!::foregroundPlayer.isInitialized || request.mediaItems.isEmpty() ||
            request.startIndex !in request.mediaItems.indices
        ) return false
        val source = foregroundPlayer
        val targetMediaItem = request.mediaItems[request.startIndex]
        lastManualTransitionDebugEvent = "request:${targetMediaItem.mediaId}/source=${source.currentMediaItem?.mediaId}"

        val isEquivalentAutomaticTarget = crossfading && crossfadeKind == CrossfadeKind.AUTOMATIC &&
            targetMediaItem.mediaId == targetMediaId && request.startIndex == preparedNextIndex &&
            request.repeatMode == source.repeatMode && request.shuffleModeEnabled == source.shuffleModeEnabled &&
            queuesHaveSameIds(request.mediaItems, source)
        if (isEquivalentAutomaticTarget) {
            completeCrossfade()
            return true
        }

        if (crossfading || pendingManualTransition != null || preparedNextIndex != C.INDEX_UNSET ||
            crossfadeCancelRestoreStartVolume != null
        ) {
            cancelCrossfade(
                restoreVolume = !sleepTimerFadeActive,
                smoothRestore = crossfading
            )
        }

        val manualFadeMs = audioSettings.manualSkipFadeSeconds.coerceIn(
            0,
            AudioSettingsContract.MAX_MANUAL_SKIP_FADE_SECONDS
        ) * 1_000L
        val shouldOverlap = manualFadeMs > 0L && source.isPlaying && request.playWhenReady &&
            !sleepTimerFadeActive && source.currentMediaItem?.mediaId != targetMediaItem.mediaId

        if (!shouldOverlap) {
            applyQueueToForeground(request, restoreVolume = !sleepTimerFadeActive)
            lastManualTransitionDebugEvent = "immediate:${targetMediaItem.mediaId}"
            return true
        }

        ensureStandbyPlayer()
        pendingManualTransition = request
        activeManualTransition = null
        manualTransitionSourceMediaId = source.currentMediaItem?.mediaId
        manualTransitionSourceQueue = (0 until source.mediaItemCount).map(source::getMediaItemAt)
        manualTransitionSourceRepeatMode = source.repeatMode
        manualTransitionSourceShuffleEnabled = source.shuffleModeEnabled
        lastManualTransitionDebugEvent = "pending:${targetMediaItem.mediaId}"
        preparedNextIndex = request.startIndex
        targetMediaId = targetMediaItem.mediaId
        fadeSourceIndex = source.currentMediaItemIndex
        fadeStartPositionMs = 0L
        val sourceDurationMs = source.duration
        val sourceRemainingMs = if (sourceDurationMs > 0L && sourceDurationMs != C.TIME_UNSET) {
            (sourceDurationMs - source.currentPosition).coerceAtLeast(1L)
        } else {
            manualFadeMs
        }
        fadeDurationMs = minOf(manualFadeMs, sourceRemainingMs).coerceAtLeast(1L)
        manualFadeElapsedMs = 0L
        manualFadeStartedAtElapsedMs = 0L

        standbyPlayer.volume = 0f
        standbyPlayer.repeatMode = request.repeatMode
        standbyPlayer.setMediaItems(request.mediaItems, request.startIndex, request.startPositionMs)
        if (request.copyCurrentShuffleOrder && source.shuffleModeEnabled) {
            standbyPlayer.setShuffleOrder(source.shuffleOrder)
        }
        standbyPlayer.shuffleModeEnabled = request.shuffleModeEnabled
        standbyPlayer.prepare()
        scheduleManualTransitionTimeout()
        maybeBeginManualTransition()
        return true
    }

    private fun queuesHaveSameIds(items: List<MediaItem>, source: ExoPlayer): Boolean {
        if (items.size != source.mediaItemCount) return false
        return items.indices.all { index -> items[index].mediaId == source.getMediaItemAt(index).mediaId }
    }

    private fun maybeBeginManualTransition() {
        val request = pendingManualTransition ?: return
        if (sleepTimerFadeActive) {
            applyPendingManualTransitionImmediately()
            return
        }
        if (crossfadeCancelRestoreStartVolume != null) return
        if (!::standbyPlayer.isInitialized || standbyPlayer.playbackState != Player.STATE_READY) return
        if (foregroundPlayer.currentMediaItem?.mediaId != manualTransitionSourceMediaId ||
            !foregroundPlayer.isPlaying || !request.playWhenReady
        ) {
            applyPendingManualTransitionImmediately()
            return
        }
        val target = request.mediaItems[request.startIndex]
        if (target.mediaId != standbyPlayer.currentMediaItem?.mediaId) {
            applyPendingManualTransitionImmediately()
            return
        }

        clearManualTransitionTimeout()
        pendingManualTransition = null
        activeManualTransition = request
        crossfading = true
        crossfadeKind = CrossfadeKind.MANUAL
        lastManualTransitionDebugEvent = "active:${target.mediaId}"
        fadeSourceIndex = foregroundPlayer.currentMediaItemIndex
        fadeStartPositionMs = 0L
        targetMediaId = target.mediaId
        preparedNextIndex = request.startIndex
        val incomingDurationMs = standbyPlayer.duration
        if (incomingDurationMs > 0L && incomingDurationMs != C.TIME_UNSET) {
            fadeDurationMs = minOf(fadeDurationMs, incomingDurationMs).coerceAtLeast(1L)
        }
        manualFadeElapsedMs = 0L
        manualFadeStartedAtElapsedMs = 0L
        standbyPlayer.volume = 0f
        scheduleManualTransitionTimeout()
        standbyPlayer.play()
        resumeManualFadeClockIfPlaying()
    }

    private fun scheduleManualTransitionTimeout(timeoutMs: Long = MANUAL_PREPARE_TIMEOUT_MS) {
        clearManualTransitionTimeout()
        val timeout = Runnable {
            when {
                pendingManualTransition != null -> applyPendingManualTransitionImmediately()
                crossfading && crossfadeKind == CrossfadeKind.MANUAL && activeManualTransition != null ->
                    applyActiveManualTransitionImmediately()
            }
        }
        manualTransitionTimeout = timeout
        mainHandler.postDelayed(timeout, timeoutMs)
    }

    private fun clearManualTransitionTimeout() {
        manualTransitionTimeout?.let(mainHandler::removeCallbacks)
        manualTransitionTimeout = null
    }

    private fun applyPendingManualTransitionImmediately() {
        val request = pendingManualTransition ?: return
        clearManualTransitionTimeout()
        pendingManualTransition = null
        manualTransitionSourceMediaId = null
        if (crossfading || preparedNextIndex != C.INDEX_UNSET || crossfadeCancelRestoreStartVolume != null) {
            cancelCrossfade(restoreVolume = !sleepTimerFadeActive, smoothRestore = crossfading)
        }
        applyManualTransitionImmediately(request, restoreVolume = !sleepTimerFadeActive)
        lastManualTransitionDebugEvent = "pending-fallback:${request.mediaItems.getOrNull(request.startIndex)?.mediaId}"
    }

    /** Complete a manual skip without a fade if the active overlap stalls before its fade clock ends. */
    private fun applyActiveManualTransitionImmediately() {
        val request = activeManualTransition ?: return
        val restoreVolume = !sleepTimerFadeActive
        cancelCrossfade(restoreVolume = restoreVolume)
        applyManualTransitionImmediately(request, restoreVolume = restoreVolume)
        lastManualTransitionDebugEvent = "active-fallback:${request.mediaItems.getOrNull(request.startIndex)?.mediaId}"
    }

    private fun applyQueueToForeground(request: PlaybackTransitionRequest, restoreVolume: Boolean) {
        if (!::foregroundPlayer.isInitialized) return
        if (restoreVolume) {
            foregroundPlayer.volume = 1f
            clearCrossfadeCancelVolumeRestore()
        }
        if (request.keepCurrentQueue) {
            foregroundPlayer.seekTo(request.startIndex, request.startPositionMs)
            if (request.playWhenReady) foregroundPlayer.play() else foregroundPlayer.pause()
            return
        }
        foregroundPlayer.repeatMode = request.repeatMode
        foregroundPlayer.shuffleModeEnabled = request.shuffleModeEnabled
        foregroundPlayer.setMediaItems(request.mediaItems, request.startIndex, request.startPositionMs)
        foregroundPlayer.prepare()
        if (request.playWhenReady) foregroundPlayer.play() else foregroundPlayer.pause()
    }

    /** Apply a fallback without reviving playback if the user paused while the incoming item loaded. */
    private fun applyManualTransitionImmediately(request: PlaybackTransitionRequest, restoreVolume: Boolean) {
        if (!::foregroundPlayer.isInitialized) return
        applyQueueToForeground(
            request.copy(playWhenReady = foregroundPlayer.playWhenReady),
            restoreVolume = restoreVolume
        )
    }

    private fun resumeManualFadeClockIfPlaying() {
        if (crossfadeKind != CrossfadeKind.MANUAL || manualFadeStartedAtElapsedMs != 0L ||
            !foregroundPlayer.isPlaying || !::standbyPlayer.isInitialized || !standbyPlayer.isPlaying
        ) return
        manualFadeStartedAtElapsedMs = SystemClock.elapsedRealtime()
        val remainingFadeMs = (fadeDurationMs - manualFadeElapsedMs).coerceAtLeast(0L)
        scheduleManualTransitionTimeout(remainingFadeMs + MANUAL_FADE_COMPLETION_GRACE_MS)
    }

    private fun pauseManualFadeClock() {
        if (crossfadeKind == CrossfadeKind.MANUAL && activeManualTransition != null) {
            clearManualTransitionTimeout()
        }
        val startedAt = manualFadeStartedAtElapsedMs
        if (startedAt == 0L) return
        manualFadeElapsedMs += (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        manualFadeStartedAtElapsedMs = 0L
    }

    private fun refreshDeviceEqualizerProfile() {
        if (!::audioSettingsStore.isInitialized || !::foregroundPlayer.isInitialized) return
        audioSettings = readAudioSettings()
        applyEqualizerSettings()
    }

    @OptIn(UnstableApi::class)
    private fun prepareNextPlayer(nextIndex: Int) {
        val source = foregroundPlayer
        if (nextIndex !in 0 until source.mediaItemCount) return
        ensureStandbyPlayer()
        clearPreparedPlayer()
        val items = (0 until source.mediaItemCount).map(source::getMediaItemAt)
        standbyPlayer.volume = 0f
        standbyPlayer.repeatMode = source.repeatMode
        standbyPlayer.shuffleModeEnabled = source.shuffleModeEnabled
        standbyPlayer.setMediaItems(items, nextIndex, 0L)
        // The shuffle-enabled flag does not copy the playlist's randomized order. Both players must
        // traverse the same precomputed order so swapping players at the crossfade doesn't reshuffle.
        standbyPlayer.setShuffleOrder(source.shuffleOrder)
        standbyPlayer.prepare()
        standbyPlayer.pause()
        preparedNextIndex = nextIndex
        targetMediaId = source.getMediaItemAt(nextIndex).mediaId
    }

    private fun beginCrossfade(startPositionMs: Long, durationMs: Long, nextIndex: Int) {
        if (durationMs <= 0L || !::standbyPlayer.isInitialized || standbyPlayer.playbackState != Player.STATE_READY) return
        crossfading = true
        crossfadeKind = CrossfadeKind.AUTOMATIC
        fadeSourceIndex = foregroundPlayer.currentMediaItemIndex
        fadeStartPositionMs = startPositionMs
        fadeDurationMs = durationMs
        preparedNextIndex = nextIndex
        targetMediaId = foregroundPlayer.getMediaItemAt(nextIndex).mediaId
        manualFadeElapsedMs = 0L
        manualFadeStartedAtElapsedMs = 0L
        standbyPlayer.volume = 0f
        standbyPlayer.play()
    }

    private fun completeCrossfade() {
        if (!crossfading) return
        lastManualTransitionDebugEvent = "complete:${targetMediaId}"
        val outgoing = foregroundPlayer
        val incoming = standbyPlayer
        incoming.volume = 1f
        outgoing.volume = 0f
        incoming.setAudioAttributes(musicAudioAttributes, true)
        foregroundPlayer = incoming
        standbyPlayer = outgoing
        mediaSession.setPlayer(createSessionPlayer(foregroundPlayer))
        crossfading = false
        crossfadeKind = null
        activeManualTransition = null
        pendingManualTransition = null
        manualTransitionSourceMediaId = null
        manualTransitionSourceQueue = null
        manualTransitionSourceRepeatMode = null
        manualTransitionSourceShuffleEnabled = null
        clearManualTransitionTimeout()
        manualFadeElapsedMs = 0L
        manualFadeStartedAtElapsedMs = 0L
        clearCrossfadeCancelVolumeRestore()
        preparedNextIndex = C.INDEX_UNSET
        targetMediaId = null
        fadeSourceIndex = C.INDEX_UNSET
        fadeStartPositionMs = 0L
        fadeDurationMs = 0L
        standbyPlayer.pause()
        standbyPlayer.stop()
        standbyPlayer.clearMediaItems()
        standbyPlayer.volume = 1f
        standbyPlayer.setAudioAttributes(musicAudioAttributes, false)
    }

    private fun cancelCrossfade(
        restoreVolume: Boolean,
        smoothRestore: Boolean = false,
        debugReason: String = "unspecified"
    ) {
        val wasCrossfading = crossfading
        val hadManualTransition = crossfadeKind == CrossfadeKind.MANUAL ||
            pendingManualTransition != null || activeManualTransition != null
        if (hadManualTransition) lastManualTransitionDebugEvent = "cancel:$debugReason"
        val volumeBeforeCancel = if (::foregroundPlayer.isInitialized) foregroundPlayer.volume else 1f
        crossfading = false
        crossfadeKind = null
        pendingManualTransition = null
        activeManualTransition = null
        manualTransitionSourceMediaId = null
        manualTransitionSourceQueue = null
        manualTransitionSourceRepeatMode = null
        manualTransitionSourceShuffleEnabled = null
        clearManualTransitionTimeout()
        manualFadeElapsedMs = 0L
        manualFadeStartedAtElapsedMs = 0L
        if (restoreVolume && ::foregroundPlayer.isInitialized) {
            if (smoothRestore && wasCrossfading && volumeBeforeCancel < 0.999f) {
                crossfadeCancelRestoreStartVolume = volumeBeforeCancel.coerceIn(0f, 1f)
                crossfadeCancelRestoreStartedAtMs = SystemClock.elapsedRealtime()
            } else {
                foregroundPlayer.volume = 1f
                clearCrossfadeCancelVolumeRestore()
            }
        } else {
            clearCrossfadeCancelVolumeRestore()
        }
        if (::standbyPlayer.isInitialized) {
            standbyPlayer.pause()
            standbyPlayer.stop()
            standbyPlayer.clearMediaItems()
            standbyPlayer.volume = 1f
        }
        preparedNextIndex = C.INDEX_UNSET
        targetMediaId = null
        fadeSourceIndex = C.INDEX_UNSET
        fadeStartPositionMs = 0L
        fadeDurationMs = 0L
    }

    private fun updateCrossfadeCancelVolumeRestore(): Boolean {
        val startVolume = crossfadeCancelRestoreStartVolume ?: return false
        val elapsedMs = (SystemClock.elapsedRealtime() - crossfadeCancelRestoreStartedAtMs).coerceAtLeast(0L)
        val progress = (elapsedMs.toFloat() / CROSSFADE_CANCEL_RESTORE_DURATION_MS).coerceIn(0f, 1f)
        foregroundPlayer.volume = startVolume + (1f - startVolume) * progress
        if (progress >= 1f) {
            foregroundPlayer.volume = 1f
            clearCrossfadeCancelVolumeRestore()
            return false
        }
        return true
    }

    private fun clearCrossfadeCancelVolumeRestore() {
        crossfadeCancelRestoreStartVolume = null
        crossfadeCancelRestoreStartedAtMs = 0L
    }

    private fun clearPreparedPlayer() {
        if (crossfading) return
        if (::standbyPlayer.isInitialized) {
            standbyPlayer.pause()
            standbyPlayer.stop()
            standbyPlayer.clearMediaItems()
            standbyPlayer.volume = 1f
        }
        preparedNextIndex = C.INDEX_UNSET
        targetMediaId = null
    }

    private fun attachEqualizer(target: ExoPlayer, audioSessionId: Int) {
        if (audioSessionId <= 0) {
            releaseEqualizer(target)
            return
        }
        audioSessionIds[target] = audioSessionId

        val existing = equalizers[target]
        val existingHasControl = existing?.let { runCatching { it.hasControl() }.getOrDefault(false) } == true
        if (existingHasControl && equalizerSessionIds[target] == audioSessionId) {
            applyEqualizer(existing!!)
            return
        }
        releaseEqualizer(target)

        val effect = runCatching { Equalizer(10, audioSessionId) }
            .onFailure { Log.w("TunelystPlayback", "Device equalizer could not attach to audio session $audioSessionId", it) }
            .getOrNull() ?: return
        equalizers[target] = effect
        equalizerSessionIds[target] = audioSessionId
        if (!runCatching { effect.hasControl() }.getOrDefault(false)) {
            Log.w("TunelystPlayback", "Device equalizer has no control for audio session $audioSessionId; it will be retried when playback becomes ready")
            return
        }
        applyEqualizer(effect)
    }

    private fun releaseEqualizer(target: ExoPlayer) {
        equalizers.remove(target)?.let { runCatching { it.release() } }
        equalizerSessionIds.remove(target)
    }

    private fun ensureEqualizerAttached(target: ExoPlayer) {
        if (!audioSettings.equalizerEnabled) return
        val sessionId = audioSessionIds[target] ?: return
        val effect = equalizers[target]
        val effectHasControl = effect?.let { runCatching { it.hasControl() }.getOrDefault(false) } == true
        if (!effectHasControl || equalizerSessionIds[target] != sessionId) {
            attachEqualizer(target, sessionId)
        } else {
            applyEqualizer(effect!!)
        }
    }

    private fun attachLoudnessEnhancer(target: ExoPlayer, audioSessionId: Int) {
        if (audioSessionId <= 0) return
        loudnessEnhancers.remove(target)?.let { runCatching { it.release() } }
        val effect = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull() ?: return
        loudnessEnhancers[target] = effect
        appliedReplayGainByPlayer.remove(target)
        applyReplayGain(target, target.currentMediaItem)
    }

    private fun applyEqualizerSettings() {
        if (!audioSettings.equalizerEnabled) {
            equalizers.values.forEach { effect ->
                runCatching { effect.enabled = false }
                    .onFailure { Log.w("TunelystPlayback", "Failed to disable device equalizer", it) }
            }
            return
        }
        if (::foregroundPlayer.isInitialized) ensureEqualizerAttached(foregroundPlayer)
        if (::standbyPlayer.isInitialized) ensureEqualizerAttached(standbyPlayer)
    }

    private fun applyPlaybackParameters() {
        if (!::foregroundPlayer.isInitialized) return
        val parameters = PlaybackParameters(audioSettings.playbackSpeed, audioSettings.playbackPitch)
        foregroundPlayer.setPlaybackParameters(parameters)
        if (::standbyPlayer.isInitialized) standbyPlayer.setPlaybackParameters(parameters)
    }

    private fun applyLoudnessSettings() {
        if (!::foregroundPlayer.isInitialized) return
        applyReplayGain(foregroundPlayer, foregroundPlayer.currentMediaItem)
        if (::standbyPlayer.isInitialized) applyReplayGain(standbyPlayer, standbyPlayer.currentMediaItem)
    }

    private fun applyReplayGain(target: ExoPlayer, item: MediaItem?) {
        val effect = loudnessEnhancers[target] ?: return
        val metadataExtras = item?.mediaMetadata?.extras
        val metadataGain = metadataExtras
            ?.takeIf { it.containsKey("replay_gain_db") }
            ?.getFloat("replay_gain_db")
            ?.takeIf { it.isFinite() }
        val requestedGain = metadataGain ?: ReplayGainRuntimeCache.get(item?.mediaId)
        val gainMilliBel = ((requestedGain ?: 0f).coerceIn(-12f, 6f) * 100f)
            .roundToInt()
            .coerceIn(-1_200, 600)
        val enabled = audioSettings.normalizeVolume && requestedGain != null
        val applied = AppliedReplayGain(item?.mediaId, gainMilliBel, enabled)
        if (appliedReplayGainByPlayer[target] == applied) return
        runCatching {
            effect.setTargetGain(gainMilliBel)
            effect.enabled = enabled
            appliedReplayGainByPlayer[target] = applied
        }
    }

    private fun applyEqualizer(effect: Equalizer) {
        runCatching {
            if (!effect.hasControl()) return@runCatching
            val count = effect.numberOfBands.toInt()
            if (count <= 0) {
                Log.w("TunelystPlayback", "Device equalizer reports no bands")
                return@runCatching
            }
            val targetFrequenciesHz = listOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)
            val levelsByBand = mutableMapOf<Int, MutableList<Int>>()
            targetFrequenciesHz.forEachIndexed { index, targetHz ->
                if (index >= audioSettings.bandLevelsDb.size) return@forEachIndexed
                val nearestBand = (0 until count).minByOrNull { bandIndex ->
                    val centerHz = effect.getCenterFreq(bandIndex.toShort()).toFloat() / 1_000f
                    if (centerHz <= 0f) Float.MAX_VALUE else abs(ln(centerHz / targetHz))
                } ?: return@forEachIndexed
                levelsByBand.getOrPut(nearestBand) { mutableListOf() }.add(audioSettings.bandLevelsDb[index])
            }
            val range = effect.bandLevelRange
            if (range.size < 2 || range[0] >= range[1]) {
                Log.w("TunelystPlayback", "Device equalizer reports an invalid band level range")
                return@runCatching
            }
            levelsByBand.forEach { (bandIndex, requestedLevels) ->
                val averageDb = requestedLevels.average()
                val level = (averageDb * 100f).roundToInt()
                    .coerceIn(range[0].toInt(), range[1].toInt())
                    .toShort()
                runCatching { effect.setBandLevel(bandIndex.toShort(), level) }
                    .onFailure { Log.w("TunelystPlayback", "Failed to set equalizer band $bandIndex", it) }
            }
            effect.enabled = audioSettings.equalizerEnabled
        }.onFailure { Log.w("TunelystPlayback", "Failed to apply equalizer settings", it) }
    }

    private fun readAudioSettings(): SettingsSnapshot {
        val stored = audioSettingsStore.read()
        return SettingsSnapshot(
            equalizerEnabled = stored.equalizerEnabled,
            bandLevelsDb = stored.bandLevelsDb,
            crossfadeSeconds = stored.crossfadeSeconds,
            crossfadeCurve = stored.crossfadeCurve,
            crossfadeAlbumBoundariesOnly = stored.crossfadeAlbumBoundariesOnly,
            manualSkipFadeSeconds = stored.manualSkipFadeSeconds,
            playbackSpeed = stored.playbackSpeed,
            playbackPitch = stored.playbackPitch,
            normalizeVolume = stored.normalizeVolume
        )
    }

    private data class SettingsSnapshot(
        val equalizerEnabled: Boolean,
        val bandLevelsDb: List<Int>,
        val crossfadeSeconds: Int,
        val crossfadeCurve: CrossfadeCurve,
        val crossfadeAlbumBoundariesOnly: Boolean,
        val manualSkipFadeSeconds: Int,
        val playbackSpeed: Float,
        val playbackPitch: Float,
        val normalizeVolume: Boolean
    )

    private companion object {
        const val SESSION_ACTIVITY_REQUEST_CODE = 47_001
        const val TICK_INTERVAL_MS = 20L
        const val ACTIVE_FEATURE_TICK_INTERVAL_MS = 250L
        const val IDLE_TICK_INTERVAL_MS = 2_000L
        const val CROSSFADE_CANCEL_RESTORE_TICK_MS = 16L
        const val CROSSFADE_CANCEL_RESTORE_DURATION_MS = 200L
        const val MANUAL_PREPARE_TIMEOUT_MS = 8_000L
        const val MANUAL_FADE_COMPLETION_GRACE_MS = 3_000L
        const val TRANSIENT_PLAYBACK_RETRY_DELAY_MS = 400L
        const val UNEXPECTED_PAUSE_RECOVERY_DELAY_MS = 4_000L
        const val UNEXPECTED_PAUSE_RECOVERY_COOLDOWN_MS = 15_000L
        const val PRELOAD_LEAD_MS = 4_000L
    }
}

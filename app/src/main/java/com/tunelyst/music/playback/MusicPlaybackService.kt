package com.tunelyst.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tunelyst.music.MainActivity
import com.tunelyst.music.data.AudioSettingsContract
import com.tunelyst.music.data.AudioSettingsStore
import com.tunelyst.music.data.CrossfadeCurve
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/** Owns playback outside the Activity so music and media controls survive navigation/backgrounding. */
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
    private var crossfading = false
    private var preparedNextIndex = C.INDEX_UNSET
    private var targetMediaId: String? = null
    private var fadeSourceIndex = C.INDEX_UNSET
    private var fadeStartPositionMs = 0L
    private var fadeDurationMs = 0L
    private var sleepTimerEndElapsedMs = 0L
    private var sleepTimerFadeOutMs = 0L
    private var sleepFadeWindowStartElapsedMs = 0L
    private var sleepFadeWindowEndElapsedMs = 0L
    private var sleepTimerFadeActive = false
    private var sleepTimerVolumeApplied = false
    private var manualFadeInStartPositionMs = 0L
    private var manualFadeInDurationMs = 0L
    private var crossfadeCancelRestoreStartVolume: Float? = null
    private var crossfadeCancelRestoreStartedAtMs = 0L
    private var transientRetryAttemptedMediaId: String? = null
    private var unexpectedPauseStartedAtElapsedMs = 0L
    private var lastUnexpectedPauseRecoveryAtElapsedMs = 0L

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
        if (audioSettings.crossfadeSeconds == 0 && previousCrossfade != 0) cancelCrossfade(restoreVolume = true)
        if (audioSettings.manualSkipFadeSeconds == 0 && previousManualFade != 0) {
            cancelManualFadeIn(restoreVolume = true)
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
                crossfading || manualFadeInDurationMs > 0L -> TICK_INTERVAL_MS
                sleepTimerEndElapsedMs > 0L || audioSettings.crossfadeSeconds > 0 -> ACTIVE_FEATURE_TICK_INTERVAL_MS
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
        mediaSession = MediaSession.Builder(this, foregroundPlayer)
            .setSessionActivity(sessionActivity)
            .build()
        runCatching { audioManager.registerAudioDeviceCallback(outputDeviceCallback, mainHandler) }
        mainHandler.post(crossfadeTicker)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

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

    private fun installListener(target: ExoPlayer) {
        val listener = object : Player.Listener {
            @UnstableApi
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
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                applyReplayGain(target, mediaItem)
                if (target !== foregroundPlayer) return
                if (mediaItem?.mediaId != transientRetryAttemptedMediaId) transientRetryAttemptedMediaId = null
                val expectedAutoTransition = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                if (crossfading && expectedAutoTransition && mediaItem?.mediaId == targetMediaId) {
                    completeCrossfade()
                } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
                    audioSettings.manualSkipFadeSeconds > 0 && !sleepTimerFadeActive) {
                    cancelCrossfade(restoreVolume = false)
                    beginManualFadeIn(audioSettings.manualSkipFadeSeconds)
                } else if (crossfading || preparedNextIndex != C.INDEX_UNSET) {
                    cancelCrossfade(restoreVolume = !sleepTimerFadeActive)
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
                if (target === foregroundPlayer && (crossfading || preparedNextIndex != C.INDEX_UNSET)) {
                    cancelCrossfade(restoreVolume = !sleepTimerFadeActive, smoothRestore = true)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (target === foregroundPlayer && crossfading && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    cancelCrossfade(restoreVolume = true)
                } else if (target === foregroundPlayer && !crossfading && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    clearPreparedPlayer()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && audioSettings.equalizerEnabled) ensureEqualizerAttached(target)
                if (target === foregroundPlayer && crossfading && ::standbyPlayer.isInitialized) {
                    if (isPlaying) standbyPlayer.play() else standbyPlayer.pause()
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (target === foregroundPlayer && (crossfading || preparedNextIndex != C.INDEX_UNSET)) {
                    cancelCrossfade(restoreVolume = true, smoothRestore = true)
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (target === foregroundPlayer && (crossfading || preparedNextIndex != C.INDEX_UNSET)) {
                    cancelCrossfade(restoreVolume = true, smoothRestore = true)
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
                if (crossfading) cancelCrossfade(restoreVolume = !sleepTimerFadeActive)
                else if (preparedNextIndex != C.INDEX_UNSET) clearPreparedPlayer()
            }
            return
        }

        Log.e("TunelystPlayback", "Foreground playback failed for $failedItemId (code ${error.errorCode})", error)
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
        if (active && ::foregroundPlayer.isInitialized && (crossfading || preparedNextIndex != C.INDEX_UNSET)) {
            cancelCrossfade(restoreVolume = true)
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

        if (crossfading || preparedNextIndex != C.INDEX_UNSET) cancelCrossfade(restoreVolume = true)
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
            if (crossfading || preparedNextIndex != C.INDEX_UNSET || crossfadeCancelRestoreStartVolume != null) {
                cancelCrossfade(restoreVolume = false)
            }
            return
        }
        updateManualFadeIn()
        if (updateCrossfadeCancelVolumeRestore()) return
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
        if (crossfading) {
            if (player.currentMediaItemIndex != fadeSourceIndex || player.nextMediaItemIndex != preparedNextIndex) {
                cancelCrossfade(restoreVolume = true, smoothRestore = true)
                return
            }
            val elapsed = (player.currentPosition - fadeStartPositionMs).coerceAtLeast(0L)
            val progress = (elapsed.toFloat() / fadeDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            when (audioSettings.crossfadeCurve) {
                CrossfadeCurve.EQUAL_POWER -> {
                    val angle = progress * (PI.toFloat() / 2f)
                    player.volume = cos(angle).coerceIn(0f, 1f)
                    standbyPlayer.volume = sin(angle).coerceIn(0f, 1f)
                }
                CrossfadeCurve.LINEAR -> {
                    player.volume = 1f - progress
                    standbyPlayer.volume = progress
                }
            }
        }
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

    private fun refreshDeviceEqualizerProfile() {
        if (!::audioSettingsStore.isInitialized || !::foregroundPlayer.isInitialized) return
        audioSettings = readAudioSettings()
        applyEqualizerSettings()
    }

    private fun beginManualFadeIn(seconds: Int) {
        val durationMs = seconds.coerceIn(0, AudioSettingsContract.MAX_MANUAL_SKIP_FADE_SECONDS) * 1_000L
        if (durationMs <= 0L) return
        manualFadeInStartPositionMs = foregroundPlayer.currentPosition.coerceAtLeast(0L)
        manualFadeInDurationMs = durationMs
        foregroundPlayer.volume = 0f
    }

    private fun updateManualFadeIn() {
        if (manualFadeInDurationMs <= 0L || !foregroundPlayer.isPlaying) return
        val elapsedMs = (foregroundPlayer.currentPosition - manualFadeInStartPositionMs).coerceAtLeast(0L)
        val progress = (elapsedMs.toFloat() / manualFadeInDurationMs.toFloat()).coerceIn(0f, 1f)
        foregroundPlayer.volume = when (audioSettings.crossfadeCurve) {
            CrossfadeCurve.EQUAL_POWER -> sin(progress * (PI.toFloat() / 2f)).coerceIn(0f, 1f)
            CrossfadeCurve.LINEAR -> progress
        }
        if (progress >= 1f) {
            foregroundPlayer.volume = 1f
            manualFadeInStartPositionMs = 0L
            manualFadeInDurationMs = 0L
        }
    }

    private fun cancelManualFadeIn(restoreVolume: Boolean) {
        manualFadeInStartPositionMs = 0L
        manualFadeInDurationMs = 0L
        if (restoreVolume && ::foregroundPlayer.isInitialized) foregroundPlayer.volume = 1f
    }

    @UnstableApi
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
        cancelManualFadeIn(restoreVolume = true)
        crossfading = true
        fadeSourceIndex = foregroundPlayer.currentMediaItemIndex
        fadeStartPositionMs = startPositionMs
        fadeDurationMs = durationMs
        preparedNextIndex = nextIndex
        targetMediaId = foregroundPlayer.getMediaItemAt(nextIndex).mediaId
        standbyPlayer.volume = 0f
        standbyPlayer.play()
    }

    private fun completeCrossfade() {
        if (!crossfading) return
        val outgoing = foregroundPlayer
        val incoming = standbyPlayer
        incoming.volume = 1f
        outgoing.volume = 0f
        incoming.setAudioAttributes(musicAudioAttributes, true)
        mediaSession.setPlayer(incoming)
        foregroundPlayer = incoming
        standbyPlayer = outgoing
        crossfading = false
        clearCrossfadeCancelVolumeRestore()
        preparedNextIndex = C.INDEX_UNSET
        targetMediaId = null
        fadeSourceIndex = C.INDEX_UNSET
        fadeStartPositionMs = 0L
        fadeDurationMs = 0L
        manualFadeInStartPositionMs = 0L
        manualFadeInDurationMs = 0L
        standbyPlayer.volume = 1f
        standbyPlayer.pause()
        standbyPlayer.stop()
        standbyPlayer.clearMediaItems()
        standbyPlayer.setAudioAttributes(musicAudioAttributes, false)
    }

    private fun cancelCrossfade(restoreVolume: Boolean, smoothRestore: Boolean = false) {
        val wasCrossfading = crossfading
        val volumeBeforeCancel = if (::foregroundPlayer.isInitialized) foregroundPlayer.volume else 1f
        crossfading = false
        manualFadeInStartPositionMs = 0L
        manualFadeInDurationMs = 0L
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
            standbyPlayer.volume = 1f
            standbyPlayer.pause()
            standbyPlayer.stop()
            standbyPlayer.clearMediaItems()
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
            standbyPlayer.volume = 1f
            standbyPlayer.pause()
            standbyPlayer.stop()
            standbyPlayer.clearMediaItems()
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
        const val TICK_INTERVAL_MS = 100L
        const val ACTIVE_FEATURE_TICK_INTERVAL_MS = 250L
        const val IDLE_TICK_INTERVAL_MS = 2_000L
        const val CROSSFADE_CANCEL_RESTORE_TICK_MS = 16L
        const val CROSSFADE_CANCEL_RESTORE_DURATION_MS = 200L
        const val TRANSIENT_PLAYBACK_RETRY_DELAY_MS = 400L
        const val UNEXPECTED_PAUSE_RECOVERY_DELAY_MS = 4_000L
        const val UNEXPECTED_PAUSE_RECOVERY_COOLDOWN_MS = 15_000L
        const val PRELOAD_LEAD_MS = 4_000L
    }
}

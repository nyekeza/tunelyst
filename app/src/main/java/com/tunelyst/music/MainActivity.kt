package com.tunelyst.music

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.tunelyst.music.playback.MusicPlaybackService
import com.tunelyst.music.ui.TunelystMusicApp
import com.tunelyst.music.ui.MusicViewModel
import java.util.concurrent.Executor

class MainActivity : ComponentActivity() {
    private val musicViewModel: MusicViewModel by viewModels {
        MusicViewModelFactory(application)
    }
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controllerConnectionGeneration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        musicViewModel.attachPlayerControllerRetryAction(::connectMediaController)
        connectMediaController()

        setContent {
            TunelystMusicApp(viewModel = musicViewModel)
        }
        handleOpenNowPlayingIntent(intent)
        if (savedInstanceState == null) handleIncomingAudioIntent(intent)
    }

    private fun connectMediaController() {
        controllerFuture?.let { previous -> runCatching { MediaController.releaseFuture(previous) } }
        val attempt = ++controllerConnectionGeneration
        val token = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            Runnable {
                if (attempt != controllerConnectionGeneration || isFinishing || isDestroyed) return@Runnable
                runCatching { musicViewModel.attachPlayer(future.get()) }
                    .onFailure { error ->
                        Log.e("TunelystPlayback", "MediaController connection failed", error)
                        musicViewModel.reportPlayerConnectionFailure()
                    }
            },
            Executor { command -> command.run() }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenNowPlayingIntent(intent)
        handleIncomingAudioIntent(intent)
    }

    private fun handleOpenNowPlayingIntent(incoming: Intent?) {
        if (incoming?.action == ACTION_OPEN_NOW_PLAYING) {
            musicViewModel.requestOpenNowPlaying()
            incoming.action = null
        }
    }

    private fun handleIncomingAudioIntent(incoming: Intent?) {
        if (incoming == null || incoming.action !in AUDIO_INTENT_ACTIONS) return
        val sharedUris = linkedSetOf<Uri>()

        fun addSharedValue(value: Any?) {
            when (value) {
                is Uri -> sharedUris += value
                is String -> runCatching { Uri.parse(value) }.getOrNull()?.let(sharedUris::add)
                is Iterable<*> -> value.forEach { addSharedValue(it) }
                is Array<*> -> value.forEach { addSharedValue(it) }
            }
        }

        // EXTRA_STREAM is the standard share contract, but several file managers only populate
        // data or ClipData. Collect all forms so a single share action cannot silently lose audio.
        runCatching { incoming.extras?.get(Intent.EXTRA_STREAM) }.getOrNull()?.let { addSharedValue(it) }
        incoming.data?.let(sharedUris::add)
        incoming.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(sharedUris::add)
        }

        val readableUris = sharedUris.filter { it.scheme == "content" || it.scheme == "file" }
        if (readableUris.isEmpty()) return

        val hasReadGrant = incoming.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        val hasPersistableGrant = incoming.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        if (hasReadGrant && hasPersistableGrant) {
            readableUris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
        musicViewModel.acceptSharedAudioUris(readableUris)
    }

    override fun onDestroy() {
        controllerConnectionGeneration++
        musicViewModel.attachPlayerControllerRetryAction(null)
        musicViewModel.detachPlayer()
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN_NOW_PLAYING = "com.tunelyst.music.action.OPEN_NOW_PLAYING"
        private val AUDIO_INTENT_ACTIONS = setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)
    }
}

private class MusicViewModelFactory(
    private val application: android.app.Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MusicViewModel::class.java)) {
            return MusicViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

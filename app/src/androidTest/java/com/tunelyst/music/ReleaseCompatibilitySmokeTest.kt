package com.tunelyst.music

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tunelyst.music.playback.MusicPlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReleaseCompatibilitySmokeTest {
    @Test
    fun manifestKeepsAndroid8AndPlayTargetRequirements() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val applicationInfo = appContext.packageManager.getApplicationInfo(appContext.packageName, 0)

        assertEquals("com.tunelyst.music", appContext.packageName)
        assertEquals(26, applicationInfo.minSdkVersion)
        assertEquals(36, applicationInfo.targetSdkVersion)
    }

    @Test
    fun mergedManifestDoesNotRequestInternetAccess() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            appContext.packageManager.checkPermission(android.Manifest.permission.INTERNET, appContext.packageName)
        )
    }

    @Test
    fun mainActivityStartsWhileAudioPermissionIsStillUnchanged() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val hadAudioPermission = appContext.checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        scenario.use {
            it.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertNotNull(activity.window.decorView)
            }
        }

        // Launch must not change the grant; audio access remains an explicit user action.
        assertEquals(
            hadAudioPermission,
            appContext.checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    @OptIn(UnstableApi::class)
    @Test
    fun playbackServiceAcceptsAMediaControllerConnection() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val token = SessionToken(appContext, ComponentName(appContext, MusicPlaybackService::class.java))
        val controllerFuture = MediaController.Builder(appContext, token).buildAsync()

        try {
            val controller = controllerFuture.get(30, TimeUnit.SECONDS)
            assertTrue(controller.isConnected)
        } finally {
            MediaController.releaseFuture(controllerFuture)
        }
    }
}

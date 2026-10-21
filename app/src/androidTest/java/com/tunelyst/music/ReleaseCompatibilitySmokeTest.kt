package com.tunelyst.music

import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

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
        val hasLegacyAudioPermission = appContext.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        scenario.use {
            it.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertNotNull(activity.window.decorView)
            }
        }

        // Launch must not change the grant; audio access remains an explicit user action.
        assertEquals(
            hasLegacyAudioPermission,
            appContext.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        )
    }
}

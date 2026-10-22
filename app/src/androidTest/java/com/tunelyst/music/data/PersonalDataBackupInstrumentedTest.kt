package com.tunelyst.music.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PersonalDataBackupInstrumentedTest {
    private lateinit var testContext: IsolatedPreferencesContext

    @Before
    fun setUp() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "tunelyst_backup_test_${UUID.randomUUID().toString().replace("-", "")}"
        testContext = IsolatedPreferencesContext(appContext, namespace)
        testContext.clearPreferences()
    }

    @After
    fun tearDown() {
        if (::testContext.isInitialized) testContext.clearPreferences()
    }

    @Test
    fun portableBackupRemapsFavoritesAndSettingsWithoutDeviceUrisOrPaths() {
        val sourceSong = sampleSong(id = 401L)
        val library = LibraryStore(testContext)
        val appSettings = AppSettingsStore(testContext)
        val audioSettings = AudioSettingsStore(testContext)
        val backup = PersonalDataBackup(testContext)

        library.toggleFavorite(sourceSong.id)
        appSettings.setThemeMode(AppThemeMode.DEVICE_COLORS)
        audioSettings.setPlaybackSpeed(1.5f)
        audioSettings.setPlaybackPitch(0.9f)

        val json = backup.exportJson(songs = listOf(sourceSong))
        assertFalse(json.contains(sourceSong.uri.toString()))
        assertFalse(json.contains(sourceSong.artworkUri.toString()))
        assertFalse(json.contains(sourceSong.folderPath))

        val destinationSong = sourceSong.copy(
            id = 902L,
            uri = Uri.parse("content://media/external/audio/media/902"),
            folderPath = "new-device/music"
        )
        val result = backup.importJson(json, currentSongs = listOf(destinationSong))

        assertEquals(0, result.unmatchedTrackCount)
        assertEquals(setOf(902L), library.favorites())
        assertEquals(AppThemeMode.DEVICE_COLORS, appSettings.read().themeMode)
        assertEquals(1.5f, audioSettings.read().playbackSpeed, 0.001f)
        assertEquals(0.9f, audioSettings.read().playbackPitch, 0.001f)
    }

    @Test
    fun ambiguousTrackMatchesAreLeftUnresolved() {
        val sourceSong = sampleSong(id = 401L)
        val library = LibraryStore(testContext)
        val backup = PersonalDataBackup(testContext)
        library.toggleFavorite(sourceSong.id)
        val json = backup.exportJson(songs = listOf(sourceSong))

        val ambiguousMatches = listOf(
            sourceSong.copy(id = 902L, uri = Uri.parse("content://media/external/audio/media/902")),
            sourceSong.copy(id = 903L, uri = Uri.parse("content://media/external/audio/media/903"))
        )
        val result = backup.importJson(json, currentSongs = ambiguousMatches)

        assertEquals(1, result.unmatchedTrackCount)
        assertTrue(library.favorites().isEmpty())
    }

    @Test
    fun invalidBackupDoesNotChangeExistingLibraryData() {
        val library = LibraryStore(testContext)
        library.toggleFavorite(777L)
        val favoritesBeforeImport = library.favorites()

        val failure = runCatching {
            PersonalDataBackup(testContext).importJson("""{"format":"not-a-tunelyst-backup"}""")
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(favoritesBeforeImport, library.favorites())
    }

    private fun sampleSong(id: Long) = Song(
        id = id,
        title = "A Local Test Track",
        artist = "Test Artist",
        album = "Test Album",
        durationMs = 180_000L,
        uri = Uri.parse("content://media/external/audio/media/$id"),
        artworkUri = Uri.parse("content://media/external/audio/albumart/42"),
        dateAddedSeconds = 1_700_000_000L,
        trackNumber = 1,
        albumArtist = "Test Artist",
        genre = "Test Genre",
        year = 2024,
        discNumber = 1,
        mimeType = "audio/mpeg",
        folderPath = "private-library-folder/not-for-backup",
        fileName = "a-local-test-track.mp3",
        sizeBytes = 24_000L
    )

    private class IsolatedPreferencesContext(
        base: Context,
        private val namespace: String
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("${namespace}_$name", mode)

        fun clearPreferences() {
            listOf(
                "tunelyst_library",
                "tunelyst_app_settings",
                AudioSettingsContract.PREFERENCES_NAME
            ).forEach { name ->
                getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
    }
}

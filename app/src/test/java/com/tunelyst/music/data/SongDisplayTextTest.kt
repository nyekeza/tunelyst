package com.tunelyst.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SongDisplayTextTest {
    @Test
    fun fallbackFilenameDropsPathExtensionAndTrackNumber() {
        assertEquals("Neon Skyline", cleanTrackDisplayName("<unknown>", "Music/Albums/01_Neon_Skyline.FLAC"))
    }

    @Test
    fun taggedTitleIsTrimmedAndBoundedWithoutUsingFilePath() {
        val title = "  A Song With a Long Name  "
        assertEquals("A Song With a Long Name", cleanTrackDisplayName(title, "/storage/Music/wrong-name.mp3"))
    }

    @Test
    fun emptyAndUnknownInputsUseSafeFallback() {
        assertEquals("Untitled track", cleanTrackDisplayName("", ""))
    }
}

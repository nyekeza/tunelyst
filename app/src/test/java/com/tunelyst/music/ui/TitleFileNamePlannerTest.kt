package com.tunelyst.music.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleFileNamePlannerTest {
    @Test
    fun keepsAudioExtensionAndRemovesPathSeparatorsAndControlCharacters() {
        assertEquals(
            "New Track Name.mp3",
            safeAudioDisplayName("New/Track:\nName", "03 - noisy title.mp3")
        )
    }

    @Test
    fun addsStableSuffixForCollidingFileNames() {
        val occupied = setOf("same.flac", "same (2).flac")
        assertEquals("Same (3).flac", uniqueAudioDisplayName("Same", "old.flac", occupied))
    }

    @Test
    fun doesNotInventAnExtensionWhenTheOriginalHasNone() {
        assertEquals("Clean title", safeAudioDisplayName("Clean title", "old title"))
    }
}

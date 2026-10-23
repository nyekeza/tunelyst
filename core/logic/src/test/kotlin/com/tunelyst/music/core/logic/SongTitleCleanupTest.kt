package com.tunelyst.music.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongTitleCleanupTest {
    @Test
    fun removesTrackNumbersPromotionalTagsAndQualityText() {
        assertEquals(
            "My Song",
            suggestCleanTrackTitle("01 - Artist Name - My Song [Official Video] 320kbps", "Artist Name")
        )
    }

    @Test
    fun preservesMeaningfulParentheticalVersionNames() {
        assertNull(suggestCleanTrackTitle("Song Title (Live)", "Artist Name"))
    }

    @Test
    fun removesDecorativeSymbolsAroundTheTrackName() {
        assertEquals("Neon Skyline", suggestCleanTrackTitle("♪ ★ Neon Skyline ★ ♪", "Artist"))
    }

    @Test
    fun doesNotStripAOneDigitTitlePrefixWithoutATrackSeparator() {
        assertNull(suggestCleanTrackTitle("7 Days", "Craig David"))
    }

    @Test
    fun doesNotSuggestWhenNoUnnecessaryTextIsFound() {
        assertNull(suggestCleanTrackTitle("Here Comes the Sun", "The Beatles"))
        assertNull(suggestCleanTrackTitle("Help!", "The Beatles"))
    }
}

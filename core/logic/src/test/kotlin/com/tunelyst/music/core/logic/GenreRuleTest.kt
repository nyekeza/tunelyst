package com.tunelyst.music.core.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreRuleTest {
    @Test
    fun matchesAnyNormalizedGenreInCompoundSongOrRuleValues() {
        assertTrue(matchesGenreRule("Rock / Pop", "pop"))
        assertTrue(matchesGenreRule("Rock; Pop", " ROCK "))
        assertTrue(matchesGenreRule("Electronic, Dance", "Dance/House"))
    }

    @Test
    fun ignoresUnknownAndEmptyGenreValues() {
        assertFalse(matchesGenreRule("Unknown genre", "rock"))
        assertFalse(matchesGenreRule("Rock", ""))
        assertFalse(matchesGenreRule("Rock", "Unknown"))
    }
}

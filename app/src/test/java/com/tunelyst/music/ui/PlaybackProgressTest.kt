package com.tunelyst.music.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressTest {
    @Test
    fun pausedAndBufferingProjectionStaysAtTheReportedPosition() {
        assertEquals(0.4f, projectPlaybackProgress(40_000L, 9_000L, 100_000L, 1f, shouldAdvance = false), 0.0001f)
    }

    @Test
    fun projectsAtTheSelectedPlaybackSpeed() {
        assertEquals(0.55f, projectPlaybackProgress(40_000L, 10_000L, 100_000L, 1.5f, shouldAdvance = true), 0.0001f)
    }

    @Test
    fun clampsAtTrackEndAndRejectsInvalidInputs() {
        assertEquals(1f, projectPlaybackProgress(95_000L, 10_000L, 100_000L, 1f, shouldAdvance = true), 0.0001f)
        assertEquals(0f, projectPlaybackProgress(5_000L, 10_000L, 0L, 1f, shouldAdvance = true), 0.0001f)
        assertEquals(0.2f, projectPlaybackProgress(20_000L, 10_000L, 100_000L, Float.NaN, shouldAdvance = true), 0.0001f)
    }

    @Test
    fun clampsNegativePositionsAndElapsedTimes() {
        assertEquals(0f, projectPlaybackProgress(-5_000L, -2_000L, 100_000L, 1f, shouldAdvance = true), 0.0001f)
        assertEquals(0.1f, projectPlaybackProgress(-5_000L, 10_000L, 100_000L, 1f, shouldAdvance = true), 0.0001f)
    }
}

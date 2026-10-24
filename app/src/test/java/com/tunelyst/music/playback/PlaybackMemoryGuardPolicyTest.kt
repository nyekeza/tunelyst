package com.tunelyst.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMemoryGuardPolicyTest {
    @Test
    fun thresholdScalesWithDeviceMemoryAndStaysBounded() {
        val gib = 1024L * 1024L * 1024L
        val mib = 1024L * 1024L

        assertEquals(320L * mib, PlaybackMemoryGuardPolicy.thresholdBytes(0L, false))
        assertEquals((8L * gib * 0.10).toLong(), PlaybackMemoryGuardPolicy.thresholdBytes(8L * gib, false))
        assertEquals((4L * gib * 0.08).toLong(), PlaybackMemoryGuardPolicy.thresholdBytes(4L * gib, true))
        assertEquals(1_024L * mib, PlaybackMemoryGuardPolicy.thresholdBytes(32L * gib, false))
    }

    @Test
    fun highSampleCountRequiresMeaningfulGrowthAndResetsBelowThreshold() {
        val mib = 1024L * 1024L
        val threshold = 500L * mib

        assertEquals(1, PlaybackMemoryGuardPolicy.nextHighSampleCount(0, 500L * mib, threshold, 0L))
        assertEquals(2, PlaybackMemoryGuardPolicy.nextHighSampleCount(1, 750L * mib, threshold, 0L))
        assertEquals(0, PlaybackMemoryGuardPolicy.nextHighSampleCount(2, 700L * mib, threshold, 550L * mib))
        assertEquals(0, PlaybackMemoryGuardPolicy.nextHighSampleCount(2, 499L * mib, threshold, 0L))
        assertEquals(0, PlaybackMemoryGuardPolicy.nextHighSampleCount(2, 600L * mib, 0L, 0L))
    }

    @Test
    fun recycleRequiresSustainedPressureAndHonorsCooldown() {
        assertFalse(PlaybackMemoryGuardPolicy.shouldRecycle(2, 30_000L, 0L))
        assertTrue(PlaybackMemoryGuardPolicy.shouldRecycle(3, 45_000L, 0L))
        assertFalse(PlaybackMemoryGuardPolicy.shouldRecycle(3, 100_000L, 45_000L))
        assertTrue(PlaybackMemoryGuardPolicy.shouldRecycle(3, 165_000L, 45_000L))
        assertFalse(PlaybackMemoryGuardPolicy.shouldRecycle(3, 44_999L, 45_000L))
    }
}

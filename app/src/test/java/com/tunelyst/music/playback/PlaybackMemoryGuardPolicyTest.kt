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
    fun firstRecoveryUsesAbsoluteThresholdEvenWithHighStartupBaseline() {
        val mib = 1024L * 1024L
        val threshold = 500L * mib

        assertEquals(
            1,
            PlaybackMemoryGuardPolicy.nextHighSampleCount(
                current = 0,
                pressureBytes = threshold,
                thresholdBytes = threshold,
                recoveryBaselineBytes = 2_000L * mib,
                hasRecoveredBefore = false
            )
        )
        assertEquals(
            2,
            PlaybackMemoryGuardPolicy.nextHighSampleCount(
                current = 1,
                pressureBytes = 750L * mib,
                thresholdBytes = threshold,
                recoveryBaselineBytes = 2_000L * mib,
                hasRecoveredBefore = false
            )
        )
        assertEquals(
            0,
            PlaybackMemoryGuardPolicy.nextHighSampleCount(
                current = 2,
                pressureBytes = 499L * mib,
                thresholdBytes = threshold,
                recoveryBaselineBytes = 0L,
                hasRecoveredBefore = false
            )
        )
        assertEquals(
            0,
            PlaybackMemoryGuardPolicy.nextHighSampleCount(
                current = 2,
                pressureBytes = 600L * mib,
                thresholdBytes = 0L,
                recoveryBaselineBytes = 0L,
                hasRecoveredBefore = false
            )
        )
    }

    @Test
    fun subsequentRecoveriesRequireRenewedGrowthAbovePostRecoveryBaseline() {
        val mib = 1024L * 1024L
        val threshold = 500L * mib
        val recoveryBaseline = 600L * mib

        assertEquals(
            0,
            PlaybackMemoryGuardPolicy.nextHighSampleCount(
                current = 2,
                pressureBytes = 700L * mib,
                thresholdBytes = threshold,
                recoveryBaselineBytes = recoveryBaseline,
                hasRecoveredBefore = true
            )
        )
        assertEquals(
            1,
            PlaybackMemoryGuardPolicy.nextHighSampleCount(
                current = 0,
                pressureBytes = 792L * mib,
                thresholdBytes = threshold,
                recoveryBaselineBytes = recoveryBaseline,
                hasRecoveredBefore = true
            )
        )
    }

    @Test
    fun recycleRequiresSustainedPressureAndHonorsAttemptCooldown() {
        assertFalse(PlaybackMemoryGuardPolicy.shouldRecycle(2, 30_000L, 0L))
        assertTrue(PlaybackMemoryGuardPolicy.shouldRecycle(3, 45_000L, 0L))
        assertFalse(PlaybackMemoryGuardPolicy.shouldRecycle(3, 100_000L, 45_000L))
        assertTrue(PlaybackMemoryGuardPolicy.shouldRecycle(3, 165_000L, 45_000L))
        assertFalse(PlaybackMemoryGuardPolicy.shouldRecycle(3, 44_999L, 45_000L))
    }
}

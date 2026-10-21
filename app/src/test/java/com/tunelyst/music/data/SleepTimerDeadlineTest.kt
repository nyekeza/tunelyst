package com.tunelyst.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepTimerDeadlineTest {
    private val maxDurationMs = 24L * 60L * 60L * 1_000L

    @Test
    fun sameBootUsesMonotonicDeadlineDespiteWallClockChanges() {
        val deadline = resolveSleepTimerDeadline(
            storedEndElapsedMs = 1_000_000L,
            storedEndWallClockMs = 1_700_000_900_000L,
            storedDurationMs = 900_000L,
            storedBootCount = 18,
            currentBootCount = 18,
            storedBootEpochEstimateMs = 1_700_000_000_000L,
            currentBootEpochEstimateMs = 1_700_010_000_000L,
            nowElapsedMs = 400_000L,
            nowWallClockMs = 1_700_005_000_000L,
            maxDurationMs = maxDurationMs
        )

        assertEquals(1_000_000L, deadline)
    }

    @Test
    fun rebootRebasesRemainingWallTimeOntoNewMonotonicClock() {
        val deadline = resolveSleepTimerDeadline(
            storedEndElapsedMs = 3_600_000L,
            storedEndWallClockMs = 1_700_003_600_000L,
            storedDurationMs = 3_600_000L,
            storedBootCount = 42,
            currentBootCount = 43,
            storedBootEpochEstimateMs = 1_700_000_000_000L,
            currentBootEpochEstimateMs = 1_700_003_000_000L,
            nowElapsedMs = 120_000L,
            nowWallClockMs = 1_700_003_000_000L,
            maxDurationMs = maxDurationMs
        )

        assertEquals(720_000L, deadline)
    }

    @Test
    fun rebootAfterTimerDeadlineExpiresInsteadOfResumingPlayback() {
        val deadline = resolveSleepTimerDeadline(
            storedEndElapsedMs = 600_000L,
            storedEndWallClockMs = 1_700_000_600_000L,
            storedDurationMs = 600_000L,
            storedBootCount = 7,
            currentBootCount = 8,
            storedBootEpochEstimateMs = 1_700_000_000_000L,
            currentBootEpochEstimateMs = 1_700_000_700_000L,
            nowElapsedMs = 30_000L,
            nowWallClockMs = 1_700_000_700_000L,
            maxDurationMs = maxDurationMs
        )

        assertNull(deadline)
    }

    @Test
    fun backwardClockJumpCannotExtendTimerBeyondOriginalDuration() {
        val deadline = resolveSleepTimerDeadline(
            storedEndElapsedMs = 600_000L,
            storedEndWallClockMs = 1_700_000_600_000L,
            storedDurationMs = 600_000L,
            storedBootCount = 7,
            currentBootCount = 8,
            storedBootEpochEstimateMs = 1_700_000_000_000L,
            currentBootEpochEstimateMs = 1_700_000_010_000L,
            nowElapsedMs = 10_000L,
            nowWallClockMs = 1_699_000_000_000L,
            maxDurationMs = maxDurationMs
        )

        assertNull(deadline)
    }

    @Test
    fun sameBootCanBeRecognizedWhenBootCountSettingIsUnavailable() {
        val deadline = resolveSleepTimerDeadline(
            storedEndElapsedMs = 1_000_000L,
            storedEndWallClockMs = 1_700_000_900_000L,
            storedDurationMs = 900_000L,
            storedBootCount = -1,
            currentBootCount = -1,
            storedBootEpochEstimateMs = 1_700_000_000_000L,
            currentBootEpochEstimateMs = 1_700_000_030_000L,
            nowElapsedMs = 400_000L,
            nowWallClockMs = 1_700_000_400_000L,
            maxDurationMs = maxDurationMs
        )

        assertEquals(1_000_000L, deadline)
    }

    @Test
    fun expiredOrMalformedScheduleIsRejected() {
        val expiredSameBoot = resolveSleepTimerDeadline(
            storedEndElapsedMs = 100_000L,
            storedEndWallClockMs = 1_700_000_100_000L,
            storedDurationMs = 100_000L,
            storedBootCount = 1,
            currentBootCount = 1,
            storedBootEpochEstimateMs = 1_700_000_000_000L,
            currentBootEpochEstimateMs = 1_700_000_000_000L,
            nowElapsedMs = 100_000L,
            nowWallClockMs = 1_700_000_100_000L,
            maxDurationMs = maxDurationMs
        )
        val malformedDuration = resolveSleepTimerDeadline(
            storedEndElapsedMs = 100_000L,
            storedEndWallClockMs = 1_700_000_100_000L,
            storedDurationMs = maxDurationMs + 1L,
            storedBootCount = 1,
            currentBootCount = 1,
            storedBootEpochEstimateMs = 1_700_000_000_000L,
            currentBootEpochEstimateMs = 1_700_000_000_000L,
            nowElapsedMs = 10_000L,
            nowWallClockMs = 1_700_000_010_000L,
            maxDurationMs = maxDurationMs
        )

        assertNull(expiredSameBoot)
        assertNull(malformedDuration)
    }
}

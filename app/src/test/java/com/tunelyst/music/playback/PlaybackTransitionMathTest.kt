package com.tunelyst.music.playback

import com.tunelyst.music.data.CrossfadeCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTransitionMathTest {
    @Test
    fun linearCurveKeepsTotalGainAtEveryPoint() {
        for (step in 0..100) {
            val gains = crossfadeGains(step / 100f, CrossfadeCurve.LINEAR)
            assertEquals(1f, gains.outgoing + gains.incoming, 0.0001f)
        }
    }

    @Test
    fun equalPowerCurveKeepsCombinedPowerConstant() {
        for (step in 0..100) {
            val gains = crossfadeGains(step / 100f, CrossfadeCurve.EQUAL_POWER)
            assertEquals(1f, gains.outgoing * gains.outgoing + gains.incoming * gains.incoming, 0.0001f)
        }
    }

    @Test
    fun bothCurvesStartAndFinishAtTheExpectedEndpoints() {
        for (curve in CrossfadeCurve.entries) {
            val start = crossfadeGains(0f, curve)
            val middle = crossfadeGains(0.5f, curve)
            val end = crossfadeGains(1f, curve)

            assertEquals(1f, start.outgoing, 0f)
            assertEquals(0f, start.incoming, 0f)
            assertTrue(middle.outgoing in 0f..1f)
            assertTrue(middle.incoming in 0f..1f)
            assertEquals(0f, end.outgoing, 0f)
            assertEquals(1f, end.incoming, 0f)
        }
    }
}

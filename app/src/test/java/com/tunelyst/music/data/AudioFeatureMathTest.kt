package com.tunelyst.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioFeatureMathTest {
    @Test
    fun energyIncreasesWithRmsLevel() {
        val sampleRate = 22_050
        val quiet = sineWave(sampleRate, seconds = 8, amplitude = 0.10f)
        val loud = sineWave(sampleRate, seconds = 8, amplitude = 0.75f)

        val quietFeatures = AudioFeatureMath.analyzePcm(quiet, sampleRate)
        val loudFeatures = AudioFeatureMath.analyzePcm(loud, sampleRate)

        val quietResult = quietFeatures ?: error("Quiet PCM should still yield an energy estimate")
        val loudResult = loudFeatures ?: error("Loud PCM should still yield an energy estimate")
        assertTrue(loudResult.energy > quietResult.energy + 0.25f)
        assertTrue(loudResult.energy in 0f..1f)
    }

    @Test
    fun regular120BpmPulseFixtureEstimatesAbout120Bpm() {
        val sampleRate = 22_050
        val samples = FloatArray(sampleRate * 12)
        val beatPeriod = sampleRate / 2
        val burstLength = sampleRate / 25
        var beatStart = beatPeriod / 3
        while (beatStart < samples.size) {
            for (offset in 0 until burstLength) {
                val envelope = kotlin.math.exp(-offset.toDouble() / (sampleRate * 0.012)).toFloat()
                samples[beatStart + offset] = (sin(2.0 * PI * 1_100.0 * offset / sampleRate) * envelope * 0.8).toFloat()
            }
            beatStart += beatPeriod
        }

        val features = AudioFeatureMath.analyzePcm(samples, sampleRate)
            ?: error("Pulse PCM should yield an audio-feature result")
        val tempoBpm = features.tempoBpm
            ?: error("A regular pulse train should produce a tempo estimate")
        assertTrue("Tempo should be close to 120 BPM, got $tempoBpm",
            kotlin.math.abs(tempoBpm - 120f) <= 12f)
        assertTrue(features.tempoConfidence >= 0.08f)
    }

    @Test
    fun playbackParameterSanitizerRejectsNonFiniteValuesAndClampsFiniteRates() {
        assertEquals(1f, sanitizePlaybackParameter(Float.NaN), 0f)
        assertEquals(1f, sanitizePlaybackParameter(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, sanitizePlaybackParameter(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(0.5f, sanitizePlaybackParameter(0.1f), 0f)
        assertEquals(2f, sanitizePlaybackParameter(3f), 0f)
        assertEquals(1.25f, sanitizePlaybackParameter(1.25f), 0f)
    }

    @Test
    fun silenceHasNoTempoAndMinimalEnergy() {
        val features = AudioFeatureMath.analyzePcm(FloatArray(22_050 * 8), 22_050)
            ?: error("Silence should still yield an energy estimate")
        assertNull(features.tempoBpm)
        assertEquals(0f, features.energy, 0.001f)
    }

    private fun sineWave(sampleRate: Int, seconds: Int, amplitude: Float): FloatArray =
        FloatArray(sampleRate * seconds) { index ->
            (sin(2.0 * PI * 440.0 * index / sampleRate) * amplitude).toFloat()
        }
}

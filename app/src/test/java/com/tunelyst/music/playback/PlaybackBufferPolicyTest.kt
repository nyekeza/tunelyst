package com.tunelyst.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBufferPolicyTest {
    @Test
    fun lowMemoryProfileBoundsBothPlayerBuffers() {
        val policy = PlaybackBufferPolicy.forLowMemoryDevice(lowMemory = true)

        assertTrue(policy.minBufferMs >= policy.bufferForPlaybackMs)
        assertTrue(policy.minBufferMs >= policy.bufferForPlaybackAfterRebufferMs)
        assertTrue(policy.maxBufferMs >= policy.minBufferMs)
        assertEquals(2 * 1024 * 1024, policy.targetBufferBytes)
        assertTrue(2L * policy.targetBufferBytes <= 4L * 1024L * 1024L)
    }

    @Test
    fun standardProfileStillCapsCrossfadePrebuffering() {
        val policy = PlaybackBufferPolicy.forLowMemoryDevice(lowMemory = false)

        assertEquals(12_000, policy.maxBufferMs)
        assertTrue(policy.minBufferMs >= policy.bufferForPlaybackAfterRebufferMs)
        assertEquals(4 * 1024 * 1024, policy.targetBufferBytes)
        assertTrue(2L * policy.targetBufferBytes <= 8L * 1024L * 1024L)
    }
}

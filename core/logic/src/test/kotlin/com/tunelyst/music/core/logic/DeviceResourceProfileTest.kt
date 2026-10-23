package com.tunelyst.music.core.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceResourceProfileTest {
    @Test
    fun platformLowRamFlagAlwaysEnablesConservativeProfile() {
        assertTrue(isTunelystLowMemoryDevice(platformLowRam = true, memoryClassMb = 256, maxHeapBytes = 256L * 1024L * 1024L))
    }

    @Test
    fun smallMemoryClassEnablesConservativeProfile() {
        assertTrue(isTunelystLowMemoryDevice(platformLowRam = false, memoryClassMb = 128, maxHeapBytes = 512L * 1024L * 1024L))
    }

    @Test
    fun smallRuntimeHeapEnablesConservativeProfile() {
        assertTrue(isTunelystLowMemoryDevice(platformLowRam = false, memoryClassMb = 256, maxHeapBytes = 160L * 1024L * 1024L))
    }

    @Test
    fun capableDeviceDoesNotGetLowMemoryProfile() {
        assertFalse(isTunelystLowMemoryDevice(platformLowRam = false, memoryClassMb = 256, maxHeapBytes = 256L * 1024L * 1024L))
    }
}

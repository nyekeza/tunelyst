package com.tunelyst.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeModeTest {
    @Test
    fun legacyWhiteThemeFallsBackToPitchBlack() {
        assertEquals(AppThemeMode.PITCH_BLACK, AppThemeMode.fromStorage("white"))
    }

    @Test
    fun adaptiveDeviceColorsRemainAvailable() {
        assertEquals(AppThemeMode.DEVICE_COLORS, AppThemeMode.fromStorage("device_colors"))
    }

    @Test
    fun unknownThemeFallsBackToPitchBlack() {
        assertEquals(AppThemeMode.PITCH_BLACK, AppThemeMode.fromStorage("unknown"))
    }
}

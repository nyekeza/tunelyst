package com.tunelyst.music.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import com.tunelyst.music.data.AppThemeMode
import com.tunelyst.music.core.logic.isTunelystLowMemoryDevice

internal data class TunelystPalette(
    val background: Color,
    val backgroundSoft: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val primary: Color,
    val primaryDeep: Color,
    val rose: Color,
    val lime: Color,
    val text: Color,
    val muted: Color,
    val subtle: Color,
    val border: Color,
    val onPrimary: Color,
    val isLight: Boolean
)

private val PitchBlackPalette = TunelystPalette(
    // Keep the established dark surfaces and let the new yellow/amber mark lead the accents.
    background = Color.Black,
    backgroundSoft = Color(0xFF060C11),
    surface = Color(0xE60B151C),
    surfaceRaised = Color(0xEF14222B),
    primary = Color(0xFFFFD42A),
    primaryDeep = Color(0xFFFF8F00),
    rose = Color(0xFFFFA000),
    lime = Color(0xFFFFE082),
    text = Color(0xFFF4FAFC),
    muted = Color(0xFFBCCAD1),
    subtle = Color(0xFF8D9BA3),
    border = Color(0xFF293A43),
    onPrimary = Color(0xFF241600),
    isLight = false
)

internal val LocalTunelystPalette = staticCompositionLocalOf { PitchBlackPalette }
internal val LocalTunelystMotionEnabled = staticCompositionLocalOf { true }
internal val LocalTunelystTouchTargetsLarge = staticCompositionLocalOf { false }
internal val LocalTunelystLowMemoryDevice = staticCompositionLocalOf { false }

/** Theme colors are composition-local so every screen, dialog and player control follows the selected skin. */
@Composable
fun TunelystTheme(
    themeMode: AppThemeMode,
    highContrastEnabled: Boolean = false,
    reducedMotionEnabled: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val lowMemoryDevice = remember(context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        isTunelystLowMemoryDevice(
            platformLowRam = activityManager?.isLowRamDevice == true,
            memoryClassMb = activityManager?.memoryClass ?: Int.MAX_VALUE,
            maxHeapBytes = Runtime.getRuntime().maxMemory()
        )
    }
    val systemDark = isSystemInDarkTheme()
    val deviceColorScheme = remember(context, themeMode, systemDark) {
        if (themeMode == AppThemeMode.DEVICE_COLORS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            null
        }
    }
    val basePalette = when {
        deviceColorScheme != null -> paletteFrom(deviceColorScheme, isLight = !systemDark)
        else -> PitchBlackPalette
    }
    val contrastAdjustedPalette = if (highContrastEnabled) basePalette.copy(
        text = if (basePalette.isLight) Color.Black else Color.White,
        muted = if (basePalette.isLight) Color(0xFF28232D) else Color.White,
        subtle = if (basePalette.isLight) Color(0xFF35303A) else Color.White,
        border = if (basePalette.isLight) Color(0xFF4A4451) else Color(0xFFB9AEC4),
        primary = if (basePalette.isLight) Color(0xFF704500) else Color(0xFFFFEE70)
    ) else basePalette
    val palette = if (contrastAdjustedPalette.isLight) contrastAdjustedPalette else contrastAdjustedPalette.copy(
        text = Color.White,
        muted = Color.White,
        subtle = Color.White
    )
    val colorScheme = if (deviceColorScheme != null && !highContrastEnabled) deviceColorScheme else if (palette.isLight) {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            secondary = palette.rose,
            onSecondary = Color.White,
            tertiary = palette.lime,
            background = palette.background,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceRaised,
            onSurfaceVariant = palette.muted,
            outline = palette.border
        )
    } else {
        darkColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            secondary = palette.rose,
            onSecondary = Color(0xFF061620),
            tertiary = palette.lime,
            background = palette.background,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceRaised,
            onSurfaceVariant = palette.muted,
            outline = palette.border
        )
    }
    CompositionLocalProvider(
        LocalTunelystPalette provides palette,
        LocalTunelystMotionEnabled provides !reducedMotionEnabled,
        LocalTunelystTouchTargetsLarge provides false,
        LocalTunelystLowMemoryDevice provides lowMemoryDevice
    ) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

private fun paletteFrom(scheme: ColorScheme, isLight: Boolean) = TunelystPalette(
    background = scheme.background,
    backgroundSoft = scheme.surfaceVariant,
    surface = scheme.surface.copy(alpha = 0.94f),
    surfaceRaised = scheme.surfaceVariant,
    primary = scheme.primary,
    primaryDeep = scheme.primaryContainer,
    rose = scheme.tertiary,
    lime = scheme.secondary,
    text = scheme.onBackground,
    muted = scheme.onSurfaceVariant,
    subtle = scheme.onSurfaceVariant,
    border = scheme.outlineVariant,
    onPrimary = scheme.onPrimary,
    isLight = isLight
)

/** Central color accessor keeps custom Compose surfaces synced with the active palette. */
internal object TunelystColors {
    val Background: Color
        @Composable get() = LocalTunelystPalette.current.background
    val BackgroundSoft: Color
        @Composable get() = LocalTunelystPalette.current.backgroundSoft
    val Surface: Color
        @Composable get() = LocalTunelystPalette.current.surface
    val SurfaceRaised: Color
        @Composable get() = LocalTunelystPalette.current.surfaceRaised
    val Primary: Color
        @Composable get() = LocalTunelystPalette.current.primary
    val PrimaryDeep: Color
        @Composable get() = LocalTunelystPalette.current.primaryDeep
    val Rose: Color
        @Composable get() = LocalTunelystPalette.current.rose
    val Lime: Color
        @Composable get() = LocalTunelystPalette.current.lime
    val Text: Color
        @Composable get() = LocalTunelystPalette.current.text
    val Muted: Color
        @Composable get() = LocalTunelystPalette.current.muted
    val Subtle: Color
        @Composable get() = LocalTunelystPalette.current.subtle
    val Border: Color
        @Composable get() = LocalTunelystPalette.current.border
    val OnPrimary: Color
        @Composable get() = LocalTunelystPalette.current.onPrimary
}

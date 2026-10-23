package com.tunelyst.music.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat

@Composable
internal fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = TunelystColors.Primary,
    selectedTextColor = TunelystColors.Primary,
    indicatorColor = TunelystColors.Primary.copy(alpha = 0.13f),
    unselectedIconColor = TunelystColors.Subtle,
    unselectedTextColor = TunelystColors.Subtle
)

@Composable
internal fun MusicLibraryGlyph(selected: Boolean = false) {
    LibraryTabIcon(selected = selected)
}

internal fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, audioPermissionForDevice()) == PackageManager.PERMISSION_GRANTED

internal fun audioPermissionForDevice(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    Manifest.permission.READ_MEDIA_AUDIO
} else {
    Manifest.permission.READ_EXTERNAL_STORAGE
}

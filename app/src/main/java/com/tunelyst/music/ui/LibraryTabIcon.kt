package com.tunelyst.music.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Material's rounded Library Music glyph, sized and animated to match Tunelyst's icon-only tabs. */
@Composable
internal fun LibraryTabIcon(
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    ExpressiveToggleIcon(
        selected = selected,
        selectedIcon = Icons.Filled.LibraryMusic,
        unselectedIcon = Icons.Rounded.LibraryMusic,
        contentDescription = "Library",
        tint = LocalContentColor.current,
        modifier = modifier,
        size = 28.dp,
        label = "navigation-library-icon"
    )
}

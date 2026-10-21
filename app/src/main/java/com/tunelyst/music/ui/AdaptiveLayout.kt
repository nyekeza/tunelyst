package com.tunelyst.music.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A shared compact-layout breakpoint; text itself continues to follow Android's font scale. */
@Composable
internal fun isTunelystCompactViewport(): Boolean {
    val configuration = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    return fontScale >= 1.25f || configuration.screenWidthDp < 360 || configuration.screenHeightDp < 650
}

/** A tighter layout for very short/narrow windows or particularly large accessibility text. */
@Composable
internal fun isTunelystVeryCompactViewport(): Boolean {
    val configuration = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    return fontScale >= 1.5f || configuration.screenWidthDp < 340 || configuration.screenHeightDp < 580
}

@Composable
internal fun tunelystHorizontalContentInset(): Dp =
    if (LocalConfiguration.current.screenWidthDp < 360) 16.dp else 20.dp

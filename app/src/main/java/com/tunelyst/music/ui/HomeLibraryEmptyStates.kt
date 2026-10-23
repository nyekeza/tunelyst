package com.tunelyst.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun LoadingCard(message: String = "Looking for music on this device…") {
    val motionEnabled = LocalTunelystMotionEnabled.current
    Surface(color = TunelystColors.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (motionEnabled) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = TunelystColors.Primary, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = TunelystColors.Primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(message, color = TunelystColors.Muted, fontSize = 14.sp)
        }
    }
}

@Composable
internal fun EmptyLibraryCard(onRescan: () -> Unit, errorMessage: String? = null) {
    val hasError = !errorMessage.isNullOrBlank()
    val motionEnabled = LocalTunelystMotionEnabled.current
    Surface(color = TunelystColors.Surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.Start) {
            ExpressiveToggleIcon(
                selected = hasError,
                selectedIcon = Icons.Rounded.Refresh,
                unselectedIcon = Icons.Rounded.MusicNote,
                contentDescription = if (hasError) "Rescan library" else "Music library",
                tint = if (hasError) TunelystColors.Rose else TunelystColors.Primary,
                size = 30.dp,
                label = "library-empty-state-icon"
            )
            AnimatedContent(
                targetState = hasError,
                transitionSpec = {
                    if (motionEnabled) fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                    else EnterTransition.None togetherWith ExitTransition.None
                },
                label = "library-empty-state-title"
            ) { error ->
                Text(
                    if (error) "Couldn't scan for songs" else "No tracks found just yet",
                    color = TunelystColors.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 9.dp)
                )
            }
            Text(
                errorMessage ?: "Add audio files to your device, then scan again. Tunelyst plays music stored locally — no streaming or account needed.",
                color = TunelystColors.Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                if (hasError) "Try scanning again  ↗" else "Scan again  ↗",
                color = TunelystColors.Primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.springyClickable(onClick = onRescan).padding(top = 14.dp)
            )
        }
    }
}

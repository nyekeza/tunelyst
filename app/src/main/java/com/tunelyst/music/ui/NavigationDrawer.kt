package com.tunelyst.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class DrawerShortcutIcon {
    Settings,
    ListeningStats
}

@Composable
internal fun TunelystMainDrawer(
    drawerState: DrawerState,
    gesturesEnabled: Boolean,
    onOpenSettings: () -> Unit,
    onOpenListeningStats: () -> Unit,
    content: @Composable () -> Unit
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        scrimColor = Color.Black.copy(alpha = 0.62f),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxHeight().widthIn(max = 352.dp),
                drawerContainerColor = TunelystColors.Surface
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TunelystBrandMark(Modifier.size(56.dp))
                        Text(
                            text = "Tunelyst",
                            color = TunelystColors.Text,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f).padding(start = 14.dp)
                        )
                    }
                    HomeDrawerAction(
                        icon = DrawerShortcutIcon.Settings,
                        title = "Settings",
                        onClick = onOpenSettings
                    )
                    Spacer(Modifier.height(10.dp))
                    HomeDrawerAction(
                        icon = DrawerShortcutIcon.ListeningStats,
                        title = "Listening stats",
                        onClick = onOpenListeningStats
                    )
                }
            }
        },
        content = content
    )
}

@Composable
private fun HomeDrawerAction(
    icon: DrawerShortcutIcon,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(TunelystColors.SurfaceRaised.copy(alpha = 0.48f))
            .springyClickable(role = Role.Button, pressedScale = 0.98f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                when (icon) {
                    DrawerShortcutIcon.Settings -> Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(23.dp)
                    )
                    DrawerShortcutIcon.ListeningStats -> ListeningStatsGlyph(Modifier.size(23.dp))
                }
            }
        }
        Text(title, color = TunelystColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Spotify-inspired listening-equalizer mark; an original bar glyph, not the Spotify logo. */
@Composable
private fun ListeningStatsGlyph(modifier: Modifier = Modifier) {
    val glyphColor = MaterialTheme.colorScheme.onSurfaceVariant
    val barHeights = floatArrayOf(0.38f, 0.67f, 0.50f, 0.88f)
    Canvas(modifier) {
        val barWidth = size.width * 0.14f
        val gap = size.width * 0.105f
        val totalWidth = barWidth * barHeights.size + gap * (barHeights.size - 1)
        val startX = (size.width - totalWidth) / 2f
        barHeights.forEachIndexed { index, heightFraction ->
            val barHeight = size.height * heightFraction
            drawRoundRect(
                color = glyphColor,
                topLeft = Offset(startX + index * (barWidth + gap), (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

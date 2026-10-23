package com.tunelyst.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource

@Composable
internal fun FullPlayerHeader(
    radioActive: Boolean,
    accent: Color,
    playerMenuOpen: Boolean,
    dismissInteraction: MutableInteractionSource,
    moreInteraction: MutableInteractionSource,
    onDismiss: () -> Unit,
    onOpenPlayerMenu: () -> Unit,
    onDismissPlayerMenu: () -> Unit,
    onShowSongInformation: () -> Unit,
    onStartRadio: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(46.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.28f)).springyPress(dismissInteraction),
            interactionSource = dismissInteraction
        ) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Minimize player", tint = TunelystColors.Text, modifier = Modifier.size(28.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text("NOW PLAYING", color = TunelystColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
            Text(
                text = "RADIO · ${if (radioActive) "ON" else "OFF"}",
                color = if (radioActive) accent else TunelystColors.Subtle,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp
            )
        }
        Box {
            IconButton(
                onClick = onOpenPlayerMenu,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.28f)).springyPress(moreInteraction),
                interactionSource = moreInteraction
            ) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Player options", tint = TunelystColors.Text)
            }
            DropdownMenu(
                expanded = playerMenuOpen,
                onDismissRequest = onDismissPlayerMenu,
                containerColor = TunelystColors.SurfaceRaised.copy(alpha = 0.86f)
            ) {
                DropdownMenuItem(
                    text = { Text("Song information") },
                    onClick = { onShowSongInformation() }
                )
                DropdownMenuItem(
                    text = { Text("Start radio from this song") },
                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                    onClick = { onStartRadio() }
                )
                DropdownMenuItem(
                    text = { Text("Open album") },
                    onClick = { onOpenAlbum() }
                )
                DropdownMenuItem(
                    text = { Text("Open artist") },
                    onClick = { onOpenArtist() }
                )
            }
        }
    }
}

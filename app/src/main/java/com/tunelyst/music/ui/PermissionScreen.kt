package com.tunelyst.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PermissionScreen(
    onRequestPermission: () -> Unit,
    showOpenSettings: Boolean = false,
    onOpenSettings: () -> Unit = {}
) {
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(TunelystColors.Background, TunelystColors.BackgroundSoft, TunelystColors.Background))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(112.dp).background(TunelystColors.Primary.copy(alpha = 0.13f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("♫", color = TunelystColors.Primary, fontSize = 58.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(28.dp))
            Text("Your music stays yours.", color = TunelystColors.Text, fontSize = 29.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                "Tunelyst uses audio access to find your music on this device for offline playback. Your files stay on your phone; nothing is uploaded.",
                color = TunelystColors.Muted,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(26.dp))
            AccentButton(
                text = "Allow library access",
                onClick = onRequestPermission,
                icon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = TunelystColors.OnPrimary, modifier = Modifier.size(18.dp)) }
            )
            if (showOpenSettings) {
                TextButton(onClick = onOpenSettings) { Text("Open Android app settings", color = TunelystColors.Primary) }
            }
            Spacer(Modifier.height(18.dp))
            Text("No account · no ads · no internet required", color = TunelystColors.Subtle, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

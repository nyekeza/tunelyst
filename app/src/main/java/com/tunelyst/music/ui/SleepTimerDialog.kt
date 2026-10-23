package com.tunelyst.music.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun SleepTimerDialog(
    currentMinutes: Int?,
    currentFadeOutSeconds: Int,
    onSelect: (Int, Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var customMinutes by remember { mutableStateOf("") }
    var fadeSeconds by remember(currentFadeOutSeconds) { mutableIntStateOf(currentFadeOutSeconds) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text("Pause playback after…", color = TunelystColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                listOf(15, 30, 45, 60).forEach { minutes ->
                    TextButton(onClick = { onSelect(minutes, fadeSeconds) }, modifier = Modifier.fillMaxWidth()) {
                        Text("$minutes minutes", color = TunelystColors.Text, modifier = Modifier.weight(1f))
                        if (minutes == currentMinutes) Text("ACTIVE", color = TunelystColors.Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedTextField(
                    value = customMinutes,
                    onValueChange = { customMinutes = it.filter(Char::isDigit).take(4) },
                    label = { Text("Custom minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
                )
                Text("Fade out · ${fadeSeconds}s", color = TunelystColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                Slider(value = fadeSeconds.toFloat(), onValueChange = { fadeSeconds = it.roundToInt() }, valueRange = 0f..60f, steps = 11)
                if (fadeSeconds > 0) Text("The fade runs at the end; crossfade is paused briefly so the volume curves don't conflict.", color = TunelystColors.Subtle, fontSize = 11.sp, lineHeight = 14.sp)
                val customValue = customMinutes.toIntOrNull()?.takeIf { it in 1..1_440 }
                TextButton(enabled = customValue != null, onClick = { customValue?.let { onSelect(it, fadeSeconds) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Set custom timer", color = TunelystColors.Primary, modifier = Modifier.weight(1f))
                    if (customValue == currentMinutes) Text("ACTIVE", color = TunelystColors.Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                if (currentMinutes != null) {
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Close, contentDescription = null, tint = TunelystColors.Rose)
                        Text("Cancel timer", color = TunelystColors.Rose, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        shape = RoundedCornerShape(26.dp),
        containerColor = TunelystColors.Surface
    )
}

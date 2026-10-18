package com.example.viavox.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viavox.R
import com.example.viavox.model.TrackingState

private val aqua = Color(0xFF42E6D1)
private val text = Color(0xFFF5F8FF)
private val subdued = Color(0xFFC0D0DF)
private val base = Color(0xF009172C)

/** A compact navigation HUD over a real map, not a full-height dashboard. */
@Composable
fun StatusPanel(
    state: TrackingState,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onToggleVoice: (Boolean) -> Unit,
    onRepeatSpeech: () -> Unit,
    onTestVoice: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.testTag("status_panel"),
        shape = RoundedCornerShape(25.dp),
        color = base,
        shadowElevation = 18.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier.size(7.dp).background(
                        if (state.isActive) aqua else Color(0xFF8CA2B7), CircleShape
                    )
                )
                Text(
                    if (state.isActive) "Localização ativa" else "Localização pausada",
                    color = if (state.isActive) aqua else subdued,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                state.accuracyMeters?.let { accuracy ->
                    Text("±$accuracy m", color = if (accuracy > 25) Color(0xFFFFCA91) else subdued, fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { if (state.isActive) onStopTracking() else onStartTracking() },
                    modifier = Modifier.height(31.dp).testTag("tracking_toggle")
                ) {
                    Text(if (state.isActive) "Pausar" else "Iniciar", color = subdued, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Box(
                    modifier = Modifier.size(54.dp)
                        .background(aqua.copy(alpha = 0.12f), CircleShape)
                        .border(
                            1.5.dp,
                            if (state.isVoiceEnabled) aqua else Color(0xFF738BA2),
                            CircleShape
                        )
                        .clickable { onToggleVoice(!state.isVoiceEnabled) }
                        .testTag("voice_toggle"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (state.isVoiceEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        if (state.isVoiceEnabled) "Desativar voz" else "Ativar voz",
                        tint = if (state.isVoiceEnabled) aqua else subdued,
                        modifier = Modifier.size(27.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.place?.takeIf { it.hasValidRoad() }?.road
                            ?: stringResource(R.string.where_are_you),
                        color = text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        lineHeight = 21.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("road_title")
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        state.place?.toDisplaySubtitle()?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.empty_address_hint),
                        color = subdued,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onRepeatSpeech,
                    enabled = state.place?.hasValidRoad() == true && state.isVoiceEnabled,
                    modifier = Modifier.size(43.dp)
                        .background(Color(0xFF25425E), CircleShape)
                        .testTag("repeat_button")
                ) {
                    Icon(Icons.Default.Replay, "Repetir rua", tint = text)
                }
            }

            if (state.voiceIssue != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0x332C4159), RoundedCornerShape(12.dp))
                        .padding(start = 9.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        state.voiceIssue,
                        color = Color(0xFFFFD5B3),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        testTag = "voice_diagnostic"
                    )
                    TextButton(onClick = onTestVoice, modifier = Modifier.testTag("test_voice_button")) {
                        Icon(Icons.Default.PlayArrow, null, tint = aqua, modifier = Modifier.size(18.dp))
                        Text("Testar", fontSize = 11.sp, color = aqua)
                    }
                }
            }
        }
    }
}

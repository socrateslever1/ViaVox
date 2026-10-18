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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viavox.R
import com.example.viavox.model.TrackingState

private val accent = Color(0xFF36DFCE)
private val dark = Color(0xFF08182C)

/**
 * Small navigation-style overlay. The map is the main screen, not a dashboard.
 * All controls perform real actions and remain reachable on narrow phones.
 */
@Composable
fun StatusPanel(
    state: TrackingState,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onToggleVoice: (Boolean) -> Unit,
    onRepeatSpeech: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.testTag("status_panel"),
        shape = RoundedCornerShape(26.dp),
        color = dark.copy(alpha = 0.96f),
        shadowElevation = 18.dp,
        tonalElevation = 3.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.size(7.dp).background(
                        if (state.isActive) accent else Color(0xFF8194A9),
                        CircleShape
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.isActive) "Localização ativa" else "Localização pausada",
                    color = if (state.isActive) accent else Color(0xFFB8CBDD),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                state.accuracyMeters?.let {
                    Spacer(Modifier.width(8.dp))
                    Text("±${it} m", color = Color(0xFF98AEC3), fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { if (state.isActive) onStopTracking() else onStartTracking() },
                    modifier = Modifier.height(33.dp).testTag(if (state.isActive) "stop_button" else "start_button")
                ) {
                    Text(
                        text = if (state.isActive) "Pausar" else "Iniciar",
                        color = if (state.isActive) Color(0xFFF1B8B5) else accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(5.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(57.dp)
                        .background(accent.copy(alpha = if (state.isVoiceEnabled) 0.15f else 0.06f), CircleShape)
                        .border(
                            1.5.dp,
                            if (state.isVoiceEnabled) accent else Color(0xFF5B6F82),
                            CircleShape
                        )
                        .clickable { onToggleVoice(!state.isVoiceEnabled) }
                        .testTag("voice_toggle"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (state.isVoiceEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = if (state.isVoiceEnabled) "Desativar voz" else "Ativar voz",
                        tint = if (state.isVoiceEnabled) accent else Color(0xFFA7B9C8),
                        modifier = Modifier.size(30.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.place?.takeIf { it.hasValidRoad() }?.road
                            ?: stringResource(R.string.where_are_you),
                        color = Color(0xFFF5F9FF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        lineHeight = 22.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("road_title")
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = state.place?.toDisplaySubtitle()?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.empty_address_hint),
                        color = Color(0xFFB2C5D7),
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("address_subtitle")
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    IconButton(
                        onClick = onRepeatSpeech,
                        enabled = state.place?.hasValidRoad() == true,
                        modifier = Modifier
                            .size(43.dp)
                            .background(Color(0xFF203956), CircleShape)
                            .testTag("repeat_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay,
                            contentDescription = "Repetir informação da rua",
                            tint = if (state.place?.hasValidRoad() == true) Color.White else Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text("Repetir", fontSize = 10.sp, color = Color(0xFFB8CDE0))
                }
            }
        }
    }
}

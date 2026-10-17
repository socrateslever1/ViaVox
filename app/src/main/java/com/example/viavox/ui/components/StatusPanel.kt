package com.example.viavox.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viavox.R
import com.example.viavox.model.RelativeDirection
import com.example.viavox.model.TrackingState
import com.example.viavox.ui.theme.ViaVoxBtnRepeat
import com.example.viavox.ui.theme.ViaVoxCard
import com.example.viavox.ui.theme.ViaVoxCoralDarkText
import com.example.viavox.ui.theme.ViaVoxCoralStop
import com.example.viavox.ui.theme.ViaVoxDarkBg
import com.example.viavox.ui.theme.ViaVoxDarkText
import com.example.viavox.ui.theme.ViaVoxReference
import com.example.viavox.ui.theme.ViaVoxTeal
import com.example.viavox.ui.theme.ViaVoxTextMuted
import com.example.viavox.ui.theme.ViaVoxTextSub
import com.example.viavox.ui.theme.ViaVoxTextWhite

@Composable
fun StatusPanel(
    state: TrackingState,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onToggleVoice: (Boolean) -> Unit,
    onRepeatSpeech: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ViaVoxDarkBg)
            .padding(horizontal = 20.dp, vertical = 22.dp)
            .testTag("status_panel")
    ) {
        // Eyebrow tracking indicator + Accuracy + Speed
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (state.isActive) ViaVoxTeal else ViaVoxTextMuted)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (state.isActive) {
                    stringResource(R.string.eyebrow_tracking)
                } else {
                    stringResource(R.string.eyebrow_paused)
                },
                color = ViaVoxTeal,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )

            state.accuracyMeters?.let { acc ->
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.accuracy_format, acc),
                    color = Color(0xFFB5D0E0),
                    fontSize = 11.sp
                )
            }

            if (state.isActive && state.speedKmh > 2f) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "· ${state.speedKmh.toInt()} km/h",
                    color = ViaVoxTeal,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Road Name H1
        Text(
            text = state.place?.road ?: stringResource(R.string.where_are_you),
            color = ViaVoxTextWhite,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 34.sp,
            modifier = Modifier
                .padding(bottom = 6.dp)
                .testTag("road_title")
        )

        // Subtitle (neighbourhood + city)
        Text(
            text = state.place?.toDisplaySubtitle() ?: stringResource(R.string.empty_address_hint),
            color = ViaVoxTextSub,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            modifier = Modifier
                .padding(bottom = 10.dp)
                .testTag("address_subtitle")
        )

        // Reference point if present
        AnimatedVisibility(
            visible = state.place?.reference?.isNotBlank() == true,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            state.place?.reference?.let { ref ->
                Text(
                    text = stringResource(R.string.reference_format, ref),
                    color = ViaVoxReference,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        // Nearby landmark POI card if discovered
        AnimatedVisibility(
            visible = state.currentPoi != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            state.currentPoi?.let { poi ->
                val dirLabel = when (state.poiDirection) {
                    RelativeDirection.RIGHT -> "👉 À sua direita"
                    RelativeDirection.LEFT -> "👈 À sua esquerda"
                    else -> "📍 Próximo daqui"
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ViaVoxCard)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = dirLabel,
                                color = ViaVoxTeal,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = poi.name,
                                color = ViaVoxTextWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // Status message
        Text(
            text = state.statusMessage,
            color = Color(0xFFA7BDCE),
            fontSize = 13.sp,
            modifier = Modifier
                .padding(bottom = 18.dp)
                .testTag("status_text")
        )

        // Primary Action Button (Start / Stop)
        Button(
            onClick = {
                if (state.isActive) onStopTracking() else onStartTracking()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isActive) ViaVoxCoralStop else ViaVoxTeal,
                contentColor = if (state.isActive) ViaVoxCoralDarkText else ViaVoxDarkText
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag(if (state.isActive) "stop_button" else "start_button")
        ) {
            Text(
                text = if (state.isActive) {
                    stringResource(R.string.btn_stop)
                } else {
                    stringResource(R.string.btn_start)
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Toggle: Narração por voz
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.toggle_voice),
                color = Color(0xFFD5E3ED),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )

            Switch(
                checked = state.isVoiceEnabled,
                onCheckedChange = onToggleVoice,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ViaVoxTeal,
                    checkedTrackColor = ViaVoxBtnRepeat,
                    uncheckedThumbColor = ViaVoxTextMuted,
                    uncheckedTrackColor = ViaVoxDarkBg
                ),
                modifier = Modifier.testTag("voice_toggle")
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Repeat button: "🔊 Ouvir localização atual"
        Button(
            onClick = onRepeatSpeech,
            enabled = state.place != null,
            colors = ButtonDefaults.buttonColors(
                containerColor = ViaVoxBtnRepeat,
                contentColor = Color(0xFFEEF6FF),
                disabledContainerColor = ViaVoxBtnRepeat.copy(alpha = 0.4f),
                disabledContentColor = Color(0xFFEEF6FF).copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("repeat_button")
        ) {
            Text(
                text = stringResource(R.string.btn_repeat),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Explanatory note
        Text(
            text = stringResource(R.string.info_note),
            color = Color(0xFF8FA5B8),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

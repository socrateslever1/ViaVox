package com.example.viavox.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viavox.R
import com.example.viavox.model.RelativeDirection
import com.example.viavox.model.TrackingState
import com.example.viavox.ui.theme.ViaVoxBtnRepeat
import com.example.viavox.ui.theme.ViaVoxCard
import com.example.viavox.ui.theme.ViaVoxCoralStop
import com.example.viavox.ui.theme.ViaVoxDarkBg
import com.example.viavox.ui.theme.ViaVoxHeader
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
            .clip(RoundedCornerShape(22.dp))
            .background(ViaVoxHeader.copy(alpha = 0.96f))
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .testTag("status_panel")
    ) {
        // Compact header line: GPS status dot + status text + accuracy + discreet pause/resume control
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (state.isActive) ViaVoxTeal else ViaVoxTextMuted)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = state.statusMessage,
                    color = ViaVoxTeal,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("status_text")
                )

                state.accuracyMeters?.let { acc ->
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.accuracy_format, acc),
                        color = Color(0xFFB5D0E0),
                        fontSize = 11.sp
                    )
                }
            }

            TextButton(
                onClick = { if (state.isActive) onStopTracking() else onStartTracking() },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier
                    .height(34.dp)
                    .testTag(if (state.isActive) "stop_button" else "start_button")
            ) {
                Text(
                    text = if (state.isActive) {
                        stringResource(R.string.btn_stop)
                    } else {
                        stringResource(R.string.btn_start)
                    },
                    color = if (state.isActive) ViaVoxCoralStop else ViaVoxTeal,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Current Road Name
        Text(
            text = state.place?.takeIf { it.hasValidRoad() }?.road ?: stringResource(R.string.where_are_you),
            color = ViaVoxTextWhite,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 27.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 2.dp, bottom = 2.dp)
                .testTag("road_title")
        )

        // Neighbourhood / City subtitle when identified
        val subtitle = state.place?.toDisplaySubtitle().orEmpty()
        Text(
            text = subtitle.ifBlank { stringResource(R.string.empty_address_hint) },
            color = ViaVoxTextSub,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .testTag("address_subtitle")
        )

        // Nearby POI or reference banner when present
        AnimatedVisibility(
            visible = state.currentPoi != null || state.place?.reference?.isNotBlank() == true,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val poi = state.currentPoi
            val text = if (poi != null) {
                val dirPrefix = when (state.poiDirection) {
                    RelativeDirection.RIGHT -> "À sua direita:"
                    RelativeDirection.LEFT -> "À sua esquerda:"
                    else -> "Próximo:"
                }
                "$dirPrefix ${poi.name}"
            } else {
                stringResource(R.string.reference_format, state.place?.reference.orEmpty())
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ViaVoxCard)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = text,
                    color = ViaVoxReference,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Compact Controls Row: Voice Activation Switch + Repeat Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                Text(
                    text = stringResource(R.string.toggle_voice),
                    color = Color(0xFFD5E3ED),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Button(
                onClick = onRepeatSpeech,
                enabled = state.place?.hasValidRoad() == true,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ViaVoxBtnRepeat,
                    contentColor = Color(0xFFEEF6FF),
                    disabledContainerColor = ViaVoxBtnRepeat.copy(alpha = 0.4f),
                    disabledContentColor = Color(0xFFEEF6FF).copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("repeat_button")
            ) {
                Text(
                    text = stringResource(R.string.btn_repeat),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

package com.example.viavox.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viavox.R
import com.example.viavox.model.TrackingState
import com.example.viavox.service.VoiceDataInstaller

private val teal = Color(0xFF50DECA)
private val white = Color(0xFFF4F8FF)
private val muted = Color(0xFFB7CDDC)
private val navy = Color(0xF20A2035)
private val warning = Color(0xFFFFCEA5)

/**
 * Primary street card deliberately contains only navigational information.
 * Technical HTTP codes, long police jurisdiction names and network retry
 * states belong in optional diagnostics, not on the driving HUD.
 */
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
    val context = LocalContext.current
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val place = state.place
    val road = place?.takeIf { it.hasValidRoad() }?.road
        ?: stringResource(R.string.where_are_you)
    val contextLine = listOfNotNull(
        place?.takeIf { it.hasValidNeighbourhood() }?.neighbourhood,
        place?.city?.takeIf(String::isNotBlank)
    ).distinct().joinToString(" · ").ifBlank {
        stringResource(R.string.empty_address_hint)
    }
    val policeLabel = place?.policeDistrict?.takeIf(String::isNotBlank)
    val voiceIssue = when {
        state.config.voiceProvider == "francisca" && !state.config.hasFranciscaSettings() ->
            "Francisca não está configurada. A voz Android continua disponível."
        else -> state.voiceIssue
    }
    val poiSummary = when {
        state.poiStatus.startsWith("Referência detectada:") ->
            state.poiStatus
        state.poiStatus.startsWith("Enviando à voz:") ->
            state.poiStatus.replace("Enviando à voz:", "Referência:")
        else -> null
    }

    Surface(
        modifier = modifier.testTag("status_panel"),
        shape = RoundedCornerShape(26.dp),
        color = navy,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, Color(0x553F6484))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(7.dp).background(
                    if (state.isActive) teal else muted, CircleShape
                ))
                Spacer(Modifier.width(7.dp))
                Text(
                    if (state.isActive) "GPS ativo" else "GPS pausado",
                    color = if (state.isActive) teal else muted,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                state.accuracyMeters?.let { accuracy ->
                    Text(
                        "  ±$accuracy m",
                        color = if (accuracy > 25) warning else muted,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { if (state.isActive) onStopTracking() else onStartTracking() },
                    contentPadding = PaddingValues(horizontal = 8.dp,vertical = 0.dp),
                    modifier = Modifier.height(29.dp).testTag("tracking_toggle")
                ) {
                    Text(if (state.isActive) "Pausar" else "Iniciar", color = muted, fontSize = 12.sp)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.size(48.dp)
                        .background(teal.copy(alpha = 0.10f), CircleShape)
                        .border(
                            1.5.dp,
                            if (state.isVoiceEnabled) teal else muted, CircleShape
                        )
                        .clickable { onToggleVoice(!state.isVoiceEnabled) }
                        .testTag("voice_toggle"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (state.isVoiceEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = if (state.isVoiceEnabled) "Desativar voz" else "Ativar voz",
                        tint = if (state.isVoiceEnabled) teal else muted,
                        modifier = Modifier.size(25.dp)
                    )
                }
                Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        road,
                        fontSize = 20.sp, lineHeight = 23.sp, color = white,
                        fontWeight = FontWeight.Bold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("road_title")
                    )
                    Text(
                        contextLine,
                        fontSize = 12.sp, color = muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onRepeatSpeech,
                    enabled = state.isVoiceEnabled && place?.hasValidRoad() == true,
                    modifier = Modifier.size(43.dp)
                        .background(Color(0xFF24445E), CircleShape)
                        .testTag("repeat_button")
                ) {
                    Icon(Icons.Default.Replay, contentDescription = "Repetir rua", tint = white)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                place?.zone?.takeIf(String::isNotBlank)?.let { zone ->
                    TinyLabel(zone)
                }
                if (policeLabel != null) TinyLabel("Área policial")
                if (voiceIssue != null) {
                    Text("Voz requer atenção", fontSize = 10.sp, color = warning, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { detailsExpanded = !detailsExpanded },
                    contentPadding = PaddingValues(horizontal = 3.dp,vertical = 0.dp),
                    modifier = Modifier.height(29.dp).testTag("street_more_details")
                ) {
                    Icon(Icons.Default.Info, null, tint = teal, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(if (detailsExpanded) "Ocultar" else "Detalhes", color = teal, fontSize = 11.sp)
                }
            }

            // Real nearby POIs are useful driving information; raw backend errors aren't.
            if (poiSummary != null) {
                Text(
                    poiSummary,
                    color = teal,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("poi_summary")
                )
            }

            if (detailsExpanded) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.13f))
                if (policeLabel != null) {
                    Text(
                        "Circunscrição (estimativa): $policeLabel",
                        fontSize = 11.sp, lineHeight = 15.sp, color = muted,
                        maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    PoiStatusPresenter.display(state.poiStatus),
                    color = muted,
                    fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.testTag("poi_discovery_status"),
                    maxLines = 2
                )
                if (voiceIssue != null) {
                    Text(
                        voiceIssue,
                        color = warning, fontSize = 11.sp,
                        modifier = Modifier.testTag("voice_diagnostic")
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    TextButton(
                        onClick = onTestVoice,
                        modifier = Modifier.testTag("test_voice_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = teal, modifier = Modifier.size(17.dp))
                        Text("Testar voz", color = teal, fontSize = 12.sp)
                    }
                    if (voiceIssue?.contains("instalad",ignoreCase = true) == true ||
                        voiceIssue?.contains("erro -9") == true
                    ) {
                        TextButton(
                            onClick = { VoiceDataInstaller.open(context) },
                            modifier = Modifier.testTag("install_voice_button")
                        ) {
                            Text("Instalar voz", color = teal, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TinyLabel(label: String) {
    Surface(
        color = Color(0xFF263F54),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.height(23.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(label,fontSize = 10.sp,color = muted,maxLines = 1)
        }
    }
}

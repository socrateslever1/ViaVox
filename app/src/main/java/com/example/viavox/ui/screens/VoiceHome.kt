package com.example.viavox.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import com.example.viavox.model.TrackingState
import com.example.viavox.model.ScanDirection
import com.example.viavox.model.AssistanceMode

private val midnight = Color(0xFF07111F)
private val panel = Color(0xFF102237)
private val mint = Color(0xFF35E0C3)
private val soft = Color(0xFF9CB2C5)

@Composable
fun VoiceHome(
    state: TrackingState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleVoice: (Boolean) -> Unit,
    onRepeat: () -> Unit,
    onSavePlace: () -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMap: () -> Unit,
    onSelectMode: (String) -> Unit,
    onScan: (ScanDirection) -> Unit,
    modifier: Modifier = Modifier
) {
    val place = state.place
    Column(
        modifier = modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(midnight, Color(0xFF102C40), midnight)))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(19.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("V I A V O X", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                Text("SEU MUNDO, NARRADO PARA VOCÊ", color = mint, fontSize = 10.sp, letterSpacing = 1.sp)
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("voice_home_settings")) {
                Icon(Icons.Default.Settings, contentDescription = "Configurações", tint = Color.White)
            }
        }
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF153449), border = BorderStroke(1.dp, Color(0xFF315367))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(if (state.isActive) mint else Color(0xFFFFAD70), CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(if (state.isActive) "GPS ATIVO" else "GPS DESLIGADO", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(state.accuracyMeters?.let { "± ${it} m" } ?: "Sem sinal", color = soft, fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { if (state.isActive) onStop() else onStart() }, modifier = Modifier.testTag("tracking_toggle")) {
                    Text(if (state.isActive) "Parar" else "Iniciar", color = mint, fontSize = 12.sp)
                }
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(170.dp)
                    .background(mint.copy(alpha = 0.13f), CircleShape)
                    .padding(14.dp)
                    .background(Brush.linearGradient(listOf(Color(0xFF43ECD5), Color(0xFF0F988F))), CircleShape)
                    .clickable {
                        if (!state.isActive) onStart()
                        if (!state.isVoiceEnabled) onToggleVoice(true)
                        if (state.isActive && state.isVoiceEnabled) onRepeat()
                    }
                    .testTag("voice_home_primary"),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = "Onde estou? Ouvir localização atual", tint = midnight, modifier = Modifier.size(70.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text("ONDE ESTOU?", color = Color.White, fontWeight = FontWeight.Black, fontSize = 21.sp)
            Text("Toque para ouvir sua localização", color = soft, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Narração automática", color = soft, fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                Switch(checked = state.isVoiceEnabled, onCheckedChange = onToggleVoice,
                    modifier = Modifier.testTag("voice_auto_toggle"))
            }
        }
        Surface(color = panel, shape = RoundedCornerShape(25.dp), border = BorderStroke(1.dp, Color(0xFF294659))) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("LOCALIZAÇÃO ATUAL", color = mint, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
                Text(place?.road?.takeIf { it.isNotBlank() } ?: "Aguardando identificação da rua",
                    color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        PlaceField("BAIRRO", place?.neighbourhood?.takeIf { it.isNotBlank() } ?: "Não identificado")
                    }
                    Column(Modifier.weight(1f)) {
                        PlaceField("ZONA", place?.zone?.takeIf { it.isNotBlank() } ?: "Não identificada")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        PlaceField("CICOM / CIRCUNSCRIÇÃO", place?.policeDistrict?.takeIf { it.isNotBlank() } ?: "Não confirmada")
                    }
                    Column(Modifier.weight(1f)) {
                        PlaceField("VELOCIDADE", "${state.speedKmh.toInt().coerceAtLeast(0)} km/h")
                    }
                }
            }
        }
        Text("EXPLORAÇÃO POR VOZ", color = soft, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Shortcut("Ao meu redor", Icons.Default.Explore, Modifier.weight(1f)) {
                onScan(ScanDirection.AROUND)
            }
            Shortcut("À minha frente", Icons.Default.Navigation, Modifier.weight(1f)) {
                onScan(ScanDirection.FRONT)
            }
        }
        Text("Varredura 360° de até " + state.config.surroundingsRadiusMeters + " m · ajuste o alcance nas configurações",
            color = soft, fontSize = 11.sp)
        Text("MODO DE ASSISTÊNCIA", color = soft, fontWeight = FontWeight.Bold,
            fontSize = 11.sp, letterSpacing = 1.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            AssistanceMode.entries.forEach { mode ->
                val chosen = state.config.assistanceMode == mode.storedValue
                Surface(
                    modifier = Modifier.weight(1f).clickable { onSelectMode(mode.storedValue) },
                    shape = RoundedCornerShape(13.dp),
                    color = if (chosen) mint else panel,
                    border = BorderStroke(1.dp, if (chosen) mint else Color(0xFF345066))
                ) {
                    Text(mode.title, modifier = Modifier.padding(vertical = 13.dp, horizontal = 3.dp),
                        color = if (chosen) midnight else Color.White, fontSize = 11.sp,
                        textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                        maxLines = 1)
                }
            }
        }
        if (state.config.assistanceMode == AssistanceMode.ACCESSIBILITY.storedValue) {
            Text(if (state.config.tourEnabled) "Tour ativo · referências especiais narradas com intervalos."
                else "Tour desligado · ative nas configurações para ouvir referências durante o percurso.",
                color = soft, fontSize = 11.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Shortcut("Meus lugares", Icons.Default.Home, Modifier.weight(1f), onOpenPlaces)
            Shortcut("Salvar aqui", Icons.Default.BookmarkAdd, Modifier.weight(1f), onSavePlace)
            Shortcut("Abrir mapa", Icons.Default.Map, Modifier.weight(1f), onOpenMap)
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun PlaceField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = soft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(value, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Shortcut(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, action: () -> Unit) {
    Surface(modifier = modifier.clickable(onClick = action), color = panel,
        shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFF294659))) {
        Column(Modifier.padding(vertical = 16.dp, horizontal = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = mint, modifier = Modifier.size(26.dp))
            Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1)
        }
    }
}

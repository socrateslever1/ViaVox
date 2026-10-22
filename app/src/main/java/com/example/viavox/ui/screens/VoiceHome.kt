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
import com.example.viavox.model.TrackingState

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
    modifier: Modifier = Modifier
) {
    var activeProfile by remember { mutableStateOf("Explorador") }
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
        Surface(shape = RoundedCornerShape(40.dp), color = Color(0xFF153449), border = BorderStroke(1.dp, Color(0xFF315367))) {
            Row(Modifier.padding(horizontal = 17.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(if (state.isActive) mint else Color(0xFFFFAD70), CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(if (state.isActive) "LOCALIZAÇÃO ATIVA" else "LOCALIZAÇÃO DESATIVADA", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(state.accuracyMeters?.let { "± ${it} m" } ?: "Sem sinal", color = soft, fontSize = 11.sp)
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier.size(176.dp)
                    .background(mint.copy(alpha = if (state.isActive && state.isVoiceEnabled) 0.12f else 0.04f), CircleShape)
                    .padding(16.dp)
                    .background(Brush.linearGradient(listOf(Color(0xFF43ECD5), Color(0xFF0F988F))), CircleShape)
                    .clickable {
                        if (!state.isActive) {
                            onStart()
                            onToggleVoice(true)
                        } else if (!state.isVoiceEnabled) onToggleVoice(true)
                        else onStop()
                    }
                    .testTag("voice_home_primary"),
                contentAlignment = Alignment.Center
            ) {
                Icon(if (state.isActive && state.isVoiceEnabled) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                    contentDescription = "Iniciar ou parar guia de voz", tint = midnight, modifier = Modifier.size(68.dp))
            }
            Spacer(Modifier.height(13.dp))
            Text(
                when {
                    !state.isActive -> "TOQUE PARA INICIAR"
                    state.isVoiceEnabled -> "NARRAÇÃO ATIVADA"
                    else -> "GPS ATIVO · VOZ PAUSADA"
                },
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp
            )
            Text("Continue ouvindo mesmo ao usar outros aplicativos", color = soft, fontSize = 12.sp)
            if (state.isActive) {
                TextButton(onClick = { onToggleVoice(!state.isVoiceEnabled) }) {
                    Text(if (state.isVoiceEnabled) "Silenciar voz" else "Reativar voz", color = mint)
                }
            }
        }
        Surface(color = panel, shape = RoundedCornerShape(25.dp), border = BorderStroke(1.dp, Color(0xFF294659))) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("VOCÊ ESTÁ AQUI", color = mint, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
                Text(place?.road?.takeIf { it.isNotBlank() } ?: "Aguardando identificação da rua",
                    color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                PlaceField("BAIRRO", place?.neighbourhood ?: "Não identificado")
                PlaceField("ZONA", place?.zone ?: "Não identificada")
                PlaceField("CIRCUNSCRIÇÃO", place?.policeDistrict ?: "Não identificada")
                OutlinedButton(onClick = onRepeat, enabled = state.isVoiceEnabled && state.isActive, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, tint = mint)
                    Spacer(Modifier.width(8.dp))
                    Text("Ouvir minha localização", color = mint)
                }
            }
        }
        Text("SEU MODO DE EXPLORAR", color = soft, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Explorador", "Territorial", "Acessível").forEach { profile ->
                val chosen = activeProfile == profile
                Surface(
                    modifier = Modifier.weight(1f).clickable { activeProfile = profile },
                    shape = RoundedCornerShape(13.dp),
                    color = if (chosen) mint else panel,
                    border = BorderStroke(1.dp, if (chosen) mint else Color(0xFF345066))
                ) {
                    Text(profile, modifier = Modifier.padding(vertical = 13.dp, horizontal = 3.dp),
                        color = if (chosen) midnight else Color.White, fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text("Os modos de narração serão configuráveis. A seleção visual ainda não altera as regras de anúncio.",
            color = soft, fontSize = 11.sp)
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

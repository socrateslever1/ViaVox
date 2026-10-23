package com.example.viavox.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.example.viavox.R
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
    onOpenReminders: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMap: () -> Unit,
    onSelectMode: (String) -> Unit,
    onScan: (ScanDirection) -> Unit,
    onRadiusChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Never present an old street as the current one after GPS is stopped.
    val place = if (state.isActive && state.hasLocationFix) state.place else null
    Column(
        modifier = modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(midnight, Color(0xFF102C40), midnight)))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(19.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.viavox_brand_mark),
                contentDescription = "Logo ViaVox", modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("ViaVox", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text("SEU MUNDO, NARRADO PARA VOCÊ", color = mint, fontSize = 10.sp, maxLines = 2)
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("voice_home_settings")) {
                Icon(Icons.Default.Settings, contentDescription = "Configurações", tint = Color.White)
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("gps_status_card"),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF153449),
            border = BorderStroke(1.dp, Color(0xFF315367))
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(9.dp).background(
                        if (state.isActive) mint else Color(0xFFFFAD70), CircleShape
                    ))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        if (state.isActive) "GPS ATIVO" else "GPS DESLIGADO",
                        color = Color.White, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (state.isActive) state.accuracyMeters?.let { "Precisão ±${it} m" }
                            ?: "Obtendo sinal..." else "Privacidade ativada",
                        color = soft, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(7.dp))
                OutlinedButton(
                    onClick = { if (state.isActive) onStop() else onStart() },
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                        .testTag("tracking_toggle"),
                    shape = RoundedCornerShape(13.dp),
                    border = BorderStroke(1.dp, mint.copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)
                ) {
                    Icon(
                        if (state.isActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null, tint = mint,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (state.isActive) "Encerrar localização" else "Ativar localização",
                        color = mint, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(170.dp)
                    .background(mint.copy(alpha = 0.13f), CircleShape)
                    .padding(14.dp)
                    .background(Brush.linearGradient(listOf(Color(0xFF43ECD5), Color(0xFF0F988F))), CircleShape)
                    .clickable(enabled = state.isActive, onClick = onRepeat)
                    .testTag("voice_home_primary"),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = "Onde estou? Ouvir localização atual", tint = midnight, modifier = Modifier.size(70.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text("ONDE ESTOU?", color = Color.White, fontWeight = FontWeight.Black, fontSize = 21.sp)
            Text(
                if (!state.isActive) "Ative a localização para consultar"
                else if (!state.hasLocationFix) "Obtendo localização..."
                else "Toque para ouvir sua localização",
                color = soft, fontSize = 12.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Avisos automáticos por voz", color = soft, fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                Switch(checked = state.isVoiceEnabled, onCheckedChange = onToggleVoice,
                    modifier = Modifier.testTag("voice_auto_toggle"))
            }
        }
        Surface(color = panel, shape = RoundedCornerShape(25.dp), border = BorderStroke(1.dp, Color(0xFF294659))) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("LOCALIZAÇÃO ATUAL", color = mint, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
                Text(place?.road?.takeIf { it.isNotBlank() } ?:
                    (if (state.isActive) "Identificando sua rua..." else "Localização desativada"),
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
                        PlaceField("VELOCIDADE", if (state.isActive) "${state.speedKmh.toInt().coerceAtLeast(0)} km/h" else "—")
                    }
                }
            }
        }
        Text("EXPLORAÇÃO POR VOZ", color = soft, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        state.surroundingsResult?.takeIf { it.isNotBlank() }?.let { result ->
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("scan_feedback"),
                color = panel, shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF345066))
            ) {
                Column(Modifier.padding(13.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("RESULTADO DA BUSCA", color = mint,
                        fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    if (result.startsWith("Pesquisando") || result.contains("iniciando busca")) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = mint, trackColor = soft.copy(alpha = .22f)
                        )
                    }
                    Text(result, color = Color.White, fontSize = 13.sp,
                        lineHeight = 18.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Shortcut("Ao meu redor", Icons.Default.Explore, Modifier.weight(1f), enabled = state.isActive) {
                onScan(ScanDirection.AROUND)
            }
            Shortcut("À minha frente", Icons.Default.Navigation, Modifier.weight(1f), enabled = state.isActive) {
                onScan(ScanDirection.FRONT)
            }
        }
        Text("ALCANCE DA BUSCA", color = soft, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(100, 200, 350, 500).forEach { radius ->
                val selected = state.config.surroundingsRadiusMeters == radius
                Surface(
                    modifier = Modifier.weight(1f)
                        .testTag("scan_radius_$radius")
                        .clickable { onRadiusChanged(radius) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) mint else panel,
                    border = BorderStroke(1.dp,
                        if (selected) mint else Color(0xFF345066))
                ) {
                    Text(
                        "$radius m",
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 2.dp),
                        color = if (selected) midnight else Color.White,
                        textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                        fontSize = 12.sp, maxLines = 1
                    )
                }
            }
        }
        Text("O alcance selecionado vale para 360° e À minha frente. Toque em um dos botões para consultar.",
            color = soft, fontSize = 11.sp, lineHeight = 16.sp)
        if (!state.isActive) {
            Text("Ative o GPS para explorar os arredores.", color = soft, fontSize = 11.sp)
        }
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
                    Text(if (mode == AssistanceMode.ACCESSIBILITY) "Acessível" else mode.title,
                        modifier = Modifier.padding(vertical = 13.dp, horizontal = 3.dp),
                        color = if (chosen) midnight else Color.White, fontSize = 11.sp,
                        textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                        maxLines = 1)
                }
            }
        }
        val currentMode = AssistanceMode.from(state.config.assistanceMode)
        Text(
            when (currentMode) {
                AssistanceMode.EXPLORER ->
                    "Fala: ZONA → BAIRRO → RUA · somente ao entrar ou mudar."
                AssistanceMode.TERRITORIAL ->
                    "Fala: ZONA → CICOM → CPA (1 vez por área) → BAIRRO → RUA."
                AssistanceMode.ACCESSIBILITY ->
                    "Fala: ZONA → BAIRRO → RUA · depois cruzamentos e orientações do ambiente."
            },
            color = soft, fontSize = 11.sp, lineHeight = 15.sp
        )
        if (currentMode == AssistanceMode.ACCESSIBILITY) {
            Text(if (state.config.tourEnabled)
                "Tour ativo: anuncia referências importantes sem repetir a cada GPS."
                else "Tour desligado: ative nas configurações para ouvir referências no percurso.",
                color = soft, fontSize = 11.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Shortcut("Meus lugares", Icons.Default.Home, Modifier.weight(1f), action = onOpenPlaces)
            Shortcut("Salvar aqui", Icons.Default.BookmarkAdd, Modifier.weight(1f), action = onSavePlace)
            Shortcut("Abrir mapa", Icons.Default.Map, Modifier.weight(1f), action = onOpenMap)
        }
        Surface(
            modifier = Modifier.fillMaxWidth()
                .clickable(onClick = onOpenReminders),
            color = panel, shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF294659))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null,
                    tint = mint, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Lembretes de localização", color = Color.White,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Crie alertas para quando voltar a um lugar",
                        color = soft, fontSize = 11.sp)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = soft)
            }
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
private fun Shortcut(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier,
    enabled: Boolean = true, action: () -> Unit) {
    Surface(modifier = modifier.clickable(enabled = enabled, onClick = action),
        color = if (enabled) panel else panel.copy(alpha = 0.55f),
        shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFF294659))) {
        Column(Modifier.padding(vertical = 16.dp, horizontal = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = mint, modifier = Modifier.size(26.dp))
            Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1)
        }
    }
}

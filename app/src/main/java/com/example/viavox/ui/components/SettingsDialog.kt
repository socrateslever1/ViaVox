package com.example.viavox.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.viavox.R
import com.example.viavox.model.PoiCategory
import com.example.viavox.model.ViaVoxConfig
import com.example.viavox.service.AndroidVoiceOption
import com.example.viavox.service.VoiceDataInstaller
import com.example.viavox.service.AndroidVoiceCatalog

private val accent = Color(0xFF38DFCA)
private val panel = Color(0xFF0B1D33)
private val white = Color(0xFFF2F7FF)
private val muted = Color(0xFFA9BED1)

@Composable
fun SettingsDialog(
    config: ViaVoxConfig,
    onSpeedChanged: (Float) -> Unit,
    onVoiceSelectionChanged: (String, String) -> Unit,
    onTestVoice: () -> Unit,
    onIntervalChanged: (Int) -> Unit,
    onPoiCategoryToggled: (PoiCategory, Boolean) -> Unit,
    onPoiNarrationToggled: (Boolean) -> Unit = {},
    onBackgroundVoiceToggled: (Boolean) -> Unit = {},
    onVehicleTypeChanged: (String) -> Unit = {},
    onSafetyAlertsToggled: (Boolean) -> Unit = {},
    onPoliceCircumscriptionToggled: (Boolean) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var availableVoices by remember { mutableStateOf<List<AndroidVoiceOption>>(emptyList()) }
    var isVoiceLoading by remember { mutableStateOf(true) }
    var voiceCatalog by remember { mutableStateOf<AndroidVoiceCatalog?>(null) }
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    var voicePreviewError by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val catalog = AndroidVoiceCatalog(context) { voices ->
            availableVoices = voices
            isVoiceLoading = false
        }
        voiceCatalog = catalog
        onDispose {
            catalog.close()
            voiceCatalog = null
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = panel,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
            shadowElevation = 20.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 570.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        color = white,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Voz, mapas e pontos de referência",
                        color = muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 3.dp, bottom = 19.dp)
                    )

                    SectionTitle("Como você está se deslocando?")
                    Text(
                        text = "O indicador muda imediatamente para carro, moto ou caminhada.",
                        color = muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp,bottom = 9.dp)
                    )
                    val chosenMode = com.example.viavox.model.VehicleMode.from(config.vehicleType)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            Triple(com.example.viavox.model.VehicleMode.CAR,"🚗 Carro","vehicle_car_btn"),
                            Triple(com.example.viavox.model.VehicleMode.MOTORCYCLE,"🏍 Moto","vehicle_moto_btn"),
                            Triple(com.example.viavox.model.VehicleMode.WALKING,"🚶 A pé","vehicle_walking_btn")
                        ).forEach { (mode,label,tag) ->
                            val selected = chosenMode == mode
                            Button(
                                onClick = { onVehicleTypeChanged(mode.storedValue) },
                                modifier = Modifier.weight(1f).testTag(tag),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 3.dp, vertical = 8.dp
                                ),
                                shape = RoundedCornerShape(11.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) accent else Color.White.copy(alpha = 0.08f)
                                )
                            ) {
                                Text(
                                    label,
                                    color = if (selected) Color(0xFF07192D) else white,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp,maxLines = 1
                                )
                            }
                        }
                    }
                    Text(
                        text = com.example.viavox.model.SupportedRoutingProfile.disclosure(chosenMode),
                        color = muted, fontSize = 11.sp,lineHeight = 15.sp,
                        modifier = Modifier.padding(bottom = 15.dp)
                    )

                    SectionTitle("Voz da navegação")
                    Text(
                        text = "Escolha entre as vozes em português disponíveis no seu celular.",
                        color = muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 5.dp, bottom = 8.dp)
                    )

                    val selectedVoiceName = when {
                        config.nativeVoiceId.isEmpty() -> "Padrão do Android"
                        else -> availableVoices.find { it.id == config.nativeVoiceId }?.label
                            ?: "Voz do Android (indisponível no aparelho)"
                    }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { voiceMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth().testTag("voice_picker_button"),
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = selectedVoiceName,
                                color = white,
                                fontSize = 13.sp,
                                maxLines = 2
                            )
                        }
                        DropdownMenu(
                            expanded = voiceMenuExpanded,
                            onDismissRequest = { voiceMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Padrão do Android · sem API") },
                                onClick = {
                                    onVoiceSelectionChanged("android", "")
                                    voiceMenuExpanded = false
                                }
                            )
                            availableVoices.forEach { voice ->
                                DropdownMenuItem(
                                    text = { Text(voice.label) },
                                    onClick = {
                                        onVoiceSelectionChanged("android", voice.id)
                                        voiceMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    if (isVoiceLoading) {
                        Text(
                            "Buscando vozes instaladas...",
                            color = muted,
                            fontSize = 12.sp
                        )
                    } else if (availableVoices.isEmpty()) {
                        Text(
                            "O Android não tem uma voz portuguesa pronta. Instale os dados para ativar a narração.",
                            color = muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                voicePreviewError = voiceCatalog?.preview(
                                    config.nativeVoiceId, config.speechRate
                                ) != true
                            },
                            enabled = !isVoiceLoading && availableVoices.isNotEmpty(),
                            modifier = Modifier.testTag("preview_voice_button")
                        ) {
                            Text("▶ Ouvir exemplo", color = accent)
                        }
                        TextButton(
                            onClick = { VoiceDataInstaller.open(context) },
                            modifier = Modifier.testTag("install_voice_button")
                        ) {
                            Text("Instalar voz", color = white)
                        }
                    }
                    if (voicePreviewError) {
                        Text(
                            "Prévia indisponível. Instale os dados da voz e tente novamente.",
                            color = Color(0xFFFFCA99), fontSize = 12.sp
                        )
                    }

                    TextButton(onClick = onTestVoice, modifier = Modifier.testTag("service_voice_test")) {
                        Text("▶ Testar voz no ViaVox", color = accent, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "O teste usa a voz selecionada no aplicativo. Verifique também o volume de mídia do celular.",
                        color = muted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = stringResource(R.string.settings_voice_rate, config.speechRate),
                        color = white,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 9.dp)
                    )
                    Slider(
                        value = config.speechRate,
                        onValueChange = onSpeedChanged,
                        valueRange = 0.8f..1.3f,
                        steps = 4,
                        colors = SliderDefaults.colors(
                            thumbColor = accent,
                            activeTrackColor = accent,
                            inactiveTrackColor = Color(0xFF28415C)
                        )
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.09f))
                    Spacer(Modifier.height(14.dp))

                    SectionTitle("Intervalo entre referências")
                    Text(
                        "As mudanças de rua são anunciadas independentemente deste intervalo.",
                        color = muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(vertical = 7.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IntervalChip("12 s", config.minIntervalSeconds == 12, { onIntervalChanged(12) }, Modifier.weight(1f))
                        IntervalChip("20 s", config.minIntervalSeconds == 20, { onIntervalChanged(20) }, Modifier.weight(1f))
                        IntervalChip("35 s", config.minIntervalSeconds == 35, { onIntervalChanged(35) }, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.09f))
                    Spacer(Modifier.height(15.dp))

                    SectionTitle("Tela bloqueada e segundo plano")
                    CategoryToggle(
                        "Continuar narrando em segundo plano",
                        config.backgroundVoiceEnabled
                    ) { onBackgroundVoiceToggled(it) }
                    Text(
                        "Quando habilitado, mantém GPS e voz por notificação persistente " +
                            "mesmo ao sair do aplicativo. Aumenta o consumo de bateria. " +
                            "Navegação e gravação ativas continuam automaticamente.",
                        color = muted, fontSize = 11.sp, lineHeight = 15.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.09f))
                    Spacer(Modifier.height(12.dp))
                    SectionTitle("Pontos de referência")
                    CategoryToggle("Narrar pontos de referência", config.narratePoiEnabled) {
                        onPoiNarrationToggled(it)
                    }
                    Text(
                        "Somente hospitais, escolas, praças e órgãos públicos relevantes, " +
                            "com endereço comprovado na mesma rua, a até 30 metros. " +
                            "Farmácias, lojas, postos e empresas não serão narrados. " +
                            "O mapa continua exibindo outros locais normalmente.",
                        color = muted, fontSize = 11.sp, lineHeight = 15.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    CategoryToggle(stringResource(R.string.category_health), config.enableHealthPoi) {
                        onPoiCategoryToggled(PoiCategory.HEALTH, it)
                    }
                    CategoryToggle(stringResource(R.string.category_education), config.enableEducationPoi) {
                        onPoiCategoryToggled(PoiCategory.EDUCATION, it)
                    }
                    CategoryToggle(stringResource(R.string.category_parks), config.enableParksPoi) {
                        onPoiCategoryToggled(PoiCategory.PARK, it)
                    }
                    CategoryToggle(stringResource(R.string.category_public), config.enablePublicPoi) {
                        onPoiCategoryToggled(PoiCategory.PUBLIC, it)
                    }

                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.09f))
                    Spacer(Modifier.height(15.dp))

                    SectionTitle("CICOM & Segurança Pública")
                    Text(
                        "Referência territorial opcional; confirme dados oficiais quando necessário.",
                        color = muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    CategoryToggle("Narrar área de referência da CICOM", config.enablePoliceCircumscription) {
                        onPoliceCircumscriptionToggled(it)
                    }
                    CategoryToggle("Alertas de radares e pedágios", config.enableSafetyAlerts) {
                        onSafetyAlertsToggled(it)
                    }

                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.09f))
                    Spacer(Modifier.height(15.dp))

                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar", color = muted)
                    }
                    Button(
                        onClick = {
                            onDismiss()
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent,
                            contentColor = panel
                        )
                    ) {
                        Text("Salvar", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, color = accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
}

@Composable
private fun IntervalChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = if (selected) accent.copy(alpha = 0.19f) else Color(0xFF182E47),
        border = BorderStroke(1.dp, if (selected) accent else Color.White.copy(alpha = 0.10f))
    ) {
        Text(
            text = label,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = if (selected) accent else muted,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 11.dp)
        )
    }
}

@Composable
private fun CategoryToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = white,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = accent.copy(alpha = 0.32f),
                checkedThumbColor = accent,
                uncheckedTrackColor = Color(0xFF2B4055),
                uncheckedThumbColor = muted
            )
        )
    }
}

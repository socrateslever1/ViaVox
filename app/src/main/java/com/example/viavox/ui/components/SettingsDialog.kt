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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalUriHandler
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
import com.example.viavox.model.AssistanceMode
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
    onFranciscaBrokerUrlChanged: (String) -> Unit = {},
    onFranciscaAccessTokenChanged: (String) -> Unit = {},
    onVehicleTypeChanged: (String) -> Unit = {},
    onSafetyAlertsToggled: (Boolean) -> Unit = {},
    onPoliceCircumscriptionToggled: (Boolean) -> Unit = {},
    onGoogleMapsApiKeyChanged: (String) -> Unit = {},
    onAssistanceModeChanged: (String) -> Unit = {},
    onRadiusChanged: (Int) -> Unit = {},
    onTourToggled: (Boolean) -> Unit = {},
    onDismiss: () -> Unit
) {
    var voiceUrl by remember(config.franciscaBrokerUrl) { mutableStateOf(config.franciscaBrokerUrl) }
    var accessToken by remember(config.franciscaAccessToken) { mutableStateOf(config.franciscaAccessToken) }
    var googleKey by remember(config.googleMapsApiKey) { mutableStateOf(config.googleMapsApiKey) }
    var advancedGoogleExpanded by remember { mutableStateOf(config.hasGoogleMapsKey()) }
    val invalidUrl = voiceUrl.isNotBlank() && !voiceUrl.trim().startsWith("https://")
    val franciscaReady = config.hasFranciscaSettings()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var availableVoices by remember { mutableStateOf<List<AndroidVoiceOption>>(emptyList()) }
    var isVoiceLoading by remember { mutableStateOf(true) }
    var voiceCatalog by remember { mutableStateOf<AndroidVoiceCatalog?>(null) }
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    var advancedFranciscaExpanded by remember { mutableStateOf(false) }
    var voicePreviewError by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

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

                    SectionTitle("Modo de assistência")
                    Text("Escolha o nível de orientação. O Tour detalhado é opcional e só funciona na Acessibilidade.",
                        color = muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 5.dp, bottom = 9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        AssistanceMode.entries.forEach { mode ->
                            IntervalChip(mode.title,
                                config.assistanceMode == mode.storedValue,
                                { onAssistanceModeChanged(mode.storedValue) },
                                Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    SectionTitle("Alcance da busca em 360°")
                    Text("Define até onde procurar pontos mapeados. Não altera a precisão do GPS.",
                        color = muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 5.dp, bottom = 9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        listOf(100, 200, 350, 500).forEach { radius ->
                            IntervalChip(radius.toString() + " m",
                                config.surroundingsRadiusMeters == radius,
                                { onRadiusChanged(radius) },
                                Modifier.weight(1f))
                        }
                    }
                    if (AssistanceMode.from(config.assistanceMode) == AssistanceMode.ACCESSIBILITY) {
                        Spacer(Modifier.height(13.dp))
                        CategoryToggle("Tour: narrar pontos de referência",
                            config.tourEnabled) { onTourToggled(it) }
                        Text("Durante o deslocamento, narra locais mapeados relevantes com pausas " +
                            "de pelo menos 90 segundos. Não repete o mesmo local em 45 minutos. " +
                            "A direção depende de bússola calibrada ou GPS em movimento.",
                            color = muted, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                    Spacer(Modifier.height(14.dp))

                    SectionTitle("Voz da navegação")
                    Text(
                        text = "Escolha entre as vozes em português disponíveis no seu celular.",
                        color = muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 5.dp, bottom = 8.dp)
                    )

                    val selectedVoiceName = when {
                        config.voiceProvider == "francisca" -> "Francisca · Microsoft (online)"
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
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (franciscaReady) "Francisca · Microsoft (configurada, testar)"
                                        else "Francisca · Microsoft (configuração necessária)"
                                    )
                                },
                                onClick = {
                                    voiceMenuExpanded = false
                                    if (franciscaReady) {
                                        onVoiceSelectionChanged("francisca", config.nativeVoiceId)
                                    } else {
                                        // Do not silently select a paid cloud voice that has no endpoint.
                                        advancedFranciscaExpanded = true
                                    }
                                }
                            )
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
                    if (config.voiceProvider == "android") {
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
                    } else {
                        Text(
                            if (franciscaReady)
                                "Francisca selecionada. Toque em Testar voz no ViaVox para confirmar a conexão. Se falhar, usaremos a voz Android."
                            else
                                "Francisca não configurada. É necessário publicar o serviço Azure/Cloudflare e informar URL e token. A voz Android será utilizada.",
                            color = if (franciscaReady) muted else Color(0xFFFFC99D),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
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

                    TextButton(
                        onClick = { advancedFranciscaExpanded = !advancedFranciscaExpanded },
                        modifier = Modifier.testTag("francisca_settings_toggle")
                    ) {
                        Text(
                            if (advancedFranciscaExpanded) "Ocultar configuração da Francisca"
                            else "Configurar Francisca (opcional)",
                            color = accent,
                            fontSize = 12.sp
                        )
                    }
                    if (advancedFranciscaExpanded) {
                        Text(
                            "Essa voz não vem instalada no Android. A configuração exige Microsoft Azure Speech e um servidor intermediário seguro.",
                            color = Color(0xFFFFCE9B),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Voz Microsoft Francisca", color = white, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Opcional. Informe a URL HTTPS de seu serviço de voz; sem ela, o Android continua falando normalmente.",
                            color = muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                        )
                        OutlinedTextField(
                            value = voiceUrl,
                            onValueChange = { voiceUrl = it },
                            label = { Text("Endereço da API de voz (HTTPS)") },
                            placeholder = { Text("https://seu-dominio/tts") },
                            isError = invalidUrl,
                            supportingText = {
                                if (invalidUrl) Text("O endereço precisa começar com https://")
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = white,
                                unfocusedTextColor = white,
                                focusedBorderColor = accent,
                                unfocusedBorderColor = Color(0xFF38516B),
                                focusedLabelColor = accent,
                                unfocusedLabelColor = muted,
                                cursorColor = accent
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("francisca_broker_input")
                        )
                        OutlinedTextField(
                            value = accessToken,
                            onValueChange = { accessToken = it },
                            label = { Text("Token de acesso do seu servidor") },
                            placeholder = { Text("Token configurado no Cloudflare Worker") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = white,
                                unfocusedTextColor = white,
                                focusedBorderColor = accent,
                                unfocusedBorderColor = Color(0xFF38516B),
                                focusedLabelColor = accent,
                                unfocusedLabelColor = muted,
                                cursorColor = accent
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("francisca_token_input")
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Como configurar a API Microsoft Speech ↗",
                            color = accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 7.dp, bottom = 14.dp)
                                .clickable {
                                    uriHandler.openUri("https://learn.microsoft.com/pt-br/azure/ai-services/speech-service/rest-text-to-speech")
                                }
                        )

                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.09f))
                    Spacer(Modifier.height(14.dp))

                    SectionTitle("Narração em segundo plano")
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
                    TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                        Text(if (advancedExpanded) "Ocultar opções avançadas" else "Mostrar opções avançadas", color = accent)
                    }
                    if (advancedExpanded) {
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

                    SectionTitle("Recursos territoriais e alertas")
                    Text(
                        "Consciência situacional avançada para condutores, motoristas de app e cidadãos.",
                        color = muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    CategoryToggle("Circunscrição policial (DIPs e CICOMs)", config.enablePoliceCircumscription) {
                        onPoliceCircumscriptionToggled(it)
                    }
                    CategoryToggle("Alertas de radares e pedágios", config.enableSafetyAlerts) {
                        onSafetyAlertsToggled(it)
                    }

                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.09f))
                    Spacer(Modifier.height(15.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { advancedGoogleExpanded = !advancedGoogleExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            SectionTitle("Enriquecimento de locais (Google Places)")
                            Text(
                                if (advancedGoogleExpanded) "Toque para recolher" else "Opcional · toque para expandir",
                                color = muted,
                                fontSize = 12.sp
                            )
                        }
                        Text(
                            if (advancedGoogleExpanded) "▲" else "▼",
                            color = accent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    if (advancedGoogleExpanded) {
                        Text(
                            text = "Por padrão, o ViaVox funciona 100% gratuito com OpenStreetMap em Manaus, no Brasil e no mundo. Se desejar, insira uma chave Google Cloud Places para enriquecer nomes de estabelecimentos internos e condomínios.",
                            color = muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                        )
                        OutlinedTextField(
                            value = googleKey,
                            onValueChange = { googleKey = it },
                            label = { Text("Chave da API Google Maps / Places") },
                            placeholder = { Text("AIzaSy...") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = white,
                                unfocusedTextColor = white,
                                focusedBorderColor = accent,
                                unfocusedBorderColor = Color(0xFF38516B),
                                focusedLabelColor = accent,
                                unfocusedLabelColor = muted,
                                cursorColor = accent
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("google_api_key_input")
                        )
                    }
                    }
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
                        enabled = !invalidUrl,
                        onClick = {
                            onFranciscaBrokerUrlChanged(voiceUrl.trim())
                            onFranciscaAccessTokenChanged(accessToken.trim())
                            onGoogleMapsApiKeyChanged(googleKey.trim())
                            if (config.voiceProvider == "francisca" &&
                                (voiceUrl.isBlank() || accessToken.isBlank())
                            ) {
                                onVoiceSelectionChanged("android", config.nativeVoiceId)
                            }
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

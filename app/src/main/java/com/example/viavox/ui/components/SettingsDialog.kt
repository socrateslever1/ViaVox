package com.example.viavox.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.viavox.R
import com.example.viavox.model.PoiCategory
import com.example.viavox.model.ViaVoxConfig

private val accent = Color(0xFF38DFCA)
private val panel = Color(0xFF0B1D33)
private val white = Color(0xFFF2F7FF)
private val muted = Color(0xFFA9BED1)

@Composable
fun SettingsDialog(
    config: ViaVoxConfig,
    onSpeedChanged: (Float) -> Unit,
    onIntervalChanged: (Int) -> Unit,
    onPoiCategoryToggled: (PoiCategory, Boolean) -> Unit,
    onFranciscaBrokerUrlChanged: (String) -> Unit = {},
    onFranciscaAccessTokenChanged: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    var voiceUrl by remember(config.franciscaBrokerUrl) { mutableStateOf(config.franciscaBrokerUrl) }
    var accessToken by remember(config.franciscaAccessToken) { mutableStateOf(config.franciscaAccessToken) }
    val invalidUrl = voiceUrl.isNotBlank() && !voiceUrl.trim().startsWith("https://")
    val uriHandler = LocalUriHandler.current

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

                    SectionTitle("Narração")
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

                    SectionTitle("Pontos de referência")
                    CategoryToggle(stringResource(R.string.category_health), config.enableHealthPoi) {
                        onPoiCategoryToggled(PoiCategory.HEALTH, it)
                    }
                    CategoryToggle(stringResource(R.string.category_education), config.enableEducationPoi) {
                        onPoiCategoryToggled(PoiCategory.EDUCATION, it)
                    }
                    CategoryToggle(stringResource(R.string.category_parks), config.enableParksPoi) {
                        onPoiCategoryToggled(PoiCategory.PARK, it)
                    }
                    CategoryToggle(stringResource(R.string.category_fuel), config.enableFuelPoi) {
                        onPoiCategoryToggled(PoiCategory.FUEL, it)
                    }
                    CategoryToggle(stringResource(R.string.category_public), config.enablePublicPoi) {
                        onPoiCategoryToggled(PoiCategory.PUBLIC, it)
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

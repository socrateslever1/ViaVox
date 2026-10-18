package com.example.viavox.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viavox.R
import com.example.viavox.model.PoiCategory
import com.example.viavox.model.ViaVoxConfig
import com.example.viavox.ui.theme.ViaVoxDarkBg
import com.example.viavox.ui.theme.ViaVoxDarkText
import com.example.viavox.ui.theme.ViaVoxHeader
import com.example.viavox.ui.theme.ViaVoxTeal
import com.example.viavox.ui.theme.ViaVoxTextMuted
import com.example.viavox.ui.theme.ViaVoxTextWhite

@Composable
fun SettingsDialog(
    config: ViaVoxConfig,
    onSpeedChanged: (Float) -> Unit,
    onIntervalChanged: (Int) -> Unit,
    onPoiCategoryToggled: (PoiCategory, Boolean) -> Unit,
    onFranciscaBrokerUrlChanged: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    var brokerUrlText by remember(config.franciscaBrokerUrl) {
        mutableStateOf(config.franciscaBrokerUrl)
    }

    AlertDialog(
        onDismissRequest = {
            onFranciscaBrokerUrlChanged(brokerUrlText)
            onDismiss()
        },
        containerColor = ViaVoxHeader,
        title = {
            Text(
                text = stringResource(R.string.settings_title),
                color = ViaVoxTextWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                // Speech Rate Slider
                Text(
                    text = stringResource(R.string.settings_voice_rate, config.speechRate),
                    color = ViaVoxTeal,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Slider(
                    value = config.speechRate,
                    onValueChange = onSpeedChanged,
                    valueRange = 0.8f..1.3f,
                    steps = 4,
                    colors = SliderDefaults.colors(
                        thumbColor = ViaVoxTeal,
                        activeTrackColor = ViaVoxTeal,
                        inactiveTrackColor = ViaVoxDarkBg
                    ),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                HorizontalDivider(color = Color(0xFF1E3A52), thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                // Optional HTTPS Voice Broker URL (pt-BR-FranciscaNeural)
                Text(
                    text = stringResource(R.string.settings_francisca_label),
                    color = ViaVoxTeal,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                OutlinedTextField(
                    value = brokerUrlText,
                    onValueChange = {
                        brokerUrlText = it
                        onFranciscaBrokerUrlChanged(it)
                    },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.settings_francisca_hint),
                            color = ViaVoxTextMuted.copy(alpha = 0.6f),
                            fontSize = 13.sp
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ViaVoxTextWhite,
                        unfocusedTextColor = ViaVoxTextWhite,
                        focusedBorderColor = ViaVoxTeal,
                        unfocusedBorderColor = Color(0xFF284B6A),
                        cursorColor = ViaVoxTeal
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .testTag("francisca_broker_input")
                )

                HorizontalDivider(color = Color(0xFF1E3A52), thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                // Interval Between Announcements
                Text(
                    text = stringResource(R.string.settings_interval),
                    color = ViaVoxTeal,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                IntervalOption(
                    title = stringResource(R.string.interval_dynamic),
                    selected = config.minIntervalSeconds == 12,
                    onClick = { onIntervalChanged(12) }
                )
                IntervalOption(
                    title = stringResource(R.string.interval_standard),
                    selected = config.minIntervalSeconds == 20,
                    onClick = { onIntervalChanged(20) }
                )
                IntervalOption(
                    title = stringResource(R.string.interval_calm),
                    selected = config.minIntervalSeconds == 35,
                    onClick = { onIntervalChanged(35) }
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF1E3A52), thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                // POI Category Toggles
                Text(
                    text = stringResource(R.string.settings_poi_categories),
                    color = ViaVoxTeal,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                CategoryRow(
                    title = stringResource(R.string.category_health),
                    checked = config.enableHealthPoi,
                    onCheckedChange = { onPoiCategoryToggled(PoiCategory.HEALTH, it) }
                )

                CategoryRow(
                    title = stringResource(R.string.category_education),
                    checked = config.enableEducationPoi,
                    onCheckedChange = { onPoiCategoryToggled(PoiCategory.EDUCATION, it) }
                )

                CategoryRow(
                    title = stringResource(R.string.category_parks),
                    checked = config.enableParksPoi,
                    onCheckedChange = { onPoiCategoryToggled(PoiCategory.PARK, it) }
                )

                CategoryRow(
                    title = stringResource(R.string.category_fuel),
                    checked = config.enableFuelPoi,
                    onCheckedChange = { onPoiCategoryToggled(PoiCategory.FUEL, it) }
                )

                CategoryRow(
                    title = stringResource(R.string.category_public),
                    checked = config.enablePublicPoi,
                    onCheckedChange = { onPoiCategoryToggled(PoiCategory.PUBLIC, it) }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onFranciscaBrokerUrlChanged(brokerUrlText)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = ViaVoxTeal,
                    contentColor = ViaVoxDarkText
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.btn_close),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

@Composable
private fun IntervalOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = ViaVoxTeal,
                unselectedColor = ViaVoxTextMuted
            )
        )
        Text(
            text = title,
            color = if (selected) ViaVoxTextWhite else ViaVoxTextMuted,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
private fun CategoryRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = ViaVoxTextWhite,
            fontSize = 14.sp
        )

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ViaVoxTeal,
                checkedTrackColor = Color(0xFF264057),
                uncheckedThumbColor = ViaVoxTextMuted,
                uncheckedTrackColor = ViaVoxDarkBg
            )
        )
    }
}

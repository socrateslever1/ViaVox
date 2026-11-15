package com.example.viavox.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.viavox.service.BackgroundReadiness

/**
 * Shown only while ViaVox is NOT exempt from battery optimisation. Without the
 * exemption many phones stop the service when the screen turns off, and street,
 * bairro and zone announcements are heard only while the app is open.
 */
@Composable
fun BackgroundReadinessCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var exempt by remember {
        mutableStateOf(BackgroundReadiness.isExemptFromBatteryOptimization(context))
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        // Re-check when the user returns from the system prompt.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exempt = BackgroundReadiness.isExemptFromBatteryOptimization(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (exempt) return

    Surface(
        color = Color(0x33FFB300),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0x99FFB300)),
        modifier = modifier.fillMaxWidth().testTag("background_readiness_card")
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "Para falar ruas e bairros com a tela apagada ou em outro aplicativo, " +
                    "permita o uso do ViaVox sem restrição de bateria.",
                color = Color.White, fontSize = 12.sp, lineHeight = 16.sp
            )
            TextButton(
                onClick = { BackgroundReadiness.requestExemption(context) },
                modifier = Modifier.testTag("background_readiness_button")
            ) {
                Text("Permitir agora", color = Color(0xFFFFD27A), fontSize = 13.sp)
            }
        }
    }
}

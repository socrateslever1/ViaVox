package com.example.viavox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.viavox.model.PoiCategory
import com.example.viavox.model.TrackingState
import com.example.viavox.ui.components.HeaderBar
import com.example.viavox.ui.components.OpenStreetMapComponent
import com.example.viavox.ui.components.SettingsDialog
import com.example.viavox.ui.components.StatusPanel
import com.example.viavox.ui.theme.ViaVoxDarkBg

@Composable
fun ViaVoxMainScreen(
    state: TrackingState,
    isSettingsDialogOpen: Boolean,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onSpeedChanged: (Float) -> Unit,
    onIntervalChanged: (Int) -> Unit,
    onPoiCategoryToggled: (PoiCategory, Boolean) -> Unit,
    onFranciscaBrokerUrlChanged: (String) -> Unit = {},
    onFranciscaAccessTokenChanged: (String) -> Unit = {},
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onToggleVoice: (Boolean) -> Unit,
    onRepeatSpeech: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(ViaVoxDarkBg)
            .testTag("main_screen")
    ) {
        val isWide = maxWidth >= 700.dp

        // Full-bleed GPU vector map occupying the entire screen
        OpenStreetMapComponent(
            latitude = state.latitude,
            longitude = state.longitude,
            bearing = state.bearing,
            hasBearing = state.hasBearing,
            isActive = state.isActive,
            modifier = Modifier.fillMaxSize()
        )

        // Overlay container respecting system status/navigation bars
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            // Top essential controls bar
            HeaderBar(
                onOpenSettings = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 720.dp)
            )

            // Compact bottom street & voice control card (never occupies half the screen)
            StatusPanel(
                state = state,
                onStartTracking = onStartTracking,
                onStopTracking = onStopTracking,
                onToggleVoice = onToggleVoice,
                onRepeatSpeech = onRepeatSpeech,
                modifier = Modifier
                    .align(if (isWide) Alignment.BottomStart else Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .widthIn(max = if (isWide) 420.dp else 560.dp)
                    .fillMaxWidth()
            )
        }

        if (isSettingsDialogOpen) {
            SettingsDialog(
                config = state.config,
                onSpeedChanged = onSpeedChanged,
                onIntervalChanged = onIntervalChanged,
                onPoiCategoryToggled = onPoiCategoryToggled,
                onFranciscaBrokerUrlChanged = onFranciscaBrokerUrlChanged,
                onFranciscaAccessTokenChanged = onFranciscaAccessTokenChanged,
                onDismiss = onCloseSettings
            )
        }
    }
}

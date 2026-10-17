package com.example.viavox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.viavox.model.TrackingState
import com.example.viavox.ui.components.HeaderBar
import com.example.viavox.ui.components.OpenStreetMapComponent
import com.example.viavox.ui.components.StatusPanel
import com.example.viavox.ui.theme.ViaVoxDarkBg

@Composable
fun ViaVoxMainScreen(
    state: TrackingState,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onToggleVoice: (Boolean) -> Unit,
    onRepeatSpeech: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            HeaderBar()
        },
        containerColor = ViaVoxDarkBg,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("main_screen")
        ) {
            val isExpanded = maxWidth >= 840.dp

            if (isExpanded) {
                // Tablet / Large screen layout: side-by-side map and control panel
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        OpenStreetMapComponent(
                            latitude = state.latitude,
                            longitude = state.longitude,
                            isActive = state.isActive
                        )
                    }

                    Box(
                        modifier = Modifier
                            .width(420.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                    ) {
                        StatusPanel(
                            state = state,
                            onStartTracking = onStartTracking,
                            onStopTracking = onStopTracking,
                            onToggleVoice = onToggleVoice,
                            onRepeatSpeech = onRepeatSpeech
                        )
                    }
                }
            } else {
                // Mobile handheld vertical layout: top map (46% height), bottom scrollable panel
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.46f)
                    ) {
                        OpenStreetMapComponent(
                            latitude = state.latitude,
                            longitude = state.longitude,
                            isActive = state.isActive
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.54f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        StatusPanel(
                            state = state,
                            onStartTracking = onStartTracking,
                            onStopTracking = onStopTracking,
                            onToggleVoice = onToggleVoice,
                            onRepeatSpeech = onRepeatSpeech
                        )
                    }
                }
            }
        }
    }
}

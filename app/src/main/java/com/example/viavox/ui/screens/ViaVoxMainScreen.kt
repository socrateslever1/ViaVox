package com.example.viavox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.example.viavox.ui.components.NavigationSearchAndActions
import com.example.viavox.ui.components.NavigationRouteCard
import com.example.viavox.ui.components.NavigationLibraryDialog
import com.example.viavox.viewmodel.NavigationUiState
import com.example.viavox.viewmodel.NavigationViewModel
import com.example.viavox.ui.components.HeaderBar
import com.example.viavox.ui.components.OpenStreetMapComponent
import com.example.viavox.ui.components.SettingsDialog
import com.example.viavox.ui.components.StatusPanel
import com.example.viavox.ui.theme.ViaVoxDarkBg

@Composable
fun ViaVoxMainScreen(
    state: TrackingState,
    navigationUi: NavigationUiState,
    navigation: NavigationViewModel,
    isSettingsDialogOpen: Boolean,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onSpeedChanged: (Float) -> Unit,
    onVoiceSelectionChanged: (String, String) -> Unit,
    onIntervalChanged: (Int) -> Unit,
    onPoiCategoryToggled: (PoiCategory, Boolean) -> Unit,
    onFranciscaBrokerUrlChanged: (String) -> Unit = {},
    onFranciscaAccessTokenChanged: (String) -> Unit = {},
    onVehicleTypeChanged: (String) -> Unit = {},
    onSafetyAlertsToggled: (Boolean) -> Unit = {},
    onPoliceCircumscriptionToggled: (Boolean) -> Unit = {},
    onGoogleMapsApiKeyChanged: (String) -> Unit = {},
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onToggleVoice: (Boolean) -> Unit,
    onRepeatSpeech: () -> Unit,
    onTestVoice: () -> Unit,
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
            hasLocationFix = state.hasLocationFix,
            speedKmh = state.speedKmh,
            latitude = state.latitude,
            longitude = state.longitude,
            bearing = state.bearing,
            hasBearing = state.hasBearing,
            isActive = state.isActive,
            route = navigationUi.route,
            navigating = navigationUi.navigating,
            destination = navigationUi.selected,
            favorites = navigationUi.savedPlaces.map { it.second },
            savedTrack = navigationUi.showingTrip?.points.orEmpty(),
            liveTrack = navigationUi.livePoints,
            isMotorcycle = state.config.isMotorcycle(),
            safetyFeatures = state.safetyFeatures,
            onMapLongPress = { lat, lon -> navigation.selectMapPoint(lat, lon) },
            onReferenceSelected = navigation::select,
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

            NavigationSearchAndActions(
                ui = navigationUi,
                onQueryChanged = navigation::updateQuery,
                onSearch = navigation::search,
                onOpenSearch = { navigation.openPanel("search") },
                onOpenSaved = navigation::showSavedPlaces,
                onOpenRoutes = navigation::showSavedRoutes,
                onOpenHistory = navigation::showHistory,
                onSaveCurrentPosition = navigation::saveCurrentPoint,
                onStartRecording = navigation::beginRecording,
                onStopRecording = navigation::finishRecording,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 77.dp)
                    .widthIn(max = 720.dp)
            )

            if (navigationUi.selected != null || navigationUi.route != null) {
                NavigationRouteCard(
                    ui = navigationUi,
                    onStart = navigation::startNavigation,
                    onStop = navigation::stopNavigation,
                    onSaveRoute = navigation::saveCurrentRoute,
                    modifier = Modifier
                        .align(if (isWide) Alignment.BottomEnd else Alignment.BottomCenter)
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 179.dp)
                        .widthIn(max = if (isWide) 430.dp else 560.dp)
                        .fillMaxWidth()
                )
            }

            if (navigationUi.error != null && navigationUi.panel.isBlank()) {
                androidx.compose.material3.Surface(
                    color = androidx.compose.ui.graphics.Color(0xED863437),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(20.dp)
                        .clickable(onClick = navigation::dismissError)
                ) {
                    androidx.compose.material3.Text(
                        navigationUi.error,
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Compact bottom street & voice control card (never occupies half the screen)
            StatusPanel(
                state = state,
                onStartTracking = onStartTracking,
                onStopTracking = onStopTracking,
                onToggleVoice = onToggleVoice,
                onRepeatSpeech = onRepeatSpeech,
                onTestVoice = onTestVoice,
                modifier = Modifier
                    .align(if (isWide) Alignment.BottomStart else Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .widthIn(max = if (isWide) 420.dp else 560.dp)
                    .fillMaxWidth()
            )
        }

        NavigationLibraryDialog(
            ui = navigationUi,
            onDismiss = navigation::closePanel,
            onQueryChanged = navigation::updateQuery,
            onSearch = navigation::search,
            onSelectDestination = navigation::select,
            onSelectSavedRoute = navigation::openSavedRoute,
            onSelectTrip = navigation::openTrip,
            onDeleteFavorite = { navigation.removeFavorite(it) },
            onDeleteRoute = { navigation.removeRoute(it) },
            onDeleteTrip = { navigation.removeTrip(it) }
        )

        if (isSettingsDialogOpen) {
            SettingsDialog(
                config = state.config,
                onSpeedChanged = onSpeedChanged,
                onVoiceSelectionChanged = onVoiceSelectionChanged,
                onTestVoice = onTestVoice,
                onIntervalChanged = onIntervalChanged,
                onPoiCategoryToggled = onPoiCategoryToggled,
                onFranciscaBrokerUrlChanged = onFranciscaBrokerUrlChanged,
                onFranciscaAccessTokenChanged = onFranciscaAccessTokenChanged,
                onVehicleTypeChanged = onVehicleTypeChanged,
                onSafetyAlertsToggled = onSafetyAlertsToggled,
                onPoliceCircumscriptionToggled = onPoliceCircumscriptionToggled,
                onGoogleMapsApiKeyChanged = onGoogleMapsApiKeyChanged,
                onDismiss = onCloseSettings
            )
        }
    }
}

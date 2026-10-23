package com.example.viavox.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.viavox.ui.components.FloatingSpeedometer
import com.example.viavox.ui.components.resolveRoadSpeedLimit
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import com.example.viavox.model.ExternalMapLinks
import com.example.viavox.data.GeoReminderStore
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.viavox.model.PoiCategory
import com.example.viavox.model.TrackingState
import com.example.viavox.model.ScanDirection
import com.example.viavox.ui.components.NavigationSearchAndActions
import com.example.viavox.ui.components.NavigationRouteCard
import com.example.viavox.ui.components.ActiveNavigationBanner
import com.example.viavox.ui.components.NavigationStreetStrip
import com.example.viavox.ui.components.NavigationLibraryDialog
import com.example.viavox.viewmodel.NavigationUiState
import com.example.viavox.viewmodel.NavigationViewModel
import com.example.viavox.ui.components.OpenStreetMapComponent
import com.example.viavox.ui.components.SettingsDialog
import com.example.viavox.ui.components.StatusPanel
import com.example.viavox.ui.components.SavedPlaceEditorDialog
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
    onScan: (ScanDirection) -> Unit = {},
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
        val compactHeight = maxHeight < 730.dp || maxWidth < 355.dp

        var mapVisible by remember { mutableStateOf(false) }
        var mapChoiceOpen by remember { mutableStateOf(false) }
        var mapLaunchError by remember { mutableStateOf(false) }
        val uriHandler = LocalUriHandler.current
        val context = LocalContext.current
        val reminderStore = remember(context) { GeoReminderStore(context) }
        var reminderDialogOpen by remember { mutableStateOf(false) }
        var reminderDraft by remember { mutableStateOf("") }
        var reminderRadius by remember { mutableStateOf(100) }
        var reminderList by remember { mutableStateOf(reminderStore.all()) }
        if (!mapVisible) {
            VoiceHome(
                state = state,
                onStart = onStartTracking,
                onStop = onStopTracking,
                onToggleVoice = onToggleVoice,
                onRepeat = onRepeatSpeech,
                onSavePlace = navigation::saveCurrentPoint,
                onOpenPlaces = navigation::showSavedPlaces,
                onOpenReminders = {
                    reminderList = reminderStore.all()
                    reminderDialogOpen = true
                },
                onOpenSettings = onOpenSettings,
                onOpenMap = { mapChoiceOpen = true },
                onSelectMode = onAssistanceModeChanged,
                onScan = onScan,
                onRadiusChanged = onRadiusChanged,
                modifier = Modifier.fillMaxSize().safeDrawingPadding()
            )
        } else {
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
            favorites = navigationUi.favoriteDestinations.ifEmpty {
                navigationUi.savedPlaces.map { it.second }
            },
            savedTrack = navigationUi.showingTrip?.points.orEmpty(),
            liveTrack = navigationUi.livePoints,
            vehicleType = state.config.vehicleType,
            showMapControls = !navigationUi.suggestionsVisible,
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
            // One compact search/action strip replaces the two stacked headers.
            // The map keeps the space previously consumed by brand and shortcuts.
            if (!navigationUi.navigating) NavigationSearchAndActions(
                ui = navigationUi,
                onQueryChanged = navigation::updateQuery,
                onSearch = navigation::search,
                onOpenSearch = navigation::showSuggestions,
                onShowSuggestions = navigation::showSuggestions,
                onDismissSuggestions = navigation::hideSuggestions,
                onSelectSuggestion = navigation::select,
                onToggleFavorite = navigation::toggleFavorite,
                onRemoveRecentDestination = navigation::removeRecentDestination,
                onClearRecentDestinations = navigation::clearRecentDestinations,
                isCurrentLocationSaved = state.hasLocationFix && navigationUi.savedPlaces.any { pair ->
                    com.example.viavox.model.NavigationMath.meters(
                        state.latitude,state.longitude,pair.second.latitude,pair.second.longitude
                    ) < 24.0
                },
                onOpenSaved = navigation::showSavedPlaces,
                onOpenRoutes = navigation::showSavedRoutes,
                onOpenHistory = navigation::showHistory,
                onSaveCurrentPosition = navigation::saveCurrentPoint,
                onStartRecording = navigation::beginRecording,
                onStopRecording = navigation::finishRecording,
                onOpenSettings = onOpenSettings,
                compactHeight = compactHeight,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (compactHeight) 5.dp else 9.dp)
                    .widthIn(max = 680.dp)
            )

            val searchOpen = navigationUi.suggestionsVisible
            if (!searchOpen && !navigationUi.navigating &&
                (navigationUi.selected != null || navigationUi.route != null)) {
                NavigationRouteCard(
                    ui = navigationUi,
                    onStart = {
                        if (navigationUi.route == null) navigation.confirmSelectedDestination()
                        else navigation.startNavigation()
                    },
                    onStop = navigation::stopNavigation,
                    onSaveRoute = navigation::saveCurrentRoute,
                    vehicleIsMotorcycle = state.config.isMotorcycle(),
                    modifier = Modifier
                        .align(if (isWide) Alignment.BottomEnd else Alignment.BottomCenter)
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 8.dp)
                        .widthIn(max = if (isWide) 430.dp else 560.dp)
                        .fillMaxWidth()
                )
            }

            AnimatedVisibility(
                visible = navigationUi.navigating,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .widthIn(max = 680.dp),
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 }
            ) {
                ActiveNavigationBanner(
                    ui = navigationUi,
                    onStop = navigation::stopNavigation,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            AnimatedVisibility(
                visible = navigationUi.navigating,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(horizontal = 10.dp,vertical = 7.dp)
                    .widthIn(max = 680.dp),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                NavigationStreetStrip(
                    street = state.place?.road?.takeIf { it.isNotBlank() } ?: "Localizando via...",
                    neighborhood = state.place?.neighbourhood.orEmpty(),
                    accuracyMeters = state.accuracyMeters,
                    voiceEnabled = state.isVoiceEnabled,
                    onToggleVoice = onToggleVoice,
                    onRepeat = onRepeatSpeech,
                    destinationName = navigationUi.selected?.name.orEmpty(),
                    remainingMeters = navigationUi.remainingDistanceMeters.coerceAtLeast(0.0),
                    estimatedMinutes = navigationUi.route?.let { route ->
                        if (route.distanceMeters > 0.0) {
                            kotlin.math.ceil(
                                (route.durationSeconds / 60.0) *
                                    (navigationUi.remainingDistanceMeters
                                        .coerceAtLeast(0.0) / route.distanceMeters)
                            ).toInt().coerceAtLeast(0)
                        } else 0
                    } ?: 0,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            var customSpeedLimit by remember { mutableStateOf<Int?>(null) }
            val autoSpeedLimit = remember(state.place?.road, state.safetyFeatures, state.latitude, state.longitude) {
                resolveRoadSpeedLimit(state.place?.road, state.safetyFeatures, state.latitude, state.longitude)
            }
            val activeSpeedLimit = customSpeedLimit ?: autoSpeedLimit

            AnimatedVisibility(
                visible = navigationUi.navigating,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 96.dp),
                enter = fadeIn() + slideInHorizontally { -it },
                exit = fadeOut() + slideOutHorizontally { -it }
            ) {
                FloatingSpeedometer(
                    speedKmh = state.speedKmh,
                    speedLimitKmh = activeSpeedLimit,
                    onSpeedLimitChanged = { customSpeedLimit = it }
                )
            }

            if (navigationUi.error != null && navigationUi.panel.isBlank() && !searchOpen) {
                androidx.compose.material3.Surface(
                    color = androidx.compose.ui.graphics.Color(0xED863437),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp)
                        .padding(bottom = if (navigationUi.navigating) 100.dp else 155.dp)
                        .clickable(onClick = navigation::dismissError)
                ) {
                    androidx.compose.material3.Text(
                        navigationUi.error,
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Route preview replaces the street card; searching hides it entirely
            // so autocomplete and the keyboard never cover the whole map.
            if (!navigationUi.navigating && !searchOpen && navigationUi.selected == null) {
            StatusPanel(
                state = state,
                onStartTracking = onStartTracking,
                onStopTracking = onStopTracking,
                onToggleVoice = onToggleVoice,
                onRepeatSpeech = onRepeatSpeech,
                onTestVoice = onTestVoice,
                modifier = Modifier
                    .align(if (isWide) Alignment.BottomStart else Alignment.BottomCenter)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .widthIn(max = if (isWide) 420.dp else 560.dp)
                    .fillMaxWidth()
            )
            }
        }
            androidx.compose.material3.Button(
                onClick = { mapVisible = false },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
            ) {
                androidx.compose.material3.Text("Voz")
            }
        }

        if (reminderDialogOpen) {
            val canSaveHere = state.isActive && state.hasLocationFix &&
                (state.accuracyMeters ?: 100) in 1..25
            AlertDialog(
                onDismissRequest = { reminderDialogOpen = false },
                title = { Text("Lembretes de localização") },
                text = {
                    Column(
                        modifier = Modifier.heightIn(max = 440.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Registre algo para lembrar quando sair e voltar a este lugar. " +
                            "O alerta é disparado uma única vez, com GPS preciso.")
                        Text(
                            if (canSaveHere) "Posição atual confirmada: " +
                                (state.place?.road ?: "ponto atual")
                            else "Ative o GPS e aguarde precisão de até 25 metros para salvar aqui."
                        )
                        OutlinedTextField(
                            value = reminderDraft,
                            onValueChange = { reminderDraft = it.take(120) },
                            label = { Text("O que deseja lembrar?") },
                            singleLine = false,
                            minLines = 1,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Raio de chegada:")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(100, 200, 350).forEach { radius ->
                                FilterChip(
                                    selected = reminderRadius == radius,
                                    onClick = { reminderRadius = radius },
                                    label = { Text("${radius} m") }
                                )
                            }
                        }
                        TextButton(
                            enabled = canSaveHere && reminderDraft.isNotBlank(),
                            onClick = {
                                val created = reminderStore.add(reminderDraft,
                                    state.latitude, state.longitude, reminderRadius)
                                if (created != null) {
                                    reminderList = reminderStore.all()
                                    reminderDraft = ""
                                }
                            }
                        ) { Text("Salvar lembrete neste local") }
                        HorizontalDivider()
                        if (reminderList.isEmpty()) Text("Nenhum lembrete cadastrado.")
                        reminderList.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.title)
                                    Text(if (item.completed) "Concluído" else if (item.armed)
                                        "Aguardando chegada" else "Aguardando você sair do local")
                                }
                                IconButton(onClick = {
                                    reminderStore.remove(item.id)
                                    reminderList = reminderStore.all()
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Excluir lembrete")
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { reminderDialogOpen = false }) { Text("Concluir") }
                }
            )
        }

        if (mapChoiceOpen) {
            AlertDialog(
                onDismissRequest = { mapChoiceOpen = false },
                title = { Text("Abrir mapa") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Escolha onde consultar o mapa. Nenhum destino será definido automaticamente.")
                        TextButton(onClick = {
                            mapChoiceOpen = false
                            mapVisible = true
                        }) { Text("Mapa interno do ViaVox") }
                        HorizontalDivider()
                        TextButton(onClick = {
                            mapChoiceOpen = false
                            try {
                                uriHandler.openUri(ExternalMapLinks.googleMaps(
                                    state.latitude.takeIf { state.isActive && state.hasLocationFix },
                                    state.longitude.takeIf { state.isActive && state.hasLocationFix }
                                ))
                            } catch (_: Exception) { mapLaunchError = true }
                        }) { Text("Google Maps") }
                        TextButton(onClick = {
                            mapChoiceOpen = false
                            try {
                                uriHandler.openUri(ExternalMapLinks.waze(
                                    state.latitude.takeIf { state.isActive && state.hasLocationFix },
                                    state.longitude.takeIf { state.isActive && state.hasLocationFix }
                                ))
                            } catch (_: Exception) { mapLaunchError = true }
                        }) { Text("Waze") }
                        if (!state.isActive || !state.hasLocationFix) {
                            Text("Sem GPS ativo: o aplicativo externo abrirá sem marcar sua posição atual.")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { mapChoiceOpen = false }) { Text("Cancelar") }
                }
            )
        }
        if (mapLaunchError) {
            AlertDialog(
                onDismissRequest = { mapLaunchError = false },
                title = { Text("Mapa indisponível") },
                text = { Text("Não foi possível abrir o serviço de mapas neste aparelho.") },
                confirmButton = {
                    TextButton(onClick = { mapLaunchError = false }) { Text("Entendi") }
                }
            )
        }

        SavedPlaceEditorDialog(
            place = navigationUi.pendingSavePlace,
            existingId = navigationUi.pendingSaveId,
            onSave = navigation::confirmSavePlace,
            onRemove = navigation::removeCurrentSavedPlace,
            onDismiss = navigation::dismissSavePlace
        )

        NavigationLibraryDialog(
            ui = navigationUi,
            onDismiss = navigation::closePanel,
            onQueryChanged = navigation::updateQuery,
            onSearch = navigation::search,
            onSelectDestination = navigation::select,
            onSelectSavedRoute = navigation::openSavedRoute,
            onSelectTrip = navigation::openTrip,
            onRenameFavorite = navigation::renameFavorite,
            onDeleteFavorite = { navigation.removeFavorite(it) },
            onDeleteRoute = { navigation.removeRoute(it) },
            onDeleteTrip = { navigation.removeTrip(it) },
            onExportTrip = navigation::exportTrip
        )

        if (isSettingsDialogOpen) {
            SettingsDialog(
                config = state.config,
                onSpeedChanged = onSpeedChanged,
                onVoiceSelectionChanged = onVoiceSelectionChanged,
                onTestVoice = onTestVoice,
                onIntervalChanged = onIntervalChanged,
                onPoiCategoryToggled = onPoiCategoryToggled,
                onPoiNarrationToggled = onPoiNarrationToggled,
                onBackgroundVoiceToggled = onBackgroundVoiceToggled,
                onFranciscaBrokerUrlChanged = onFranciscaBrokerUrlChanged,
                onFranciscaAccessTokenChanged = onFranciscaAccessTokenChanged,
                onVehicleTypeChanged = onVehicleTypeChanged,
                onSafetyAlertsToggled = onSafetyAlertsToggled,
                onPoliceCircumscriptionToggled = onPoliceCircumscriptionToggled,
                onGoogleMapsApiKeyChanged = onGoogleMapsApiKeyChanged,
                onAssistanceModeChanged = onAssistanceModeChanged,
                onRadiusChanged = onRadiusChanged,
                onTourToggled = onTourToggled,
                onDismiss = onCloseSettings
            )
        }
    }
}

package com.example.viavox

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.viavox.ui.screens.ViaVoxMainScreen
import com.example.viavox.ui.theme.ViaVoxTheme
import com.example.viavox.viewmodel.ViaVoxViewModel
import com.example.viavox.viewmodel.NavigationViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ViaVoxTheme {
                val viewModel: ViaVoxViewModel = viewModel()
                val navigation: NavigationViewModel = viewModel()
                val navigationUi by navigation.ui.collectAsState()
                val state by viewModel.state.collectAsState()
                val isSettingsOpen by viewModel.isSettingsDialogOpen.collectAsState()
                val lifecycleOwner = LocalLifecycleOwner.current
                val currentNavigation by rememberUpdatedState(navigationUi)
                val currentTracking by rememberUpdatedState(state)
                DisposableEffect(lifecycleOwner, viewModel) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> viewModel.onAppVisible()
                            Lifecycle.Event.ON_STOP -> {
                                val activeJourney = currentNavigation.navigating ||
                                    currentNavigation.recording ||
                                    currentTracking.config.backgroundVoiceEnabled
                                navigation.onAppHidden()
                                viewModel.onAppHidden(activeJourney)
                            }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                    val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                    val granted = fineGranted || coarseGranted
                    viewModel.onPermissionResult(granted)

                    // Permission only: tracking begins after an explicit user action.
                }

                // Request permission on startup, but never start tracking without user action
                LaunchedEffect(Unit) {
                    val fine = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    val coarse = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    if (fine || coarse) {
                        viewModel.onPermissionResult(true)
                        // Do not auto-start GPS simply because permission is granted.
                    } else {
                        val permissionsToRequest = buildRequiredPermissionsList()
                        permissionLauncher.launch(permissionsToRequest)
                    }
                }

                ViaVoxMainScreen(
                    state = state,
                    navigationUi = navigationUi,
                    navigation = navigation,
                    isSettingsDialogOpen = isSettingsOpen,
                    onOpenSettings = { viewModel.openSettings() },
                    onCloseSettings = { viewModel.closeSettings() },
                    onSpeedChanged = { viewModel.updateSpeechRate(it) },
                    onVoiceSelectionChanged = { provider, voiceId -> viewModel.updateVoiceSelection(provider, voiceId) },
                    onIntervalChanged = { viewModel.updateInterval(it) },
                    onPoiCategoryToggled = { cat, enabled -> viewModel.togglePoiCategory(cat, enabled) },
                    onPoiNarrationToggled = { viewModel.togglePoiNarration(it) },
                    onBackgroundVoiceToggled = { viewModel.toggleBackgroundVoice(it) },
                    onFranciscaBrokerUrlChanged = { url -> viewModel.updateFranciscaBrokerUrl(url) },
                    onFranciscaAccessTokenChanged = { token -> viewModel.updateFranciscaAccessToken(token) },
                    onVehicleTypeChanged = { choice ->
                        if (choice == "walking" && currentNavigation.navigating) {
                            navigation.stopNavigation()
                        }
                        viewModel.updateVehicleType(choice)
                    },
                    onSafetyAlertsToggled = { viewModel.updateSafetyAlerts(it) },
                    onPoliceCircumscriptionToggled = { viewModel.updatePoliceCircumscription(it) },
                    onGoogleMapsApiKeyChanged = { viewModel.updateGoogleMapsApiKey(it) },
                    onAssistanceModeChanged = { viewModel.updateAssistanceMode(it) },
                    onRadiusChanged = { viewModel.updateSurroundingsRadius(it) },
                    onTourToggled = { viewModel.toggleTour(it) },
                    onScan = { viewModel.scanSurroundings(it) },
                    onStartTracking = {
                        val fine = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        val coarse = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED

                        if (fine || coarse) {
                            viewModel.startTracking()
                        } else {
                            permissionLauncher.launch(buildRequiredPermissionsList())
                        }
                    },
                    onStopTracking = {
                        viewModel.stopTracking()
                    },
                    onToggleVoice = { enabled ->
                        viewModel.toggleVoice(enabled)
                    },
                    onTestVoice = { viewModel.testVoice() },
                    onRepeatSpeech = {
                        viewModel.repeatCurrentLocationSpeech()
                    }
                )
            }
        }
    }

    private fun buildRequiredPermissionsList(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        return list.toTypedArray()
    }
}

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.viavox.ui.screens.ViaVoxMainScreen
import com.example.viavox.ui.theme.ViaVoxTheme
import com.example.viavox.viewmodel.ViaVoxViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ViaVoxTheme {
                val viewModel: ViaVoxViewModel = viewModel()
                val state by viewModel.state.collectAsState()
                val isSettingsOpen by viewModel.isSettingsDialogOpen.collectAsState()

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                    val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                    val granted = fineGranted || coarseGranted
                    viewModel.onPermissionResult(granted)

                    if (granted) {
                        viewModel.startTracking()
                    }
                }

                // Automatically request permissions on startup and start tracking when authorized
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
                        if (!state.isActive) {
                            viewModel.startTracking()
                        }
                    } else {
                        val permissionsToRequest = buildRequiredPermissionsList()
                        permissionLauncher.launch(permissionsToRequest)
                    }
                }

                ViaVoxMainScreen(
                    state = state,
                    isSettingsDialogOpen = isSettingsOpen,
                    onOpenSettings = { viewModel.openSettings() },
                    onCloseSettings = { viewModel.closeSettings() },
                    onSpeedChanged = { viewModel.updateSpeechRate(it) },
                    onIntervalChanged = { viewModel.updateInterval(it) },
                    onPoiCategoryToggled = { cat, enabled -> viewModel.togglePoiCategory(cat, enabled) },
                    onFranciscaBrokerUrlChanged = { url -> viewModel.updateFranciscaBrokerUrl(url) },
                    onFranciscaAccessTokenChanged = { token -> viewModel.updateFranciscaAccessToken(token) },
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

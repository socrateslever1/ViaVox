package com.example.viavox.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

private const val INITIAL_ZOOM = 16.5
private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

/**
 * Real vector map powered by MapLibre and OpenFreeMap.
 * No route is drawn: ViaVox identifies and speaks the current street without a destination.
 * Camera following stops after a user gesture and resumes on the next activation.
 */
@Composable
fun OpenStreetMapComponent(
    latitude: Double,
    longitude: Double,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var isStyleReady by remember { mutableStateOf(false) }
    var followUser by remember { mutableStateOf(true) }
    var marker by remember { mutableStateOf<org.maplibre.android.annotations.Marker?>(null) }

    DisposableEffect(mapView) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { readyMap ->
            readyMap.uiSettings.isCompassEnabled = true
            readyMap.uiSettings.isZoomGesturesEnabled = true
            readyMap.uiSettings.isRotateGesturesEnabled = true
            readyMap.uiSettings.isTiltGesturesEnabled = true
            readyMap.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    followUser = false
                }
            }
            readyMap.setStyle(Style.Builder().fromUri(MAP_STYLE)) {
                isStyleReady = true
            }
            map = readyMap
        }
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(map, isStyleReady, latitude, longitude, isActive) {
        val currentMap = map ?: return@LaunchedEffect
        if (!isStyleReady || !isActive) return@LaunchedEffect
        val point = LatLng(latitude, longitude)
        val existing = marker
        if (existing == null) {
            marker = currentMap.addMarker(MarkerOptions().position(point).title("Sua localização"))
        } else {
            existing.position = point
        }
        if (followUser) {
            currentMap.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(point)
                        .zoom(INITIAL_ZOOM)
                        .tilt(45.0)
                        .build()
                ),
                750
            )
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag("map_view")) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (!isStyleReady) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

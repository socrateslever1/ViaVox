package com.example.viavox.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.viavox.ui.theme.ViaVoxHeader
import com.example.viavox.ui.theme.ViaVoxTeal
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private const val INITIAL_ZOOM = 16.8
private const val DARK_MAP_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val FALLBACK_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

/**
 * Real GPU-accelerated vector map powered by MapLibre Native and OpenStreetMap data.
 * Displays a dark cartographic theme, 3D tilted camera perspective, blue location marker
 * with heading indicator, compass, attribution, and automatic position tracking with
 * gesture pause and recenter button.
 */
@Composable
fun OpenStreetMapComponent(
    latitude: Double,
    longitude: Double,
    bearing: Float = 0f,
    hasBearing: Boolean = false,
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
    var marker by remember { mutableStateOf<Marker?>(null) }

    // Resume following user whenever tracking starts
    LaunchedEffect(isActive) {
        if (isActive) {
            followUser = true
        }
    }

    DisposableEffect(mapView) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { readyMap ->
            readyMap.uiSettings.isCompassEnabled = true
            readyMap.uiSettings.isAttributionEnabled = true
            readyMap.uiSettings.isLogoEnabled = false
            readyMap.uiSettings.isZoomGesturesEnabled = true
            readyMap.uiSettings.isRotateGesturesEnabled = true
            readyMap.uiSettings.isTiltGesturesEnabled = true
            readyMap.uiSettings.isScrollGesturesEnabled = true

            readyMap.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    followUser = false
                }
            }

            readyMap.setStyle(Style.Builder().fromUri(DARK_MAP_STYLE)) {
                isStyleReady = true
            }
            mapView.addOnDidFailLoadingMapListener {
                if (!isStyleReady) {
                    readyMap.setStyle(Style.Builder().fromUri(FALLBACK_MAP_STYLE)) {
                        isStyleReady = true
                    }
                }
            }

            // Set initial camera position
            val initialPoint = LatLng(latitude, longitude)
            readyMap.cameraPosition = CameraPosition.Builder()
                .target(initialPoint)
                .zoom(INITIAL_ZOOM)
                .tilt(45.0)
                .build()

            map = readyMap
        }
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(map, isStyleReady, latitude, longitude, bearing, hasBearing, isActive, followUser) {
        val currentMap = map ?: return@LaunchedEffect
        if (!isStyleReady) return@LaunchedEffect

        val point = LatLng(latitude, longitude)
        val markerIcon = IconFactory.getInstance(context).fromBitmap(
            createBlueLocationMarkerBitmap(bearing = bearing, showHeading = hasBearing)
        )

        val existing = marker
        if (existing == null) {
            marker = currentMap.addMarker(
                MarkerOptions()
                    .position(point)
                    .icon(markerIcon)
            )
        } else {
            existing.position = point
            existing.icon = markerIcon
        }

        if (followUser) {
            val cameraPos = CameraPosition.Builder()
                .target(point)
                .zoom(INITIAL_ZOOM)
                .tilt(45.0)
                .bearing(if (hasBearing) bearing.toDouble() else currentMap.cameraPosition.bearing)
                .build()
            currentMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPos), 750)
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag("map_view")) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        if (!isStyleReady) {
            CircularProgressIndicator(
                color = ViaVoxTeal,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Discreet Recenter button when user has manually moved the map
        AnimatedVisibility(
            visible = !followUser,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 88.dp, end = 16.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { followUser = true },
                shape = CircleShape,
                containerColor = ViaVoxHeader.copy(alpha = 0.92f),
                contentColor = ViaVoxTeal,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("recenter_button")
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Recentralizar mapa",
                    tint = Color(0xFF29B6F6)
                )
            }
        }
    }
}

/**
 * Generates a high-DPI blue GPS location puck bitmap with an optional directional heading cone.
 */
private fun createBlueLocationMarkerBitmap(bearing: Float, showHeading: Boolean): Bitmap {
    val sizePx = 96
    val center = sizePx / 2f
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Outer translucent blue halo
    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(65, 33, 150, 243)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, 38f, haloPaint)

    // Directional heading arrow if vehicle/user bearing is available
    if (showHeading) {
        canvas.save()
        canvas.rotate(bearing, center, center)
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(41, 182, 246)
            style = Paint.Style.FILL
        }
        val path = Path().apply {
            moveTo(center, 6f)
            lineTo(center - 13f, center - 12f)
            lineTo(center + 13f, center - 12f)
            close()
        }
        canvas.drawPath(path, arrowPaint)
        canvas.restore()
    }

    // White border ring
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, 21f, borderPaint)

    // Solid vibrant blue inner puck
    val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(2, 136, 209)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, 16f, corePaint)

    return bitmap
}

package com.example.viavox.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.expressions.Expression
import kotlin.math.abs

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
    hasLocationFix: Boolean = false,
    speedKmh: Float = 0f,
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
    var darkStyle by remember { mutableStateOf(true) }
    var headingUp by remember { mutableStateOf(true) }
    var threeDView by remember { mutableStateOf(true) }
    var mapCameraBearing by remember { mutableStateOf(0f) }
    var marker by remember { mutableStateOf<Marker?>(null) }
    val compassHeading = rememberDeviceCompassHeading(isActive, latitude, longitude)

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
            readyMap.uiSettings.isCompassEnabled = false // custom functional compass in the right-hand control rail
            readyMap.uiSettings.isAttributionEnabled = true
            readyMap.uiSettings.isLogoEnabled = false
            readyMap.uiSettings.isZoomGesturesEnabled = true
            readyMap.uiSettings.isRotateGesturesEnabled = true
            readyMap.uiSettings.isTiltGesturesEnabled = true
            readyMap.uiSettings.isScrollGesturesEnabled = true

            readyMap.addOnCameraIdleListener {
                mapCameraBearing = readyMap.cameraPosition.bearing.toFloat()
            }
            readyMap.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    followUser = false
                }
            }

            readyMap.setStyle(Style.Builder().fromUri(DARK_MAP_STYLE)) { style ->
                enhanceRealRoadContrast(style, true)
                addBuildings3DIfAvailable(style)
                isStyleReady = true
            }
            mapView.addOnDidFailLoadingMapListener {
                if (!isStyleReady) {
                    readyMap.setStyle(Style.Builder().fromUri(FALLBACK_MAP_STYLE)) { style ->
                        if (darkStyle) enhanceRealRoadContrast(style, true)
                        addBuildings3DIfAvailable(style)
                        isStyleReady = true
                    }
                }
            }

            // Set initial camera position
            val initialPoint = LatLng(latitude, longitude)
            readyMap.cameraPosition = CameraPosition.Builder()
                .target(initialPoint)
                .zoom(if (hasLocationFix) INITIAL_ZOOM else 11.5)
                .tilt(if (hasLocationFix) 58.0 else 0.0)
                .build()

            map = readyMap
        }
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Update coordinates/camera only on new GPS fixes, not on every compass event.
    LaunchedEffect(
        map, isStyleReady, latitude, longitude, bearing, hasBearing,
        speedKmh, followUser, hasLocationFix, headingUp, threeDView
    ) {
        val currentMap = map ?: return@LaunchedEffect
        if (!isStyleReady || !hasLocationFix) return@LaunchedEffect
        val point = LatLng(latitude, longitude)
        val previousMarker = marker
        if (previousMarker == null) {
            val defaultPuck = IconFactory.getInstance(context).fromBitmap(
                createBlueLocationMarkerBitmap(0f, false)
            )
            marker = currentMap.addMarker(MarkerOptions().position(point).icon(defaultPuck))
        } else {
            previousMarker.position = point
        }

        if (followUser) {
            // Heading-up while actually moving; don't spin the map as the
            // phone is handled while stopped. Preserve user zoom and tilt.
            val cameraNow = currentMap.cameraPosition
            val cameraPos = CameraPosition.Builder()
                .target(point)
                .zoom(if (previousMarker == null) INITIAL_ZOOM else cameraNow.zoom)
                .tilt(if (threeDView) 58.0 else 0.0)
                .bearing(cameraNow.bearing)
                .build()
            currentMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPos), 550)
        }
    }

    // Annotation icons are screen-aligned: compensate for map camera rotation.
    // In heading-up mode the arrow faces screen top, rather than double-rotating.
    LaunchedEffect(
        map, isStyleReady, marker, compassHeading, bearing, hasBearing,
        speedKmh, followUser, hasLocationFix, mapCameraBearing, headingUp
    ) {
        val currentMap = map ?: return@LaunchedEffect
        val currentMarker = marker ?: return@LaunchedEffect
        if (!isStyleReady || !hasLocationFix) return@LaunchedEffect

        // Vehicle course is meaningful only when actually moving.
        // At a stop, the top of the phone is the chosen reference direction.
        val movingCourseReliable = hasBearing && speedKmh >= 7f
        val heading = if (movingCourseReliable) bearing else compassHeading
        if (headingUp && followUser && heading != null) {
            val cameraBearing = currentMap.cameraPosition.bearing.toFloat()
            val delta = abs(((heading - cameraBearing + 540f) % 360f) - 180f)
            if (delta >= 7f) {
                currentMap.animateCamera(CameraUpdateFactory.bearingTo(heading.toDouble()), 450)
            }
        }
        val relative = if (heading == null) 0f else {
            ((heading - mapCameraBearing) % 360f + 360f) % 360f
        }
        currentMarker.icon = IconFactory.getInstance(context).fromBitmap(
            createBlueLocationMarkerBitmap(relative, heading != null)
        )
    }

    Box(modifier = modifier.fillMaxSize().testTag("map_view")) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        if (!isStyleReady) {
            CircularProgressIndicator(
                color = ViaVoxTeal,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Floating map controls, all wired to MapLibre camera or style actions.
        // Keep the map interactive and visible beneath the controls.
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MapControlButton(
                icon = Icons.Default.Layers,
                label = if (darkStyle) "Mapa claro" else "Mapa escuro",
                testTag = "map_style_button"
            ) {
                darkStyle = !darkStyle
                val current = map
                if (current != null) {
                    isStyleReady = false
                    current.setStyle(
                        Style.Builder().fromUri(if (darkStyle) DARK_MAP_STYLE else FALLBACK_MAP_STYLE)
                    ) { style ->
                        addBuildings3DIfAvailable(style)
                        isStyleReady = true
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MapControlButton(
                    icon = Icons.Default.Explore,
                    label = if (headingUp) "Fixar norte na tela" else "Seguir direção à frente",
                    testTag = "compass_button"
                ) {
                    headingUp = !headingUp
                    followUser = true
                    if (!headingUp) {
                        map?.animateCamera(CameraUpdateFactory.bearingTo(0.0), 450)
                    }
                }
                Text(
                    if (headingUp) "FRENTE" else "NORTE",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .background(Color(0xE0102642), RoundedCornerShape(7.dp))
                        .padding(horizontal = 5.dp, vertical = 3.dp)
                )
            }

            MapControlButton(
                icon = Icons.Default.Terrain,
                label = if (threeDView) "Vista de cima (2D)" else "Vista inclinada (3D)",
                testTag = "map_3d_button"
            ) {
                threeDView = !threeDView
                map?.animateCamera(CameraUpdateFactory.tiltTo(if (threeDView) 58.0 else 0.0), 550)
            }

            MapControlButton(
                icon = Icons.Default.MyLocation,
                label = "Recentralizar minha posição",
                testTag = "recenter_button"
            ) { followUser = true }

            Surface(
                shape = RoundedCornerShape(23.dp),
                color = Color(0xF0081A30),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
                shadowElevation = 8.dp
            ) {
                Column {
                    IconButton(
                        onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn(), 260) },
                        modifier = Modifier.size(44.dp).testTag("zoom_in_button")
                    ) {
                        Icon(Icons.Default.Add, "Ampliar mapa", tint = Color.White)
                    }
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 10.dp)
                            .size(width = 24.dp, height = 1.dp)
                            .background(Color.White.copy(alpha = 0.18f))
                    )
                    IconButton(
                        onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut(), 260) },
                        modifier = Modifier.size(44.dp).testTag("zoom_out_button")
                    ) {
                        Icon(Icons.Default.Remove, "Reduzir mapa", tint = Color.White)
                    }
                }
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

@Composable
private fun MapControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = Color(0xF0081A30),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
        shadowElevation = 8.dp
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(44.dp).testTag(testTag)
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(23.dp))
        }
    }
}

/**
 * Enhance actual vector road lines in the dark style rather than drawing fake
 * roads or inventing 3D buildings. Other geometry retains provider styling.
 */
private fun enhanceRealRoadContrast(style: Style, dark: Boolean) {
    if (!dark) return
    try {
        style.layers.filterIsInstance<LineLayer>().forEach { layer ->
            val id = layer.id.lowercase()
            if ((id.contains("road") || id.contains("street")) &&
                !id.contains("label") && !id.contains("outline") &&
                !id.contains("casing")
            ) {
                layer.setProperties(
                    PropertyFactory.lineColor(android.graphics.Color.rgb(78, 105, 139))
                )
            }
        }
    } catch (_: Exception) {
        // A provider changing layer names must never stop the map loading.
    }
}

/**
 * Use real OpenStreetMap building heights where available in OpenFreeMap tiles.
 * Never synthesizes geometry: no building data simply means no extrusion.
 */
private fun addBuildings3DIfAvailable(style: Style) {
    val sourceId = listOf("openmaptiles", "openfreemap").firstOrNull { style.getSource(it) != null }
        ?: return
    if (style.getLayer("viavox-3d-buildings") != null) return
    try {
        val layer = FillExtrusionLayer("viavox-3d-buildings", sourceId).apply {
            sourceLayer = "building"
            minZoom = 15f
            setFilter(Expression.all(
                Expression.has("render_height"),
                Expression.has("render_min_height")
            ))
            setProperties(
                PropertyFactory.fillExtrusionColor(android.graphics.Color.rgb(73, 94, 119)),
                PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
                PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
                PropertyFactory.fillExtrusionOpacity(0.83f)
            )
        }
        style.addLayer(layer)
    } catch (_: Exception) {
        // Rendering the road map is more important than an optional 3D effect.
    }
}

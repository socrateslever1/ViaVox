package com.example.viavox.ui.components

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.viavox.data.ArcGisManausPlacesService
import com.example.viavox.data.GisBounds
import com.example.viavox.data.GisReference
import com.example.viavox.data.ManausArcGisLayer
import com.example.viavox.model.NavigationCameraMath
import com.example.viavox.model.MapPlace
import com.example.viavox.model.NavigationRoute
import com.example.viavox.model.TripPoint
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
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.expressions.Expression
import kotlin.math.abs
import kotlin.math.max

private const val INITIAL_ZOOM = 17.2
private const val DARK_MAP_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val BRIGHT_MAP_STYLE = "https://tiles.openfreemap.org/styles/bright"
private const val FALLBACK_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

private fun mapStyleUrl(style: String): String = when (style) {
    "dark" -> DARK_MAP_STYLE
    "liberty" -> FALLBACK_MAP_STYLE
    else -> BRIGHT_MAP_STYLE
}

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
    route: NavigationRoute? = null,
    navigating: Boolean = false,
    destination: MapPlace? = null,
    favorites: List<MapPlace> = emptyList(),
    savedTrack: List<TripPoint> = emptyList(),
    liveTrack: List<TripPoint> = emptyList(),
    isMotorcycle: Boolean = false,
    safetyFeatures: List<com.example.viavox.service.SafetyAlertEngine.SafetyFeature> = emptyList(),
    onMapLongPress: (Double, Double) -> Unit = { _, _ -> },
    onReferenceSelected: (MapPlace) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val longPressCallback by rememberUpdatedState(onMapLongPress)
    val referenceCallback by rememberUpdatedState(onReferenceSelected)
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var isStyleReady by remember { mutableStateOf(false) }
    var followUser by remember { mutableStateOf(true) }
    val prefs = remember(context) { context.getSharedPreferences("viavox_prefs", android.content.Context.MODE_PRIVATE) }
    var selectedMapStyle by remember { mutableStateOf(prefs.getString("map_style", "bright") ?: "bright") }
    var styleMenuExpanded by remember { mutableStateOf(false) }
    var showArcGisReferences by remember {
        mutableStateOf(prefs.getBoolean("map_arcgis_references",true))
    }
    val arcGisService = remember { ArcGisManausPlacesService() }
    var arcGisViewport by remember { mutableStateOf<GisBounds?>(null) }
    var arcGisZoom by remember { mutableStateOf(0.0) }
    var arcGisReferences by remember { mutableStateOf<List<GisReference>>(emptyList()) }
    var headingUp by remember { mutableStateOf(true) }
    var threeDView by remember { mutableStateOf(true) }
    var mapCameraBearing by remember { mutableStateOf(0f) }
    var marker by remember { mutableStateOf<Marker?>(null) }
    val puckAnimator = remember { arrayOfNulls<ValueAnimator>(1) }
    var lastNavigationBearing by remember { mutableStateOf<Double?>(null) }
    var wasNavigating by remember { mutableStateOf(false) }
    var destMarker by remember { mutableStateOf<Marker?>(null) }
    val favoriteMarkers = remember { mutableListOf<Marker>() }
    val safetyMarkers = remember { mutableListOf<Marker>() }
    val compassHeading = rememberDeviceCompassHeading(isActive, latitude, longitude)

    // Resume following user whenever tracking starts
    LaunchedEffect(isActive, navigating) {
        if (isActive && navigating) {
            followUser = true
        } else if (isActive && !followUser && route == null) {
            followUser = true
        }
    }

    DisposableEffect(mapView, lifecycleOwner) {
        mapView.onCreate(null)
        val lifecycle = lifecycleOwner.lifecycle
        var mapStarted = false
        var mapResumed = false
        fun startIfNeeded() { if (!mapStarted) { mapView.onStart(); mapStarted = true } }
        fun resumeIfNeeded() { if (!mapResumed) { startIfNeeded(); mapView.onResume(); mapResumed = true } }
        fun pauseIfNeeded() { if (mapResumed) { mapView.onPause(); mapResumed = false } }
        fun stopIfNeeded() { if (mapStarted) { pauseIfNeeded(); mapView.onStop(); mapStarted = false } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startIfNeeded()
                Lifecycle.Event.ON_RESUME -> resumeIfNeeded()
                Lifecycle.Event.ON_PAUSE -> pauseIfNeeded()
                Lifecycle.Event.ON_STOP -> stopIfNeeded()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startIfNeeded()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resumeIfNeeded()
        mapView.getMapAsync { readyMap ->
            readyMap.uiSettings.isCompassEnabled = false // custom functional compass in the right-hand control rail
            readyMap.uiSettings.isAttributionEnabled = true
            readyMap.uiSettings.isLogoEnabled = false
            readyMap.uiSettings.isZoomGesturesEnabled = true
            readyMap.uiSettings.isRotateGesturesEnabled = true
            readyMap.uiSettings.isTiltGesturesEnabled = true
            readyMap.uiSettings.isScrollGesturesEnabled = true
            readyMap.addOnMapLongClickListener { location ->
                longPressCallback(location.latitude, location.longitude)
                true
            }

            readyMap.addOnCameraIdleListener {
                mapCameraBearing = readyMap.cameraPosition.bearing.toFloat()
                val zoom = readyMap.cameraPosition.zoom
                if (zoom < ManausArcGisLayer.MIN_ZOOM) {
                    arcGisZoom = zoom
                    arcGisViewport = null
                } else {
                    val b = readyMap.projection.visibleRegion.latLngBounds
                    val viewport = GisBounds(
                        b.longitudeWest,b.latitudeSouth,b.longitudeEast,b.latitudeNorth
                    )
                    val previous = arcGisViewport
                    if (previous == null || kotlin.math.abs(previous.midLatitude() - viewport.midLatitude()) > 0.0009 ||
                        kotlin.math.abs(previous.midLongitude() - viewport.midLongitude()) > 0.0009 ||
                        kotlin.math.abs(arcGisZoom - zoom) > 0.7
                    ) {
                        arcGisViewport = viewport
                        arcGisZoom = zoom
                    }
                }
            }
            readyMap.addOnMapClickListener { coordinate ->
                val features = readyMap.queryRenderedFeatures(
                    readyMap.projection.toScreenLocation(coordinate), "viavox-arcgis-points",
                    "viavox-arcgis-labels"
                )
                val hit = features.firstOrNull()
                if (hit == null) {
                    longPressCallback(coordinate.latitude, coordinate.longitude)
                    true
                }
                else {
                    val name = hit.getStringProperty("name") ?: ""
                    val label = hit.getStringProperty("label") ?: ""
                    val lat = hit.getNumberProperty("lat")?.toDouble()
                    val lon = hit.getNumberProperty("lon")?.toDouble()
                    if (lat == null || lon == null || name.isBlank()) false
                    else {
                        referenceCallback(MapPlace(
                            label.ifBlank { name },lat,lon,
                            (hit.getStringProperty("address") ?: "") +
                                " · Manaus · ArcGIS"
                        ))
                        true
                    }
                }
            }
            readyMap.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    followUser = false
                }
            }

            readyMap.setStyle(Style.Builder().fromUri(mapStyleUrl(selectedMapStyle))) { style ->
                enhanceRealRoadContrast(style, selectedMapStyle == "dark")
                if (threeDView) addBuildings3DIfAvailable(style)
                ensureNavigationLayers(style)
                ensureArcGisReferenceLayers(style)
                isStyleReady = true
            }
            mapView.addOnDidFailLoadingMapListener {
                if (!isStyleReady) {
                    readyMap.setStyle(Style.Builder().fromUri(FALLBACK_MAP_STYLE)) { style ->
                        enhanceRealRoadContrast(style, false)
                        if (threeDView) addBuildings3DIfAvailable(style)
                        ensureNavigationLayers(style)
                        ensureArcGisReferenceLayers(style)
                        isStyleReady = true
                    }
                }
            }

            // Set initial camera position
            val initialPoint = LatLng(latitude, longitude)
            readyMap.cameraPosition = CameraPosition.Builder()
                .target(initialPoint)
                .zoom(if (hasLocationFix) INITIAL_ZOOM else 11.5)
                .tilt(if (hasLocationFix) 48.0 else 0.0)
                .build()

            map = readyMap
        }
        onDispose {
            lifecycle.removeObserver(observer)
            stopIfNeeded()
            puckAnimator[0]?.cancel()
            mapView.onDestroy()
        }
    }

    // One camera animator owns location, bearing, pitch and zoom together.
    // A second bearingTo animation formerly cancelled every position animation.
    LaunchedEffect(
        map, isStyleReady, latitude, longitude, bearing, hasBearing,
        speedKmh, followUser, hasLocationFix, headingUp, threeDView,
        navigating, isMotorcycle
    ) {
        val currentMap = map ?: return@LaunchedEffect
        if (!isStyleReady || !hasLocationFix) return@LaunchedEffect
        val effective = if (navigating && route != null && route.coordinates.isNotEmpty()) {
            com.example.viavox.model.NavigationMath.snapToPolyline(
                latitude,longitude,route.coordinates
            )
        } else latitude to longitude
        val point = LatLng(effective.first,effective.second)
        val existing = marker
        if (existing == null) {
            val initialIcon = IconFactory.getInstance(context).fromBitmap(
                createBlueLocationMarkerBitmap(0f,false,isMotorcycle)
            )
            marker = currentMap.addMarker(MarkerOptions().position(point).icon(initialIcon))
        } else {
            val oldPoint = existing.position
            val jump = com.example.viavox.model.NavigationMath.meters(
                oldPoint.latitude,oldPoint.longitude,point.latitude,point.longitude
            )
            puckAnimator[0]?.cancel()
            if (jump < 95.0 && jump > 0.3) {
                val animator = ValueAnimator.ofFloat(0f,1f).apply {
                    duration = 850L
                    interpolator = LinearInterpolator()
                    addUpdateListener { animation ->
                        val t = (animation.animatedValue as Float).toDouble()
                        existing.position = LatLng(
                            oldPoint.latitude + (point.latitude - oldPoint.latitude)*t,
                            oldPoint.longitude + (point.longitude - oldPoint.longitude)*t
                        )
                    }
                }
                puckAnimator[0] = animator
                animator.start()
            } else existing.position = point
        }

        val reliableCourse = hasBearing && speedKmh >= 7f
        val sensorHeading = compassHeading
        val targetBearing = if (reliableCourse) bearing.toDouble()
            else lastNavigationBearing ?: sensorHeading?.toDouble()
        if (reliableCourse) lastNavigationBearing = bearing.toDouble()

        if (followUser) {
            val current = currentMap.cameraPosition
            val cameraBearing = when {
                !headingUp -> 0.0
                navigating && targetBearing != null ->
                    NavigationCameraMath.smoothBearing(current.bearing,targetBearing)
                targetBearing != null ->
                    NavigationCameraMath.smoothBearing(current.bearing,targetBearing,0.4)
                else -> current.bearing
            }
            val target = if (navigating && headingUp && targetBearing != null)
                NavigationCameraMath.cameraTarget(
                    point.latitude,point.longitude,cameraBearing,
                    NavigationCameraMath.lookAheadMeters(speedKmh)
                )
            else point.latitude to point.longitude
            val cameraZoom = when {
                navigating && !wasNavigating ->
                    NavigationCameraMath.followZoom(speedKmh)
                navigating && current.zoom < 16.6 ->
                    NavigationCameraMath.followZoom(speedKmh)
                navigating && current.zoom > 18.4 -> 18.4
                existing == null -> INITIAL_ZOOM
                else -> current.zoom // respect a deliberate gesture/recenter zoom
            }
            currentMap.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(target.first,target.second))
                        .zoom(cameraZoom)
                        .bearing(cameraBearing)
                        .tilt(if (threeDView) NavigationCameraMath.GUIDE_TILT else 0.0)
                        .build()
                ), if (navigating) 1000 else 740
            )
        }
        wasNavigating = navigating
    }

    // Update the screen-aligned vehicle symbol independently of map camera.
    // Crucially, never animate the camera in this effect.
    LaunchedEffect(
        map, isStyleReady, marker, compassHeading, bearing, hasBearing,
        speedKmh, mapCameraBearing, isMotorcycle, headingUp, navigating
    ) {
        val currentMarker = marker ?: return@LaunchedEffect
        if (!isStyleReady || !hasLocationFix) return@LaunchedEffect
        val reliable = hasBearing && speedKmh >= 7f
        val direction = if (reliable) bearing.toDouble()
            else lastNavigationBearing ?: compassHeading?.toDouble()
        val displayAngle = if (direction == null) 0f
            else ((direction.toFloat() - mapCameraBearing) % 360f + 360f) % 360f
        currentMarker.icon = IconFactory.getInstance(context).fromBitmap(
            createBlueLocationMarkerBitmap(displayAngle,direction != null,isMotorcycle)
        )
    }

    // Load references only for the visible zoomed-in view.  This is independent
    // of tracking fixes and will also refresh when a user pans the map.
    LaunchedEffect(map, isStyleReady, arcGisViewport, arcGisZoom, showArcGisReferences) {
        if (!isStyleReady || map == null || !showArcGisReferences ||
            arcGisZoom < ManausArcGisLayer.MIN_ZOOM || arcGisViewport == null
        ) {
            arcGisReferences = emptyList()
        } else {
            delay(450)
            arcGisReferences = arcGisService.loadVisible(
                arcGisViewport!!,arcGisZoom
            )
        }
    }

    LaunchedEffect(map, isStyleReady, arcGisReferences, showArcGisReferences) {
        val currentMap = map ?: return@LaunchedEffect
        if (!isStyleReady) return@LaunchedEffect
        val style = currentMap.style ?: return@LaunchedEffect
        ensureArcGisReferenceLayers(style)
        updateArcGisReferenceLayers(
            style,
            if (showArcGisReferences) arcGisReferences else emptyList()
        )
    }

    LaunchedEffect(map, isStyleReady, route, destination, favorites, savedTrack, liveTrack) {
        val mapRef = map ?: return@LaunchedEffect
        if (!isStyleReady) return@LaunchedEffect
        val style = mapRef.style ?: return@LaunchedEffect
        ensureNavigationLayers(style)
        ensureCircumscriptionLayers(context, style)
        updateLine(style, "viavox-route", route?.coordinates.orEmpty())
        val recordedPoints = if (liveTrack.isNotEmpty()) liveTrack else savedTrack
        updateLine(style, "viavox-track", recordedPoints.map { it.latitude to it.longitude })
        destMarker?.let { mapRef.removeMarker(it) }
        destMarker = null
        favoriteMarkers.forEach { mapRef.removeMarker(it) }
        favoriteMarkers.clear()
        favorites.take(80).forEach { favorite ->
            favoriteMarkers.add(mapRef.addMarker(
                MarkerOptions().position(LatLng(favorite.latitude,favorite.longitude))
                    .title(favorite.name)
            ))
        }
        if (destination != null) {
            destMarker = mapRef.addMarker(
                MarkerOptions().position(LatLng(destination.latitude,destination.longitude))
                    .title(destination.name)
            )
        }
    }

    LaunchedEffect(map, isStyleReady, safetyFeatures) {
        val mapRef = map ?: return@LaunchedEffect
        if (!isStyleReady) return@LaunchedEffect
        safetyMarkers.forEach { mapRef.removeMarker(it) }
        safetyMarkers.clear()

        safetyFeatures.forEach { feature ->
            val icon = IconFactory.getInstance(context).fromBitmap(
                createSafetyMarkerBitmap(feature.type, feature.speedLimitKmh)
            )
            val title = when (feature.type) {
                com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type.SPEED_CAMERA ->
                    feature.speedLimitKmh?.let { "Radar $it km/h" } ?: "Radar de velocidade"
                com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type.TOLL_BOOTH -> "Pedágio"
            }
            safetyMarkers.add(
                mapRef.addMarker(
                    MarkerOptions()
                        .position(LatLng(feature.latitude, feature.longitude))
                        .title(title)
                        .icon(icon)
                )
            )
        }
    }

    // Route overview: fit all route vertices and destination without hard-coded
    // 13.6 zoom. Starting navigation restores a close tracking camera.
    LaunchedEffect(map, route, navigating) {
        val mapRef = map ?: return@LaunchedEffect
        if (route != null && route.coordinates.isNotEmpty() && !navigating) {
            followUser = false
            val boundsBuilder = org.maplibre.android.geometry.LatLngBounds.Builder()
            route.coordinates.forEach { (lat,lon) ->
                boundsBuilder.include(LatLng(lat,lon))
            }
            val bounds = boundsBuilder.build()
            mapRef.animateCamera(
                CameraUpdateFactory.newLatLngBounds(bounds, 95), 720
            )
        } else if (navigating) {
            followUser = true
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag("map_view")) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        if (!navigating && showArcGisReferences && arcGisReferences.isNotEmpty()) {
            Surface(
                color = Color(0xE9082336),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = 12.dp,bottom = 185.dp)
            ) {
                Text(
                    "${arcGisReferences.size} referências · GeoSegurança/ArcGIS",
                    color = Color.White,fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 9.dp,vertical = 5.dp)
                )
            }
        }

        if (!isStyleReady) {
            CircularProgressIndicator(
                color = ViaVoxTeal,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Floating map controls, all wired to MapLibre camera or style actions.
        // Keep the map interactive and visible beneath the controls.
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, bottom = if (navigating) 170.dp else 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!navigating) Box {
                MapControlButton(
                    icon = Icons.Default.Layers,
                    label = "Selecionar mapa: ruas, detalhado ou noturno",
                    testTag = "map_style_button"
                ) { styleMenuExpanded = true }

                DropdownMenu(
                    expanded = styleMenuExpanded,
                    onDismissRequest = { styleMenuExpanded = false }
                ) {
                    listOf(
                        "bright" to "Ruas · colorido",
                        "liberty" to "Detalhado · bairros e pontos",
                        "dark" to "Noturno · escuro"
                    ).forEach { (key, title) ->
                        DropdownMenuItem(
                            text = { Text(if (selectedMapStyle == key) "✓ $title" else title) },
                            onClick = {
                                styleMenuExpanded = false
                                if (selectedMapStyle == key) return@DropdownMenuItem
                                selectedMapStyle = key
                                prefs.edit().putString("map_style", key).apply()
                                val current = map ?: return@DropdownMenuItem
                                marker?.let { current.removeMarker(it) }
                                marker = null
                                isStyleReady = false
                                current.setStyle(Style.Builder().fromUri(mapStyleUrl(key))) { style ->
                                    if (key == "dark") enhanceRealRoadContrast(style, true)
                                    if (threeDView) addBuildings3DIfAvailable(style)
                                    ensureNavigationLayers(style)
                                    ensureArcGisReferenceLayers(style)
                                    isStyleReady = true
                                }
                            }
                        )
                    }
                    androidx.compose.material3.HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (showArcGisReferences) "✓ Referências de Manaus" else "Referências de Manaus"
                            )
                        },
                        onClick = {
                            showArcGisReferences = !showArcGisReferences
                            prefs.edit().putBoolean("map_arcgis_references",showArcGisReferences).apply()
                            styleMenuExpanded = false
                            if (showArcGisReferences) {
                                val current = map
                                if (current != null) {
                                    val b = current.projection.visibleRegion.latLngBounds
                                    arcGisZoom = current.cameraPosition.zoom
                                    arcGisViewport = GisBounds(
                                        b.longitudeWest,b.latitudeSouth,b.longitudeEast,b.latitudeNorth
                                    )
                                }
                            }
                        },
                        modifier = Modifier.testTag("arcgis_references_toggle")
                    )
                    Text(
                        "GeoSegurança · ArcGIS Online",
                        color = Color.Gray,fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 14.dp,vertical = 4.dp)
                    )
                }
            }

            if (!navigating) Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

            if (!navigating) MapControlButton(
                icon = Icons.Default.Terrain,
                label = if (threeDView) "Vista de cima (2D)" else "Vista inclinada (3D)",
                testTag = "map_3d_button"
            ) {
                threeDView = !threeDView
                map?.animateCamera(CameraUpdateFactory.tiltTo(if (threeDView) 48.0 else 0.0), 550)
            }

            MapControlButton(
                icon = Icons.Default.MyLocation,
                label = "Recentralizar minha posição",
                testTag = "recenter_button"
            ) {
                followUser = true
                val current = map
                if (navigating && current != null) {
                    current.animateCamera(CameraUpdateFactory.zoomTo(
                        NavigationCameraMath.followZoom(speedKmh)
                    ), 350)
                }
            }

            if (!navigating) Surface(
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

/** Native MapLibre layers are significantly cheaper than 200 Annotation views. */
private fun ensureArcGisReferenceLayers(style: Style) {
    val id = "viavox-arcgis-references"
    if (style.getSource(id) == null) {
        style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getLayer("viavox-arcgis-points") == null) {
        style.addLayer(CircleLayer("viavox-arcgis-points",id).withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor(android.graphics.Color.rgb(2, 166, 156)),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(1.5f)
        ).apply { minZoom = 15f })
    }
    if (style.getLayer("viavox-arcgis-labels") == null) {
        style.addLayer(SymbolLayer("viavox-arcgis-labels",id).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor(android.graphics.Color.rgb(22, 45, 68)),
            PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
            PropertyFactory.textHaloWidth(1.5f)
        ).apply { minZoom = 15f })
    }
}

private fun updateArcGisReferenceLayers(style: Style, places: List<GisReference>) {
    val source = style.getSource("viavox-arcgis-references") as? GeoJsonSource ?: return
    val features = places.map { place ->
        Feature.fromGeometry(Point.fromLngLat(place.longitude,place.latitude)).apply {
            addStringProperty("name",place.name)
            addStringProperty("label",place.shortLabel)
            addStringProperty("address",
                listOf(place.address,place.neighborhood).filter(String::isNotBlank)
                    .joinToString(" · "))
            addNumberProperty("lat",place.latitude)
            addNumberProperty("lon",place.longitude)
        }
    }
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}

/** Route and recorded trip use separate GPU line layers on top of real map tiles. */
private fun ensureNavigationLayers(style: Style) {
    val specs = listOf(
        Triple("viavox-route", android.graphics.Color.rgb(20, 112, 249), 8f),
        Triple("viavox-track", android.graphics.Color.rgb(20, 204, 160), 5f)
    )
    for ((id, color, width) in specs) {
        if (style.getSource(id) == null) {
            style.addSource(GeoJsonSource(id,
                LineString.fromLngLats(listOf(Point.fromLngLat(0.0,0.0),Point.fromLngLat(0.0,0.0)))
            ))
        }
        if (style.getLayer("$id-line") == null) {
            style.addLayer(LineLayer("$id-line",id).withProperties(
                PropertyFactory.lineColor(color),
                PropertyFactory.lineWidth(width),
                PropertyFactory.lineOpacity(0.88f)
            ))
        }
    }
}

private fun ensureCircumscriptionLayers(context: android.content.Context, style: Style) {
    try {
        if (style.getSource("bairros-source") == null) {
            val json = context.assets.open("bairros_manaus.geojson").bufferedReader().use { it.readText() }
            style.addSource(GeoJsonSource("bairros-source", json))
            style.addLayer(
                FillLayer("bairros-fill", "bairros-source").withProperties(
                    PropertyFactory.fillColor(Expression.get("color")),
                    PropertyFactory.fillOpacity(0.24f)
                )
            )
            style.addLayer(
                LineLayer("bairros-border", "bairros-source").withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineWidth(1.8f),
                    PropertyFactory.lineOpacity(0.85f)
                )
            )
        }
    } catch (_: Exception) {}
}

private fun updateLine(style: Style, id: String, route: List<Pair<Double,Double>>) {
    val source = style.getSource(id) as? GeoJsonSource ?: return
    val positions = if (route.size >= 2) route else listOf(0.0 to 0.0,0.0 to 0.0)
    source.setGeoJson(LineString.fromLngLats(
        positions.map { (lat,lon) -> Point.fromLngLat(lon,lat) }
    ))
}

/**
 * Generates a high-DPI blue GPS location puck bitmap with an optional directional heading cone.
 */
private fun createBlueLocationMarkerBitmap(bearing: Float, showHeading: Boolean, isMotorcycle: Boolean = false): Bitmap {
    val sizePx = 112
    val center = sizePx / 2f
    val bitmap = Bitmap.createBitmap(sizePx,sizePx,Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val teal = android.graphics.Color.rgb(31,221,207)
    val blue = android.graphics.Color.rgb(28,120,248)
    val highlight = if (isMotorcycle) teal else blue
    val aura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(56,
            android.graphics.Color.red(highlight),
            android.graphics.Color.green(highlight),
            android.graphics.Color.blue(highlight))
    }
    canvas.drawCircle(center,center,43f,aura)

    if (!showHeading) {
        // When the heading is unknown, explicitly use a nondirectional dot.
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
        }
        canvas.drawCircle(center,center,22f,outline)
        outline.color = highlight
        canvas.drawCircle(center,center,17f,outline)
        return bitmap
    }

    canvas.save()
    canvas.rotate(bearing,center,center)
    val direction = Path().apply {
        moveTo(center,5f)
        lineTo(center-14f,center-16f)
        lineTo(center+14f,center-16f)
        close()
    }
    canvas.drawPath(direction,Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(190,
            android.graphics.Color.red(highlight),
            android.graphics.Color.green(highlight),
            android.graphics.Color.blue(highlight))
    })

    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = highlight
        style = Paint.Style.FILL
    }
    if (isMotorcycle) {
        // A real two-wheel vehicle silhouette, not just a teal car dot.
        val darkWheel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(12,32,51)
            style = Paint.Style.STROKE
            strokeWidth = 4.5f
        }
        canvas.drawCircle(center,center-18f,9f,darkWheel)
        canvas.drawCircle(center,center+19f,9f,darkWheel)
        canvas.drawRoundRect(android.graphics.RectF(center-5f,center-21f,
            center+5f,center+25f),5f,5f,white)
        canvas.drawRoundRect(android.graphics.RectF(center-4f,center-16f,
            center+4f,center+12f),3f,3f,body)
        canvas.drawLine(center-12f,center-13f,center+12f,center-13f,darkWheel)
        canvas.drawCircle(center,center+10f,6.5f,white)
    } else {
        // Car icon with windshield, body and wheels.
        val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(12,32,51)
        }
        canvas.drawRoundRect(android.graphics.RectF(center-16f,center-27f,
            center+16f,center+28f),10f,10f,white)
        canvas.drawRoundRect(android.graphics.RectF(center-12.5f,center-24f,
            center+12.5f,center+24f),7f,7f,body)
        canvas.drawRoundRect(android.graphics.RectF(center-10f,center-19f,
            center+10f,center-6f),4f,4f,dark)
        canvas.drawRoundRect(android.graphics.RectF(center-10f,center+6f,
            center+10f,center+19f),4f,4f,dark)
        canvas.drawRoundRect(android.graphics.RectF(center-21f,center-16f,
            center-15f,center-3f),2f,2f,dark)
        canvas.drawRoundRect(android.graphics.RectF(center+15f,center-16f,
            center+21f,center-3f),2f,2f,dark)
        canvas.drawRoundRect(android.graphics.RectF(center-21f,center+9f,
            center-15f,center+22f),2f,2f,dark)
        canvas.drawRoundRect(android.graphics.RectF(center+15f,center+9f,
            center+21f,center+22f),2f,2f,dark)
    }
    canvas.restore()
    return bitmap
}

/**
 * Generates clear, high-contrast map badges for speed cameras and toll booths.
 */
private fun createSafetyMarkerBitmap(
    type: com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type,
    speedLimit: Int?
): Bitmap {
    val sizePx = 72
    val center = sizePx / 2f
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Outer shadow
    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(90, 0, 0, 0)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center + 2f, 30f, haloPaint)

    if (type == com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type.SPEED_CAMERA) {
        // Red speed limit ring
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(230, 40, 40)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, 28f, borderPaint)

        // White inner circle
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, 22f, innerPaint)

        // Speed limit number or "RAD"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(20, 20, 20)
            textAlign = Paint.Align.CENTER
            textSize = if (speedLimit != null) 20f else 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val label = speedLimit?.toString() ?: "RAD"
        val yPos = center - ((textPaint.descent() + textPaint.ascent()) / 2)
        canvas.drawText(label, center, yPos, textPaint)
    } else {
        // Toll booth: Amber / orange badge
        val tollPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(255, 152, 0)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, 28f, tollPaint)

        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(40, 25, 0)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, 23f, innerPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val yPos = center - ((textPaint.descent() + textPaint.ascent()) / 2)
        canvas.drawText("PED", center, yPos, textPaint)
    }

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

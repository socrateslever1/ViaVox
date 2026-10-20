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
import com.example.viavox.model.VehicleMode
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
import org.maplibre.android.maps.MapLibreMapOptions
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

private const val INITIAL_ZOOM = 18.05
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
    vehicleType: String = "car",
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
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
        MapView(context, options)
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
    var showAdminBoundaries by remember {
        mutableStateOf(prefs.getBoolean("map_admin_boundaries",false))
    }
    val arcGisService = remember { ArcGisManausPlacesService() }
    var arcGisViewport by remember { mutableStateOf<GisBounds?>(null) }
    var arcGisZoom by remember { mutableStateOf(0.0) }
    var arcGisReferences by remember { mutableStateOf<List<GisReference>>(emptyList()) }
    var headingUp by remember {
        mutableStateOf(prefs.getBoolean("map_heading_up",true))
    }
    // Bússola follows the orientation of the phone even at zero speed.
    // GPS-course mode follows movement direction, ignoring phone rotation.
    var followPhoneCompass by remember {
        mutableStateOf(prefs.getBoolean("map_follow_device_compass",true))
    }
    val vehicleMode = VehicleMode.from(vehicleType)
    var threeDView by remember { mutableStateOf(true) }
    var mapCameraBearing by remember { mutableStateOf(0f) }
    var marker by remember { mutableStateOf<Marker?>(null) }
    var markerIconKey by remember { mutableStateOf("") }
    val vehicleIconCache = remember { mutableMapOf<String, org.maplibre.android.annotations.Icon>() }
    val puckAnimator = remember { arrayOfNulls<ValueAnimator>(1) }
    var lastNavigationBearing by remember { mutableStateOf<Double?>(null) }
    var wasNavigating by remember { mutableStateOf(false) }
    var destMarker by remember { mutableStateOf<Marker?>(null) }
    val favoriteMarkers = remember { mutableListOf<Marker>() }
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
                Lifecycle.Event.ON_DESTROY -> {
                    stopIfNeeded()
                    try {
                        mapView.onDestroy()
                    } catch (_: Exception) {}
                }
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
                val safetyHits = readyMap.queryRenderedFeatures(
                    readyMap.projection.toScreenLocation(coordinate),
                    "viavox-safety-circles", "viavox-safety-labels"
                )
                val safetyHit = safetyHits.firstOrNull()
                if (safetyHit != null) {
                    val title = safetyHit.getStringProperty("title") ?: "Alerta viário"
                    val lat = safetyHit.getNumberProperty("lat")?.toDouble()
                    val lon = safetyHit.getNumberProperty("lon")?.toDouble()
                    if (lat != null && lon != null) {
                        referenceCallback(
                            MapPlace(
                                title, lat, lon,
                                "Alerta de fiscalização · ViaVox"
                            )
                        )
                        return@addOnMapClickListener true
                    }
                }

                val features = readyMap.queryRenderedFeatures(
                    readyMap.projection.toScreenLocation(coordinate), "viavox-arcgis-points",
                    "viavox-arcgis-labels"
                )
                val hit = features.firstOrNull()
                if (hit == null) {
                    // Regular taps must not open a random destination preview.
                    // Choosing an arbitrary GPS coordinate requires a long press.
                    false
                } else {
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
                ensureSafetyAlertLayers(style)
                updateSafetyAlertLayers(style, safetyFeatures)
                isStyleReady = true
            }
            mapView.addOnDidFailLoadingMapListener {
                if (!isStyleReady) {
                    readyMap.setStyle(Style.Builder().fromUri(FALLBACK_MAP_STYLE)) { style ->
                        enhanceRealRoadContrast(style, false)
                        if (threeDView) addBuildings3DIfAvailable(style)
                        ensureNavigationLayers(style)
                        ensureArcGisReferenceLayers(style)
                        ensureSafetyAlertLayers(style)
                        updateSafetyAlertLayers(style, safetyFeatures)
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
            if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
                try {
                    mapView.onDestroy()
                } catch (_: Exception) {}
            }
        }
    }

    // One camera animator owns location, bearing, pitch and zoom together.
    // A second bearingTo animation formerly cancelled every position animation.
    LaunchedEffect(
        map, isStyleReady, latitude, longitude, bearing, hasBearing,
        speedKmh, followUser, hasLocationFix, headingUp, threeDView,
        navigating,vehicleType,compassHeading,followPhoneCompass
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
                createVehicleLocationMarkerBitmap(0f,false,vehicleMode)
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
                        try {
                            val t = (animation.animatedValue as Float).toDouble()
                            existing.position = LatLng(
                                oldPoint.latitude + (point.latitude - oldPoint.latitude)*t,
                                oldPoint.longitude + (point.longitude - oldPoint.longitude)*t
                            )
                        } catch (_: Exception) {}
                    }
                }
                puckAnimator[0] = animator
                animator.start()
            } else existing.position = point
        }

        val reliableCourse = hasBearing && speedKmh >= 7f
        val sensorHeading = compassHeading?.toDouble()
        val targetBearing = NavigationCameraMath.resolveHeading(
            phoneHeading = sensorHeading,
            gpsCourse = bearing.toDouble().takeIf { reliableCourse },
            lastCourse = lastNavigationBearing,
            followCompass = followPhoneCompass
        )
        if (reliableCourse) lastNavigationBearing = bearing.toDouble()

        if (followUser) {
            val current = currentMap.cameraPosition
            val cameraBearing = when {
                !headingUp -> 0.0
                navigating && targetBearing != null ->
                    NavigationCameraMath.smoothBearing(current.bearing,targetBearing,0.72)
                targetBearing != null ->
                    NavigationCameraMath.smoothBearing(current.bearing,targetBearing,0.72)
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
                navigating && current.zoom < 17.0 ->
                    NavigationCameraMath.followZoom(speedKmh)
                navigating && current.zoom > 18.5 -> 18.5
                existing == null -> INITIAL_ZOOM
                !navigating && wasNavigating ->
                    NavigationCameraMath.EXPLORE_FOLLOW_ZOOM
                !navigating && current.zoom < 16.8 ->
                    NavigationCameraMath.EXPLORE_FOLLOW_ZOOM
                else -> current.zoom // respect a deliberate pinch/zoom after recenter
            }
            // Never start an overlapping camera animation for tiny GPS noise.
            // Keep guidance responsive without interrupting 1-second transitions
            // with another full 1-second movement on each fix.
            val previousCameraTarget = current.target ?: point
            val remainingCameraMovement = com.example.viavox.model.NavigationMath.meters(
                previousCameraTarget.latitude,previousCameraTarget.longitude,
                target.first,target.second
            )
            val turning = abs(
                NavigationCameraMath.angularDelta(current.bearing,cameraBearing)
            )
            if (remainingCameraMovement > 2.5 || turning > 2.5 ||
                abs(current.zoom-cameraZoom) > 0.04 ||
                abs(current.tilt - if (threeDView) NavigationCameraMath.GUIDE_TILT else 0.0) > 1.5
            ) {
                currentMap.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(target.first,target.second))
                            .zoom(cameraZoom)
                            .bearing(cameraBearing)
                            .tilt(if (threeDView) NavigationCameraMath.GUIDE_TILT else 0.0)
                            .build()
                    ), if (navigating) 370 else 320
                )
            }
        }
        wasNavigating = navigating
    }

    // Update the screen-aligned vehicle symbol independently of map camera.
    // Crucially, never animate the camera in this effect.
    LaunchedEffect(
        map, isStyleReady, marker, compassHeading, bearing, hasBearing,
        speedKmh, mapCameraBearing, vehicleType, headingUp, navigating,
        followPhoneCompass, hasLocationFix
    ) {
        val currentMarker = marker ?: return@LaunchedEffect
        if (!isStyleReady || !hasLocationFix) return@LaunchedEffect
        val reliable = hasBearing && speedKmh >= 7f
        val direction = NavigationCameraMath.resolveHeading(
            phoneHeading = compassHeading?.toDouble(),
            gpsCourse = bearing.toDouble().takeIf { reliable },
            lastCourse = lastNavigationBearing,
            followCompass = followPhoneCompass
        )
        val angle = if (direction == null) 0.0
            else NavigationCameraMath.angularDelta(mapCameraBearing.toDouble(),direction)
        // MapLibre uses a native sprite atlas. Reuse a bounded set of images
        // instead of allocating a new bitmap every compass notification.
        val bucket = if (direction == null) 0 else
            (((angle + 360.0) % 360.0 / 10.0).toInt() * 10) % 360
        val key = "${vehicleMode.storedValue}:${direction != null}:$bucket"
        if (key != markerIconKey) {
            if (vehicleIconCache.size > 45) vehicleIconCache.clear()
            val icon = vehicleIconCache.getOrPut(key) {
                IconFactory.getInstance(context).fromBitmap(
                    createVehicleLocationMarkerBitmap(bucket.toFloat(),direction != null,vehicleMode)
                )
            }
            currentMarker.icon = icon
            markerIconKey = key
        }
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

    LaunchedEffect(map, isStyleReady, showAdminBoundaries) {
        val currentMap = map ?: return@LaunchedEffect
        if (!isStyleReady) return@LaunchedEffect
        val style = currentMap.style ?: return@LaunchedEffect
        ensureCircumscriptionLayers(context,style)
        style.getLayer("bairros-fill")?.setProperties(
            PropertyFactory.fillOpacity(if (showAdminBoundaries) 0.13f else 0f)
        )
        style.getLayer("bairros-border")?.setProperties(
            PropertyFactory.lineOpacity(if (showAdminBoundaries) 0.48f else 0f)
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
        val style = mapRef.style ?: return@LaunchedEffect
        ensureSafetyAlertLayers(style)
        updateSafetyAlertLayers(style, safetyFeatures)
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
        }
        // A navigation transition itself re-enables following above. Subsequent
        // route updates must NOT undo a user's chosen manual zoom/overview.
    }

    Box(modifier = modifier.fillMaxSize().testTag("map_view")) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        if (!isStyleReady) {
            CircularProgressIndicator(
                color = ViaVoxTeal,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // The map owns the screen. Frequent operations stay under one small
        // control menu; the rail never overlaps the street/navigation HUD.
        Column(
            // Keep the rail within the middle-right safe region; bottom cards,
            // gesture navigation and the navigation strip must never cover it.
            modifier = Modifier.align(Alignment.TopEnd)
                // Anchor beneath the top search/navigation header. Do not
                // measure from screen center: on short phones it collides
                // with the bottom street panel and clips the recenter button.
                .padding(end = 14.dp, top = if (navigating) 112.dp else 146.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!navigating) Box {
                MapControlButton(
                    icon = Icons.Default.Layers,
                    label = "Camadas, orientação e nível de zoom",
                    testTag = "map_style_button"
                ) { styleMenuExpanded = true }

                DropdownMenu(
                    expanded = styleMenuExpanded,
                    onDismissRequest = { styleMenuExpanded = false }
                ) {
                    listOf(
                        "bright" to "Mapa claro",
                        "liberty" to "Mapa detalhado",
                        "dark" to "Mapa noturno"
                    ).forEach { (key,title) ->
                        DropdownMenuItem(
                            text = { Text(if (selectedMapStyle == key) "✓ $title" else title) },
                            onClick = {
                                styleMenuExpanded = false
                                if (selectedMapStyle != key) {
                                    selectedMapStyle = key
                                    prefs.edit().putString("map_style",key).apply()
                                    val current = map
                                    if (current != null) {
                                        puckAnimator[0]?.cancel()
                                        marker?.let { current.removeMarker(it) }
                                        marker = null
                                        markerIconKey = ""
                                        isStyleReady = false
                                        current.setStyle(
                                            Style.Builder().fromUri(mapStyleUrl(key))
                                        ) { style ->
                                            enhanceRealRoadContrast(style,key == "dark")
                                            if (threeDView) addBuildings3DIfAvailable(style)
                                            ensureNavigationLayers(style)
                                            ensureArcGisReferenceLayers(style)
                                            ensureSafetyAlertLayers(style)
                                            updateSafetyAlertLayers(style, safetyFeatures)
                                            isStyleReady = true
                                        }
                                    }
                                }
                            }
                        )
                    }
                    androidx.compose.material3.HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (showArcGisReferences)
                            "✓ Mostrar referências" else "Mostrar referências") },
                        onClick = {
                            showArcGisReferences = !showArcGisReferences
                            prefs.edit().putBoolean(
                                "map_arcgis_references",showArcGisReferences
                            ).apply()
                            styleMenuExpanded = false
                            if (showArcGisReferences) {
                                map?.let { activeMap ->
                                    val b = activeMap.projection.visibleRegion.latLngBounds
                                    arcGisZoom = activeMap.cameraPosition.zoom
                                    arcGisViewport = GisBounds(
                                        b.longitudeWest,b.latitudeSouth,
                                        b.longitudeEast,b.latitudeNorth
                                    )
                                }
                            }
                        },
                        modifier = Modifier.testTag("arcgis_references_toggle")
                    )
                    DropdownMenuItem(
                        text = { Text(if (showAdminBoundaries)
                            "✓ Limites de bairros" else "Limites de bairros") },
                        onClick = {
                            showAdminBoundaries = !showAdminBoundaries
                            prefs.edit().putBoolean(
                                "map_admin_boundaries",showAdminBoundaries
                            ).apply()
                            styleMenuExpanded = false
                        },
                        modifier = Modifier.testTag("map_admin_boundaries_toggle")
                    )
                    androidx.compose.material3.HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(
                            if (headingUp && followPhoneCompass)
                                "✓ Bússola · girar com o celular" else "Bússola · girar com o celular"
                        ) },
                        onClick = {
                            followPhoneCompass = true
                            headingUp = true
                            followUser = true
                            prefs.edit().putBoolean("map_follow_device_compass",true)
                                .putBoolean("map_heading_up",true).apply()
                            styleMenuExpanded = false
                        },
                        modifier = Modifier.testTag("compass_button")
                    )
                    DropdownMenuItem(
                        text = { Text(
                            if (headingUp && !followPhoneCompass)
                                "✓ Rumo do GPS · movimento" else "Rumo do GPS · movimento"
                        ) },
                        onClick = {
                            followPhoneCompass = false
                            headingUp = true
                            followUser = true
                            prefs.edit().putBoolean("map_follow_device_compass",false)
                                .putBoolean("map_heading_up",true).apply()
                            styleMenuExpanded = false
                        },
                        modifier = Modifier.testTag("gps_course_button")
                    )
                    DropdownMenuItem(
                        text = { Text(if (!headingUp)
                            "✓ Norte para cima" else "Norte para cima") },
                        onClick = {
                            headingUp = false
                            followUser = true
                            prefs.edit().putBoolean("map_heading_up",false).apply()
                            styleMenuExpanded = false
                            map?.animateCamera(CameraUpdateFactory.bearingTo(0.0),350)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (threeDView)
                            "✓ Perspectiva 3D" else "Perspectiva 3D") },
                        onClick = {
                            threeDView = !threeDView
                            styleMenuExpanded = false
                            map?.animateCamera(CameraUpdateFactory.tiltTo(
                                if (threeDView) NavigationCameraMath.GUIDE_TILT else 0.0
                            ),450)
                        },
                        modifier = Modifier.testTag("map_3d_button")
                    )
                    androidx.compose.material3.HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Aproximar mapa") },
                        leadingIcon = { Icon(Icons.Default.Add,null) },
                        onClick = {
                            styleMenuExpanded = false
                            followUser = false
                            map?.animateCamera(CameraUpdateFactory.zoomIn(),280)
                        },
                        modifier = Modifier.testTag("zoom_in_button")
                    )
                    DropdownMenuItem(
                        text = { Text("Afastar mapa") },
                        leadingIcon = { Icon(Icons.Default.Remove,null) },
                        onClick = {
                            styleMenuExpanded = false
                            followUser = false
                            map?.animateCamera(CameraUpdateFactory.zoomOut(),280)
                        },
                        modifier = Modifier.testTag("zoom_out_button")
                    )
                    Text(
                        "Referências: GeoSegurança / ArcGIS Online",
                        color = Color.Gray,fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 14.dp,vertical = 4.dp)
                    )
                }
            }

            MapControlButton(
                icon = Icons.Default.MyLocation,
                label = "Seguir minha posição, na direção do movimento",
                testTag = "recenter_button"
            ) {
                headingUp = true
                followUser = true
                prefs.edit().putBoolean("map_heading_up",true).apply()
                map?.let { activeMap ->
                    if (hasLocationFix) {
                        val targetBearing = NavigationCameraMath.resolveHeading(
                            phoneHeading = compassHeading?.toDouble(),
                            gpsCourse = bearing.toDouble().takeIf { hasBearing && speedKmh >= 7f },
                            lastCourse = lastNavigationBearing,
                            followCompass = followPhoneCompass
                        )
                        val mapDirection = targetBearing ?: activeMap.cameraPosition.bearing
                        val targetCoords = if (navigating && targetBearing != null)
                            NavigationCameraMath.cameraTarget(
                                latitude,longitude,mapDirection,
                                NavigationCameraMath.lookAheadMeters(speedKmh)
                            )
                        else latitude to longitude
                        activeMap.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(LatLng(targetCoords.first,targetCoords.second))
                                    .zoom(if (navigating)
                                        NavigationCameraMath.followZoom(speedKmh)
                                        else NavigationCameraMath.EXPLORE_FOLLOW_ZOOM
                                    )
                                    .bearing(mapDirection)
                                    .tilt(if (threeDView)
                                        NavigationCameraMath.GUIDE_TILT else 0.0
                                    )
                                    .build()
                            ),520
                        )
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

/** Native GPU layers for real-time safety alerts (speed cameras, speed humps, red light cameras, toll booths). */
private fun ensureSafetyAlertLayers(style: Style) {
    val id = "viavox-safety-alerts"
    if (style.getSource(id) == null) {
        style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getLayer("viavox-safety-circles") == null) {
        style.addLayer(
            CircleLayer("viavox-safety-circles", id).withProperties(
                PropertyFactory.circleRadius(14f),
                PropertyFactory.circleColor(
                    Expression.match(
                        Expression.get("type"),
                        Expression.literal("speed_camera"), Expression.color(android.graphics.Color.rgb(230, 40, 40)),
                        Expression.literal("speed_hump_camera"), Expression.color(android.graphics.Color.rgb(235, 120, 0)),
                        Expression.literal("red_light_camera"), Expression.color(android.graphics.Color.rgb(180, 20, 70)),
                        Expression.literal("toll_booth"), Expression.color(android.graphics.Color.rgb(255, 152, 0)),
                        Expression.color(android.graphics.Color.rgb(230, 40, 40))
                    )
                ),
                PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                PropertyFactory.circleStrokeWidth(2f)
            )
        )
    }
    if (style.getLayer("viavox-safety-labels") == null) {
        style.addLayer(
            SymbolLayer("viavox-safety-labels", id).withProperties(
                PropertyFactory.textField(Expression.get("badge")),
                PropertyFactory.textSize(10f),
                PropertyFactory.textColor(android.graphics.Color.WHITE),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true)
            )
        )
    }
}

private fun updateSafetyAlertLayers(
    style: Style,
    features: List<com.example.viavox.service.SafetyAlertEngine.SafetyFeature>
) {
    val source = style.getSource("viavox-safety-alerts") as? GeoJsonSource ?: return
    val geoJsonFeatures = features.map { feature ->
        val typeStr = when (feature.type) {
            com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type.SPEED_CAMERA -> "speed_camera"
            com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type.SPEED_HUMP_CAMERA -> "speed_hump_camera"
            com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type.RED_LIGHT_CAMERA -> "red_light_camera"
            com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type.TOLL_BOOTH -> "toll_booth"
        }
        val badge = when (feature.type) {
            com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type.SPEED_CAMERA ->
                feature.speedLimitKmh?.toString() ?: "RAD"
            com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type.SPEED_HUMP_CAMERA ->
                feature.speedLimitKmh?.toString() ?: "LOM"
            com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type.RED_LIGHT_CAMERA -> "SEM"
            com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type.TOLL_BOOTH -> "PED"
        }
        val title = when (feature.type) {
            com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type.SPEED_CAMERA ->
                feature.location.ifBlank { feature.speedLimitKmh?.let { "Radar $it km/h" } ?: "Radar de velocidade" }
            com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type.SPEED_HUMP_CAMERA ->
                feature.location.ifBlank { feature.speedLimitKmh?.let { "Lombada eletrônica $it km/h" } ?: "Lombada eletrônica" }
            com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type.RED_LIGHT_CAMERA ->
                feature.location.ifBlank { "Fiscalização semafórica" }
            com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type.TOLL_BOOTH ->
                feature.location.ifBlank { "Pedágio" }
        }
        Feature.fromGeometry(Point.fromLngLat(feature.longitude, feature.latitude)).apply {
            addStringProperty("id", feature.id)
            addStringProperty("type", typeStr)
            addStringProperty("badge", badge)
            addStringProperty("title", title)
            addNumberProperty("lat", feature.latitude)
            addNumberProperty("lon", feature.longitude)
        }
    }
    source.setGeoJson(FeatureCollection.fromFeatures(geoJsonFeatures))
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
                    PropertyFactory.fillOpacity(0f)
                )
            )
            style.addLayer(
                LineLayer("bairros-border", "bairros-source").withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineWidth(1.8f),
                    PropertyFactory.lineOpacity(0f)
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
 * Three genuinely different top-down GPS symbols, facing screen-up before
 * rotation: car, two-wheel motorcycle and walking person. No tiny stock
 * arrow is layered over them. The GPS position remains the image center.
 *
 * A visible compass direction rotates the symbol and a small forward pointer.
 * When sensors/course are unavailable, do not invent a direction.
 */
private fun createVehicleLocationMarkerBitmap(
    bearing: Float,
    showHeading: Boolean,
    mode: VehicleMode
): Bitmap {
    // Pixel dimensions are intentional: MapLibre markers use bitmap pixels.
    // The previous 164px sprite covered too much of the street at urban zoom.
    val side = 104
    val center = side / 2f
    val bitmap = Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val white = android.graphics.Color.WHITE
    val navy = android.graphics.Color.rgb(9,27,42)
    val teal = android.graphics.Color.rgb(32,221,195)
    val accent = when(mode) {
        VehicleMode.MOTORCYCLE -> teal
        VehicleMode.CAR -> android.graphics.Color.rgb(35,146,255)
        VehicleMode.WALKING -> android.graphics.Color.rgb(255,180,76)
    }
    fun paint(color: Int, width: Float = 0f): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            if (width > 0f) {
                style = Paint.Style.STROKE
                strokeWidth = width
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
        }
    canvas.drawCircle(center,center,37f,paint(android.graphics.Color.argb(32,32,221,195)))
    canvas.drawCircle(center,center,31f,paint(android.graphics.Color.argb(222,9,27,42)))
    canvas.drawCircle(center,center,30f,paint(android.graphics.Color.argb(198,32,221,195),1.5f))
    canvas.save()
    if (showHeading) canvas.rotate(bearing,center,center)
    canvas.translate(center,center)

    if (showHeading) {
        // Heading points up. It never overlays the motorcycle's front tyre.
        val triangle = Path().apply {
            moveTo(0f,-45f);lineTo(-6f,-34f);lineTo(6f,-34f);close()
        }
        canvas.drawPath(triangle,paint(accent))
    }
    when(mode) {
        VehicleMode.MOTORCYCLE -> {
            // Recognizable top view: front/rear tyres, handlebar, streamlined
            // tank, seat and helmet. No white "stacked rings" or giant halo.
            canvas.drawRoundRect(
                android.graphics.RectF(-5.5f,-28f,5.5f,-17f),
                5f,5f,paint(android.graphics.Color.rgb(22,31,39))
            )
            canvas.drawLine(0f,-27f,0f,-19f,paint(white,1.8f))
            canvas.drawRoundRect(
                android.graphics.RectF(-5.5f,18f,5.5f,28f),
                5f,5f,paint(android.graphics.Color.rgb(22,31,39))
            )
            canvas.drawLine(0f,21f,0f,26f,paint(white,1.7f))
            canvas.drawLine(-19f,-14f,19f,-14f,paint(white,3.2f))
            canvas.drawCircle(-19f,-14f,2.2f,paint(navy))
            canvas.drawCircle(19f,-14f,2.2f,paint(navy))
            val fairing = Path().apply {
                moveTo(0f,-20f)
                cubicTo(-11f,-19f,-12f,-4f,-10f,8f)
                lineTo(-6f,18f);lineTo(6f,18f);lineTo(10f,8f)
                cubicTo(12f,-4f,11f,-19f,0f,-20f)
                close()
            }
            canvas.drawPath(fairing,paint(accent))
            canvas.drawPath(fairing,paint(white,1.5f))
            canvas.drawRoundRect(
                android.graphics.RectF(-7f,9f,7f,23f),
                5f,5f,paint(navy)
            )
            canvas.drawRoundRect(
                android.graphics.RectF(-5f,10f,5f,20f),
                3f,3f,paint(android.graphics.Color.rgb(39,79,91))
            )
            // Single helmet, small enough not to resemble a second wheel.
            canvas.drawCircle(0f,-6f,7.5f,paint(navy))
            canvas.drawArc(
                android.graphics.RectF(-7.5f,-13.5f,7.5f,1.5f),
                204f,132f,false,paint(white,2.5f)
            )
        }
        VehicleMode.CAR -> {
            canvas.drawRoundRect(android.graphics.RectF(-22f,-19f,-15f,-4f),3f,3f,paint(navy))
            canvas.drawRoundRect(android.graphics.RectF(15f,-19f,22f,-4f),3f,3f,paint(navy))
            canvas.drawRoundRect(android.graphics.RectF(-22f,10f,-15f,24f),3f,3f,paint(navy))
            canvas.drawRoundRect(android.graphics.RectF(15f,10f,22f,24f),3f,3f,paint(navy))
            canvas.drawRoundRect(android.graphics.RectF(-17f,-29f,17f,29f),
                9f,9f,paint(accent))
            canvas.drawRoundRect(android.graphics.RectF(-13f,-22f,13f,-5f),
                4f,4f,paint(navy))
            canvas.drawRoundRect(android.graphics.RectF(-12f,6f,12f,23f),
                4f,4f,paint(android.graphics.Color.rgb(35,82,119)))
            canvas.drawRoundRect(android.graphics.RectF(-17f,-29f,17f,29f),
                9f,9f,paint(white,1.8f))
        }
        VehicleMode.WALKING -> {
            canvas.drawCircle(0f,-19f,6f,paint(white))
            canvas.drawLine(0f,-12f,0f,9f,paint(white,5f))
            canvas.drawLine(0f,-5f,-15f,6f,paint(white,4f))
            canvas.drawLine(0f,-5f,15f,1f,paint(white,4f))
            canvas.drawLine(0f,9f,-12f,23f,paint(white,4f))
            canvas.drawLine(0f,9f,12f,23f,paint(white,4f))
            canvas.drawRoundRect(android.graphics.RectF(-6f,-10f,6f,9f),
                5f,5f,paint(accent))
        }
    }
    canvas.restore()
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
    // FillExtrusionLayer on Android emulators / mobile GPUs can trigger SIGSEGV in RenderThread / libmaplibre shaders.
    // The tilted 3D driving perspective is rendered cleanly, smoothly and reliably via CameraPosition.tilt.
    return
}

package com.example.viavox.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viavox.data.ArcGisManausPlacesService
import com.example.viavox.data.GisBounds
import com.example.viavox.data.GisReference
import com.example.viavox.data.ManausArcGisLayer
import com.example.viavox.model.MapPlace
import com.example.viavox.model.NavigationCameraMath
import com.example.viavox.model.NavigationMath
import com.example.viavox.model.NavigationRoute
import com.example.viavox.model.TripPoint
import com.example.viavox.model.VehicleMode
import com.example.viavox.service.SafetyAlertEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

private const val INITIAL_ZOOM = 17.5
private const val MIN_MAP_ZOOM = 4.0
private const val MAX_MAP_ZOOM = 19.0
private const val TILE_SIZE = 256.0

/**
 * Pure Kotlin + Jetpack Compose hardware-accelerated OpenStreetMap Slippy Map Engine.
 * Eliminates native C++ libmaplibre.so / GL2Encoder::s_glDrawElements SIGSEGV crashes
 * on Android emulators and mobile GPUs while rendering real OpenStreetMap / CartoDB tiles,
 * 3D perspective tilt, smooth camera tracking, routes, safety alerts, ArcGIS references,
 * neighborhood boundaries, and custom top-down vehicle markers.
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
    showMapControls: Boolean = true,
    safetyFeatures: List<SafetyAlertEngine.SafetyFeature> = emptyList(),
    onMapLongPress: (Double, Double) -> Unit = { _, _ -> },
    onReferenceSelected: (MapPlace) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val longPressCallback by rememberUpdatedState(onMapLongPress)
    val referenceCallback by rememberUpdatedState(onReferenceSelected)

    val prefs = remember(context) {
        context.getSharedPreferences("viavox_prefs", Context.MODE_PRIVATE)
    }
    var selectedMapStyle by remember {
        mutableStateOf(prefs.getString("map_style", "bright") ?: "bright")
    }
    var styleMenuExpanded by remember { mutableStateOf(false) }
    var showArcGisReferences by remember {
        mutableStateOf(prefs.getBoolean("map_arcgis_references", true))
    }
    var showAdminBoundaries by remember {
        mutableStateOf(prefs.getBoolean("map_admin_boundaries", false))
    }
    var headingUp by remember {
        mutableStateOf(prefs.getBoolean("map_heading_up", true))
    }
    var followPhoneCompass by remember {
        mutableStateOf(prefs.getBoolean("map_follow_device_compass", true))
    }
    // Flat, full-viewport map first. Perspective remains an explicit opt-in.
    var threeDView by remember { mutableStateOf(false) }
    var followUser by remember { mutableStateOf(true) }

    val vehicleMode = VehicleMode.from(vehicleType)
    val compassHeading = rememberDeviceCompassHeading(isActive, latitude, longitude)
    var lastNavigationBearing by remember { mutableStateOf<Double?>(null) }
    var wasNavigating by remember { mutableStateOf(false) }

    // Camera state
    var cameraLat by remember { mutableDoubleStateOf(latitude) }
    var cameraLon by remember { mutableDoubleStateOf(longitude) }
    var cameraZoom by remember { mutableDoubleStateOf(if (hasLocationFix) INITIAL_ZOOM else 12.5) }
    var cameraBearing by remember { mutableFloatStateOf(0f) }
    var viewportSize by remember { mutableStateOf(IntSize(1080, 1920)) }

    // Animated 3D tilt angle
    val animatedTilt by animateFloatAsState(
        targetValue = if (threeDView) 28f else 0f,
        animationSpec = tween(durationMillis = 380),
        label = "camera_tilt"
    )

    // Tile loader & cache
    var tileTick by remember { mutableIntStateOf(0) }
    var tileSuccesses by remember { mutableIntStateOf(0) }
    var tileFailures by remember { mutableIntStateOf(0) }
    var tileLoadTimedOut by remember { mutableStateOf(false) }
    var tileRetryGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedMapStyle, tileRetryGeneration) {
        delay(15_000L)
        if (tileSuccesses == 0) tileLoadTimedOut = true
    }
    val cartoApiKey = remember(context) {
        prefs.getString("carto_basemaps_key", "").orEmpty()
    }
    val tileRepository = remember(context) {
        OsmTileRepository(context,
            onTileLoaded = {
                tileSuccesses++
                tileTick++
            },
            onTileFailed = {
                tileFailures++
                tileTick++
            }
        )
    }

    // Neighborhood boundaries (bairros_manaus.geojson)
    var adminPolygons by remember { mutableStateOf<List<AdminBoundaryPolygon>>(emptyList()) }
    LaunchedEffect(showAdminBoundaries) {
        if (showAdminBoundaries && adminPolygons.isEmpty()) {
            adminPolygons = withContext(Dispatchers.IO) {
                loadAdminBoundaries(context)
            }
        }
    }

    // ArcGIS Manaus references
    val arcGisService = remember { ArcGisManausPlacesService() }
    var arcGisReferences by remember { mutableStateOf<List<GisReference>>(emptyList()) }

    LaunchedEffect(cameraLat, cameraLon, cameraZoom, showArcGisReferences, viewportSize) {
        if (!showArcGisReferences || cameraZoom < ManausArcGisLayer.MIN_ZOOM || viewportSize.width <= 0) {
            arcGisReferences = emptyList()
        } else {
            delay(450)
            val bounds = computeVisibleGisBounds(
                cameraLat, cameraLon, cameraZoom,
                viewportSize.width.toDouble(), viewportSize.height.toDouble()
            )
            arcGisReferences = arcGisService.loadVisible(bounds, cameraZoom)
        }
    }

    // Resume following user whenever tracking or navigation starts
    LaunchedEffect(isActive, navigating) {
        if (isActive && navigating) {
            followUser = true
        } else if (isActive && !followUser && route == null) {
            followUser = true
        }
    }

    // Effective user coordinate (snapped to route during active navigation)
    val effectiveUserLatLon = remember(latitude, longitude, navigating, route) {
        if (navigating && route != null && route.coordinates.isNotEmpty()) {
            NavigationMath.snapToPolyline(latitude, longitude, route.coordinates)
        } else {
            latitude to longitude
        }
    }

    // Smooth camera tracking when followUser is active
    LaunchedEffect(
        effectiveUserLatLon, bearing, hasBearing, speedKmh,
        followUser, hasLocationFix, headingUp, navigating,
        compassHeading, followPhoneCompass
    ) {
        if (!hasLocationFix) return@LaunchedEffect
        val reliableCourse = hasBearing && speedKmh >= 7f
        val targetBearing = NavigationCameraMath.resolveHeading(
            phoneHeading = compassHeading?.toDouble(),
            gpsCourse = bearing.toDouble().takeIf { reliableCourse },
            lastCourse = lastNavigationBearing,
            followCompass = followPhoneCompass
        )
        if (reliableCourse) {
            lastNavigationBearing = bearing.toDouble()
        }

        if (followUser) {
            val nextBearing = when {
                !headingUp -> 0.0
                targetBearing != null -> NavigationCameraMath.smoothBearing(
                    cameraBearing.toDouble(),
                    targetBearing,
                    0.72
                )
                else -> cameraBearing.toDouble()
            }
            val targetCoords = if (navigating && headingUp && targetBearing != null) {
                NavigationCameraMath.cameraTarget(
                    effectiveUserLatLon.first,
                    effectiveUserLatLon.second,
                    nextBearing,
                    NavigationCameraMath.lookAheadMeters(speedKmh)
                )
            } else {
                effectiveUserLatLon
            }
            val targetZoom = when {
                navigating && !wasNavigating -> NavigationCameraMath.followZoom(speedKmh)
                navigating && cameraZoom < 17.0 -> NavigationCameraMath.followZoom(speedKmh)
                navigating && cameraZoom > 18.5 -> 18.5
                !navigating && wasNavigating -> NavigationCameraMath.EXPLORE_FOLLOW_ZOOM
                !navigating && cameraZoom < 16.5 -> NavigationCameraMath.EXPLORE_FOLLOW_ZOOM
                else -> cameraZoom
            }.coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)

            cameraLat = targetCoords.first
            cameraLon = targetCoords.second
            cameraZoom = targetZoom
            cameraBearing = nextBearing.toFloat()
        }
        wasNavigating = navigating
    }

    // Fit route bounds when previewing a route
    LaunchedEffect(route, navigating) {
        if (route != null && route.coordinates.isNotEmpty() && !navigating) {
            followUser = false
            var minLat = Double.POSITIVE_INFINITY
            var maxLat = Double.NEGATIVE_INFINITY
            var minLon = Double.POSITIVE_INFINITY
            var maxLon = Double.NEGATIVE_INFINITY
            route.coordinates.forEach { (lat, lon) ->
                minLat = min(minLat, lat)
                maxLat = max(maxLat, lat)
                minLon = min(minLon, lon)
                maxLon = max(maxLon, lon)
            }
            cameraLat = (minLat + maxLat) / 2.0
            cameraLon = (minLon + maxLon) / 2.0
            cameraBearing = 0f
            val latSpan = (maxLat - minLat).coerceAtLeast(0.002)
            val lonSpan = (maxLon - minLon).coerceAtLeast(0.002)
            val maxSpan = max(latSpan, lonSpan)
            val fittedZoom = (15.5 - ln(maxSpan / 0.01) / ln(2.0)).coerceIn(11.0, 17.2)
            cameraZoom = fittedZoom
        }
    }

    // Cache of top-down vehicle bitmaps by (mode, hasDirection, bucketAngle)
    val vehicleBitmapCache = remember { LruCache<String, Bitmap>(48) }

    val reliableCourse = hasBearing && speedKmh >= 7f
    val resolvedDirection = NavigationCameraMath.resolveHeading(
        phoneHeading = compassHeading?.toDouble(),
        gpsCourse = bearing.toDouble().takeIf { reliableCourse },
        lastCourse = lastNavigationBearing,
        followCompass = followPhoneCompass
    )
    val relativeVehicleAngle = if (resolvedDirection == null) 0f else {
        NavigationCameraMath.angularDelta(cameraBearing.toDouble(), resolvedDirection).toFloat()
    }
    val angleBucket = if (resolvedDirection == null) 0 else {
        (((relativeVehicleAngle + 360f) % 360f / 10f).toInt() * 10) % 360
    }
    val vehicleBitmapKey = "${vehicleMode.storedValue}:${resolvedDirection != null}:$angleBucket"
    val vehicleBitmap = remember(vehicleBitmapKey) {
        vehicleBitmapCache.get(vehicleBitmapKey) ?: createVehicleLocationMarkerBitmap(
            bearing = angleBucket.toFloat(),
            showHeading = resolvedDirection != null,
            mode = vehicleMode
        ).also { vehicleBitmapCache.put(vehicleBitmapKey, it) }
    }

    // Reusable Paint objects for labels and badges on nativeCanvas
    val badgeTextPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
    }
    val labelTextPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(22, 45, 68)
            textSize = 18f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
    }
    val labelHaloPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 18f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
    }
    val tilePaint = remember(selectedMapStyle, cartoApiKey) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            // If no CARTO key is configured, dark mode uses OSM raster tiles
            // with a visible local night filter, not a nonexistent tile service.
            if (selectedMapStyle == "dark" && cartoApiKey.isBlank()) {
                colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                    -0.70f, 0f, 0f, 0f, 204f,
                    0f, -0.70f, 0f, 0f, 206f,
                    0f, 0f, -0.70f, 0f, 215f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
        }
    }

    val currentSafetyFeatures by rememberUpdatedState(safetyFeatures)
    val currentArcGisReferences by rememberUpdatedState(arcGisReferences)
    val currentShowArcGis by rememberUpdatedState(showArcGisReferences)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { if (it.width > 0 && it.height > 0) viewportSize = it }
            .testTag("map_view")
    ) {
        ComposeCanvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationX = animatedTilt
                    cameraDistance = 18f * density
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoomChange, rotationChange ->
                        if (abs(pan.x) > 1.5f || abs(pan.y) > 1.5f || abs(zoomChange - 1f) > 0.01f) {
                            followUser = false
                        }
                        if (zoomChange != 1f && zoomChange > 0f) {
                            val zoomDelta = ln(zoomChange.toDouble()) / ln(2.0)
                            cameraZoom = (cameraZoom + zoomDelta).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
                        }
                        if (abs(rotationChange) > 0.2f && !followUser) {
                            cameraBearing = (cameraBearing + rotationChange + 360f) % 360f
                        }
                        if (pan.x != 0f || pan.y != 0f) {
                            val newLatLon = WebMercatorMath.screenToLatLon(
                                screenX = size.width / 2f - pan.x,
                                screenY = size.height / 2f - pan.y,
                                centerX = size.width / 2f,
                                centerY = size.height / 2f,
                                centerLat = cameraLat,
                                centerLon = cameraLon,
                                zoom = cameraZoom,
                                bearingDeg = cameraBearing.toDouble()
                            )
                            cameraLat = newLatLon.first.coerceIn(-85.0, 85.0)
                            cameraLon = newLatLon.second.coerceIn(-180.0, 180.0)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { tapOffset ->
                            val w = size.width / 2f
                            val h = size.height / 2f
                            // 1. Check safety alert hit (within 42px)
                            val hitSafety = currentSafetyFeatures.firstOrNull { feat ->
                                val pt = WebMercatorMath.latLonToScreen(
                                    feat.latitude, feat.longitude,
                                    w, h, cameraLat, cameraLon, cameraZoom, cameraBearing.toDouble()
                                )
                                hypot(pt.x - tapOffset.x, pt.y - tapOffset.y) <= 42f
                            }
                            if (hitSafety != null) {
                                val title = when (hitSafety.type) {
                                    SafetyAlertEngine.SafetyFeature.Type.SPEED_CAMERA ->
                                        hitSafety.location.ifBlank {
                                            hitSafety.speedLimitKmh?.let { "Radar $it km/h" } ?: "Radar de velocidade"
                                        }
                                    SafetyAlertEngine.SafetyFeature.Type.SPEED_HUMP_CAMERA ->
                                        hitSafety.location.ifBlank {
                                            hitSafety.speedLimitKmh?.let { "Lombada eletrônica $it km/h" } ?: "Lombada eletrônica"
                                        }
                                    SafetyAlertEngine.SafetyFeature.Type.RED_LIGHT_CAMERA ->
                                        hitSafety.location.ifBlank { "Fiscalização semafórica" }
                                    SafetyAlertEngine.SafetyFeature.Type.TOLL_BOOTH ->
                                        hitSafety.location.ifBlank { "Pedágio" }
                                }
                                referenceCallback(
                                    MapPlace(
                                        title,
                                        hitSafety.latitude,
                                        hitSafety.longitude,
                                        "Alerta de fiscalização · ViaVox"
                                    )
                                )
                                return@detectTapGestures
                            }

                            // 2. Check ArcGIS reference hit (within 36px)
                            if (currentShowArcGis) {
                                val hitRef = currentArcGisReferences.firstOrNull { ref ->
                                    val pt = WebMercatorMath.latLonToScreen(
                                        ref.latitude, ref.longitude,
                                        w, h, cameraLat, cameraLon, cameraZoom, cameraBearing.toDouble()
                                    )
                                    hypot(pt.x - tapOffset.x, pt.y - tapOffset.y) <= 36f
                                }
                                if (hitRef != null) {
                                    val addr = listOf(hitRef.address, hitRef.neighborhood)
                                        .filter(String::isNotBlank)
                                        .joinToString(" · ")
                                    referenceCallback(
                                        MapPlace(
                                            hitRef.shortLabel.ifBlank { hitRef.name },
                                            hitRef.latitude,
                                            hitRef.longitude,
                                            "$addr · Manaus · ArcGIS"
                                        )
                                    )
                                }
                            }
                        },
                        onLongPress = { pressOffset ->
                            val (lat, lon) = WebMercatorMath.screenToLatLon(
                                screenX = pressOffset.x,
                                screenY = pressOffset.y,
                                centerX = size.width / 2f,
                                centerY = size.height / 2f,
                                centerLat = cameraLat,
                                centerLon = cameraLon,
                                zoom = cameraZoom,
                                bearingDeg = cameraBearing.toDouble()
                            )
                            longPressCallback(lat, lon)
                        }
                    )
                }
        ) {
            // Read tileTick to trigger redraw when async tiles finish downloading
            @Suppress("UNUSED_VARIABLE")
            val tick = tileTick

            val isDark = selectedMapStyle == "dark"
            drawRect(color = if (isDark) Color(0xFF0E1B2A) else Color(0xFFE8EEF2))

            val cx = size.width / 2f
            val cy = size.height / 2f

            // 1. Render interactive-viewport OSM tiles, or CARTO if authorized
            drawSlippyTiles(
                styleKey = selectedMapStyle,
                centerLat = cameraLat,
                centerLon = cameraLon,
                zoom = cameraZoom,
                bearingDeg = cameraBearing.toDouble(),
                tileRepository = tileRepository,
                tilePaint = tilePaint,
                isDark = isDark,
                scope = scope
            )

            // 2. Draw Neighborhood Boundaries (bairros_manaus.geojson) if enabled
            if (showAdminBoundaries && adminPolygons.isNotEmpty()) {
                adminPolygons.forEach { poly ->
                    if (poly.points.size >= 3) {
                        val path = androidx.compose.ui.graphics.Path()
                        poly.points.forEachIndexed { idx, (lat, lon) ->
                            val pt = WebMercatorMath.latLonToScreen(
                                lat, lon, cx, cy, cameraLat, cameraLon, cameraZoom, cameraBearing.toDouble()
                            )
                            if (idx == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                        }
                        path.close()
                        drawPath(path, color = poly.color.copy(alpha = 0.13f))
                        drawPath(path, color = poly.color.copy(alpha = 0.52f), style = Stroke(width = 3.5f))
                    }
                }
            }

            // 3. Draw Recorded / Live Trip Track
            val recordedPoints = if (liveTrack.isNotEmpty()) liveTrack else savedTrack
            if (recordedPoints.size >= 2) {
                val trackPath = androidx.compose.ui.graphics.Path()
                recordedPoints.forEachIndexed { idx, ptData ->
                    val pt = WebMercatorMath.latLonToScreen(
                        ptData.latitude, ptData.longitude,
                        cx, cy, cameraLat, cameraLon, cameraZoom, cameraBearing.toDouble()
                    )
                    if (idx == 0) trackPath.moveTo(pt.x, pt.y) else trackPath.lineTo(pt.x, pt.y)
                }
                drawPath(
                    path = trackPath,
                    color = Color(0xFF14CCA0),
                    style = Stroke(width = 11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // 4. Draw Active Navigation Route
            val routeCoords = route?.coordinates.orEmpty()
            if (routeCoords.size >= 2) {
                val routePath = androidx.compose.ui.graphics.Path()
                routeCoords.forEachIndexed { idx, (lat, lon) ->
                    val pt = WebMercatorMath.latLonToScreen(
                        lat, lon, cx, cy, cameraLat, cameraLon, cameraZoom, cameraBearing.toDouble()
                    )
                    if (idx == 0) routePath.moveTo(pt.x, pt.y) else routePath.lineTo(pt.x, pt.y)
                }
                drawPath(
                    path = routePath,
                    color = Color(0xFF0A2E6E),
                    style = Stroke(width = 22f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = routePath,
                    color = Color(0xFF1470F9),
                    style = Stroke(width = 15f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // 5. Render decluttered ArcGIS landmarks. Base map tiles already contain labels.
            // The overlay must never print every fetched business name on top of the streets.
            if (showArcGisReferences && cameraZoom >= ManausArcGisLayer.MIN_ZOOM) {
                val occupiedLabels = mutableListOf<RectF>()
                val occupiedPins = mutableListOf<Offset>()
                val labelLimit = when {
                    navigating -> 0
                    cameraZoom >= 18.5 -> 12
                    cameraZoom >= 17.5 -> 8
                    cameraZoom >= 16.5 -> 4
                    else -> 0
                }
                val pinLimit = when {
                    navigating -> 14
                    cameraZoom >= 17.5 -> 38
                    else -> 22
                }
                var labelsPlaced = 0
                var pinsPlaced = 0
                for (place in arcGisReferences) {
                    if (pinsPlaced >= pinLimit) break
                    val pt = WebMercatorMath.latLonToScreen(
                        place.latitude, place.longitude,
                        cx, cy, cameraLat, cameraLon, cameraZoom, cameraBearing.toDouble()
                    )
                    if (pt.x !in 24f..(size.width - 24f) ||
                        pt.y !in 115f..(size.height - 180f)) continue
                    // Spatial thinning prevents huge clusters of identical markers.
                    if (occupiedPins.any { other ->
                            hypot(other.x - pt.x, other.y - pt.y) < 58f
                        }) continue
                    occupiedPins.add(pt)
                    pinsPlaced++
                    drawCircle(color = Color.White, radius = 10f, center = pt)
                    drawCircle(color = Color(0xFF02A69C), radius = 7f, center = pt)

                    if (labelsPlaced < labelLimit && place.shortLabel.isNotBlank()) {
                        val title = place.shortLabel.trim().let {
                            if (it.length > 22) it.take(21).trimEnd() + "…" else it
                        }
                        val labelWidth = labelTextPaint.measureText(title)
                        val box = RectF(
                            pt.x - labelWidth / 2f - 8f, pt.y + 11f,
                            pt.x + labelWidth / 2f + 8f, pt.y + 39f
                        )
                        if (box.left >= 10f && box.right <= size.width - 10f &&
                            box.bottom <= size.height - 180f &&
                            occupiedLabels.none { RectF.intersects(it, box) }) {
                            occupiedLabels.add(RectF(box).apply { inset(-16f, -16f) })
                            labelsPlaced++
                            drawIntoCanvas { canvas ->
                                canvas.nativeCanvas.drawText(title, pt.x, pt.y + 30f, labelHaloPaint)
                                canvas.nativeCanvas.drawText(title, pt.x, pt.y + 30f, labelTextPaint)
                            }
                        }
                    }
                }
            }

            // 6. Draw Safety Alerts (Radars, Speed Humps, Red Light Cameras, Toll Booths)
            safetyFeatures.forEach { feature ->
                val pt = WebMercatorMath.latLonToScreen(
                    feature.latitude, feature.longitude,
                    cx, cy, cameraLat, cameraLon, cameraZoom, cameraBearing.toDouble()
                )
                if (pt.x in -60f..(size.width + 60f) && pt.y in -60f..(size.height + 60f)) {
                    val badgeColor = when (feature.type) {
                        SafetyAlertEngine.SafetyFeature.Type.SPEED_CAMERA -> Color(0xFFE62828)
                        SafetyAlertEngine.SafetyFeature.Type.SPEED_HUMP_CAMERA -> Color(0xFFEB7800)
                        SafetyAlertEngine.SafetyFeature.Type.RED_LIGHT_CAMERA -> Color(0xFFB41446)
                        SafetyAlertEngine.SafetyFeature.Type.TOLL_BOOTH -> Color(0xFFFF9800)
                    }
                    val badgeText = when (feature.type) {
                        SafetyAlertEngine.SafetyFeature.Type.SPEED_CAMERA ->
                            feature.speedLimitKmh?.toString() ?: "RAD"
                        SafetyAlertEngine.SafetyFeature.Type.SPEED_HUMP_CAMERA ->
                            feature.speedLimitKmh?.toString() ?: "LOM"
                        SafetyAlertEngine.SafetyFeature.Type.RED_LIGHT_CAMERA -> "SEM"
                        SafetyAlertEngine.SafetyFeature.Type.TOLL_BOOTH -> "PED"
                    }
                    drawCircle(color = Color.White, radius = 30f, center = pt)
                    drawCircle(color = badgeColor, radius = 25f, center = pt)
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(
                            badgeText,
                            pt.x,
                            pt.y + 8f,
                            badgeTextPaint
                        )
                    }
                }
            }

            // 7. Draw Saved Places (Favorites)
            favorites.take(80).forEach { fav ->
                val pt = WebMercatorMath.latLonToScreen(
                    fav.latitude, fav.longitude,
                    cx, cy, cameraLat, cameraLon, cameraZoom, cameraBearing.toDouble()
                )
                if (pt.x in -60f..(size.width + 60f) && pt.y in -60f..(size.height + 60f)) {
                    drawCircle(color = Color.White, radius = 16f, center = pt)
                    drawCircle(color = Color(0xFFFFB300), radius = 12f, center = pt)
                }
            }

            // 8. Draw Destination Marker
            if (destination != null) {
                val pt = WebMercatorMath.latLonToScreen(
                    destination.latitude, destination.longitude,
                    cx, cy, cameraLat, cameraLon, cameraZoom, cameraBearing.toDouble()
                )
                if (pt.x in -80f..(size.width + 80f) && pt.y in -80f..(size.height + 80f)) {
                    drawCircle(color = Color.White, radius = 24f, center = pt)
                    drawCircle(color = Color(0xFFE53935), radius = 18f, center = pt)
                    drawCircle(color = Color.White, radius = 7f, center = pt)
                }
            }

            // 9. Draw Vehicle Location Puck
            if (hasLocationFix) {
                val userPt = WebMercatorMath.latLonToScreen(
                    effectiveUserLatLon.first,
                    effectiveUserLatLon.second,
                    cx, cy, cameraLat, cameraLon, cameraZoom, cameraBearing.toDouble()
                )
                drawIntoCanvas { canvas ->
                    val half = vehicleBitmap.width / 2f
                    canvas.nativeCanvas.drawBitmap(
                        vehicleBitmap,
                        userPt.x - half,
                        userPt.y - half,
                        null
                    )
                }
            }
        }

        if (tileSuccesses == 0 && (tileFailures >= 3 || tileLoadTimedOut)) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp)
                    .testTag("map_tiles_error"),
                color = Color(0xEF10243A),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color(0xFF46667B))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Não foi possível carregar as ruas do mapa.",
                        color = Color.White, fontSize = 14.sp)
                    Text("O GPS continua ativo. Verifique a conexão com a internet.",
                        color = Color(0xFFC7D8E4), fontSize = 12.sp)
                    TextButton(
                        onClick = {
                            tileRepository.retryFailedTiles()
                            tileFailures = 0
                            tileLoadTimedOut = false
                            tileRetryGeneration++
                            tileTick++
                        },
                        modifier = Modifier.testTag("retry_map_tiles")
                    ) {
                        Text("Tentar carregar novamente", color = Color(0xFF34DAC3))
                    }
                }
            }
        }
        if (tileSuccesses == 0 && tileFailures < 3 && !tileLoadTimedOut) {
            Surface(
                modifier = Modifier.align(Alignment.Center).testTag("map_tiles_loading"),
                color = Color(0xE510243A),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            ) {
                Text("Carregando ruas e bairros...",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 190.dp),
            color = Color(0xC900162A),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
        ) {
            Text(
                MapTileSourcePolicy.attribution(cartoApiKey),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                color = Color.White, fontSize = 10.sp
            )
        }

        // Keep the two controls ABOVE the lower street/navigation cards,
        // including on shorter screens. Center-right is deliberately independent
        // of the variable-height search bar and Android bottom system bar.
        if (showMapControls) Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                // Anchor to the safe TOP; centering with bottom padding caused
                // controls to be covered and clipped by the variable-height HUD.
                .padding(
                    end = 14.dp,
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
                        if (navigating) 115.dp else 125.dp
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    ).forEach { (key, title) ->
                        DropdownMenuItem(
                            text = { Text(if (selectedMapStyle == key) "✓ $title" else title) },
                            onClick = {
                                styleMenuExpanded = false
                                if (selectedMapStyle != key) {
                                    selectedMapStyle = key
                                    tileSuccesses = 0
                                    tileFailures = 0
                                    tileLoadTimedOut = false
                                    tileRetryGeneration++
                                    prefs.edit().putString("map_style", key).apply()
                                }
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (showArcGisReferences) "✓ Mostrar referências"
                                else "Mostrar referências"
                            )
                        },
                        onClick = {
                            showArcGisReferences = !showArcGisReferences
                            prefs.edit().putBoolean("map_arcgis_references", showArcGisReferences).apply()
                            styleMenuExpanded = false
                        },
                        modifier = Modifier.testTag("arcgis_references_toggle")
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (showAdminBoundaries) "✓ Limites de bairros"
                                else "Limites de bairros"
                            )
                        },
                        onClick = {
                            showAdminBoundaries = !showAdminBoundaries
                            prefs.edit().putBoolean("map_admin_boundaries", showAdminBoundaries).apply()
                            styleMenuExpanded = false
                        },
                        modifier = Modifier.testTag("map_admin_boundaries_toggle")
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (headingUp && followPhoneCompass)
                                    "✓ Bússola · girar com o celular"
                                else "Bússola · girar com o celular"
                            )
                        },
                        onClick = {
                            followPhoneCompass = true
                            headingUp = true
                            followUser = true
                            prefs.edit()
                                .putBoolean("map_follow_device_compass", true)
                                .putBoolean("map_heading_up", true)
                                .apply()
                            styleMenuExpanded = false
                        },
                        modifier = Modifier.testTag("compass_button")
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (headingUp && !followPhoneCompass)
                                    "✓ Rumo do GPS · movimento"
                                else "Rumo do GPS · movimento"
                            )
                        },
                        onClick = {
                            followPhoneCompass = false
                            headingUp = true
                            followUser = true
                            prefs.edit()
                                .putBoolean("map_follow_device_compass", false)
                                .putBoolean("map_heading_up", true)
                                .apply()
                            styleMenuExpanded = false
                        },
                        modifier = Modifier.testTag("gps_course_button")
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (!headingUp) "✓ Norte para cima" else "Norte para cima")
                        },
                        onClick = {
                            headingUp = false
                            followUser = true
                            cameraBearing = 0f
                            prefs.edit().putBoolean("map_heading_up", false).apply()
                            styleMenuExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (threeDView) "✓ Perspectiva 3D" else "Perspectiva 3D")
                        },
                        onClick = {
                            threeDView = !threeDView
                            styleMenuExpanded = false
                        },
                        modifier = Modifier.testTag("map_3d_button")
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Aproximar mapa") },
                        leadingIcon = { Icon(Icons.Default.Add, null) },
                        onClick = {
                            styleMenuExpanded = false
                            followUser = false
                            cameraZoom = (cameraZoom + 1.0).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
                        },
                        modifier = Modifier.testTag("zoom_in_button")
                    )
                    DropdownMenuItem(
                        text = { Text("Afastar mapa") },
                        leadingIcon = { Icon(Icons.Default.Remove, null) },
                        onClick = {
                            styleMenuExpanded = false
                            followUser = false
                            cameraZoom = (cameraZoom - 1.0).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
                        },
                        modifier = Modifier.testTag("zoom_out_button")
                    )
                    Text(
                        "Referências: GeoSegurança / ArcGIS Online",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
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
                prefs.edit().putBoolean("map_heading_up", true).apply()
                if (hasLocationFix) {
                    val targetBearing = NavigationCameraMath.resolveHeading(
                        phoneHeading = compassHeading?.toDouble(),
                        gpsCourse = bearing.toDouble().takeIf { hasBearing && speedKmh >= 7f },
                        lastCourse = lastNavigationBearing,
                        followCompass = followPhoneCompass
                    )
                    val mapDirection = targetBearing ?: cameraBearing.toDouble()
                    val targetCoords = if (navigating && targetBearing != null) {
                        NavigationCameraMath.cameraTarget(
                            latitude, longitude, mapDirection,
                            NavigationCameraMath.lookAheadMeters(speedKmh)
                        )
                    } else {
                        latitude to longitude
                    }
                    cameraLat = targetCoords.first
                    cameraLon = targetCoords.second
                    cameraZoom = if (navigating) {
                        NavigationCameraMath.followZoom(speedKmh)
                    } else {
                        NavigationCameraMath.EXPLORE_FOLLOW_ZOOM
                    }
                    cameraBearing = mapDirection.toFloat()
                }
            }
        }
    }
}

private fun hypot(dx: Float, dy: Float): Float = kotlin.math.sqrt(dx * dx + dy * dy)

/**
 * Web Mercator projection helpers converting between (latitude, longitude) and screen pixels
 * with support for fractional zoom and camera rotation.
 */
private object WebMercatorMath {
    fun lonToWorldX(lon: Double, worldSize: Double): Double =
        ((lon + 180.0) / 360.0) * worldSize

    fun latToWorldY(lat: Double, worldSize: Double): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = clamped * PI / 180.0
        val mercN = ln(tan(PI / 4.0 + rad / 2.0))
        return (0.5 - mercN / (2.0 * PI)) * worldSize
    }

    fun worldXToLon(worldX: Double, worldSize: Double): Double =
        (worldX / worldSize) * 360.0 - 180.0

    fun worldYToLat(worldY: Double, worldSize: Double): Double {
        val n = PI - 2.0 * PI * (worldY / worldSize)
        return (180.0 / PI) * atan(sinh(n))
    }

    fun latLonToScreen(
        lat: Double,
        lon: Double,
        centerX: Float,
        centerY: Float,
        centerLat: Double,
        centerLon: Double,
        zoom: Double,
        bearingDeg: Double
    ): Offset {
        val worldSize = TILE_SIZE * 2.0.pow(zoom)
        val dx = lonToWorldX(lon, worldSize) - lonToWorldX(centerLon, worldSize)
        val dy = latToWorldY(lat, worldSize) - latToWorldY(centerLat, worldSize)
        val rad = -bearingDeg * PI / 180.0
        val cosA = cos(rad)
        val sinA = sin(rad)
        val rx = dx * cosA - dy * sinA
        val ry = dx * sinA + dy * cosA
        return Offset((centerX + rx).toFloat(), (centerY + ry).toFloat())
    }

    fun screenToLatLon(
        screenX: Float,
        screenY: Float,
        centerX: Float,
        centerY: Float,
        centerLat: Double,
        centerLon: Double,
        zoom: Double,
        bearingDeg: Double
    ): Pair<Double, Double> {
        val worldSize = TILE_SIZE * 2.0.pow(zoom)
        val rx = (screenX - centerX).toDouble()
        val ry = (screenY - centerY).toDouble()
        val rad = bearingDeg * PI / 180.0
        val cosA = cos(rad)
        val sinA = sin(rad)
        val dx = rx * cosA - ry * sinA
        val dy = rx * sinA + ry * cosA
        val wx = lonToWorldX(centerLon, worldSize) + dx
        val wy = latToWorldY(centerLat, worldSize) + dy
        return worldYToLat(wy, worldSize) to worldXToLon(wx, worldSize)
    }
}

private fun computeVisibleGisBounds(
    centerLat: Double,
    centerLon: Double,
    zoom: Double,
    width: Double,
    height: Double
): GisBounds {
    val worldSize = TILE_SIZE * 2.0.pow(zoom)
    val cx = WebMercatorMath.lonToWorldX(centerLon, worldSize)
    val cy = WebMercatorMath.latToWorldY(centerLat, worldSize)
    val halfW = width / 2.0
    val halfH = height / 2.0
    val west = WebMercatorMath.worldXToLon(cx - halfW, worldSize)
    val east = WebMercatorMath.worldXToLon(cx + halfW, worldSize)
    val north = WebMercatorMath.worldYToLat(cy - halfH, worldSize)
    val south = WebMercatorMath.worldYToLat(cy + halfH, worldSize)
    return GisBounds(west, south, east, north)
}

/**
 * Renders visible Web Mercator tiles on the DrawScope with parent-tile fallback and
 * cartographic road grid fallback when tiles are loading.
 */
private fun DrawScope.drawSlippyTiles(
    styleKey: String,
    centerLat: Double,
    centerLon: Double,
    zoom: Double,
    bearingDeg: Double,
    tileRepository: OsmTileRepository,
    tilePaint: Paint,
    isDark: Boolean,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val tileZoom = floor(zoom).toInt().coerceIn(3, 19)
    val scale = 2.0.pow(zoom - tileZoom).toFloat()
    val scaledTileSize = (TILE_SIZE * scale).toFloat()
    val numTiles = 1 shl tileZoom
    val worldTileSize = TILE_SIZE * numTiles

    val centerWorldX = WebMercatorMath.lonToWorldX(centerLon, worldTileSize)
    val centerWorldY = WebMercatorMath.latToWorldY(centerLat, worldTileSize)

    val centerTileX = floor(centerWorldX / TILE_SIZE).toInt()
    val centerTileY = floor(centerWorldY / TILE_SIZE).toInt()

    // Only ask for tiles visible within the rotated viewport, not an oversized
    // square of hundreds of images on every redraw.
    val rad = Math.toRadians(bearingDeg)
    val extentX = (abs(cos(rad)) * size.width + abs(sin(rad)) * size.height) / 2f
    val extentY = (abs(sin(rad)) * size.width + abs(cos(rad)) * size.height) / 2f
    val radiusTiles = ceil(max(extentX, extentY) / scaledTileSize).toInt() + 1

    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.save()
        native.translate(size.width / 2f, size.height / 2f)
        if (bearingDeg != 0.0) {
            native.rotate(-bearingDeg.toFloat())
        }

        // Schedule the center tiles first: on a slow mobile connection the
        // current street must become visible before distant screen corners.
        val centerFirst = (-radiusTiles..radiusTiles).flatMap { dy ->
            (-radiusTiles..radiusTiles).map { dx -> dx to dy }
        }.sortedBy { (dx, dy) -> dx * dx + dy * dy }
        for ((dx, dy) in centerFirst) {
            val ty = centerTileY + dy
            if (ty < 0 || ty >= numTiles) continue
                val txRaw = centerTileX + dx
                val tx = ((txRaw % numTiles) + numTiles) % numTiles

                val left = ((txRaw * TILE_SIZE - centerWorldX) * scale).toFloat()
                val top = ((ty * TILE_SIZE - centerWorldY) * scale).toFloat()
                val dstRect = RectF(left, top, left + scaledTileSize + 0.6f, top + scaledTileSize + 0.6f)
                if (dstRect.right < -extentX || dstRect.left > extentX ||
                    dstRect.bottom < -extentY || dstRect.top > extentY) continue

                val bmp = tileRepository.getTileOrRequest(styleKey, tileZoom, tx, ty, scope)
                if (bmp != null) {
                    native.drawBitmap(bmp, null, dstRect, tilePaint)
                } else {
                    // Try parent tile fallback for seamless zooming
                    val parent = tileRepository.findParentTile(styleKey, tileZoom, tx, ty)
                    if (parent != null) {
                        native.drawBitmap(parent.bitmap, parent.srcRect, dstRect, tilePaint)
                    } else {
                        // Keep the neutral background. A decorative grid is not
                        // a real road map and looked like spurious diagonal roads.
                    }
                }
        }
        native.restore()
    }
}

private data class ParentTileSlice(
    val bitmap: Bitmap,
    val srcRect: Rect
)

private class OsmTileRepository(
    context: Context,
    private val onTileLoaded: () -> Unit,
    private val onTileFailed: () -> Unit
) {
    private val memoryCache = LruCache<String, Bitmap>(160)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastFailure = ConcurrentHashMap<String, Long>()
    private val concurrentDownloads = Semaphore(3)
    private val cartoApiKey = context.getSharedPreferences("viavox_prefs", Context.MODE_PRIVATE)
        .getString("carto_basemaps_key", "").orEmpty()

    fun retryFailedTiles() { lastFailure.clear() }
    private val cacheDir = File(context.cacheDir, "viavox_osm_tiles").apply { mkdirs() }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    private fun key(style: String, z: Int, x: Int, y: Int): String = "$style/$z/$x/$y"

    fun getTileOrRequest(
        style: String,
        z: Int,
        x: Int,
        y: Int,
        scope: kotlinx.coroutines.CoroutineScope
    ): Bitmap? {
        val k = key(style, z, x, y)
        memoryCache.get(k)?.let { return it }
        val previousFailure = lastFailure[k]
        if (previousFailure != null &&
            android.os.SystemClock.elapsedRealtime() - previousFailure < 45_000L) return null
        // Keep a small viewport queue; each downloaded tile triggers a
        // repaint and schedules the next nearest still-visible tile.
        if (inFlight.size >= 9) return null
        if (inFlight.add(k)) {
            scope.launch(Dispatchers.IO) {
                try {
                    concurrentDownloads.withPermit {
                        val bmp = loadFromDiskOrNetwork(style, z, x, y)
                        if (bmp != null) {
                            memoryCache.put(k, bmp)
                            lastFailure.remove(k)
                            withContext(Dispatchers.Main) { onTileLoaded() }
                        } else {
                            lastFailure[k] = android.os.SystemClock.elapsedRealtime()
                            withContext(Dispatchers.Main) { onTileFailed() }
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    lastFailure[k] = android.os.SystemClock.elapsedRealtime()
                    android.util.Log.w("ViaVoxMapTiles", "Tile loading failed", error)
                    withContext(Dispatchers.Main) { onTileFailed() }
                } finally {
                    inFlight.remove(k)
                }
            }
        }
        return null
    }

    fun findParentTile(style: String, z: Int, x: Int, y: Int): ParentTileSlice? {
        for (step in 1..3) {
            val pz = z - step
            if (pz < 3) break
            val px = x shr step
            val py = y shr step
            val parentBmp = memoryCache.get(key(style, pz, px, py)) ?: continue
            val factor = 1 shl step
            val subW = parentBmp.width / factor
            val subH = parentBmp.height / factor
            val rx = x - (px shl step)
            val ry = y - (py shl step)
            return ParentTileSlice(
                bitmap = parentBmp,
                srcRect = Rect(rx * subW, ry * subH, (rx + 1) * subW, (ry + 1) * subH)
            )
        }
        return null
    }

    private fun loadFromDiskOrNetwork(style: String, z: Int, x: Int, y: Int): Bitmap? {
        val diskFile = File(cacheDir, "${style}_${z}_${x}_${y}.png")
        if (diskFile.isFile && diskFile.length() > 100L) {
            BitmapFactory.decodeFile(diskFile.absolutePath)?.let { return it }
        }
        val urls = MapTileSourcePolicy.urls(style, z, x, y, cartoApiKey)
        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent",
                        "ViaVox/0.10.7 (+https://github.com/socrateslever1/ViaVox)")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                runCatching { diskFile.writeBytes(bytes) }
                                return bmp
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                android.util.Log.w("ViaVoxMapTiles", "Map provider unavailable", error)
            }
        }
        return null
    }
}

private data class AdminBoundaryPolygon(
    val color: Color,
    val points: List<Pair<Double, Double>>
)

private fun loadAdminBoundaries(context: Context): List<AdminBoundaryPolygon> {
    return try {
        val raw = context.assets.open("bairros_manaus.geojson").bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        val features = root.optJSONArray("features") ?: return emptyList()
        val result = mutableListOf<AdminBoundaryPolygon>()
        for (i in 0 until features.length()) {
            val feat = features.optJSONObject(i) ?: continue
            val props = feat.optJSONObject("properties")
            val colorHex = props?.optString("color", "#20DDC3").orEmpty()
            val parsedColor = runCatching {
                Color(android.graphics.Color.parseColor(colorHex))
            }.getOrDefault(Color(0xFF20DDC3))
            val geom = feat.optJSONObject("geometry") ?: continue
            val type = geom.optString("type")
            val coords = geom.optJSONArray("coordinates") ?: continue
            if (type == "Polygon" && coords.length() > 0) {
                val ring = coords.optJSONArray(0) ?: continue
                val pts = mutableListOf<Pair<Double, Double>>()
                for (j in 0 until ring.length()) {
                    val pair = ring.optJSONArray(j) ?: continue
                    pts.add(pair.optDouble(1) to pair.optDouble(0))
                }
                if (pts.size >= 3) result.add(AdminBoundaryPolygon(parsedColor, pts))
            } else if (type == "MultiPolygon") {
                for (p in 0 until coords.length()) {
                    val poly = coords.optJSONArray(p) ?: continue
                    val ring = poly.optJSONArray(0) ?: continue
                    val pts = mutableListOf<Pair<Double, Double>>()
                    for (j in 0 until ring.length()) {
                        val pair = ring.optJSONArray(j) ?: continue
                        pts.add(pair.optDouble(1) to pair.optDouble(0))
                    }
                    if (pts.size >= 3) result.add(AdminBoundaryPolygon(parsedColor, pts))
                }
            }
        }
        result
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Three genuinely different top-down GPS symbols, facing screen-up before
 * rotation: car, two-wheel motorcycle and walking person.
 */
private fun createVehicleLocationMarkerBitmap(
    bearing: Float,
    showHeading: Boolean,
    mode: VehicleMode
): Bitmap {
    val side = 96
    val center = side / 2f
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val white = android.graphics.Color.WHITE
    val navy = android.graphics.Color.rgb(9, 27, 42)
    val teal = android.graphics.Color.rgb(32, 221, 195)
    val accent = when (mode) {
        VehicleMode.MOTORCYCLE -> teal
        VehicleMode.CAR -> android.graphics.Color.rgb(35, 146, 255)
        VehicleMode.WALKING -> android.graphics.Color.rgb(255, 180, 76)
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
    val haloRadius = if (mode == VehicleMode.MOTORCYCLE) 31f else 36f
    val discRadius = if (mode == VehicleMode.MOTORCYCLE) 25f else 30f
    canvas.drawCircle(center, center, haloRadius, paint(android.graphics.Color.argb(36, 32, 221, 195)))
    canvas.drawCircle(center, center, discRadius, paint(android.graphics.Color.argb(230, 9, 27, 42)))
    canvas.drawCircle(center, center, discRadius - 1f,
        paint(android.graphics.Color.argb(160, 32, 221, 195), 1.3f))
    canvas.save()
    if (showHeading) canvas.rotate(bearing, center, center)
    canvas.translate(center, center)

    if (showHeading) {
        val triangle = Path().apply {
            moveTo(0f, -39f); lineTo(-5f, -30f); lineTo(5f, -30f); close()
        }
        canvas.drawPath(triangle, paint(accent))
    }
    when (mode) {
        VehicleMode.MOTORCYCLE -> {
            // ONE solid top-down motorcycle silhouette, facing north.
            // No hollow wheels, white rings or rider circles: they looked like
            // several overlapping location markers on the actual Samsung.
            val rubber = paint(android.graphics.Color.rgb(2, 18, 30))
            val darkTeal = paint(android.graphics.Color.rgb(0, 110, 108))
            // Two subtle elongated tires are part of one compact silhouette.
            canvas.drawRoundRect(RectF(-4.8f, -22f, 4.8f, -13f), 3f, 3f, rubber)
            canvas.drawRoundRect(RectF(-4.8f, 14f, 4.8f, 23f), 3f, 3f, rubber)
            // Integrated chassis and fairing, clearly distinct from car puck.
            val fairing = Path().apply {
                moveTo(0f, -20f)
                cubicTo(-7f, -17f, -8f, -10f, -8f, -1f)
                lineTo(-7f, 13f)
                quadTo(0f, 20f, 7f, 13f)
                lineTo(8f, -1f)
                cubicTo(8f, -10f, 7f, -17f, 0f, -20f)
                close()
            }
            canvas.drawPath(fairing, paint(white))
            canvas.drawPath(fairing, paint(accent, 2f))
            canvas.drawLine(-14f, -9f, 14f, -9f, paint(accent, 3f))
            canvas.drawRoundRect(RectF(-4f, -15f, 4f, -10f), 2f, 2f, darkTeal)
            canvas.drawRoundRect(RectF(-4.6f, -2f, 4.6f, 12f), 3f, 3f, rubber)
            canvas.drawLine(-5f, 14f, 5f, 14f, paint(accent, 1.8f))
        }
        VehicleMode.CAR -> {
            canvas.drawRoundRect(RectF(-22f, -19f, -15f, -4f), 3f, 3f, paint(navy))
            canvas.drawRoundRect(RectF(15f, -19f, 22f, -4f), 3f, 3f, paint(navy))
            canvas.drawRoundRect(RectF(-22f, 10f, -15f, 24f), 3f, 3f, paint(navy))
            canvas.drawRoundRect(RectF(15f, 10f, 22f, 24f), 3f, 3f, paint(navy))
            canvas.drawRoundRect(RectF(-17f, -29f, 17f, 29f), 9f, 9f, paint(accent))
            canvas.drawRoundRect(RectF(-13f, -22f, 13f, -5f), 4f, 4f, paint(navy))
            canvas.drawRoundRect(
                RectF(-12f, 6f, 12f, 23f),
                4f, 4f, paint(android.graphics.Color.rgb(35, 82, 119))
            )
            canvas.drawRoundRect(RectF(-17f, -29f, 17f, 29f), 9f, 9f, paint(white, 1.8f))
        }
        VehicleMode.WALKING -> {
            canvas.drawCircle(0f, -19f, 6f, paint(white))
            canvas.drawLine(0f, -12f, 0f, 9f, paint(white, 5f))
            canvas.drawLine(0f, -5f, -15f, 6f, paint(white, 4f))
            canvas.drawLine(0f, -5f, 15f, 1f, paint(white, 4f))
            canvas.drawLine(0f, 9f, -12f, 23f, paint(white, 4f))
            canvas.drawLine(0f, 9f, 12f, 23f, paint(white, 4f))
            canvas.drawRoundRect(RectF(-6f, -10f, 6f, 9f), 5f, 5f, paint(accent))
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

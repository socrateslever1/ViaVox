package com.example.viavox.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.viavox.MainActivity
import com.example.viavox.R
import com.example.viavox.data.NominatimService
import com.example.viavox.data.PoiDiscoveryService
import com.example.viavox.data.TrackingRepository
import com.example.viavox.model.RelativeDirection
import com.example.viavox.model.ViaVoxConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class ViaVoxTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var poiJob: Job? = null
    private var lastPoiCheckTimestamp = 0L
    private val poiAnnouncements = PoiAnnouncementCoordinator()

    private lateinit var ttsManager: TextToSpeechManager
    private lateinit var locationTracker: LocationTrackingManager
    private lateinit var nominatimService: NominatimService
    private lateinit var poiService: PoiDiscoveryService
    private lateinit var roadEngine: RoadDetectionEngine
    private lateinit var safetyAlertEngine: SafetyAlertEngine
    private var safetyJob: Job? = null

    private var currentConfig = ViaVoxConfig()
    private var isTrackingRunning = false
    private var lastSpeechTimestamp = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Creating ViaVoxTrackingService")

        val prefs = getSharedPreferences("viavox_prefs", Context.MODE_PRIVATE)
        currentConfig = ViaVoxConfig.loadFrom(prefs)
        val initialVoiceEnabled = prefs.getBoolean("voice_enabled", true)

        ttsManager = TextToSpeechManager(
            context = this,
            onSpeakingStateChanged = { TrackingRepository.updateSpeaking(it) },
            onVoiceIssue = { TrackingRepository.updateVoiceIssue(it) }
        )
        ttsManager.setVoiceEnabled(initialVoiceEnabled)
        ttsManager.setSpeechRate(currentConfig.speechRate)
        ttsManager.franciscaEndpoint = currentConfig.franciscaBrokerUrl.ifBlank { null }
        ttsManager.franciscaAccessToken = currentConfig.franciscaAccessToken.ifBlank { null }
        ttsManager.setVoiceSelection(currentConfig.voiceProvider, currentConfig.nativeVoiceId)

        nominatimService = NominatimService()
        nominatimService.googleApiKey = currentConfig.googleMapsApiKey
        poiService = PoiDiscoveryService()
        safetyAlertEngine = SafetyAlertEngine(
            onAlert = { speechMessage ->
                if (TrackingRepository.state.value.isVoiceEnabled && isTrackingRunning) {
                    speakIfAllowed(speechMessage, forceRepeat = false)
                }
            }
        )

        roadEngine = RoadDetectionEngine(
            nominatimService = nominatimService,
            coroutineScope = serviceScope,
            onPlaceUpdated = { place ->
                TrackingRepository.updatePlace(place)
                updateNotification(place.road, place.neighbourhood)
            },
            onRoadAnnounceNeeded = { speechMessage, _ ->
                if (TrackingRepository.state.value.isVoiceEnabled) {
                    poiAnnouncements.roadWasAnnounced(System.currentTimeMillis())
                }
                speakIfAllowed(speechMessage, forceRepeat = false)
            },
            onStatusMessage = { status ->
                TrackingRepository.updateStatus(status)
            }
        )
        roadEngine.enablePoliceCircumscription = currentConfig.enablePoliceCircumscription

        locationTracker = LocationTrackingManager(
            context = this,
            onLocationUpdated = { location ->
                onLocationUpdateReceived(location)
            },
            onError = { err ->
                TrackingRepository.updateStatus(err)
            }
        )

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand received action: $action")

        when (action) {
            ACTION_START -> startForegroundTracking()
            ACTION_STOP -> stopForegroundTracking()
            ACTION_REPEAT_SPEECH -> repeatCurrentSpeech()
            ACTION_TEST_VOICE -> ttsManager.testVoice()
            ACTION_NAV_GUIDANCE -> {
                val instruction = intent?.getStringExtra(EXTRA_NAV_TEXT).orEmpty().trim()
                if (instruction.isNotBlank() && isTrackingRunning) {
                    serviceScope.launch {
                        // A street/neighborhood announcement is never interrupted by routing.
                        val waitMs = (lastSpeechTimestamp + 3_500L - System.currentTimeMillis()).coerceAtLeast(0L)
                        if (waitMs > 0) delay(waitMs)
                        if (isTrackingRunning) speakIfAllowed(instruction,forceRepeat = true)
                    }
                }
            }
            ACTION_UPDATE_CONFIG -> {
                val prefs = getSharedPreferences("viavox_prefs", Context.MODE_PRIVATE)
                currentConfig = ViaVoxConfig.loadFrom(prefs)
                val voiceEnabled = prefs.getBoolean("voice_enabled", true)
                ttsManager.setVoiceEnabled(voiceEnabled)
                ttsManager.setSpeechRate(currentConfig.speechRate)
                ttsManager.franciscaEndpoint = currentConfig.franciscaBrokerUrl.ifBlank { null }
                ttsManager.franciscaAccessToken = currentConfig.franciscaAccessToken.ifBlank { null }
                ttsManager.setVoiceSelection(currentConfig.voiceProvider, currentConfig.nativeVoiceId)
                nominatimService.googleApiKey = currentConfig.googleMapsApiKey
                roadEngine.enablePoliceCircumscription = currentConfig.enablePoliceCircumscription
                TrackingRepository.updateConfig(currentConfig)
                TrackingRepository.updateVoiceEnabled(voiceEnabled)
            }
        }

        return START_NOT_STICKY
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun startForegroundTracking() {
        if (!hasLocationPermission()) {
            TrackingRepository.updateActive(false)
            TrackingRepository.updateStatus(getString(R.string.status_permission_denied))
            stopSelf()
            return
        }

        if (isTrackingRunning) return

        val initialNotification = buildNotification(getString(R.string.status_waiting_gps), "")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
            TrackingRepository.updateStatus(getString(R.string.status_permission_denied))
            stopSelf()
            return
        }

        // Fused Location delivers callbacks without holding a persistent CPU wake lock.
        isTrackingRunning = true
        TrackingRepository.updateActive(true)
        TrackingRepository.updateStatus(getString(R.string.status_waiting_gps))

        roadEngine.reset()
        lastPoiCheckTimestamp = 0L
        poiAnnouncements.reset()
        TrackingRepository.updatePoiStatus("Buscando pontos de referência próximos...")
        locationTracker.startTracking()
    }

    private fun stopForegroundTracking() {
        Log.d(TAG, "Stopping foreground tracking")
        isTrackingRunning = false
        locationTracker.stopTracking()
        ttsManager.stop()
        roadEngine.reset()
        poiJob?.cancel()
        poiJob = null
        poiAnnouncements.reset()
        safetyJob?.cancel()
        safetyJob = null
        TrackingRepository.updateSafetyFeatures(emptyList())
        TrackingRepository.updatePoi(null, null)
        TrackingRepository.updatePoiStatus("Referências pausadas")

        TrackingRepository.updateActive(false)
        TrackingRepository.updateStatus(getString(R.string.status_location_paused))

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping foreground: ${e.message}")
        }

        stopSelf()
    }

    private fun onLocationUpdateReceived(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        val accuracy = if (location.hasAccuracy()) location.accuracy.toInt() else null
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        val bearing = if (location.hasBearing()) location.bearing else 0f
        val hasBearing = location.hasBearing()

        TrackingRepository.updateCoordinates(lat, lon, accuracy, speedKmh, bearing, hasBearing)

        // Identify current street or street transition
        roadEngine.onLocationUpdate(location)

        // POI discovery is independent of the road-announcement cooldown:
        // 25-second scan delays previously skipped whole landmarks at driving speeds.
        tryAnnouncePendingPoi(location)
        val now = System.currentTimeMillis()
        val intervalMs = if (speedKmh >= 5f) 5_000L else 12_000L
        val accuracyMeters = if (location.hasAccuracy()) location.accuracy else 100f
        if (accuracyMeters > 40f) {
            TrackingRepository.updatePoiStatus("Aguardando GPS mais preciso para reconhecer referências")
            return
        }
        if (now - lastPoiCheckTimestamp < intervalMs || poiJob?.isActive == true) return

        lastPoiCheckTimestamp = now
        poiJob = serviceScope.launch {
            val poi = poiService.findNearbyPoi(
                userLat = lat,
                userLon = lon,
                config = currentConfig,
                gpsAccuracyMeters = accuracyMeters,
                speedMps = if (location.hasSpeed()) location.speed else 0f,
                bearingDegrees = if (location.hasBearing()) location.bearing else null
            )
            if (!isTrackingRunning) return@launch
            if (poi != null) {
                poiAnnouncements.offer(poi, System.currentTimeMillis())
                TrackingRepository.updatePoiStatus("Referência detectada: ${poi.name}")
                // Permit current street-change geocoding and TTS to finish first.
                delay(1_800L)
                if (isTrackingRunning) tryAnnouncePendingPoi(location)
            } else {
                val failure = poiService.lastFailure
                TrackingRepository.updatePoiStatus(
                    failure ?: if (poiService.cachedCount == 0)
                        "Nenhum ponto cadastrado próximo no OpenStreetMap"
                    else "Procurando referências próximas na direção do percurso"
                )
            }
        }

        // 3. Safety camera & toll monitoring ahead on the road
        if (currentConfig.enableSafetyAlerts && (safetyJob == null || safetyJob?.isActive == false)) {
            safetyJob = serviceScope.launch {
                val features = safetyAlertEngine.evaluate(
                    userLat = lat,
                    userLon = lon,
                    bearing = bearing,
                    speedKmh = speedKmh,
                    enabled = currentConfig.enableSafetyAlerts
                )
                TrackingRepository.updateSafetyFeatures(features)
            }
        }
    }

    private fun tryAnnouncePendingPoi(location: Location) {
        if (!isTrackingRunning) return
        // Use the *latest* accepted GPS fix, not coordinates captured before
        // a potentially slow HTTP query. A car can cover 50m while waiting.
        val position = TrackingRepository.state.value
        val latitude = if (position.hasLocationFix) position.latitude else location.latitude
        val longitude = if (position.hasLocationFix) position.longitude else location.longitude
        val speedMps = if (position.hasLocationFix) position.speedKmh / 3.6f else 0f
        val accuracyMeters = position.accuracyMeters?.toFloat() ?: 100f
        val bearingDegrees = position.bearing.takeIf { position.hasBearing }
        val poi = poiAnnouncements.ready(
            nowMs = System.currentTimeMillis(),
            poiIntervalSeconds = currentConfig.minIntervalSeconds,
            ttsBusy = position.isSpeaking,
            voiceEnabled = position.isVoiceEnabled,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            speedMps = speedMps,
            bearingDegrees = bearingDegrees
        ) ?: return

        // Polygon/relation centroid is not precise enough to claim a particular
        // side of the road. Side announcements are restricted to true OSM nodes.
        val relativeDir = if (poi.id.startsWith("osm_node_") &&
            bearingDegrees != null && speedMps >= 2.5f && accuracyMeters <= 20f
        ) {
            poi.calculateRelativeDirection(
                latitude, longitude, bearingDegrees, speedMps
            )
        } else RelativeDirection.NEARBY

        val speech = when (relativeDir) {
            RelativeDirection.RIGHT -> getString(R.string.tts_poi_right, poi.name)
            RelativeDirection.LEFT -> getString(R.string.tts_poi_left, poi.name)
            RelativeDirection.NEARBY -> getString(R.string.tts_poi_nearby, poi.name)
        }
        // Mark as announced only at the moment it actually enters the TTS system.
        poiAnnouncements.poiWasAnnounced(System.currentTimeMillis())
        poiService.markAnnounced(poi)
        TrackingRepository.updatePoi(poi, relativeDir)
        TrackingRepository.updatePoiStatus("Enviando à voz: ${poi.name}")
        speakIfAllowed(speech, forceRepeat = false)
    }

    private fun speakIfAllowed(message: String, forceRepeat: Boolean) {
        val isVoiceEnabled = TrackingRepository.state.value.isVoiceEnabled
        if (!isVoiceEnabled) return

        lastSpeechTimestamp = System.currentTimeMillis()
        ttsManager.speak(message, forceRepeat = forceRepeat)
    }

    private fun repeatCurrentSpeech() {
        val currentPlace = TrackingRepository.state.value.place
        if (currentPlace != null && currentPlace.hasValidRoad()) {
            val message = currentPlace.toSpeechMessage(isTransition = false)
            speakIfAllowed(message, forceRepeat = true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(road: String, neighbourhood: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ViaVoxTrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val content = if (neighbourhood.isNotBlank() && neighbourhood != "Bairro não identificado") {
            "$road · $neighbourhood"
        } else {
            road
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_stop_action),
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(road: String, neighbourhood: String) {
        if (!isTrackingRunning) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(road, neighbourhood))
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destroying ViaVoxTrackingService")
        isTrackingRunning = false
        locationTracker.stopTracking()
        ttsManager.shutdown()
        roadEngine.reset()
        poiJob?.cancel()
        poiJob = null
        poiAnnouncements.reset()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ViaVoxTrackingService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "viavox_tracking_channel"

        const val ACTION_START = "com.example.viavox.ACTION_START"
        const val ACTION_STOP = "com.example.viavox.ACTION_STOP"
        const val ACTION_REPEAT_SPEECH = "com.example.viavox.ACTION_REPEAT_SPEECH"
        const val ACTION_TEST_VOICE = "com.example.viavox.ACTION_TEST_VOICE"
        const val ACTION_NAV_GUIDANCE = "com.example.viavox.ACTION_NAV_GUIDANCE"
        private const val EXTRA_NAV_TEXT = "navigation_text"
        const val ACTION_UPDATE_CONFIG = "com.example.viavox.ACTION_UPDATE_CONFIG"

        fun start(context: Context) {
            val intent = Intent(context, ViaVoxTrackingService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ViaVoxTrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun repeatSpeech(context: Context) {
            val intent = Intent(context, ViaVoxTrackingService::class.java).apply {
                action = ACTION_REPEAT_SPEECH
            }
            context.startService(intent)
        }

        fun announceGuidance(context: Context, text: String) {
            context.startService(Intent(context,ViaVoxTrackingService::class.java).apply {
                action = ACTION_NAV_GUIDANCE
                putExtra(EXTRA_NAV_TEXT,text.take(350))
            })
        }

        fun testVoice(context: Context) {
            context.startService(Intent(context, ViaVoxTrackingService::class.java).apply {
                action = ACTION_TEST_VOICE
            })
        }

        fun updateConfig(context: Context) {
            val intent = Intent(context, ViaVoxTrackingService::class.java).apply {
                action = ACTION_UPDATE_CONFIG
            }
            context.startService(intent)
        }
    }
}

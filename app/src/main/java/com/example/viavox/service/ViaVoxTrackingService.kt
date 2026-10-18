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

class ViaVoxTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var poiJob: Job? = null
    private var lastPoiCheckTimestamp = 0L

    private lateinit var ttsManager: TextToSpeechManager
    private lateinit var locationTracker: LocationTrackingManager
    private lateinit var nominatimService: NominatimService
    private lateinit var poiService: PoiDiscoveryService
    private lateinit var roadEngine: RoadDetectionEngine

    private var currentConfig = ViaVoxConfig()
    private var isTrackingRunning = false
    private var lastSpeechTimestamp = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Creating ViaVoxTrackingService")

        val prefs = getSharedPreferences("viavox_prefs", Context.MODE_PRIVATE)
        currentConfig = ViaVoxConfig.loadFrom(prefs)
        val initialVoiceEnabled = prefs.getBoolean("voice_enabled", true)

        ttsManager = TextToSpeechManager(this) { isSpeaking ->
            TrackingRepository.updateSpeaking(isSpeaking)
        }
        ttsManager.setVoiceEnabled(initialVoiceEnabled)
        ttsManager.setSpeechRate(currentConfig.speechRate)
        ttsManager.franciscaEndpoint = currentConfig.franciscaBrokerUrl.ifBlank { null }
        ttsManager.franciscaAccessToken = currentConfig.franciscaAccessToken.ifBlank { null }
        ttsManager.setVoiceSelection(currentConfig.voiceProvider, currentConfig.nativeVoiceId)

        nominatimService = NominatimService()
        poiService = PoiDiscoveryService()

        roadEngine = RoadDetectionEngine(
            nominatimService = nominatimService,
            coroutineScope = serviceScope,
            onPlaceUpdated = { place ->
                TrackingRepository.updatePlace(place)
                updateNotification(place.road, place.neighbourhood)
            },
            onRoadAnnounceNeeded = { speechMessage, _ ->
                speakIfAllowed(speechMessage, forceRepeat = false)
            },
            onStatusMessage = { status ->
                TrackingRepository.updateStatus(status)
            }
        )

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
            ACTION_UPDATE_CONFIG -> {
                val prefs = getSharedPreferences("viavox_prefs", Context.MODE_PRIVATE)
                currentConfig = ViaVoxConfig.loadFrom(prefs)
                val voiceEnabled = prefs.getBoolean("voice_enabled", true)
                ttsManager.setVoiceEnabled(voiceEnabled)
                ttsManager.setSpeechRate(currentConfig.speechRate)
                ttsManager.franciscaEndpoint = currentConfig.franciscaBrokerUrl.ifBlank { null }
                ttsManager.franciscaAccessToken = currentConfig.franciscaAccessToken.ifBlank { null }
                ttsManager.setVoiceSelection(currentConfig.voiceProvider, currentConfig.nativeVoiceId)
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

        // Check for nearby landmark POIs if speech cooldown allows
        val now = System.currentTimeMillis()
        val speechIntervalMs = currentConfig.minIntervalSeconds * 1000L
        // Nearby-place lookup is lower priority than street announcements.
        // Never start a new network operation on every GPS fix.
        val poiIntervalMs = if (speedKmh >= 5f) 25_000L else 90_000L
        if (now - lastPoiCheckTimestamp >= poiIntervalMs &&
            now - lastSpeechTimestamp >= speechIntervalMs &&
            !TrackingRepository.state.value.isSpeaking &&
            poiJob?.isActive != true
        ) {
            lastPoiCheckTimestamp = now
            poiJob = serviceScope.launch {
                val poi = poiService.findNearbyPoi(lat, lon, currentConfig)
                if (poi != null) {
                    val relativeDir = poi.calculateRelativeDirection(
                        userLat = lat,
                        userLon = lon,
                        vehicleBearing = bearing,
                        speedMps = if (location.hasSpeed()) location.speed else 0f
                    )

                    TrackingRepository.updatePoi(poi, relativeDir)

                    val poiMessage = when (relativeDir) {
                        RelativeDirection.RIGHT -> getString(R.string.tts_poi_right, poi.name)
                        RelativeDirection.LEFT -> getString(R.string.tts_poi_left, poi.name)
                        RelativeDirection.NEARBY -> getString(R.string.tts_poi_nearby, poi.name)
                    }

                    speakIfAllowed(poiMessage, forceRepeat = false)
                }
            }
        }
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

        fun updateConfig(context: Context) {
            val intent = Intent(context, ViaVoxTrackingService::class.java).apply {
                action = ACTION_UPDATE_CONFIG
            }
            context.startService(intent)
        }
    }
}

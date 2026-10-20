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
import com.example.viavox.data.NavigationNetworkService
import com.example.viavox.model.NavigationRoute
import com.example.viavox.data.NominatimService
import com.example.viavox.data.PoiDiscoveryService
import com.example.viavox.data.TrackingRepository
import com.example.viavox.data.TripRecordingRepository
import com.example.viavox.model.TripPoint
import com.example.viavox.model.RelativeDirection
import com.example.viavox.model.ViaVoxConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
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

    private lateinit var tripRecorder: ServiceTripRecorder
    private lateinit var recorderReady: Deferred<com.example.viavox.data.TripRecordingState>
    private lateinit var ttsManager: TextToSpeechManager
    private lateinit var speechQueue: SpeechOrchestrator
    private lateinit var locationTracker: LocationTrackingManager
    private lateinit var nominatimService: NominatimService
    private lateinit var poiService: PoiDiscoveryService
    private lateinit var roadEngine: RoadDetectionEngine
    private lateinit var safetyAlertEngine: SafetyAlertEngine
    private var safetyJob: Job? = null
    private lateinit var navigationStore: NavigationSessionStore
    private val navigationNetwork = NavigationNetworkService()
    private var navigationEngine: NavigationSessionEngine? = null
    private var rerouteJob: Job? = null
    private var navigationEpoch = 0L
    private var lastNavFixAt = 0L

    private var currentConfig = ViaVoxConfig()
    private var isTrackingRunning = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Creating ViaVoxTrackingService")

        val prefs = getSharedPreferences("viavox_prefs", Context.MODE_PRIVATE)
        currentConfig = ViaVoxConfig.loadFrom(prefs)
        val initialVoiceEnabled = prefs.getBoolean("voice_enabled", true)
        navigationStore = NavigationSessionStore(this)
        tripRecorder = ServiceTripRecorder(this)
        recorderReady = serviceScope.async(Dispatchers.IO) { tripRecorder.restore() }

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
        speechQueue = SpeechOrchestrator(
            play = { text, finished -> ttsManager.speakManaged(
                SpeechTextNormalizer.normalize(text), finished
            ) },
            canPlay = { event ->
                if (event.kind != SpeechKind.POI) true
                else {
                    val latest = TrackingRepository.state.value
                    val poiId = event.key.removePrefix("poi:")
                    latest.hasLocationFix && currentConfig.narratePoiEnabled &&
                        poiService.isStillRelevant(
                            poiId, latest.latitude,latest.longitude,
                            latest.accuracyMeters?.toFloat() ?: 100f,
                            latest.speedKmh / 3.6f,
                            latest.bearing.takeIf { latest.hasBearing },
                            latest.place?.road.orEmpty()
                        )
                }
            }
        )
        speechQueue.setEnabled(initialVoiceEnabled)

        nominatimService = NominatimService()
        nominatimService.googleApiKey = currentConfig.googleMapsApiKey
        poiService = PoiDiscoveryService()
        safetyAlertEngine = SafetyAlertEngine(
            onAlert = { speechMessage ->
                if (TrackingRepository.state.value.isVoiceEnabled && isTrackingRunning) {
                    speakIfAllowed(speechMessage, forceRepeat = false, kind = SpeechKind.SAFETY)
                }
            }
        ).also {
            it.loadOfficialAlertsFromAssets(this)
        }

        roadEngine = RoadDetectionEngine(
            nominatimService = nominatimService,
            coroutineScope = serviceScope,
            onPlaceUpdated = { place ->
                TrackingRepository.updatePlace(place)
                updateNotification(place.road, place.neighbourhood)
            },
            // Legacy callback retained for existing unit tests; service plays
            // only the structured transition to avoid double announcements.
            onRoadAnnounceNeeded = { _, _ -> },
            onGeographicTransition = { transition ->
                if (TrackingRepository.state.value.isVoiceEnabled) {
                    poiAnnouncements.roadWasAnnounced(System.currentTimeMillis())
                }
                speakIfAllowed(
                    transition.sentence(), false,
                    SpeechKind.GEOGRAPHY, "geo:" + transition.dedupeKey()
                )
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
        // Navigation ownership is in this foreground service. Restoring an
        // interrupted activity must never create two competing voices.
        val startupEpoch = navigationEpoch
        serviceScope.launch {
            val previous = withContext(Dispatchers.IO) { navigationStore.read() }
            if (startupEpoch == navigationEpoch && navigationEngine == null &&
                previous != null) {
                installNavigation(previous)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand received action: $action")
        if (intent == null) {
            // START_STICKY is only for explicit user sessions. Never wake GPS
            // after a restart for an ordinary map-only foreground session.
            val prefs = getSharedPreferences("viavox_prefs",Context.MODE_PRIVATE)
            val tripPrefs = getSharedPreferences("viavox_trip_session",Context.MODE_PRIVATE)
            val mayRestore = BackgroundSessionPolicy.mayRestore(
                hasSavedNavigation = java.io.File(
                    filesDir,"viavox_active_navigation.json"
                ).exists(),
                hasUnfinishedRecording = tripPrefs.getLong("active_trip_id",0L) > 0L,
                backgroundVoice = currentConfig.backgroundVoiceEnabled,
                manuallyPaused = prefs.getBoolean("tracking_manually_paused",false)
            )
            if (!mayRestore) {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        when (action) {
            ACTION_START -> startForegroundTracking()
            ACTION_RECORD_START -> {
                if (isTrackingRunning) serviceScope.launch {
                    withContext(Dispatchers.IO) {
                        recorderReady.await()
                        tripRecorder.start()
                    }
                    updateNotification(
                        TrackingRepository.state.value.place?.road.orEmpty(),
                        TrackingRepository.state.value.place?.neighbourhood.orEmpty()
                    )
                }
            }
            ACTION_RECORD_STOP -> serviceScope.launch {
                withContext(Dispatchers.IO) {
                    recorderReady.await()
                    tripRecorder.finish()
                }
                updateNotification(
                    TrackingRepository.state.value.place?.road.orEmpty(),
                    TrackingRepository.state.value.place?.neighbourhood.orEmpty()
                )
            }
            ACTION_NAV_START -> {
                startForegroundTracking()
                if (!isTrackingRunning) {
                    ActiveNavigationRepository.publish(ActiveNavigationState(
                        status = "Permita o GPS para iniciar a navegação"
                    ))
                    return START_NOT_STICKY
                }
                val requestEpoch = ++navigationEpoch
                serviceScope.launch {
                    val saved = withContext(Dispatchers.IO) { navigationStore.read() }
                    if (requestEpoch != navigationEpoch) return@launch
                    if (saved != null) installNavigation(saved)
                    else TrackingRepository.updateStatus("Não foi possível recuperar a rota solicitada.")
                }
            }
            ACTION_NAV_STOP -> finishNavigationSession()
            ACTION_STOP -> stopForegroundTracking()
            ACTION_REPEAT_SPEECH -> repeatCurrentSpeech()
            ACTION_TEST_VOICE -> speakIfAllowed(
                "ViaVox. Teste de voz em português.",true,SpeechKind.USER_REQUEST,"voice-test"
            )
            ACTION_NAV_GUIDANCE -> {
                val instruction = intent?.getStringExtra(EXTRA_NAV_TEXT).orEmpty().trim()
                if (instruction.isNotBlank() && isTrackingRunning) {
                    speakIfAllowed(instruction,true,SpeechKind.NAVIGATION,
                        "nav:" + instruction.lowercase())
                }
            }
            ACTION_UPDATE_CONFIG -> {
                val prefs = getSharedPreferences("viavox_prefs", Context.MODE_PRIVATE)
                currentConfig = ViaVoxConfig.loadFrom(prefs)
                val voiceEnabled = prefs.getBoolean("voice_enabled", true)
                ttsManager.setVoiceEnabled(voiceEnabled)
                speechQueue.setEnabled(voiceEnabled)
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

        val persistent = BackgroundSessionPolicy.keepAlive(
            navigation = ActiveNavigationRepository.state.value.navigating ||
                action == ACTION_NAV_START,
            recording = TripRecordingRepository.state.value.recording ||
                action == ACTION_RECORD_START,
            backgroundVoice = currentConfig.backgroundVoiceEnabled
        )
        return if (isTrackingRunning && persistent) START_STICKY else START_NOT_STICKY
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
        TrackingRepository.updatePoiStatus(
            if (currentConfig.narratePoiEnabled) "Buscando pontos de referência próximos..."
            else "Referências silenciadas nas configurações"
        )
        locationTracker.startTracking()
    }

    private fun stopForegroundTracking() {
        Log.d(TAG, "Stopping foreground tracking")
        isTrackingRunning = false
        locationTracker.stopTracking()
        finishNavigationSession()
        speechQueue.cancelAll()
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

        serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    recorderReady.await()
                    tripRecorder.finish()
                }
            } catch (e: Exception) {
                Log.e(TAG,"Could not finalize trip before stopping",e)
            } finally {
                stopSelf()
            }
        }
    }

    private fun onLocationUpdateReceived(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        val accuracy = if (location.hasAccuracy()) location.accuracy.toInt() else null
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        val bearing = if (location.hasBearing()) location.bearing else 0f
        val hasBearing = location.hasBearing()

        TrackingRepository.updateCoordinates(lat, lon, accuracy, speedKmh, bearing, hasBearing)
        if (TripRecordingRepository.state.value.recording) {
            val point = TripPoint(lat,lon,System.currentTimeMillis(),
                if (location.hasAccuracy()) location.accuracy else 100f)
            serviceScope.launch(Dispatchers.IO) {
                recorderReady.await()
                tripRecorder.append(point)
            }
        }

        // A route keeps advancing on the GPS service even if Compose is hidden.
        onNavigationFix(location)
        // Identify current street or street transition
        roadEngine.onLocationUpdate(location)

        // POI discovery is independent of the road-announcement cooldown:
        // 25-second scan delays previously skipped whole landmarks at driving speeds.
        if (currentConfig.narratePoiEnabled) tryAnnouncePendingPoi(location)
        val now = System.currentTimeMillis()
        val intervalMs = if (speedKmh >= 5f) 5_000L else 12_000L
        val accuracyMeters = if (location.hasAccuracy()) location.accuracy else 100f
        if (accuracyMeters > 40f) {
            if (currentConfig.narratePoiEnabled) {
                TrackingRepository.updatePoiStatus("Aguardando GPS mais preciso para reconhecer referências")
            }
            return
        }
        // POI throttling must NEVER prevent an independent safety evaluation.
        if (currentConfig.narratePoiEnabled &&
            now - lastPoiCheckTimestamp >= intervalMs && poiJob?.isActive != true
        ) {
            lastPoiCheckTimestamp = now
            poiJob = serviceScope.launch {
            val poi = poiService.findNearbyPoi(
                userLat = lat,
                userLon = lon,
                config = currentConfig,
                gpsAccuracyMeters = accuracyMeters,
                speedMps = if (location.hasSpeed()) location.speed else 0f,
                bearingDegrees = if (location.hasBearing()) location.bearing else null,
                currentRoad = TrackingRepository.state.value.place?.road.orEmpty()
            )
            if (!isTrackingRunning || !currentConfig.narratePoiEnabled) return@launch
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

    private fun installNavigation(route: NavigationRoute) {
        if (route.coordinates.size < 2) return
        rerouteJob?.cancel()
        navigationEngine = NavigationSessionEngine(route)
        ActiveNavigationRepository.publish(ActiveNavigationState(
            navigating = true,route = route,
            instruction = "Siga pela rota indicada",
            metersRemaining = route.distanceMeters,status = "Navegação ativa"
        ))
        lastNavFixAt = 0L
        updateNotification(
            TrackingRepository.state.value.place?.road.orEmpty(),
            TrackingRepository.state.value.place?.neighbourhood.orEmpty()
        )
    }

    private fun finishNavigationSession() {
        navigationEpoch++
        rerouteJob?.cancel()
        rerouteJob = null
        navigationEngine = null
        ActiveNavigationRepository.publish(ActiveNavigationState())
        // Clearing the small private state file is synchronized with writes.
        // Do not defer it: otherwise a new destination can race with old cleanup.
        navigationStore.clear()
        updateNotification(
            TrackingRepository.state.value.place?.road.orEmpty(),
            TrackingRepository.state.value.place?.neighbourhood.orEmpty()
        )
    }

    private fun onNavigationFix(location: Location) {
        val engine = navigationEngine ?: return
        if (!isTrackingRunning || !location.hasAccuracy()) return
        val now = System.currentTimeMillis()
        if (now - lastNavFixAt < 1_000L) return
        lastNavFixAt = now
        val tick = engine.update(
            location.latitude,location.longitude,location.accuracy,
            if (location.hasSpeed()) location.speed * 3.6f else 0f,now
        ) ?: return
        ActiveNavigationRepository.publish(tick.state)
        tick.speech.forEach { instruction ->
            speakIfAllowed(instruction,true,SpeechKind.NAVIGATION,
                "nav:${instruction.lowercase()}")
        }
        if (tick.arrived) {
            finishNavigationSession()
            return
        }
        if (tick.requestReroute && rerouteJob?.isActive != true) {
            val epoch = navigationEpoch
            val previous = engine.route
            ActiveNavigationRepository.publish(tick.state.copy(
                rerouting = true,status = "Recalculando rota"
            ))
            rerouteJob = serviceScope.launch {
                val result = navigationNetwork.route(
                    location.latitude,location.longitude,previous.destination
                )
                if (epoch != navigationEpoch || navigationEngine !== engine) return@launch
                result.onSuccess { next ->
                    // Persist new geometry before exposing it to UI.
                    try {
                        withContext(Dispatchers.IO) {
                            if (epoch == navigationEpoch) navigationStore.write(next)
                        }
                        if (epoch != navigationEpoch) return@onSuccess
                        engine.replaceRoute(next)
                        ActiveNavigationRepository.publish(ActiveNavigationState(
                            navigating = true,route = next,
                            instruction = "Siga pela rota recalculada",
                            metersRemaining = next.distanceMeters,
                            status = "Rota recalculada"
                        ))
                        speakIfAllowed("Rota recalculada.",true,
                            SpeechKind.NAVIGATION,"nav:rerouted:$epoch")
                        updateNotification(
                            TrackingRepository.state.value.place?.road.orEmpty(),
                            TrackingRepository.state.value.place?.neighbourhood.orEmpty()
                        )
                    } catch (e: Exception) {
                        Log.w(TAG,"Cannot persist recalculated route",e)
                        ActiveNavigationRepository.publish(tick.state.copy(
                            status = "Falha ao salvar a rota recalculada"
                        ))
                    }
                }.onFailure {
                    ActiveNavigationRepository.publish(tick.state.copy(
                        status = "Sem conexão para recalcular; seguindo a última rota"
                    ))
                }
            }
        }
    }

    private fun tryAnnouncePendingPoi(location: Location) {
        if (!isTrackingRunning || !currentConfig.narratePoiEnabled) return
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
            bearingDegrees = bearingDegrees,
            currentRoad = position.place?.road.orEmpty()
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
        if (speakIfAllowed(speech, false,SpeechKind.POI,"poi:" + poi.id)) {
            poiAnnouncements.poiWasAnnounced(System.currentTimeMillis())
            poiService.markAnnounced(poi)
            TrackingRepository.updatePoi(poi, relativeDir)
            TrackingRepository.updatePoiStatus("Referência aguardando voz: ${poi.name}")
        }
    }

    private fun speakIfAllowed(
        message: String, forceRepeat: Boolean,
        kind: SpeechKind = SpeechKind.GEOGRAPHY,
        key: String = kind.name + ":" + message.lowercase()
    ): Boolean {
        if (!isTrackingRunning || !TrackingRepository.state.value.isVoiceEnabled) return false
        return speechQueue.offer(kind,message,key,forceRepeat)
    }

    private fun repeatCurrentSpeech() {
        val currentPlace = TrackingRepository.state.value.place
        if (currentPlace != null && currentPlace.hasValidRoad()) {
            val message = currentPlace.toSpeechMessage(isTransition = false)
            speakIfAllowed(message,true,SpeechKind.USER_REQUEST,"repeat-current-road")
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

        val nav = ActiveNavigationRepository.state.value
        val content = if (nav.navigating && nav.route != null) {
            "Navegando para ${nav.route.destination.name}"
        } else if (neighbourhood.isNotBlank() && neighbourhood != "Bairro não identificado") {
            "$road · $neighbourhood"
        } else {
            road
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
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
        if (ActiveNavigationRepository.state.value.navigating) {
            val finish = PendingIntent.getService(
                this, 3,Intent(this,ViaVoxTrackingService::class.java).apply {
                    action = ACTION_NAV_STOP
                },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                "Encerrar rota",finish)
        }
        if (TripRecordingRepository.state.value.recording) {
            val finishTrack = PendingIntent.getService(
                this, 4,Intent(this,ViaVoxTrackingService::class.java).apply {
                    action = ACTION_RECORD_STOP
                },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause,"Finalizar percurso",finishTrack)
        }
        return builder.build()
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
        speechQueue.cancelAll()
        ttsManager.shutdown()
        roadEngine.reset()
        poiJob?.cancel()
        poiJob = null
        poiAnnouncements.reset()
        serviceScope.cancel()
        tripRecorder.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ViaVoxTrackingService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "viavox_tracking_channel"

        const val ACTION_START = "com.example.viavox.ACTION_START"
        const val ACTION_RECORD_START = "com.example.viavox.ACTION_RECORD_START"
        const val ACTION_RECORD_STOP = "com.example.viavox.ACTION_RECORD_STOP"
        const val ACTION_NAV_START = "com.example.viavox.ACTION_NAV_START"
        const val ACTION_NAV_STOP = "com.example.viavox.ACTION_NAV_STOP"
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

        fun startNavigation(context: Context) {
            start(context)
            context.startService(Intent(context,ViaVoxTrackingService::class.java).apply {
                action = ACTION_NAV_START
            })
        }

        fun stopNavigation(context: Context) {
            context.startService(Intent(context,ViaVoxTrackingService::class.java).apply {
                action = ACTION_NAV_STOP
            })
        }

        fun startRecording(context: Context) {
            context.startService(Intent(context,ViaVoxTrackingService::class.java).apply {
                action = ACTION_RECORD_START
            })
        }

        fun stopRecording(context: Context) {
            context.startService(Intent(context,ViaVoxTrackingService::class.java).apply {
                action = ACTION_RECORD_STOP
            })
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

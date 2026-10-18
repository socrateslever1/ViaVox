package com.example.viavox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.viavox.data.NavigationNetworkService
import com.example.viavox.data.TrackingRepository
import com.example.viavox.data.ViaVoxHistoryDatabase
import com.example.viavox.model.MapPlace
import com.example.viavox.model.NavigationMath
import com.example.viavox.model.NavigationRoute
import com.example.viavox.model.SavedTrip
import com.example.viavox.model.StoredRoute
import com.example.viavox.model.TripPoint
import com.example.viavox.service.ViaVoxTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class NavigationUiState(
    val query: String = "",
    val results: List<MapPlace> = emptyList(),
    val searching: Boolean = false,
    val calculating: Boolean = false,
    val error: String? = null,
    val selected: MapPlace? = null,
    val route: NavigationRoute? = null,
    val navigating: Boolean = false,
    val nextInstruction: String = "",
    val metersUntilTurn: Double = 0.0,
    val savedPlaces: List<Pair<Long, MapPlace>> = emptyList(),
    val savedRoutes: List<StoredRoute> = emptyList(),
    val trips: List<SavedTrip> = emptyList(),
    val showingTrip: SavedTrip? = null,
    val panel: String = "", // search, saved, history, routes
    val recording: Boolean = false,
    val recordedMeters: Double = 0.0,
    val recordedCount: Int = 0
)

/** Navigation and local history are separate from working road/neighborhood narration. */
class NavigationViewModel(application: Application) : AndroidViewModel(application) {
    private val network = NavigationNetworkService()
    private val db = ViaVoxHistoryDatabase(application)
    private val _ui = MutableStateFlow(NavigationUiState())
    val ui: StateFlow<NavigationUiState> = _ui.asStateFlow()
    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private var rerouteJob: Job? = null
    private var recordingId = 0L
    private var lastRecorded: TripPoint? = null
    private var distanceRecorded = 0.0
    private var lastGuidedStep = -1
    private var lastRerouteAt = 0L
    private var offRouteFixes = 0
    private var previousFixTimestamp = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            db.recoverUnfinishedTrips()
            refreshLists()
        }
        viewModelScope.launch {
            TrackingRepository.state.collect { tracking ->
                if (!tracking.hasLocationFix || !tracking.isActive) return@collect
                val now = System.currentTimeMillis()
                if (now - previousFixTimestamp < 1_000L) return@collect
                previousFixTimestamp = now
                if (_ui.value.recording) appendLocation(
                    TripPoint(tracking.latitude,tracking.longitude,now,
                        tracking.accuracyMeters?.toFloat() ?: 100f)
                )
                if (_ui.value.navigating) monitorNavigation(
                    tracking.latitude,tracking.longitude,
                    tracking.accuracyMeters?.toFloat() ?: 100f,
                    tracking.speedKmh
                )
            }
        }
    }

    fun updateQuery(query: String) = _ui.update { it.copy(query = query) }
    fun openPanel(panel: String) = _ui.update { it.copy(panel = panel, error = null) }
    fun closePanel() = _ui.update { it.copy(panel = "", error = null) }
    fun dismissError() = _ui.update { it.copy(error = null) }

    fun search() {
        val query = _ui.value.query.trim()
        searchJob?.cancel()
        if (query.length < 3) {
            _ui.update { it.copy(error = "Informe pelo menos três letras para pesquisar.") }
            return
        }
        _ui.update { it.copy(searching = true, error = null, panel = "search") }
        val location = TrackingRepository.state.value
        searchJob = viewModelScope.launch {
            val result = network.search(query,
                location.latitude.takeIf { location.hasLocationFix },
                location.longitude.takeIf { location.hasLocationFix })
            result.fold(
                onSuccess = { items ->
                    _ui.update { it.copy(
                        results = items, searching = false,
                        error = if (items.isEmpty()) "Nenhum local encontrado." else null
                    ) }
                },
                onFailure = { error ->
                    _ui.update { it.copy(searching = false,
                        error = error.message ?: "Não foi possível consultar os endereços.") }
                }
            )
        }
    }

    fun select(place: MapPlace) {
        _ui.update { it.copy(selected = place, route = null, navigating = false,
            showingTrip = null, panel = "", error = null) }
        calculateRoute(place)
    }

    fun selectMapPoint(latitude: Double, longitude: Double) =
        select(MapPlace("Ponto escolhido no mapa",latitude,longitude,
            "${"%.5f".format(java.util.Locale.US,latitude)}, ${"%.5f".format(java.util.Locale.US,longitude)}"))

    private fun calculateRoute(destination: MapPlace) {
        val tracking = TrackingRepository.state.value
        if (!tracking.hasLocationFix || (tracking.accuracyMeters ?: 100) > 80) {
            _ui.update { it.copy(error = "Aguarde uma posição GPS precisa para calcular a rota.") }
            return
        }
        routeJob?.cancel()
        _ui.update { it.copy(calculating = true,error = null) }
        routeJob = viewModelScope.launch {
            val result = network.route(tracking.latitude,tracking.longitude,destination)
            result.fold(
                onSuccess = { route ->
                    lastGuidedStep = -1
                    _ui.update { it.copy(route = route, calculating = false, error = null) }
                },
                onFailure = { error ->
                    _ui.update { it.copy(calculating = false,
                        error = error.message ?: "Não foi possível traçar a rota.") }
                }
            )
        }
    }

    fun startNavigation() {
        val route = _ui.value.route ?: return
        lastGuidedStep = -1
        offRouteFixes = 0
        _ui.update { it.copy(navigating = true, nextInstruction = route.steps.firstOrNull()?.instruction.orEmpty()) }
    }

    fun stopNavigation() {
        rerouteJob?.cancel()
        _ui.update { it.copy(navigating = false, route = null, selected = null,
            nextInstruction = "", metersUntilTurn = 0.0) }
    }

    private suspend fun monitorNavigation(lat: Double, lon: Double, accuracy: Float, speedKmh: Float) {
        val route = _ui.value.route ?: return
        if (accuracy > 45f || !accuracy.isFinite()) return
        val destinationDistance = NavigationMath.meters(
            lat,lon,route.destination.latitude,route.destination.longitude
        )
        if (destinationDistance < 35) {
            if (lastGuidedStep != Int.MAX_VALUE) {
                lastGuidedStep = Int.MAX_VALUE
                ViaVoxTrackingService.announceGuidance(getApplication(),"Você chegou ao destino.")
            }
            _ui.update { it.copy(navigating = false,nextInstruction = "Chegou ao destino",
                metersUntilTurn = 0.0) }
            return
        }

        val upcoming = route.steps.mapIndexed { index, step -> index to step }
            .filter { (index,step) -> index > lastGuidedStep &&
                step.instruction != "Você chegou ao destino" }
            .minByOrNull { (_,step) -> NavigationMath.meters(lat,lon,step.latitude,step.longitude) }
        if (upcoming != null) {
            val (index,step) = upcoming
            val meters = NavigationMath.meters(lat,lon,step.latitude,step.longitude)
            _ui.update { it.copy(nextInstruction = step.instruction, metersUntilTurn = meters) }
            if (meters <= 70.0 && index > lastGuidedStep && index > 0 && speedKmh > 2f) {
                lastGuidedStep = index
                ViaVoxTrackingService.announceGuidance(
                    getApplication(),"${step.instruction}. ${NavigationMath.formatDistance(meters)}."
                )
            }
        }

        val offRouteMeters = NavigationMath.distanceToPolylineMeters(lat,lon,route.coordinates)
        offRouteFixes = if (offRouteMeters > (accuracy * 2 + 65.0)) offRouteFixes + 1 else 0
        val now = System.currentTimeMillis()
        if (offRouteFixes >= 3 && now - lastRerouteAt >= 45_000 &&
            rerouteJob?.isActive != true
        ) {
            lastRerouteAt = now
            offRouteFixes = 0
            _ui.update { it.copy(calculating = true) }
            rerouteJob = viewModelScope.launch {
                val result = network.route(lat,lon,route.destination)
                result.fold(
                    onSuccess = {
                        lastGuidedStep = -1
                        _ui.update { old -> old.copy(route = it, calculating = false,
                            nextInstruction = it.steps.firstOrNull()?.instruction.orEmpty()) }
                        ViaVoxTrackingService.announceGuidance(getApplication(),"Rota recalculada.")
                    },
                    onFailure = {
                        _ui.update { old -> old.copy(calculating = false,
                            error = "Não foi possível recalcular a rota. Confira a conexão.") }
                    }
                )
            }
        }
    }

    fun saveCurrentPoint() {
        val location = TrackingRepository.state.value
        if (!location.hasLocationFix || (location.accuracyMeters ?: 100) > 45) {
            _ui.update { it.copy(error = "Aguarde uma localização GPS mais precisa.") }
            return
        }
        val name = location.place?.road?.takeIf { it.isNotBlank() } ?: "Local salvo"
        saveFavorite(MapPlace(name,location.latitude,location.longitude,
            location.place?.neighbourhood.orEmpty()))
    }

    fun saveFavorite(place: MapPlace) {
        viewModelScope.launch(Dispatchers.IO) {
            db.addFavorite(place)
            refreshLists()
        }
    }

    fun removeFavorite(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        db.deleteFavorite(id);refreshLists()
    }

    fun saveCurrentRoute() {
        val route = _ui.value.route ?: return
        viewModelScope.launch(Dispatchers.IO) { db.saveRoute(route);refreshLists() }
    }

    fun openSavedRoute(route: StoredRoute) {
        _ui.update { it.copy(
            selected = route.route.destination, route = route.route,
            showingTrip = null, navigating = false, panel = "", error = null
        ) }
    }

    fun removeRoute(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        db.deleteRoute(id);refreshLists()
    }

    fun beginRecording() {
        if (_ui.value.recording) return
        val t = TrackingRepository.state.value
        if (!t.isActive || !t.hasLocationFix) {
            _ui.update { it.copy(error = "Ative o GPS antes de gravar o percurso.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            recordingId = db.startTrip()
            lastRecorded = null
            distanceRecorded = 0.0
            _ui.update { it.copy(recording = true, recordedMeters = 0.0,recordedCount = 0) }
        }
    }

    private suspend fun appendLocation(point: TripPoint) {
        if (point.accuracy > 45f || recordingId == 0L) return
        val last = lastRecorded
        if (last != null) {
            if (point.recordedAt - last.recordedAt < 3_000L) return
            val distance = NavigationMath.meters(last.latitude,last.longitude,
                point.latitude,point.longitude)
            if (distance < 6.0) return
            if (distance > 65.0 * ((point.recordedAt - last.recordedAt) / 1000.0) +
                point.accuracy + last.accuracy) return
            distanceRecorded += distance
        }
        withContext(Dispatchers.IO) {
            db.appendTripPoint(recordingId,point,distanceRecorded)
        }
        lastRecorded = point
        _ui.update { it.copy(recordedMeters = distanceRecorded,recordedCount = it.recordedCount + 1) }
    }

    fun finishRecording() {
        if (!_ui.value.recording) return
        val finishedId = recordingId
        recordingId = 0L
        lastRecorded = null
        _ui.update { it.copy(recording = false) }
        viewModelScope.launch(Dispatchers.IO) {
            db.finishTrip(finishedId)
            refreshLists()
        }
    }

    fun onAppHidden() {
        // Recording is foreground-only: stop and persist instead of silently
        // claiming that it continued in background.
        finishRecording()
    }

    fun openTrip(trip: SavedTrip) {
        _ui.update { it.copy(showingTrip = trip, route = null, navigating = false,
            selected = null, panel = "") }
    }

    fun removeTrip(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        db.deleteTrip(id)
        _ui.update { it.copy(showingTrip = it.showingTrip?.takeUnless { trip -> trip.id == id }) }
        refreshLists()
    }

    fun showSavedPlaces() { refreshListsAsync();openPanel("saved") }
    fun showSavedRoutes() { refreshListsAsync();openPanel("routes") }
    fun showHistory() { refreshListsAsync();openPanel("history") }

    private fun refreshListsAsync() { viewModelScope.launch(Dispatchers.IO) { refreshLists() } }
    private fun refreshLists() {
        val places = db.getFavorites()
        val routes = db.getRoutes()
        val trips = db.getTrips()
        _ui.update { it.copy(savedPlaces = places, savedRoutes = routes, trips = trips) }
    }

    override fun onCleared() {
        super.onCleared()
        // The Android lifecycle typically calls onAppHidden first.
        if (recordingId != 0L) db.finishTrip(recordingId)
        db.close()
    }
}

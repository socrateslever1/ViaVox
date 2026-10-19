package com.example.viavox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.viavox.data.NavigationNetworkService
import com.example.viavox.data.VerifiedManausLandmarks
import com.example.viavox.data.DestinationSearchPolicy
import com.example.viavox.data.TrackingRepository
import com.example.viavox.data.TripRecordingRepository
import com.example.viavox.data.ViaVoxHistoryDatabase
import com.example.viavox.model.MapPlace
import com.example.viavox.model.NavigationMath
import com.example.viavox.model.NavigationRoute
import com.example.viavox.model.SavedTrip
import com.example.viavox.model.StoredRoute
import com.example.viavox.model.TripPoint
import com.example.viavox.service.ViaVoxTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
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
    val searchNotice: String? = null,
    val suggestionsVisible: Boolean = false,
    val suggesting: Boolean = false,
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
    val recordedCount: Int = 0,
    val livePoints: List<TripPoint> = emptyList()
)

/** Navigation and local history are separate from working road/neighborhood narration. */
class NavigationViewModel(application: Application) : AndroidViewModel(application) {
    private val network = NavigationNetworkService()
    private val db = ViaVoxHistoryDatabase(application)
    private val _ui = MutableStateFlow(NavigationUiState())
    val ui: StateFlow<NavigationUiState> = _ui.asStateFlow()
    private var searchJob: Job? = null
    private var suggestionJob: Job? = null
    private var routeJob: Job? = null
    private var rerouteJob: Job? = null
    private var lastGuidedStep = -1
    private var lastAdvanceStep = -1
    private var lastRerouteAt = 0L
    private var offRouteFixes = 0
    private var previousFixTimestamp = 0L

    init {
        bindTripRecording()
        viewModelScope.launch(Dispatchers.IO) {
            refreshLists()
        }
        viewModelScope.launch {
            TrackingRepository.state.collect { tracking ->
                if (!tracking.hasLocationFix || !tracking.isActive) return@collect
                val now = System.currentTimeMillis()
                if (now - previousFixTimestamp < 1_000L) return@collect
                previousFixTimestamp = now
                if (_ui.value.navigating) monitorNavigation(
                    tracking.latitude,tracking.longitude,
                    tracking.accuracyMeters?.toFloat() ?: 100f,
                    tracking.speedKmh
                )
            }
        }
    }

    // A recording survives Activity/Compose lifecycle transitions because
    // the foreground service owns the GPS fixes, SQLite writes and lifecycle.
    private fun bindTripRecording() {
        viewModelScope.launch {
            var wasRecording = false
            TripRecordingRepository.state.collect { snapshot ->
                _ui.update { it.copy(
                    recording = snapshot.recording,
                    recordedMeters = snapshot.meters,
                    recordedCount = snapshot.count,
                    livePoints = snapshot.points
                ) }
                if (wasRecording && !snapshot.recording) {
                    viewModelScope.launch(Dispatchers.IO) { refreshLists() }
                }
                wasRecording = snapshot.recording
            }
        }
    }

    fun updateQuery(query: String) {
        searchJob?.cancel()
        suggestionJob?.cancel()
        val normalized = query.trim()
        val ready = normalized.length >= 3
        val instant = if (ready) DestinationSearchPolicy.merge(
            DestinationSearchPolicy.localMatches(
                normalized,_ui.value.savedPlaces.map { it.second }
            ),VerifiedManausLandmarks.find(normalized)
        ) else emptyList()
        _ui.update { it.copy(
            query = query, results = instant,
            suggestionsVisible = ready, suggesting = ready,
            searching = false, searchNotice = null,error = null,
            panel = ""
        ) }
        if (!ready) return
        suggestionJob = viewModelScope.launch {
            delay(480L)
            val tracking = TrackingRepository.state.value
            val places = runCatching {
                network.suggest(
                    normalized,
                    tracking.latitude.takeIf { tracking.hasLocationFix },
                    tracking.longitude.takeIf { tracking.hasLocationFix },
                    _ui.value.savedPlaces.map { it.second }
                )
            }.getOrDefault(emptyList())
            if (_ui.value.query.trim() != normalized) return@launch
            _ui.update { state ->
                state.copy(
                    results = DestinationSearchPolicy.merge(places,instant).take(6),
                    suggesting = false,
                    suggestionsVisible = true,
                    searchNotice = null
                )
            }
        }
    }

    fun hideSuggestions() {
        suggestionJob?.cancel()
        _ui.update { it.copy(suggestionsVisible = false,suggesting = false) }
    }

    fun showSuggestions() {
        if (_ui.value.query.trim().length >= 3) {
            _ui.update { it.copy(suggestionsVisible = true,panel = "") }
        }
    }

    fun openPanel(panel: String) = _ui.update {
        it.copy(panel = panel, error = null, suggestionsVisible = false)
    }
    fun closePanel() = _ui.update { it.copy(panel = "", error = null) }
    fun dismissError() = _ui.update { it.copy(error = null) }

    fun search() {
        val query = _ui.value.query.trim()
        searchJob?.cancel()
        suggestionJob?.cancel()
        if (query.length < 3) {
            _ui.update { it.copy(error = "Informe pelo menos três letras para pesquisar.") }
            return
        }
        _ui.update { it.copy(
            searching = true, suggesting = false, suggestionsVisible = true,
            error = null, searchNotice = null, panel = ""
        ) }
        val location = TrackingRepository.state.value
        searchJob = viewModelScope.launch {
            // Personal bookmarks remain available even when public geocoders fail.
            val saved = _ui.value.savedPlaces.map { it.second }
            val result = network.search(
                query,
                location.latitude.takeIf { location.hasLocationFix },
                location.longitude.takeIf { location.hasLocationFix },
                saved
            )
            result.fold(
                onSuccess = { items ->
                    _ui.update { it.copy(
                        results = items, searching = false,
                        error = if (items.isEmpty())
                            "Destino não encontrado. Experimente o nome completo ou selecione um ponto no mapa."
                            else null,
                        searchNotice = if (items.isNotEmpty())
                            "${items.size} destino(s) encontrado(s). Escolha um para traçar a rota."
                            else null
                    ) }
                },
                onFailure = { error ->
                    _ui.update { it.copy(searching = false, results = emptyList(), searchNotice = null,
                        error = error.message ?: "Busca online indisponível. Escolha o destino no mapa.") }
                }
            )
        }
    }

    fun select(place: MapPlace) {
        suggestionJob?.cancel()
        searchJob?.cancel()
        _ui.update { it.copy(selected = place, route = null, navigating = false,
            query = place.name, results = emptyList(),suggestionsVisible = false,
            suggesting = false, showingTrip = null, panel = "", error = null) }
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
                    lastAdvanceStep = -1
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
        lastAdvanceStep = -1
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

            // Stage 1: Advance warning (120m to 320m ahead)
            if (meters in 120.0..320.0 && index > lastAdvanceStep && speedKmh > 8f) {
                lastAdvanceStep = index
                val cleanInstruction = step.instruction.replaceFirstChar { it.lowercase() }
                ViaVoxTrackingService.announceGuidance(
                    getApplication(), "Em ${NavigationMath.formatDistance(meters)}, $cleanInstruction."
                )
            }

            // Stage 2: Turn execution command (under 55m)
            if (meters <= 55.0 && index > lastGuidedStep && index > 0 && speedKmh > 1.5f) {
                lastGuidedStep = index
                ViaVoxTrackingService.announceGuidance(
                    getApplication(), "${step.instruction}."
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
        val current = TrackingRepository.state.value
        if (!current.isActive || !current.hasLocationFix) {
            _ui.update { it.copy(error = "Ative o GPS antes de gravar o percurso.") }
            return
        }
        ViaVoxTrackingService.startRecording(getApplication())
    }

    fun finishRecording() {
        if (!_ui.value.recording) return
        ViaVoxTrackingService.stopRecording(getApplication())
    }

    fun onAppHidden() {
        // GPS and trip recording are service-owned. Do not destroy an active
        // session when Android hides the activity or locks the screen.
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
        // Never finish a service-owned trip just because the screen is destroyed.
        db.close()
    }
}

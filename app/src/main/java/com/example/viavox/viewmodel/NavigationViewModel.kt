package com.example.viavox.viewmodel

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.viavox.data.GpxTripExporter
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.viavox.data.NavigationNetworkService
import com.example.viavox.data.VerifiedManausLandmarks
import com.example.viavox.data.DestinationSearchPolicy
import com.example.viavox.data.GeoCatalogDatabase
import com.example.viavox.data.TrackingRepository
import com.example.viavox.data.TripRecordingRepository
import com.example.viavox.data.RecentDestinationsStore
import com.example.viavox.data.ViaVoxHistoryDatabase
import com.example.viavox.model.MapPlace
import com.example.viavox.model.NavigationMath
import com.example.viavox.model.NavigationRoute
import com.example.viavox.service.ActiveNavigationRepository
import com.example.viavox.service.NavigationSessionStore
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
    val remainingDistanceMeters: Double = 0.0,
    val metersUntilTurn: Double = 0.0,
    val pendingSavePlace: MapPlace? = null,
    val pendingSaveId: Long? = null,
    val savedPlaces: List<Pair<Long, MapPlace>> = emptyList(),
    val savedRoutes: List<StoredRoute> = emptyList(),
    val trips: List<SavedTrip> = emptyList(),
    val showingTrip: SavedTrip? = null,
    val panel: String = "", // search, saved, history, routes
    val recording: Boolean = false,
    val recordedMeters: Double = 0.0,
    val recordedCount: Int = 0,
    val livePoints: List<TripPoint> = emptyList(),
    val recentDestinations: List<MapPlace> = emptyList(),
    val favoriteDestinations: List<MapPlace> = emptyList()
)

/** Navigation and local history are separate from working road/neighborhood narration. */
class NavigationViewModel(application: Application) : AndroidViewModel(application) {
    private val network = NavigationNetworkService()
    private val db = ViaVoxHistoryDatabase(application)
    private val recentDestinationsStore = RecentDestinationsStore(application)
    private val geoCatalog = GeoCatalogDatabase.shared(application)
    private val _ui = MutableStateFlow(
        NavigationUiState(
            recentDestinations = recentDestinationsStore.load(),
            favoriteDestinations = recentDestinationsStore.loadFavorites()
        )
    )
    val ui: StateFlow<NavigationUiState> = _ui.asStateFlow()
    private var searchJob: Job? = null
    private var suggestionJob: Job? = null
    private var routeJob: Job? = null
    private val sessionStore = NavigationSessionStore(application)

    init {
        bindTripRecording()
        viewModelScope.launch(Dispatchers.IO) {
            refreshLists()
        }
        viewModelScope.launch {
            ActiveNavigationRepository.state.collect { current ->
                if (current.navigating && current.route != null) {
                    _ui.update { it.copy(
                        navigating = true, route = current.route,
                        selected = current.route.destination,
                        nextInstruction = current.instruction,
                        metersUntilTurn = current.metersToStep,
                        remainingDistanceMeters = current.metersRemaining,
                        calculating = current.rerouting,
                        error = if (current.status.contains("Sem conexão"))
                            current.status else null
                    ) }
                } else if (_ui.value.navigating && !current.navigating) {
                    _ui.update { it.copy(
                        navigating = false,
                        nextInstruction = current.instruction,
                        calculating = false
                    ) }
                }
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
            suggestionsVisible = true, suggesting = ready,
            searching = false, searchNotice = null,error = null,
            panel = ""
        ) }
        if (!ready) return
        suggestionJob = viewModelScope.launch {
            // The first suggestions come from indexed SQLite, not 480 ms network debounce.
            val offline = runCatching {
                geoCatalog.searchDestinations(normalized,8)
            }.getOrDefault(emptyList())
            if (_ui.value.query.trim() != normalized) return@launch
            val quick = DestinationSearchPolicy.merge(offline,instant).take(8)
            _ui.update { state -> state.copy(
                results = quick, suggesting = true, suggestionsVisible = true,
                searchNotice = if (quick.isNotEmpty()) "Referências do banco local" else null
            ) }
            delay(350L)
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
            _ui.update { state -> state.copy(
                results = DestinationSearchPolicy.merge(quick,places).take(8),
                suggesting = false, suggestionsVisible = true,
                searchNotice = if (places.isEmpty() && quick.isNotEmpty())
                    "Resultados locais disponíveis mesmo sem internet" else null
            ) }
        }
    }

    fun hideSuggestions() {
        suggestionJob?.cancel()
        _ui.update { it.copy(suggestionsVisible = false,suggesting = false) }
    }

    fun showSuggestions() {
        _ui.update { it.copy(suggestionsVisible = true, panel = "") }
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
            val saved = _ui.value.savedPlaces.map { it.second }
            val local = runCatching { geoCatalog.searchDestinations(query,12) }
                .getOrDefault(emptyList())
            val cached = DestinationSearchPolicy.merge(saved.filter {
                DestinationSearchPolicy.normalize(it.name).contains(
                    DestinationSearchPolicy.normalize(query))
            },local)
            if (_ui.value.query.trim() != query) return@launch
            if (cached.isNotEmpty()) _ui.update { it.copy(
                results = cached, searching = true,
                searchNotice = "Referências locais encontradas; ampliando a busca..."
            ) }
            val result = network.search(
                query,
                location.latitude.takeIf { location.hasLocationFix },
                location.longitude.takeIf { location.hasLocationFix },
                saved
            )
            if (_ui.value.query.trim() != query) return@launch
            result.fold(
                onSuccess = { remote ->
                    val items = DestinationSearchPolicy.merge(cached,remote)
                    val updatedRecent = items.firstOrNull()?.let { top ->
                        recentDestinationsStore.record(top)
                    } ?: _ui.value.recentDestinations
                    _ui.update { it.copy(
                        results = items, searching = false,
                        recentDestinations = updatedRecent,
                        error = if (items.isEmpty())
                            "Destino não encontrado no banco nem no serviço de mapas."
                        else null,
                        searchNotice = if (items.isNotEmpty())
                            items.size.toString() + " destino(s). Escolha um para traçar a rota."
                        else null
                    ) }
                },
                onFailure = { error ->
                    _ui.update { it.copy(
                        searching = false, results = cached,
                        searchNotice = if (cached.isNotEmpty())
                            "Busca online indisponível. Resultados locais preservados." else null,
                        error = if (cached.isEmpty())
                            error.message ?: "Busca online indisponível."
                        else null
                    ) }
                }
            )
        }
    }

    fun select(place: MapPlace) {
        if (_ui.value.navigating || ActiveNavigationRepository.state.value.navigating) {
            ViaVoxTrackingService.stopNavigation(getApplication())
        }
        suggestionJob?.cancel()
        searchJob?.cancel()
        val updatedRecent = recentDestinationsStore.record(place)
        _ui.update { it.copy(selected = place, route = null, navigating = false,
            query = place.name, results = emptyList(),suggestionsVisible = false,
            suggesting = false, showingTrip = null, panel = "", error = null,
            recentDestinations = updatedRecent) }
        calculateRoute(place)
    }

    fun removeRecentDestination(place: MapPlace) {
        val updated = recentDestinationsStore.remove(place)
        _ui.update { it.copy(recentDestinations = updated) }
    }

    fun clearRecentDestinations() {
        val updated = recentDestinationsStore.clear()
        _ui.update { it.copy(recentDestinations = updated) }
    }

    fun selectMapPoint(latitude: Double, longitude: Double) {
        val place = MapPlace("Ponto escolhido no mapa",latitude,longitude,
            "${"%.5f".format(java.util.Locale.US,latitude)}, ${"%.5f".format(java.util.Locale.US,longitude)}")
        val updatedRecent = recentDestinationsStore.record(place)
        _ui.update { it.copy(selected = place, route = null, navigating = false,
            query = place.name, results = emptyList(), suggestionsVisible = false,
            suggesting = false, showingTrip = null, panel = "", error = null,
            recentDestinations = updatedRecent) }
    }

    fun confirmSelectedDestination() {
        _ui.value.selected?.let(::calculateRoute)
    }

    private fun calculateRoute(destination: MapPlace) {
        val tracking = TrackingRepository.state.value
        if (com.example.viavox.model.VehicleMode.from(tracking.config.vehicleType) ==
            com.example.viavox.model.VehicleMode.WALKING
        ) {
            _ui.update { it.copy(
                calculating = false,route = null,
                error = "Modo A pé: a bússola e o indicador funcionam. Rotas para pedestres ainda não têm servidor compatível."
            ) }
            return
        }
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
        if (com.example.viavox.model.VehicleMode.from(
                TrackingRepository.state.value.config.vehicleType
            ) == com.example.viavox.model.VehicleMode.WALKING) {
            _ui.update { it.copy(error = "Navegação a pé exige um roteador para pedestres; o servidor atual é rodoviário.") }
            return
        }
        val route = _ui.value.route ?: return
        if (!TrackingRepository.state.value.hasLocationPermission) {
            _ui.update { it.copy(error = "Autorize o GPS antes de iniciar a navegação.") }
            return
        }
        _ui.update { it.copy(
            navigating = true, nextInstruction = "Preparando navegação...",
            remainingDistanceMeters = route.distanceMeters, metersUntilTurn = 0.0,
            error = null
        ) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { sessionStore.write(route) }
                // Only start the foreground session from a user-initiated action.
                ViaVoxTrackingService.startNavigation(getApplication())
            } catch (e: Exception) {
                _ui.update { it.copy(
                    navigating = false,error = "Não foi possível iniciar a rota. Tente novamente."
                ) }
            }
        }
    }

    fun stopNavigation() {
        ViaVoxTrackingService.stopNavigation(getApplication())
        _ui.update { it.copy(
            navigating = false,route = null,selected = null,
            nextInstruction = "",metersUntilTurn = 0.0,remainingDistanceMeters = 0.0
        ) }
    }

    /** Open a true editable bookmark dialog instead of silently inserting. */
    fun saveCurrentPoint() {
        val location = TrackingRepository.state.value
        if (!location.hasLocationFix || (location.accuracyMeters ?: 100) > 45) {
            _ui.update { it.copy(error = "Aguarde uma localização GPS mais precisa.") }
            return
        }
        val existing = _ui.value.savedPlaces.filter {
            NavigationMath.meters(
                it.second.latitude,it.second.longitude,
                location.latitude,location.longitude
            ) < 24.0
        }.minByOrNull {
            NavigationMath.meters(
                it.second.latitude,it.second.longitude,
                location.latitude,location.longitude
            )
        }
        val defaultTitle = location.place?.road?.takeIf { it.isNotBlank() }
            ?: "Meu local"
        val point = existing?.second ?: MapPlace(
            defaultTitle,location.latitude,location.longitude,
            listOfNotNull(
                location.place?.neighbourhood?.takeIf { it.isNotBlank() },
                location.place?.city?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
        )
        _ui.update { it.copy(
            pendingSavePlace = point, pendingSaveId = existing?.first,
            panel = "", suggestionsVisible = false, error = null
        ) }
    }

    fun dismissSavePlace() {
        _ui.update { it.copy(pendingSavePlace = null,pendingSaveId = null) }
    }

    fun confirmSavePlace(name: String) {
        val point = _ui.value.pendingSavePlace ?: return
        val title = name.trim()
        if (title.isBlank() || title.length > 90) {
            _ui.update { it.copy(error = "Escolha um nome de até 90 caracteres.") }
            return
        }
        val savedId = _ui.value.pendingSaveId
        viewModelScope.launch(Dispatchers.IO) {
            if (savedId != null) db.renameFavorite(savedId,title)
            else {
                val exists = db.closestFavorite(point.latitude,point.longitude)
                if (exists == null) db.addFavorite(point.copy(name = title))
                else db.renameFavorite(exists.first,title)
            }
            refreshLists()
            _ui.update { it.copy(pendingSavePlace = null,pendingSaveId = null,error = null) }
        }
    }

    fun removeCurrentSavedPlace() {
        val id = _ui.value.pendingSaveId ?: return
        val pendingPlace = _ui.value.pendingSavePlace
        viewModelScope.launch(Dispatchers.IO) {
            pendingPlace?.let { recentDestinationsStore.removeFavorite(it) }
            db.deleteFavorite(id)
            refreshLists()
            _ui.update { it.copy(pendingSavePlace = null,pendingSaveId = null) }
        }
    }

    fun toggleFavorite(place: MapPlace) {
        val updatedFavorites = recentDestinationsStore.toggleFavorite(place)
        val isNowFavorite = RecentDestinationsStore.isFavoriteInList(updatedFavorites, place)
        _ui.update { it.copy(favoriteDestinations = updatedFavorites) }
        viewModelScope.launch(Dispatchers.IO) {
            if (isNowFavorite) {
                val existing = db.getFavorites().firstOrNull {
                    RecentDestinationsStore.isSameDestination(it.second, place)
                }
                if (existing == null) {
                    db.addFavorite(place)
                }
            } else {
                db.getFavorites()
                    .filter { RecentDestinationsStore.isSameDestination(it.second, place) }
                    .forEach { db.deleteFavorite(it.first) }
            }
            refreshLists()
        }
    }

    fun saveFavorite(place: MapPlace) {
        val currentFavs = recentDestinationsStore.loadFavorites()
        if (!RecentDestinationsStore.isFavoriteInList(currentFavs, place)) {
            recentDestinationsStore.toggleFavorite(place)
        }
        viewModelScope.launch(Dispatchers.IO) {
            db.closestFavorite(place.latitude,place.longitude)?.let {
                db.renameFavorite(it.first,place.name)
            } ?: db.addFavorite(place)
            refreshLists()
        }
    }

    fun renameFavorite(id: Long,name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val oldPlace = db.getFavorites().firstOrNull { it.first == id }?.second
            val ok = db.renameFavorite(id,name)
            if (ok && oldPlace != null) {
                val updated = recentDestinationsStore.loadFavorites().map { fav ->
                    if (RecentDestinationsStore.isSameDestination(fav, oldPlace)) {
                        fav.copy(name = name.trim())
                    } else fav
                }
                recentDestinationsStore.saveFavorites(updated)
            }
            refreshLists()
            if (!ok) _ui.update { it.copy(error = "Não foi possível renomear o local.") }
        }
    }

    fun removeFavorite(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        val oldPlace = db.getFavorites().firstOrNull { it.first == id }?.second
        oldPlace?.let { recentDestinationsStore.removeFavorite(it) }
        db.deleteFavorite(id)
        refreshLists()
    }

    fun saveCurrentRoute() {
        val route = _ui.value.route ?: return
        viewModelScope.launch(Dispatchers.IO) { db.saveRoute(route);refreshLists() }
    }

    fun openSavedRoute(route: StoredRoute) {
        if (_ui.value.navigating || ActiveNavigationRepository.state.value.navigating) {
            ViaVoxTrackingService.stopNavigation(getApplication())
        }
        val updatedRecent = recentDestinationsStore.record(route.route.destination)
        _ui.update { it.copy(
            selected = route.route.destination, route = route.route,
            showingTrip = null, navigating = false, panel = "", error = null,
            recentDestinations = updatedRecent
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
        if (_ui.value.navigating || ActiveNavigationRepository.state.value.navigating) {
            ViaVoxTrackingService.stopNavigation(getApplication())
        }
        _ui.update { it.copy(showingTrip = trip, route = null, navigating = false,
            selected = null, panel = "") }
    }

    fun exportTrip(trip: SavedTrip) {
        val app: Application = getApplication()
        viewModelScope.launch {
            try {
                val export = withContext(Dispatchers.IO) {
                    val path = File(app.cacheDir, "shared_tracks").apply { mkdirs() }
                    val file = File(path, "viavox-percurso-${trip.id}.gpx")
                    file.writeText(GpxTripExporter.encode(trip), Charsets.UTF_8)
                    FileProvider.getUriForFile(
                        app,"${app.packageName}.fileprovider",file
                    )
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/gpx+xml"
                    putExtra(Intent.EXTRA_STREAM,export)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(
                    send,"Salvar ou compartilhar percurso GPX"
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(chooser)
            } catch (error: Exception) {
                _ui.update { it.copy(
                    error = "Não foi possível exportar este percurso. Verifique se há pontos gravados."
                ) }
            }
        }
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
        var places = db.getFavorites()
        val storeFavs = recentDestinationsStore.loadFavorites()
        // Ensure any favorites pinned via localStorage are also stored in SQLite
        var dbChanged = false
        storeFavs.forEach { fav ->
            if (places.none { RecentDestinationsStore.isSameDestination(it.second, fav) }) {
                db.addFavorite(fav)
                dbChanged = true
            }
        }
        if (dbChanged) {
            places = db.getFavorites()
        }
        // Keep localStorage favorites synchronized with SQLite favorites
        val mergedFavorites = buildList {
            addAll(storeFavs)
            places.map { it.second }.forEach { dbPlace ->
                if (none { RecentDestinationsStore.isSameDestination(it, dbPlace) }) {
                    add(dbPlace)
                }
            }
        }
        val persistedFavorites = recentDestinationsStore.saveFavorites(mergedFavorites)
        val routes = db.getRoutes()
        val trips = db.getTrips()
        val recent = recentDestinationsStore.load()
        _ui.update {
            it.copy(
                savedPlaces = places,
                savedRoutes = routes,
                trips = trips,
                recentDestinations = recent,
                favoriteDestinations = persistedFavorites
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Never finish a service-owned trip just because the screen is destroyed.
        db.close()
    }
}

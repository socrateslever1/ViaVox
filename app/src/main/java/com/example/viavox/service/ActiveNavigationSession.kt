package com.example.viavox.service

import com.example.viavox.model.NavigationMath
import com.example.viavox.model.NavigationRoute
import com.example.viavox.model.RouteProgressEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One navigation session shared by the UI and the GPS foreground service. */
data class ActiveNavigationState(
    val navigating: Boolean = false,
    val route: NavigationRoute? = null,
    val instruction: String = "",
    val metersToStep: Double = 0.0,
    val metersRemaining: Double = 0.0,
    val rerouting: Boolean = false,
    val status: String = ""
)

object ActiveNavigationRepository {
    private val writable = MutableStateFlow(ActiveNavigationState())
    val state: StateFlow<ActiveNavigationState> = writable.asStateFlow()
    fun publish(value: ActiveNavigationState) { writable.value = value }
}

data class NavigationTick(
    val state: ActiveNavigationState,
    val speech: List<String> = emptyList(),
    val requestReroute: Boolean = false,
    val arrived: Boolean = false
)

/** Pure progress, voice triggers and deviation detection; does not own UI. */
class NavigationSessionEngine(
    startingRoute: NavigationRoute,
    private val rerouteCooldownMs: Long = 45_000L
) {
    var route: NavigationRoute = startingRoute
        private set
    private var progress = RouteProgressEngine(route)
    private var lastAdvanceIndex = -1
    private var lastTurnIndex = -1
    private var offRouteFixes = 0
    private var lastRerouteAt = 0L
    private var completed = false

    fun replaceRoute(next: NavigationRoute) {
        route = next
        progress = RouteProgressEngine(next)
        lastAdvanceIndex = -1
        lastTurnIndex = -1
        offRouteFixes = 0
        completed = false
    }

    fun update(
        latitude: Double, longitude: Double, accuracyMeters: Float,
        speedKmh: Float, timestampMs: Long
    ): NavigationTick? {
        if (completed || !latitude.isFinite() || !longitude.isFinite() ||
            !accuracyMeters.isFinite() || accuracyMeters > 45f || accuracyMeters <= 0f
        ) return null
        val remaining = NavigationMath.meters(
            latitude,longitude,route.destination.latitude,route.destination.longitude
        )
        if (remaining <= 35.0 && speedKmh < 35f) {
            completed = true
            return NavigationTick(ActiveNavigationState(
                navigating = false,route = route,
                instruction = "Você chegou ao destino",
                metersToStep = 0.0,metersRemaining = 0.0,status = "Chegada"
            ),listOf("Você chegou ao destino."),arrived = true)
        }

        val fix = progress.update(latitude,longitude,speedKmh,timestampMs) ?: return null
        val instruction = fix.instruction.ifBlank { "Continue pela rota" }
        val index = fix.nextStepIndex
        val meters = fix.metersToStep
        val voices = mutableListOf<String>()
        val clean = instruction.replaceFirstChar { it.lowercase() }
        if (index > 0 && meters in 120.0..320.0 &&
            index > lastAdvanceIndex && speedKmh > 8f) {
            lastAdvanceIndex = index
            voices += "Em ${NavigationMath.formatDistance(meters)}, $clean."
        }
        if (index > 0 && meters in 1.0..55.0 &&
            index > lastTurnIndex && speedKmh > 1.5f) {
            lastTurnIndex = index
            voices += "$instruction."
        }
        offRouteFixes = if (fix.distanceOffRouteMeters > accuracyMeters*2.0 + 65.0)
            offRouteFixes + 1 else 0
        val reroute = offRouteFixes >= 3 &&
            (lastRerouteAt == 0L || timestampMs - lastRerouteAt >= rerouteCooldownMs)
        if (reroute) {
            lastRerouteAt = timestampMs
            offRouteFixes = 0
        }
        return NavigationTick(
            state = ActiveNavigationState(
                navigating = true,route = route,
                instruction = instruction,metersToStep = meters,
                metersRemaining = fix.metersRemaining,
                status = if (reroute) "Recalculando rota" else "Navegação ativa"
            ),
            speech = voices,requestReroute = reroute
        )
    }
}

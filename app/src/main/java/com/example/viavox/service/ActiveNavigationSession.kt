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
    private val rerouteCooldownMs: Long = 25_000L,
    private val walking: Boolean = false
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
        if (remaining <= (if (walking) 18.0 else 35.0) && speedKmh < 35f) {
            completed = true
            val arrival = "Você chegou ao destino" + destinationSide()
            return NavigationTick(ActiveNavigationState(
                navigating = false,route = route,
                instruction = arrival,
                metersToStep = 0.0,metersRemaining = 0.0,status = "Chegada"
            ),listOf("$arrival."),arrived = true)
        }

        val fix = progress.update(latitude,longitude,speedKmh,timestampMs) ?: return null
        val instruction = fix.instruction.ifBlank { "Continue pela rota" }
        val index = fix.nextStepIndex
        val meters = fix.metersToStep
        val voices = mutableListOf<String>()
        val clean = instruction.replaceFirstChar { it.lowercase() }
        val speedMps = speedKmh / 3.6
        // The warning distance grows with speed: about 25 seconds ahead in a
        // vehicle, a few dozen steps ahead on foot.
        val advanceFrom = if (walking) 25.0 else 120.0
        val advanceTo = if (walking) 70.0 else (speedMps * 25.0).coerceIn(320.0, 900.0)
        val advanceMinSpeed = if (walking) 1.0f else 8f
        if (index > 0 && meters in advanceFrom..advanceTo &&
            index > lastAdvanceIndex && speedKmh > advanceMinSpeed) {
            lastAdvanceIndex = index
            voices += "Em ${NavigationMath.formatDistance(meters)}, $clean."
        }
        val turnNow = if (walking) 15.0 else (speedMps * 4.0).coerceIn(55.0, 110.0)
        if (index > 0 && meters in 1.0..turnNow &&
            index > lastTurnIndex && speedKmh > (if (walking) 0.5f else 1.5f)) {
            lastTurnIndex = index
            // Two maneuvers close together are announced as one sentence, so
            // the second is not heard only when it is already being missed.
            val chained = fix.followingInstruction.takeIf {
                it.isNotBlank() && !it.startsWith("Você chegou") &&
                    fix.metersBetweenNextAndFollowing <= (if (walking) 30.0 else 150.0)
            }
            voices += if (chained != null) {
                lastAdvanceIndex = maxOf(lastAdvanceIndex, index + 1)
                "$instruction. Em seguida, ${chained.replaceFirstChar { it.lowercase() }}."
            } else "$instruction."
        }
        val offRouteLimit = accuracyMeters * 1.5 + (if (walking) 30.0 else 45.0)
        offRouteFixes = if (fix.distanceOffRouteMeters > offRouteLimit)
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

    /**
     * Which side of the final stretch the real destination is on. The route
     * ends on the road; the place itself is usually a few metres to one side.
     */
    private fun destinationSide(): String {
        val points = route.coordinates
        if (points.size < 2) return ""
        val end = points.last()
        val before = points.dropLast(1).lastOrNull {
            NavigationMath.meters(it.first,it.second,end.first,end.second) >= 8.0
        } ?: return ""
        val lonScale = kotlin.math.cos(Math.toRadians(end.first))
        val alongX = (end.second - before.second) * lonScale
        val alongY = end.first - before.first
        val offX = (route.destination.longitude - end.second) * lonScale
        val offY = route.destination.latitude - end.first
        val offset = NavigationMath.meters(end.first,end.second,
            route.destination.latitude,route.destination.longitude)
        if (offset < 6.0 || offset > 120.0) return ""
        val cross = alongX * offY - alongY * offX
        val along = kotlin.math.hypot(alongX,alongY) * kotlin.math.hypot(offX,offY)
        if (along <= 0.0 || kotlin.math.abs(cross) / along < 0.5) return ""
        return if (cross < 0) ", à direita" else ", à esquerda"
    }
}

package com.example.viavox.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/** The junction is established only by a common real OpenStreetMap node. */
data class RoadGraphNode(val id: Long, val latitude: Double, val longitude: Double)
data class NamedRoadSegment(val id: Long, val name: String, val nodes: List<RoadGraphNode>)
data class RoadJunction(
    val nodeId: Long,
    val latitude: Double,
    val longitude: Double,
    val otherRoads: List<String>,
    val distanceMeters: Double,
    val hasRight: Boolean,
    val hasLeft: Boolean,
    val continuesAhead: Boolean
)

/**
 * Not a turn-by-turn navigation engine. OSM topology shows possible connecting
 * streets, not traffic permissions, safe crossings or pedestrian accessibility.
 */
object RoadJunctionPolicy {
    private fun normalizeStreet(raw: String): String = java.text.Normalizer
        .normalize(raw.lowercase(java.util.Locale.forLanguageTag("pt-BR")), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("^(rua|r\\.|avenida|av\\.|av|travessa|tv\\.|alameda|praca|praça)\\s+"), "")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    fun sameStreet(a: String, b: String): Boolean {
        val first = normalizeStreet(a)
        return first.length >= 4 && first == normalizeStreet(b)
    }

    private fun meters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val y = (aLat - bLat) * 111_195.0
        val x = (aLon - bLon) * 111_195.0 * cos(Math.toRadians((aLat + bLat) / 2.0))
        return hypot(x, y)
    }

    private fun distanceToSegment(lat: Double, lon: Double, a: RoadGraphNode, b: RoadGraphNode): Double {
        val kx = 111_195.0 * cos(Math.toRadians(lat))
        val x1 = (a.longitude - lon) * kx
        val y1 = (a.latitude - lat) * 111_195.0
        val x2 = (b.longitude - lon) * kx
        val y2 = (b.latitude - lat) * 111_195.0
        val dx = x2 - x1
        val dy = y2 - y1
        val length = dx * dx + dy * dy
        val t = if (length < 0.001) 0.0 else (-(x1 * dx + y1 * dy) / length).coerceIn(0.0, 1.0)
        return hypot(x1 + t * dx, y1 + t * dy)
    }

    fun findAhead(
        roads: List<NamedRoadSegment>,
        currentRoad: String,
        latitude: Double,
        longitude: Double,
        heading: Float?,
        accuracyMeters: Float,
        maxDistanceMeters: Int = 160
    ): List<RoadJunction> {
        if (heading == null || !heading.isFinite() || !accuracyMeters.isFinite() ||
            accuracyMeters <= 0 || accuracyMeters > 20 || !latitude.isFinite() || !longitude.isFinite() ||
            currentRoad.isBlank()) return emptyList()
        val current = roads.filter { sameStreet(it.name, currentRoad) && it.nodes.size >= 2 }
        if (current.isEmpty()) return emptyList()
        // This user's coordinate must genuinely lie on (or close to) the OSM geometry
        // of the named road, not merely near a street that has the same name.
        val minRoadDistance = current.minOfOrNull { way ->
            way.nodes.zipWithNext().minOfOrNull { (a, b) ->
                distanceToSegment(latitude, longitude, a, b)
            } ?: Double.POSITIVE_INFINITY
        } ?: return emptyList()
        if (minRoadDistance > (accuracyMeters + 9.0).coerceAtMost(24.0)) return emptyList()

        val memberships = mutableMapOf<Long, MutableList<Pair<NamedRoadSegment, Int>>>()
        roads.forEach { road ->
            road.nodes.forEachIndexed { index, node ->
                memberships.getOrPut(node.id) { mutableListOf() }.add(road to index)
            }
        }

        return current.flatMap { road -> road.nodes }.distinctBy { it.id }.mapNotNull { junction ->
            val membershipsHere = memberships[junction.id].orEmpty()
            val others = membershipsHere.map { it.first.name }
                .filterNot { sameStreet(it, currentRoad) }
                .distinctBy { normalizeStreet(it) }
            if (others.isEmpty()) return@mapNotNull null
            val distance = meters(latitude, longitude, junction.latitude, junction.longitude)
            if (distance < accuracyMeters + 10 || distance + accuracyMeters > maxDistanceMeters) {
                return@mapNotNull null
            }
            val target = com.example.viavox.data.PoiSelectionPolicy.absoluteBearing(
                latitude, longitude, junction.latitude, junction.longitude
            )
            val deviation = com.example.viavox.data.PoiSelectionPolicy.angularDifference(
                target, heading.toDouble()
            )
            if (abs(deviation) > 62.0) return@mapNotNull null

            var left = false
            var right = false
            var straight = false
            membershipsHere.forEach { (way, index) ->
                val adjacent = listOfNotNull(
                    way.nodes.getOrNull(index - 1),
                    way.nodes.getOrNull(index + 1)
                )
                adjacent.forEach { next ->
                    if (meters(junction.latitude, junction.longitude, next.latitude, next.longitude) < 3) return@forEach
                    val outgoing = com.example.viavox.data.PoiSelectionPolicy.absoluteBearing(
                        junction.latitude, junction.longitude, next.latitude, next.longitude
                    )
                    val angle = com.example.viavox.data.PoiSelectionPolicy.angularDifference(
                        outgoing, heading.toDouble()
                    )
                    if (sameStreet(way.name, currentRoad)) {
                        if (abs(angle) <= 50.0) straight = true
                    } else {
                        if (angle in 35.0..145.0) right = true
                        if (angle in -145.0..-35.0) left = true
                    }
                }
            }
            RoadJunction(junction.id, junction.latitude, junction.longitude, others,
                distance, right, left, straight)
        }.distinctBy { it.nodeId }
            .sortedBy { it.distanceMeters }
            .take(3)
    }

    fun describe(feature: RoadJunction): String {
        val approx = ((feature.distanceMeters / 10.0).toInt() * 10).coerceAtLeast(10)
        val other = feature.otherRoads.take(2).joinToString(" e ")
        val info = when {
            feature.hasRight && feature.hasLeft -> "Existem vias à direita e à esquerda."
            feature.hasRight -> "Existe uma via à direita."
            feature.hasLeft -> "Existe uma via à esquerda."
            else -> "Há uma ligação entre as vias."
        }
        val straight = if (feature.continuesAhead) " A rua atual continua adiante." else ""
        return "A aproximadamente $approx metros, cruzamento mapeado com $other. $info$straight"
    }
}

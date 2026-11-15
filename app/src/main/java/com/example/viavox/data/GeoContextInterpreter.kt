package com.example.viavox.data

import com.example.viavox.model.LandmarkCandidate
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Fully on-device contextual scoring. It cannot invent facts: only candidates
 * returned by verified coordinates are ranked. No internet or model key.
 */
object GeoContextInterpreter {
    /**
     * What a place is, for ranking and for keeping a scan varied: in a dense
     * neighbourhood there are hundreds of churches and shops, and a list of
     * five should still include the hospital, the school and the square.
     */
    enum class PlaceCategory(val priority: Double, val maxPerResult: Int) {
        EMERGENCY(26.0, 5), TRANSPORT(22.0, 5), CIVIC(18.0, 5), EDUCATION(15.0, 5),
        HEALTH(12.0, 2), LANDMARK(10.0, 5), COMMERCE(5.0, 2), WORSHIP(3.0, 1),
        OTHER(0.0, 2)
    }

    private fun words(vararg terms: String) =
        Regex(terms.joinToString("|") { "\\b" + it + "\\b" })

    // Matched on accent-free lower case text, so "Farmácia" and "farmacia" agree.
    private val EMERGENCY = words("hospital", "pronto socorro", "upa", "maternidade",
        "emergencia", "delegacia", "policia", "police", "bombeiros?", "fire station",
        "samu", "cicom")
    private val TRANSPORT = words("rodoviaria", "bus station", "ferry terminal",
        "terminal", "aeroporto", "airport", "porto de")
    private val WORSHIP = words("place of worship", "igreja", "templo", "paroquia",
        "catedral", "capela", "mesquita", "assembleia de deus", "ministerio",
        "centro espirita", "casa de oracao", "congregacao")
    private val EDUCATION = words("universidade", "university", "faculdade", "college",
        "escola", "school", "colegio", "creche", "kindergarten", "cmei",
        "instituto federal", "ifam", "ufam", "uea", "senai", "sesi", "senac")
    private val CIVIC = words("prefeitura", "townhall", "courthouse", "forum",
        "tribunal", "government", "secretaria", "camara", "cartorio", "post office",
        "correios", "library", "biblioteca", "museu", "museum", "teatro", "theatre",
        "community centre", "centro de convivencia", "cras", "creas", "inss", "detran")
    private val HEALTH = words("farmacia", "drogaria", "pharmacy", "clinica", "clinic",
        "ubs", "posto de saude", "unidade basica", "laboratorio", "policlinica")
    private val LANDMARK = words("parque", "park", "garden", "praca", "mercado municipal", "monument",
        "memorial", "historic", "attraction", "viewpoint", "artwork", "gallery",
        "estadio", "stadium", "sports centre", "ginasio")
    private val COMMERCE = words("supermarket", "supermercado", "mall", "shopping",
        "marketplace", "mercado", "feira", "bank", "banco", "fuel", "posto",
        "hotel", "pousada", "restaurant", "restaurante", "cafe", "lanchonete",
        "shop", "loja", "consultorio", "odont\\w*", "dentist", "bar")

    private fun plain(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.forLanguageTag("pt-BR")), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace('_', ' ')

    fun category(kind: String, name: String): PlaceCategory {
        val label = plain("$kind $name")
        return when {
            EMERGENCY.containsMatchIn(label) -> PlaceCategory.EMERGENCY
            TRANSPORT.containsMatchIn(label) -> PlaceCategory.TRANSPORT
            WORSHIP.containsMatchIn(label) -> PlaceCategory.WORSHIP
            EDUCATION.containsMatchIn(label) -> PlaceCategory.EDUCATION
            CIVIC.containsMatchIn(label) -> PlaceCategory.CIVIC
            HEALTH.containsMatchIn(label) -> PlaceCategory.HEALTH
            LANDMARK.containsMatchIn(label) -> PlaceCategory.LANDMARK
            COMMERCE.containsMatchIn(label) -> PlaceCategory.COMMERCE
            else -> PlaceCategory.OTHER
        }
    }

    private fun categoryOf(point: LandmarkCandidate) =
        category(point.landmark.kind + " " + point.landmark.subkind, point.landmark.name)

    /**
     * Distance is still significant. In a front scan a mapped point directly
     * ahead ranks above a similarly important off-axis point.
     */
    fun score(point:LandmarkCandidate, forward:Boolean):Double {
        val priority=categoryOf(point).priority
        val distancePenalty=point.distanceMeters/12.0
        val directionPenalty=if (forward) abs(point.angle ?: 90.0)/12.0 else 0.0
        return priority-distancePenalty-directionPenalty
    }

    fun rank(points:List<LandmarkCandidate>,forward:Boolean):List<LandmarkCandidate> =
        points.sortedWith(
            compareByDescending<LandmarkCandidate> { score(it,forward) }
                .thenBy { it.distanceMeters }
                .thenBy { it.landmark.name }
        )

    /**
     * Picks [limit] places so a crowded area still shows its variety: first
     * the best place of each category (the hospital, the school, the square,
     * one shop, one church), then more by ranking while a category stays
     * under its cap, and finally whatever is best of the rest. A quiet area
     * with only churches therefore still gets a full answer.
     */
    fun diversify(ranked: List<LandmarkCandidate>, limit: Int): List<LandmarkCandidate> {
        val chosen = LinkedHashSet<LandmarkCandidate>()
        val counts = HashMap<PlaceCategory, Int>()
        for (candidate in ranked) {
            if (chosen.size >= limit) break
            val category = categoryOf(candidate)
            if (category !in counts) {
                counts[category] = 1
                chosen += candidate
            }
        }
        for (candidate in ranked) {
            if (chosen.size >= limit) break
            if (candidate in chosen) continue
            val category = categoryOf(candidate)
            val used = counts[category] ?: 0
            if (used < category.maxPerResult) {
                counts[category] = used + 1
                chosen += candidate
            }
        }
        for (candidate in ranked) {
            if (chosen.size >= limit) break
            chosen += candidate
        }
        // Keep the incoming ranking order (it already accounts for direction).
        return ranked.filter { it in chosen }
    }

    /** Tour: prefer sights over everyday places; lower is better. */
    fun tourPenalty(kind: String, subkind: String, name: String): Double = when {
        kind == "historic" || kind == "tourism" -> 0.0
        kind == "leisure" -> 12.0
        else -> when (category("$kind $subkind", name)) {
            PlaceCategory.LANDMARK -> 0.0
            PlaceCategory.COMMERCE, PlaceCategory.OTHER -> 32.0
            else -> 20.0
        }
    }
}

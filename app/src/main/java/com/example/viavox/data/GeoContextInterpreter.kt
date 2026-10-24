package com.example.viavox.data

import com.example.viavox.model.LandmarkCandidate
import java.util.Locale
import kotlin.math.abs

/**
 * Fully on-device contextual scoring. It cannot invent facts: only candidates
 * returned by verified coordinates are ranked. No internet or model key.
 */
object GeoContextInterpreter {
    private fun priority(kind:String, name:String):Double {
        val label=(kind+" "+name).lowercase(Locale.forLanguageTag("pt-BR"))
        return when {
            listOf("rodoviária","rodoviario","terminal","hospital","upa","pronto socorro",
                "maternidade","emergência","delegacia","polícia","bombeiros")
                .any(label::contains) -> 26.0
            listOf("universidade","escola","farmácia","drogaria","aeroporto",
                "prefeitura","teatro","museu","mercado municipal")
                .any(label::contains) -> 15.0
            listOf("igreja","praça","parque","biblioteca","posto","clínica")
                .any(label::contains) -> 8.0
            else -> 0.0
        }
    }

    /**
     * Distance is still significant. In a front scan a mapped point directly
     * ahead ranks above a similarly important off-axis point.
     */
    fun score(point:LandmarkCandidate, forward:Boolean):Double {
        val priority=priority(point.landmark.kind,point.landmark.name)
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
}

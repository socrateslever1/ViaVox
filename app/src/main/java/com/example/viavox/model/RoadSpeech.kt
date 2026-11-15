package com.example.viavox.model

import java.text.Normalizer
import java.util.Locale

/** Portuguese wording helpers for street names in spoken sentences. */
object RoadSpeech {
    // Street types that are masculine: "no Beco", not "na Beco".
    private val MASCULINE = setOf(
        "beco", "largo", "viaduto", "parque", "acesso", "caminho", "corredor",
        "conjunto", "ramal", "residencial", "loteamento", "complexo", "anel",
        "eixo", "tunel", "trecho", "passeio", "patio", "mirante", "trevo",
        "calcadao", "varadouro"
    )

    /** "no" or "na" for the street type that opens [road]. */
    fun article(road: String): String {
        val first = Normalizer.normalize(
            road.trim().substringBefore(' ').lowercase(Locale.forLanguageTag("pt-BR")),
            Normalizer.Form.NFD
        ).replace(Regex("\\p{M}+"), "")
        return if (first in MASCULINE) "no" else "na"
    }
}

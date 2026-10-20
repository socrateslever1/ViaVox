package com.example.viavox.service

/** One geographic announcement always follows Zona -> Bairro -> Rua. */
data class GeographicTransition(
    val zone: String? = null,
    val neighbourhood: String? = null,
    val road: String? = null
) {
    fun isEmpty(): Boolean = zone.isNullOrBlank() && neighbourhood.isNullOrBlank() && road.isNullOrBlank()

    fun sentence(): String {
        val parts = mutableListOf<String>()
        zone?.takeIf { it.isNotBlank() }?.let { parts.add("Entrando na $it") }
        neighbourhood?.takeIf { it.isNotBlank() }?.let { parts.add("Bairro $it") }
        road?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return if (parts.isEmpty()) "" else parts.joinToString(". ") + "."
    }

    fun dedupeKey(): String = listOf(zone.orEmpty(),neighbourhood.orEmpty(),road.orEmpty())
        .joinToString("|").lowercase()
}

/** Audio-only normalization; never mutate GIS strings or saved destination names. */
object SpeechTextNormalizer {
    fun normalize(raw: String): String {
        var text = raw.replace(Regex("""\s+"""), " ").trim()
        text = text.replace(Regex("""(?i)\b(\d{1,2})\s*[ªaºo]?\s*CICOM\b""")) {
            val n = it.groupValues[1].toIntOrNull()
            val ordinal = if (n != null) portugueseOrdinalFeminine(n) else null
            (ordinal ?: it.groupValues[1]) + " cicom"
        }
        text = text.replace(Regex("""(?i)\bCICOM\b"""), "cicom")
        text = text.replace(Regex("""(?i)\bCOMANDO GERALCMDOGERAL\b"""), "Comando Geral")
        // Acronyms are expanded only for audio. Otherwise Android TTS may
        // spell the letters even though the POI name is a pronounceable word.
        val audioNames = listOf(
            "UPA" to "úpa",
            "UBS" to "unidade básica de saúde",
            "PMAM" to "Polícia Militar do Amazonas",
            "UFAM" to "Universidade Federal do Amazonas",
            "UEA" to "Universidade do Estado do Amazonas",
            "CMDOGERAL" to "Comando Geral",
            "CICOM" to "cícom"
        )
        audioNames.forEach { (acronym, spoken) ->
            text = text.replace(Regex("(?i)\\b" + acronym + "\\b"), spoken)
        }
        // Full-uppercase GIS labels are words, not a sequence of initials.
        text = text.replace(Regex("""\b\p{Lu}{4,}\b""")) {
            it.value.lowercase(java.util.Locale.forLanguageTag("pt-BR"))
                .replaceFirstChar { letter -> letter.uppercaseChar() }
        }
        text = text.replace(Regex("""(?i)\bBR[-\s]*(\d{2,3})\b""")) {
            "BR " + it.groupValues[1].map(Char::digitToInt).joinToString(" ")
        }
        text = text.replace(Regex("""(?i)\bAM[-\s]*(\d{2,3})\b""")) {
            "AM " + it.groupValues[1].map(Char::digitToInt).joinToString(" ")
        }
        return text.replace(Regex("""\s+""")," ").trim()
    }

    private fun portugueseOrdinalFeminine(value: Int): String? {
        val units = arrayOf("","primeira","segunda","terceira","quarta","quinta",
            "sexta","sétima","oitava","nona")
        val tens = arrayOf("","décima","vigésima","trigésima")
        return when {
            value in 1..9 -> units[value]
            value in 10..29 -> {
                val tensWord = tens[value/10]
                val unit = units[value%10]
                if (unit.isBlank()) tensWord else "$tensWord $unit"
            }
            else -> null
        }
    }
}

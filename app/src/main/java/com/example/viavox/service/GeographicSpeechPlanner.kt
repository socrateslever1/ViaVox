package com.example.viavox.service

/** One geographic announcement always follows Zona -> Bairro -> Rua. */
data class GeographicTransition(
    val zone: String? = null,
    val neighbourhood: String? = null,
    val road: String? = null,
    val policeCircumscription: String? = null
) {
    fun isEmpty(): Boolean = zone.isNullOrBlank() && neighbourhood.isNullOrBlank() &&
        road.isNullOrBlank() && policeCircumscription.isNullOrBlank()

    fun sentence(): String {
        val parts = mutableListOf<String>()
        zone?.takeIf { it.isNotBlank() }?.let { parts.add("Entrando na $it") }
        neighbourhood?.takeIf { it.isNotBlank() }?.let { parts.add("Bairro $it") }
        road?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        policeCircumscription?.takeIf { it.isNotBlank() }?.let { parts.add("Área da $it") }
        return if (parts.isEmpty()) "" else parts.joinToString(". ") + "."
    }

    fun dedupeKey(): String = listOf(
        zone.orEmpty(), neighbourhood.orEmpty(), road.orEmpty(), policeCircumscription.orEmpty()
    )
        .joinToString("|").lowercase()
}

/** Audio-only normalization; never mutate GIS strings or saved destination names. */
object SpeechTextNormalizer {
    /**
     * Reference labels are often raw GIS names with internal identifiers.
     * Unknown consonant-only initialisms are not spoken: omit that landmark
     * rather than have the TTS spell a garbage company/acronym.
     */
    fun normalizeReferenceName(raw: String): String? {
        val trimmed = raw.trim().replace(Regex("""\s+"""), " ")
        if (trimmed.isBlank() || trimmed.length > 100) return null
        val lowered = trimmed.lowercase(java.util.Locale.forLanguageTag("pt-BR"))
        if (Regex("""(?i)\b(?:ltda|eireli|s\/a|mei|comercio|supermercado|farmacia|drogaria|consultorio|petshop)\b""")
                .containsMatchIn(lowered)) return null
        // GIS identifiers are not speech. Do not read codes, URLs or labels
        // containing repeated separators as names of meaningful landmarks.
        if (trimmed.contains("http",ignoreCase = true) ||
            trimmed.contains('@') || trimmed.count { it == '_' } >= 2) return null
        val spokenAliases = setOf(
            "UPA","UBS","PMAM","UFAM","UEA","IFAM","CETI","DIP","SENAI",
            "SESC","CICOM","SSP","PCAM","PM","PC","SAMU","DETRAN",
            "BR","AM","CMDOGERAL","INSS","CMEI","CEI","CAPS",
            "CRAS","CREAS","IML","CIGS","IBGE","SEDUC","INPA",
            "CBMAM","FVS","SES","CMA","PAC"
        )
        val grammatical = setOf("DE","DA","DO","DAS","DOS","EM","AO","AOS",
            "E","NA","NO","PA","AV","R")
        val unknownInitialism = Regex("""\b[A-Z]{2,9}\b""").findAll(trimmed).any { match ->
            val word = match.value
            word !in spokenAliases && word !in grammatical &&
                !word.any { it in "AEIOU" } // unpronounceable acronym
        }
        if (unknownInitialism) return null
        val normalized = normalize(trimmed)
        return normalized.takeIf { it.isNotBlank() }
    }

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
            "UPA" to "unidade de pronto atendimento",
            "UBS" to "unidade básica de saúde",
            "PMAM" to "Polícia Militar do Amazonas",
            "UFAM" to "Universidade Federal do Amazonas",
            "UEA" to "Universidade do Estado do Amazonas",
            "IFAM" to "Instituto Federal do Amazonas",
            "CETI" to "Centro Educacional de Tempo Integral",
            "DIP" to "Distrito Integrado de Polícia",
            "SENAI" to "senai",
            "SESC" to "sesc",
            "CMDOGERAL" to "Comando Geral",
            "SSP" to "Secretaria de Segurança Pública",
            "PCAM" to "Polícia Civil do Amazonas",
            "PM" to "Polícia Militar",
            "PC" to "Polícia Civil",
            "SAMU" to "samu",
            "DETRAN" to "Detran",
            "INSS" to "Instituto Nacional do Seguro Social",
            "CMEI" to "Centro Municipal de Educação Infantil",
            "CEI" to "Centro de Educação Infantil",
            "CAPS" to "Centro de Atenção Psicossocial",
            "CRAS" to "Centro de Referência de Assistência Social",
            "CREAS" to "Centro de Referência Especializado de Assistência Social",
            "IML" to "Instituto Médico Legal",
            "CIGS" to "Centro de Instrução de Guerra na Selva",
            "IBGE" to "Instituto Brasileiro de Geografia e Estatística",
            "SEDUC" to "Secretaria de Estado de Educação",
            "INPA" to "Instituto Nacional de Pesquisas da Amazônia",
            "CBMAM" to "Corpo de Bombeiros Militar do Amazonas",
            "FVS" to "Fundação de Vigilância em Saúde",
            "CMA" to "Comando Militar da Amazônia",
            "PAC" to "Pronto Atendimento ao Cidadão",
            "CICOM" to "companhia interativa comunitária"
        )
        audioNames.forEach { (acronym, spoken) ->
            text = text.replace(Regex("(?i)\\b" + acronym + "\\b"), spoken)
        }
        // Speak common map abbreviations as ordinary words. The source
        // display name is not modified; expansions are audio-only.
        text = text.replace(Regex("""(?i)\b(?:av|avda)\.\s*"""),"Avenida ")
        text = text.replace(Regex("""(?i)\b(?:r)\.\s*"""),"Rua ")
        text = text.replace(Regex("""(?i)\b(?:trav|tv)\.\s*"""),"Travessa ")
        text = text.replace(Regex("""(?i)\b(?:prof|profa)\.\s*"""),"Professor ")
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

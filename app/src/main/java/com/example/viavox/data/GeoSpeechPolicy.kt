package com.example.viavox.data

import java.text.Normalizer
import java.util.Locale

/**
 * Deterministic pronunciation policy. The official name is never overwritten.
 * Never ask an LLM to invent a local name or override a mapped location.
 */
object GeoSpeechPolicy {
    private fun fold(raw: String) = Normalizer.normalize(raw,Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"),"")
        .lowercase(Locale.forLanguageTag("pt-BR"))
        .replace(Regex("[^a-z0-9 ]")," ")
        .replace(Regex("\\s+")," ").trim()

    private val localAliases = mapOf(
        "terminal rodoviario engenheiro huascar angelim" to
            "Terminal Rodoviário de Manaus",
        "terminal rodoviario huascar angelim" to
            "Terminal Rodoviário de Manaus",
        "terminal rodoviario de manaus" to
            "Terminal Rodoviário de Manaus",
        "rodoviaria de manaus" to
            "Terminal Rodoviário de Manaus"
    )

    /** Only returns an alias for a known name or a transparently generic category. */
    fun referenceAlias(official: String): String? {
        val key = fold(official)
        if (key.isBlank()) return null
        localAliases[key]?.let { return it }
        if (key.contains("huascar angelim") &&
            (key.contains("rodoviari") || key.contains("terminal"))) {
            return "Terminal Rodoviário de Manaus"
        }
        return null
    }

    /**
     * Prevent TTS spelling raw unmapped all-capitals GIS identifiers.
     * Do not invent a POI: if identity cannot be spoken, omit the POI.
     */
    fun speakableReference(official: String): String? {
        referenceAlias(official)?.let { return it }
        val name = official.trim().replace(Regex("\\s+")," ")
        if (name.isBlank() || name.length > 110 || name.contains("http",true) ||
            name.contains('@') || name.count { it == '_' } >= 2) return null
        val validAcronyms = setOf("UPA","UBS","PMAM","UFAM","UEA","IFAM",
            "CICOM","DIP","CPA","SENAI","SESC","SAMU","DETRAN","INSS",
            "CMEI","CEI","CAPS","CRAS","CREAS","IML","CIGS","IBGE",
            "SEDUC","INPA","CMA","PAC","BR","AM","SSP")
        val grammar = setOf("DA","DO","DAS","DOS","DE","E","EM","NO","NA")
        val uppercase = Regex("""\b[A-Z]{2,12}\b""").findAll(name)
        if (uppercase.any {
            it.value !in validAcronyms && it.value !in grammar &&
                !it.value.any { char -> char in "AEIOU" }
        }) return null
        // Short code-only labels are not useful locations; preserve normal
        // uppercase natural words such as "HOSPITAL GERAL".
        val tokens = name.split(Regex("""[\\s./_-]+"""))
            .filter { it.isNotBlank() }
        if (tokens.isNotEmpty() && tokens.all { token ->
            token.length <= 4 && token.all(Char::isUpperCase) &&
                token !in validAcronyms && token !in grammar
        }) return null
        return name
    }

    /** Common textual abbreviations, without phonetic letter-by-letter reading. */
    fun expandAcronyms(source:String):String {
        var value=source
        val entries = listOf(
            "CPA" to "Comando de Policiamento de Área",
            "GPS" to "localização por satélite",
            "IBGE" to "Instituto Brasileiro de Geografia e Estatística"
        )
        entries.forEach { (token,spoken) ->
            value=value.replace(Regex("(?i)\\b"+token+"\\b"),spoken)
        }
        return value
    }
}

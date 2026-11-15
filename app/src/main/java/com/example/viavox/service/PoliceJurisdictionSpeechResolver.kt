package com.example.viavox.service

/**
 * Only an explicitly mapped CICOM can determine the corresponding CPA.
 * A generic Nominatim police district or a guessed administrative zone
 * must NEVER be announced as a PMAM area of responsibility.
 *
 * Administrative mapping supported by historical public institutional lists;
 * 2026 PMAM material describes Centro-Sul/Centro-Oeste under a combined CPA.
 * Cross-checked against the bundled sector map: the neighbourhoods inside
 * every CICOM lie in the municipal zone of the CPA assigned here.
 * Checked against the PMAM portal unit records (Nov 2026): CPA Sul lists
 * the 1st, 2nd, 3rd, 7th and 24th CICOM; the 5th is under CPA Oeste; the 21st
 * sits in Sao Jorge; the 22nd is described as Centro-Sul. The portal does not
 * list the CICOMs of the other area commands.
 * This is a provisional jurisdiction reference, not real-time dispatch routing.
 */
data class MappedPoliceJurisdiction(val cicom: String, val cpa: String)

object PoliceJurisdictionSpeechResolver {
    private val cicomPattern = Regex("""(?i)\b(\d{1,2})\s*[ªa]?\s*CICOM\b""")
    private val sul = setOf(1, 2, 3, 7, 24)
    private val norte = setOf(6, 13, 15, 18, 26, 27)
    private val leste = setOf(4, 9, 11, 14, 25, 28, 29, 30)
    private val oeste = setOf(5, 8, 19, 20, 21)
    private val centro = setOf(10, 12, 16, 17, 22, 23)

    fun resolve(policeDistrict: String, city: String): MappedPoliceJurisdiction? {
        if (!city.trim().equals("Manaus", ignoreCase = true)) return null
        val number = cicomPattern.find(policeDistrict)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val cpa = when (number) {
            in sul -> "CPA Sul"
            in norte -> "CPA Norte"
            in leste -> "CPA Leste"
            in oeste -> "CPA Oeste"
            in centro -> "CPA Centro-Sul/Centro-Oeste"
            else -> return null
        }
        return MappedPoliceJurisdiction("${number}ª CICOM", cpa)
    }
}

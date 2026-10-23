package com.example.viavox.data

/**
 * Maps neighborhoods and geographic zones to competent police jurisdictions
 * (e.g. DIP - Distrito Integrado de Polícia / CICOM - Companhia Interativa Comunitária
 * in Amazonas, and DP / BPM in other states).
 * Essential for public safety, drivers, transport operators, and citizen awareness.
 */
object PoliceCircumscriptionService {

    data class PoliceZone(
        val dip: String,           // Ex: "3º DIP"
        val cicom: String = "",    // Ex: "3ª CICOM (PMAM)"
        val emergencyPhone: String = "190"
    ) {
        val label: String
            get() = if (cicom.isNotBlank()) "$dip · $cicom" else dip
    }

    // Official mappings for Manaus (DIPs & CICOMs)
    private val MANAUS_POLICE_DISTRICTS = mapOf(
        "Japiim" to PoliceZone("3º DIP", "3ª CICOM"),
        "Petrópolis" to PoliceZone("3º DIP", "3ª CICOM"),
        "São Francisco" to PoliceZone("3º DIP", "3ª CICOM"),
        "Raiz" to PoliceZone("3º DIP", "3ª CICOM"),
        "Cachoeirinha" to PoliceZone("1º DIP", "1ª CICOM"),
        "Praça 14 de Janeiro" to PoliceZone("1º DIP", "1ª CICOM"),
        "Centro" to PoliceZone("24º DIP", "24ª CICOM"),
        "Presidente Vargas" to PoliceZone("24º DIP", "24ª CICOM"),
        "Aparecida" to PoliceZone("24º DIP", "24ª CICOM"),
        "Educandos" to PoliceZone("2º DIP", "2ª CICOM"),
        "Santa Luzia" to PoliceZone("2º DIP", "2ª CICOM"),
        "Betânia" to PoliceZone("7º DIP", "7ª CICOM"),
        "Colônia Oliveira Machado" to PoliceZone("2º DIP", "2ª CICOM"),
        "Morro da Liberdade" to PoliceZone("2º DIP", "2ª CICOM"),
        "São Lázaro" to PoliceZone("7º DIP", "7ª CICOM"),
        "Crespo" to PoliceZone("7º DIP", "7ª CICOM"),
        "Distrito Industrial I" to PoliceZone("7º DIP", "7ª CICOM"),

        "Adrianópolis" to PoliceZone("16º DIP", "16ª CICOM"),
        "Nossa Senhora das Graças" to PoliceZone("22º DIP", "22ª CICOM"),
        "Chapada" to PoliceZone("22º DIP", "22ª CICOM"),
        "São Geraldo" to PoliceZone("22º DIP", "22ª CICOM"),
        "Parque Dez de Novembro" to PoliceZone("23º DIP", "23ª CICOM"),
        "Flores" to PoliceZone("12º DIP", "12ª CICOM"),
        "Aleixo" to PoliceZone("16º DIP", "16ª CICOM"),

        "Alvorada" to PoliceZone("10º DIP", "10ª CICOM"),
        "Dom Pedro" to PoliceZone("10º DIP", "10ª CICOM"),
        "Redenção" to PoliceZone("17º DIP", "17ª CICOM"),
        "Planalto" to PoliceZone("17º DIP", "17ª CICOM"),
        "Da Paz" to PoliceZone("17º DIP", "17ª CICOM"),

        "Compensa" to PoliceZone("8º DIP", "8ª CICOM"),
        "São Jorge" to PoliceZone("21º DIP", "21ª CICOM"),
        "Glória" to PoliceZone("5º DIP", "5ª CICOM"),
        "Santo Antônio" to PoliceZone("5º DIP", "5ª CICOM"),
        "Vila da Prata" to PoliceZone("21º DIP", "21ª CICOM"),
        "São Raimundo" to PoliceZone("5º DIP", "5ª CICOM"),
        "Ponta Negra" to PoliceZone("19º DIP", "19ª CICOM"),
        "Tarumã" to PoliceZone("20º DIP", "20ª CICOM"),

        "Coroado" to PoliceZone("11º DIP", "11ª CICOM"),
        "São José Operário" to PoliceZone("9º DIP", "9ª CICOM"),
        "Tancredo Neves" to PoliceZone("14º DIP", "14ª CICOM"),
        "Armando Mendes" to PoliceZone("25º DIP", "25ª CICOM"),
        "Zumbi dos Palmares" to PoliceZone("25º DIP", "25ª CICOM"),
        "Jorge Teixeira" to PoliceZone("30º DIP", "30ª CICOM"),
        "Mauazinho" to PoliceZone("29º DIP", "29ª CICOM"),
        "Colônia Antônio Aleixo" to PoliceZone("28º DIP", "28ª CICOM"),

        "Cidade Nova" to PoliceZone("6º DIP", "6ª CICOM"),
        "Novo Aleixo" to PoliceZone("27º DIP", "27ª CICOM"),
        "Cidade de Deus" to PoliceZone("13º DIP", "13ª CICOM"),
        "Monte das Oliveiras" to PoliceZone("26º DIP", "26ª CICOM"),
        "Colônia Santo Antônio" to PoliceZone("18º DIP", "18ª CICOM"),
        "Colônia Terra Nova" to PoliceZone("18º DIP", "18ª CICOM"),
        "Nova Cidade" to PoliceZone("15º DIP", "15ª CICOM"),
        "Santa Etelvina" to PoliceZone("26º DIP", "26ª CICOM")
    )

    /**
     * Resolves the police district for a given neighbourhood and city.
     * Generates standard jurisdiction labels for other Brazilian municipalities.
     */
    fun resolve(neighbourhood: String, city: String, zone: String = ""): PoliceZone? {
        if (neighbourhood.isBlank() || neighbourhood.contains("não identificado", ignoreCase = true)) {
            return null
        }

        // Manaus official DIP/CICOM lookup
        if (city.equals("Manaus", ignoreCase = true)) {
            val direct = MANAUS_POLICE_DISTRICTS[neighbourhood]
            if (direct != null) return direct
            val matchedKey = MANAUS_POLICE_DISTRICTS.keys.firstOrNull { it.equals(neighbourhood.trim(), ignoreCase = true) }
            if (matchedKey != null) return MANAUS_POLICE_DISTRICTS[matchedKey]
        }

        // General Brazilian municipality police zone fallback
        val zoneText = if (zone.isNotBlank()) " ($zone)" else ""
        return PoliceZone(
            dip = "Circunscrição Policial de $neighbourhood$zoneText",
            cicom = if (city.isNotBlank()) "PM $city" else "190"
        )
    }
}

package com.example.viavox.data

/**
 * High-precision local boundary and district resolver for Manaus (AM).
 * Solves OpenStreetMap / Nominatim tagging omissions where suburbs like "Japiim"
 * are omitted or falsely labeled as "bairro Manaus".
 */
object ManausDistrictResolver {

    data class District(
        val name: String,
        val zone: String,
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    )

    private val DISTRICTS = listOf(
        // Zona Sul
        District("Japiim", "Zona Sul", -3.142, -3.112, -60.002, -59.968),
        District("Cachoeirinha", "Zona Sul", -3.135, -3.115, -60.018, -59.998),
        District("Praça 14 de Janeiro", "Zona Sul", -3.132, -3.118, -60.025, -60.008),
        District("Centro", "Zona Sul", -3.145, -3.122, -60.038, -60.015),
        District("Aparecida", "Zona Sul", -3.138, -3.124, -60.040, -60.025),
        District("Educandos", "Zona Sul", -3.148, -3.132, -60.022, -60.002),
        District("Santa Luzia", "Zona Sul", -3.148, -3.132, -60.012, -59.998),
        District("Betânia", "Zona Sul", -3.148, -3.130, -60.002, -59.988),
        District("Colônia Oliveira Machado", "Zona Sul", -3.155, -3.135, -60.012, -59.990),
        District("Morro da Liberdade", "Zona Sul", -3.145, -3.135, -60.015, -60.000),
        District("São Lázaro", "Zona Sul", -3.145, -3.125, -60.000, -59.985),
        District("Crespo", "Zona Sul", -3.140, -3.125, -60.008, -59.995),
        District("Distrito Industrial I", "Zona Sul", -3.155, -3.118, -59.990, -59.935),

        // Zona Centro-Sul
        District("Adrianópolis", "Zona Centro-Sul", -3.118, -3.096, -60.022, -59.998),
        District("Nossa Senhora das Graças", "Zona Centro-Sul", -3.122, -3.104, -60.028, -60.010),
        District("Chapada", "Zona Centro-Sul", -3.110, -3.088, -60.038, -60.016),
        District("São Geraldo", "Zona Centro-Sul", -3.125, -3.108, -60.034, -60.018),
        District("Parque Dez de Novembro", "Zona Centro-Sul", -3.105, -3.072, -60.018, -59.982),
        District("Flores", "Zona Centro-Sul", -3.090, -3.052, -60.038, -59.995),
        District("Aleixo", "Zona Centro-Sul", -3.115, -3.088, -60.002, -59.968),

        // Zona Centro-Oeste
        District("Alvorada", "Zona Centro-Oeste", -3.098, -3.065, -60.065, -60.032),
        District("Dom Pedro", "Zona Centro-Oeste", -3.110, -3.082, -60.058, -60.032),
        District("Redenção", "Zona Centro-Oeste", -3.075, -3.055, -60.055, -60.032),
        District("Planalto", "Zona Centro-Oeste", -3.082, -3.058, -60.065, -60.042),
        District("Da Paz", "Zona Centro-Oeste", -3.078, -3.062, -60.050, -60.035),

        // Zona Oeste
        District("Compensa", "Zona Oeste", -3.125, -3.095, -60.075, -60.038),
        District("São Jorge", "Zona Oeste", -3.118, -3.098, -60.042, -60.028),
        District("Glória", "Zona Oeste", -3.132, -3.118, -60.042, -60.028),
        District("Santo Antônio", "Zona Oeste", -3.128, -3.110, -60.048, -60.032),
        District("Vila da Prata", "Zona Oeste", -3.120, -3.105, -60.048, -60.035),
        District("São Raimundo", "Zona Oeste", -3.135, -3.120, -60.045, -60.030),
        District("Ponta Negra", "Zona Oeste", -3.095, -3.040, -60.120, -60.050),
        District("Tarumã", "Zona Oeste", -3.050, -2.980, -60.110, -60.015),

        // Zona Leste
        District("Coroado", "Zona Leste", -3.115, -3.080, -59.988, -59.952),
        District("São José Operário", "Zona Leste", -3.102, -3.065, -59.970, -59.928),
        District("Tancredo Neves", "Zona Leste", -3.095, -3.070, -59.965, -59.935),
        District("Armando Mendes", "Zona Leste", -3.125, -3.095, -59.960, -59.922),
        District("Zumbi dos Palmares", "Zona Leste", -3.098, -3.072, -59.952, -59.918),
        District("Jorge Teixeira", "Zona Leste", -3.075, -3.030, -59.955, -59.905),
        District("Mauazinho", "Zona Leste", -3.155, -3.125, -59.945, -59.910),
        District("Colônia Antônio Aleixo", "Zona Leste", -3.135, -3.095, -59.925, -59.870),

        // Zona Norte
        District("Cidade Nova", "Zona Norte", -3.065, -3.010, -60.018, -59.958),
        District("Novo Aleixo", "Zona Norte", -3.075, -3.045, -59.980, -59.945),
        District("Cidade de Deus", "Zona Norte", -3.045, -3.015, -59.965, -59.925),
        District("Monte das Oliveiras", "Zona Norte", -3.035, -2.995, -60.025, -59.985),
        District("Colônia Santo Antônio", "Zona Norte", -3.055, -3.025, -60.035, -60.005),
        District("Colônia Terra Nova", "Zona Norte", -3.045, -3.010, -60.045, -60.010),
        District("Nova Cidade", "Zona Norte", -3.025, -2.990, -60.015, -59.975),
        District("Santa Etelvina", "Zona Norte", -3.015, -2.975, -60.040, -59.995)
    )

    fun isManausCoordinates(lat: Double, lon: Double): Boolean =
        lat in -3.25..-2.90 && lon in -60.18..-59.80

    /**
     * Resolves the exact Manaus neighborhood from coordinates or extracts it from the Nominatim
     * display_name if address fields failed to provide a valid name.
     */
    fun resolveDistrict(lat: Double, lon: Double, displayName: String = ""): District? {
        if (!isManausCoordinates(lat, lon)) return null

        // 1. Try matching known district names inside the full Nominatim display_name
        if (displayName.isNotBlank()) {
            val lower = displayName.lowercase()
            val match = DISTRICTS.firstOrNull { d ->
                lower.contains(d.name.lowercase())
            }
            if (match != null) return match
        }

        // 2. Spatial bounding box lookup
        val candidates = DISTRICTS.filter { d ->
            lat in d.minLat..d.maxLat && lon in d.minLon..d.maxLon
        }

        // Pick the most specific (smallest bounding area) if multiple match
        return candidates.minByOrNull { d ->
            (d.maxLat - d.minLat) * (d.maxLon - d.minLon)
        }
    }
}

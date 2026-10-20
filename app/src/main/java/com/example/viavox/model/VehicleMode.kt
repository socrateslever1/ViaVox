package com.example.viavox.model

/** UI profile. A routing profile is separate and must be supplied by the backend. */
enum class VehicleMode(val storedValue: String, val displayName: String) {
    CAR("car", "Carro"),
    MOTORCYCLE("motorcycle", "Moto"),
    WALKING("walking", "A pé");

    companion object {
        fun from(value: String?): VehicleMode =
            entries.firstOrNull { it.storedValue.equals(value, ignoreCase = true) } ?: CAR
    }
}

/**
 * Navigation displays must not pass driving-only routes off as pedestrian
 * directions. OSRM's public 'driving' backend is car-only.
 */
object SupportedRoutingProfile {
    fun isSupported(mode: VehicleMode): Boolean = mode != VehicleMode.WALKING
    fun disclosure(mode: VehicleMode): String = when (mode) {
        VehicleMode.CAR -> "Trajeto de carro"
        VehicleMode.MOTORCYCLE -> "Trajeto rodoviário (perfil de carro); não considera restrições específicas para motos"
        VehicleMode.WALKING -> "Navegação a pé ainda não disponível; posição e bússola funcionam sem rota"
    }
}

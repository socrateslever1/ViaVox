package com.example.viavox.model

/**
 * A reminder placed at the current position begins disarmed. It only triggers
 * after the person has genuinely left the geofence and returned with precise GPS.
 */
data class GeoReminder(
    val id: String,
    val title: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = 100,
    val armed: Boolean = false,
    val completed: Boolean = false
)

data class ReminderEvaluation(
    val items: List<GeoReminder>,
    val triggered: List<GeoReminder>
)

/** Pure state machine: conservative entry/exit thresholds prevent GPS jitter. */
object GeoReminderPolicy {
    fun evaluate(
        reminders: List<GeoReminder>,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float
    ): ReminderEvaluation {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            !accuracyMeters.isFinite() || accuracyMeters <= 0f || accuracyMeters > 25f) {
            return ReminderEvaluation(reminders, emptyList())
        }
        val triggered = mutableListOf<GeoReminder>()
        val updated = reminders.map { reminder ->
            if (reminder.completed) return@map reminder
            val radius = reminder.radiusMeters.coerceIn(75, 500).toDouble()
            val distance = PointOfInterest(reminder.id, reminder.title, PoiCategory.PUBLIC,
                reminder.latitude, reminder.longitude).distanceTo(latitude, longitude)
            when {
                !reminder.armed && distance - accuracyMeters > radius + 35.0 ->
                    reminder.copy(armed = true)
                reminder.armed && distance + accuracyMeters <= radius -> {
                    val finished = reminder.copy(completed = true)
                    triggered += finished
                    finished
                }
                else -> reminder
            }
        }
        return ReminderEvaluation(updated, triggered)
    }
}

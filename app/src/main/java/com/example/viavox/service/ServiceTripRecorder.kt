package com.example.viavox.service

import android.content.Context
import android.util.Log
import com.example.viavox.data.TripRecordingRepository
import com.example.viavox.data.TripRecordingState
import com.example.viavox.data.ViaVoxHistoryDatabase
import com.example.viavox.model.NavigationMath
import com.example.viavox.model.TripPoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The same GPS quality and anti-jitter rules apply with the screen on or off. */
class TripPointGate {
    fun distanceIfAccepted(previous: TripPoint?, current: TripPoint): Double? {
        if (!current.latitude.isFinite() || !current.longitude.isFinite() ||
            current.latitude !in -90.0..90.0 || current.longitude !in -180.0..180.0 ||
            !current.accuracy.isFinite() || current.accuracy > 45f ||
            current.accuracy <= 0f
        ) return null
        if (previous == null) return 0.0
        val seconds = (current.recordedAt - previous.recordedAt) / 1000.0
        if (seconds < 3.0) return null
        val meters = NavigationMath.meters(previous.latitude, previous.longitude,
            current.latitude, current.longitude)
        if (meters < 6.0) return null
        // Avoid GPS teleportation without excluding normal road speeds.
        if (meters > 65.0 * seconds + current.accuracy + previous.accuracy) return null
        return meters
    }
}

/**
 * Persists every accepted point on the GPS service's IO coroutine, with a
 * mutex to prevent races between start, fix, stop and restoration. UI state is
 * a read-only snapshot; background recording never depends on a ViewModel.
 */
class ServiceTripRecorder(context: Context) {
    private val db = ViaVoxHistoryDatabase(context)
    private val prefs = context.getSharedPreferences("viavox_trip_session", Context.MODE_PRIVATE)
    private val gate = TripPointGate()
    private val mutex = Mutex()
    private var activeTripId = 0L
    private var cumulativeMeters = 0.0
    private var lastPoint: TripPoint? = null
    private var savedPoints: List<TripPoint> = emptyList()

    suspend fun restore(): TripRecordingState = mutex.withLock {
        val persistedId = prefs.getLong("active_trip_id",0L)
        if (persistedId > 0L) {
            val stored = db.getTrips().firstOrNull { it.id == persistedId && it.finishedAt == 0L }
            if (stored != null) {
                activeTripId = stored.id
                cumulativeMeters = stored.distanceMeters
                savedPoints = stored.points.takeLast(4500)
                lastPoint = stored.points.lastOrNull()
                return@withLock publish()
            }
            prefs.edit().remove("active_trip_id").commit()
        }
        db.recoverUnfinishedTrips()
        publish()
    }

    suspend fun start(): TripRecordingState = mutex.withLock {
        if (activeTripId != 0L) return@withLock publish()
        activeTripId = db.startTrip()
        cumulativeMeters = 0.0
        lastPoint = null
        savedPoints = emptyList()
        if (!prefs.edit().putLong("active_trip_id",activeTripId).commit()) {
            Log.w("ViaVoxTrip","Could not persist active trip identifier")
        }
        publish()
    }

    suspend fun append(point: TripPoint): TripRecordingState = mutex.withLock {
        if (activeTripId == 0L) return@withLock publish()
        val meters = gate.distanceIfAccepted(lastPoint,point) ?: return@withLock publish()
        val newDistance = cumulativeMeters + meters
        db.appendTripPoint(activeTripId,point,newDistance)
        lastPoint = point
        cumulativeMeters = newDistance
        savedPoints = (savedPoints + point).takeLast(4500)
        publish()
    }

    suspend fun finish(): TripRecordingState = mutex.withLock {
        if (activeTripId != 0L) db.finishTrip(activeTripId)
        activeTripId = 0L
        lastPoint = null
        cumulativeMeters = 0.0
        savedPoints = emptyList()
        prefs.edit().remove("active_trip_id").commit()
        publish()
    }

    private fun publish(): TripRecordingState {
        val snapshot = TripRecordingState(
            tripId = activeTripId, recording = activeTripId > 0,
            meters = cumulativeMeters, count = if (activeTripId > 0) savedPoints.size else 0,
            points = savedPoints
        )
        TripRecordingRepository.update(snapshot)
        return snapshot
    }

    fun close() = db.close()
}

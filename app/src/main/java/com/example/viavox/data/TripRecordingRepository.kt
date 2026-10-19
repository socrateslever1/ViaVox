package com.example.viavox.data

import com.example.viavox.model.TripPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Session snapshot emitted by the GPS service, not owned by any screen. */
data class TripRecordingState(
    val tripId: Long = 0L,
    val recording: Boolean = false,
    val meters: Double = 0.0,
    val count: Int = 0,
    val points: List<TripPoint> = emptyList()
)

object TripRecordingRepository {
    private val mutable = MutableStateFlow(TripRecordingState())
    val state: StateFlow<TripRecordingState> = mutable.asStateFlow()

    fun update(next: TripRecordingState) { mutable.value = next }
}

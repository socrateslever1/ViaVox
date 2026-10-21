package com.example.viavox.service

/**
 * Single admission point for navigation audio. Playback starts only when the
 * the Android speech engine reports actual completion.
 * All calls must run on the service's main thread.
 */
enum class SpeechKind(val priority: Int, val maxAgeMs: Long) {
    NAVIGATION(4, 12_000L),
    GEOGRAPHY(3, 25_000L),
    SAFETY(3, 12_000L),
    USER_REQUEST(4, 60_000L),
    POI(1, 9_000L)
}

data class SpeechEvent(
    val kind: SpeechKind,
    val text: String,
    val key: String,
    val createdAt: Long,
    val force: Boolean = false
)

class SpeechOrchestrator(
    private val play: (text: String, done: () -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxQueue: Int = 12,
    private val onDrop: (SpeechEvent) -> Unit = {},
    /** Revalidate a queued POI against the latest GPS *before* TTS starts. */
    private val canPlay: (SpeechEvent) -> Boolean = { true }
) {
    private val waiting = mutableListOf<SpeechEvent>()
    private var active: SpeechEvent? = null
    private var generation: Long = 0L
    var enabled: Boolean = true
        private set

    val isBusy: Boolean get() = active != null
    val pendingCount: Int get() = waiting.size
    val currentKind: SpeechKind? get() = active?.kind

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) cancelAll() else pump()
    }

    fun cancelAll() {
        generation++
        waiting.forEach(onDrop)
        waiting.clear()
        active = null
    }

    fun offer(kind: SpeechKind, text: String, key: String, force: Boolean = false): Boolean {
        if (!enabled || text.isBlank()) return false
        val now = clock()
        val event = SpeechEvent(kind,text.trim(),key,now,force)
        // A duplicate already playing must not interrupt itself.
        if (!force && (active?.key == key || waiting.any { it.key == key })) return false
        // Fresh street/area update makes a pending old geographical fix obsolete.
        if (kind == SpeechKind.GEOGRAPHY) waiting.removeAll {
            it.kind == SpeechKind.GEOGRAPHY
        }
        if (kind == SpeechKind.POI) waiting.removeAll { it.kind == SpeechKind.POI }
        waiting.add(event)
        if (waiting.size > maxQueue) {
            val last = waiting.minWithOrNull(
                compareBy<SpeechEvent> { it.kind.priority }.thenBy { it.createdAt }
            )
            if (last != null) {
                waiting.remove(last)
                onDrop(last)
            }
        }
        pump()
        return active === event || waiting.contains(event)
    }

    private fun pump() {
        if (!enabled || active != null) return
        val now = clock()
        val expired = waiting.filter { now - it.createdAt > it.kind.maxAgeMs }
        waiting.removeAll(expired)
        expired.forEach(onDrop)
        val next = waiting.maxWithOrNull(
            compareBy<SpeechEvent> { it.kind.priority }.thenByDescending { it.createdAt }
        ) ?: return
        waiting.remove(next)
        // The vehicle may have driven past this POI while navigation or a
        // road name was being spoken. Never say it without fresh evidence.
        if (!canPlay(next)) {
            onDrop(next)
            pump()
            return
        }
        active = next
        val ticket = ++generation
        try {
            play(next.text) {
                if (generation == ticket && active === next) {
                    active = null
                    pump()
                }
            }
        } catch (_: Exception) {
            if (generation == ticket) {
                active = null
                pump()
            }
        }
    }
}

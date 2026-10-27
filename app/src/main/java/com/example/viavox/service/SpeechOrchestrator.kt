package com.example.viavox.service

/**
 * Single admission point for navigation audio. Playback starts only when the
 * previous provider (Android or remote Francisca) reports actual completion.
 * All calls must run on the service's main thread.
 */
enum class SpeechKind(val priority: Int, val maxAgeMs: Long) {
    NAVIGATION(4, 12_000L),
    GEOGRAPHY(3, 25_000L),
    SAFETY(3, 12_000L),
    USER_REQUEST(4, 60_000L),
    REMINDER(3, 40_000L),
    POI(1, 9_000L),
    TOUR(1, 10_000L)
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
    /** Called before a street announcement supersedes an ongoing optional Tour/POI. */
    private val onInterrupt: () -> Unit = {},
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
        // An active optional Tour or POI must not delay an actual street entry
        // by several seconds. Navigation instructions remain uninterrupted.
        if (kind == SpeechKind.GEOGRAPHY && active?.kind in
            setOf(SpeechKind.TOUR, SpeechKind.POI)) {
            val interrupted = active
            generation++
            active = null
            interrupted?.let(onDrop)
            try { onInterrupt() } catch (_: Exception) {}
        }
        // Never discard a zone or neighbourhood announcement merely because
        // a road change was resolved a moment later. Only optional Tour items
        // are disposable; fresh geographic events remain ordered by arrival.
        if (kind == SpeechKind.GEOGRAPHY) waiting.removeAll {
            it.kind == SpeechKind.TOUR
        }
        if (kind == SpeechKind.POI) waiting.removeAll { it.kind == SpeechKind.POI }
        if (kind == SpeechKind.TOUR) waiting.removeAll { it.kind == SpeechKind.TOUR }
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

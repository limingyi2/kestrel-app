package io.github.nanomuse.hands

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/** Monotonic milestones shared by the chat turn and its native Hands call. No screen text. */
class HandsTimeline(instructionReceived: Boolean = false, private val origin: Long = SystemClock.elapsedRealtime()) {
    private val milestones = linkedMapOf<String, Long>()

    init { if (instructionReceived) mark("instruction_received") }

    @Synchronized fun mark(name: String) {
        milestones.putIfAbsent(name, SystemClock.elapsedRealtime() - origin)
    }

    @Synchronized fun snapshot(): Map<String, Long> = milestones.toMap()

    @Synchronized private fun forRun(): HandsTimeline = HandsTimeline(origin = origin).also {
        it.milestones.putAll(milestones)
    }

    companion object {
        private val sessions = ConcurrentHashMap<String, HandsTimeline>()
        fun bind(sessionId: String, timeline: HandsTimeline) {
            // Only a bridge for the current turn, bounded even if no Hands call follows.
            if (sessions.size >= 128) sessions.clear()
            sessions[sessionId] = timeline
        }
        fun take(sessionId: String?): HandsTimeline? = sessionId?.let { sessions.remove(it)?.forRun() }
        fun forget(sessionId: String, timeline: HandsTimeline) { sessions.remove(sessionId, timeline) }
    }
}

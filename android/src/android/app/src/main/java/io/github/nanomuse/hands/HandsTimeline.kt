package io.github.nanomuse.hands

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.io.File
import org.json.JSONObject

/** Monotonic milestones shared by the chat turn and its native Hands call. No screen text. */
class HandsTimeline(
    instructionReceived: Boolean = false,
    private val origin: Long = SystemClock.elapsedRealtime(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val milestones = linkedMapOf<String, Long>()
    private var summaries = CopyOnWriteArrayList<File>()

    init { if (instructionReceived) milestones["instruction_received"] = 0L }

    @Synchronized fun mark(name: String) {
        milestones.putIfAbsent(name, clock() - origin)
    }

    @Synchronized fun snapshot(): Map<String, Long> = milestones.toMap()

    @Synchronized private fun forRun(): HandsTimeline = HandsTimeline(origin = origin, clock = clock).also {
        it.milestones.putAll(milestones)
        it.summaries = summaries
    }

    fun registerSummary(file: File) { summaries.add(file) }

    /** Called off the UI thread after the main agent ends, including its final model response. */
    fun agentFinishedAt(elapsedRealtimeMs: Long) {
        val ended = (elapsedRealtimeMs - origin).coerceAtLeast(0L)
        summaries.forEach { file -> runCatching {
            val summary = JSONObject(file.readText())
            val times = summary.optJSONObject("milestones_ms") ?: JSONObject()
            if (!times.has("agent_task_finished")) {
                times.put("agent_task_finished", ended)
                summary.put("milestones_ms", times)
                file.writeText(summary.toString())
            }
        } }
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

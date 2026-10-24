package io.github.nanomuse.hands

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HandsTimelineTest {
    @Test fun `runs share instruction clock without sharing action milestones`() {
        var now = 100L
        val turn = HandsTimeline(instructionReceived = true, origin = 0L, clock = { now })
        turn.mark("hands_tool_call")
        HandsTimeline.bind("timeline-test", turn)
        val first = HandsTimeline.take("timeline-test")!!
        now = 200L
        first.mark("first_android_action")
        HandsTimeline.bind("timeline-test", turn)
        val second = HandsTimeline.take("timeline-test")!!
        assertFalse(second.snapshot().containsKey("first_android_action"))
        now = 500L
        second.mark("first_android_action")
        assertEquals(200L, first.snapshot()["first_android_action"])
        assertEquals(500L, second.snapshot()["first_android_action"])
        assertEquals(0L, second.snapshot()["instruction_received"])
    }

    @Test fun `main agent completion updates registered summary without changing hands finish`() {
        val directory = Files.createTempDirectory("hands-timeline").toFile()
        try {
            val summary = File(directory, "run-summary.json")
            summary.writeText("""{"outcome":"done","milestones_ms":{"task_finished":400}}""")
            val turn = HandsTimeline(origin = 100L, clock = { 100L })
            HandsTimeline.bind("timeline-finish", turn)
            HandsTimeline.take("timeline-finish")!!.registerSummary(summary)
            turn.agentFinishedAt(700L)
            val result = JSONObject(summary.readText())
            val times = result.getJSONObject("milestones_ms")
            assertEquals(400L, times.getLong("task_finished"))
            assertEquals(600L, times.getLong("agent_task_finished"))
            assertEquals("done", result.getString("outcome"))
            turn.agentFinishedAt(900L)
            assertEquals(600L, JSONObject(summary.readText()).getJSONObject("milestones_ms").getLong("agent_task_finished"))
        } finally { directory.deleteRecursively() }
    }
}

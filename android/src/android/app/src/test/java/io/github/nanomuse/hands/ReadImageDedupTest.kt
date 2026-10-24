package io.github.nanomuse.hands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behavior-level gate on re-reading a Hands screenshot the hands model already saw:
 * one attach per user turn for run screenshots only; ordinary read_image uses are untouched.
 */
class ReadImageDedupTest {
    private val hands =
        "/var/minis/attachments/hands/20261024-093015-123/step-4.jpg"
    private val handsSessionScoped =
        "/data/user/0/io.github.limingyi2.kestrel/files/minis-sessions/s1/attachments/hands/20261024-093015-123/step-4.jpg"
    private val chart = "/var/minis/attachments/generated/chart.png"

    @Test fun `the same hands screenshot is attached once per turn`() {
        val d = ReadImageDedup()
        assertTrue(d.shouldAttach(hands))
        assertFalse(d.shouldAttach(hands))
    }

    @Test fun `a session-scoped hands screenshot is gated the same way`() {
        val d = ReadImageDedup()
        assertTrue(d.shouldAttach(handsSessionScoped))
        assertFalse(d.shouldAttach(handsSessionScoped))
    }

    @Test fun `a different step of the same run is a different picture`() {
        val d = ReadImageDedup()
        assertTrue(d.shouldAttach(hands))
        assertTrue(d.shouldAttach("/var/minis/attachments/hands/20261024-093015-123/step-5.jpg"))
    }

    @Test fun `a new user turn reopens the gate for the same picture`() {
        val d = ReadImageDedup()
        assertTrue(d.shouldAttach(hands))
        d.onUserTurn()
        assertTrue(d.shouldAttach(hands))
    }

    @Test fun `ordinary images are never gated`() {
        val d = ReadImageDedup()
        assertTrue(d.shouldAttach(chart))
        assertTrue(d.shouldAttach(chart))
        assertTrue(d.shouldAttach(chart))
    }

    @Test fun `only hands run screenshots match, not any hands path`() {
        val d = ReadImageDedup()
        assertFalse(d.isHandsScreen("/var/minis/attachments/hands/"))
        assertFalse(d.isHandsScreen("/var/minis/attachments/hands/notes.txt"))
        assertFalse(d.isHandsScreen("/var/minis/attachments/hands/20261024-093015/step-1.jpg")) // no millis
        assertTrue(d.isHandsScreen(hands))
        assertTrue(d.isHandsScreen("/var/minis/attachments/hands/20261024-093015-123/step-1.jpg"))
    }

    // ── the completed-run gate: even the FIRST read is answered with a note ──

    private val runResult = """
        {
          "ok": true,
          "outcome": "done",
          "steps": 5,
          "run_id": "20261024-093015-123",
          "last_screen": "minis://attachments/hands/20261024-093015-123/step-5.jpg",
          "answer": "已找到价格较低的牛奶商品。"
        }
    """.trimIndent()

    private val lastScreenHost = "/var/minis/attachments/hands/20261024-093015-123/step-5.jpg"

    @Test fun `a completed run's last_screen is not read even the first time`() {
        val d = ReadImageDedup()
        d.noteFromRunResult(runResult)
        // the agent's first read_image habit after a hands run is blocked: the result text
        // is authoritative. The JSON carries the minis:// URL; the read resolves to the
        // host path — both must land on the same gate.
        assertFalse(d.shouldAttach("minis://attachments/hands/20261024-093015-123/step-5.jpg"))
        assertFalse(d.shouldAttach(lastScreenHost))
        assertFalse(d.shouldAttach(handsSessionScoped.replace("step-4.jpg", "step-5.jpg")))
    }

    @Test fun `the user asking again reopens a completed run's gate`() {
        val d = ReadImageDedup()
        d.noteFromRunResult(runResult)
        assertFalse(d.shouldAttach(lastScreenHost))
        d.onUserTurn()
        assertTrue(d.shouldAttach(lastScreenHost))
    }

    @Test fun `a failed or stopped run's last_screen stays readable`() {
        val d = ReadImageDedup()
        d.noteFromRunResult(runResult.replace("\"outcome\": \"done\"", "\"outcome\": \"failed\""))
        assertTrue(d.shouldAttach(hands))
        d.onUserTurn()
        d.noteFromRunResult(runResult.replace("\"outcome\": \"done\"", "\"outcome\": \"stopped\""))
        assertTrue(d.shouldAttach(hands))
    }

    @Test fun `run results without a hands last_screen change nothing`() {
        val d = ReadImageDedup()
        d.noteFromRunResult(runResult.replace(Regex("\"last_screen\"[^,]*,"), "\"last_screen\": null,"))
        d.noteFromRunResult("ls -la\n(ordinary shell output)")
        assertTrue(d.shouldAttach(hands))
    }

    @Test fun `a completed run's gate is scoped to hands screenshots only`() {
        val d = ReadImageDedup()
        d.noteFromRunResult(runResult)
        assertTrue(d.shouldAttach(chart))
        assertTrue(d.shouldAttach("/var/minis/attachments/hands/20261024-093015-123/step-4.jpg")) // an earlier step of the run
    }
}

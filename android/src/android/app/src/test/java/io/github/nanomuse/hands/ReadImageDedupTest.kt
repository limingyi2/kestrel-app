package io.github.nanomuse.hands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behavior-level gate on reading a Hands screenshot within one user turn: one attach
 * per turn for run screenshots only; the FIRST read is always allowed (P0: the chat model's
 * context is independent of the hands model's — it may genuinely need to look); ordinary
 * read_image uses are untouched.
 */
class ReadImageDedupTest {
    private val hands =
        "/var/minis/attachments/hands/20261024-093015-123/step-4.jpg"
    private val handsSessionScoped =
        "/data/user/0/io.github.limingyi2.kestrel/files/minis-sessions/s1/attachments/hands/20261024-093015-123/step-4.jpg"
    private val chart = "/var/minis/attachments/generated/chart.png"

    @Test fun `the first read of a hands screenshot is always allowed`() {
        // P0 regression guard: the outer agent never saw the pixels (they went to the hands
        // model in a separate context), so blocking the first read strands it.
        val d = ReadImageDedup()
        assertTrue(d.shouldAttach(hands))
    }

    @Test fun `a second read of the same hands screenshot in the turn is a note`() {
        val d = ReadImageDedup()
        assertTrue(d.shouldAttach(hands))
        assertFalse(d.shouldAttach(hands))
    }

    @Test fun `the same picture under a different path form is still one picture`() {
        val d = ReadImageDedup()
        assertTrue(d.shouldAttach("minis://attachments/hands/20261024-093015-123/step-5.jpg"))
        assertFalse(d.shouldAttach("/var/minis/attachments/hands/20261024-093015-123/step-5.jpg"))
        assertFalse(d.shouldAttach(handsSessionScoped.replace("step-4.jpg", "step-5.jpg")))
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
}

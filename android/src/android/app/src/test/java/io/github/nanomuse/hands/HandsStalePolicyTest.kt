package io.github.nanomuse.hands

import io.github.nanomuse.hands.HandsStalePolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a decided action is judged after the page moved while it was being decided.
 *
 * Regression history this suite guards:
 * - P1.6: a content event during observation threw every decision away; an animated home
 *   page looped screenshot → decide → discard without ever tapping (invisible to the loop
 *   watch, which only sees executed actions).
 * - PR #7 first cut: the whole-image byte compare failed forever on animated pages, the
 *   window-switch re-observation had no bound, and alternating strategies reset the
 *   coordinate budget — the run kept "thinking" and never tapped.
 * - P0 restore: local evidence (the screenshot region around the point, or a live label)
 *   unblocks honest taps, and ONE shared budget bounds every rejection whatever refused
 *   the step, reset only by an actually executed action.
 */
class HandsStalePolicyTest {

    // ── after the model decided ────────────────────────────────────────────

    @Test fun `a window change sends a screen action back to observation`() {
        assertEquals(Verdict.REOBSERVE, HandsStalePolicy.afterDecision(windowChanged = true, contentChanged = true, actsOnScreen = true))
    }

    @Test fun `content events alone do not discard a node-grounded tap`() {
        assertEquals(Verdict.ACT, HandsStalePolicy.afterDecision(windowChanged = false, contentChanged = true, actsOnScreen = true, tapGrounded = true))
    }

    @Test fun `content events plus no unique node demand local evidence`() {
        assertEquals(Verdict.VERIFY, HandsStalePolicy.afterDecision(windowChanged = false, contentChanged = true, actsOnScreen = true, tapGrounded = false))
    }

    @Test fun `an unchanged screen needs no coordinate verification`() {
        assertEquals(Verdict.ACT, HandsStalePolicy.afterDecision(windowChanged = false, contentChanged = false, actsOnScreen = true))
    }

    @Test fun `content events do not gate non-tap actions that revalidate themselves`() {
        assertEquals(Verdict.ACT, HandsStalePolicy.afterDecision(windowChanged = false, contentChanged = true, actsOnScreen = true, tapGrounded = null))
    }

    @Test fun `a status verdict is re-verified after a window switch`() {
        assertEquals(Verdict.REOBSERVE, HandsStalePolicy.afterDecision(windowChanged = true, contentChanged = true, actsOnScreen = true))
    }

    @Test fun `a status verdict stands on content-only changes`() {
        assertEquals(Verdict.ACT, HandsStalePolicy.afterDecision(windowChanged = false, contentChanged = true, actsOnScreen = true))
    }

    @Test fun `a paused or waiting answer survives a window change`() {
        assertEquals(Verdict.ACT, HandsStalePolicy.afterDecision(windowChanged = true, contentChanged = true, actsOnScreen = false))
    }

    // ── immediately before the gesture, after approval ─────────────────────

    @Test fun `a stop never reaches the gesture`() {
        assertEquals(Verdict.REOBSERVE, HandsStalePolicy.beforeGesture(stopped = true, windowChanged = false, nodeMoved = false))
    }

    @Test fun `a window change before the gesture reobserves`() {
        assertEquals(Verdict.REOBSERVE, HandsStalePolicy.beforeGesture(stopped = false, windowChanged = true, nodeMoved = false))
    }

    @Test fun `a unique node that moved is not tapped at the old point`() {
        assertEquals(Verdict.REOBSERVE, HandsStalePolicy.beforeGesture(stopped = false, windowChanged = false, nodeMoved = true))
    }

    @Test fun `a quiet page and a still node tap`() {
        assertEquals(Verdict.ACT, HandsStalePolicy.beforeGesture(stopped = false, windowChanged = false, nodeMoved = false))
    }

    // ── local evidence: the region around the point ─────────────────────────

    private fun grid(fill: Int) = IntArray(12 * 20) { fill }

    @Test fun `an animation elsewhere leaves the region around the point provable`() {
        val old = grid(100)
        val new = grid(100)
        // the top banner (first two rows) cycles hard — the search box at mid-screen does not
        for (c in 0 until 12) { new[c] = (c * 37) % 256; new[12 + c] = (c * 91) % 256 }
        assertTrue(HandsStalePolicy.regionUnchanged(old, new, fracX = 0.5, fracY = 0.5))
    }

    @Test fun `the target region itself changing is no evidence`() {
        val old = grid(100)
        val new = grid(100)
        // the control under the point flipped: every cell of its rows changed
        for (r in 8..12) for (c in 3..8) new[r * 12 + c] = 200
        assertFalse(HandsStalePolicy.regionUnchanged(old, new, fracX = 0.5, fracY = 0.5))
    }

    @Test fun `a cursor blink inside the window is tolerated`() {
        val old = grid(100)
        val new = grid(100)
        new[10 * 12 + 6] = 60 // one cell flickers
        assertTrue(HandsStalePolicy.regionUnchanged(old, new, fracX = 0.5, fracY = 0.5))
    }

    @Test fun `mismatched or empty grids are no evidence`() {
        assertFalse(HandsStalePolicy.regionUnchanged(grid(100), IntArray(12 * 19) { 100 }, 0.5, 0.5))
        assertFalse(HandsStalePolicy.regionUnchanged(IntArray(0), IntArray(0), 0.5, 0.5))
    }

    @Test fun `the window hugs the screen edges without leaving the grid`() {
        val old = grid(100)
        val new = grid(100)
        assertTrue(HandsStalePolicy.regionUnchanged(old, new, fracX = 0.0, fracY = 0.0))
        assertTrue(HandsStalePolicy.regionUnchanged(old, new, fracX = 1.0, fracY = 1.0))
    }

    // ── the label proof ─────────────────────────────────────────────────────

    @Test fun `a label match reads the way a person would accept`() {
        assertTrue(HandsStalePolicy.labelMatchesTarget("搜索", "搜索 商品"))
        assertTrue(HandsStalePolicy.labelMatchesTarget("搜索商品", "搜 索 商 品"))
        assertTrue(HandsStalePolicy.labelMatchesTarget("search box", "Search"))
        assertFalse(HandsStalePolicy.labelMatchesTarget("购物车", "我的订单"))
        assertFalse(HandsStalePolicy.labelMatchesTarget("", "搜索"))
        assertFalse(HandsStalePolicy.labelMatchesTarget("搜", "搜索")) // a one-character target is no proof
        assertFalse(HandsStalePolicy.labelMatchesTarget("搜索", ""))
    }

    // ── behavior: the Pinduoduo home page, simulated end to end ────────────

    /**
     * Drives the exact decision sequence HandsOperator runs per step against a page that
     * animates everywhere and never settles, and asserts the P0 contract:
     * - an honest coordinate tap (its region static, label proof unneeded) executes on the
     *   first attempt — the P1.6 and PR#7-lockout behavior of never tapping is gone;
     * - whatever cannot be proven ends the run after exactly MAX_STALE_REJECTIONS refused
     *   steps — "keeps thinking" forever is impossible;
     * - rejections accumulate across refusal kinds and strategies: only an executed action
     *   restarts the budget.
     */
    @Test fun `an honest tap on an animated page executes at once, an unprovable one ends the run bounded`() {
        // per step: the observer fires (content always changing), no unique node matches,
        // the model insists on the coordinate tap
        fun decide(labelUnderPoint: String?, regionOk: Boolean, rejections: Int): String {
            val verdict = HandsStalePolicy.afterDecision(
                windowChanged = false, contentChanged = true, actsOnScreen = true, tapGrounded = false,
            )
            if (verdict == Verdict.VERIFY) {
                val labelProof = labelUnderPoint != null && HandsStalePolicy.labelMatchesTarget("搜索", labelUnderPoint)
                if (regionOk || labelProof) return "TAP"
                val rejections = rejections + 1
                return if (HandsStalePolicy.rejectionsExhausted(rejections)) "FAIL" else "REOBSERVE"
            }
            return "ACT"
        }

        // the search entrance region is static although the banner animates: taps at once
        assertEquals("TAP", decide(labelUnderPoint = null, regionOk = true, rejections = 0))
        // the pixels around the point moved but the live label names the target: taps too
        assertEquals("TAP", decide(labelUnderPoint = "搜索商品或店铺", regionOk = false, rejections = 2))
        // nothing proves the point: exactly MAX_STALE_REJECTIONS refusals, then a terminal stop
        var rejections = 0
        var outcome = ""
        var step = 0
        while (step < 10) {
            step++
            outcome = decide(labelUnderPoint = null, regionOk = false, rejections = rejections)
            if (outcome == "REOBSERVE") rejections++
            if (outcome == "FAIL") { rejections++; break } // the terminal refusal is one too
        }
        assertEquals("FAIL", outcome)
        assertEquals(HandsStalePolicy.MAX_STALE_REJECTIONS, rejections)
    }

    /**
     * The window-switch rejection path is bounded as well — the exact P0 "keeps thinking,
     * never taps" loop, where every step was discarded as stale and nothing but the 30-minute
     * run limit ever stopped it.
     */
    @Test fun `a page that keeps switching windows ends the run instead of thinking forever`() {
        var rejections = 0
        var ended = false
        var step = 0
        while (step < 10 && !ended) {
            step++
            // every step: a window event fired during observation, the model re-decides the tap
            val verdict = HandsStalePolicy.afterDecision(
                windowChanged = true, contentChanged = true, actsOnScreen = true, tapGrounded = true,
            )
            if (verdict == Verdict.REOBSERVE) {
                rejections++
                if (HandsStalePolicy.rejectionsExhausted(rejections)) ended = true
            }
        }
        assertTrue(ended)
        assertEquals(HandsStalePolicy.MAX_STALE_REJECTIONS, rejections)
    }

    /** Only an executed action restarts the budget — alternating rejections cannot reset it. */
    @Test fun `alternating refusal kinds and strategies still exhaust the shared budget`() {
        var rejections = 0
        // window-change rejection, then a coordinate rejection, then a moved-node rejection —
        // three different refusals, one budget
        repeat(2) { rejections++ } // stale_observation_skipped + stale_coordinate_rejected
        assertFalse(HandsStalePolicy.rejectionsExhausted(rejections))
        rejections++ // target_reconfirmation_failed
        assertTrue(HandsStalePolicy.rejectionsExhausted(rejections))
    }
}

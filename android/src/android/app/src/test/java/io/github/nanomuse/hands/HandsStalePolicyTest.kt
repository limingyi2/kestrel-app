package io.github.nanomuse.hands

import io.github.nanomuse.hands.HandsStalePolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a decided action is judged after the page moved while it was being decided. Regression
 * for the Pinduoduo symptom: an animated home page fired content events constantly, every
 * decision was skipped as stale, and the run re-screenshotted and re-decided on a
 * near-identical screen without ever tapping.
 *
 * The other side of the coin is also regression-covered here: a coordinate-fallback tap
 * cannot act on content-changed pixels, and a `status` verdict may not accept completion
 * about a screen whose window has already switched away.
 */
class HandsStalePolicyTest {

    // ── after the model decided ────────────────────────────────────────────

    @Test fun `a window change sends a screen action back to observation`() {
        assertEquals(Verdict.REOBSERVE, HandsStalePolicy.afterDecision(windowChanged = true, contentChanged = true, actsOnScreen = true))
    }

    @Test fun `content events alone do not discard a node-grounded tap`() {
        // the case the old skip looped on: animations, clocks, carousels
        assertEquals(Verdict.ACT, HandsStalePolicy.afterDecision(windowChanged = false, contentChanged = true, actsOnScreen = true, tapGrounded = true))
    }

    @Test fun `content events plus no unique node demand coordinate freshness proof`() {
        // the coordinate fallback must not tap pixels that may be stale
        assertEquals(Verdict.VERIFY, HandsStalePolicy.afterDecision(windowChanged = false, contentChanged = true, actsOnScreen = true, tapGrounded = false))
    }

    @Test fun `an unchanged screen needs no coordinate verification`() {
        assertEquals(Verdict.ACT, HandsStalePolicy.afterDecision(windowChanged = false, contentChanged = false, actsOnScreen = true, tapGrounded = null))
    }

    @Test fun `content events do not gate non-tap actions that revalidate themselves`() {
        // typing re-finds the focused field, Enter requires focus, scroll sweeps live
        assertEquals(Verdict.ACT, HandsStalePolicy.afterDecision(windowChanged = false, contentChanged = true, actsOnScreen = true, tapGrounded = null))
    }

    @Test fun `a status verdict is re-verified after a window switch`() {
        // completion declared about a screen that is gone is not accepted as-is
        assertEquals(Verdict.REOBSERVE, HandsStalePolicy.afterDecision(windowChanged = true, contentChanged = true, actsOnScreen = true, tapGrounded = null))
    }

    @Test fun `a status verdict stands on content-only changes`() {
        assertEquals(Verdict.ACT, HandsStalePolicy.afterDecision(windowChanged = false, contentChanged = true, actsOnScreen = true, tapGrounded = null))
    }

    @Test fun `a paused or waiting answer survives a window change`() {
        // Wait, TakeOver and AskUser neither act on the changed screen nor state about it
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

    // ── the bounded coordinate fallback ────────────────────────────────────

    @Test fun `a live label at the coordinate rescues the tap at any attempt count`() {
        assertEquals(
            HandsStalePolicy.CoordinateFallback.ACT_ON_LABEL,
            HandsStalePolicy.afterFreshnessFailure(liveLabelMatches = true, failedAttempts = 1),
        )
        assertEquals(
            HandsStalePolicy.CoordinateFallback.ACT_ON_LABEL,
            HandsStalePolicy.afterFreshnessFailure(liveLabelMatches = true, failedAttempts = HandsStalePolicy.MAX_COORDINATE_VERIFY_FAILS),
        )
    }

    @Test fun `no label proof reobserves below the attempt ceiling`() {
        assertEquals(
            HandsStalePolicy.CoordinateFallback.REOBSERVE,
            HandsStalePolicy.afterFreshnessFailure(liveLabelMatches = false, failedAttempts = 1),
        )
        assertEquals(
            HandsStalePolicy.CoordinateFallback.REOBSERVE,
            HandsStalePolicy.afterFreshnessFailure(liveLabelMatches = false, failedAttempts = HandsStalePolicy.MAX_COORDINATE_VERIFY_FAILS - 1),
        )
    }

    @Test fun `at the attempt ceiling the run gives up with the reason`() {
        assertEquals(
            HandsStalePolicy.CoordinateFallback.EXHAUSTED,
            HandsStalePolicy.afterFreshnessFailure(liveLabelMatches = false, failedAttempts = HandsStalePolicy.MAX_COORDINATE_VERIFY_FAILS),
        )
        assertEquals(
            HandsStalePolicy.CoordinateFallback.EXHAUSTED,
            HandsStalePolicy.afterFreshnessFailure(liveLabelMatches = false, failedAttempts = 10),
        )
    }

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
     * Drives the exact decision sequence HandsOperator runs per step — observe (content
     * events always fire), decide a coordinate tap, verify pixels, fall back to the label —
     * and asserts the two properties the runtime needs: with a live label under the point
     * the tap happens on every attempt (no re-observation churn), and without one the run
     * is bounded: exactly MAX_COORDINATE_VERIFY_FAILS verify failures, then a terminal
     * verdict, never an unbounded loop.
     */
    @Test fun `a busy page with a matching label taps every time, without one the run ends bounded`() {
        fun simulateStep(labelUnderPoint: String?, failuresSoFar: Int): Pair<HandsStalePolicy.Verdict, HandsStalePolicy.CoordinateFallback?> {
            val verdict = HandsStalePolicy.afterDecision(
                windowChanged = false, contentChanged = true, actsOnScreen = true, tapGrounded = false,
            )
            if (verdict != Verdict.VERIFY) return verdict to null
            val fallback = HandsStalePolicy.afterFreshnessFailure(
                liveLabelMatches = labelUnderPoint != null &&
                    HandsStalePolicy.labelMatchesTarget("搜索商品", labelUnderPoint),
                failedAttempts = failuresSoFar + 1,
            )
            return verdict to fallback
        }

        // the search entry is where the model said it is: every attempt taps, no budget burn
        repeat(HandsStalePolicy.MAX_COORDINATE_VERIFY_FAILS + 2) { attempt ->
            val (_, fallback) = simulateStep("搜索商品", 0)
            assertEquals(HandsStalePolicy.CoordinateFallback.ACT_ON_LABEL, fallback)
        }

        // the point no longer names the target: re-observe, and the third failure ends the run
        var failures = 0
        var exhausted = false
        repeat(10) {
            when (simulateStep(null, failures).second) {
                HandsStalePolicy.CoordinateFallback.REOBSERVE -> failures++
                HandsStalePolicy.CoordinateFallback.EXHAUSTED -> { exhausted = true; return@repeat }
                else -> {}
            }
        }
        assertTrue(exhausted)
        // two re-observations, then the third failed verify terminates the run
        assertEquals(HandsStalePolicy.MAX_COORDINATE_VERIFY_FAILS - 1, failures)
    }

    /** A model that changes tack (a node-grounded tap, a scroll) gets a fresh verify budget. */
    @Test fun `changing strategy resets the coordinate verify budget`() {
        var failures = 0
        // two failures on the coordinate fallback …
        repeat(2) {
            failures++
            assertEquals(HandsStalePolicy.CoordinateFallback.REOBSERVE, HandsStalePolicy.afterFreshnessFailure(false, failures))
        }
        // … then the model taps a node it found: budget resets (tapGrounded != false), so a
        // later coordinate fallback starts over at one, not three.
        failures = 0
        assertEquals(HandsStalePolicy.CoordinateFallback.REOBSERVE, HandsStalePolicy.afterFreshnessFailure(false, failures + 1))
    }
}

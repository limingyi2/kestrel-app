package io.github.nanomuse.hands

/**
 * Whether a decided action may still run after the page moved while it was being decided.
 *
 * A window switch is a real page change: the model looked at a screen that is gone, so the
 * step goes back to observation — including a `status` verdict, whose completion or
 * infeasibility statement would otherwise be accepted about a screen that no longer exists.
 *
 * A content event alone is not a page switch: animations, clocks and carousels fire
 * constantly on busy pages (a shopping app's home screen never goes quiet). But the two tap
 * groundings are not equally stale-proof, so they are judged separately:
 *
 * - A tap grounded on a unique live accessibility node acts: the executor re-resolves that
 *   node at act time, so it lands wherever the control is *now*.
 * - A tap falling back to the model's screenshot coordinate must first prove the screen
 *   still matches what the model saw ([Verdict.VERIFY]: a fresh byte-identical screenshot);
 *   a coordinate that cannot be re-verified never taps — stale coordinates tap the wrong
 *   thing silently, which is worse than another look.
 *
 * Everything else revalidates against the live tree on its own: typing requires a live
 * focused field, Enter requires focus, and a dangerous tap keeps the byte-identical
 * freshness check before the gesture.
 *
 * Pure Kotlin so the runtime's judgement can be exercised without a device.
 */
object HandsStalePolicy {
    enum class Verdict { ACT, VERIFY, REOBSERVE }

    /**
     * Called once the screen model has decided. [contentChanged]/[windowChanged] come from
     * the observation listener; [actsOnScreen] is false only for Wait, TakeOver and AskUser,
     * which neither act on the screen nor state something about it; [tapGrounded] is null
     * for non-tap actions (and whenever [contentChanged] is false, when no re-check is
     * needed), true when the tap's target resolves to one unique live node, false when the
     * tap would fall back to the model's screenshot coordinate.
     */
    fun afterDecision(
        windowChanged: Boolean,
        contentChanged: Boolean,
        actsOnScreen: Boolean,
        tapGrounded: Boolean? = null,
    ): Verdict = when {
        windowChanged && actsOnScreen -> Verdict.REOBSERVE
        contentChanged && tapGrounded == false -> Verdict.VERIFY
        else -> Verdict.ACT
    }

    /**
     * Called immediately before the gesture, after approval. [nodeMoved]: the tap was
     * grounded on a unique live node that no longer resolves to the same point.
     */
    fun beforeGesture(stopped: Boolean, windowChanged: Boolean, nodeMoved: Boolean): Verdict =
        if (stopped || windowChanged || nodeMoved) Verdict.REOBSERVE else Verdict.ACT

    // ── the bounded coordinate fallback ────────────────────────────────────

    /** What to do after a coordinate tap's pixel freshness check failed. */
    enum class CoordinateFallback { ACT_ON_LABEL, REOBSERVE, EXHAUSTED }

    /** Verify-failure attempts (fresh screenshot + new model call each) before the run gives up with the reason. */
    const val MAX_COORDINATE_VERIFY_FAILS = 3

    /**
     * A page with constant motion (a shopping home screen) can fail the byte-identical
     * freshness check forever, looping screenshot → model call → reject. The bounded escape,
     * in strict preference order: a *live* accessibility label at the coordinate that still
     * names the model's target is proof the point is not stale — animations elsewhere on the
     * page do not invalidate it, and the tap proceeds without a fresh look. Without that
     * proof the step goes back to observation for at most [MAX_COORDINATE_VERIFY_FAILS]
     * failed attempts ([failedAttempts] counts the failure just recorded); beyond that the
     * run ends with the reason on the table for the user. A stale coordinate is never
     * tapped: only the label match or the byte-identical screen can send the gesture.
     */
    fun afterFreshnessFailure(liveLabelMatches: Boolean, failedAttempts: Int): CoordinateFallback = when {
        liveLabelMatches -> CoordinateFallback.ACT_ON_LABEL
        failedAttempts >= MAX_COORDINATE_VERIFY_FAILS -> CoordinateFallback.EXHAUSTED
        else -> CoordinateFallback.REOBSERVE
    }

    /**
     * Whether a live label found at a coordinate still names [target]: containment in either
     * direction after whitespace is stripped and case folded, the same reading a person would
     * accept ("搜索" under the point when the model said it taps the search box — some trees
     * put a space between every glyph of a CJK label).
     */
    fun labelMatchesTarget(target: String, liveLabel: String): Boolean {
        val t = target.trim().replace(WHITESPACE, "").lowercase()
        val s = liveLabel.trim().replace(WHITESPACE, "").lowercase()
        if (t.length < 2 || s.isEmpty()) return false
        return s.contains(t) || t.contains(s)
    }

    private val WHITESPACE = Regex("\\s+")
}

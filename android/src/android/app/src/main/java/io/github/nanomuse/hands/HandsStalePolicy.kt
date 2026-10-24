package io.github.nanomuse.hands

import kotlin.math.abs

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
 * - A tap falling back to the model's screenshot coordinate must produce fresh local
 *   evidence before it taps: either the screenshot region *around the point* is still the
 *   picture the model saw ([regionUnchanged] on the grey fingerprint grid — an animation
 *   elsewhere on the page does not invalidate the point), or a live accessibility label at
 *   the point still names the target ([labelMatchesTarget]). A coordinate with neither
 *   proof never taps — stale coordinates tap the wrong thing silently, which is worse than
 *   another look.
 *
 * **Every rejection is counted, whatever path rejected it** (a stale window switch, an
 * unprovable coordinate, a moved node before the gesture). The budget is shared and only
 * reset when an action actually executes, so no alternation of rejections — including ones
 * the model varies between steps — can loop a run without acting: after
 * [MAX_STALE_REJECTIONS] rejections the run ends with the reason on the table.
 *
 * Pure Kotlin so the runtime's judgement can be exercised without a device.
 */
object HandsStalePolicy {
    enum class Verdict { ACT, VERIFY, REOBSERVE }

    /** Called once the screen model has decided. [contentChanged]/[windowChanged] come from
     * the observation listener; [actsOnScreen] is false only for Wait, TakeOver and AskUser,
     * which neither act on the screen nor state something about it; [tapGrounded] is null
     * for non-tap actions (and whenever [contentChanged] is false, when no re-check is
     * needed), true when the tap's target resolves to one unique live node, false when the
     * tap would fall back to the model's screenshot coordinate. */
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

    // ── the bounded rejection budget ───────────────────────────────────────

    /** Rejected steps (each costs a screenshot and a model call) before the run gives up with the reason. */
    const val MAX_STALE_REJECTIONS = 3

    /**
     * True when the shared rejection budget is spent: [rejections] counts every step that
     * was decided and then refused, whatever refused it, since the last executed action.
     */
    fun rejectionsExhausted(rejections: Int): Boolean = rejections >= MAX_STALE_REJECTIONS

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

    // ── local screenshot evidence ──────────────────────────────────────────

    /** The screenshot fingerprint is a [COLS]-column grey grid (HandsOperator.sample). */
    private const val COLS = 12

    /** The evidence window around a tap point: ±2 columns, ±3 rows of the grid. */
    private const val HALF_COLS = 2
    private const val HALF_ROWS = 3

    /** Small dynamic bits inside the window (a cursor, a clock) are tolerated. */
    private const val MAX_CHANGED_SHARE = 0.10

    /**
     * Whether the screenshot region *around a tap point* is still the picture the model
     * decided on: the same grey grid sampled from a fresh capture, cells within the window
     * compared with the loop-watch cell delta. This is the reliable local evidence for a
     * page that animates somewhere else — a whole-image compare would fail it forever,
     * while the point itself and its neighbourhood are untouched.
     *
     * [fracX]/[fracY] are the tap point as fractions of the screen (0–1). Grid shape
     * mismatch or an empty grid is treated as *no* evidence.
     */
    fun regionUnchanged(old: IntArray, new: IntArray, fracX: Double, fracY: Double): Boolean {
        if (old.size != new.size || old.isEmpty()) return false
        val rows = old.size / COLS
        if (rows <= 0) return false
        val cx = (fracX.coerceIn(0.0, 1.0) * COLS).toInt().coerceIn(0, COLS - 1)
        val cy = (fracY.coerceIn(0.0, 1.0) * rows).toInt().coerceIn(0, rows - 1)
        var changed = 0
        var total = 0
        for (r in (cy - HALF_ROWS)..(cy + HALF_ROWS)) {
            if (r < 0 || r >= rows) continue
            for (c in (cx - HALF_COLS)..(cx + HALF_COLS)) {
                if (c < 0 || c >= COLS) continue
                total++
                if (abs(old[r * COLS + c] - new[r * COLS + c]) > HandsLoopWatch.CELL_DELTA) changed++
            }
        }
        return total > 0 && changed <= total * MAX_CHANGED_SHARE
    }

    private val WHITESPACE = Regex("\\s+")
}

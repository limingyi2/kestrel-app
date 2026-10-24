package io.github.nanomuse.hands

/**
 * Behavior-level guard against re-reading a Hands screenshot the hands model already saw.
 *
 * A completed Hands run (`outcome: done`) judged its `last_screen` with the hands model and
 * reported the result as text; a chat-agent `read_image` of that same picture re-decodes a
 * JPEG for an answer that is already on the table. Two gates, both scoped to one user turn:
 *
 * - [noteFromRunResult] registers the `last_screen` of a completed run, so even the *first*
 *   `read_image` of that picture this turn is answered with a note instead of pixels.
 *   Failed, stopped and needs-user runs are not registered — there the agent may genuinely
 *   need to look.
 * - [shouldAttach] also allows one read per turn of a Hands screenshot that no completed
 *   run produced, and answers repeats with the note.
 *
 * Scope, deliberately narrow:
 * - Only Hands run screenshots (`…/hands/<run>/step-N.jpg`) are gated; charts, downloads,
 *   attachments and every other image read exactly as before.
 * - A new user message is new evidence: [onUserTurn] reopens both gates, so "look at this
 *   screenshot" after the user asks works normally. For fresh visual analysis of the same
 *   screen the hands can simply be run again.
 * - The Vision Group routing (a text-only main model querying through `prompt`) is not
 *   gated by the caller: a focused question about an image is a legitimate new query.
 *
 * Pure Kotlin so the gate can be exercised in a JVM test.
 */
class ReadImageDedup {
    private val readThisTurn = mutableSetOf<String>()
    private val completedRuns = mutableSetOf<String>()

    /** True when [path] points at a Hands run screenshot (`/hands/<run>/step-N.jpg`). */
    fun isHandsScreen(path: String): Boolean =
        HANDS_SCREEN.containsMatchIn(path.replace('\\', '/'))

    /**
     * Registers the `last_screen` of a Hands run result, as printed by `nanomuse-hands run`.
     * Only a completed run (`"outcome": "done"`) is registered: its result text is
     * authoritative and its final screenshot needs no second opinion this turn.
     */
    fun noteFromRunResult(runResultText: String) {
        if (!DONE_RUN.containsMatchIn(runResultText)) return
        val screen = LAST_SCREEN.find(runResultText)?.groupValues?.get(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: return
        if (isHandsScreen(screen)) completedRuns.add(canonical(screen))
    }

    /**
     * False when the caller should answer with a note instead of pixels: the picture is the
     * `last_screen` of a Hands run that completed this turn, or it was already attached
     * earlier this turn. True otherwise, and always true for anything that is not a Hands
     * run screenshot.
     */
    fun shouldAttach(path: String): Boolean {
        if (!isHandsScreen(path)) return true
        val key = canonical(path)
        if (key in completedRuns) return false
        return readThisTurn.add(key)
    }

    /** A new user message may ask about the same picture again: clear both gates. */
    fun onUserTurn() {
        readThisTurn.clear()
        completedRuns.clear()
    }

    companion object {
        // The run id is yyyyMMdd-HHmmss-SSS (HandsOperator.run); the path may be a host path
        // (/var/minis/attachments/hands/… or a session-scoped attachments dir) or the
        // minis:// URL the result JSON carries — matching the tail covers both.
        private val HANDS_SCREEN = Regex("/hands/\\d{8}-\\d{6}-\\d{3}/step-\\d+\\.jpg$")
        private val DONE_RUN = Regex("\"outcome\"\\s*:\\s*\"done\"")
        private val LAST_SCREEN = Regex("\"last_screen\"\\s*:\\s*\"([^\"]+)\"")

        /**
         * One picture, one key: the result JSON carries `minis://attachments/hands/…`, while
         * the read tool resolves to a host path (`/var/minis/…` or a session-scoped dir).
         * Everything up to the run's `/hands/` directory is prefix, so the key is the run id
         * and the file name.
         */
        private fun canonical(path: String): String {
            val p = path.replace('\\', '/').trim()
            val idx = p.indexOf("/hands/")
            return if (idx >= 0) p.substring(idx) else p
        }
    }
}

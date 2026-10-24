package io.github.nanomuse.hands

/**
 * Behavior-level guard against re-reading a Hands screenshot within one user turn.
 *
 * The prompt asks the chat agent to show a run's `last_screen` inline and never call
 * `read_image` on it; models do not always comply. A *second* `read_image` of the same
 * Hands run screenshot in the same turn is answered with a note instead of the pixels,
 * which were already attached by the first call and are still in the model's context.
 *
 * The **first** read of a Hands screenshot is deliberately never blocked (P0 regression
 * fix): the chat model's context is independent of the hands model's — the pixels went to
 * the hands model only — so the chat agent may genuinely need `read_image` to see a run's
 * final screen itself. Blocking that first read stranded the outer agent with an answer it
 * could not look at, and it kept calling tools instead of replying.
 *
 * Scope, deliberately narrow:
 * - Only Hands run screenshots (`…/hands/<run>/step-N.jpg`) are gated; charts, downloads,
 *   attachments and every other image read exactly as before.
 * - A new user message is new evidence: [onUserTurn] reopens the gate.
 * - The Vision Group routing (a text-only main model querying through `prompt`) is not
 *   gated by the caller: a focused question about an image is a legitimate new query.
 *
 * Pure Kotlin so the gate can be exercised in a JVM test.
 */
class ReadImageDedup {
    private val readThisTurn = mutableSetOf<String>()

    /** True when [path] points at a Hands run screenshot (`/hands/<run>/step-N.jpg`). */
    fun isHandsScreen(path: String): Boolean =
        HANDS_SCREEN.containsMatchIn(path.replace('\\', '/'))

    /**
     * False when this exact Hands screenshot was already returned earlier in this turn and
     * the caller should answer with a note instead of pixels; true otherwise — including
     * the first read — and always true for anything that is not a Hands run screenshot.
     */
    fun shouldAttach(path: String): Boolean {
        if (!isHandsScreen(path)) return true
        return readThisTurn.add(canonical(path))
    }

    /** A new user message may ask about the same picture again: clear the gate. */
    fun onUserTurn() {
        readThisTurn.clear()
    }

    companion object {
        // The run id is yyyyMMdd-HHmmss-SSS (HandsOperator.run); the path may be a host path
        // (/var/minis/attachments/hands/… or a session-scoped attachments dir) or the
        // minis:// URL the result JSON carries — matching the tail covers both.
        private val HANDS_SCREEN = Regex("/hands/\\d{8}-\\d{6}-\\d{3}/step-\\d+\\.jpg$")

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

package io.github.nanomuse.hands

/** Policy for returning to chat; cancellation and requests for user intervention remain in place. */
internal object HandsCompletionPolicy {
    fun shouldReturnToChat(outcome: String, enabled: Boolean): Boolean =
        enabled && (outcome == "done" || outcome == "failed")
}

package io.github.nanomuse.hands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsCompletionPolicyTest {
    @Test fun `success and recoverable errors return by default`() {
        assertTrue(HandsCompletionPolicy.shouldReturnToChat("done", enabled = true))
        assertTrue(HandsCompletionPolicy.shouldReturnToChat("failed", enabled = true))
    }

    @Test fun `stay preference and user action outcomes keep the target app in front`() {
        assertFalse(HandsCompletionPolicy.shouldReturnToChat("done", enabled = false))
        assertFalse(HandsCompletionPolicy.shouldReturnToChat("stopped", enabled = true))
        assertFalse(HandsCompletionPolicy.shouldReturnToChat("needs_user", enabled = true))
        assertFalse(HandsCompletionPolicy.shouldReturnToChat("infeasible", enabled = true))
    }
}

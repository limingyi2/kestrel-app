package io.github.nanomuse.hands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsPageStateTest {
    @Test fun `quiet with no change cannot shorten fallback`() {
        val state = HandsPageState(0)
        assertFalse(state.ready(2_000, true, false))
        state.event("TYPE_NOTIFICATION_STATE_CHANGED", 100)
        assertFalse(state.ready(2_000, true, false))
    }

    @Test fun `window switch needs visible content and a quiet interval`() {
        val state = HandsPageState(0)
        state.event("TYPE_WINDOW_STATE_CHANGED", 100)
        assertTrue(state.windowChanged)
        assertFalse(state.ready(349, true, false))
        assertFalse(state.ready(350, false, false))
        assertTrue(state.ready(350, true, false))
    }

    @Test fun `dynamic content and progress prevent premature readiness`() {
        val state = HandsPageState(0)
        state.event("TYPE_WINDOW_CONTENT_CHANGED", 100)
        assertFalse(state.ready(500, true, true))
        state.event("TYPE_VIEW_SCROLLED", 490)
        assertTrue(state.contentChanged)
        assertFalse(state.ready(500, true, false))
        assertTrue(state.ready(740, true, false))
    }
}

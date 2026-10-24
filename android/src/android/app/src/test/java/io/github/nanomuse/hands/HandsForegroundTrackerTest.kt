package io.github.nanomuse.hands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsForegroundTrackerTest {
    @Test fun `MainActivity lifecycle controls foreground confirmation state`() {
        HandsForegroundTracker.onPaused()
        assertFalse(HandsForegroundTracker.isResumed())

        HandsForegroundTracker.onResumed()
        assertTrue(HandsForegroundTracker.isResumed())

        HandsForegroundTracker.onPaused()
        assertFalse(HandsForegroundTracker.isResumed())
    }
}

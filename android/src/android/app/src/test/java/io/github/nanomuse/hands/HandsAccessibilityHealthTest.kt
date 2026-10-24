package io.github.nanomuse.hands

import org.junit.Assert.assertEquals
import org.junit.Test

class HandsAccessibilityHealthTest {
    @Test fun `revocation and a disconnected enabled service remain distinct`() {
        assertEquals("permission_disabled", HandsAccessibilityHealth.classify(false, false))
        assertEquals("permission_disabled", HandsAccessibilityHealth.classify(false, true))
        assertEquals("enabled_disconnected", HandsAccessibilityHealth.classify(true, false))
        assertEquals("connected", HandsAccessibilityHealth.classify(true, true))
        assertEquals("grant_unknown", HandsAccessibilityHealth.classify(null, false))
    }
}

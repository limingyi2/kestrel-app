package io.github.nanomuse.onboarding

import io.github.nanomuse.ui.onboarding.FirstRunSetup
import io.github.nanomuse.ui.onboarding.FirstRunSetup.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunSetupTest {
    @Test
    fun `a fresh install is guided to configure a provider before chat`() {
        assertTrue(FirstRunSetup.needed(hasProviders = false, hasSessions = false, done = false))
        assertTrue(FirstRunSetup.needed(hasProviders = true, hasSessions = false, done = false))
        assertFalse(FirstRunSetup.needed(hasProviders = true, hasSessions = false, done = true))
    }

    @Test
    fun `no provider always opens configuration even on an older install`() {
        assertTrue(FirstRunSetup.needed(hasProviders = false, hasSessions = true, done = true))
        assertFalse(FirstRunSetup.needed(hasProviders = true, hasSessions = true, done = false))
    }

    @Test
    fun `setup stages are welcome models hands and first conversation`() {
        assertEquals(Stage.WELCOME, FirstRunSetup.stage(hasGroups = true, modelsSkipped = false, handsSeen = false, hasProviders = false))
        assertEquals(Stage.MODELS, FirstRunSetup.stage(hasGroups = false, modelsSkipped = false, handsSeen = false, handsPossible = false, hasProviders = true))
        assertEquals(Stage.HANDS, FirstRunSetup.stage(hasGroups = true, modelsSkipped = false, handsSeen = false, handsPossible = true, hasProviders = true))
        assertEquals(Stage.MEET, FirstRunSetup.stage(hasGroups = true, modelsSkipped = false, handsSeen = true, hasProviders = true))
        assertEquals(Stage.MEET, FirstRunSetup.stage(hasGroups = true, modelsSkipped = false, handsSeen = false, handsPossible = false, hasProviders = true))
        assertEquals(Stage.HANDS, FirstRunSetup.stage(hasGroups = false, modelsSkipped = true, handsSeen = false, handsPossible = true, hasProviders = true))
    }

    @Test
    fun `the three dots group models with welcome`() {
        assertEquals(0, FirstRunSetup.dot(Stage.WELCOME))
        assertEquals(0, FirstRunSetup.dot(Stage.MODELS))
        assertEquals(1, FirstRunSetup.dot(Stage.HANDS))
        assertEquals(2, FirstRunSetup.dot(Stage.MEET))
    }
}

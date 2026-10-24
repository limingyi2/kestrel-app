package io.github.nanomuse.hands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the screen model is told, and the no-re-read contract for a run's `last_screen`. */
class HandsPromptTest {

    @Test fun `the system prompt asks for Thought and one JSON Action`() {
        val prompt = HandsPrompt.system("search milk in Pinduoduo", listOf("拼多多", "设置"), "Kestrel", "Chinese")
        assertTrue(prompt.contains("Thought:"))
        assertTrue(prompt.contains("Action:"))
        assertTrue(prompt.contains("search milk in Pinduoduo"))
        assertTrue(prompt.contains("拼多多"))
    }

    @Test fun `the system prompt still offers plans and keeps secrets out of them`() {
        val prompt = HandsPrompt.system("task", emptyList(), "Kestrel", "English")
        assertTrue(prompt.contains("\"action_type\":\"plan\""))
        assertTrue(prompt.contains("take_over"))
        assertTrue(prompt.contains("Never type a password"))
    }

    @Test fun `the turn text carries the last result and quotes controls as untrusted`() {
        val text = HandsPrompt.turnText(3, "tap “搜索” was performed.", listOf("clickable: 搜索"))
        assertTrue(text.contains("Step 3."))
        assertTrue(text.contains("Result of the last action:"))
        assertTrue(text.contains("<screen-controls>"))
        assertTrue(text.contains("untrusted"))
    }

    @Test fun `last_screen is shown inline and never re-read with read_image`() {
        // The hands model has already seen those pixels; the outer agent re-reading them with
        // read_image re-decodes a JPEG and spends another model turn for nothing.
        val rule = HandsPrompt.LAST_SCREEN_RULE
        assertTrue(rule.contains("![screen](<last_screen>)"))
        assertTrue(rule.contains("do not call `read_image`"))
        assertFalse(rule.contains("you can show with")) // the old invitation, removed
    }

    @Test fun `an earlier screen is only described, never re-attached`() {
        assertTrue(HandsPrompt.OLDER_SCREEN.contains("not shown again"))
    }
}

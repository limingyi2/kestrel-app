package io.github.nanomuse.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsNativePlanExecutorTest {
    private val point = HandsAction.Point(0, 0)

    @Test fun `plan runs only live expected controls and reuses a foreground app`() {
        val port = FakePort().apply {
            packageName = "com.android.settings"
            visible += "Settings"
            visible += "About phone"
            clickables["About phone"] = HandsNativePlanExecutor.Port.Clickable("about", "About phone", true, true)
            afterClick["about"] = "About phone"
        }
        val plan = HandsAction.Plan(listOf(
            HandsAction.PlanStep(HandsAction.OpenApp("Settings"), expectAfter = "Settings"),
            HandsAction.PlanStep(HandsAction.Click(point, "About phone"), "Settings", "About phone"),
        ))

        val result = HandsNativePlanExecutor(port, { false }).execute(plan)

        assertEquals(HandsNativePlanExecutor.State.COMPLETE, result.state)
        assertEquals(1, result.completedActions)
        assertEquals(0, port.launches)
        assertEquals(listOf("About phone"), port.clicks)
        assertEquals(1, result.eventStableTransitions)
    }

    @Test fun `missing expected page label stops before the following action`() {
        val port = FakePort().apply {
            visible += "Settings"
            clickables["Network"] = HandsNativePlanExecutor.Port.Clickable("network", "Network", true, true)
        }
        val plan = HandsAction.Plan(listOf(
            HandsAction.PlanStep(HandsAction.Click(point, "Network"), "Settings", "Network details"),
            HandsAction.PlanStep(HandsAction.Click(point, "Internet"), "Network details", null),
        ))

        val result = HandsNativePlanExecutor(port, { false }).execute(plan)

        assertEquals(HandsNativePlanExecutor.State.NEEDS_VISION, result.state)
        assertEquals(1, result.completedActions)
        assertEquals(listOf("Network"), port.clicks)
    }

    @Test fun `ambiguous target never clicks`() {
        val port = FakePort().apply {
            visible += "Settings"
            ambiguous += "Display"
        }
        val plan = HandsAction.Plan(listOf(
            HandsAction.PlanStep(HandsAction.Click(point, "Display"), "Settings", "Display settings"),
            HandsAction.PlanStep(HandsAction.Back, "Display settings", null),
        ))

        val result = HandsNativePlanExecutor(port, { false }).execute(plan)

        assertEquals(HandsNativePlanExecutor.State.NEEDS_VISION, result.state)
        assertEquals(0, result.completedActions)
        assertTrue(port.clicks.isEmpty())
    }

    @Test fun `text entry requires unique focused non-secret field and exact readback`() {
        val port = FakePort().apply {
            visible += "Search"
            editables["Search"] = HandsNativePlanExecutor.Port.Editable("search", "Search", true, true, true, false)
            fieldReadback = "dsh 桌宠插件"
            afterSetText = "Results"
        }
        val plan = HandsAction.Plan(listOf(
            HandsAction.PlanStep(HandsAction.InputText("dsh 桌宠插件", "Search"), "Search", "Results"),
            HandsAction.PlanStep(HandsAction.Back, "Results", null),
        ))
        val result = HandsNativePlanExecutor(port, { false }).execute(plan)
        assertEquals(HandsNativePlanExecutor.State.COMPLETE, result.state)
        assertEquals("dsh 桌宠插件", port.entered)
        assertEquals(2, result.completedActions)

        val passwordPort = FakePort().apply {
            visible += "Password"
            editables["Password"] = HandsNativePlanExecutor.Port.Editable("password", "Password", true, true, true, true)
        }
        val unsafe = HandsAction.Plan(listOf(
            HandsAction.PlanStep(HandsAction.InputText("123456", "Password"), "Password", "Accepted"),
            HandsAction.PlanStep(HandsAction.Back, "Accepted", null),
        ))
        val rejected = HandsNativePlanExecutor(passwordPort, { false }).execute(unsafe)
        assertEquals(HandsNativePlanExecutor.State.NEEDS_VISION, rejected.state)
        assertEquals(null, passwordPort.entered)
    }

    @Test fun `sensitive screen label is sent to the existing single action flow`() {
        val port = FakePort().apply {
            visible += "App"
            clickables["Next"] = HandsNativePlanExecutor.Port.Clickable("install", "Install this app", true, true)
        }
        val plan = HandsAction.Plan(listOf(
            HandsAction.PlanStep(HandsAction.Click(point, "Next"), "App", "Installed"),
            HandsAction.PlanStep(HandsAction.Back, "Installed", null),
        ))
        val result = HandsNativePlanExecutor(port, { false }).execute(plan)
        assertEquals(HandsNativePlanExecutor.State.NEEDS_VISION, result.state)
        assertTrue(port.clicks.isEmpty())
    }

    @Test fun `cancel prevents the next semantic action`() {
        var cancelled = false
        val port = FakePort().apply {
            visible += "Settings"
            clickables["About phone"] = HandsNativePlanExecutor.Port.Clickable("about", "About phone", true, true)
            afterClick["about"] = "About phone"
            afterStable = { cancelled = true }
        }
        val plan = HandsAction.Plan(listOf(
            HandsAction.PlanStep(HandsAction.Click(point, "About phone"), "Settings", "About phone"),
            HandsAction.PlanStep(HandsAction.Back, "About phone", null),
        ))
        val result = HandsNativePlanExecutor(port, { cancelled }).execute(plan)
        assertEquals(HandsNativePlanExecutor.State.STOPPED, result.state)
        assertEquals(1, result.completedActions)
        assertTrue(port.backActions == 0)
        assertFalse(port.actions.any { it == "back" })
    }

    private class FakePort : HandsNativePlanExecutor.Port {
        var packageName: String? = "com.android.launcher"
        val visible = mutableSetOf<String>()
        val clickables = mutableMapOf<String, HandsNativePlanExecutor.Port.Clickable>()
        val ambiguous = mutableSetOf<String>()
        val editables = mutableMapOf<String, HandsNativePlanExecutor.Port.Editable>()
        val afterClick = mutableMapOf<String, String>()
        val clicks = mutableListOf<String>()
        val actions = mutableListOf<String>()
        var launches = 0
        var entered: String? = null
        var fieldReadback = ""
        var afterSetText: String? = null
        var backActions = 0
        var stable = true
        var afterStable: (() -> Unit)? = null

        override fun foregroundPackage() = packageName
        override fun hasVisibleText(text: String) = text in visible
        override fun resolveApp(name: String) = if (name == "Settings") App("Settings", "com.android.settings") else null
        override fun launch(app: App): Boolean { launches++; packageName = app.packageName; actions += "launch"; return true }
        override fun findUniqueClickable(target: String): Clickable? = if (target in ambiguous) null else clickables[target]
        override fun clickIfCurrent(target: String, token: String): Boolean {
            val match = clickables[target]?.takeIf { it.token == token } ?: return false
            clicks += target
            actions += "click"
            afterClick[match.token]?.let { visible.clear(); visible += it }
            return true
        }
        override fun findUniqueEditable(target: String) = editables[target]
        override fun setTextIfCurrent(target: String, token: String, value: String): Boolean {
            val field = editables[target]?.takeIf { it.token == token && it.focused && !it.password } ?: return false
            entered = value
            actions += "set_text"
            afterSetText?.let { visible.clear(); visible += it }
            return fieldReadback == value
        }
        override fun back(): Boolean { backActions++; actions += "back"; return true }
        override fun home() = true
        override fun scroll(direction: String) = true
        override fun beginObservation() {}
        override fun awaitStable(timeoutMs: Long, stopped: () -> Boolean): Boolean {
            afterStable?.invoke()
            return stable && !stopped()
        }
    }
}

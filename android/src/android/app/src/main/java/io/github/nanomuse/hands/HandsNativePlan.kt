package io.github.nanomuse.hands

/**
 * Runs a model-proposed semantic plan against the live accessibility state. The port is small
 * on purpose so the state machine can be exercised without an Android device.
 */
class HandsNativePlanExecutor(
    private val port: Port,
    private val stopped: () -> Boolean,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    interface Port {
        data class App(val label: String, val packageName: String)
        data class Clickable(val token: String, val label: String, val visible: Boolean, val enabled: Boolean)
        data class Editable(
            val token: String,
            val label: String,
            val visible: Boolean,
            val enabled: Boolean,
            val focused: Boolean,
            val password: Boolean,
        )

        fun foregroundPackage(): String?
        fun hasVisibleText(text: String): Boolean
        fun resolveApp(name: String): App?
        fun launch(app: App): Boolean
        fun findUniqueClickable(target: String): Clickable?
        /** Reacquire the same unique target and perform ACTION_CLICK only if it is unchanged. */
        fun clickIfCurrent(target: String, token: String): Boolean
        fun findUniqueEditable(target: String): Editable?
        /** Reacquire the same unique focused field, set text, and verify its value. */
        fun setTextIfCurrent(target: String, token: String, value: String): Boolean
        fun back(): Boolean
        fun home(): Boolean
        fun scroll(direction: String): Boolean
        fun beginObservation()
        /** Event driven with a finite upper bound; false means the visual fallback is required. */
        fun awaitStable(timeoutMs: Long, stopped: () -> Boolean): Boolean
    }

    enum class State { COMPLETE, NEEDS_VISION, STOPPED }

    data class Result(
        val state: State,
        val completedActions: Int,
        val reason: String,
        val nodeLookupMs: Long,
        val actionMs: Long,
        val pageWaitMs: Long,
        val eventStableTransitions: Int,
    )

    fun execute(plan: HandsAction.Plan): Result {
        var completed = 0
        var lookupMs = 0L
        var actionMs = 0L
        var waitMs = 0L
        var stableTransitions = 0
        var expectedForeground = port.foregroundPackage()?.takeIf { it.isNotBlank() }

        fun result(state: State, reason: String) = Result(
            state, completed, reason, lookupMs, actionMs, waitMs, stableTransitions,
        )

        for ((index, step) in plan.steps.withIndex()) {
            if (stopped()) return result(State.STOPPED, "cancelled before step ${index + 1}")
            val action = step.action
            if (action !is HandsAction.OpenApp &&
                (expectedForeground == null || port.foregroundPackage() != expectedForeground)) {
                return result(State.NEEDS_VISION, "step ${index + 1} foreground package changed")
            }
            if (step.whenVisible.isNotBlank()) {
                val started = nowMs()
                val visible = port.hasVisibleText(step.whenVisible)
                lookupMs += (nowMs() - started).coerceAtLeast(0L)
                if (!visible) return result(State.NEEDS_VISION, "step ${index + 1} precondition is no longer visible")
            }

            var expectedPackage: String? = null
            val needsTransitionWait: Boolean
            var eventStable = false
            when (action) {
                is HandsAction.OpenApp -> {
                    val started = nowMs()
                    val app = port.resolveApp(action.appName)
                    lookupMs += (nowMs() - started).coerceAtLeast(0L)
                    if (app == null) return result(State.NEEDS_VISION, "step ${index + 1} app name is missing or ambiguous")
                    expectedPackage = app.packageName
                    val alreadyForeground = port.foregroundPackage() == app.packageName
                    if (!alreadyForeground) {
                        port.beginObservation()
                        val actionStarted = nowMs()
                        val opened = port.launch(app)
                        actionMs += (nowMs() - actionStarted).coerceAtLeast(0L)
                        if (!opened) return result(State.NEEDS_VISION, "step ${index + 1} app launch failed")
                        completed++
                    }
                    needsTransitionWait = !alreadyForeground
                }
                is HandsAction.Click -> {
                    val started = nowMs()
                    val target = port.findUniqueClickable(action.target)
                    lookupMs += (nowMs() - started).coerceAtLeast(0L)
                    if (target == null || !target.visible || !target.enabled) {
                        return result(State.NEEDS_VISION, "step ${index + 1} target is missing, ambiguous, hidden, or disabled")
                    }
                    // Risk labels never run inside a multi-step semantic plan. The existing
                    // single-action path asks Sentinel and rechecks the current page first.
                    if (requiresSingleAction(target.label, action.target)) {
                        return result(State.NEEDS_VISION, "step ${index + 1} needs the existing single-action risk flow")
                    }
                    port.beginObservation()
                    val actionStarted = nowMs()
                    val clicked = port.clickIfCurrent(action.target, target.token)
                    actionMs += (nowMs() - actionStarted).coerceAtLeast(0L)
                    if (!clicked) return result(State.NEEDS_VISION, "step ${index + 1} target changed before click")
                    completed++
                    needsTransitionWait = true
                }
                is HandsAction.InputText -> {
                    val started = nowMs()
                    val field = port.findUniqueEditable(action.field)
                    lookupMs += (nowMs() - started).coerceAtLeast(0L)
                    if (field == null || !field.visible || !field.enabled || !field.focused || field.password ||
                        io.github.nanomuse.guard.TapWords.looksSecret(field.label) ||
                        io.github.nanomuse.guard.TapWords.looksSecret(action.field)) {
                        return result(State.NEEDS_VISION, "step ${index + 1} field is missing, ambiguous, unfocused, or sensitive")
                    }
                    port.beginObservation()
                    val actionStarted = nowMs()
                    val entered = port.setTextIfCurrent(action.field, field.token, action.text)
                    actionMs += (nowMs() - actionStarted).coerceAtLeast(0L)
                    if (!entered) return result(State.NEEDS_VISION, "step ${index + 1} text was not accepted and verified")
                    completed++
                    needsTransitionWait = true
                }
                HandsAction.Back -> {
                    port.beginObservation()
                    val actionStarted = nowMs()
                    val acted = port.back()
                    actionMs += (nowMs() - actionStarted).coerceAtLeast(0L)
                    if (!acted) return result(State.NEEDS_VISION, "step ${index + 1} Back was rejected")
                    completed++
                    needsTransitionWait = true
                }
                HandsAction.Home -> {
                    port.beginObservation()
                    val actionStarted = nowMs()
                    val acted = port.home()
                    actionMs += (nowMs() - actionStarted).coerceAtLeast(0L)
                    if (!acted) return result(State.NEEDS_VISION, "step ${index + 1} Home was rejected")
                    completed++
                    needsTransitionWait = true
                }
                is HandsAction.Scroll -> {
                    port.beginObservation()
                    val actionStarted = nowMs()
                    val acted = port.scroll(action.direction)
                    actionMs += (nowMs() - actionStarted).coerceAtLeast(0L)
                    if (!acted) return result(State.NEEDS_VISION, "step ${index + 1} scroll was rejected")
                    completed++
                    needsTransitionWait = true
                }
                else -> return result(State.NEEDS_VISION, "step ${index + 1} is not supported by the native executor")
            }

            if (stopped()) return result(State.STOPPED, "cancelled after step ${index + 1}")
            if (needsTransitionWait) {
                val waitStarted = nowMs()
                eventStable = port.awaitStable(MAX_EVENT_WAIT_MS, stopped)
                waitMs += (nowMs() - waitStarted).coerceAtLeast(0L)
                if (eventStable) stableTransitions++
            }
            if (stopped()) return result(State.STOPPED, "cancelled while waiting after step ${index + 1}")
            if (index < plan.steps.lastIndex && needsTransitionWait && !eventStable) {
                return result(State.NEEDS_VISION, "step ${index + 1} had no stable accessibility event; refresh the screen")
            }
            if (expectedPackage != null && port.foregroundPackage() != expectedPackage) {
                return result(State.NEEDS_VISION, "step ${index + 1} foreground package did not match")
            }
            val expected = step.expectAfter
            if (expected != null) {
                if (stopped()) return result(State.STOPPED, "cancelled before page verification")
                val started = nowMs()
                val visible = port.hasVisibleText(expected)
                lookupMs += (nowMs() - started).coerceAtLeast(0L)
                if (!visible) return result(State.NEEDS_VISION, "step ${index + 1} expected page label did not appear")
                expectedForeground = port.foregroundPackage()?.takeIf { it.isNotBlank() }
                if (expectedForeground == null) return result(State.NEEDS_VISION, "step ${index + 1} has no foreground package")
            }
        }
        return result(State.COMPLETE, "all ${plan.steps.size} planned steps were checked")
    }

    private fun requiresSingleAction(onScreen: String, target: String): Boolean {
        val combined = "$onScreen $target"
        return io.github.nanomuse.guard.TapWords.classify(target, onScreen) != null ||
            Regex("install|download|安装|下载", RegexOption.IGNORE_CASE).containsMatchIn(combined)
    }

    companion object { const val MAX_EVENT_WAIT_MS = 3_000L }
}

package io.github.nanomuse.hands

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the screen model can do on the phone, compatible with the `mobile_use` action space
 * of MemGUI-Bench (MIT) and Open-AutoGLM. Visual actions use a 0–999 screenshot grid; a plan
 * uses bounded semantic targets and is revalidated against the live accessibility tree.
 */
sealed class HandsAction {
    /** A point on the 0–999 grid of the screenshot. */
    data class Point(val gx: Int, val gy: Int) {
        fun toPixels(screenW: Int, screenH: Int): Pair<Int, Int> =
            Math.round(gx * screenW / GRID.toDouble()).toInt().coerceIn(0, screenW - 1) to
                Math.round(gy * screenH / GRID.toDouble()).toInt().coerceIn(0, screenH - 1)
    }

    data class Click(val at: Point, val target: String) : HandsAction()
    data class DoubleTap(val at: Point, val target: String) : HandsAction()
    data class LongPress(val at: Point, val target: String) : HandsAction()
    data class Swipe(val from: Point, val to: Point) : HandsAction()
    /** Scroll the content in [direction]: `down` reveals what is below. */
    data class Scroll(val direction: String) : HandsAction()
    data class InputText(val text: String, val field: String) : HandsAction()
    object KeyboardEnter : HandsAction()
    data class OpenApp(val appName: String) : HandsAction()
    object Back : HandsAction()
    object Home : HandsAction()
    data class Wait(val seconds: Int) : HandsAction()
    /** Logins, passwords, codes, captchas, protected pages: the user does it and taps Continue. */
    data class TakeOver(val reason: String) : HandsAction()
    /** The model needs something only the user knows; the task pauses and the chat asks. */
    data class AskUser(val question: String) : HandsAction()
    data class Status(val complete: Boolean, val answer: String) : HandsAction()
    /** A bounded series of semantic steps. The local executor validates each step on live UI. */
    data class Plan(val steps: List<PlanStep>) : HandsAction()
    data class PlanStep(
        val action: HandsAction,
        /** Exact visible label that must still be present before this step. */
        val whenVisible: String = "",
        /** Exact visible label expected before the next planned step. */
        val expectAfter: String? = null,
    )

    /** The label the guard reads, for taps; null for everything else. */
    val tapTarget: String?
        get() = when (this) {
            is Click -> target
            is DoubleTap -> target
            is LongPress -> target
            else -> null
        }

    /** One line for the capsule and the trace. */
    fun describe(): String = when (this) {
        is Click -> if (target.isNotBlank()) "tap “${target.take(40)}”" else "tap"
        is DoubleTap -> if (target.isNotBlank()) "double-tap “${target.take(40)}”" else "double-tap"
        is LongPress -> if (target.isNotBlank()) "hold “${target.take(40)}”" else "long press"
        is Swipe -> "swipe"
        is Scroll -> "scroll $direction"
        is InputText -> if (field.isNotBlank()) "type into ${field.take(40)}" else "type"
        KeyboardEnter -> "enter"
        is OpenApp -> "open ${appName.take(40)}"
        Back -> "back"
        Home -> "home"
        is Wait -> "wait ${seconds}s"
        is TakeOver -> "your turn"
        is AskUser -> "question for you"
        is Status -> if (complete) "done" else "cannot finish"
        is Plan -> "verified plan (${steps.size} steps)"
    }

    companion object {
        const val GRID = 1000

        /** `Thought:` and `Action:` pulled out of a reply. */
        data class Parsed(val thought: String, val action: HandsAction, val raw: String)

        enum class ParseFailure { EMPTY_RESPONSE, MISSING_ACTION, INVALID_JSON, TRUNCATED, INVALID_ACTION, PROVIDER_REFUSAL }

        class FormatError(message: String, val failure: ParseFailure = ParseFailure.INVALID_ACTION) : Exception(message)

        private val aliases = mapOf(
            "click" to "click", "tap" to "click", "press" to "click", "touch" to "click",
            "long_press" to "long_press", "long_tap" to "long_press", "hold" to "long_press", "longpress" to "long_press",
            "double_tap" to "double_tap", "double_click" to "double_tap", "doubletap" to "double_tap",
            "swipe" to "swipe", "drag" to "swipe", "fling" to "swipe",
            "scroll" to "scroll",
            "input_text" to "input_text", "type" to "input_text", "enter_text" to "input_text", "write" to "input_text", "type_text" to "input_text",
            "keyboard_enter" to "keyboard_enter", "enter" to "keyboard_enter", "press_enter" to "keyboard_enter",
            "open_app" to "open_app", "launch" to "open_app", "launch_app" to "open_app", "open" to "open_app",
            "navigate_back" to "navigate_back", "back" to "navigate_back",
            "navigate_home" to "navigate_home", "home" to "navigate_home",
            "wait" to "wait",
            "take_over" to "take_over", "takeover" to "take_over", "handoff" to "take_over", "hand_over" to "take_over",
            "ask_user" to "ask_user", "ask" to "ask_user",
            "status" to "status", "answer" to "status", "finish" to "status", "done" to "status", "complete" to "status", "terminate" to "status",
            "plan" to "plan", "sequence" to "plan",
        )

        /**
         * Reads the normal Thought/Action protocol, plus an exact JSON object or JSON fence
         * for providers that omit the labels. Unmarked prose is never searched for coordinates.
         */
        fun parse(reply: String, stopReason: String? = null): Parsed {
            val text = reply.trim()
            if (isProviderRefusal(text, stopReason)) {
                throw FormatError("model provider refused the request", ParseFailure.PROVIDER_REFUSAL)
            }
            if (text.isEmpty()) throw FormatError("empty model response", ParseFailure.EMPTY_RESPONSE)
            val normalized = text.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
            val idx = normalized.lastIndexOf("Action:")
            if (idx < 0) {
                val structured = unmarkedStructuredJson(normalized)
                    ?: throw if (providerTruncated(stopReason)) {
                        FormatError("provider stopped at its output limit before returning a structured action", ParseFailure.TRUNCATED)
                    } else {
                        FormatError("missing `Action:` marker and no standalone JSON action", ParseFailure.MISSING_ACTION)
                    }
                val json = parseJsonObject(structured, stopReason, allowTrailing = false)
                return Parsed(extractThought(normalized), fromJson(json), text)
            }
            var thought = normalized.substring(0, idx).trim()
            if (thought.startsWith("Thought:")) thought = thought.removePrefix("Thought:").trim()
            val json = parseJsonObject(normalized.substring(idx + "Action:".length), stopReason, allowTrailing = true)
            return Parsed(thought, fromJson(json), text)
        }

        private fun extractThought(text: String): String {
            val prefix = text.substringBefore("```").trim()
            return if (prefix.startsWith("Thought:")) prefix.removePrefix("Thought:").trim() else ""
        }

        private fun unmarkedStructuredJson(text: String): String? {
            val noThought = if (text.startsWith("Thought:")) {
                val newline = text.indexOf('\n')
                if (newline < 0) return null
                text.substring(newline + 1).trim()
            } else text
            if (noThought.startsWith("{")) return noThought
            if (noThought.startsWith("```")) {
                val firstNewline = noThought.indexOf('\n')
                val close = noThought.lastIndexOf("```")
                if (firstNewline < 0) return null
                if (close <= firstNewline) return noThought.substring(firstNewline + 1).trim()
                if (noThought.substring(close + 3).isNotBlank()) return null
                return noThought.substring(firstNewline + 1, close).trim()
            }
            return null
        }

        fun isProviderRefusal(text: String, stopReason: String? = null): Boolean {
            val reason = stopReason.orEmpty().lowercase()
            if (reason in setOf("safety", "content_filter", "content_filter_triggered", "refusal")) return true
            return text.lowercase().contains("the request was rejected because it was considered high risk")
        }

        private fun parseJsonObject(tail: String, stopReason: String?, allowTrailing: Boolean): JSONObject {
            val s = tail.trim().removePrefix("```json").removePrefix("```").trim()
            val start = s.indexOf('{')
            if (start < 0) {
                if (providerTruncated(stopReason)) {
                    throw FormatError("provider stopped at its output limit before returning action JSON", ParseFailure.TRUNCATED)
                }
                throw FormatError("action has no JSON object", ParseFailure.INVALID_JSON)
            }
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until s.length) {
                val c = s[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            if (!allowTrailing && s.substring(i + 1).isNotBlank()) {
                                throw FormatError("standalone action JSON must not include trailing text", ParseFailure.INVALID_JSON)
                            }
                            return try {
                                JSONObject(s.substring(start, i + 1))
                            } catch (_: Exception) {
                                throw FormatError("action JSON is malformed", ParseFailure.INVALID_JSON)
                            }
                        }
                    }
                }
            }
            val truncated = providerTruncated(stopReason)
            throw FormatError(
                if (truncated) "action JSON was truncated at the provider output limit" else "action JSON object is incomplete",
                ParseFailure.TRUNCATED,
            )
        }

        private fun providerTruncated(stopReason: String?): Boolean =
            stopReason.orEmpty().lowercase() in setOf("length", "max_tokens", "max_output_tokens")

        internal fun extractJson(tail: String): JSONObject? {
            val s = tail.trim().removePrefix("```json").removePrefix("```").trim()
            val start = s.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until s.length) {
                val c = s[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return runCatching { JSONObject(s.substring(start, i + 1)) }.getOrNull()
                    }
                }
            }
            return null
        }

        fun fromJson(o: JSONObject): HandsAction {
            val rawType = (o.optString("action_type").ifBlank { o.optString("action") }).trim().lowercase().replace(' ', '_')
            val type = aliases[rawType] ?: throw FormatError("unknown action_type “$rawType”")
            fun point(key: String): Point {
                val arr: JSONArray = o.optJSONArray(key)
                    ?: o.optJSONArray("element")?.takeIf { key == "coordinate" }
                    ?: o.optJSONArray("coordinates")?.takeIf { key == "coordinate" }
                    ?: throw FormatError("missing $key")
                if (arr.length() != 2) throw FormatError("$key must be [x, y]")
                return Point(clampGrid(arr.optDouble(0)), clampGrid(arr.optDouble(1)))
            }
            val target = o.optString("target").ifBlank { o.optString("element_description") }.ifBlank { o.optString("label") }.trim()
            return when (type) {
                "click" -> Click(point("coordinate"), target)
                "double_tap" -> DoubleTap(point("coordinate"), target)
                "long_press" -> LongPress(point("coordinate"), target)
                "swipe" -> {
                    val from = if (o.has("start_coordinate")) point("start_coordinate") else point("coordinate")
                    val to = if (o.has("end_coordinate")) point("end_coordinate") else {
                        // `swipe` with a direction and no end point: half a screen in that direction.
                        val dir = o.optString("direction", "up").lowercase()
                        Point(
                            (from.gx + when (dir) { "left" -> -400; "right" -> 400; else -> 0 }).coerceIn(0, GRID - 1),
                            (from.gy + when (dir) { "up" -> -400; "down" -> 400; else -> 0 }).coerceIn(0, GRID - 1),
                        )
                    }
                    Swipe(from, to)
                }
                "scroll" -> {
                    val dir = o.optString("direction", "down").lowercase()
                    if (dir !in setOf("up", "down", "left", "right")) throw FormatError("scroll direction must be up|down|left|right")
                    Scroll(dir)
                }
                "input_text" -> InputText(o.optString("text"), o.optString("field").ifBlank { target })
                "keyboard_enter" -> KeyboardEnter
                "open_app" -> OpenApp(o.optString("app_name").ifBlank { o.optString("app") }.ifBlank { o.optString("name") }.trim())
                "navigate_back" -> Back
                "navigate_home" -> Home
                "wait" -> Wait(o.optDouble("seconds", 2.0).toInt().coerceIn(1, 10))
                "take_over" -> TakeOver(o.optString("reason").ifBlank { o.optString("message") })
                "ask_user" -> AskUser(o.optString("question").ifBlank { o.optString("text") })
                "status" -> {
                    val status = o.optString("goal_status").ifBlank { o.optString("status") }.lowercase()
                    val answer = o.optString("answer").ifBlank { o.optString("text") }.ifBlank { o.optString("message") }
                    val complete = status in setOf("complete", "completed", "success", "done", "finished") ||
                        (status.isBlank() && rawType in setOf("answer", "finish", "done", "complete"))
                    Status(complete, answer)
                }
                "plan" -> parsePlan(o)
                else -> throw FormatError("unknown action_type “$rawType”")
            }
        }

        private fun parsePlan(o: JSONObject): Plan {
            val array = o.optJSONArray("steps") ?: throw FormatError("plan is missing steps")
            if (array.length() !in MIN_PLAN_STEPS..MAX_PLAN_STEPS) {
                throw FormatError("plan must contain $MIN_PLAN_STEPS to $MAX_PLAN_STEPS steps")
            }
            val steps = (0 until array.length()).map { index ->
                val item = array.optJSONObject(index) ?: throw FormatError("plan step ${index + 1} is not an object")
                val actionObject = JSONObject(item.toString())
                val stepType = actionObject.optString("action_type").ifBlank { actionObject.optString("action") }
                    .trim().lowercase().replace(' ', '_')
                if (aliases[stepType] !in PLAN_ACTIONS) {
                    throw FormatError("plan step ${index + 1} is not a supported native action")
                }
                if (aliases[stepType] == "click" && !actionObject.has("coordinate") &&
                    !actionObject.has("element") && !actionObject.has("coordinates")) {
                    actionObject.put("coordinate", JSONArray().put(0).put(0))
                }
                val action = fromJson(actionObject)
                if (action is Plan || action !is Click && action !is InputText && action !is OpenApp &&
                    action !is Scroll && action !is Back && action !is Home && action !is KeyboardEnter) {
                    throw FormatError("plan step ${index + 1} is not a supported native action")
                }
                if (action is InputText && io.github.nanomuse.guard.TapWords.looksSecret(action.field)) {
                    throw FormatError("secret fields must stay in the single-action handoff flow")
                }
                val whenVisible = item.optString("when_visible").trim()
                if (action !is OpenApp && whenVisible.length < 2) {
                    throw FormatError("plan step ${index + 1} needs a visible precondition")
                }
                if (whenVisible.length > MAX_PLAN_LABEL_CHARS || action.tapTarget.orEmpty().length > MAX_PLAN_LABEL_CHARS ||
                    (action is InputText && action.field.length > MAX_PLAN_LABEL_CHARS) ||
                    (action is OpenApp && action.appName.length > MAX_PLAN_LABEL_CHARS)) {
                    throw FormatError("plan step ${index + 1} contains an oversized target label")
                }
                val expectAfter = item.optString("expect_after").trim().takeIf { it.isNotEmpty() }
                if (expectAfter != null && expectAfter.length > MAX_PLAN_LABEL_CHARS) {
                    throw FormatError("plan step ${index + 1} contains an oversized expected label")
                }
                if (index < array.length() - 1 && expectAfter == null) {
                    throw FormatError("plan step ${index + 1} needs an expected next-page label")
                }
                PlanStep(action, whenVisible, expectAfter)
            }
            // Multi-step plans may not carry a send, payment, delete, or install through
            // an approval boundary. Such an action remains available in the ordinary loop.
            val sensitive = steps.filter { step ->
                val action = step.action
                action is KeyboardEnter || action is Click && (
                    io.github.nanomuse.guard.TapWords.classify(action.target) != null ||
                        INSTALL_WORDS.containsMatchIn(action.target)
                    )
            }
            if (sensitive.isNotEmpty()) {
                throw FormatError("sensitive actions must use the existing single-action approval flow")
            }
            return Plan(steps)
        }

        private fun clampGrid(v: Double): Int {
            if (v.isNaN()) throw FormatError("coordinate is not a number")
            // A model that answers in pixels of a ~1000-wide image lands in range anyway; one that
            // answers 0–1 fractions is scaled up.
            val n = if (v > 0 && v <= 1.0) v * GRID else v
            return n.toInt().coerceIn(0, GRID - 1)
        }

        const val MIN_PLAN_STEPS = 2
        const val MAX_PLAN_STEPS = 6
        const val MAX_PLAN_LABEL_CHARS = 120
        private val PLAN_ACTIONS = setOf("click", "input_text", "open_app", "scroll", "navigate_back", "navigate_home")
        private val INSTALL_WORDS = Regex("install|download|安装|下载", RegexOption.IGNORE_CASE)
    }
}

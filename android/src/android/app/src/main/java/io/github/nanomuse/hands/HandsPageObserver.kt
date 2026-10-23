package io.github.nanomuse.hands

import android.os.SystemClock
import com.openminis.app.accessibility.MinisAccessibilityService
import java.io.Closeable

/** Subscribe BEFORE the action. Ignore our overlay, retain system dialogs and package switches. */
class HandsPageObserver(private val svc: MinisAccessibilityService, private val ownPackage: String, private val onChange: () -> Unit = {}) : Closeable {
    private val monitor = Object()
    private val started = SystemClock.elapsedRealtime()
    private val state = HandsPageState(started)
    private val listener: (MinisAccessibilityService.RecordedEvent) -> Unit = { event ->
        if (event.packageName != ownPackage && event.packageName != null) synchronized(monitor) {
            state.event(event.type, SystemClock.elapsedRealtime())
            if (state.lastChangeMs != null) onChange()
            monitor.notifyAll()
        }
    }
    init { svc.addEventListener(listener) }

    fun changed(): Boolean = synchronized(monitor) { state.lastChangeMs != null }
    fun windowChanged(): Boolean = synchronized(monitor) { state.windowChanged }

    fun await(fallbackMs: Long, stopped: () -> Boolean, timings: MutableMap<String, Long>) {
        val waitStarted = SystemClock.elapsedRealtime()
        val deadline = waitStarted + fallbackMs + 1_000L
        var reason = "timeout_screenshot_fallback"
        while (!stopped()) {
            val now = SystemClock.elapsedRealtime()
            val content = contentState()
            val ready = synchronized(monitor) { state.ready(now, content.first, content.second) }
            if (ready) { reason = "event_stable"; break }
            if (now >= deadline) break
            // No usable events: retain the previous fallback before taking a fresh screenshot.
            if (!changed() && now - waitStarted >= fallbackMs) break
            synchronized(monitor) { monitor.wait(50L) }
        }
        synchronized(monitor) {
            timings["page_window_changed"] = if (state.windowChanged) 1L else 0L
            timings["page_content_changed"] = if (state.contentChanged) 1L else 0L
            state.lastChangeMs?.let { timings["page_change_detected_ms"] = it - started }
        }
        timings["page_event_stable"] = if (reason == "event_stable") 1L else 0L
        timings["settle_wait_ms"] = SystemClock.elapsedRealtime() - waitStarted
    }

    private fun contentState(): Pair<Boolean, Boolean> {
        var visible = false
        var loading = false
        var count = 0
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
            if (depth > 60 || count++ >= 2500) return
            if (node.isVisibleToUser) {
                if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) visible = true
                if (node.className?.toString()?.contains("ProgressBar") == true) loading = true
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        return try {
            val pkg = svc.foregroundPackage().first
            svc.rootNodes().forEach { root ->
                try { if (root.packageName?.toString() == pkg) visit(root, 0) } finally { root.recycle() }
            }
            (visible && count < 2500) to loading
        } catch (_: Exception) { false to false }
    }

    override fun close() { svc.removeEventListener(listener) }
}

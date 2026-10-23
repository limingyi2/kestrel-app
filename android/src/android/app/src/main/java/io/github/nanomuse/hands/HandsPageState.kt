package io.github.nanomuse.hands

/** Pure readiness policy. A quiet interval alone, without a change, never proves readiness. */
class HandsPageState(private val startMs: Long) {
    var lastChangeMs: Long? = null
        private set
    var windowChanged = false
        private set
    var contentChanged = false
        private set

    fun event(type: String, nowMs: Long) {
        when (type) {
            "TYPE_WINDOW_STATE_CHANGED", "TYPE_WINDOWS_CHANGED" -> windowChanged = true
            "TYPE_WINDOW_CONTENT_CHANGED", "TYPE_VIEW_SCROLLED", "TYPE_VIEW_TEXT_CHANGED" -> contentChanged = true
            else -> return
        }
        lastChangeMs = nowMs
    }

    fun ready(nowMs: Long, hasVisibleContent: Boolean, loading: Boolean): Boolean =
        hasVisibleContent && !loading && lastChangeMs?.let { nowMs - it >= QUIET_MS } == true &&
            nowMs - startMs >= QUIET_MS

    companion object { const val QUIET_MS = 250L }
}

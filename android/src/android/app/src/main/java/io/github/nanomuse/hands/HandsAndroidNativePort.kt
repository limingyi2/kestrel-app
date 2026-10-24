package io.github.nanomuse.hands

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Point
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService

/** Android implementation of the testable, semantic plan port. It never executes a coordinate. */
class HandsAndroidNativePort(
    private val context: Context,
    private val service: MinisAccessibilityService,
    private val onAction: () -> Unit,
    private val onPageChange: () -> Unit,
) : HandsNativePlanExecutor.Port {
    private var observer: HandsPageObserver? = null

    override fun foregroundPackage(): String? = service.foregroundPackage().first

    override fun hasVisibleText(text: String): Boolean = ScreenWords.hasVisibleText(service, text)

    override fun resolveApp(name: String): HandsNativePlanExecutor.Port.App? = HandsApps.resolveUnique(context, name)
        ?.let { HandsNativePlanExecutor.Port.App(it.label, it.packageName) }

    override fun launch(app: HandsNativePlanExecutor.Port.App): Boolean {
        if (foregroundPackage() == app.packageName) return true
        onAction()
        return HandsApps.open(context, HandsApps.App(app.label, app.packageName))
    }

    override fun findUniqueClickable(target: String): HandsNativePlanExecutor.Port.Clickable? {
        val packageName = foregroundPackage()?.takeIf { it.isNotBlank() } ?: return null
        val center = ScreenWords.uniqueClickableCenter(service, target) ?: return null
        if (foregroundPackage() != packageName) return null
        val actualLabel = ScreenWords.at(service, center.x, center.y)?.take(160).orEmpty().ifBlank { target }
        return HandsNativePlanExecutor.Port.Clickable(
            token = "$packageName|${center.x}|${center.y}",
            label = actualLabel,
            visible = true,
            enabled = true,
        )
    }

    override fun clickIfCurrent(target: String, token: String): Boolean {
        val parts = token.split('|')
        if (parts.size != 3 || foregroundPackage() != parts[0]) return false
        val point = runCatching { Point(parts[1].toInt(), parts[2].toInt()) }.getOrNull() ?: return false
        onAction()
        return ScreenWords.clickUnique(service, target, point, parts[0])
    }

    override fun findUniqueEditable(target: String): HandsNativePlanExecutor.Port.Editable? {
        val packageName = foregroundPackage()?.takeIf { it.isNotBlank() } ?: return null
        val info = ScreenWords.uniqueEditableInfo(service, target) ?: return null
        if (foregroundPackage() != packageName) return null
        return HandsNativePlanExecutor.Port.Editable(
            token = "$packageName|${info.center.x}|${info.center.y}",
            label = info.label,
            visible = info.visible,
            enabled = info.enabled,
            focused = info.focused,
            password = info.password,
        )
    }

    override fun setTextIfCurrent(target: String, token: String, value: String): Boolean {
        val parts = token.split('|')
        if (parts.size != 3 || foregroundPackage() != parts[0]) return false
        val point = runCatching { Point(parts[1].toInt(), parts[2].toInt()) }.getOrNull() ?: return false
        onAction()
        return ScreenWords.setTextUnique(service, target, point, value, parts[0])
    }

    override fun back(): Boolean {
        onAction()
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    override fun home(): Boolean {
        onAction()
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    override fun scroll(direction: String): Boolean {
        val packageName = foregroundPackage()?.takeIf { it.isNotBlank() } ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 60 || visited++ >= 2500) return
            if (node.packageName?.toString() == packageName && node.isVisibleToUser && node.isEnabled && node.isScrollable) {
                nodes += AccessibilityNodeInfo.obtain(node)
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        try {
            service.rootNodes().forEach { root ->
                try { if (root.packageName?.toString() == packageName) visit(root, 0) } finally { root.recycle() }
            }
            if (visited >= 2500 || nodes.size != 1 || foregroundPackage() != packageName) return false
            val action = when (direction) {
                "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                else -> return false
            }
            onAction()
            return nodes.single().performAction(action)
        } catch (_: Throwable) {
            return false
        } finally {
            nodes.forEach { runCatching { it.recycle() } }
        }
    }

    override fun beginObservation() {
        observer?.close()
        observer = HandsPageObserver(service, context.packageName, onPageChange)
    }

    override fun awaitStable(timeoutMs: Long, stopped: () -> Boolean): Boolean {
        val current = observer ?: return false
        return try {
            val timings = linkedMapOf<String, Long>()
            // HandsPageObserver adds one second for event-driven settling before screenshot fallback.
            current.await((timeoutMs - 1_000L).coerceAtLeast(0L), stopped, timings)
            timings["page_event_stable"] == 1L && !stopped()
        } finally {
            current.close()
            if (observer === current) observer = null
        }
    }

    fun close() {
        observer?.close()
        observer = null
    }
}

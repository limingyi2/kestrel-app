package io.github.nanomuse.hands

import android.graphics.Rect
import android.graphics.Point
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService

/**
 * What the accessibility tree says is under a point: the text and description of the
 * deepest node whose bounds contain it, with its ancestors' labels up to the nearest
 * clickable one (a pay button is often a container with the label in a child). The
 * approval for a tap reads this next to the label the screen model reported, so a model
 * that misnames a button cannot talk its way past the card.
 */
object ScreenWords {
    private const val MAX_UP = 3
    private const val MAX_NODES = 2500

    /** A coordinate hint is only replaced when one visible clickable control matches exactly. */
    fun uniqueClickableCenter(svc: MinisAccessibilityService, target: String): Point? {
        val node = uniqueClickableNode(svc, target) ?: return null
        return try { Rect().also { node.getBoundsInScreen(it) }.let { Point(it.centerX(), it.centerY()) } }
        finally { node.recycle() }
    }

    /** Reacquire the unique live node after approval. A changed bound requires a fresh observation. */
    fun clickUnique(svc: MinisAccessibilityService, target: String, expected: Point): Boolean {
        val node = uniqueClickableNode(svc, target) ?: return false
        return try {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            bounds.centerX() == expected.x && bounds.centerY() == expected.y &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally { node.recycle() }
    }

    private fun uniqueClickableNode(svc: MinisAccessibilityService, target: String): AccessibilityNodeInfo? {
        val wanted = normalize(target)
        if (wanted.length < 2) return null
        val foregroundPackage = svc.foregroundPackage().first?.takeIf { it.isNotBlank() } ?: return null
        val matches = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 60 || visited++ >= MAX_NODES) return
            val nodeLabel = normalize(label(node).orEmpty())
            if (node.isVisibleToUser && nodeLabel == wanted) {
                var clickable: AccessibilityNodeInfo? = node
                var up = 0
                while (clickable != null && up <= MAX_UP && !clickable.isClickable) {
                    val parent = clickable.parent
                    if (clickable !== node) clickable.recycle()
                    clickable = parent
                    up++
                }
                if (clickable?.isClickable == true && clickable.isEnabled && clickable.isVisibleToUser && !clickable.isPassword) {
                    val bounds = Rect()
                    clickable.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) matches += AccessibilityNodeInfo.obtain(clickable)
                }
                if (clickable != null && clickable !== node) clickable.recycle()
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        return try {
            svc.rootNodes().forEach { root ->
                try {
                    if (root.packageName?.toString() == foregroundPackage) visit(root, 0)
                } finally {
                    root.recycle()
                }
            }
            if (visited >= MAX_NODES || matches.size != 1) {
                matches.forEach { it.recycle() }; null
            } else matches.single()
        } catch (_: Throwable) {
            matches.forEach { it.recycle() }
            null
        }
    }

    fun at(svc: MinisAccessibilityService, x: Int, y: Int): String? {
        val words = LinkedHashSet<String>()
        try {
            for (root in svc.rootNodes()) {
                val leaf = deepestAt(root, x, y, 0) ?: continue
                var node: AccessibilityNodeInfo? = leaf
                var up = 0
                while (node != null && up <= MAX_UP) {
                    label(node)?.let { words.add(it) }
                    if (node.isClickable && up > 0) break
                    node = node.parent
                    up++
                }
                if (words.isNotEmpty()) break
            }
        } catch (_: Throwable) {
            // a tree that changed under us is no reason to fail the tap; the model's label stands
        }
        return words.joinToString(" ").trim().take(200).ifBlank { null }
    }

    private fun label(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        return listOf(text, desc).filter { it.isNotEmpty() }.joinToString(" ").ifBlank { null }
    }

    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun deepestAt(node: AccessibilityNodeInfo, x: Int, y: Int, depth: Int): AccessibilityNodeInfo? {
        if (depth > 60) return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            deepestAt(child, x, y, depth + 1)?.let { return it }
        }
        return node
    }
}

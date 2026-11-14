package app.chattranslator

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** [bounds] is the text itself; [bubble] the chat bubble around it (used for width and colour). */
data class ChatMessage(val text: String, val bounds: Rect, val bubble: Rect)

/** [area] is the scrolling message list on screen; translations are clipped to it. */
data class ChatScreen(val area: Rect, val messages: List<ChatMessage>)

/** Finds the message bubbles WhatsApp is currently showing. */
object MessageScanner {
    /** View ids WhatsApp uses for message text and media captions. */
    private val MESSAGE_IDS = listOf("message_text", "caption")
    private const val MAX_DEPTH = 40
    /** A bubble is the text plus a little padding; anything wider is not the bubble. */
    private const val MAX_BUBBLE_PADDING_PX = 400
    /** Chat bubbles never span the whole conversation width. */
    private const val MAX_BUBBLE_FRACTION = 0.9

    fun scan(root: AccessibilityNodeInfo, packageName: String): ChatScreen? {
        val nodes = MESSAGE_IDS.flatMap { root.findAccessibilityNodeInfosByViewId("$packageName:id/$it") }
        if (nodes.isNotEmpty()) {
            val area = boundsOf(scrollableAncestor(nodes.first()) ?: root)
            return ChatScreen(area, nodes.mapNotNull { toMessage(it, area) })
        }
        // Fallback for when a WhatsApp update renames its views: take the text inside the
        // biggest scrolling list on screen, which is the conversation.
        val list = largestScrollable(root, 0) ?: return null
        val messages = ArrayList<ChatMessage>()
        val area = boundsOf(list)
        collectTexts(list, area, messages, 0)
        return ChatScreen(area, messages)
    }

    private fun toMessage(node: AccessibilityNodeInfo, area: Rect): ChatMessage? {
        if (!node.isVisibleToUser) return null
        val text = MessageFilter.clean(node.text) ?: return null
        val bounds = boundsOf(node)
        if (bounds.isEmpty) return null
        // The parent is normally the bubble. If it is much wider (e.g. the whole list), we don't
        // know the bubble, so the translation must stay inside the original text's own space.
        val bubble = node.parent?.let(::boundsOf)
            ?.takeIf {
                it.contains(bounds) &&
                    it.width() < bounds.width() + MAX_BUBBLE_PADDING_PX &&
                    it.width() < area.width() * MAX_BUBBLE_FRACTION
            }
            ?: bounds
        return ChatMessage(text, bounds, bubble)
    }

    private fun scrollableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        var depth = 0
        while (current != null && depth++ < MAX_DEPTH) {
            if (current.isScrollable) return current
            current = current.parent
        }
        return null
    }

    private fun largestScrollable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        var best: AccessibilityNodeInfo? = if (node.isScrollable) node else null
        for (i in 0 until node.childCount) {
            val candidate = node.getChild(i)?.let { largestScrollable(it, depth + 1) } ?: continue
            if (best == null || area(candidate) > area(best)) best = candidate
        }
        return best
    }

    private fun collectTexts(node: AccessibilityNodeInfo, area: Rect, out: MutableList<ChatMessage>, depth: Int) {
        if (depth > MAX_DEPTH) return
        if (node.className?.toString()?.endsWith("TextView") == true) {
            toMessage(node, area)?.let(out::add)
            return
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectTexts(it, area, out, depth + 1) }
    }

    private fun boundsOf(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun area(node: AccessibilityNodeInfo) = boundsOf(node).let { it.width().toLong() * it.height() }
}

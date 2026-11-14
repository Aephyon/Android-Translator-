package app.chattranslator

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class ChatMessage(val text: String, val bounds: Rect)

/** [area] is the scrolling message list on screen; translations are clipped to it. */
data class ChatScreen(val area: Rect, val messages: List<ChatMessage>)

/** Finds the message bubbles WhatsApp is currently showing. */
object MessageScanner {
    /** View ids WhatsApp uses for message text and media captions. */
    private val MESSAGE_IDS = listOf("message_text", "caption")
    private const val MAX_DEPTH = 40

    fun scan(root: AccessibilityNodeInfo, packageName: String): ChatScreen? {
        val nodes = MESSAGE_IDS.flatMap { root.findAccessibilityNodeInfosByViewId("$packageName:id/$it") }
        if (nodes.isNotEmpty()) {
            val list = scrollableAncestor(nodes.first()) ?: root
            return ChatScreen(boundsOf(list), nodes.mapNotNull(::toMessage))
        }
        // Fallback for when a WhatsApp update renames its views: take the text inside the
        // biggest scrolling list on screen, which is the conversation.
        val list = largestScrollable(root, 0) ?: return null
        val messages = ArrayList<ChatMessage>()
        collectTexts(list, messages, 0)
        return ChatScreen(boundsOf(list), messages)
    }

    private fun toMessage(node: AccessibilityNodeInfo): ChatMessage? {
        if (!node.isVisibleToUser) return null
        val text = MessageFilter.clean(node.text) ?: return null
        val bounds = boundsOf(node)
        if (bounds.isEmpty) return null
        return ChatMessage(text, bounds)
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

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<ChatMessage>, depth: Int) {
        if (depth > MAX_DEPTH) return
        if (node.className?.toString()?.endsWith("TextView") == true) {
            toMessage(node)?.let(out::add)
            return
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectTexts(it, out, depth + 1) }
    }

    private fun boundsOf(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun area(node: AccessibilityNodeInfo) = boundsOf(node).let { it.width().toLong() * it.height() }
}

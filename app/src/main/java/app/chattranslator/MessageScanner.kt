package app.chattranslator

import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.ceil
import kotlin.math.floor

/**
 * [bounds] is where the letters of the message actually are (WhatsApp's text view has invisible
 * padding, so this can be smaller than [view], the text view itself). [bubble] is the chat bubble
 * around it, used for width and colour.
 */
data class ChatMessage(val text: String, val bounds: Rect, val bubble: Rect, val view: Rect)

/** [area] is the scrolling message list on screen; translations are clipped to it. */
data class ChatScreen(val area: Rect, val messages: List<ChatMessage>)

/** Finds the message bubbles WhatsApp is currently showing. */
object MessageScanner {
    /** View ids WhatsApp uses for message text and media captions. */
    private val MESSAGE_IDS = listOf("message_text", "caption")
    /** WhatsApp's message box; only conversation screens have it. */
    private val MESSAGE_BOX_IDS = listOf("entry", "conversation_entry_holder")
    private const val MAX_DEPTH = 40
    /** A bubble is the text plus a little padding; anything wider is not the bubble. */
    private const val MAX_BUBBLE_PADDING_PX = 400
    /** Chat bubbles never span the whole conversation width. */
    private const val MAX_BUBBLE_FRACTION = 0.9

    /**
     * Cheap position fingerprint of the messages on screen, used many times a second while the
     * user touches the screen to notice the chat moving.
     */
    fun positions(root: AccessibilityNodeInfo, packageName: String): List<Int> {
        val out = ArrayList<Int>()
        for (id in MESSAGE_IDS) {
            for (node in root.findAccessibilityNodeInfosByViewId("$packageName:id/$id")) {
                val r = boundsOf(node)
                out += r.top
                out += r.left
            }
        }
        return out
    }

    fun scan(root: AccessibilityNodeInfo, packageName: String): ChatScreen? {
        val nodes = MESSAGE_IDS.flatMap { root.findAccessibilityNodeInfosByViewId("$packageName:id/$it") }
        if (nodes.isNotEmpty()) {
            val area = boundsOf(scrollableAncestor(nodes.first()) ?: root)
            return ChatScreen(area, nodes.mapNotNull { toMessage(it, area) })
        }
        // Only conversations are translated, never lists (chats overview, updates, calls...).
        // A conversation is recognised by its message box at the bottom.
        if (!hasMessageBox(root, packageName)) return null
        // Fallback for when a WhatsApp update renames its views: take the text inside the
        // biggest scrolling list on screen, which is the conversation.
        val list = largestScrollable(root, 0) ?: return null
        val messages = ArrayList<ChatMessage>()
        val area = boundsOf(list)
        collectTexts(list, area, messages, 0)
        return ChatScreen(area, messages)
    }

    /**
     * True on a conversation screen: WhatsApp's message box (id "entry" / its holder), or, if
     * WhatsApp renamed those, an editable field in the bottom part of the screen. The chats list
     * only has a search field at the top.
     */
    private fun hasMessageBox(root: AccessibilityNodeInfo, packageName: String): Boolean {
        if (MESSAGE_BOX_IDS.any { root.findAccessibilityNodeInfosByViewId("$packageName:id/$it").isNotEmpty() }) return true
        val screen = boundsOf(root)
        return hasEditableBelow(root, screen.top + (screen.height() * 0.6).toInt(), 0)
    }

    private fun hasEditableBelow(node: AccessibilityNodeInfo, y: Int, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        if (node.isEditable && node.isVisibleToUser && boundsOf(node).top >= y) return true
        for (i in 0 until node.childCount) {
            if (node.getChild(i)?.let { hasEditableBelow(it, y, depth + 1) } == true) return true
        }
        return false
    }

    private fun toMessage(node: AccessibilityNodeInfo, area: Rect): ChatMessage? {
        if (!node.isVisibleToUser) return null
        val text = MessageFilter.clean(node.text) ?: return null
        val view = boundsOf(node)
        if (view.isEmpty) return null
        // Letters can poke out of the text view a little (e.g. "¿"); clip them to it.
        val bounds = letterBounds(node)?.let { letters -> Rect(letters).takeIf { it.intersect(view) } } ?: view
        // The parent is normally the bubble. If it is much wider (e.g. the whole list), we don't
        // know the bubble, so the translation must stay inside the original text's own space.
        val bubble = node.parent?.let(::boundsOf)
            ?.takeIf {
                it.contains(view) &&
                    it.width() < view.width() + MAX_BUBBLE_PADDING_PX &&
                    it.width() < area.width() * MAX_BUBBLE_FRACTION
            }
            ?: view
        return ChatMessage(text, bounds, bubble, view)
    }

    /**
     * The on-screen box around the visible letters of [node], asked from Android per character
     * (Android 8+ text views support this). Null if the app doesn't provide it.
     */
    private fun letterBounds(node: AccessibilityNodeInfo): Rect? {
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        if (key !in node.availableExtraData) return null
        val text = node.text ?: return null
        val length = minOf(text.length, MessageFilter.MAX_LENGTH)
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
        }
        if (!node.refreshWithExtraData(key, args)) return null
        @Suppress("DEPRECATION")
        val rects = node.extras.getParcelableArray(key) ?: return null
        var union: RectF? = null
        for (i in rects.indices) {
            val r = rects[i] as? RectF ?: continue
            if (r.isEmpty || i >= text.length || text[i].isWhitespace()) continue
            union = union?.apply { union(r) } ?: RectF(r)
        }
        return union?.let { Rect(floor(it.left).toInt(), floor(it.top).toInt(), ceil(it.right).toInt(), ceil(it.bottom).toInt()) }
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

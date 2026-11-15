package app.chattranslator

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.abs

/**
 * Watches WhatsApp chats and lays a translated copy of each foreign-language message
 * directly over the original bubble. The overlay ignores touches, so WhatsApp keeps working
 * normally underneath; a small floating button switches between translation and original.
 */
class TranslatorService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var settings: AppSettings
    private lateinit var engine: TranslationEngine
    private lateinit var speaker: Speaker
    private lateinit var learnPanel: LearnPanel

    /** Foreign-language messages currently on screen, in order, for the Learn panel. */
    private var learnItems: List<LearnItem> = emptyList()

    private lateinit var overlay: FrameLayout
    private lateinit var clip: FrameLayout
    private lateinit var toggle: ImageView
    private lateinit var toggleParams: WindowManager.LayoutParams
    /** All label views ever created (for clean-up), the ones in use by message, and spares. */
    private val labels = ArrayList<TranslationLabel>()
    private var labelsByMessage = HashMap<String, TranslationLabel>()
    private val spareLabels = ArrayList<TranslationLabel>()

    /**
     * Scrolling: translations vanish as soon as the chat moves and only come back once it has
     * been still for [SCROLL_SETTLE_MS] *and* two scans in a row find every message in the same
     * place. They never move along with the list.
     */
    private var lastScrollAt = 0L
    private var hiddenForScroll = false
    private var lastPositions: List<Int>? = null

    /**
     * Motion watch: from the moment a finger touches the screen, check message positions every
     * [MOTION_POLL_MS] and hide translations the instant anything moves. Taps and typing don't
     * move the chat, so they leave translations alone. This doesn't rely on WhatsApp's scroll
     * events, which arrive late and not always.
     */
    private var watchingUntil = 0L
    private var watching = false
    private var polledPositions: List<Int>? = null
    private val motionPoll = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val root = whatsAppRoot()
            val pkg = root?.packageName?.toString()
            if (root == null || pkg == null) {
                // Home / Back / app switch: the touch that started this watch left WhatsApp.
                if (anotherAppInFront()) hide()
            } else {
                val positions = MessageScanner.positions(root, pkg)
                val before = polledPositions
                if (before != null && positions != before) {
                    motionDetected("movement")
                    watchingUntil = now + MOTION_WATCH_MS
                }
                polledPositions = positions
            }
            if (now < watchingUntil) {
                handler.postDelayed(this, MOTION_POLL_MS)
            } else {
                watching = false
                polledPositions = null
            }
        }
    }

    /** Bubble colours measured on screen, keyed by "is outgoing". Empty until measured. */
    private val bubbleColors = HashMap<Boolean, Int>()
    private var sampling = false
    private val overlayOrigin = IntArray(2)
    private var attached = false
    private var showing = false
    private val announcedDownloads = HashSet<String>()

    private val refreshRunnable = Runnable { refresh() }

    /** Events only come from WhatsApp, so this notices when the user switches to another app. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!showing) return
            if (whatsAppRoot() == null) scheduleRefresh(0) // refresh decides: hide, or look again
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    // Kept in a field: SharedPreferences only holds listeners weakly.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            AppSettings.KEY_TARGET -> {
                engine.setTarget(settings.targetLanguage)
                announcedDownloads.clear()
                scheduleRefresh(0)
            }
            AppSettings.KEY_USE_CLAUDE, AppSettings.KEY_CLAUDE_KEY -> {
                applyClaudeSetting()
                scheduleRefresh(0)
            }
            AppSettings.KEY_SHOW -> {
                updateToggleLook()
                scheduleRefresh(0)
            }
        }
    }

    override fun onServiceConnected() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settings = AppSettings(this)
        engine = TranslationEngine(settings.targetLanguage).apply {
            onUpdated = { scheduleRefresh(0) }
            onDownloadStarted = { language ->
                if (announcedDownloads.add(language)) {
                    Toast.makeText(
                        this@TranslatorService,
                        "Downloading ${AppSettings.displayName(language)} language pack…",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
        engine.onClaudeKeyRejected = {
            Toast.makeText(
                this,
                "Claude didn't accept your API key. Using on-phone translation; check the key in Chat Translator.",
                Toast.LENGTH_LONG,
            ).show()
        }
        applyClaudeSetting()
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        speaker = Speaker(this)
        learnPanel = newLearnPanel()
        createWindows()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!attached) return
        val pkg = event.packageName?.toString()
        if (pkg !in WHATSAPP) {
            // Only received while WhatsApp is open (see listenForAppSwitches); nothing from
            // other apps is read. Another app coming to the front = the user left WhatsApp.
            // Not the keyboard, and not Android's system UI (notification pop-ups, volume
            // slider), which appear over WhatsApp without leaving it.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                pkg != null && pkg != packageName && pkg != SYSTEM_UI && pkg != keyboardPackage()
            ) {
                hide()
                scheduleRefresh(RECHECK_AFTER_SWITCH_MS) // back if WhatsApp is in fact still in front
            } else if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                scheduleRefresh(0)
            }
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                motionDetected("scroll event")
                startMotionWatch()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scheduleRefresh(0)
            // WhatsApp also reports content changes while scrolling; those must not cut the
            // waiting time short, so never schedule earlier than the scroll settle time.
            else -> {
                startMotionWatch()
                scheduleRefresh(maxOf(CONTENT_DEBOUNCE_MS, scrollSettleRemaining()))
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::settings.isInitialized) settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (::engine.isInitialized) engine.close()
        if (::speaker.isInitialized) speaker.shutdown()
        if (::learnPanel.isInitialized) learnPanel.close()
        if (attached) {
            windowManager.removeView(overlay)
            windowManager.removeView(toggle)
            attached = false
        }
        super.onDestroy()
    }

    private fun scrollSettleRemaining(): Long =
        (lastScrollAt + SCROLL_SETTLE_MS - SystemClock.uptimeMillis()).coerceAtLeast(0)

    private fun motionDetected(reason: String) {
        lastScrollAt = SystemClock.uptimeMillis()
        if (!hiddenForScroll) Log.d(TAG, "Motion ($reason)")
        hideForScroll()
        scheduleRefresh(SCROLL_SETTLE_MS)
    }

    private fun startMotionWatch() {
        watchingUntil = maxOf(watchingUntil, SystemClock.uptimeMillis() + MOTION_WATCH_MS)
        if (watching) return
        watching = true
        polledPositions = null
        handler.post(motionPoll)
    }

    private fun hideForScroll() {
        if (hiddenForScroll) return
        hiddenForScroll = true
        lastPositions = null
        clip.animate().cancel()
        clip.alpha = 0f
        Log.d(TAG, "Hidden for scroll")
    }

    private fun scheduleRefresh(delayMs: Long) {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, delayMs)
    }

    private fun refresh() {
        val root = whatsAppRoot()
        val pkg = root?.packageName?.toString()
        if (root == null || pkg !in WHATSAPP) {
            if (anotherAppInFront() || ++unknownFrontCount >= UNKNOWN_FRONT_LIMIT) {
                unknownFrontCount = 0
                hide()
            } else {
                scheduleRefresh(UNKNOWN_FRONT_RETRY_MS) // Android between windows; look again
            }
            return
        }
        unknownFrontCount = 0
        show()
        val screen = MessageScanner.scan(root, pkg!!)
        if (screen == null) {
            // Not a conversation (e.g. the chats list): nothing to translate, no button.
            clip.visibility = View.GONE
            toggle.visibility = View.GONE
            learnPanel.close()
            updateLearnItems(emptyList())
            return
        }
        toggle.visibility = View.VISIBLE
        engine.chatContext = screen.messages.map { it.text }
        // Translate everything on screen (also when hidden in the chat) so the Learn panel has it.
        val translated = screen.messages.mapNotNull { message ->
            (engine.lookup(message.text) as? TranslationResult.Done)?.let { LearnItem(message.text, it) }
        }
        updateLearnItems(translated)
        if (!settings.showTranslations) {
            clip.visibility = View.GONE
            return
        }

        if (hiddenForScroll) {
            // Still scrolling (or only just stopped): wait.
            val remaining = scrollSettleRemaining()
            if (remaining > 0) {
                scheduleRefresh(remaining)
                return
            }
            // A fling can keep moving without reporting every step: only show once two scans in
            // a row see every message in the same place.
            val positions = screen.messages.flatMap { listOf(it.bounds.top, it.bounds.left) } + screen.area.top
            if (positions != lastPositions) {
                lastPositions = positions
                scheduleRefresh(STILL_CHECK_MS)
                return
            }
        }

        overlay.getLocationOnScreen(overlayOrigin)
        placeClip(screen.area)
        val fadeInTogether = hiddenForScroll || clip.visibility != View.VISIBLE
        val nowShown = HashMap<String, TranslationLabel>()
        val seen = HashMap<String, Int>()
        for (message in screen.messages) {
            val result = engine.lookup(message.text)
            if (isCutOff(message, screen.area)) continue
            if (result !is TranslationResult.Done) continue
            // Same message keeps the same label, so redraws don't make it flicker.
            val n = seen.merge(message.text, 1, Int::plus)!!
            val key = message.text + "\u0000" + n
            val label = labelsByMessage.remove(key) ?: spareLabels.removeLastOrNull() ?: newLabel()
            val outgoing = message.bubble.centerX() > screen.area.centerX()
            label.bind(message, screen.area, result, bubbleColors[outgoing], animate = !fadeInTogether)
            nowShown[key] = label
        }
        for (label in labelsByMessage.values) {
            label.clear()
            spareLabels += label
        }
        labelsByMessage = nowShown
        if (nowShown.isNotEmpty()) sampleBubbleColors(screen)
        Log.d(TAG, "Refresh: ${screen.messages.size} messages, ${nowShown.size} translated")

        val wasHidden = clip.visibility != View.VISIBLE
        clip.visibility = View.VISIBLE
        if (hiddenForScroll || (wasHidden && clip.alpha < 1f)) {
            hiddenForScroll = false
            clip.animate().cancel()
            clip.animate().alpha(1f).setDuration(FADE_IN_MS).start()
            Log.d(TAG, "Shown after scroll")
        }
    }

    /**
     * WhatsApp's window if the user is in WhatsApp. Touching our own panel or button makes *our*
     * window the active one, so then look for WhatsApp underneath instead of assuming they left.
     */
    private fun whatsAppRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        val pkg = active?.packageName?.toString()
        if (pkg in WHATSAPP) return active
        if (pkg != packageName && pkg != null) return null
        // Our own panel/button is active, or Android has no active window for a moment (e.g.
        // while our panel closes): look for WhatsApp's window instead.
        return windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() in WHATSAPP }
    }

    private fun applyClaudeSetting() {
        val key = settings.claudeApiKey
        engine.claude = if (settings.useClaude && key != null) ClaudeTranslator(key) else null
    }

    private fun newLearnPanel() = LearnPanel(this, windowManager, settings, speaker, engine, ::insertIntoChat)

    /**
     * Puts [text] in WhatsApp's message box (after anything already typed there) and focuses
     * it, so the user only has to press send. Returns false if the box can't be found.
     */
    private fun insertIntoChat(text: String): Boolean {
        val root = whatsAppRoot() ?: return false
        val pkg = root.packageName?.toString() ?: return false
        val field = root.findAccessibilityNodeInfosByViewId("$pkg:id/entry").firstOrNull { it.isEditable }
            ?: findEditable(root, 0)
            ?: return false
        val existing = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        val combined = if (existing.isBlank()) text else existing.trimEnd() + " " + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
        }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, combined.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, combined.length)
        })
        return true
    }

    /** Fallback if WhatsApp renames its message box: the lowest editable field on screen. */
    private fun findEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 40) return null
        var best: AccessibilityNodeInfo? = if (node.isEditable && node.isVisibleToUser) node else null
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findEditable(it, depth + 1) } ?: continue
            if (best == null || Rect().also(found::getBoundsInScreen).top > Rect().also(best::getBoundsInScreen).top) best = found
        }
        return best
    }

    /** True when Android reports a different app (not WhatsApp, not us) as the active one. */
    private fun anotherAppInFront(): Boolean {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return false
        return pkg !in WHATSAPP && pkg != packageName
    }

    private var unknownFrontCount = 0

    private fun updateLearnItems(items: List<LearnItem>) {
        learnItems = items
        learnPanel.update(items)
    }

    /**
     * While WhatsApp is on screen, also hear when *another* app's window comes to the front, so
     * translations can vanish the instant the user presses Home or switches apps. Otherwise
     * (and by default, see accessibility_config.xml) only WhatsApp's events are received.
     */
    private fun listenForAppSwitches(enabled: Boolean) {
        val info = serviceInfo ?: return
        info.packageNames = if (enabled) null else WHATSAPP.toTypedArray()
        info.eventTypes = BASE_EVENTS or if (enabled) AccessibilityEvent.TYPE_WINDOWS_CHANGED else 0
        serviceInfo = info
    }

    /** The keyboard app: its window appearing doesn't mean the user left WhatsApp. */
    private fun keyboardPackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { ComponentName.unflattenFromString(it)?.packageName }

    private fun show() {
        if (showing) return
        showing = true
        listenForAppSwitches(true)
        bubbleColors.clear() // the user may have changed WhatsApp's theme meanwhile
        overlay.visibility = View.VISIBLE
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun hide() {
        if (!showing) return
        showing = false
        listenForAppSwitches(false)
        Log.d(TAG, "Hidden: left WhatsApp")
        overlay.visibility = View.GONE
        toggle.visibility = View.GONE
        learnPanel.close()
        handler.removeCallbacks(watchdog)
    }

    // ---- Drawing -------------------------------------------------------------------------

    private fun createWindows() {
        overlay = FrameLayout(this).apply { visibility = View.GONE }
        clip = FrameLayout(this).apply { clipChildren = true }
        overlay.addView(clip, FrameLayout.LayoutParams(0, 0))

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        toggle = ImageView(this).apply {
            setImageResource(R.drawable.ic_translate)
            scaleType = ImageView.ScaleType.CENTER
            elevation = dp(6).toFloat()
            visibility = View.GONE
            contentDescription = "Open translations and learning panel"
        }
        val size = dp(42)
        toggleParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // WATCH_OUTSIDE_TOUCH: we hear about every touch elsewhere (no position, just "a
            // finger went down"), which starts the motion watch.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = settings.toggleY.takeIf { it >= 0 } ?: (resources.displayMetrics.heightPixels * 0.3).toInt()
        }
        attachToggleTouch()
        updateToggleLook()

        windowManager.addView(overlay, overlayParams)
        windowManager.addView(toggle, toggleParams)
        attached = true
    }

    /** Tap to open the Learn panel, drag up or down to move the button out of the way. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachToggleTouch() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downY = 0f
        var startY = 0
        var dragging = false
        toggle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> if (showing) startMotionWatch()
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startY = toggleParams.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dy) > slop) dragging = true
                    if (dragging) {
                        toggleParams.y = (startY + dy).coerceAtLeast(0)
                        windowManager.updateViewLayout(toggle, toggleParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        settings.toggleY = toggleParams.y
                    } else {
                        view.performClick()
                        learnPanel.open(learnItems)
                    }
                }
            }
            true
        }
    }

    /** On: green button with white icon. Off: white button with grey icon. */
    private fun updateToggleLook() {
        val on = settings.showTranslations
        toggle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) Color.parseColor("#00A884") else Color.WHITE)
            if (!on) setStroke(dp(1), Color.parseColor("#33000000"))
        }
        toggle.setColorFilter(if (on) Color.WHITE else Color.parseColor("#8696A0"))
    }

    private fun placeClip(area: Rect) {
        val lp = clip.layoutParams as FrameLayout.LayoutParams
        val left = area.left - overlayOrigin[0]
        val top = area.top - overlayOrigin[1]
        if (lp.leftMargin != left || lp.topMargin != top || lp.width != area.width() || lp.height != area.height()) {
            lp.leftMargin = left
            lp.topMargin = top
            lp.width = area.width()
            lp.height = area.height()
            clip.layoutParams = lp
        }
    }

    /**
     * A message half off the top or bottom of the list only reports its visible part, so it
     * can't be measured properly. It gets translated once it scrolls fully into view (very tall
     * messages, bigger than half the list, are translated anyway).
     */
    private fun isCutOff(message: ChatMessage, area: Rect): Boolean {
        val touchesEdge = message.view.top <= area.top || message.view.bottom >= area.bottom
        return touchesEdge && message.view.height() < area.height() / 2
    }

    /** Light/dark mode changed: rebuild labels so they pick up the new colours. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!attached) return
        labels.forEach { clip.removeView(it) }
        labels.clear()
        labelsByMessage.clear()
        spareLabels.clear()
        bubbleColors.clear()
        learnPanel.close()
        learnPanel = newLearnPanel()
        scheduleRefresh(0)
    }

    /**
     * WhatsApp has its own theme setting, so instead of guessing, read the bubble colours from a
     * screenshot: one pixel just above each bubble's bottom edge, below the text and outside
     * our labels. Android 11+ only; older phones use the default colours.
     */
    private fun sampleBubbleColors(screen: ChatScreen) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || sampling) return
        val wanted = screen.messages.filter { it.bubble != it.view && !isCutOff(it, screen.area) }
            .groupBy { it.bubble.centerX() > screen.area.centerX() }
            .filterKeys { it !in bubbleColors }
        if (wanted.isEmpty()) return
        sampling = true
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                sampling = false
                val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                val bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                hardware?.recycle()
                result.hardwareBuffer.close()
                if (bitmap == null) return
                for ((outgoing, messages) in wanted) {
                    val bubble = messages.first().bubble
                    val x = bubble.centerX()
                    val y = bubble.bottom - dp(2)
                    if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                        bubbleColors[outgoing] = bitmap.getPixel(x, y) or 0xFF000000.toInt()
                    }
                }
                bitmap.recycle()
                Log.d(TAG, "Sampled bubble colours: ${bubbleColors.mapValues { Integer.toHexString(it.value) }}")
                scheduleRefresh(0)
            }

            override fun onFailure(errorCode: Int) {
                sampling = false
                Log.d(TAG, "Screenshot for colours failed: $errorCode")
            }
        })
    }

    private fun newLabel(): TranslationLabel = TranslationLabel(this).also {
        labels += it
        clip.addView(it, FrameLayout.LayoutParams(0, 0))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ChatTranslator"
        val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
        const val SYSTEM_UI = "com.android.systemui"
        const val RECHECK_AFTER_SWITCH_MS = 1000L
        const val UNKNOWN_FRONT_RETRY_MS = 150L
        const val UNKNOWN_FRONT_LIMIT = 4
        const val BASE_EVENTS = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED
        const val CONTENT_DEBOUNCE_MS = 120L
        /** How long the chat must be still before translations come back. */
        const val SCROLL_SETTLE_MS = 450L
        const val STILL_CHECK_MS = 100L
        const val MOTION_POLL_MS = 40L
        const val MOTION_WATCH_MS = 1500L
        const val FADE_IN_MS = 200L
        const val WATCHDOG_MS = 250L
    }
}

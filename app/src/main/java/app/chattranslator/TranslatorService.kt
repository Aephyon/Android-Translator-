package app.chattranslator

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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

    private lateinit var overlay: FrameLayout
    private lateinit var clip: FrameLayout
    private lateinit var toggle: ImageView
    private lateinit var toggleParams: WindowManager.LayoutParams
    private val labels = ArrayList<TranslationLabel>()

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
            if (rootInActiveWindow?.packageName?.toString() !in WHATSAPP) hide()
            else handler.postDelayed(this, WATCHDOG_MS)
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
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        createWindows()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!attached) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Hide right away so translations never trail behind a moving list.
                clip.visibility = View.INVISIBLE
                scheduleRefresh(SCROLL_SETTLE_MS)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scheduleRefresh(0)
            else -> scheduleRefresh(CONTENT_DEBOUNCE_MS)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::settings.isInitialized) settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (::engine.isInitialized) engine.close()
        if (attached) {
            windowManager.removeView(overlay)
            windowManager.removeView(toggle)
            attached = false
        }
        super.onDestroy()
    }

    private fun scheduleRefresh(delayMs: Long) {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, delayMs)
    }

    private fun refresh() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root == null || pkg !in WHATSAPP) {
            hide()
            return
        }
        show()
        if (!settings.showTranslations) {
            clip.visibility = View.GONE
            return
        }
        val screen = MessageScanner.scan(root, pkg!!)
        if (screen == null) {
            clip.visibility = View.GONE
            return
        }

        overlay.getLocationOnScreen(overlayOrigin)
        placeClip(screen.area)
        var used = 0
        for (message in screen.messages) {
            val result = engine.lookup(message.text)
            if (isCutOff(message, screen.area)) continue
            if (result is TranslationResult.Done) {
                val outgoing = message.bubble.centerX() > screen.area.centerX()
                labelAt(used++).bind(message, screen.area, result, bubbleColors[outgoing])
            }
        }
        for (i in used until labels.size) labels[i].clear()
        if (used > 0) sampleBubbleColors(screen)
        Log.d(TAG, "Refresh: ${screen.messages.size} messages, $used translated, area=${screen.area}")
        clip.visibility = View.VISIBLE
    }

    private fun show() {
        if (showing) return
        showing = true
        bubbleColors.clear() // the user may have changed WhatsApp's theme meanwhile
        overlay.visibility = View.VISIBLE
        toggle.visibility = View.VISIBLE
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun hide() {
        if (!showing) return
        showing = false
        overlay.visibility = View.GONE
        toggle.visibility = View.GONE
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
            contentDescription = "Switch between translation and original"
        }
        val size = dp(42)
        toggleParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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

    /** Tap to switch translation on/off, drag up or down to move the button out of the way. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachToggleTouch() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downY = 0f
        var startY = 0
        var dragging = false
        toggle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
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
                        settings.showTranslations = !settings.showTranslations
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
        val touchesEdge = message.bounds.top <= area.top || message.bounds.bottom >= area.bottom
        return touchesEdge && message.bounds.height() < area.height() / 2
    }

    /** Light/dark mode changed: rebuild labels so they pick up the new colours. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!attached) return
        labels.forEach { clip.removeView(it) }
        labels.clear()
        bubbleColors.clear()
        scheduleRefresh(0)
    }

    /**
     * WhatsApp has its own theme setting, so instead of guessing, read the bubble colours from a
     * screenshot: one pixel just above each bubble's bottom edge, below the text and outside
     * our labels. Android 11+ only; older phones use the default colours.
     */
    private fun sampleBubbleColors(screen: ChatScreen) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || sampling) return
        val wanted = screen.messages.filter { it.bubble != it.bounds && !isCutOff(it, screen.area) }
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

    private fun labelAt(index: Int): TranslationLabel =
        labels.getOrNull(index) ?: TranslationLabel(this).also {
            labels += it
            clip.addView(it, FrameLayout.LayoutParams(0, 0))
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ChatTranslator"
        val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
        const val CONTENT_DEBOUNCE_MS = 120L
        const val SCROLL_SETTLE_MS = 180L
        const val WATCHDOG_MS = 700L
    }
}

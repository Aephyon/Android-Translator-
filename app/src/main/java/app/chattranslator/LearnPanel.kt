package app.chattranslator

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** One foreign-language message on screen and its translation. */
data class LearnItem(val original: String, val translation: TranslationResult.Done)

/**
 * A card sheet that slides up over WhatsApp and lists the foreign-language messages on screen
 * as learning pairs: language, original (large), translation (below), and a button that reads
 * the original aloud. Also holds the switch for showing translations inside the chat itself.
 */
class LearnPanel(
    context: Context,
    private val windowManager: WindowManager,
    private val settings: AppSettings,
    private val speaker: Speaker,
) {
    private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val context: Context = ContextThemeWrapper(
        context,
        if (dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light,
    )
    private val density = context.resources.displayMetrics.density
    private val ui = if (dark) Colors.DARK else Colors.LIGHT

    private var root: FrameLayout? = null
    private lateinit var sheet: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var chatSwitch: Switch
    private var shownItems: List<LearnItem>? = null

    val isOpen get() = root != null

    fun open(items: List<LearnItem>) {
        if (isOpen) return
        val scrim = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener { close() }
        }
        sheet = buildSheet()
        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.62).toInt()
        scrim.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        sheet.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        (list.parent as ScrollView).layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        sheet.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                sheet.viewTreeObserver.removeOnPreDrawListener(this)
                if (sheet.height > maxHeight) {
                    sheet.layoutParams.height = maxHeight
                    sheet.requestLayout()
                }
                sheet.translationY = sheet.height.toFloat()
                sheet.animate().translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
                return true
            }
        })
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(200).start()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // Keep the last card clear of the navigation/gesture bar.
        scrim.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            sheet.setPadding(0, dp(10), 0, dp(12) + insets.systemWindowInsetBottom)
            insets
        }
        windowManager.addView(scrim, params)
        root = scrim
        update(items)
    }

    /** Refreshes the cards, e.g. when more translations finish while the panel is open. */
    fun update(items: List<LearnItem>) {
        if (!isOpen || items == shownItems) return
        shownItems = items
        chatSwitch.isChecked = settings.showTranslations
        list.removeAllViews()
        val languages = items.map { AppSettings.displayName(it.translation.sourceLanguage) }.distinct()
        subtitle.text = when {
            items.isEmpty() -> "Into ${AppSettings.displayName(settings.targetLanguage)}"
            else -> languages.joinToString(", ") + "  →  " + AppSettings.displayName(settings.targetLanguage)
        }
        if (items.isEmpty()) {
            list.addView(text("No messages in another language on screen right now.", 15f, ui.secondary).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(28), dp(16), dp(36))
            })
            return
        }
        for (item in items) list.addView(card(item))
    }

    fun close() {
        val view = root ?: return
        root = null
        shownItems = null
        speaker.stop()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(180).start()
        view.animate().alpha(0f).setDuration(180).withEndAction {
            runCatching { windowManager.removeView(view) }
        }.start()
    }

    // ---- Views ---------------------------------------------------------------------------

    private fun buildSheet(): LinearLayout {
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // keep taps on the sheet from closing it via the scrim
            background = GradientDrawable().apply {
                setColor(ui.sheet)
                cornerRadii = FloatArray(8) { i -> if (i < 4) dp(24).toFloat() else 0f }
            }
            setPadding(0, dp(10), 0, dp(12))
            elevation = dp(16).toFloat()
        }

        // Drag-handle look, as on Android bottom sheets.
        sheet.addView(View(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(ui.handle) }
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(12), dp(6))
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("Learn", 22f, ui.primary, bold = true))
        subtitle = text("", 13f, ui.secondary)
        titles.addView(subtitle)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton(R.drawable.ic_close, ui.secondary, "Close") { close() })
        sheet.addView(header)

        val switchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(4), dp(16), dp(8))
        }
        switchRow.addView(
            text("Show translations in the chat", 15f, ui.primary),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        chatSwitch = Switch(context).apply {
            isChecked = settings.showTranslations
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ui.accent, ui.handle),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ui.accentSoft, ui.card),
            )
            setOnCheckedChangeListener { _, checked -> settings.showTranslations = checked }
        }
        switchRow.addView(chatSwitch)
        sheet.addView(switchRow)

        list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(2), dp(14), dp(4))
        }
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(list) }
        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return sheet
    }

    /** Language chip + speak button, the original in large type, then the translation. */
    private fun card(item: LearnItem): View {
        val language = item.translation.sourceLanguage
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(ui.card) }
            setPadding(dp(16), dp(12), dp(10), dp(14))
        }

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text(AppSettings.displayName(language), 12f, ui.accentText, bold = true).apply {
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(ui.accentSoft) }
            setPadding(dp(10), dp(3), dp(10), dp(3))
        })
        top.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(iconButton(R.drawable.ic_volume, ui.accentText, "Read aloud in ${AppSettings.displayName(language)}") {
            speaker.speak(item.original, language)
        })
        card.addView(top)

        card.addView(text(item.original, 18f, ui.primary).apply {
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setLineSpacing(0f, 1.1f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
            rightMargin = dp(6)
        })

        // Translation, set off by a short accent rule so the pair reads "this → means this".
        val meaning = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        meaning.addView(View(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(ui.accent) }
        }, LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(10) })
        meaning.addView(text(item.translation.text, 15f, ui.secondary).apply { setLineSpacing(0f, 1.1f) })
        card.addView(meaning, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            rightMargin = dp(6)
        })

        return card.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
    }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun iconButton(icon: Int, tint: Int, description: String, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(tint)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        setBackgroundResource(outValue.resourceId)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * density).toInt()

    private class Colors(
        val sheet: Int, val card: Int, val primary: Int, val secondary: Int,
        val accent: Int, val accentSoft: Int, val accentText: Int, val handle: Int,
    ) {
        companion object {
            val LIGHT = Colors(
                sheet = Color.parseColor("#FFFFFF"),
                card = Color.parseColor("#F0F4F3"),
                primary = Color.parseColor("#111B21"),
                secondary = Color.parseColor("#54656F"),
                accent = Color.parseColor("#00A884"),
                accentSoft = Color.parseColor("#D8F3EA"),
                accentText = Color.parseColor("#007A5E"),
                handle = Color.parseColor("#C9D1D5"),
            )
            val DARK = Colors(
                sheet = Color.parseColor("#111B21"),
                card = Color.parseColor("#1F2C34"),
                primary = Color.parseColor("#E9EDEF"),
                secondary = Color.parseColor("#AEBAC1"),
                accent = Color.parseColor("#00A884"),
                accentSoft = Color.parseColor("#0B3B30"),
                accentText = Color.parseColor("#21C99B"),
                handle = Color.parseColor("#3B4A54"),
            )
        }
    }
}

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
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.PopupMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private val engine: TranslationEngine,
    /** Puts the text in WhatsApp's message box; false if that box couldn't be found. */
    private val insertIntoChat: (String) -> Boolean,
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
    private lateinit var sheet: Sheet
    private lateinit var list: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var chatSwitch: Switch
    private var shownItems: List<LearnItem>? = null

    // Reply composer
    private val scope = MainScope()
    private lateinit var replyInput: EditText
    private lateinit var replyPreview: TextView
    private lateinit var replyPreviewRow: View
    private lateinit var replyChip: TextView
    private lateinit var insertButton: TextView
    private var replyLanguage: String = "en"
    private var replyLanguageChosen = false
    private var replyJob: Job? = null
    private var replyTranslation: String? = null

    /** The sheet never covers more than ~60% of the screen, or most of the space above the keyboard. */
    private inner class Sheet(context: Context) : LinearLayout(context) {
        var keyboardOpen = false
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = MeasureSpec.getSize(heightMeasureSpec)
            val limit = if (keyboardOpen) (available * 0.94f).toInt()
            else minOf(available, (resources.displayMetrics.heightPixels * 0.62f).toInt())
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
        }
    }

    /** Full-screen dimmed backdrop; tapping it or pressing Back closes the panel. */
    private inner class Scrim(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) close()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    val isOpen get() = root != null

    fun open(items: List<LearnItem>) {
        if (isOpen) return
        val scrim = Scrim(context).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener { close() }
            isFocusableInTouchMode = true
        }
        sheet = buildSheet()
        scrim.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        (list.parent as ScrollView).layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        sheet.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                sheet.viewTreeObserver.removeOnPreDrawListener(this)
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
            // Focusable (unlike the other overlays) so the keyboard can type into the reply box.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // Keep clear of the navigation bar, and sit on top of the keyboard while typing a reply.
        scrim.setOnApplyWindowInsetsListener { _, insets ->
            val keyboard: Int
            val navBar: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom
                navBar = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                keyboard = 0
                @Suppress("DEPRECATION")
                navBar = insets.systemWindowInsetBottom
            }
            sheet.keyboardOpen = keyboard > 0
            scrim.setPadding(0, 0, 0, keyboard)
            sheet.setPadding(0, dp(10), 0, dp(8) + if (keyboard > 0) 0 else navBar)
            sheet.requestLayout()
            insets
        }
        windowManager.addView(scrim, params)
        root = scrim
        replyLanguageChosen = false
        update(items)
    }

    /** Refreshes the cards, e.g. when more translations finish while the panel is open. */
    fun update(items: List<LearnItem>) {
        if (!isOpen || items == shownItems) return
        shownItems = items
        if (!replyLanguageChosen) setReplyLanguage(defaultReplyLanguage(items), chosen = false)
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
        replyJob?.cancel()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(180).start()
        view.animate().alpha(0f).setDuration(180).withEndAction {
            runCatching { windowManager.removeView(view) }
        }.start()
    }

    // ---- Views ---------------------------------------------------------------------------

    private fun buildSheet(): Sheet {
        val sheet = Sheet(context).apply {
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
        sheet.addView(buildComposer())
        return sheet
    }

    // ---- Reply composer ------------------------------------------------------------------

    /**
     * "Reply in Spanish": type in your own language, see the translation, and put it in
     * WhatsApp's message box. Sending stays up to the user.
     */
    private fun buildComposer(): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(4))
        }
        box.addView(View(context).apply { setBackgroundColor(ui.handle) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { bottomMargin = dp(10) })

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text("Reply in", 15f, ui.primary, bold = true))
        replyChip = text("", 13f, ui.accentText, bold = true).apply {
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(ui.accentSoft) }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            contentDescription = "Choose the language to reply in"
            setOnClickListener { pickReplyLanguage(this) }
        }
        header.addView(replyChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        box.addView(header)

        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        replyInput = EditText(context).apply {
            hint = "Write in ${AppSettings.displayName(settings.targetLanguage)}…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ui.primary)
            setHintTextColor(ui.secondary)
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(ui.card) }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minHeight = dp(44)
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = translateReply()
            })
        }
        inputRow.addView(replyInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        insertButton = text("Insert", 15f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(ui.accent) }
            setPadding(dp(18), 0, dp(18), 0)
            minHeight = dp(44)
            contentDescription = "Put the translation in WhatsApp's message box"
            setOnClickListener { insertReply() }
        }
        inputRow.addView(insertButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { leftMargin = dp(8) })
        box.addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        // Live translation, styled like the cards: accent bar, then the text.
        val previewRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(dp(6), dp(10), dp(6), dp(2))
        }
        previewRow.addView(View(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(ui.accent) }
        }, LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(10) })
        replyPreview = text("", 17f, ui.primary).apply {
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setLineSpacing(0f, 1.1f)
        }
        previewRow.addView(replyPreview)
        replyPreviewRow = previewRow
        box.addView(previewRow)

        updateInsertButton()
        return box
    }

    private fun defaultReplyLanguage(items: List<LearnItem>): String =
        items.groupingBy { it.translation.sourceLanguage }.eachCount()
            .filterKeys { it != settings.targetLanguage }
            .maxByOrNull { it.value }?.key
            ?: settings.replyLanguage?.takeIf { it != settings.targetLanguage }
            ?: if (settings.targetLanguage == "en") "es" else "en"

    private fun setReplyLanguage(language: String, chosen: Boolean) {
        if (chosen) replyLanguageChosen = true
        if (language == replyLanguage && replyChip.text.isNotEmpty()) return
        replyLanguage = language
        replyChip.text = AppSettings.displayName(language) + "  ▾"
        translateReply()
    }

    /** Languages seen in this chat first, then all others alphabetically. */
    private fun pickReplyLanguage(anchor: View) {
        val seen = (shownItems ?: emptyList()).map { it.translation.sourceLanguage }
            .filter { it != settings.targetLanguage }.distinct()
        val others = AppSettings.supportedLanguages.filter { it !in seen && it != settings.targetLanguage }
            .sortedBy { AppSettings.displayName(it) }
        val choices = seen + others
        val menu = PopupMenu(context, anchor)
        choices.forEachIndexed { i, code -> menu.menu.add(0, i, i, AppSettings.displayName(code)) }
        menu.setOnMenuItemClickListener { item ->
            setReplyLanguage(choices[item.itemId], chosen = true)
            true
        }
        menu.show()
    }

    private fun translateReply() {
        if (!::replyInput.isInitialized) return
        replyJob?.cancel()
        replyTranslation = null
        val typed = replyInput.text.toString().trim()
        updateInsertButton()
        if (typed.isEmpty()) {
            replyPreviewRow.visibility = View.GONE
            return
        }
        val from = settings.targetLanguage
        val to = replyLanguage
        replyJob = scope.launch {
            delay(REPLY_DEBOUNCE_MS)
            if (replyPreview.text.isEmpty()) showPreview("…", ui.secondary)
            try {
                val result = engine.translateReply(typed, from, to)
                replyTranslation = result
                showPreview(result, ui.primary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showPreview("Couldn't translate. The ${AppSettings.displayName(to)} language pack needs internet the first time.", ui.secondary)
            }
            updateInsertButton()
        }
    }

    private fun showPreview(value: String, color: Int) {
        replyPreview.text = value
        replyPreview.setTextColor(color)
        replyPreviewRow.visibility = View.VISIBLE
    }

    private fun updateInsertButton() {
        val ready = replyTranslation != null
        insertButton.isEnabled = ready
        insertButton.alpha = if (ready) 1f else 0.4f
    }

    private fun insertReply() {
        val translation = replyTranslation ?: return
        if (insertIntoChat(translation)) {
            settings.replyLanguage = replyLanguage
            replyInput.setText("")
            close()
        } else {
            showPreview("Couldn't find WhatsApp's message box. Open the chat and try again.", ui.secondary)
        }
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

    private companion object {
        const val REPLY_DEBOUNCE_MS = 350L
    }

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

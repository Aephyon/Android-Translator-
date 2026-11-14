package app.chattranslator

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max

/**
 * A translation drawn on top of a chat bubble. It takes the bubble's own colour and only marks
 * itself with a thin accent bar and a small language code, so it reads as part of the chat.
 */
class TranslationLabel(context: Context) : TextView(context) {
    private val density = resources.displayMetrics.density
    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val palette = if (dark) Palette.DARK else Palette.LIGHT
    private var appliedFill: Int? = null
    private val padH = dp(6)
    private val padV = dp(2)

    private var shown: TranslationResult.Done? = null
    private var metaColor = palette.meta
    private var geometryKey: List<Int>? = null

    init {
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextColor(palette.text)
        setPadding(padH, padV, padH, padV)
        visibility = View.GONE
    }

    /**
     * Positions the label over [message] (coordinates relative to [area]) and shows [result].
     * [bubbleColor] is the colour measured on screen, if known; otherwise WhatsApp's default
     * colours for the current light/dark theme are used.
     */
    fun bind(message: ChatMessage, area: Rect, result: TranslationResult.Done, bubbleColor: Int?, animate: Boolean = true) {
        val isOutgoing = message.bubble.centerX() > area.centerX()
        val fill = bubbleColor ?: if (isOutgoing) palette.outgoing else palette.incoming
        if (appliedFill != fill) {
            appliedFill = fill
            background = makeBackground(fill)
            val darkBubble = luminance(fill) < 0.5
            setTextColor(if (darkBubble) Palette.DARK.text else Palette.LIGHT.text)
            metaColor = if (darkBubble) Palette.DARK.meta else Palette.LIGHT.meta
            shown = null // rebuild the text so the language code gets the new colour
        }

        val textChanged = shown != result
        if (textChanged) {
            shown = result
            text = buildText(result)
        }

        val text = message.bounds
        // Use the bubble's full inner width (mirror the text's left inset on the right side).
        val inset = max(0, text.left - message.bubble.left)
        val maxWidth = max(text.width(), message.bubble.right - inset - text.left)
        val key = listOf(maxWidth, text.width(), text.height())
        if (textChanged || key != geometryKey) {
            geometryKey = key
            val fit = fit(this.text, maxWidth, text.height())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fit.textSize)
            val lp = layoutParams as FrameLayout.LayoutParams
            lp.width = max(fit.width, text.width()) + 2 * padH
            lp.height = max(fit.height, text.height()) + 2 * padV
            layoutParams = lp
        }
        translationX = (text.left - area.left - padH).toFloat()
        translationY = (text.top - area.top - padV).toFloat()

        if (visibility != View.VISIBLE || textChanged) {
            visibility = View.VISIBLE
            animate().cancel()
            if (animate) {
                // A translation that just finished: fade it in on its own.
                alpha = 0f
                animate().alpha(1f).setDuration(FADE_MS).start()
            } else {
                alpha = 1f // the whole layer is fading in together (e.g. after scrolling)
            }
        }
    }

    fun clear() {
        visibility = View.GONE
        shown = null
    }

    private fun buildText(result: TranslationResult.Done): CharSequence {
        val tag = "  " + result.sourceLanguage.uppercase()
        return SpannableStringBuilder(result.text).apply {
            val start = length
            append(tag)
            setSpan(RelativeSizeSpan(0.72f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(metaColor), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private class Fit(val textSize: Float, val width: Int, val height: Int)

    /**
     * Largest text size (from WhatsApp's size down to a readable minimum) at which the translation
     * fits in the space of the original. If even the minimum doesn't fit, the label grows taller.
     */
    private fun fit(text: CharSequence, maxWidth: Int, targetHeight: Int): Fit {
        val paint = TextPaint(paint)
        var sp = MAX_SP
        while (true) {
            paint.textSize = sp * resources.displayMetrics.scaledDensity
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
            // A few pixels taller than the original is fine; shrinking text for that looks worse.
            if (layout.height <= targetHeight * HEIGHT_TOLERANCE || sp <= MIN_SP) {
                val width = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
                return Fit(paint.textSize, ceil(width).toInt() + 1, layout.height)
            }
            sp -= 0.5f
        }
    }

    private fun makeBackground(fill: Int): LayerDrawable {
        val radius = dp(4).toFloat()
        val base = GradientDrawable().apply { cornerRadius = radius; setColor(fill) }
        val bar = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(palette.accent) }
        return LayerDrawable(arrayOf(base, bar)).apply {
            setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
            setLayerWidth(1, dp(3))
            setLayerInset(1, 0, dp(2), 0, dp(2))
        }
    }

    private fun dp(value: Int) = (value * density).toInt()

    private fun luminance(color: Int) =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255

    /** WhatsApp's own chat colours, so labels blend into the bubbles. */
    private class Palette(val incoming: Int, val outgoing: Int, val text: Int, val meta: Int, val accent: Int) {
        companion object {
            val LIGHT = Palette(
                incoming = Color.parseColor("#FFFFFF"),
                outgoing = Color.parseColor("#D9FDD3"),
                text = Color.parseColor("#111B21"),
                meta = Color.parseColor("#667781"),
                accent = Color.parseColor("#00A884"),
            )
            val DARK = Palette(
                incoming = Color.parseColor("#202C33"),
                outgoing = Color.parseColor("#005C4B"),
                text = Color.parseColor("#E9EDEF"),
                meta = Color.parseColor("#8696A0"),
                accent = Color.parseColor("#00A884"),
            )
        }
    }

    private companion object {
        const val MAX_SP = 15f
        const val MIN_SP = 12f
        const val FADE_MS = 160L
        const val HEIGHT_TOLERANCE = 1.15f
    }
}

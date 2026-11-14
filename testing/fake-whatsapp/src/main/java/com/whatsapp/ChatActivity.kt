package com.whatsapp

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Stand-in for a WhatsApp chat: a RecyclerView (rows are recycled while scrolling, like the real
 * app) whose message text uses the id `message_text`. Launch with `--ez light true` to keep the
 * chat light while the phone is dark (like WhatsApp's own theme setting).
 */
class ChatActivity : Activity() {
    private val base = listOf(
        false to "¿Vienes a cenar el domingo? La abuela va a hacer paella.",
        true to "Yes, I'll be there around six!",
        false to "Perfecto, no te olvides de traer el postre.",
        false to "Je ne pourrai pas venir, je suis malade.",
        false to "Ich bringe den Wein mit.",
        true to "😂👍",
        false to "Nos vemos pronto, un beso a todos.",
        false to "¿Alguien sabe a qué hora empieza la película?",
        true to "I think it starts at eight.",
        false to "Vale, entonces quedamos a las siete y media.",
    )
    private val messages = (0 until 6).flatMap { round ->
        base.map { (mine, text) -> mine to if (round == 0) text else "$text ($round)" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Familia"
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES && !intent.getBooleanExtra("light", false)

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ChatActivity)
            setBackgroundColor(Color.parseColor(if (dark) "#0B141A" else "#EFE7DE"))
            setPadding(24, 12, 24, 12)
            clipToPadding = false
            adapter = Adapter(dark)
        }
        setContentView(list)
    }

    private inner class Holder(val row: LinearLayout, val bubble: LinearLayout, val text: TextView) :
        RecyclerView.ViewHolder(row)

    private inner class Adapter(private val dark: Boolean) : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = messages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(0, 10, 0, 10)
            }
            val bubble = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(2, 16, 24, 12)
            }
            val text = TextView(parent.context).apply {
                id = R.id.message_text
                // WhatsApp's text view has invisible padding around the letters; mimic it.
                setPadding(22, 0, 0, 0)
                textSize = 15f
                setTextColor(if (dark) Color.parseColor("#E9EDEF") else Color.BLACK)
            }
            bubble.addView(text)
            bubble.addView(TextView(parent.context).apply {
                id = R.id.date
                this.text = "14:05"
                textSize = 11f
                gravity = Gravity.END
                setTextColor(Color.parseColor(if (dark) "#8696A0" else "#667781"))
            })
            row.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return Holder(row, bubble, text)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (mine, text) = messages[position]
            holder.text.text = text
            holder.row.gravity = if (mine) Gravity.END else Gravity.START
            holder.bubble.background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(
                    when {
                        dark && mine -> Color.parseColor("#005C4B")
                        dark -> Color.parseColor("#202C33")
                        mine -> Color.parseColor("#D9FDD3")
                        else -> Color.WHITE
                    }
                )
            }
            (holder.bubble.layoutParams as LinearLayout.LayoutParams).apply {
                if (mine) { leftMargin = 160; rightMargin = 0 } else { leftMargin = 0; rightMargin = 160 }
            }
        }
    }
}

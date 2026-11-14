package com.whatsapp

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ChatActivity : Activity() {
    private val messages = listOf(
        false to "¿Vienes a cenar el domingo? La abuela va a hacer paella.",
        true to "Yes, I'll be there around six!",
        false to "Perfecto, no te olvides de traer el postre.",
        false to "Je ne pourrai pas venir, je suis malade.",
        false to "Ich bringe den Wein mit.",
        true to "😂👍",
        false to "Nos vemos pronto, un beso a todos.",
    ) + (1..12).map { false to "Mensaje número $it: hace mucho calor hoy en Madrid." }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Familia"
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES &&
            !intent.getBooleanExtra("light", false) // like WhatsApp's own theme overriding the phone's
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        for ((mine, text) in messages) {
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 16, 24, 12)
                background = GradientDrawable().apply {
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
                addView(TextView(context).apply {
                    id = R.id.message_text
                    this.text = text
                    textSize = 15f
                    setTextColor(if (dark) Color.parseColor("#E9EDEF") else Color.BLACK)
                })
                addView(TextView(context).apply {
                    id = R.id.date
                    this.text = "14:05"
                    textSize = 11f
                    gravity = Gravity.END
                })
            }
            column.addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = if (mine) Gravity.END else Gravity.START
                setMargins(0, 0, 0, 20)
                if (mine) leftMargin = 160 else rightMargin = 160
            })
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor(if (dark) "#0B141A" else "#EFE7DE")); addView(column) })
    }
}

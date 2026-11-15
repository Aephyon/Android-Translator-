package com.whatsapp

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Stand-in for WhatsApp's chats list: rows with a contact name and a preview of the last
 * message (Spanish), no message_text ids and no message box. Must never be translated.
 */
class ChatListActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "WhatsApp"
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24) }
        val rows = listOf(
            "Familia" to "Abuela: ¿Vienes a cenar el domingo?",
            "Jessica" to "Dice que no quiere hacerse ilusiones",
            "Secret Santa" to "Tú: Entendido, no te preocupes",
            "Olga" to "Llegamos el martes por la tarde",
        )
        for ((name, preview) in rows) {
            column.addView(TextView(this).apply { text = name; textSize = 17f; setTextColor(Color.BLACK); setPadding(0, 24, 0, 0) })
            column.addView(TextView(this).apply {
                text = preview; textSize = 15f; setTextColor(Color.DKGRAY); setPadding(0, 4, 0, 24)
                setOnClickListener { startActivity(Intent(this@ChatListActivity, ChatActivity::class.java)) }
            })
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.WHITE); addView(column) })
    }
}

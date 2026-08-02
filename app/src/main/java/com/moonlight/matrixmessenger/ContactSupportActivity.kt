package com.moonlight.matrixmessenger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ContactSupportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_support)

        findViewById<TextView>(R.id.supportEmailRow).setOnClickListener {
            openEmail("moonhpro4@gmail.com")
        }

        findViewById<TextView>(R.id.businessEmailRow).setOnClickListener {
            openEmail("moonhpro318@gmail.com")
        }

        findViewById<TextView>(R.id.youtubeRow).setOnClickListener {
            openUrl("https://youtube.com/@moonhpro4")
        }

        findViewById<TextView>(R.id.whatsappRow).setOnClickListener {
            openUrl("https://wa.me/971547275188")
        }

        findViewById<TextView>(R.id.discordRow).setOnClickListener {
            openUrl("https://discord.gg/FNPYczarjt")
        }

        findViewById<TextView>(R.id.redditRow).setOnClickListener {
            openUrl("https://www.reddit.com/r/matrixmessenger/")
        }
    }

    private fun openEmail(address: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$address")
        }
        startActivity(Intent.createChooser(intent, "Send email"))
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

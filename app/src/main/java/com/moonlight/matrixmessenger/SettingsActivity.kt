package com.moonlight.matrixmessenger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    companion object {
        // Update this if the repo is ever renamed/moved.
        const val GITHUB_ISSUES_URL = "https://github.com/moonhpro4/matrix-messenger/issues/new"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val username = intent.getStringExtra("username") ?: "unknown"

        findViewById<TextView>(R.id.accountRow).setOnClickListener {
            startActivity(Intent(this, AccountSettingsActivity::class.java).apply {
                putExtra("username", username)
            })
        }

        findViewById<TextView>(R.id.languageRow).setOnClickListener {
            startActivity(Intent(this, LanguageActivity::class.java))
        }

        findViewById<TextView>(R.id.backupsRow).setOnClickListener {
            startActivity(Intent(this, BackupsActivity::class.java).apply {
                putExtra("username", username)
            })
        }

        findViewById<TextView>(R.id.reportMatrixRow).setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_ISSUES_URL))
            startActivity(browserIntent)
        }
    }
}

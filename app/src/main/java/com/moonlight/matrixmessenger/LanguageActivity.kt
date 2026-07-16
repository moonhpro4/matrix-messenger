package com.moonlight.matrixmessenger

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Lets the user search/pick a language. Selecting one persists automatically
 * via AndroidX's per-app language API (AppCompatDelegate.setApplicationLocales) —
 * this is backed by the framework itself, survives app restarts, and is
 * cleared only on uninstall, satisfying "set permanently until you
 * uninstall it" without needing our own SharedPreferences bookkeeping.
 *
 * If the user never opens this screen, the app naturally follows the
 * phone's system language by default, since no override has been set.
 */
class LanguageActivity : AppCompatActivity() {

    // (Display name, BCP-47 language tag). Add more as needed.
    private val languages = listOf(
        "English" to "en",
        "Arabic (العربية)" to "ar",
        "Spanish (Español)" to "es",
        "French (Français)" to "fr",
        "German (Deutsch)" to "de",
        "Hindi (हिन्दी)" to "hi",
        "Portuguese (Português)" to "pt",
        "Russian (Русский)" to "ru",
        "Japanese (日本語)" to "ja",
        "Korean (한국어)" to "ko",
        "Chinese, Simplified (简体中文)" to "zh-Hans",
        "Turkish (Türkçe)" to "tr",
        "Urdu (اردو)" to "ur",
        "Indonesian (Bahasa Indonesia)" to "id",
        "Italian (Italiano)" to "it",
        "Dutch (Nederlands)" to "nl",
        "Bengali (বাংলা)" to "bn",
        "Vietnamese (Tiếng Việt)" to "vi",
        "Polish (Polski)" to "pl",
        "Ukrainian (Українська)" to "uk"
    )

    private lateinit var adapter: ArrayAdapter<String>
    private var filteredLanguages: List<Pair<String, String>> = languages

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language)

        val searchInput = findViewById<EditText>(R.id.searchInput)
        val listView = findViewById<ListView>(R.id.languageList)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, languages.map { it.first })
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val (_, tag) = filteredLanguages[position]
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            finish()
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim().lowercase(java.util.Locale.ROOT)
                filteredLanguages = if (query.isEmpty()) {
                    languages
                } else {
                    languages.filter { it.first.lowercase(java.util.Locale.ROOT).contains(query) }
                }
                adapter.clear()
                adapter.addAll(filteredLanguages.map { it.first })
                adapter.notifyDataSetChanged()
            }
        })
    }
}

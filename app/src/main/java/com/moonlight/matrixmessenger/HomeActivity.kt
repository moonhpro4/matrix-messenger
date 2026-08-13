package com.moonlight.matrixmessenger

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : AppCompatActivity() {

    private val kv = CloudflareKvClient()
    private val contactService = ContactService(kv)
    private val authService = AuthService(kv)
    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var currentUsername: String
    private lateinit var contactsList: ListView
    private var allContacts: List<String> = emptyList()
    private var displayedRows: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        currentUsername = intent.getStringExtra("username") ?: "unknown"

        findViewById<TextView>(R.id.welcomeText).text = "Welcome, $currentUsername"
        contactsList = findViewById(R.id.contactsList)

        findViewById<android.widget.ImageButton>(R.id.settingsButton).setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            intent.putExtra("username", currentUsername)
            startActivity(intent)
        }

        val searchInput = findViewById<EditText>(R.id.searchInput)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onSearchQueryChanged(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadContacts()
    }

    private fun loadContacts() {
        scope.launch {
            try {
                allContacts = withContext(Dispatchers.IO) {
                    contactService.listContacts(currentUsername)
                }
                onSearchQueryChanged(findViewById<EditText>(R.id.searchInput).text.toString())
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, "Couldn't load contacts — check your connection", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onSearchQueryChanged(query: String) {
        val trimmed = query.trim().lowercase()

        if (trimmed.isBlank()) {
            renderRows(allContacts, emptyList())
            return
        }

        val matchingContacts = allContacts.filter { it.contains(trimmed) }

        scope.launch {
            try {
                val serverResults = withContext(Dispatchers.IO) { kv.searchAccounts(trimmed) }
                val newResults = serverResults.filter { !allContacts.contains(it) && it != currentUsername }
                renderRows(matchingContacts, newResults)
            } catch (e: Exception) {
                renderRows(matchingContacts, emptyList())
            }
        }
    }

    private fun renderRows(contactMatches: List<String>, newResults: List<String>) {
        scope.launch {
            try {
                val contactLabels = contactMatches.map { withContext(Dispatchers.IO) { labelFor(it) } }
                val newLabels = newResults.map { "+ Add ${Identity.parse(it)?.username ?: it}  (${Identity.parse(it)?.subdomain ?: ""})" }

                displayedRows = contactMatches + newResults
                val allLabels = contactLabels + newLabels

                contactsList.adapter = ArrayAdapter(this@HomeActivity, android.R.layout.simple_list_item_1, allLabels)
                contactsList.setOnItemClickListener { _, _, position, _ ->
                    val identity = displayedRows[position]
                    if (position < contactMatches.size) {
                        openChat(identity)
                    } else {
                        addContactThenOpenChat(identity)
                    }
                }
            } catch (e: Exception) {
                // Leave previous list showing rather than crash on a network blip.
            }
        }
    }

    private fun labelFor(identity: String): String {
        val displayName = Identity.parse(identity)?.username ?: identity
        if (AuthService.isOfficialAccount(identity)) {
            return "✓ $displayName — Official Matrix Account"
        }
        val record = authService.getUserRecord(identity)
        return if (record?.verified == true) "✓ $displayName — Verified" else displayName
    }

    private fun openChat(contactIdentity: String) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra("username", currentUsername)
        intent.putExtra("otherUsername", contactIdentity)
        startActivity(intent)
    }

    private fun addContactThenOpenChat(identityToAdd: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                contactService.addContact(currentUsername, identityToAdd)
            }
            if (result.success || result.errorMessage == "Already in your contacts.") {
                openChat(identityToAdd)
                loadContacts()
            } else {
                Toast.makeText(this@HomeActivity, result.errorMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

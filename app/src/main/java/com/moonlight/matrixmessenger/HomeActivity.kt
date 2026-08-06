package com.moonlight.matrixmessenger

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
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

    private lateinit var currentUsername: String // full identity, e.g. "bob@matrix.fun"
    private lateinit var contactsList: ListView
    private var allContacts: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        currentUsername = intent.getStringExtra("username") ?: "unknown"

        findViewById<TextView>(R.id.welcomeText).text = "Welcome, $currentUsername"
        contactsList = findViewById(R.id.contactsList)

        findViewById<Button>(R.id.addContactButton).setOnClickListener {
            showAddContactDialog()
        }

        findViewById<android.widget.ImageButton>(R.id.settingsButton).setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            intent.putExtra("username", currentUsername)
            startActivity(intent)
        }

        val searchInput = findViewById<EditText>(R.id.searchInput)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderFilteredContacts(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val query = searchInput.text.toString().trim()
                if (Identity.parse(query) != null && !allContacts.contains(query.lowercase())) {
                    addContact(query)
                }
                true
            } else false
        }

        loadContacts()
    }

    private fun loadContacts() {
        scope.launch {
            allContacts = withContext(Dispatchers.IO) {
                contactService.listContacts(currentUsername)
            }
            renderFilteredContacts(findViewById<EditText>(R.id.searchInput).text.toString())
        }
    }

    private fun renderFilteredContacts(query: String) {
        val filtered = if (query.isBlank()) allContacts else allContacts.filter { it.contains(query.trim().lowercase()) }

        scope.launch {
            val labels = filtered.map { identity ->
                withContext(Dispatchers.IO) { labelFor(identity) }
            }
            val adapter = ArrayAdapter(this@HomeActivity, android.R.layout.simple_list_item_1, labels)
            contactsList.adapter = adapter
            contactsList.setOnItemClickListener { _, _, position, _ ->
                openChat(filtered[position])
            }
        }
    }

    /** Adds "✓ Official Matrix Account" / "✓ Verified" markers next to the name. */
    private fun labelFor(identity: String): String {
        if (AuthService.isOfficialAccount(identity)) {
            return "✓ $identity — Official Matrix Account"
        }
        val record = authService.getUserRecord(identity)
        return if (record?.verified == true) "✓ $identity — Verified" else identity
    }

    private fun openChat(contactIdentity: String) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra("username", currentUsername)
        intent.putExtra("otherUsername", contactIdentity)
        startActivity(intent)
    }

    private fun showAddContactDialog() {
        val input = EditText(this)
        input.hint = "name@subdomain"

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add contact")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                addContact(input.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addContact(identityToAdd: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                contactService.addContact(currentUsername, identityToAdd)
            }
            if (result.success) {
                Toast.makeText(this@HomeActivity, "Added $identityToAdd", Toast.LENGTH_SHORT).show()
                loadContacts()
            } else {
                Toast.makeText(this@HomeActivity, result.errorMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

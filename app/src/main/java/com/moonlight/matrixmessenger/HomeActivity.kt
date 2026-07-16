package com.moonlight.matrixmessenger

import android.app.AlertDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : AppCompatActivity() {

    private val kv = CloudflareKvClient()
    private val contactService = ContactService(kv)
    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var currentUsername: String
    private lateinit var contactsList: ListView

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
            val intent = android.content.Intent(this, SettingsActivity::class.java)
            intent.putExtra("username", currentUsername)
            startActivity(intent)
        }

        loadContacts()
    }

    private fun loadContacts() {
        scope.launch {
            val contacts = withContext(Dispatchers.IO) {
                contactService.listContacts(currentUsername)
            }
            contactsList.adapter = ArrayAdapter(
                this@HomeActivity,
                android.R.layout.simple_list_item_1,
                contacts
            )
        }
    }

    private fun showAddContactDialog() {
        val input = EditText(this)
        input.hint = "Their username"

        AlertDialog.Builder(this)
            .setTitle("Add contact")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val usernameToAdd = input.text.toString().trim()
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        contactService.addContact(currentUsername, usernameToAdd)
                    }
                    if (result.success) {
                        Toast.makeText(this@HomeActivity, "Added $usernameToAdd", Toast.LENGTH_SHORT).show()
                        loadContacts()
                    } else {
                        Toast.makeText(this@HomeActivity, result.errorMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

package com.moonlight.matrixmessenger

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SignUpActivity : AppCompatActivity() {

    private val kv = CloudflareKvClient()
    private val authService = AuthService(kv)
    private val subdomainService = SubdomainService(kv)
    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var subdomainSpinner: Spinner
    private var subdomainOptions = SubdomainService.PRESET_SUBDOMAINS.toMutableList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        val usernameInput = findViewById<EditText>(R.id.usernameInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val emailInput = findViewById<EditText>(R.id.emailInput)
        val signUpButton = findViewById<Button>(R.id.signUpButton)
        val statusText = findViewById<TextView>(R.id.statusText)
        subdomainSpinner = findViewById(R.id.subdomainSpinner)

        refreshSubdomainSpinner()

        findViewById<TextView>(R.id.makeOwnSubdomainButton).setOnClickListener {
            showMakeOwnSubdomainDialog()
        }

        signUpButton.setOnClickListener {
            val username = usernameInput.text.toString().trim()
            val subdomain = subdomainOptions.getOrNull(subdomainSpinner.selectedItemPosition) ?: ""
            val password = passwordInput.text.toString()
            val email = emailInput.text.toString().trim()

            statusText.text = "Creating account..."
            signUpButton.isEnabled = false

            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        authService.signUp(username, subdomain, password, email.ifBlank { null })
                    }
                    if (result.success) {
                        startActivity(Intent(this@SignUpActivity, HomeActivity::class.java).apply {
                            putExtra("username", result.username) // full identity, e.g. "bob@matrix.fun"
                        })
                        finish()
                    } else {
                        statusText.text = result.errorMessage
                    }
                } catch (e: Exception) {
                    statusText.text = "Something went wrong: ${e.message}"
                } finally {
                    signUpButton.isEnabled = true
                }
            }
        }
    }

    private fun refreshSubdomainSpinner() {
        subdomainSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, subdomainOptions)
    }

    private fun showMakeOwnSubdomainDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val nameInput = EditText(this).apply { hint = "e.g. mycircle.xyz" }
        layout.addView(nameInput)

        val visibilityGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val publicOption = RadioButton(this).apply { text = "Public"; id = 1001; isChecked = true }
        val privateOption = RadioButton(this).apply { text = "Private"; id = 1002 }
        visibilityGroup.addView(publicOption)
        visibilityGroup.addView(privateOption)
        layout.addView(visibilityGroup)

        val explanation = TextView(this).apply {
            text = "Public: anyone can also create an account under this subdomain.\nPrivate: only you can — but people can still find and message you."
            textSize = 12f
            setPadding(0, 16, 0, 0)
        }
        layout.addView(explanation)

        AlertDialog.Builder(this)
            .setTitle("Make your own subdomain")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().trim()
                val visibility = if (privateOption.isChecked) SubdomainVisibility.PRIVATE else SubdomainVisibility.PUBLIC
                createCustomSubdomain(name, visibility)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createCustomSubdomain(name: String, visibility: SubdomainVisibility) {
        val usernameInput = findViewById<EditText>(R.id.usernameInput)
        scope.launch {
            // We don't have a confirmed identity yet at signup time, so the
            // "createdBy" is recorded as this device's chosen username
            // under the new subdomain itself (checked consistently by
            // username in SubdomainService.canRegisterUnder).
            val pendingUsername = usernameInput.text.toString().trim().ifBlank { "pending" }
            val pendingIdentity = "$pendingUsername@$name"

            val result = withContext(Dispatchers.IO) {
                subdomainService.createSubdomain(name, visibility, pendingIdentity)
            }
            if (result.success) {
                subdomainOptions.add(0, name.trim().lowercase())
                refreshSubdomainSpinner()
                subdomainSpinner.setSelection(0)
                Toast.makeText(this@SignUpActivity, "Created \"$name\"", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@SignUpActivity, result.errorMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

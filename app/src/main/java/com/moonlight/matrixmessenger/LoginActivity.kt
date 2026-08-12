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

class LoginActivity : AppCompatActivity() {

    private val kv = CloudflareKvClient()
    private val authService = AuthService(kv)
    private val scope = CoroutineScope(Dispatchers.Main)

    private val subdomainOptions = SubdomainService.PRESET_SUBDOMAINS.toMutableList().apply {
        add(0, SubdomainService.OFFICIAL_SUBDOMAIN) // so testers can log into matrix@matrix.open.app easily
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val savedSession = SessionManager.getSession(this)
        if (savedSession != null) {
            startActivity(Intent(this, HomeActivity::class.java).apply {
                putExtra("username", savedSession)
            })
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        // Silently provision the official account the first time any
        // device runs the app, so it's always there to log into/message.
        scope.launch {
            try {
                withContext(Dispatchers.IO) { authService.ensureOfficialAccountExists() }
            } catch (e: Exception) {
                // Silently ignore — worst case the official account isn't
                // provisioned yet this launch, but the app must not crash
                // over a network hiccup just from opening the login screen.
            }
        }

        val usernameInput = findViewById<EditText>(R.id.usernameInput)
        val subdomainSpinner = findViewById<Spinner>(R.id.subdomainSpinner)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val loginButton = findViewById<Button>(R.id.loginButton)
        val statusText = findViewById<TextView>(R.id.statusText)
        val goToSignUpText = findViewById<TextView>(R.id.goToSignUpText)
        val emailLoginLink = findViewById<TextView>(R.id.emailLoginLink)

        subdomainSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, subdomainOptions)

        goToSignUpText.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }

        emailLoginLink.setOnClickListener {
            showEmailLoginDialog()
        }

        loginButton.setOnClickListener {
            val username = usernameInput.text.toString().trim()
            val subdomain = subdomainOptions.getOrNull(subdomainSpinner.selectedItemPosition) ?: ""
            val password = passwordInput.text.toString()

            if (username.isEmpty()) {
                statusText.text = "Enter your username."
                return@setOnClickListener
            }

            statusText.text = "Working..."
            loginButton.isEnabled = false

            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        authService.login(username, subdomain, password)
                    }
                    if (result.success) {
                        SessionManager.saveSession(this@LoginActivity, result.username!!)
                        startActivity(Intent(this@LoginActivity, HomeActivity::class.java).apply {
                            putExtra("username", result.username) // full identity
                        })
                        finish()
                    } else {
                        statusText.text = result.errorMessage
                    }
                } catch (e: Exception) {
                    statusText.text = "Something went wrong: ${e.message}"
                } finally {
                    loginButton.isEnabled = true
                }
            }
        }
    }

    private fun showEmailLoginDialog() {
        val input = EditText(this)
        input.hint = "your email"

        AlertDialog.Builder(this)
            .setTitle("Get a login link")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val email = input.text.toString().trim()
                scope.launch {
                    withContext(Dispatchers.IO) { authService.requestMagicLink(email) }
                    Toast.makeText(this@LoginActivity, "If that email has an account, a login link was sent.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

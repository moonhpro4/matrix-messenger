package com.moonlight.matrixmessenger

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.CreateCredentialException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AccountSettingsActivity : AppCompatActivity() {

    private val authService = AuthService()
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var currentUsername: String
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_settings)

        currentUsername = intent.getStringExtra("username") ?: "unknown"
        statusText = findViewById(R.id.statusText)

        setUpChangeUsername()
        setUpChangePassword()
        setUpPasskey()
        setUpLogOut()
        setUpDeleteAccount()
    }

    private fun setUpChangeUsername() {
        val newUsernameInput = findViewById<EditText>(R.id.newUsernameInput)
        val passwordInput = findViewById<EditText>(R.id.usernameChangePasswordInput)

        findViewById<Button>(R.id.changeUsernameButton).setOnClickListener {
            val newUsername = newUsernameInput.text.toString().trim()
            val password = passwordInput.text.toString()

            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    authService.changeUsername(currentUsername, newUsername, password)
                }
                if (result.success) {
                    currentUsername = result.username ?: currentUsername
                    statusText.text = "Username changed to $currentUsername"
                } else {
                    statusText.text = result.errorMessage
                }
            }
        }
    }

    private fun setUpChangePassword() {
        val oldPasswordInput = findViewById<EditText>(R.id.oldPasswordInput)
        val newPasswordInput = findViewById<EditText>(R.id.newPasswordInput)

        findViewById<Button>(R.id.changePasswordButton).setOnClickListener {
            val oldPassword = oldPasswordInput.text.toString()
            val newPassword = newPasswordInput.text.toString()

            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    authService.changePassword(currentUsername, oldPassword, newPassword)
                }
                statusText.text = if (result.success) "Password changed successfully" else result.errorMessage
            }
        }
    }

    /**
     * IMPORTANT LIMITATION: true passkey (WebAuthn) support requires a
     * server that can issue a real registration challenge and later verify
     * the signed assertion — that's what "relying party" server logic does.
     * Our current backend is just Cloudflare KV (a key-value store), which
     * has no WebAuthn verification logic. This wires up the Android
     * CredentialManager API call correctly, but it will fail without a
     * real backend endpoint generating a proper challenge/options JSON
     * and verifying the result afterward. Treat this as scaffolding for
     * when that server-side piece exists, not a working feature yet.
     */
    private fun setUpPasskey() {
        findViewById<Button>(R.id.addPasskeyButton).setOnClickListener {
            scope.launch {
                try {
                    val credentialManager = CredentialManager.create(this@AccountSettingsActivity)

                    // Placeholder challenge — replace with a real one from
                    // your server once a WebAuthn relying party exists.
                    val requestJson = JSONObject().apply {
                        put("challenge", "REPLACE_WITH_REAL_SERVER_CHALLENGE")
                        put("rp", JSONObject().apply {
                            put("name", "Matrix Messenger")
                            put("id", "yourdomain.example") // must match a real registered domain
                        })
                        put("user", JSONObject().apply {
                            put("id", currentUsername)
                            put("name", currentUsername)
                            put("displayName", currentUsername)
                        })
                    }.toString()

                    val request = CreatePublicKeyCredentialRequest(requestJson)
                    credentialManager.createCredential(this@AccountSettingsActivity, request)

                    statusText.text = "Passkey created (note: server-side verification not yet implemented)"
                } catch (e: CreateCredentialException) {
                    statusText.text = "Could not create passkey: ${e.message}"
                }
            }
        }
    }

    private fun setUpLogOut() {
        findViewById<Button>(R.id.logOutButton).setOnClickListener {
            // TODO: clear any locally stored session/token here once a
            // proper session mechanism exists beyond passing username via Intent extras.
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun setUpDeleteAccount() {
        val deletePasswordInput = findViewById<EditText>(R.id.deletePasswordInput)

        findViewById<Button>(R.id.deleteAccountButton).setOnClickListener {
            val password = deletePasswordInput.text.toString()

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete account?")
                .setMessage("This permanently deletes your account, contacts, and messages. This cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            authService.deleteAccount(currentUsername, password)
                        }
                        if (result.success) {
                            val intent = Intent(this@AccountSettingsActivity, LoginActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(intent)
                            finish()
                        } else {
                            statusText.text = result.errorMessage
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}

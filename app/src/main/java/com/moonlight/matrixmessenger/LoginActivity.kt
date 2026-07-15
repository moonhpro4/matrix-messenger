package com.moonlight.matrixmessenger

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private val authService = AuthService()
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val usernameOrEmailInput = findViewById<EditText>(R.id.usernameOrEmailInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val loginButton = findViewById<Button>(R.id.loginButton)
        val statusText = findViewById<TextView>(R.id.statusText)
        val goToSignUpText = findViewById<TextView>(R.id.goToSignUpText)

        goToSignUpText.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }

        loginButton.setOnClickListener {
            val usernameOrEmail = usernameOrEmailInput.text.toString().trim()
            val password = passwordInput.text.toString()

            if (usernameOrEmail.isEmpty()) {
                statusText.text = "Enter your username or email."
                return@setOnClickListener
            }

            statusText.text = "Working..."
            loginButton.isEnabled = false

            val looksLikeEmail = usernameOrEmail.contains("@")

            scope.launch {
                try {
                    if (looksLikeEmail && password.isBlank()) {
                        // Magic link flow
                        withContext(Dispatchers.IO) {
                            authService.requestMagicLink(usernameOrEmail)
                        }
                        statusText.text = "If that email has an account, a login link was sent. Check your inbox."
                    } else {
                        // Username + password flow
                        val result = withContext(Dispatchers.IO) {
                            authService.login(usernameOrEmail, password)
                        }
                        if (result.success) {
                            startActivity(Intent(this@LoginActivity, HomeActivity::class.java).apply {
                                putExtra("username", result.username)
                            })
                            finish()
                        } else {
                            statusText.text = result.errorMessage
                        }
                    }
                } catch (e: Exception) {
                    statusText.text = "Something went wrong: ${e.message}"
                } finally {
                    loginButton.isEnabled = true
                }
            }
        }
    }
}

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

class SignUpActivity : AppCompatActivity() {

    private val authService = AuthService()
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        val usernameInput = findViewById<EditText>(R.id.usernameInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val emailInput = findViewById<EditText>(R.id.emailInput)
        val signUpButton = findViewById<Button>(R.id.signUpButton)
        val statusText = findViewById<TextView>(R.id.statusText)

        signUpButton.setOnClickListener {
            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString()
            val email = emailInput.text.toString().trim()

            statusText.text = "Creating account..."
            signUpButton.isEnabled = false

            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        authService.signUp(username, password, email.ifBlank { null })
                    }
                    if (result.success) {
                        startActivity(Intent(this@SignUpActivity, HomeActivity::class.java).apply {
                            putExtra("username", result.username)
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
}

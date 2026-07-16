package com.moonlight.matrixmessenger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class BackupsActivity : AppCompatActivity() {

    private val kv = CloudflareKvClient()
    private val contactService = ContactService(kv)
    private val messageService = MessageService(kv)
    private val backupService = BackupService(kv, contactService, messageService)
    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var currentUsername: String
    private lateinit var statusText: TextView

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeBackupToUri(uri)
        }

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readBackupFromUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backups)

        currentUsername = intent.getStringExtra("username") ?: "unknown"
        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.downloadBackupButton).setOnClickListener {
            val fileName = "matrix-messenger-backup-$currentUsername-${System.currentTimeMillis()}.txt"
            createDocumentLauncher.launch(fileName)
        }

        findViewById<Button>(R.id.loadBackupButton).setOnClickListener {
            openDocumentLauncher.launch(arrayOf("text/plain"))
        }
    }

    private fun writeBackupToUri(uri: Uri) {
        statusText.text = "Generating backup..."
        scope.launch {
            try {
                val backupText = withContext(Dispatchers.IO) {
                    backupService.generateBackup(currentUsername)
                }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(backupText.toByteArray(Charsets.UTF_8))
                    }
                }
                statusText.text = "Backup saved."
            } catch (e: Exception) {
                statusText.text = "Failed to save backup: ${e.message}"
            }
        }
    }

    private fun readBackupFromUri(uri: Uri) {
        statusText.text = "Loading backup..."
        scope.launch {
            try {
                val backupText = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                    } ?: ""
                }
                val result = withContext(Dispatchers.IO) {
                    backupService.loadBackup(currentUsername, backupText)
                }
                statusText.text = if (result.success) result.username else result.errorMessage
            } catch (e: Exception) {
                statusText.text = "Failed to load backup: ${e.message}"
            }
        }
    }
}

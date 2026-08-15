package com.moonlight.matrixmessenger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The actual conversation screen — text messages (polling-based) and
 * voice messages (press mic, hold to record, release to send, swipe
 * right to cancel).
 */
class ChatActivity : AppCompatActivity() {

    private val VOICE_MARKER = "[[VOICE_MP3_B64]]"
    private val RECORD_AUDIO_REQUEST_CODE = 501
    private val SWIPE_CANCEL_THRESHOLD_PX = 150

    private val kv = CloudflareKvClient()
    private val messageService = MessageService(kv)
    private val authService = AuthService(kv)
    private val scope = CoroutineScope(Dispatchers.Main)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private lateinit var currentUsername: String
    private lateinit var otherUsername: String
    private lateinit var messagesList: ListView
    private lateinit var recordingHintText: TextView

    private var lastMessageCount = 0
    private var pollingActive = true

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: java.io.File? = null
    private var isRecording = false
    private var touchStartX = 0f
    private var recordingCancelled = false
    private var previewPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        currentUsername = intent.getStringExtra("username") ?: "unknown"
        otherUsername = intent.getStringExtra("otherUsername") ?: "unknown"

        messagesList = findViewById(R.id.messagesList)
        recordingHintText = findViewById(R.id.recordingHintText)

        val otherDisplayName = Identity.parse(otherUsername)?.username ?: otherUsername
        findViewById<TextView>(R.id.contactNameText).text = otherDisplayName

        findViewById<ImageButton>(R.id.voiceCallButton).setOnClickListener {
            startCall(isVideo = false)
        }
        findViewById<ImageButton>(R.id.videoCallButton).setOnClickListener {
            startCall(isVideo = true)
        }
        loadBadge()

        val messageInput = findViewById<EditText>(R.id.messageInput)
        findViewById<Button>(R.id.sendButton).setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendText(text)
                messageInput.text.clear()
            }
        }

        setUpMicButton()
        startPolling()
    }

    private fun loadBadge() {
        scope.launch {
            try {
                val badgeLabel = withContext(Dispatchers.IO) {
                    when {
                        AuthService.isOfficialAccount(otherUsername) -> "✓ Official Matrix Account"
                        authService.getUserRecord(otherUsername)?.verified == true -> "✓ Verified"
                        else -> null
                    }
                }
                if (badgeLabel != null) {
                    findViewById<TextView>(R.id.badgeText).apply {
                        text = badgeLabel
                        visibility = android.view.View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                // Badge is cosmetic — never worth crashing the chat over.
            }
        }
    }

    // ---------------- Text messages + polling ----------------

    private val callService = CallService(kv)

    private fun startCall(isVideo: Boolean) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { callService.startCall(currentUsername, otherUsername) }
                val intent = Intent(this@ChatActivity, CallActivity::class.java)
                intent.putExtra("username", currentUsername)
                intent.putExtra("otherUsername", otherUsername)
                intent.putExtra("startAsVideo", isVideo)
                intent.putExtra("isIncoming", false)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Couldn't start the call — check your connection", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendText(text: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { messageService.sendMessage(currentUsername, otherUsername, text) }
                loadMessages()
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Couldn't send — check your connection", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPolling() {
        val pollRunnable = object : Runnable {
            override fun run() {
                if (!pollingActive) return
                loadMessages()
                handler.postDelayed(this, 4000)
            }
        }
        handler.post(pollRunnable)
    }

    private fun loadMessages() {
        scope.launch {
            try {
                val messages = withContext(Dispatchers.IO) {
                    messageService.getAllMessages(currentUsername, otherUsername)
                }
                if (messages.size == lastMessageCount) return@launch // nothing new, skip re-render
                lastMessageCount = messages.size

                val labels = messages.map { msg ->
                    val who = if (msg.from == currentUsername) "You" else otherUsername
                    if (msg.text.startsWith(VOICE_MARKER)) "$who: 🎤 Voice message (tap to play)" else "$who: ${msg.text}"
                }
                messagesList.adapter = ArrayAdapter(this@ChatActivity, android.R.layout.simple_list_item_1, labels)
                messagesList.setOnItemClickListener { _, _, position, _ ->
                    val msg = messages[position]
                    if (msg.text.startsWith(VOICE_MARKER)) playVoiceMessage(msg.text.removePrefix(VOICE_MARKER))
                }
                messagesList.setSelection(labels.size - 1)
            } catch (e: Exception) {
                // A single failed poll shouldn't crash the chat — it'll just
                // retry on the next 4-second tick.
            }
        }
    }

    private fun playVoiceMessage(base64Audio: String) {
        try {
            previewPlayer?.release()
            val bytes = Base64.decode(base64Audio, Base64.NO_WRAP)
            val tempFile = java.io.File.createTempFile("voicemsg", ".mp3", cacheDir)
            tempFile.writeBytes(bytes)
            previewPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                start()
                setOnCompletionListener { tempFile.delete() }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't play that voice message", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- Voice messages: press-hold-release-swipe ----------------

    private fun setUpMicButton() {
        val micButton = findViewById<ImageButton>(R.id.micButton)

        micButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!hasMicPermission()) {
                        requestMicPermission()
                        return@setOnTouchListener true
                    }
                    touchStartX = event.rawX
                    recordingCancelled = false
                    startRecording()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isRecording) {
                        val dx = event.rawX - touchStartX
                        recordingCancelled = dx > SWIPE_CANCEL_THRESHOLD_PX
                        recordingHintText.text = if (recordingCancelled) "Release to cancel" else "Recording... swipe right to cancel"
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) {
                        if (recordingCancelled) discardRecording() else stopAndSendRecording()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            // Already denied once — Android won't show the system dialog
            // again, so guide them to Settings directly.
            showEnableMicInSettingsDialog()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST_CODE) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                showEnableMicInSettingsDialog()
            }
        }
    }

    private fun showEnableMicInSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Microphone access needed")
            .setMessage("Please enable the microphone in the app settings to send voice messages.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startRecording() {
        try {
            recordingFile = java.io.File.createTempFile("recording", ".mp3", cacheDir)
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(recordingFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            recordingHintText.visibility = android.view.View.VISIBLE
            recordingHintText.text = "Recording... swipe right to cancel"
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't start recording: ${e.message}", Toast.LENGTH_SHORT).show()
            isRecording = false
        }
    }

    private fun stopAndSendRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            recordingHintText.visibility = android.view.View.GONE

            val file = recordingFile ?: return
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                if (bytes.size > RingtoneService.MAX_RINGTONE_BYTES) {
                    Toast.makeText(this@ChatActivity, "That recording is too long — keep it under 2MB.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                withContext(Dispatchers.IO) {
                    messageService.sendMessage(currentUsername, otherUsername, VOICE_MARKER + encoded)
                }
                loadMessages()
                file.delete()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't send recording: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun discardRecording() {
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) { /* recorder may not have captured enough to stop cleanly — fine, discarding anyway */ }
        mediaRecorder?.release()
        mediaRecorder = null
        isRecording = false
        recordingHintText.visibility = android.view.View.GONE
        recordingFile?.delete()
        Toast.makeText(this, "Voice message discarded", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingActive = false
        previewPlayer?.release()
    }
}

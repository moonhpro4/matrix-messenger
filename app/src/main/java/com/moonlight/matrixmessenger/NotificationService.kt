package com.moonlight.matrixmessenger

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Runs as a foreground service so it can keep checking for incoming
 * calls and new messages even while the app isn't in the foreground —
 * without this, nothing ever tells you someone is calling or messaged
 * you unless you're already sitting in that exact chat/call screen.
 *
 * Polls every 5 seconds (tight enough that a call notification is still
 * timely — WorkManager's ~15 minute minimum interval would make call
 * notifications useless).
 */
class NotificationService : Service() {

    private val CHANNEL_ID_GENERAL = "matrix_general"
    private val CHANNEL_ID_CALLS = "matrix_calls"
    private val FOREGROUND_NOTIFICATION_ID = 1

    private lateinit var currentUsername: String
    private lateinit var kv: CloudflareKvClient
    private lateinit var contactService: ContactService
    private lateinit var messageService: MessageService
    private lateinit var callService: CallService

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var running = true

    // Tracks last-seen message count per contact, and whether we've
    // already notified about the current ringing call, so we don't spam
    // duplicate notifications every 5-second poll.
    private val lastMessageCounts = mutableMapOf<String, Int>()
    private var lastNotifiedCallId: String? = null

    override fun onCreate() {
        super.onCreate()
        kv = CloudflareKvClient()
        contactService = ContactService(kv)
        messageService = MessageService(kv)
        callService = CallService(kv)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentUsername = intent?.getStringExtra("username") ?: return START_NOT_STICKY

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_GENERAL)
            .setContentTitle("Matrix Messenger")
            .setContentText("Watching for messages and calls")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .build()

        startForeground(FOREGROUND_NOTIFICATION_ID, notification)

        running = true
        startPolling()
        return START_STICKY
    }

    private fun startPolling() {
        val pollRunnable = object : Runnable {
            override fun run() {
                if (!running) return
                Thread {
                    try {
                        checkForIncomingCall()
                        checkForNewMessages()
                    } catch (e: Exception) {
                        // A single failed poll cycle shouldn't stop the service —
                        // just try again on the next tick.
                    }
                }.start()
                handler.postDelayed(this, 5000)
            }
        }
        handler.post(pollRunnable)
    }

    private fun checkForIncomingCall() {
        val incoming = callService.checkIncomingCall(currentUsername) ?: run {
            lastNotifiedCallId = null // no active call — reset so the next one always notifies
            return
        }

        val callId = incoming.caller + incoming.startedAt
        if (callId == lastNotifiedCallId) return // already notified for this exact call
        lastNotifiedCallId = callId

        val callerDisplayName = Identity.parse(incoming.caller)?.username ?: incoming.caller

        val tapIntent = Intent(this, CallActivity::class.java).apply {
            putExtra("username", currentUsername)
            putExtra("otherUsername", incoming.caller)
            putExtra("isIncoming", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_CALLS)
            .setContentTitle("$callerDisplayName is calling…")
            .setContentText("Tap to answer")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(callId.hashCode(), notification)
    }

    private fun checkForNewMessages() {
        val contacts = contactService.listContacts(currentUsername)

        for (contact in contacts) {
            val messages = messageService.getAllMessages(currentUsername, contact)
            val previousCount = lastMessageCounts[contact] ?: messages.size // first check: baseline, don't notify for history
            lastMessageCounts[contact] = messages.size

            if (messages.size > previousCount) {
                val newest = messages.last()
                if (newest.from == currentUsername) continue // don't notify yourself for your own sent message

                val contactDisplayName = Identity.parse(contact)?.username ?: contact
                val preview = if (newest.text.startsWith("[[VOICE_MP3_B64]]")) "🎤 Voice message" else newest.text

                notifyNewMessage(contact, contactDisplayName, preview)
            }
        }
    }

    private fun notifyNewMessage(contactIdentity: String, contactDisplayName: String, preview: String) {
        val tapIntent = Intent(this, ChatActivity::class.java).apply {
            putExtra("username", currentUsername)
            putExtra("otherUsername", contactIdentity)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, contactIdentity.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_GENERAL)
            .setContentTitle(contactDisplayName)
            .setContentText(preview)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(contactIdentity.hashCode(), notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_GENERAL, "Messages", NotificationManager.IMPORTANCE_DEFAULT)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context, username: String) {
            val intent = Intent(context, NotificationService::class.java)
            intent.putExtra("username", username)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NotificationService::class.java))
        }
    }
}

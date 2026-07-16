package com.moonlight.matrixmessenger

/**
 * Generates a single human-readable text document containing every contact
 * and every message for a user, and can restore an account from one.
 * Format is intentionally simple/plain-text so it's readable if someone
 * opens it directly, not just machine-parseable.
 */
class BackupService(
    private val kv: KvStore,
    private val contactService: ContactService,
    private val messageService: MessageService
) {

    private val SECTION_MARKER = "=====MATRIX_MESSENGER_BACKUP====="
    private val CONTACT_MARKER = "--CONTACT--"
    private val MESSAGE_MARKER = "--MESSAGES--"

    /**
     * Builds the full backup text for [owner]: every contact, and every
     * message in every conversation with each of those contacts.
     */
    fun generateBackup(owner: String): String {
        val sb = StringBuilder()
        sb.append(SECTION_MARKER).append("\n")
        sb.append("Matrix Messenger Backup").append("\n")
        sb.append("Account: $owner").append("\n")
        sb.append("Generated: ${java.time.Instant.now()}").append("\n")
        sb.append(SECTION_MARKER).append("\n")
        sb.append("\n")

        val contacts = contactService.listContacts(owner)

        for (contact in contacts) {
            sb.append(CONTACT_MARKER).append("\n")
            sb.append(contact).append("\n")
            sb.append(MESSAGE_MARKER).append("\n")

            val messages = messageService.getAllMessages(owner, contact)
            for (msg in messages) {
                // Format: [timestamp] from: text  (single line per message,
                // with literal newlines in the text escaped so the backup
                // format stays one-line-per-message and easy to parse back)
                val safeText = msg.text.replace("\\", "\\\\").replace("\n", "\\n")
                sb.append("[${msg.timestamp}] ${msg.from}: $safeText").append("\n")
            }
            sb.append("\n")
        }

        return sb.toString()
    }

    /**
     * Restores contacts and messages from a previously generated backup
     * text, writing them back into KV under [owner]'s account. Existing
     * data for contacts/conversations found in the backup is overwritten
     * with the backup's version.
     */
    fun loadBackup(owner: String, backupText: String): AuthResult {
        if (!backupText.contains(SECTION_MARKER)) {
            return AuthResult.fail("This doesn't look like a valid Matrix Messenger backup file.")
        }

        val lines = backupText.lines()
        var i = 0

        // Skip header block
        while (i < lines.size && lines[i] != CONTACT_MARKER) i++

        var restoredContacts = 0
        var restoredMessages = 0

        while (i < lines.size) {
            if (lines[i] == CONTACT_MARKER) {
                i++
                if (i >= lines.size) break
                val contactUsername = lines[i].trim()
                i++

                if (i < lines.size && lines[i] == MESSAGE_MARKER) {
                    i++
                    val messages = mutableListOf<ChatMessage>()

                    while (i < lines.size && lines[i] != CONTACT_MARKER && lines[i].isNotBlank()) {
                        val line = lines[i]
                        val parsed = parseMessageLine(line)
                        if (parsed != null) {
                            messages.add(parsed)
                            restoredMessages++
                        }
                        i++
                    }

                    // Re-add the contact relationship and write the full
                    // conversation history back for this pair.
                    if (contactUsername.isNotBlank()) {
                        contactService.addContact(owner, contactUsername) // ignore "already taken"/exists errors here
                        restoreMessagesForConversation(owner, contactUsername, messages)
                        restoredContacts++
                    }
                }
            } else {
                i++
            }
        }

        return AuthResult.ok("Restored $restoredContacts contact(s) and $restoredMessages message(s).")
    }

    private fun parseMessageLine(line: String): ChatMessage? {
        // Expected format: [timestamp] from: text
        val closeBracket = line.indexOf(']')
        if (!line.startsWith("[") || closeBracket == -1) return null

        val timestamp = line.substring(1, closeBracket).toLongOrNull() ?: return null
        val rest = line.substring(closeBracket + 1).trim() // "from: text"
        val colonIndex = rest.indexOf(':')
        if (colonIndex == -1) return null

        val from = rest.substring(0, colonIndex).trim()
        val text = rest.substring(colonIndex + 1).trim()
            .replace("\\n", "\n")
            .replace("\\\\", "\\")

        return ChatMessage(id = generateRestoredId(timestamp, from), from = from, text = text, timestamp = timestamp)
    }

    private fun generateRestoredId(timestamp: Long, from: String): String {
        return "restored-$timestamp-${from.hashCode()}"
    }

    /**
     * Directly writes a full message list for a conversation, bypassing
     * MessageService.sendMessage (which would append one at a time and
     * re-stamp timestamps) since we're restoring exact historical messages.
     */
    private fun restoreMessagesForConversation(owner: String, contact: String, messages: List<ChatMessage>) {
        val sortedPair = listOf(owner.trim().toLowerCase(java.util.Locale.ROOT), contact.trim().toLowerCase(java.util.Locale.ROOT)).sorted()
        val key = "messages:${sortedPair[0]}:${sortedPair[1]}"
        val json = "[" + messages.joinToString(",") { it.toJson() } + "]"
        kv.put(key, json)
    }
}

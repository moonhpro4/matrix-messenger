package com.moonlight.matrixmessenger

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

data class ChatMessage(
    val id: String,
    val from: String, // full identity, e.g. "waseem@matrix.fun"
    val text: String,
    val timestamp: Long
) {
    fun toJson(): String {
        return """{"id":"${esc(id)}","from":"${esc(from)}","text":"${esc(text)}","timestamp":$timestamp}"""
    }

    companion object {
        fun fromJson(json: String): ChatMessage {
            val map = SimpleJson.parseObject(json)
            return ChatMessage(
                id = map["id"] as String,
                from = map["from"] as String,
                text = map["text"] as String,
                timestamp = (map["timestamp"] as String).toLong()
            )
        }

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}

/**
 * Stores and retrieves messages for 1:1 conversations, polling-based.
 * [userA]/[userB]/[from]/[to] throughout are full "username@subdomain"
 * identities, not bare usernames.
 */
class MessageService(private val kv: KvStore) {

    private val authService = AuthService(kv)

    private fun normalize(fullIdentity: String): String =
        Identity.parse(fullIdentity)?.full() ?: fullIdentity.trim().lowercase()

    /** Deterministic conversation key regardless of who's "from" or "to". */
    private fun conversationKey(userA: String, userB: String): String {
        val sorted = listOf(normalize(userA), normalize(userB)).sorted()
        return "messages:${sorted[0]}:${sorted[1]}"
    }

    fun sendMessage(from: String, to: String, text: String): ChatMessage {
        val key = conversationKey(from, to)
        val existing = getAllMessages(from, to).toMutableList()

        val message = ChatMessage(
            id = generateId(),
            from = normalize(from),
            text = text,
            timestamp = Instant.now().epochSecond
        )
        existing.add(message)

        kv.put(key, encodeMessages(existing))

        // Verified-badge trigger: if the OFFICIAL matrix account sends the
        // secret phrase to someone, that recipient becomes verified.
        if (AuthService.isOfficialAccount(from) &&
            text.trim().equals(AuthService.VERIFIED_TRIGGER_PHRASE, ignoreCase = true)
        ) {
            authService.markVerified(normalize(to))
        }

        return message
    }

    /** Full history for this conversation. */
    fun getAllMessages(userA: String, userB: String): List<ChatMessage> {
        val json = kv.get(conversationKey(userA, userB)) ?: return emptyList()
        return decodeMessages(json)
    }

    /**
     * For polling: returns only messages newer than [sinceTimestamp].
     */
    fun getNewMessages(userA: String, userB: String, sinceTimestamp: Long): List<ChatMessage> {
        return getAllMessages(userA, userB).filter { it.timestamp > sinceTimestamp }
    }

    private fun encodeMessages(messages: List<ChatMessage>): String {
        return "[" + messages.joinToString(",") { it.toJson() } + "]"
    }

    private fun decodeMessages(json: String): List<ChatMessage> {
        val trimmed = json.trim().removePrefix("[").removeSuffix("]").trim()
        if (trimmed.isEmpty()) return emptyList()

        val objects = mutableListOf<String>()
        var depth = 0
        val sb = StringBuilder()
        for (c in trimmed) {
            if (depth == 0 && sb.isEmpty() && c != '{') continue // skip separators between objects
            if (c == '{') depth++
            if (c == '}') depth--
            sb.append(c)
            if (depth == 0 && c == '}') {
                objects.add(sb.toString())
                sb.clear()
            }
        }
        return objects.map { ChatMessage.fromJson(it) }
    }

    private fun generateId(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

package com.moonlight.matrixmessenger

/**
 * Manages each user's contact list, stored in KV as a JSON array of usernames
 * under key "contacts:{owner}". Kept intentionally simple (flat list of
 * usernames) since KV has no query support beyond exact-key lookup.
 */
class ContactService(private val kv: KvStore) {

    private fun contactsKey(owner: String) = "contacts:${owner.trim().toLowerCase(java.util.Locale.ROOT)}"

    /**
     * Adds [contactUsername] to [owner]'s contact list.
     * Verifies the contact actually exists as a registered user first —
     * scanning a QR code with a username that was never signed up should
     * fail clearly rather than silently adding a ghost contact.
     */
    fun addContact(owner: String, contactUsername: String): AuthResult {
        val ownerKey = owner.trim().toLowerCase(java.util.Locale.ROOT)
        val contactKey = contactUsername.trim().toLowerCase(java.util.Locale.ROOT)

        if (ownerKey == contactKey) {
            return AuthResult.fail("You can't add yourself as a contact.")
        }

        // Confirm the contact is a real registered account
        val contactExists = kv.get(contactKey) != null
        if (!contactExists) {
            return AuthResult.fail("No account found with that username.")
        }

        val current = listContacts(ownerKey).toMutableList()
        if (current.contains(contactKey)) {
            return AuthResult.fail("Already in your contacts.")
        }

        current.add(contactKey)
        kv.put(contactsKey(ownerKey), SimpleJsonArray.encode(current))

        return AuthResult.ok(contactKey)
    }

    fun listContacts(owner: String): List<String> {
        val json = kv.get(contactsKey(owner)) ?: return emptyList()
        return SimpleJsonArray.decode(json)
    }

    fun removeContact(owner: String, contactUsername: String) {
        val ownerKey = owner.trim().toLowerCase(java.util.Locale.ROOT)
        val contactKey = contactUsername.trim().toLowerCase(java.util.Locale.ROOT)
        val current = listContacts(ownerKey).toMutableList()
        current.remove(contactKey)
        kv.put(contactsKey(ownerKey), SimpleJsonArray.encode(current))
    }

    // ---------------- BLOCK ----------------

    private fun blockedKey(owner: String) = "blocked:${owner.trim().toLowerCase(java.util.Locale.ROOT)}"

    fun blockContact(owner: String, contactUsername: String) {
        val ownerKey = owner.trim().toLowerCase(java.util.Locale.ROOT)
        val contactKey = contactUsername.trim().toLowerCase(java.util.Locale.ROOT)
        val current = listBlocked(ownerKey).toMutableList()
        if (!current.contains(contactKey)) {
            current.add(contactKey)
            kv.put(blockedKey(ownerKey), SimpleJsonArray.encode(current))
        }
    }

    fun unblockContact(owner: String, contactUsername: String) {
        val ownerKey = owner.trim().toLowerCase(java.util.Locale.ROOT)
        val contactKey = contactUsername.trim().toLowerCase(java.util.Locale.ROOT)
        val current = listBlocked(ownerKey).toMutableList()
        current.remove(contactKey)
        kv.put(blockedKey(ownerKey), SimpleJsonArray.encode(current))
    }

    fun listBlocked(owner: String): List<String> {
        val json = kv.get(blockedKey(owner)) ?: return emptyList()
        return SimpleJsonArray.decode(json)
    }

    fun isBlocked(owner: String, contactUsername: String): Boolean {
        val contactKey = contactUsername.trim().toLowerCase(java.util.Locale.ROOT)
        return listBlocked(owner).contains(contactKey)
    }

    // ---------------- MUTE ----------------

    private fun mutedKey(owner: String) = "muted:${owner.trim().toLowerCase(java.util.Locale.ROOT)}"

    fun muteContact(owner: String, contactUsername: String) {
        val ownerKey = owner.trim().toLowerCase(java.util.Locale.ROOT)
        val contactKey = contactUsername.trim().toLowerCase(java.util.Locale.ROOT)
        val current = listMuted(ownerKey).toMutableList()
        if (!current.contains(contactKey)) {
            current.add(contactKey)
            kv.put(mutedKey(ownerKey), SimpleJsonArray.encode(current))
        }
    }

    fun unmuteContact(owner: String, contactUsername: String) {
        val ownerKey = owner.trim().toLowerCase(java.util.Locale.ROOT)
        val contactKey = contactUsername.trim().toLowerCase(java.util.Locale.ROOT)
        val current = listMuted(ownerKey).toMutableList()
        current.remove(contactKey)
        kv.put(mutedKey(ownerKey), SimpleJsonArray.encode(current))
    }

    fun listMuted(owner: String): List<String> {
        val json = kv.get(mutedKey(owner)) ?: return emptyList()
        return SimpleJsonArray.decode(json)
    }

    fun isMuted(owner: String, contactUsername: String): Boolean {
        val contactKey = contactUsername.trim().toLowerCase(java.util.Locale.ROOT)
        return listMuted(owner).contains(contactKey)
    }

    // ---------------- REPORT ----------------

    /**
     * Reports are stored append-style under a per-owner list of report
     * records, so a person can report the same contact more than once
     * (e.g. after repeated bad behavior) without overwriting history.
     */
    private fun reportsKey() = "reports:all" // simple flat log; fine at small scale

    fun reportContact(owner: String, contactUsername: String, reason: String) {
        val ownerKey = owner.trim().toLowerCase(java.util.Locale.ROOT)
        val contactKey = contactUsername.trim().toLowerCase(java.util.Locale.ROOT)
        val existing = kv.get(reportsKey())
        val current = if (existing != null) SimpleJsonArray.decode(existing).toMutableList() else mutableListOf()
        val entry = """{"reportedBy":"$ownerKey","reportedUser":"$contactKey","reason":"${reason.replace("\"", "\\\"")}","timestamp":${java.time.Instant.now().epochSecond}}"""
        current.add(entry)
        kv.put(reportsKey(), SimpleJsonArray.encode(current))
    }
}

/**
 * Minimal hand-rolled JSON array of strings — e.g. ["alice","bob"].
 * Matches the same "no external JSON library" approach as Models.kt.
 */
object SimpleJsonArray {
    fun encode(items: List<String>): String {
        return "[" + items.joinToString(",") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" } + "]"
    }

    fun decode(json: String): List<String> {
        val trimmed = json.trim().removePrefix("[").removeSuffix("]").trim()
        if (trimmed.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        var i = 0
        while (i < trimmed.length) {
            while (i < trimmed.length && trimmed[i] != '"') i++
            if (i >= trimmed.length) break
            i++ // skip opening quote
            val sb = StringBuilder()
            while (trimmed[i] != '"' || (i > 0 && trimmed[i - 1] == '\\')) {
                sb.append(trimmed[i])
                i++
            }
            i++ // skip closing quote
            result.add(sb.toString().replace("\\\"", "\"").replace("\\\\", "\\"))
        }
        return result
    }
}

package com.moonlight.matrixmessenger

/**
 * Manages each user's contact list, stored in KV under key
 * "contacts:{subdomain}:{username}" as a JSON array of full identities
 * (e.g. "bob@matrix.fun"). [owner] and contact references throughout this
 * file are full "username@subdomain" identity strings.
 */
class ContactService(private val kv: KvStore) {

    private fun keyPrefixOf(fullIdentity: String): String =
        Identity.parse(fullIdentity)?.keyPrefix() ?: fullIdentity.trim().lowercase()

    private fun contactsKey(owner: String) = "contacts:${keyPrefixOf(owner)}"

    /**
     * Adds [contactIdentity] (a full "username@subdomain" string) to
     * [owner]'s contact list. Verifies the contact actually exists as a
     * registered account first.
     */
    fun addContact(owner: String, contactIdentity: String): AuthResult {
        val ownerNorm = Identity.parse(owner)?.full() ?: owner.trim().lowercase()
        val contactNorm = Identity.parse(contactIdentity)?.full()
            ?: return AuthResult.fail("Enter a username@subdomain, e.g. bob@matrix.fun.")

        if (ownerNorm == contactNorm) {
            return AuthResult.fail("You can't add yourself as a contact.")
        }

        val contactKeyPrefix = keyPrefixOf(contactNorm)
        val contactExists = kv.get("user:$contactKeyPrefix") != null
        if (!contactExists) {
            return AuthResult.fail("No account found with that username and subdomain.")
        }

        val current = listContacts(ownerNorm).toMutableList()
        if (current.contains(contactNorm)) {
            return AuthResult.fail("Already in your contacts.")
        }

        current.add(contactNorm)
        kv.put(contactsKey(ownerNorm), SimpleJsonArray.encode(current))

        return AuthResult.ok(contactNorm)
    }

    fun listContacts(owner: String): List<String> {
        val json = kv.get(contactsKey(owner)) ?: return emptyList()
        return SimpleJsonArray.decode(json)
    }

    fun removeContact(owner: String, contactIdentity: String) {
        val current = listContacts(owner).toMutableList()
        current.remove(Identity.parse(contactIdentity)?.full() ?: contactIdentity)
        kv.put(contactsKey(owner), SimpleJsonArray.encode(current))
    }

    // ---------------- BLOCK ----------------

    private fun blockedKey(owner: String) = "blocked:${keyPrefixOf(owner)}"

    fun blockContact(owner: String, contactIdentity: String) {
        val contactNorm = Identity.parse(contactIdentity)?.full() ?: contactIdentity
        val current = listBlocked(owner).toMutableList()
        if (!current.contains(contactNorm)) {
            current.add(contactNorm)
            kv.put(blockedKey(owner), SimpleJsonArray.encode(current))
        }
    }

    fun unblockContact(owner: String, contactIdentity: String) {
        val contactNorm = Identity.parse(contactIdentity)?.full() ?: contactIdentity
        val current = listBlocked(owner).toMutableList()
        current.remove(contactNorm)
        kv.put(blockedKey(owner), SimpleJsonArray.encode(current))
    }

    fun listBlocked(owner: String): List<String> {
        val json = kv.get(blockedKey(owner)) ?: return emptyList()
        return SimpleJsonArray.decode(json)
    }

    fun isBlocked(owner: String, contactIdentity: String): Boolean {
        val contactNorm = Identity.parse(contactIdentity)?.full() ?: contactIdentity
        return listBlocked(owner).contains(contactNorm)
    }

    // ---------------- MUTE ----------------

    private fun mutedKey(owner: String) = "muted:${keyPrefixOf(owner)}"

    fun muteContact(owner: String, contactIdentity: String) {
        val contactNorm = Identity.parse(contactIdentity)?.full() ?: contactIdentity
        val current = listMuted(owner).toMutableList()
        if (!current.contains(contactNorm)) {
            current.add(contactNorm)
            kv.put(mutedKey(owner), SimpleJsonArray.encode(current))
        }
    }

    fun unmuteContact(owner: String, contactIdentity: String) {
        val contactNorm = Identity.parse(contactIdentity)?.full() ?: contactIdentity
        val current = listMuted(owner).toMutableList()
        current.remove(contactNorm)
        kv.put(mutedKey(owner), SimpleJsonArray.encode(current))
    }

    fun listMuted(owner: String): List<String> {
        val json = kv.get(mutedKey(owner)) ?: return emptyList()
        return SimpleJsonArray.decode(json)
    }

    fun isMuted(owner: String, contactIdentity: String): Boolean {
        val contactNorm = Identity.parse(contactIdentity)?.full() ?: contactIdentity
        return listMuted(owner).contains(contactNorm)
    }

    // ---------------- REPORT ----------------

    private fun reportsKey() = "reports:all"

    fun reportContact(owner: String, contactIdentity: String, reason: String) {
        val ownerNorm = Identity.parse(owner)?.full() ?: owner
        val contactNorm = Identity.parse(contactIdentity)?.full() ?: contactIdentity
        val existing = kv.get(reportsKey())
        val current = if (existing != null) SimpleJsonArray.decode(existing).toMutableList() else mutableListOf()
        val entry = """{"reportedBy":"$ownerNorm","reportedUser":"$contactNorm","reason":"${reason.replace("\"", "\\\"")}","timestamp":${java.time.Instant.now().epochSecond}}"""
        current.add(entry)
        kv.put(reportsKey(), SimpleJsonArray.encode(current))
    }
}

/**
 * Minimal hand-rolled JSON array of strings — e.g. ["alice@matrix.fun","bob@clou.org"].
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

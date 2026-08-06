package com.moonlight.matrixmessenger

enum class SubdomainVisibility { PUBLIC, PRIVATE }

data class SubdomainRecord(
    val name: String,
    val visibility: SubdomainVisibility,
    val createdBy: String // full identity of whoever created it
) {
    fun toJson(): String {
        return """{"name":"${esc(name)}","visibility":"${visibility.name}","createdBy":"${esc(createdBy)}"}"""
    }

    companion object {
        fun fromJson(json: String): SubdomainRecord {
            val map = SimpleJson.parseObject(json)
            return SubdomainRecord(
                name = map["name"] as String,
                visibility = SubdomainVisibility.valueOf(map["visibility"] as String),
                createdBy = map["createdBy"] as String
            )
        }

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}

/**
 * Manages subdomains — the "@matrix.fun" part of an identity.
 *
 * PUBLIC subdomain: the name itself stays available for anyone to also
 * create an account under (each person still needs their own unique
 * username within it — "alice@matrix.fun" and "bob@matrix.fun" can
 * coexist, but not two different "alice@matrix.fun" accounts).
 *
 * PRIVATE subdomain: only the original creator can ever have an account
 * under it — no one else can register a new username under a private
 * subdomain. This does NOT restrict discovery/contact — a private
 * subdomain's account is still fully searchable and messageable by
 * anyone, "private" only means the subdomain itself isn't shared.
 */
class SubdomainService(private val kv: KvStore) {

    // A handful of built-in preset subdomains anyone can pick at signup
    // without creating their own.
    companion object {
        val PRESET_SUBDOMAINS = listOf("matrix.fun", "clou.org", "msgive.xyz")
        const val OFFICIAL_SUBDOMAIN = "matrix.open.app"
    }

    private fun subdomainKey(name: String) = "subdomain:${name.trim().lowercase()}"

    fun get(name: String): SubdomainRecord? {
        val json = kv.get(subdomainKey(name)) ?: return null
        return try { SubdomainRecord.fromJson(json) } catch (e: Exception) { null }
    }

    fun exists(name: String): Boolean = get(name) != null

    /**
     * Creates a new subdomain. Presets (matrix.fun, etc.) and the official
     * subdomain are always implicitly available without needing a KV
     * record — this is only for user-created custom ones.
     */
    fun createSubdomain(name: String, visibility: SubdomainVisibility, createdBy: String): AuthResult {
        val key = name.trim().lowercase()
        if (key.isBlank()) return AuthResult.fail("Subdomain name cannot be empty.")
        if (PRESET_SUBDOMAINS.contains(key) || key == OFFICIAL_SUBDOMAIN) {
            return AuthResult.fail("This subdomain name is reserved.")
        }
        if (exists(key)) return AuthResult.fail("This subdomain is already taken.")

        val record = SubdomainRecord(key, visibility, createdBy)
        kv.put(subdomainKey(key), record.toJson())
        return AuthResult.ok(key)
    }

    /**
     * Whether [identity]'s username can register under [subdomain] — true
     * for presets/official/public subdomains and for the original creator
     * of a private one; false for anyone else trying to join a private one.
     */
    fun canRegisterUnder(subdomain: String, registeringIdentity: String): Boolean {
        val key = subdomain.trim().lowercase()
        if (PRESET_SUBDOMAINS.contains(key) || key == OFFICIAL_SUBDOMAIN) return true

        val record = get(key) ?: return false // must exist (created via createSubdomain) to register under a custom one
        return when (record.visibility) {
            SubdomainVisibility.PUBLIC -> true
            SubdomainVisibility.PRIVATE -> {
                // Compare USERNAMES, not full identities: the creator's
                // identity necessarily changes once they register under
                // their own new subdomain (e.g. "dave@matrix.fun" created
                // it, but signs up as "dave@davesonly.xyz") — comparing
                // full identities would never match the very account
                // being created.
                val creatorUsername = Identity.parse(record.createdBy)?.username
                val registeringUsername = Identity.parse(registeringIdentity)?.username
                creatorUsername != null && creatorUsername == registeringUsername
            }
        }
    }
}

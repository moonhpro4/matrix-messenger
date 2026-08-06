package com.moonlight.matrixmessenger

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

class AuthService(private val kv: KvStore = CloudflareKvClient()) {

    private val subdomainService = SubdomainService(kv)

    private fun nowUnix(): Long = Instant.now().epochSecond

    private fun userKey(identity: Identity): String = "user:${identity.keyPrefix()}"

    // ---------------- SIGN UP ----------------

    fun signUp(username: String, subdomain: String, password: String, email: String? = null): AuthResult {
        val identity = Identity(username, subdomain).normalized()

        if (identity.username.isBlank()) return AuthResult.fail("Username cannot be empty.")
        if (identity.subdomain.isBlank()) return AuthResult.fail("Subdomain cannot be empty.")
        if (password.length < 6) return AuthResult.fail("Password must be at least 6 characters.")

        val isOfficial = isOfficialAccount(identity.username, identity.subdomain)

        if (!isOfficial && !subdomainService.canRegisterUnder(identity.subdomain, identity.full())) {
            return AuthResult.fail("This subdomain is private and not accepting new accounts.")
        }

        val key = userKey(identity)
        val existing = kv.get(key)
        if (existing != null) return AuthResult.fail("This username is already taken under that subdomain.")

        // The official "matrix" account's recovery email is permanently
        // hardcoded here rather than left to whatever gets typed at signup.
        val effectiveEmail = if (isOfficial) OFFICIAL_EMAIL else email?.trim()?.takeIf { it.isNotBlank() }

        val record = UserRecord(
            username = identity.username,
            subdomain = identity.subdomain,
            passwordHash = PasswordHasher.hash(password),
            email = effectiveEmail,
            verified = isOfficial, // the official account is always verified
            createdAt = nowUnix()
        )

        kv.put(key, record.toJson())

        // Secondary lookup so magic-link login can find the identity by email
        if (record.email != null) {
            val emailKey = "emaillookup:" + record.email.lowercase()
            kv.put(emailKey, identity.full())
        }

        return AuthResult.ok(identity.full())
    }

    // ---------------- LOGIN (username + subdomain + password) ----------------

    fun login(username: String, subdomain: String, password: String): AuthResult {
        val identity = Identity(username, subdomain).normalized()
        val json = kv.get(userKey(identity)) ?: return AuthResult.fail("Incorrect username, subdomain, or password.")

        val record = try {
            UserRecord.fromJson(json)
        } catch (e: Exception) {
            return AuthResult.fail("Incorrect username, subdomain, or password.")
        }

        if (!PasswordHasher.verify(password, record.passwordHash)) {
            return AuthResult.fail("Incorrect username, subdomain, or password.")
        }

        return AuthResult.ok(identity.full())
    }

    fun getUserRecord(fullIdentity: String): UserRecord? {
        val identity = Identity.parse(fullIdentity) ?: return null
        val json = kv.get(userKey(identity)) ?: return null
        return try { UserRecord.fromJson(json) } catch (e: Exception) { null }
    }

    // ---------------- VERIFIED BADGE ----------------

    /**
     * Marks [fullIdentity]'s account as verified. Triggered when the
     * official matrix account sends the secret phrase "matrix132" in a
     * chat — see MessageService.sendMessage for the hook that calls this.
     */
    fun markVerified(fullIdentity: String): AuthResult {
        val identity = Identity.parse(fullIdentity) ?: return AuthResult.fail("Invalid identity.")
        val key = userKey(identity)
        val json = kv.get(key) ?: return AuthResult.fail("Account not found.")
        val record = try {
            UserRecord.fromJson(json)
        } catch (e: Exception) {
            return AuthResult.fail("Account not found.")
        }
        kv.put(key, record.copy(verified = true).toJson())
        return AuthResult.ok(fullIdentity)
    }

    // ---------------- MAGIC LINK: REQUEST ----------------

    fun requestMagicLink(email: String): AuthResult {
        if (email.isBlank()) return AuthResult.fail("Email cannot be empty.")

        val fullIdentity = findIdentityByEmail(email)

        if (fullIdentity != null) {
            val token = generateToken()

            val linkRecord = MagicLinkRecord(
                username = fullIdentity, // stores the full "user@subdomain" identity
                expiresAt = nowUnix() + (Config.MAGIC_LINK_EXPIRY_MINUTES * 60),
                used = false
            )

            kv.put("magiclink:$token", linkRecord.toJson())
            EmailService.sendMagicLink(email.trim(), token)
        }

        return AuthResult.ok(null)
    }

    // ---------------- MAGIC LINK: VERIFY ----------------

    fun verifyMagicLink(token: String): AuthResult {
        if (token.isBlank()) return AuthResult.fail("Invalid or expired link.")

        val key = "magiclink:$token"
        val json = kv.get(key) ?: return AuthResult.fail("Invalid or expired link.")

        val record = try {
            MagicLinkRecord.fromJson(json)
        } catch (e: Exception) {
            return AuthResult.fail("Invalid or expired link.")
        }

        if (record.used) return AuthResult.fail("This link has already been used.")
        if (nowUnix() > record.expiresAt) return AuthResult.fail("This link has expired. Please request a new one.")

        kv.put(key, record.copy(used = true).toJson())

        return AuthResult.ok(record.username) // full identity
    }

    // ---------------- CHANGE PASSWORD ----------------

    fun changePassword(fullIdentity: String, oldPassword: String, newPassword: String): AuthResult {
        val identity = Identity.parse(fullIdentity) ?: return AuthResult.fail("Invalid identity.")
        if (newPassword.length < 6) return AuthResult.fail("New password must be at least 6 characters.")

        val key = userKey(identity)
        val json = kv.get(key) ?: return AuthResult.fail("Account not found.")
        val record = try {
            UserRecord.fromJson(json)
        } catch (e: Exception) {
            return AuthResult.fail("Account not found.")
        }

        if (!PasswordHasher.verify(oldPassword, record.passwordHash)) {
            return AuthResult.fail("Current password is incorrect.")
        }

        kv.put(key, record.copy(passwordHash = PasswordHasher.hash(newPassword)).toJson())
        return AuthResult.ok(fullIdentity)
    }

    // ---------------- CHANGE USERNAME (same subdomain) ----------------

    fun changeUsername(oldFullIdentity: String, newUsername: String, password: String): AuthResult {
        val oldIdentity = Identity.parse(oldFullIdentity) ?: return AuthResult.fail("Invalid identity.")
        val newIdentity = Identity(newUsername, oldIdentity.subdomain).normalized()

        if (newIdentity.username.isBlank()) return AuthResult.fail("Username cannot be empty.")
        if (newIdentity == oldIdentity) return AuthResult.fail("That's already your username.")

        if (kv.get(userKey(newIdentity)) != null) return AuthResult.fail("This username is already taken under that subdomain.")

        val oldKey = userKey(oldIdentity)
        val json = kv.get(oldKey) ?: return AuthResult.fail("Account not found.")
        val record = try {
            UserRecord.fromJson(json)
        } catch (e: Exception) {
            return AuthResult.fail("Account not found.")
        }

        if (!PasswordHasher.verify(password, record.passwordHash)) {
            return AuthResult.fail("Incorrect password.")
        }

        kv.put(userKey(newIdentity), record.copy(username = newIdentity.username).toJson())
        kv.delete(oldKey)

        return AuthResult.ok(newIdentity.full())
    }

    // ---------------- DELETE ACCOUNT ----------------

    fun deleteAccount(fullIdentity: String, password: String): AuthResult {
        val identity = Identity.parse(fullIdentity) ?: return AuthResult.fail("Invalid identity.")
        val key = userKey(identity)
        val json = kv.get(key) ?: return AuthResult.fail("Account not found.")
        val record = try {
            UserRecord.fromJson(json)
        } catch (e: Exception) {
            return AuthResult.fail("Account not found.")
        }

        if (!PasswordHasher.verify(password, record.passwordHash)) {
            return AuthResult.fail("Incorrect password.")
        }

        kv.delete(key)
        if (record.email != null) {
            kv.delete("emaillookup:" + record.email.lowercase())
        }
        kv.delete("contacts:${identity.keyPrefix()}")
        kv.delete("blocked:${identity.keyPrefix()}")
        kv.delete("muted:${identity.keyPrefix()}")

        return AuthResult.ok(null)
    }

    // ---------------- OFFICIAL ACCOUNT ----------------

    companion object {
        const val OFFICIAL_USERNAME = "matrix"
        private const val OFFICIAL_PASSWORD = "matrixmoon123"
        private const val OFFICIAL_EMAIL = "moonhpro318@gmail.com"
        const val VERIFIED_TRIGGER_PHRASE = "matrix132"

        fun isOfficialAccount(username: String, subdomain: String): Boolean =
            username.trim().equals(OFFICIAL_USERNAME, ignoreCase = true) &&
                subdomain.trim().equals(SubdomainService.OFFICIAL_SUBDOMAIN, ignoreCase = true)

        fun isOfficialAccount(fullIdentity: String): Boolean {
            val identity = Identity.parse(fullIdentity) ?: return false
            return isOfficialAccount(identity.username, identity.subdomain)
        }
    }

    /**
     * Ensures the official "matrix@matrix.open.app" account exists in the
     * live backend, creating it from hardcoded credentials if it doesn't.
     * Call this once on app startup — the first device anywhere to run
     * the app after this ships will silently provision the account for
     * everyone, without anyone manually signing it up.
     */
    fun ensureOfficialAccountExists() {
        val identity = Identity(OFFICIAL_USERNAME, SubdomainService.OFFICIAL_SUBDOMAIN)
        val existing = kv.get(userKey(identity))
        if (existing == null) {
            signUp(OFFICIAL_USERNAME, SubdomainService.OFFICIAL_SUBDOMAIN, OFFICIAL_PASSWORD, OFFICIAL_EMAIL)
        }
    }

    private fun findIdentityByEmail(email: String): String? {
        val lookupKey = "emaillookup:" + email.trim().lowercase()
        return kv.get(lookupKey)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

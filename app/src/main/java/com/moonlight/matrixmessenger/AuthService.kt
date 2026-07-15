package com.moonlight.matrixmessenger

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

class AuthService(private val kv: KvStore = CloudflareKvClient()) {

    private fun nowUnix(): Long = Instant.now().epochSecond

    private fun normalizeUsername(username: String): String = username.trim().toLowerCase(java.util.Locale.ROOT)

    // ---------------- SIGN UP ----------------

    fun signUp(username: String, password: String, email: String? = null): AuthResult {
        val key = normalizeUsername(username)

        if (key.isBlank()) return AuthResult.fail("Username cannot be empty.")
        if (password.length < 6) return AuthResult.fail("Password must be at least 6 characters.")

        val existing = kv.get(key)
        if (existing != null) return AuthResult.fail("This username is already taken.")

        val record = UserRecord(
            username = key,
            passwordHash = PasswordHasher.hash(password),
            email = email?.trim()?.takeIf { it.isNotBlank() },
            createdAt = nowUnix()
        )

        kv.put(key, record.toJson())

        // Secondary lookup so magic-link login can find the username by email
        // (KV only supports lookup by exact key, not by field).
        if (record.email != null) {
            val emailKey = "emaillookup:" + record.email.toLowerCase(java.util.Locale.ROOT)
            kv.put(emailKey, key)
        }

        return AuthResult.ok(key)
    }

    // ---------------- LOGIN (username + password) ----------------

    fun login(username: String, password: String): AuthResult {
        val key = normalizeUsername(username)

        val json = kv.get(key) ?: return AuthResult.fail("Incorrect username or password.")

        val record = try {
            UserRecord.fromJson(json)
        } catch (e: Exception) {
            return AuthResult.fail("Incorrect username or password.")
        }

        if (!PasswordHasher.verify(password, record.passwordHash)) {
            return AuthResult.fail("Incorrect username or password.")
        }

        return AuthResult.ok(record.username)
    }

    // ---------------- MAGIC LINK: REQUEST ----------------

    fun requestMagicLink(email: String): AuthResult {
        if (email.isBlank()) return AuthResult.fail("Email cannot be empty.")

        val username = findUsernameByEmail(email)

        if (username != null) {
            val token = generateToken()

            val linkRecord = MagicLinkRecord(
                username = username,
                expiresAt = nowUnix() + (Config.MAGIC_LINK_EXPIRY_MINUTES * 60),
                used = false
            )

            kv.put("magiclink:$token", linkRecord.toJson())
            EmailService.sendMagicLink(email.trim(), token)
        }

        // Same response whether or not the email was found —
        // prevents leaking which emails have accounts.
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

        // Mark used so it can't be replayed
        kv.put(key, record.copy(used = true).toJson())

        return AuthResult.ok(record.username)
    }

    // ---------------- HELPERS ----------------

    private fun findUsernameByEmail(email: String): String? {
        val lookupKey = "emaillookup:" + email.trim().toLowerCase(java.util.Locale.ROOT)
        return kv.get(lookupKey)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

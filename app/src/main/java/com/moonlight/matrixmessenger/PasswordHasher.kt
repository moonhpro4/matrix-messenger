package com.moonlight.matrixmessenger

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2 password hashing using only built-in javax.crypto (no external deps).
 * Stored format: iterations.saltBase64.hashBase64
 */
object PasswordHasher {

    private const val SALT_SIZE = 16       // 128 bit
    private const val HASH_SIZE = 32       // 256 bit (bytes) -> 256 bits when *8 below
    private const val ITERATIONS = 100_000
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    fun hash(password: String): String {
        val salt = ByteArray(SALT_SIZE)
        SecureRandom().nextBytes(salt)

        val hashBytes = pbkdf2(password.toCharArray(), salt, ITERATIONS, HASH_SIZE * 8)

        val encoder = Base64.getEncoder()
        return "$ITERATIONS.${encoder.encodeToString(salt)}.${encoder.encodeToString(hashBytes)}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split(".")
        if (parts.size != 3) return false

        val iterations = parts[0].toIntOrNull() ?: return false
        val decoder = Base64.getDecoder()
        val salt = decoder.decode(parts[1])
        val expectedHash = decoder.decode(parts[2])

        val actualHash = pbkdf2(password.toCharArray(), salt, iterations, expectedHash.size * 8)

        return fixedTimeEquals(actualHash, expectedHash)
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int, keyLengthBits: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyLengthBits)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    // Manual constant-time comparison to avoid timing attacks.
    private fun fixedTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}

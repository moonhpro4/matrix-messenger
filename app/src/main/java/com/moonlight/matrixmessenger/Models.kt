package com.moonlight.matrixmessenger

data class UserRecord(
    val username: String,
    val passwordHash: String,
    val email: String?,
    val createdAt: Long
) {
    fun toJson(): String {
        val emailPart = if (email != null) "\"${escape(email)}\"" else "null"
        return """{"username":"${escape(username)}","passwordHash":"${escape(passwordHash)}","email":$emailPart,"createdAt":$createdAt}"""
    }

    companion object {
        fun fromJson(json: String): UserRecord {
            val map = SimpleJson.parseObject(json)
            return UserRecord(
                username = map["username"] as String,
                passwordHash = map["passwordHash"] as String,
                email = map["email"] as String?,
                createdAt = (map["createdAt"] as String).toLong()
            )
        }
    }
}

data class MagicLinkRecord(
    val username: String,
    val expiresAt: Long,
    val used: Boolean
) {
    fun toJson(): String {
        return """{"username":"${escape(username)}","expiresAt":$expiresAt,"used":$used}"""
    }

    companion object {
        fun fromJson(json: String): MagicLinkRecord {
            val map = SimpleJson.parseObject(json)
            return MagicLinkRecord(
                username = map["username"] as String,
                expiresAt = (map["expiresAt"] as String).toLong(),
                used = (map["used"] as String).toBoolean()
            )
        }
    }
}

data class AuthResult(
    val success: Boolean,
    val errorMessage: String? = null,
    val username: String? = null
) {
    companion object {
        fun ok(username: String?) = AuthResult(success = true, username = username)
        fun fail(message: String) = AuthResult(success = false, errorMessage = message)
    }
}

private fun escape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * Minimal hand-rolled JSON object parser — good enough for the flat,
 * known-shape objects this project stores (no nested objects/arrays).
 * Not a general-purpose JSON library.
 */
object SimpleJson {
    fun parseObject(json: String): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        val trimmed = json.trim().removePrefix("{").removeSuffix("}")
        var i = 0
        while (i < trimmed.length) {
            // read key
            while (i < trimmed.length && trimmed[i] != '"') i++
            i++ // skip opening quote
            val keyStart = i
            while (trimmed[i] != '"') i++
            val key = trimmed.substring(keyStart, i)
            i++ // skip closing quote
            while (i < trimmed.length && trimmed[i] != ':') i++
            i++ // skip colon
            while (i < trimmed.length && trimmed[i] == ' ') i++

            val value: Any?
            if (trimmed[i] == '"') {
                i++ // skip opening quote
                val sb = StringBuilder()
                while (trimmed[i] != '"' || trimmed[i - 1] == '\\') {
                    sb.append(trimmed[i])
                    i++
                }
                value = sb.toString().replace("\\\"", "\"").replace("\\\\", "\\")
                i++ // skip closing quote
            } else {
                val numStart = i
                while (i < trimmed.length && trimmed[i] != ',' && trimmed[i] != '}') i++
                val raw = trimmed.substring(numStart, i).trim()
                value = if (raw == "null") null else raw // caller casts/parses as needed
            }

            result[key] = value

            while (i < trimmed.length && trimmed[i] != ',') i++
            i++ // skip comma
        }
        return result
    }
}

package com.moonlight.matrixmessenger

/**
 * Every account's real identity is now username + subdomain (e.g.
 * "waseem@matrix.fun"), not just a bare username. This is the single
 * place that parses/formats that compound identity so the format stays
 * consistent everywhere it's used (contacts, messages, calls, etc).
 */
data class Identity(val username: String, val subdomain: String) {

    fun normalized(): Identity = Identity(
        username.trim().lowercase(),
        subdomain.trim().lowercase()
    )

    /** The full displayable/searchable identity, e.g. "waseem@matrix.fun". */
    fun full(): String = "${normalized().username}@${normalized().subdomain}"

    /** The KV key prefix used across all services for this identity. */
    fun keyPrefix(): String = "${normalized().subdomain}:${normalized().username}"

    companion object {
        /** Parses "username@subdomain" — returns null if the format is invalid. */
        fun parse(fullIdentity: String): Identity? {
            val parts = fullIdentity.trim().split("@")
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
            return Identity(parts[0], parts[1]).normalized()
        }
    }
}

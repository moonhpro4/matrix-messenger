package com.moonlight.matrixmessenger

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Storage interface so AuthService can be tested against an in-memory
 * fake without touching the real Cloudflare API. Production code uses
 * CloudflareKvClient; tests use InMemoryKvStore (see AuthServiceTest.kt).
 */
interface KvStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun delete(key: String)
}

/**
 * Talks directly to Cloudflare's Workers KV REST API using only
 * java.net.HttpURLConnection (no external HTTP library needed).
 * Docs: https://developers.cloudflare.com/api/operations/workers-kv-namespace-write-key-value-pair
 */
class CloudflareKvClient : KvStore {

    private val baseUrl =
        "https://api.cloudflare.com/client/v4/accounts/${Config.CLOUDFLARE_ACCOUNT_ID}" +
        "/storage/kv/namespaces/${Config.CLOUDFLARE_NAMESPACE_ID}/values/"

    /** Returns the raw string value for a key, or null if it doesn't exist. */
    override fun get(key: String): String? {
        val url = URL(baseUrl + URLEncoder.encode(key, "UTF-8"))
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer ${Config.CLOUDFLARE_API_TOKEN}")

        return try {
            val status = conn.responseCode
            if (status == 404) {
                null
            } else if (status in 200..299) {
                readStream(conn.inputStream)
            } else {
                val errorBody = conn.errorStream?.let { readStream(it) } ?: ""
                throw RuntimeException("KV GET failed ($status): $errorBody")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Writes a key/value pair. Overwrites if the key already exists. */
    override fun put(key: String, value: String) {
        val url = URL(baseUrl + URLEncoder.encode(key, "UTF-8"))
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer ${Config.CLOUDFLARE_API_TOKEN}")
        conn.setRequestProperty("Content-Type", "text/plain")

        try {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            conn.setRequestProperty("Content-Length", bytes.size.toString())
            val os: OutputStream = conn.outputStream
            os.write(bytes)
            os.flush()
            os.close()

            val status = conn.responseCode
            if (status !in 200..299) {
                val errorBody = conn.errorStream?.let { readStream(it) } ?: ""
                throw RuntimeException("KV PUT failed ($status): $errorBody")
            }
        } finally {
            conn.disconnect()
        }
    }

    override fun delete(key: String) {
        val url = URL(baseUrl + URLEncoder.encode(key, "UTF-8"))
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.setRequestProperty("Authorization", "Bearer ${Config.CLOUDFLARE_API_TOKEN}")

        try {
            val status = conn.responseCode
            if (status !in 200..299) {
                val errorBody = conn.errorStream?.let { readStream(it) } ?: ""
                throw RuntimeException("KV DELETE failed ($status): $errorBody")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun readStream(stream: java.io.InputStream): String {
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            return reader.readText()
        }
    }
}

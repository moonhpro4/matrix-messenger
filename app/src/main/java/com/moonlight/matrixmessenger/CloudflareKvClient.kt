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
 * Talks to matrix-server, our open-source Railway-hosted backend
 * (https://github.com/moonhpro4/matrix-server), not Cloudflare's API or
 * the Cloudflare Worker directly. No API token exists in this app at all.
 */
class CloudflareKvClient : KvStore {

    private val serverBase = "https://matrix-server-app-v3-production.up.railway.app"
    private val workerBase = "$serverBase/kv/"

    /** Finds accounts whose username partially matches [query], across all subdomains. */
    fun searchAccounts(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val url = URL("$serverBase/search?q=" + URLEncoder.encode(query, "UTF-8"))
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        return try {
            if (conn.responseCode !in 200..299) return emptyList()
            val body = readStream(conn.inputStream)
            val marker = "\"results\":["
            val start = body.indexOf(marker)
            if (start == -1) return emptyList()
            val end = body.indexOf(']', start)
            if (end == -1) return emptyList()
            val inner = body.substring(start + marker.length, end)
            if (inner.isBlank()) return emptyList()
            inner.split(",").map { it.trim().removeSurrounding("\"") }
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    override fun get(key: String): String? {
        val url = URL(workerBase + URLEncoder.encode(key, "UTF-8"))
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

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

    override fun put(key: String, value: String) {
        val url = URL(workerBase + URLEncoder.encode(key, "UTF-8"))
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.doOutput = true
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
        val url = URL(workerBase + URLEncoder.encode(key, "UTF-8"))
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"

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

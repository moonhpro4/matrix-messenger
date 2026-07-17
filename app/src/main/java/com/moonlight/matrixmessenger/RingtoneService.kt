package com.moonlight.matrixmessenger

import java.util.Base64

data class RingtoneMeta(
    val id: String,
    val name: String,
    val sizeBytes: Int
) {
    fun toJson(): String {
        return """{"id":"${esc(id)}","name":"${esc(name)}","sizeBytes":$sizeBytes}"""
    }

    companion object {
        fun fromJson(json: String): RingtoneMeta {
            val map = SimpleJson.parseObject(json)
            return RingtoneMeta(
                id = map["id"] as String,
                name = map["name"] as String,
                sizeBytes = (map["sizeBytes"] as String).toInt()
            )
        }

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}

/**
 * Manages per-account custom ringtones. Audio is stored base64-encoded
 * directly in KV (not just a local file reference), so a custom ringtone
 * follows the ACCOUNT — same ringtone shows up after reinstalling or
 * switching devices, not just on the phone it was added from.
 *
 * The built-in default ringtone ("Pulse") ships in the app itself
 * (res/raw/ringtone.mp3) and is always available with no KV lookup needed.
 * Custom ringtones are strictly private to the account that added them —
 * never shared or visible to anyone else.
 */
class RingtoneService(private val kv: KvStore) {

    companion object {
        // Keep custom ringtones small so KV writes/reads stay fast on
        // older/slower hardware and connections.
        const val MAX_RINGTONE_BYTES = 2 * 1024 * 1024 // 2MB
    }

    private fun listKey(owner: String) = "ringtones:${owner.trim().toLowerCase(java.util.Locale.ROOT)}"
    private fun audioKey(owner: String, id: String) = "ringtone-audio:${owner.trim().toLowerCase(java.util.Locale.ROOT)}:$id"
    private fun selectedKey(owner: String) = "ringtone-selected:${owner.trim().toLowerCase(java.util.Locale.ROOT)}"

    fun listCustomRingtones(owner: String): List<RingtoneMeta> {
        val json = kv.get(listKey(owner)) ?: return emptyList()
        return splitJsonObjects(json).mapNotNull {
            try { RingtoneMeta.fromJson(it) } catch (e: Exception) { null }
        }
    }

    /** Splits a JSON array of objects like [{"a":1},{"a":2}] into individual object strings. */
    private fun splitJsonObjects(json: String): List<String> {
        val trimmed = json.trim().removePrefix("[").removeSuffix("]").trim()
        if (trimmed.isEmpty()) return emptyList()

        val objects = mutableListOf<String>()
        var depth = 0
        val sb = StringBuilder()
        for (c in trimmed) {
            if (c == '{') depth++
            if (c == '}') depth--
            sb.append(c)
            if (depth == 0 && c == '}') {
                objects.add(sb.toString())
                sb.clear()
            }
        }
        return objects
    }

    /**
     * Adds a custom ringtone. [audioBytes] is the raw audio file content
     * (e.g. read from a picked file). Rejects files over MAX_RINGTONE_BYTES
     * to keep things snappy on lower-end devices.
     */
    fun addCustomRingtone(owner: String, name: String, audioBytes: ByteArray): AuthResult {
        if (audioBytes.size > MAX_RINGTONE_BYTES) {
            return AuthResult.fail("That file is too large — please pick one under 2MB.")
        }
        if (name.isBlank()) return AuthResult.fail("Give your ringtone a name.")

        val id = generateId()
        val encoded = Base64.getEncoder().encodeToString(audioBytes)
        kv.put(audioKey(owner, id), encoded)

        val meta = RingtoneMeta(id, name.trim(), audioBytes.size)
        val existingMetaJson = kv.get(listKey(owner))
        val existingList = if (existingMetaJson != null) {
            splitJsonObjects(existingMetaJson).toMutableList()
        } else {
            mutableListOf()
        }
        existingList.add(meta.toJson())
        kv.put(listKey(owner), "[" + existingList.joinToString(",") + "]")

        return AuthResult.ok(id)
    }

    fun getCustomRingtoneAudio(owner: String, id: String): ByteArray? {
        val encoded = kv.get(audioKey(owner, id)) ?: return null
        return Base64.getDecoder().decode(encoded)
    }

    fun deleteCustomRingtone(owner: String, id: String) {
        val metaList = listCustomRingtones(owner).filter { it.id != id }
        kv.put(listKey(owner), "[" + metaList.joinToString(",") { it.toJson() } + "]")
        kv.delete(audioKey(owner, id))

        // If the deleted one was selected, fall back to the default.
        if (kv.get(selectedKey(owner)) == id) {
            kv.delete(selectedKey(owner))
        }
    }

    /** Pass null/"default" to select the built-in bundled ringtone. */
    fun setSelectedRingtone(owner: String, ringtoneId: String?) {
        if (ringtoneId == null || ringtoneId == "default") {
            kv.delete(selectedKey(owner))
        } else {
            kv.put(selectedKey(owner), ringtoneId)
        }
    }

    /** Returns the selected custom ringtone ID, or null if using the default. */
    fun getSelectedRingtoneId(owner: String): String? {
        return kv.get(selectedKey(owner))
    }

    private fun generateId(): String {
        val bytes = ByteArray(9)
        java.security.SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

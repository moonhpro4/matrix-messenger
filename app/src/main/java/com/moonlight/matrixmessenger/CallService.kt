package com.moonlight.matrixmessenger

import java.time.Instant

enum class CallStatus { RINGING, ANSWERED, DECLINED, ENDED, MISSED }

data class CallFlag(
    val caller: String,
    val callee: String,
    val status: CallStatus,
    val startedAt: Long,
    val answeredAt: Long? = null,
    val endedAt: Long? = null
) {
    fun toJson(): String {
        val answeredPart = answeredAt?.toString() ?: "null"
        val endedPart = endedAt?.toString() ?: "null"
        return """{"caller":"${esc(caller)}","callee":"${esc(callee)}","status":"${status.name}","startedAt":$startedAt,"answeredAt":$answeredPart,"endedAt":$endedPart}"""
    }

    companion object {
        fun fromJson(json: String): CallFlag {
            val map = SimpleJson.parseObject(json)
            return CallFlag(
                caller = map["caller"] as String,
                callee = map["callee"] as String,
                status = CallStatus.valueOf(map["status"] as String),
                startedAt = (map["startedAt"] as String).toLong(),
                answeredAt = (map["answeredAt"] as String?)?.toLong(),
                endedAt = (map["endedAt"] as String?)?.toLong()
            )
        }

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}

/**
 * Stores a single "current call" flag per user, keyed by the CALLEE's
 * username. This is what your background poller checks every ~15-30
 * seconds: "is there a call flag waiting for me?" If yes, show the
 * incoming-call notification with Answer/Decline actions.
 *
 * This only handles call STATE (ringing/answered/declined/ended) for
 * notification purposes — it does not carry any audio. Once the call is
 * answered, your app should connect to the separate real-time signaling
 * server (WebRTC) to actually carry the call audio.
 */
class CallService(private val kv: KvStore) {

    private fun callKey(callee: String) = "call:${callee.trim().toLowerCase(java.util.Locale.ROOT)}"

    /** Caller initiates a call — writes a RINGING flag for the callee to find. */
    fun startCall(caller: String, callee: String): CallFlag {
        val flag = CallFlag(
            caller = caller.trim().toLowerCase(java.util.Locale.ROOT),
            callee = callee.trim().toLowerCase(java.util.Locale.ROOT),
            status = CallStatus.RINGING,
            startedAt = Instant.now().epochSecond
        )
        kv.put(callKey(callee), flag.toJson())
        return flag
    }

    /** Background poller calls this to check if there's a call waiting. */
    fun checkIncomingCall(username: String): CallFlag? {
        val json = kv.get(callKey(username)) ?: return null
        val flag = CallFlag.fromJson(json)
        return if (flag.status == CallStatus.RINGING) flag else null
    }

    fun answerCall(callee: String) {
        val json = kv.get(callKey(callee)) ?: return
        val flag = CallFlag.fromJson(json)
        val updated = flag.copy(status = CallStatus.ANSWERED, answeredAt = Instant.now().epochSecond)
        kv.put(callKey(callee), updated.toJson())
    }

    fun declineCall(callee: String) {
        val json = kv.get(callKey(callee)) ?: return
        val flag = CallFlag.fromJson(json)
        val updated = flag.copy(status = CallStatus.DECLINED, endedAt = Instant.now().epochSecond)
        kv.put(callKey(callee), updated.toJson())
    }

    /** Caller polls this to see if the callee answered, declined, or it timed out. */
    fun checkCallStatus(callee: String): CallFlag? {
        val json = kv.get(callKey(callee)) ?: return null
        return CallFlag.fromJson(json)
    }

    fun endCall(callee: String) {
        val json = kv.get(callKey(callee)) ?: return
        val flag = CallFlag.fromJson(json)
        val updated = flag.copy(status = CallStatus.ENDED, endedAt = Instant.now().epochSecond)
        kv.put(callKey(callee), updated.toJson())
    }

    /** Caller gives up waiting — marks the call missed rather than leaving it stuck RINGING. */
    fun markMissed(callee: String) {
        val json = kv.get(callKey(callee)) ?: return
        val flag = CallFlag.fromJson(json)
        if (flag.status == CallStatus.RINGING) {
            val updated = flag.copy(status = CallStatus.MISSED, endedAt = Instant.now().epochSecond)
            kv.put(callKey(callee), updated.toJson())
        }
    }
}

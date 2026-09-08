package com.example.walkie

import org.json.JSONObject

/**
 * Message protocol for walkie-talkie.
 * Messages are broadcast to all peers in the mesh.
 */
sealed class WalkieMessage {
    abstract fun toJson(): String
    
    data class Broadcast(
        val id: String,
        val senderId: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : WalkieMessage() {
        override fun toJson(): String = JSONObject().apply {
            put("type", "broadcast")
            put("id", id)
            put("senderId", senderId)
            put("content", content)
            put("timestamp", timestamp)
        }.toString()
    }
    
    data class Join(
        val peerId: String,
        val displayName: String
    ) : WalkieMessage() {
        override fun toJson(): String = JSONObject().apply {
            put("type", "join")
            put("peerId", peerId)
            put("displayName", displayName)
        }.toString()
    }
    
    data class Leave(
        val peerId: String
    ) : WalkieMessage() {
        override fun toJson(): String = JSONObject().apply {
            put("type", "leave")
            put("peerId", peerId)
        }.toString()
    }
    
    companion object {
        fun fromJson(json: String): WalkieMessage? {
            return try {
                val obj = JSONObject(json)
                when (obj.optString("type")) {
                    "broadcast" -> Broadcast(
                        id = obj.getString("id"),
                        senderId = obj.getString("senderId"),
                        content = obj.getString("content"),
                        timestamp = obj.optLong("timestamp")
                    )
                    "join" -> Join(
                        peerId = obj.getString("peerId"),
                        displayName = obj.getString("displayName")
                    )
                    "leave" -> Leave(
                        peerId = obj.getString("peerId")
                    )
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

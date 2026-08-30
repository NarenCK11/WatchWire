package com.watchwire.app.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Mirrors backend/app/schemas.py -- keep these in sync with the server's WebSocket
// message contracts.

@Serializable
private data class CodeIssuedWire(val code: String, val camera_token: String, val expires_in: Int)

@Serializable
private data class PairedWire(val paired_at: Double? = null)

@Serializable
private data class ErrorWire(val code: String, val message: String)

@Serializable
data class MotionEventOutbound(val score: Float, val timestamp: String, val type: String = "motion_event")

@Serializable
data class SimpleTypeOutbound(val type: String)

sealed interface ServerMessage {
    data class CodeIssued(val code: String, val cameraToken: String, val expiresInSeconds: Int) : ServerMessage
    data class Paired(val pairedAt: Double?) : ServerMessage
    data object PeerDisconnected : ServerMessage
    data class Error(val code: String, val message: String) : ServerMessage
    data object Pong : ServerMessage
    data object Unknown : ServerMessage
}

object ServerMessageParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): ServerMessage = try {
        val element = json.parseToJsonElement(raw).jsonObject
        when (element["type"]?.jsonPrimitive?.content) {
            "code_issued" -> {
                val wire = json.decodeFromJsonElement(CodeIssuedWire.serializer(), element)
                ServerMessage.CodeIssued(wire.code, wire.camera_token, wire.expires_in)
            }
            "paired" -> {
                val wire = json.decodeFromJsonElement(PairedWire.serializer(), element)
                ServerMessage.Paired(wire.paired_at)
            }
            "peer_disconnected" -> ServerMessage.PeerDisconnected
            "error" -> {
                val wire = json.decodeFromJsonElement(ErrorWire.serializer(), element)
                ServerMessage.Error(wire.code, wire.message)
            }
            "pong" -> ServerMessage.Pong
            else -> ServerMessage.Unknown
        }
    } catch (e: Exception) {
        ServerMessage.Unknown
    }
}

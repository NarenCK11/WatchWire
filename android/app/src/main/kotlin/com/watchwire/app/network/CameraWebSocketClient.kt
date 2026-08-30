package com.watchwire.app.network

import android.util.Log
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.Proxy
import java.util.concurrent.TimeUnit

private const val TAG = "CameraWsClient"

/**
 * Makes a hand-typed server address usable. People reasonably type `192.168.1.6:8000`,
 * `http://192.168.1.6:8000`, or paste something with a trailing slash; all of those should
 * work rather than silently failing to connect.
 */
internal fun normalizeWsUrl(raw: String): String {
    val url = raw.trim().trimEnd('/')
    return when {
        url.startsWith("ws://", ignoreCase = true) || url.startsWith("wss://", ignoreCase = true) -> url
        url.startsWith("https://", ignoreCase = true) -> "wss://" + url.substring("https://".length)
        url.startsWith("http://", ignoreCase = true) -> "ws://" + url.substring("http://".length)
        // No scheme at all -- assume plain ws, which is the common LAN-development case.
        else -> "ws://$url"
    }
}

/** One WebSocket connection to /ws/camera. The repository creates a fresh instance for
 * each connection attempt and discards it on close/failure -- reconnection with backoff
 * is the repository's job, not this class's. */
class CameraWebSocketClient(
    private val baseWsUrl: String,
    private val cameraToken: String?,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen()
        fun onServerMessage(message: ServerMessage)
        fun onClosed()
        fun onFailure(t: Throwable)
    }

    private val json = Json { encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // This is a direct device-to-backend link to a URL the user explicitly configured
        // -- it must never be silently routed through a network-provided/auto-detected
        // proxy (e.g. one advertised via DHCP/WPAD on the local network).
        .proxy(Proxy.NO_PROXY)
        .build()

    private var webSocket: WebSocket? = null

    fun connect() {
        val url = buildString {
            append(normalizeWsUrl(baseWsUrl))
            append("/ws/camera")
            if (!cameraToken.isNullOrBlank()) {
                append("?camera_token=")
                append(cameraToken)
            }
        }
        Log.i(TAG, "Connecting to $url")

        // A user-entered URL can still be unparseable after normalization (e.g. stray
        // spaces). Report it through the normal failure path instead of throwing out of
        // connect(), which runs on the caller's thread and would take the app down.
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid server URL: $url", e)
            listener.onFailure(IllegalArgumentException("Invalid server address: $baseWsUrl", e))
            return
        }
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    listener.onServerMessage(ServerMessageParser.parse(text))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    listener.onFailure(t)
                }
            },
        )
    }

    fun sendMotionEvent(score: Float, timestampIso: String) {
        send(MotionEventOutbound.serializer(), MotionEventOutbound(score = score, timestamp = timestampIso))
    }

    fun sendMonitoringStarted() {
        send(SimpleTypeOutbound.serializer(), SimpleTypeOutbound("monitoring_started"))
    }

    fun sendMonitoringStopped() {
        send(SimpleTypeOutbound.serializer(), SimpleTypeOutbound("monitoring_stopped"))
    }

    private fun <T> send(serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        val socket = webSocket ?: return
        val ok = socket.send(json.encodeToString(serializer, value))
        if (!ok) {
            Log.w(TAG, "Failed to enqueue WebSocket message (socket likely closing/closed)")
        }
    }

    fun close() {
        webSocket?.close(1000, "client closing")
        webSocket = null
        client.dispatcher.executorService.shutdown()
    }
}

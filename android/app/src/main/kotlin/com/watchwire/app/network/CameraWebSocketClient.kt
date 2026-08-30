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
            append(baseWsUrl.trimEnd('/'))
            append("/ws/camera")
            if (!cameraToken.isNullOrBlank()) {
                append("?camera_token=")
                append(cameraToken)
            }
        }
        Log.i(TAG, "Connecting to $url")
        val request = Request.Builder().url(url).build()
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

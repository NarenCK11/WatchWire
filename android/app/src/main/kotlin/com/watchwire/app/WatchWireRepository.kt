package com.watchwire.app

import android.content.Context
import android.util.Log
import com.watchwire.app.network.CameraWebSocketClient
import com.watchwire.app.network.ServerMessage
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "WatchWireRepository"

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

sealed interface PairingState {
    data object AwaitingConnection : PairingState
    data class CodeReady(val code: String) : PairingState
    data class Paired(val pairedAt: Double?, val viewerConnected: Boolean = true) : PairingState
    data class Failed(val message: String) : PairingState
}

data class MotionEvent(val score: Float, val timestampMillis: Long)

/** Process-wide singleton owning the single WebSocket connection to the backend and all
 * pairing/monitoring state. Both MainActivity (UI) and MonitoringService (background
 * camera + motion detection) talk to this same instance, so the connection survives
 * exactly as long as the process does -- which is what lets monitoring keep running with
 * the screen off, as long as the foreground service keeps the process alive. */
object WatchWireRepository {
    private lateinit var appContext: Context
    private lateinit var prefs: Prefs
    private var initialized = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.AwaitingConnection)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private val _monitoringActive = MutableStateFlow(false)
    val monitoringActive: StateFlow<Boolean> = _monitoringActive.asStateFlow()

    private val _lastMotionEvent = MutableStateFlow<MotionEvent?>(null)
    val lastMotionEvent: StateFlow<MotionEvent?> = _lastMotionEvent.asStateFlow()

    private var wsClient: CameraWebSocketClient? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var manualClose = true

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        initialized = true
    }

    val wsBaseUrl: String get() = prefs.wsBaseUrl

    val sensitivity: Float get() = prefs.sensitivity
    fun setSensitivity(value: Float) {
        prefs.sensitivity = value.coerceIn(0f, 1f)
    }

    /** Changing the backend URL invalidates any existing pairing -- it's effectively a
     * different server. */
    fun updateWsBaseUrl(url: String) {
        prefs.wsBaseUrl = url
        prefs.cameraToken = null
        disconnect()
        connect()
    }

    fun connect() {
        if (_connectionStatus.value != ConnectionStatus.DISCONNECTED) return
        manualClose = false
        openSocket()
    }

    private fun openSocket() {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        val client = CameraWebSocketClient(
            baseWsUrl = prefs.wsBaseUrl,
            cameraToken = prefs.cameraToken,
            listener = object : CameraWebSocketClient.Listener {
                override fun onOpen() {
                    reconnectAttempt = 0
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    if (_monitoringActive.value) {
                        // Reconnected mid-monitoring-session: let the server know we're
                        // still live so the web UI doesn't show a stale "Idle" badge.
                        wsClient?.sendMonitoringStarted()
                    }
                }

                override fun onServerMessage(message: ServerMessage) {
                    handleServerMessage(message)
                }

                override fun onClosed() {
                    handleDisconnect()
                }

                override fun onFailure(t: Throwable) {
                    Log.w(TAG, "WebSocket failure", t)
                    handleDisconnect()
                }
            },
        )
        wsClient = client
        client.connect()
    }

    private fun handleServerMessage(message: ServerMessage) {
        when (message) {
            is ServerMessage.CodeIssued -> {
                prefs.cameraToken = message.cameraToken
                _pairingState.value = PairingState.CodeReady(message.code)
            }

            is ServerMessage.Paired -> {
                _pairingState.value = PairingState.Paired(message.pairedAt)
            }

            ServerMessage.PeerDisconnected -> {
                val current = _pairingState.value
                if (current is PairingState.Paired) {
                    _pairingState.value = current.copy(viewerConnected = false)
                }
            }

            is ServerMessage.Error -> {
                if (message.code == "SESSION_EXPIRED" || message.code == "INVALID_SESSION") {
                    prefs.cameraToken = null
                }
                _pairingState.value = PairingState.Failed(message.message)
            }

            ServerMessage.Pong, ServerMessage.Unknown -> Unit
        }
    }

    private fun handleDisconnect() {
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        wsClient = null
        if (manualClose) return
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val attempt = ++reconnectAttempt
        val delayMs = (1000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(15_000L)
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!manualClose) openSocket()
        }
    }

    fun disconnect() {
        manualClose = true
        reconnectJob?.cancel()
        wsClient?.close()
        wsClient = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _pairingState.value = PairingState.AwaitingConnection
    }

    fun setMonitoringActive(active: Boolean) {
        _monitoringActive.value = active
        if (active) wsClient?.sendMonitoringStarted() else wsClient?.sendMonitoringStopped()
    }

    fun reportMotion(score: Float) {
        val timestamp = Instant.now().toString()
        _lastMotionEvent.value = MotionEvent(score, System.currentTimeMillis())
        wsClient?.sendMotionEvent(score, timestamp)
    }
}

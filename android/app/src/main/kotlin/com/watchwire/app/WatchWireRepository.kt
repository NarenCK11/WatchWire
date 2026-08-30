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

    /** Why the last connection attempt failed, shown on the connecting screen so a wrong
     * address or an unreachable host is diagnosable from the device instead of needing adb. */
    private val _lastConnectionError = MutableStateFlow<String?>(null)
    val lastConnectionError: StateFlow<String?> = _lastConnectionError.asStateFlow()

    private var wsClient: CameraWebSocketClient? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var manualClose = true

    /** Incremented every time we open (or deliberately tear down) a socket. OkHttp delivers
     * onClosed/onFailure asynchronously, so a socket we already replaced can report its death
     * *after* its successor is live. Every callback carries the generation it was created
     * with and is ignored unless it still matches -- otherwise a stale close would null out
     * the current client and strand the UI on "Connecting to server...". */
    private var connectionGeneration = 0

    private val _wsBaseUrl = MutableStateFlow("")

    /** Observable so the UI reflects a URL change immediately; the authoritative copy lives
     * in SharedPreferences and is what the socket actually dials. */
    val wsBaseUrl: StateFlow<String> = _wsBaseUrl.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        _wsBaseUrl.value = prefs.wsBaseUrl
        initialized = true
    }

    val sensitivity: Float get() = prefs.sensitivity
    fun setSensitivity(value: Float) {
        prefs.sensitivity = value.coerceIn(0f, 1f)
    }

    /** Changing the backend URL invalidates any existing pairing -- it's effectively a
     * different server. */
    fun updateWsBaseUrl(url: String) {
        prefs.wsBaseUrl = url
        _wsBaseUrl.value = url
        prefs.cameraToken = null
        _lastConnectionError.value = null
        disconnect()
        connect()
    }

    fun connect() {
        if (_connectionStatus.value != ConnectionStatus.DISCONNECTED) return
        manualClose = false
        openSocket()
    }

    private fun openSocket() {
        val generation = ++connectionGeneration
        _connectionStatus.value = ConnectionStatus.CONNECTING

        lateinit var client: CameraWebSocketClient
        client = CameraWebSocketClient(
            baseWsUrl = prefs.wsBaseUrl,
            cameraToken = prefs.cameraToken,
            listener = object : CameraWebSocketClient.Listener {
                override fun onOpen() {
                    if (generation != connectionGeneration) return
                    reconnectAttempt = 0
                    _lastConnectionError.value = null
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    if (_monitoringActive.value) {
                        // Reconnected mid-monitoring-session: let the server know we're
                        // still live so the web UI doesn't show a stale "Idle" badge.
                        // Send on *our* socket, not the shared field, which may have moved on.
                        client.sendMonitoringStarted()
                    }
                }

                override fun onServerMessage(message: ServerMessage) {
                    if (generation != connectionGeneration) return
                    handleServerMessage(message)
                }

                override fun onClosed() {
                    if (generation != connectionGeneration) return
                    handleDisconnect()
                }

                override fun onFailure(t: Throwable) {
                    if (generation != connectionGeneration) {
                        Log.d(TAG, "Ignoring failure from superseded socket (gen $generation)", t)
                        return
                    }
                    Log.w(TAG, "WebSocket failure", t)
                    _lastConnectionError.value = describeFailure(t)
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

    /** Turns an OkHttp failure into something a person standing in front of the phone can act on. */
    private fun describeFailure(t: Throwable): String {
        val host = prefs.wsBaseUrl
        return when (t) {
            is IllegalArgumentException -> "Invalid server address. Expected something like ws://192.168.1.5:8000"
            is java.net.SocketTimeoutException ->
                "Timed out reaching $host. Check the phone and the server are on the same Wi-Fi, and that the server's firewall allows the port."
            is java.net.ConnectException ->
                "Couldn't connect to $host. Is the backend running with --host 0.0.0.0, and the port allowed through the firewall?"
            is java.net.UnknownHostException -> "Can't resolve the host in $host. Check the address."
            is javax.net.ssl.SSLException -> "TLS error talking to $host. Use ws:// for a plain local server, wss:// only behind HTTPS."
            else -> t.message?.take(160) ?: t.javaClass.simpleName
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
        // Retire the current generation so the socket we're about to close can't report its
        // (asynchronous) death after a replacement has already been opened.
        connectionGeneration++
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

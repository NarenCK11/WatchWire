package com.watchwire.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.watchwire.app.service.MonitoringService
import com.watchwire.app.ui.ConnectingScreen
import com.watchwire.app.ui.ErrorScreen
import com.watchwire.app.ui.MonitoringScreen
import com.watchwire.app.ui.PairingScreen
import com.watchwire.app.ui.PermissionScreen
import com.watchwire.app.ui.theme.WatchWireTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchWireTheme {
                WatchWireScreen()
            }
        }
    }
}

@Composable
private fun WatchWireScreen() {
    val context = LocalContext.current

    var hasCameraPermission by remember { mutableStateOf(hasCameraPermission(context)) }
    var hasNotificationPermission by remember { mutableStateOf(hasNotificationPermission(context)) }

    val permissionLauncher = rememberActivityResultLauncher { granted ->
        hasCameraPermission = granted[Manifest.permission.CAMERA] ?: hasCameraPermission
        hasNotificationPermission = granted[Manifest.permission.POST_NOTIFICATIONS] ?: hasNotificationPermission
    }

    if (!hasCameraPermission) {
        PermissionScreen(
            onRequestPermission = {
                permissionLauncher.launch(requiredPermissions())
            },
        )
        return
    }

    LaunchedEffect(Unit) {
        WatchWireRepository.connect()
    }

    val pairingState by WatchWireRepository.pairingState.collectAsState()
    val connectionStatus by WatchWireRepository.connectionStatus.collectAsState()
    val monitoringActive by WatchWireRepository.monitoringActive.collectAsState()
    val lastMotionEvent by WatchWireRepository.lastMotionEvent.collectAsState()
    var sensitivity by remember { mutableStateOf(WatchWireRepository.sensitivity) }

    val lastConnectionError by WatchWireRepository.lastConnectionError.collectAsState()
    val wsBaseUrl by WatchWireRepository.wsBaseUrl.collectAsState()

    when (val state = pairingState) {
        PairingState.AwaitingConnection -> ConnectingScreen(
            connectionStatus = connectionStatus,
            wsBaseUrl = wsBaseUrl,
            lastError = lastConnectionError,
            onUpdateServerUrl = { WatchWireRepository.updateWsBaseUrl(it) },
        )

        is PairingState.CodeReady -> PairingScreen(
            code = state.code,
            connectionStatus = connectionStatus,
            wsBaseUrl = wsBaseUrl,
            onUpdateServerUrl = { WatchWireRepository.updateWsBaseUrl(it) },
        )

        is PairingState.Paired -> MonitoringScreen(
            viewerConnected = state.viewerConnected,
            monitoringActive = monitoringActive,
            sensitivity = sensitivity,
            onSensitivityChange = {
                sensitivity = it
                WatchWireRepository.setSensitivity(it)
            },
            lastMotionEvent = lastMotionEvent,
            hasNotificationPermission = hasNotificationPermission,
            onRequestNotificationPermission = { permissionLauncher.launch(requiredPermissions()) },
            onStart = { MonitoringService.start(context) },
            onStop = { MonitoringService.stop(context) },
        )

        is PairingState.Failed -> ErrorScreen(
            message = state.message,
            onRetry = {
                WatchWireRepository.disconnect()
                WatchWireRepository.connect()
            },
        )
    }
}

@Composable
private fun rememberActivityResultLauncher(onResult: (Map<String, Boolean>) -> Unit) =
    rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = onResult,
    )

private fun requiredPermissions(): Array<String> {
    val perms = mutableListOf(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms += Manifest.permission.POST_NOTIFICATIONS
    }
    return perms.toTypedArray()
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

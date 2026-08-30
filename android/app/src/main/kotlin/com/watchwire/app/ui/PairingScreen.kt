package com.watchwire.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchwire.app.ConnectionStatus

@Composable
fun ConnectingScreen(connectionStatus: ConnectionStatus) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = connectionStatusLabel(connectionStatus),
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun PairingScreen(
    code: String,
    connectionStatus: ConnectionStatus,
    wsBaseUrl: String,
    onUpdateServerUrl: (String) -> Unit,
) {
    var editingUrl by remember { mutableStateOf(false) }
    var urlDraft by remember(wsBaseUrl) { mutableStateOf(wsBaseUrl) }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("📡 WatchWire", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Enter this code on your WatchWire web app to connect",
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = code,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 8.sp,
        )
        Text(
            text = connectionStatusLabel(connectionStatus),
            modifier = Modifier.padding(top = 24.dp),
            style = MaterialTheme.typography.bodySmall,
        )

        if (editingUrl) {
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                label = { Text("Server WebSocket URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
            Button(
                onClick = {
                    editingUrl = false
                    onUpdateServerUrl(urlDraft.trim())
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Save & Reconnect")
            }
        } else {
            TextButton(onClick = { editingUrl = true }, modifier = Modifier.padding(top = 24.dp)) {
                Text("Server: $wsBaseUrl (tap to edit)")
            }
        }
    }
}

internal fun connectionStatusLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.CONNECTED -> "Connected to server – waiting for pairing"
    ConnectionStatus.CONNECTING -> "Connecting to server…"
    ConnectionStatus.DISCONNECTED -> "Disconnected – retrying…"
}

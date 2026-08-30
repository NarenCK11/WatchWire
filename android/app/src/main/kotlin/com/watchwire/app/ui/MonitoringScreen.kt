package com.watchwire.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.watchwire.app.MotionEvent
import com.watchwire.app.ui.theme.DangerRed
import com.watchwire.app.ui.theme.SuccessGreen
import com.watchwire.app.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MonitoringScreen(
    viewerConnected: Boolean,
    monitoringActive: Boolean,
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
    lastMotionEvent: MotionEvent?,
    hasNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📡 WatchWire", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatusPill(label = "Viewer", active = viewerConnected, activeText = "Connected", inactiveText = "Disconnected")
            StatusPill(label = "Monitoring", active = monitoringActive, activeText = "LIVE", inactiveText = "Idle")
        }

        if (!viewerConnected) {
            Text(
                "The remote web viewer isn't connected right now. Motion events won't be delivered until it reconnects.",
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }

        if (!hasNotificationPermission) {
            Text(
                "Enable notifications so this app can keep monitoring reliably in the background.",
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onRequestNotificationPermission, modifier = Modifier.padding(top = 8.dp)) {
                Text("Enable Notifications")
            }
        }

        Column(modifier = Modifier.padding(top = 32.dp).fillMaxWidth()) {
            Text("Sensitivity", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = sensitivity,
                onValueChange = onSensitivityChange,
                enabled = !monitoringActive,
            )
            Text(
                if (monitoringActive) "Stop monitoring to adjust sensitivity" else "Higher sensitivity detects smaller movements",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }

        Column(modifier = Modifier.padding(top = 24.dp).fillMaxWidth()) {
            Text("Last Motion Event", style = MaterialTheme.typography.labelLarge)
            if (lastMotionEvent == null) {
                Text("No motion detected yet this session.", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            } else {
                val time = remember(lastMotionEvent.timestampMillis) {
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastMotionEvent.timestampMillis))
                }
                Text(
                    "Score ${(lastMotionEvent.score * 100).toInt()}% at $time",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Button(
            onClick = if (monitoringActive) onStop else onStart,
            modifier = Modifier.padding(top = 32.dp).fillMaxWidth(),
            colors = if (monitoringActive) {
                ButtonDefaults.buttonColors(containerColor = DangerRed)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(if (monitoringActive) "Stop Monitoring" else "Start Monitoring")
        }
    }
}

@Composable
private fun StatusPill(label: String, active: Boolean, activeText: String, inactiveText: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Text(
            if (active) activeText else inactiveText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (active) SuccessGreen else TextMuted,
        )
    }
}

package com.pocketssh.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketssh.app.ssh.HostKeyRequest

@Composable
fun HostKeyDialog(request: HostKeyRequest, onAnswer: (accepted: Boolean, remember: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Default.Security, null) },
        title = { Text("Unknown server key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Verify this fingerprint before connecting to ${request.host}:${request.port}.")
                Text(request.algorithm, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(request.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true, true) }) { Text("Trust and save") } },
        dismissButton = { TextButton(onClick = { onAnswer(false, false) }) { Text("Cancel") } },
    )
}

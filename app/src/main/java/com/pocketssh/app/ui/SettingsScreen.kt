package com.pocketssh.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketssh.app.MainViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, navigate: (String) -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var hasPin by remember { mutableStateOf(viewModel.hasPin()) }
    var hosts by remember { mutableStateOf(viewModel.knownHosts()) }
    var toast by remember { mutableStateOf<String?>(null) }

    var showExportPassphrase by remember { mutableStateOf(false) }
    var importPayload by remember { mutableStateOf<String?>(null) }
    var pendingExportPassphrase by remember { mutableStateOf<String?>(null) }

    toast?.let { current -> LaunchedEffect(current) { delay(3000); toast = null } }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val passphrase = pendingExportPassphrase
        pendingExportPassphrase = null
        if (uri != null && passphrase != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.exportBackup(passphrase).toByteArray(Charsets.UTF_8)) }
                toast = "Backup exported"
            }.onFailure { toast = "Export failed: ${it.message}" }
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { importPayload = it }
        }
    }

    if (showExportPassphrase) {
        PassphraseDialog(
            title = "Set an export passphrase",
            onDismiss = { showExportPassphrase = false },
            onConfirm = { passphrase ->
                showExportPassphrase = false
                pendingExportPassphrase = passphrase
                exportPicker.launch("pocketssh-backup.psb")
            },
        )
    }

    importPayload?.let { payload ->
        PassphraseDialog(
            title = "Enter the backup passphrase",
            onDismiss = { importPayload = null },
            onConfirm = { passphrase ->
                importPayload = null
                runCatching { viewModel.importBackup(payload, passphrase) }
                    .onSuccess { count -> toast = "Imported $count server(s)" }
                    .onFailure { toast = "Import failed — wrong passphrase or corrupted file" }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { navigate("servers") }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            toast?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("App lock", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    if (hasPin) "A PIN is set — PocketSSH locks whenever it's backgrounded." else "No PIN set — PocketSSH does not lock.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(9) },
                    label = { Text("New PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            viewModel.setPin(pin)
                            hasPin = true
                            pin = ""
                            toast = "PIN saved"
                        },
                        enabled = pin.isNotEmpty(),
                    ) { Text(if (hasPin) "Change PIN" else "Set PIN") }
                    if (hasPin) {
                        OutlinedButton(onClick = {
                            viewModel.clearPin()
                            hasPin = false
                            toast = "PIN removed"
                        }) { Text("Remove PIN") }
                    }
                }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Known host keys", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (hosts.isEmpty()) {
                    Text("No servers trusted yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                } else {
                    hosts.forEach { host ->
                        ListItem(
                            leadingContent = { Icon(Icons.Default.Key, null) },
                            headlineContent = { Text("${host.host}:${host.port}") },
                            supportingContent = { Text(host.fingerprint, fontSize = 11.sp) },
                            trailingContent = {
                                IconButton(onClick = {
                                    viewModel.forgetHost(host.host, host.port)
                                    hosts = viewModel.knownHosts()
                                }) { Icon(Icons.Default.Delete, "Forget") }
                            },
                        )
                    }
                }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backup", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    "Servers are encrypted with a passphrase you choose — keep it safe, it cannot be recovered.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { showExportPassphrase = true }) { Text("Export backup") }
                    OutlinedButton(onClick = { importPicker.launch(arrayOf("*/*")) }) { Text("Import backup") }
                }
            }
        }
    }
}

@Composable
private fun PassphraseDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Passphrase") },
                visualTransformation = PasswordVisualTransformation(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotEmpty()) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

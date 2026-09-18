package com.pocketssh.app.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketssh.app.MainViewModel
import com.pocketssh.app.data.AuthType
import com.pocketssh.app.data.ServerProfile
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(existing: ServerProfile?, viewModel: MainViewModel, navigate: (String) -> Unit) {
    val context = LocalContext.current
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember(existing?.id) { mutableStateOf(existing?.host.orEmpty()) }
    var port by remember(existing?.id) { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by remember(existing?.id) { mutableStateOf(existing?.username.orEmpty()) }
    var authType by remember(existing?.id) { mutableStateOf(existing?.authType ?: AuthType.PASSWORD) }
    var password by remember(existing?.id) { mutableStateOf(existing?.password.orEmpty()) }
    var privateKey by remember(existing?.id) { mutableStateOf(existing?.privateKey.orEmpty()) }
    var passphrase by remember(existing?.id) { mutableStateOf(existing?.keyPassphrase.orEmpty()) }
    var saveSecret by remember(existing?.id) { mutableStateOf(existing?.saveSecret ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) privateKey = context.readText(uri) ?: run { error = "Could not read this key"; "" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New server" else "Edit server") },
                navigationIcon = { IconButton(onClick = { navigate("servers") }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    val parsedPort = port.toIntOrNull()
                    when {
                        host.isBlank() -> error = "Host is required"
                        username.isBlank() -> error = "Username is required"
                        parsedPort == null || parsedPort !in 1..65535 -> error = "Port must be between 1 and 65535"
                        authType == AuthType.PRIVATE_KEY && privateKey.isBlank() -> error = "Choose a private key"
                        else -> {
                            val profile = ServerProfile(
                                id = existing?.id ?: UUID.randomUUID().toString(),
                                name = name.ifBlank { host }, host = host.trim(), port = parsedPort,
                                username = username.trim(), authType = authType, password = password,
                                privateKey = privateKey, keyPassphrase = passphrase, saveSecret = saveSecret,
                            )
                            viewModel.upsert(profile)
                            navigate("servers")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(52.dp),
            ) { Text("Save server") }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), label = { Text("Host or IP") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(username, { username = it }, Modifier.weight(1f), label = { Text("Username") }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.width(104.dp), label = { Text("Port") }, singleLine = true)
            }
            Text("Authentication", fontWeight = FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(authType == AuthType.PASSWORD, { authType = AuthType.PASSWORD }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Password") }
                SegmentedButton(authType == AuthType.PRIVATE_KEY, { authType = AuthType.PRIVATE_KEY }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Private key") }
            }
            if (authType == AuthType.PASSWORD) {
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            } else {
                OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Default.Key, null); Spacer(Modifier.width(8.dp)); Text(if (privateKey.isBlank()) "Choose private key" else "Private key loaded")
                }
                OutlinedTextField(passphrase, { passphrase = it }, Modifier.fillMaxWidth(), label = { Text("Key passphrase (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(saveSecret, { saveSecret = it })
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Remember credential")
                    Text("Encrypted with Android Keystore", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun Context.readText(uri: android.net.Uri): String? = runCatching {
    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()

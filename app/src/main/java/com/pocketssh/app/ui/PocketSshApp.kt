package com.pocketssh.app.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketssh.app.MainViewModel
import com.pocketssh.app.data.AuthType
import com.pocketssh.app.data.ServerProfile
import com.pocketssh.app.ssh.ConnectionState

@Composable
fun PocketSshApp(viewModel: MainViewModel, destination: String, navigate: (String) -> Unit) {
    PocketSshTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                destination == "servers" -> ServerList(viewModel, navigate)
                destination == "terminal" -> TerminalScreen(viewModel, navigate)
                destination.startsWith("edit:") -> {
                    val id = destination.removePrefix("edit:")
                    val profiles by viewModel.profiles.collectAsState()
                    ProfileEditor(profiles.firstOrNull { it.id == id }, viewModel, navigate)
                }
                else -> ProfileEditor(null, viewModel, navigate)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerList(viewModel: MainViewModel, navigate: (String) -> Unit) {
    val profiles by viewModel.profiles.collectAsState()
    val state by viewModel.session.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PocketSSH", fontWeight = FontWeight.Bold)
                        Text("Your servers", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navigate("new") }) { Icon(Icons.Default.Add, "Add server") }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Terminal, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("No servers yet", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("Add a host to start an SSH session", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state is ConnectionState.Connected) {
                    item {
                        Card(
                            Modifier.fillMaxWidth().clickable { navigate("terminal") },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .14f)),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Circle, null, Modifier.size(10.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text("Session active — tap to return", Modifier.weight(1f))
                                Icon(Icons.Default.ChevronRight, null)
                            }
                        }
                    }
                }
                items(profiles, key = { it.id }) { profile ->
                    ServerCard(
                        profile = profile,
                        onConnect = {
                            viewModel.session.connect(profile)
                            navigate("terminal")
                        },
                        onEdit = { navigate("edit:${profile.id}") },
                        onDelete = { viewModel.delete(profile) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerCard(profile: ServerProfile, onConnect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onConnect)) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) {
                Icon(Icons.Default.Dns, null, Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name.ifBlank { profile.host }, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("${profile.username}@${profile.host}:${profile.port}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditor(existing: ServerProfile?, viewModel: MainViewModel, navigate: (String) -> Unit) {
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
                                id = existing?.id ?: java.util.UUID.randomUUID().toString(),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalScreen(viewModel: MainViewModel, navigate: (String) -> Unit) {
    val state by viewModel.session.state.collectAsState()
    val rawOutput by viewModel.session.output.collectAsState()
    val hostKey by viewModel.session.hostKeyRequest.collectAsState()
    var command by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val output = remember(rawOutput) { renderTerminalText(rawOutput) }
    LaunchedEffect(output.length) { scroll.scrollTo(scroll.maxValue) }

    hostKey?.let { request ->
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
            confirmButton = { TextButton(onClick = { viewModel.session.answerHostKey(true, true) }) { Text("Trust and save") } },
            dismissButton = { TextButton(onClick = { viewModel.session.answerHostKey(false, false) }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF070A0D)).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navigate("servers") }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Servers") }
            Column(Modifier.weight(1f)) {
                Text(when (val s = state) { is ConnectionState.Connected -> s.title; is ConnectionState.Connecting -> "Connecting…"; else -> "Terminal" }, fontWeight = FontWeight.SemiBold)
                Text(stateLabel(state), color = stateColor(state), fontSize = 11.sp)
            }
            if (state !is ConnectionState.Disconnected) {
                TextButton(onClick = { viewModel.session.disconnect() }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = .08f))
        SelectionContainer {
            Text(
                text = output,
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(12.dp),
                color = Color(0xFFD7E0E8),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TerminalKey("Esc") { viewModel.session.send("\u001B") }
            TerminalKey("Tab") { viewModel.session.send("\t") }
            TerminalKey("↑") { viewModel.session.send("\u001B[A") }
            TerminalKey("↓") { viewModel.session.send("\u001B[B") }
            TerminalKey("←") { viewModel.session.send("\u001B[D") }
            TerminalKey("→") { viewModel.session.send("\u001B[C") }
            TerminalKey("Ctrl+C") { viewModel.session.send("\u0003") }
            TerminalKey("Ctrl+D") { viewModel.session.send("\u0004") }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a command") },
                singleLine = true,
                enabled = state is ConnectionState.Connected,
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { if (command.isNotEmpty()) { viewModel.session.send(command + "\n"); command = "" } },
                enabled = state is ConnectionState.Connected && command.isNotEmpty(),
                modifier = Modifier.size(52.dp),
            ) { Icon(Icons.Default.Send, "Send") }
        }
    }
}

@Composable
private fun TerminalKey(label: String, action: () -> Unit) {
    Surface(onClick = action, shape = RoundedCornerShape(7.dp), color = Color(0xFF19212A)) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

private fun stateLabel(state: ConnectionState) = when (state) {
    ConnectionState.Disconnected -> "Disconnected"
    ConnectionState.Connecting -> "Negotiating SSH"
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Failed -> state.message
}

@Composable
private fun stateColor(state: ConnectionState) = when (state) {
    is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
    is ConnectionState.Failed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun renderTerminalText(raw: String): String {
    val withoutOsc = raw.replace(Regex("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)"), "")
    val withoutCsi = withoutOsc.replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "")
    val lines = mutableListOf(StringBuilder())
    var column = 0
    withoutCsi.forEach { char ->
        when (char) {
            '\r' -> column = 0
            '\n' -> { lines += StringBuilder(); column = 0 }
            '\b' -> if (column > 0) { column--; if (column < lines.last().length) lines.last().deleteCharAt(column) }
            else -> if (char >= ' ' || char == '\t') {
                val line = lines.last()
                while (line.length < column) line.append(' ')
                if (column < line.length) line.setCharAt(column, char) else line.append(char)
                column++
            }
        }
    }
    return lines.joinToString("\n")
}

private fun Context.readText(uri: android.net.Uri): String? = runCatching {
    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()

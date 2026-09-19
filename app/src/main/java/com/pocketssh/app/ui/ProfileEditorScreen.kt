package com.pocketssh.app.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketssh.app.MainViewModel
import com.pocketssh.app.R
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

    // stringResource() only works inside composition, so callbacks that run later (click, file
    // picker result) capture these as plain values instead of calling it themselves.
    val errorKeyReadFailed = stringResource(R.string.error_key_read_failed)
    val errorHostRequired = stringResource(R.string.error_host_required)
    val errorUsernameRequired = stringResource(R.string.error_username_required)
    val errorPortRange = stringResource(R.string.error_port_range)
    val errorChooseKey = stringResource(R.string.error_choose_key)

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) privateKey = context.readText(uri) ?: run { error = errorKeyReadFailed; "" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) stringResource(R.string.profile_new_title) else stringResource(R.string.profile_edit_title)) },
                navigationIcon = { IconButton(onClick = { navigate("servers") }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    val parsedPort = port.toIntOrNull()
                    when {
                        host.isBlank() -> error = errorHostRequired
                        username.isBlank() -> error = errorUsernameRequired
                        parsedPort == null || parsedPort !in 1..65535 -> error = errorPortRange
                        authType == AuthType.PRIVATE_KEY && privateKey.isBlank() -> error = errorChooseKey
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
            ) { Text(stringResource(R.string.action_save_server)) }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedVisibility(
                visible = error != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.label_name)) }, singleLine = true)
            OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.label_host)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(username, { username = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.label_username)) }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.width(104.dp), label = { Text(stringResource(R.string.label_port)) }, singleLine = true)
            }
            Text(stringResource(R.string.label_authentication), fontWeight = FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(authType == AuthType.PASSWORD, { authType = AuthType.PASSWORD }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.auth_password)) }
                SegmentedButton(authType == AuthType.PRIVATE_KEY, { authType = AuthType.PRIVATE_KEY }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.auth_private_key)) }
            }
            AnimatedContent(
                targetState = authType,
                transitionSpec = {
                    (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 6 })
                        .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 6 })
                },
                label = "auth-fields",
            ) { type ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (type == AuthType.PASSWORD) {
                        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.auth_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    } else {
                        OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().height(52.dp)) {
                            Icon(Icons.Default.Key, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (privateKey.isBlank()) stringResource(R.string.action_choose_private_key) else stringResource(R.string.action_private_key_loaded))
                        }
                        OutlinedTextField(passphrase, { passphrase = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.label_key_passphrase)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(saveSecret, { saveSecret = it })
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.label_remember_credential))
                    Text(stringResource(R.string.desc_keystore_encrypted), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun Context.readText(uri: android.net.Uri): String? = runCatching {
    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()

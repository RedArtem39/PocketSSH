package com.pocketssh.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketssh.app.AppLocale
import com.pocketssh.app.MainViewModel
import com.pocketssh.app.R
import kotlinx.coroutines.delay

private data class AppLanguage(val tag: String?, val nativeName: String)

private val SupportedLanguages = listOf(
    AppLanguage(null, ""),
    AppLanguage("en", "English"),
    AppLanguage("ru", "Русский"),
    AppLanguage("uk", "Українська"),
    AppLanguage("es", "Español"),
    AppLanguage("de", "Deutsch"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, navigate: (String) -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var hasPin by remember { mutableStateOf(viewModel.hasPin()) }
    var hosts by remember { mutableStateOf(viewModel.knownHosts()) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var currentTag by remember { mutableStateOf(AppLocale.get(context)) }

    var showExportPassphrase by remember { mutableStateOf(false) }
    var importPayload by remember { mutableStateOf<String?>(null) }
    var pendingExportPassphrase by remember { mutableStateOf<String?>(null) }

    // stringResource() only works inside composition, so callbacks that run later (activity
    // result, network/file result lambdas) capture these as plain values instead of calling it
    // themselves.
    val toastBackupExported = stringResource(R.string.toast_backup_exported)
    val toastImportFailed = stringResource(R.string.toast_import_failed)

    toast?.let { current -> LaunchedEffect(current) { delay(3000); toast = null } }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val passphrase = pendingExportPassphrase
        pendingExportPassphrase = null
        if (uri != null && passphrase != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.exportBackup(passphrase).toByteArray(Charsets.UTF_8)) }
                toast = toastBackupExported
            }.onFailure { toast = context.getString(R.string.toast_export_failed, it.message) }
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { importPayload = it }
        }
    }

    if (showLanguagePicker) {
        LanguageDialog(
            currentTag = currentTag,
            onDismiss = { showLanguagePicker = false },
            onSelect = { tag ->
                showLanguagePicker = false
                currentTag = tag
                AppLocale.set(context, tag)
                (context as? Activity)?.recreate()
            },
        )
    }

    if (showExportPassphrase) {
        PassphraseDialog(
            title = stringResource(R.string.dialog_export_passphrase_title),
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
            title = stringResource(R.string.dialog_import_passphrase_title),
            onDismiss = { importPayload = null },
            onConfirm = { passphrase ->
                importPayload = null
                runCatching { viewModel.importBackup(payload, passphrase) }
                    .onSuccess { count -> toast = context.getString(R.string.toast_imported, count) }
                    .onFailure { toast = toastImportFailed }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = { navigate("servers") }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AnimatedVisibility(
                visible = toast != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Text(toast.orEmpty(), color = MaterialTheme.colorScheme.primary)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_app_lock), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    if (hasPin) stringResource(R.string.settings_pin_set_desc) else stringResource(R.string.settings_pin_not_set_desc),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(9) },
                    label = { Text(stringResource(R.string.label_new_pin)) },
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
                            toast = context.getString(R.string.toast_pin_saved)
                        },
                        enabled = pin.isNotEmpty(),
                    ) { Text(if (hasPin) stringResource(R.string.action_change_pin) else stringResource(R.string.action_set_pin)) }
                    if (hasPin) {
                        OutlinedButton(onClick = {
                            viewModel.clearPin()
                            hasPin = false
                            toast = context.getString(R.string.toast_pin_removed)
                        }) { Text(stringResource(R.string.action_remove_pin)) }
                    }
                }
                ListItem(
                    modifier = Modifier.clickable { navigate("lock_style") },
                    leadingContent = { Icon(Icons.Default.Palette, null) },
                    headlineContent = { Text(stringResource(R.string.lockstyle_title)) },
                    supportingContent = { Text(stringResource(R.string.lockstyle_entry_desc), fontSize = 12.sp) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                )
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_language), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                ListItem(
                    modifier = Modifier.padding(horizontal = 0.dp),
                    leadingContent = { Icon(Icons.Default.Language, null) },
                    headlineContent = { Text(languageDisplayName(currentTag)) },
                    trailingContent = { TextButton(onClick = { showLanguagePicker = true }) { Text(stringResource(R.string.action_edit)) } },
                )
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_known_hosts), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (hosts.isEmpty()) {
                    Text(stringResource(R.string.settings_no_hosts), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
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
                                }) { Icon(Icons.Default.Delete, stringResource(R.string.cd_forget_host)) }
                            },
                        )
                    }
                }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_backup), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    stringResource(R.string.settings_backup_desc),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { showExportPassphrase = true }) { Text(stringResource(R.string.action_export_backup)) }
                    OutlinedButton(onClick = { importPicker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.action_import_backup)) }
                }
            }
        }
    }
}

@Composable
private fun languageDisplayName(tag: String?): String {
    if (tag == null) return stringResource(R.string.settings_language_system_default)
    return SupportedLanguages.firstOrNull { it.tag == tag }?.nativeName ?: tag
}

@Composable
private fun LanguageDialog(currentTag: String?, onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column {
                SupportedLanguages.forEach { lang ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = lang.tag == currentTag, onClick = { onSelect(lang.tag) })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (lang.tag == null) stringResource(R.string.settings_language_system_default) else lang.nativeName,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
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
                label = { Text(stringResource(R.string.label_passphrase)) },
                visualTransformation = PasswordVisualTransformation(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotEmpty()) { Text(stringResource(R.string.action_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

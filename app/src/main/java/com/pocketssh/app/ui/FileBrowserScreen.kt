package com.pocketssh.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketssh.app.MainViewModel
import com.pocketssh.app.R
import com.pocketssh.app.ssh.RemoteEntry
import com.pocketssh.app.ssh.SftpState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(profileId: String, viewModel: MainViewModel, navigate: (String) -> Unit) {
    val profiles by viewModel.profiles.collectAsState()
    val profile = profiles.firstOrNull { it.id == profileId }
    val state by viewModel.sftp.state.collectAsState()
    val hostKey by viewModel.sftp.hostKeyRequest.collectAsState()
    val context = LocalContext.current

    var pendingDownload by remember { mutableStateOf<RemoteEntry?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profileId) { profile?.let { viewModel.sftp.open(it) } }
    DisposableEffect(Unit) { onDispose { viewModel.sftp.close() } }
    toast?.let { current -> LaunchedEffect(current) { delay(2500); toast = null } }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = queryFileName(context, uri) ?: "upload.bin"
            context.contentResolver.openInputStream(uri)?.let { input ->
                viewModel.sftp.upload(name, input) { result ->
                    toast = if (result.isSuccess) context.getString(R.string.toast_uploaded, name)
                    else context.getString(R.string.toast_upload_failed, result.exceptionOrNull()?.message)
                }
            }
        }
    }
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) {
            context.contentResolver.openOutputStream(uri)?.let { output ->
                viewModel.sftp.download(entry, output) { result ->
                    toast = if (result.isSuccess) context.getString(R.string.toast_downloaded, entry.name)
                    else context.getString(R.string.toast_download_failed, result.exceptionOrNull()?.message)
                }
            }
        }
    }

    hostKey?.let { request -> HostKeyDialog(request, onAnswer = viewModel.sftp::answerHostKey) }

    if (showNewFolder) {
        NewFolderDialog(
            onDismiss = { showNewFolder = false },
            onCreate = { name ->
                showNewFolder = false
                viewModel.sftp.mkdir(name) { result ->
                    if (result.isFailure) toast = context.getString(R.string.toast_mkdir_failed, result.exceptionOrNull()?.message)
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(profile?.name ?: stringResource(R.string.files_title_default), fontWeight = FontWeight.Bold)
                        val path = (state as? SftpState.Ready)?.path
                        if (path != null) Text(path, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = { navigate("servers") }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
                actions = {
                    IconButton(onClick = { showNewFolder = true }, enabled = state is SftpState.Ready) { Icon(Icons.Default.CreateNewFolder, stringResource(R.string.cd_new_folder)) }
                    IconButton(onClick = { viewModel.sftp.refresh() }, enabled = state is SftpState.Ready) { Icon(Icons.Default.Refresh, stringResource(R.string.cd_refresh)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { uploadPicker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.UploadFile, stringResource(R.string.cd_upload)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(
                visible = toast != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Text(toast.orEmpty(), Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
            Crossfade(targetState = state, label = "sftp-state") { s ->
                when (s) {
                    SftpState.Disconnected -> {}
                    SftpState.Connecting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is SftpState.Failed -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.files_error_title), fontWeight = FontWeight.SemiBold)
                            Text(s.message, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { profile?.let { viewModel.sftp.open(it) } }) { Text(stringResource(R.string.action_retry)) }
                        }
                    }
                    is SftpState.Ready -> {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                            if (s.path != "/") {
                                item(key = "up") {
                                    ListItem(
                                        headlineContent = { Text("..") },
                                        leadingContent = { Icon(Icons.Default.Folder, null) },
                                        modifier = Modifier.animateItem().clickable { viewModel.sftp.up() },
                                    )
                                }
                            }
                            items(s.entries, key = { it.path }) { entry ->
                                RemoteEntryRow(
                                    entry = entry,
                                    modifier = Modifier.animateItem(),
                                    onOpen = { if (entry.isDirectory) viewModel.sftp.navigate(entry.path) },
                                    onDownload = {
                                        pendingDownload = entry
                                        downloadPicker.launch(entry.name)
                                    },
                                    onDelete = {
                                        viewModel.sftp.delete(entry) { result ->
                                            if (result.isFailure) toast = context.getString(R.string.toast_delete_failed, result.exceptionOrNull()?.message)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteEntryRow(
    entry: RemoteEntry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.files_delete_title, entry.name)) },
            text = { Text(if (entry.isDirectory) stringResource(R.string.files_delete_folder_text) else stringResource(R.string.files_delete_file_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    ListItem(
        headlineContent = { Text(entry.name) },
        supportingContent = { if (!entry.isDirectory) Text(formatSize(entry.size)) },
        leadingContent = { Icon(if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.cd_options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!entry.isDirectory) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_download)) }, onClick = { menu = false; onDownload() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        },
        modifier = modifier.clickable(onClick = onOpen),
    )
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_new_folder_title)) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.label_name)) }) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun queryFileName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

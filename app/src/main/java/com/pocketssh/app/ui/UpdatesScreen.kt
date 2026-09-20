package com.pocketssh.app.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketssh.app.MainViewModel
import com.pocketssh.app.R
import com.pocketssh.app.data.StoredFile
import com.pocketssh.app.update.ReleaseInfo
import com.pocketssh.app.update.UpdateState
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(viewModel: MainViewModel, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.updates.state.collectAsState()

    var folderName by remember { mutableStateOf(viewModel.storageFolder.displayName()) }
    var backups by remember { mutableStateOf(viewModel.autoBackup.list()) }
    var archived by remember { mutableStateOf(viewModel.updates.archivedApks()) }
    var toast by remember { mutableStateOf<String?>(null) }
    var rollbackTarget by remember { mutableStateOf<StoredFile?>(null) }
    var includePrereleases by remember { mutableStateOf(false) }

    fun refreshFolderState() {
        folderName = viewModel.storageFolder.displayName()
        backups = viewModel.autoBackup.list()
        archived = viewModel.updates.archivedApks()
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            viewModel.rememberStorageFolder(uri)
            refreshFolderState()
        }
    }

    toast?.let { current -> LaunchedEffect(current) { kotlinx.coroutines.delay(3000); toast = null } }

    // Check once on arrival so the screen is never just an inert button.
    LaunchedEffect(Unit) {
        if (state is UpdateState.Idle) viewModel.updates.check(includePrereleases)
    }

    rollbackTarget?.let { target ->
        RollbackDialog(
            file = target,
            hasBackup = backups.isNotEmpty(),
            onDismiss = { rollbackTarget = null },
            onUninstall = { context.startActivity(viewModel.updates.uninstallIntent()) },
            onInstall = {
                if (!viewModel.updates.installFromFolder(target)) {
                    toast = context.getString(R.string.updates_install_failed)
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.updates_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigate("settings") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AnimatedVisibility(toast != null, enter = fadeIn(), exit = fadeOut()) {
                Text(toast.orEmpty(), color = MaterialTheme.colorScheme.primary)
            }

            Text(
                stringResource(R.string.updates_installed_version, viewModel.updates.currentVersionName()),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )

            UpdateStatusCard(
                state = state,
                onCheck = { scope.launch { viewModel.updates.check(includePrereleases) } },
                onDownload = { release ->
                    viewModel.scheduleAutoBackup()
                    scope.launch { viewModel.updates.download(release) }
                },
                onInstall = { file ->
                    if (!viewModel.updates.install(file)) {
                        toast = context.getString(R.string.updates_install_failed)
                    }
                    refreshFolderState()
                },
                onAllowInstalls = { context.startActivity(viewModel.updates.installPermissionIntent()) },
                canInstall = viewModel.updates.canRequestInstalls(),
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.updates_prereleases))
                    Text(
                        stringResource(R.string.updates_prereleases_desc),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = includePrereleases,
                    onCheckedChange = {
                        includePrereleases = it
                        scope.launch { viewModel.updates.check(it) }
                    },
                )
            }

            HorizontalDivider()

            FolderSection(
                folderName = folderName,
                backupCount = backups.size,
                latest = backups.firstOrNull(),
                onChoose = { folderPicker.launch(null) },
                onForget = {
                    viewModel.forgetStorageFolder()
                    refreshFolderState()
                },
                onBackupNow = {
                    viewModel.scheduleAutoBackup()
                    toast = context.getString(R.string.updates_backup_started)
                },
            )

            HorizontalDivider()

            RollbackSection(
                archived = archived,
                onPick = { rollbackTarget = it },
            )
        }
    }
}

@Composable
private fun UpdateStatusCard(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: (ReleaseInfo) -> Unit,
    onInstall: (java.io.File) -> Unit,
    onAllowInstalls: () -> Unit,
    canInstall: Boolean,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                is UpdateState.Idle -> Button(onClick = onCheck) { Text(stringResource(R.string.updates_check_now)) }

                is UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.updates_checking))
                }

                is UpdateState.UpToDate -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.updates_up_to_date))
                    }
                    OutlinedButton(onClick = onCheck) { Text(stringResource(R.string.updates_check_again)) }
                }

                is UpdateState.Available -> {
                    ReleaseHeadline(state.release)
                    if (!canInstall) {
                        PermissionNotice(onAllowInstalls)
                    }
                    Button(onClick = { onDownload(state.release) }) {
                        Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.updates_download, formatSize(state.release.apkSize)))
                    }
                }

                is UpdateState.Downloading -> {
                    Text(stringResource(R.string.updates_downloading, state.release.version.raw), fontWeight = FontWeight.SemiBold)
                    val fraction = if (state.total > 0) state.downloaded.toFloat() / state.total else 0f
                    if (state.total > 0) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${formatSize(state.downloaded)} / ${formatSize(state.total)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

                is UpdateState.ReadyToInstall -> {
                    ReleaseHeadline(state.release)
                    if (!canInstall) PermissionNotice(onAllowInstalls)
                    Text(
                        stringResource(R.string.updates_ready_note),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { onInstall(state.file) }, enabled = canInstall) {
                        Text(stringResource(R.string.updates_install))
                    }
                }

                is UpdateState.Failed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(10.dp))
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = onCheck) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
    }
}

/** Release notes run to dozens of lines, so they start collapsed — otherwise the download
 *  button they belong to is pushed several screens down and the card reads as a wall of text. */
@Composable
private fun ReleaseHeadline(release: ReleaseInfo) {
    var expanded by remember(release.tag) { mutableStateOf(false) }
    Text(release.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    if (release.notes.isNotBlank()) {
        // Release notes are markdown. Rather than pull in a renderer for one screen, the few
        // constructs actually used here are reduced to plain text.
        val text = remember(release.tag) { plainText(release.notes) }
        Text(
            text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else CollapsedNoteLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (expanded || text.lineSequence().count() > CollapsedNoteLines) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(if (expanded) R.string.updates_show_less else R.string.updates_show_all))
            }
        }
    }
}

private const val CollapsedNoteLines = 8

@Composable
private fun PermissionNotice(onAllow: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.updates_permission_needed),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onAllow) { Text(stringResource(R.string.updates_allow_installs)) }
    }
}

@Composable
private fun FolderSection(
    folderName: String?,
    backupCount: Int,
    latest: StoredFile?,
    onChoose: () -> Unit,
    onForget: () -> Unit,
    onBackupNow: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.updates_folder), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            stringResource(R.string.updates_folder_desc),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (folderName == null) {
            Button(onClick = onChoose) {
                Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.updates_choose_folder))
            }
        } else {
            ListItem(
                leadingContent = { Icon(Icons.Default.Folder, null) },
                headlineContent = { Text(folderName) },
                supportingContent = {
                    Text(
                        if (latest == null) {
                            stringResource(R.string.updates_no_backups)
                        } else {
                            stringResource(R.string.updates_backup_count, backupCount, formatTime(latest.modifiedAt))
                        },
                        fontSize = 12.sp,
                    )
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBackupNow) { Text(stringResource(R.string.updates_backup_now)) }
                OutlinedButton(onClick = onChoose) { Text(stringResource(R.string.action_edit)) }
                TextButton(onClick = onForget) { Text(stringResource(R.string.updates_forget_folder)) }
            }
        }
    }
}

@Composable
private fun RollbackSection(archived: List<StoredFile>, onPick: (StoredFile) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.updates_rollback), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            stringResource(R.string.updates_rollback_desc),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (archived.isEmpty()) {
            Text(
                stringResource(R.string.updates_no_archived),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            archived.forEach { file ->
                ListItem(
                    leadingContent = { Icon(Icons.Default.History, null) },
                    headlineContent = { Text(file.name.removePrefix("pocketssh-apk-")) },
                    supportingContent = { Text("${formatSize(file.size)} · ${formatTime(file.modifiedAt)}", fontSize = 12.sp) },
                    trailingContent = {
                        TextButton(onClick = { onPick(file) }) { Text(stringResource(R.string.updates_use)) }
                    },
                )
            }
        }
    }
}

/**
 * Walks through a downgrade. Android refuses to install an APK whose version code is lower than
 * the installed one, and there is no way around that from an ordinary app — so the only route is
 * uninstall, then install. Uninstalling destroys the Keystore key that protects the profiles,
 * which is exactly why the auto backup exists and why this dialog refuses to start without one.
 */
@Composable
private fun RollbackDialog(
    file: StoredFile,
    hasBackup: Boolean,
    onDismiss: () -> Unit,
    onUninstall: () -> Unit,
    onInstall: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Restore, null) },
        title = { Text(stringResource(R.string.updates_rollback_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.updates_rollback_steps, file.name.removePrefix("pocketssh-apk-")), fontSize = 13.sp)
                if (!hasBackup) {
                    Text(
                        stringResource(R.string.updates_rollback_no_backup),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = onUninstall, enabled = hasBackup) {
                    Text(stringResource(R.string.updates_step_uninstall))
                }
                TextButton(onClick = onInstall, enabled = hasBackup) {
                    Text(stringResource(R.string.updates_step_install))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Reduces the markdown that actually appears in these release notes — headings, bullets, bold
 * and inline code — to readable plain text. Anything else is left as written.
 */
private fun plainText(markdown: String): String = markdown
    .lineSequence()
    .map { line ->
        line.trimEnd()
            .replace(Regex("^#{1,6}\\s*"), "")
            .replace(Regex("^[-*]\\s+"), "• ")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("`(.+?)`"), "$1")
    }
    .joinToString("\n")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

/**
 * Offered on the empty server list. A fresh install cannot tell itself apart from a reinstall —
 * uninstalling wipes every preference, including any memory of the backup folder — so rather
 * than guess, the empty state simply offers the route back, and picking the folder restores in
 * the same step.
 */
@Composable
fun RestoreBanner(viewModel: MainViewModel, context: Context, modifier: Modifier = Modifier) {
    val restorable by viewModel.restorable.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun finish(count: Int) {
        busy = false
        message = if (count > 0) {
            context.getString(R.string.restore_done, count)
        } else {
            context.getString(R.string.restore_nothing_found)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            busy = true
            viewModel.rememberStorageFolder(uri, ::finish)
        }
    }

    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (message != null) {
                Text(message.orEmpty(), color = MaterialTheme.colorScheme.primary)
            } else {
                Text(
                    stringResource(if (restorable) R.string.restore_found_title else R.string.restore_offer_title),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(if (restorable) R.string.restore_found_desc else R.string.restore_offer_desc),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (restorable) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                viewModel.restoreFromAutoBackup(::finish)
                            },
                        ) { Text(stringResource(R.string.restore_action)) }
                        TextButton(onClick = { folderPicker.launch(null) }) {
                            Text(stringResource(R.string.restore_other_folder))
                        }
                    } else {
                        Button(enabled = !busy, onClick = { folderPicker.launch(null) }) {
                            Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.restore_choose_folder))
                        }
                    }
                }
            }
            if (busy) {
                Box(Modifier.fillMaxWidth().height(4.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        }
    }
}

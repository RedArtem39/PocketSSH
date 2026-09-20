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
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketssh.app.MainViewModel
import com.pocketssh.app.R
import com.pocketssh.app.data.StoredFile
import com.pocketssh.app.update.InstallResult
import com.pocketssh.app.update.InstallStatusReceiver
import com.pocketssh.app.update.ReleaseInfo
import com.pocketssh.app.update.UpdateState
import com.pocketssh.app.update.Version
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What one read of the backup folder yields; held as a unit so the screen reads it once. */
private data class FolderSnapshot(
    val name: String? = null,
    val backups: List<StoredFile> = emptyList(),
    val archived: List<StoredFile> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(viewModel: MainViewModel, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.updates.state.collectAsState()

    // Reading the folder means a ContentProvider round trip per entry. Doing that during
    // composition — three separate walks, once per value — is what froze the screen on entry, so
    // it is loaded once, in the background, into a single snapshot.
    var folder by remember { mutableStateOf(FolderSnapshot()) }
    var toast by remember { mutableStateOf<String?>(null) }
    var rollbackTarget by remember { mutableStateOf<StoredFile?>(null) }
    var includePrereleases by remember { mutableStateOf(false) }
    val profiles by viewModel.profiles.collectAsState()
    var helperBusy by remember { mutableStateOf(false) }
    // Re-read on resume like the install permission: the helper is installed from inside this
    // screen, and the dialog has to notice once it lands.
    var hasRecovery by remember { mutableStateOf(viewModel.updates.isRecoveryInstalled()) }

    // Reported by the system after the session is committed, so it arrives well after the call
    // that started it has returned. Android's own wording is what tells the user which of the
    // two rollback routes they have ended up in.
    val installFailure by InstallStatusReceiver.lastFailure.collectAsState()
    var installError by remember { mutableStateOf<String?>(null) }
    var busyVersion by remember { mutableStateOf<String?>(null) }
    val releases by viewModel.updates.releases.collectAsState()
    LaunchedEffect(installFailure) { installFailure?.let { installError = it } }

    // Re-read on every resume rather than once per composition: granting the permission happens
    // in a system screen, and coming back from it used to leave the Install button greyed out
    // with no hint that anything had changed.
    var canInstall by remember { mutableStateOf(viewModel.updates.canRequestInstalls()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canInstall = viewModel.updates.canRequestInstalls()
                hasRecovery = viewModel.updates.isRecoveryInstalled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun report(result: InstallResult) {
        toast = when (result) {
            is InstallResult.Started -> null
            is InstallResult.NeedsPermission -> context.getString(R.string.updates_permission_needed)
            is InstallResult.Failed -> context.getString(R.string.updates_install_failed_detail, result.message)
        }
    }

    fun reloadFolder() {
        scope.launch {
            folder = withContext(Dispatchers.IO) {
                FolderSnapshot(
                    name = viewModel.storageFolder.displayName(),
                    backups = viewModel.autoBackup.list(),
                    archived = viewModel.updates.archivedApks(),
                )
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.rememberStorageFolder(uri) { reloadFolder() }
    }

    toast?.let { current -> LaunchedEffect(current) { kotlinx.coroutines.delay(3000); toast = null } }

    // Check once on arrival so the screen is never just an inert button.
    LaunchedEffect(Unit) {
        if (state is UpdateState.Idle) viewModel.updates.check(includePrereleases)
    }

    // Keep a copy of whatever is running, so the version now installed is always one of the
    // versions that can be returned to. archiveCurrentApk() does its own folder checks on IO and
    // returns false when there was nothing to do, so this costs one background pass.
    LaunchedEffect(Unit) {
        folder = withContext(Dispatchers.IO) {
            FolderSnapshot(
                name = viewModel.storageFolder.displayName(),
                backups = viewModel.autoBackup.list(),
                archived = viewModel.updates.archivedApks(),
            )
        }
        if (viewModel.updates.archiveCurrentApk()) reloadFolder()
    }

    // The download finishes in about a second on wifi, so waiting for a second tap made the whole
    // thing look like nothing had happened. Going straight to the system installer means the
    // confirmation dialog is the feedback. Keyed on the file so a dismissed dialog is not
    // immediately reopened — the button below is there for a deliberate retry.
    var autoLaunched by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state, canInstall) {
        val ready = state as? UpdateState.ReadyToInstall ?: return@LaunchedEffect
        if (!canInstall || autoLaunched == ready.file.name) return@LaunchedEffect
        autoLaunched = ready.file.name
        report(viewModel.updates.install(ready.file))
        reloadFolder()
    }

    rollbackTarget?.let { target ->
        RollbackDialog(
            file = target,
            // Nothing to lose is as safe as having a backup: with no servers stored there is
            // nothing an uninstall could destroy, and blocking the rollback then is just noise.
            hasBackup = folder.backups.isNotEmpty() || profiles.isEmpty(),
            hasRecovery = hasRecovery,
            helperBusy = helperBusy,
            folderName = folder.name,
            installError = installError,
            onDismiss = {
                rollbackTarget = null
                installError = null
            },
            onDirectInstall = {
                installError = null
                // Streams the APK into the install session off the main thread; doing it inline
                // was what made this button hang before the dialog appeared.
                scope.launch { report(viewModel.updates.installFromFolder(target)) }
            },
            onUninstall = { context.startActivity(viewModel.updates.uninstallIntent()) },
            onRollback = {
                rollbackTarget = null
                report(viewModel.updates.launchRecovery(target))
            },
            onInstallHelper = {
                val release = (state as? UpdateState.Available)?.release
                    ?: (state as? UpdateState.ReadyToInstall)?.release
                    ?: viewModel.updates.releases.value.firstOrNull()
                if (release == null) {
                    toast = context.getString(R.string.updates_helper_needs_check)
                } else {
                    helperBusy = true
                    scope.launch {
                        report(viewModel.updates.installRecovery(release))
                        helperBusy = false
                    }
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
                    scope.launch {
                        report(viewModel.updates.install(file))
                        reloadFolder()
                    }
                },
                onAllowInstalls = { context.startActivity(viewModel.updates.installPermissionIntent()) },
                canInstall = canInstall,
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
                folderName = folder.name,
                backupCount = folder.backups.size,
                latest = folder.backups.firstOrNull(),
                onChoose = { folderPicker.launch(null) },
                onForget = {
                    viewModel.forgetStorageFolder()
                    reloadFolder()
                },
                onBackupNow = {
                    viewModel.scheduleAutoBackup()
                    toast = context.getString(R.string.updates_backup_started)
                },
            )

            HorizontalDivider()

            VersionLog(
                releases = releases,
                archived = folder.archived,
                currentVersion = viewModel.updates.currentVersionName(),
                busyVersion = busyVersion,
                archivedNameFor = { viewModel.updates.archivedCopyOf(it, folder.archived) },
                onPick = { release, saved ->
                    if (saved != null) {
                        rollbackTarget = saved
                    } else {
                        // Not in the folder yet, so fetch it first; it lands in the folder on the
                        // way through and is available offline from then on.
                        busyVersion = release.version.raw
                        scope.launch {
                            viewModel.scheduleAutoBackup()
                            report(viewModel.updates.installVersion(release))
                            busyVersion = null
                            reloadFolder()
                        }
                    }
                },
                onPickLocal = { rollbackTarget = it },
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
                            "${(fraction * 100).toInt()}%  ·  ${formatSize(state.downloaded)} / ${formatSize(state.total)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

                is UpdateState.ReadyToInstall -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.updates_downloaded, state.release.version.raw),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (!canInstall) PermissionNotice(onAllowInstalls)
                    Text(
                        stringResource(R.string.updates_ready_note),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Deliberately always enabled: a greyed-out button explains nothing, whereas
                    // pressing this one says exactly what is missing.
                    Button(onClick = { onInstall(state.file) }) {
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

/**
 * First release whose version code is the frozen constant. Anything earlier still has a
 * sequential code and cannot be installed over a later build without uninstalling first.
 */
private val FrozenCodeSince = Version.parse("0.6.0")

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

/**
 * Every published version, in one list. Entries already sitting in the backup folder install
 * without a network; the rest are fetched when picked.
 *
 * This used to list only what happened to be in the folder, which meant a version installed any
 * way other than through this screen never appeared — including, often, the one running.
 */
@Composable
private fun VersionLog(
    releases: List<ReleaseInfo>,
    archived: List<StoredFile>,
    currentVersion: String,
    busyVersion: String?,
    archivedNameFor: (ReleaseInfo) -> StoredFile?,
    onPick: (ReleaseInfo, StoredFile?) -> Unit,
    onPickLocal: (StoredFile) -> Unit,
) {
    // Anything in the folder that no release accounts for — a sideloaded build, or a release
    // since deleted — still belongs in the list; it is installable all the same.
    val orphans = archived.filter { stored -> releases.none { archivedNameFor(it)?.name == stored.name } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.updates_rollback), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            stringResource(R.string.updates_versions_desc),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (releases.isEmpty() && orphans.isEmpty()) {
            Text(
                stringResource(R.string.updates_no_archived),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        releases.forEach { release ->
            val saved = archivedNameFor(release)
            val installed = release.version.raw == currentVersion
            val busy = busyVersion == release.version.raw
            ListItem(
                leadingContent = {
                    Icon(
                        if (saved != null) Icons.Default.Save else Icons.Default.CloudDownload,
                        null,
                        tint = if (installed) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                },
                headlineContent = { Text(release.title) },
                supportingContent = {
                    val where = when {
                        installed -> stringResource(R.string.updates_version_installed)
                        saved != null -> stringResource(R.string.updates_version_offline, formatSize(saved.size))
                        else -> stringResource(R.string.updates_version_downloads, formatSize(release.apkSize))
                    }
                    // Releases from before the version code was frozen still carry sequential
                    // codes, so Android refuses to install them over this one. Saying so here
                    // beats letting the attempt fail and explaining afterwards.
                    val legacy = !installed && release.version < FrozenCodeSince
                    Text(
                        if (legacy) "$where · " + stringResource(R.string.updates_version_needs_uninstall) else where,
                        fontSize = 12.sp,
                        color = if (legacy) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current,
                    )
                },
                trailingContent = {
                    when {
                        installed -> Unit
                        busy -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else -> TextButton(onClick = { onPick(release, saved) }) {
                            Text(stringResource(R.string.updates_use))
                        }
                    }
                },
            )
        }
        orphans.forEach { file ->
            ListItem(
                leadingContent = { Icon(Icons.Default.Save, null) },
                headlineContent = { Text(file.name.removePrefix("pocketssh-apk-")) },
                supportingContent = {
                    Text("${formatSize(file.size)} · ${formatTime(file.modifiedAt)}", fontSize = 12.sp)
                },
                trailingContent = {
                    TextButton(onClick = { onPickLocal(file) }) { Text(stringResource(R.string.updates_use)) }
                },
            )
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
    hasRecovery: Boolean,
    helperBusy: Boolean,
    folderName: String?,
    installError: String?,
    onDismiss: () -> Unit,
    onUninstall: () -> Unit,
    onDirectInstall: () -> Unit,
    onRollback: () -> Unit,
    onInstallHelper: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Restore, null) },
        title = { Text(stringResource(R.string.updates_rollback_title, file.name.removePrefix("pocketssh-apk-"))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.updates_rollback_direct), fontSize = 13.sp)
                // Only reachable for builds published before the version code was frozen, which
                // is the one case Android still refuses outright.
                installError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    Text(
                        stringResource(
                            if (hasRecovery) R.string.updates_rollback_with_helper else R.string.updates_rollback_steps,
                            file.name.removePrefix("pocketssh-apk-"),
                            folderName ?: "—",
                        ),
                        fontSize = 13.sp,
                    )
                }
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
                // The ordinary route now: the version code is frozen, so this is a plain install
                // over the top that keeps every setting. Only a build from before the freeze
                // falls through to the options below.
                TextButton(onClick = onDirectInstall) {
                    Text(stringResource(R.string.updates_rollback_now))
                }
                if (installError != null) {
                    if (hasRecovery) {
                        TextButton(onClick = onRollback, enabled = hasBackup) {
                            Text(stringResource(R.string.updates_rollback_via_helper))
                        }
                    } else {
                        TextButton(onClick = onInstallHelper, enabled = !helperBusy) {
                            Text(stringResource(R.string.updates_install_helper))
                        }
                        TextButton(onClick = onUninstall, enabled = hasBackup) {
                            Text(stringResource(R.string.updates_step_uninstall))
                        }
                    }
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

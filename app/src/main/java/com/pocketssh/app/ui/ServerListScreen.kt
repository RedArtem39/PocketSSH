package com.pocketssh.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketssh.app.MainViewModel
import com.pocketssh.app.R
import com.pocketssh.app.data.ServerProfile
import com.pocketssh.app.ssh.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(viewModel: MainViewModel, navigate: (String) -> Unit) {
    val profiles by viewModel.profiles.collectAsState()
    val state by viewModel.session.state.collectAsState()
    var pendingConnect by remember { mutableStateOf<ServerProfile?>(null) }

    pendingConnect?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingConnect = null },
            title = { Text(stringResource(R.string.servers_replace_session_title)) },
            text = { Text(stringResource(R.string.servers_replace_session_text, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingConnect = null
                    viewModel.session.connect(target)
                    navigate("terminal")
                }) { Text(stringResource(R.string.action_connect)) }
            },
            dismissButton = { TextButton(onClick = { pendingConnect = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.servers_subtitle), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { navigate("settings") }) { Icon(Icons.Default.Settings, stringResource(R.string.cd_settings)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navigate("new") }) { Icon(Icons.Default.Add, stringResource(R.string.cd_add_server)) }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Crossfade(targetState = profiles.isEmpty(), label = "server-list") { empty ->
            if (empty) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Terminal, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.servers_empty_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.servers_empty_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state is ConnectionState.Connected) {
                        item(key = "active-session") {
                            Card(
                                Modifier.fillMaxWidth().animateItem().clickable { navigate("terminal") },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .14f)),
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Circle, null, Modifier.size(10.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Text(stringResource(R.string.servers_session_active), Modifier.weight(1f))
                                    Icon(Icons.Default.ChevronRight, null)
                                }
                            }
                        }
                    }
                    items(profiles, key = { it.id }) { profile ->
                        ServerCard(
                            profile = profile,
                            modifier = Modifier.animateItem(),
                            onConnect = {
                                if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
                                    pendingConnect = profile
                                } else {
                                    viewModel.session.connect(profile)
                                    navigate("terminal")
                                }
                            },
                            onEdit = { navigate("edit:${profile.id}") },
                            onDelete = { viewModel.delete(profile) },
                            onBrowseFiles = { navigate("files:${profile.id}") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    profile: ServerProfile,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBrowseFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Card(modifier.fillMaxWidth().clickable(onClick = onConnect)) {
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
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.cd_options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_browse_files)) }, leadingIcon = { Icon(Icons.Default.Folder, null) }, onClick = { menu = false; onBrowseFiles() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

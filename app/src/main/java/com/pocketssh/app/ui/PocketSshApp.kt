package com.pocketssh.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.pocketssh.app.MainViewModel

@Composable
fun PocketSshApp(viewModel: MainViewModel, destination: String, navigate: (String) -> Unit) {
    when {
        destination == "servers" -> ServerListScreen(viewModel, navigate)
        destination == "terminal" -> TerminalScreen(viewModel, navigate)
        destination == "settings" -> SettingsScreen(viewModel, navigate)
        destination.startsWith("edit:") -> {
            val id = destination.removePrefix("edit:")
            val profiles by viewModel.profiles.collectAsState()
            ProfileEditorScreen(profiles.firstOrNull { it.id == id }, viewModel, navigate)
        }
        destination.startsWith("files:") -> {
            val id = destination.removePrefix("files:")
            FileBrowserScreen(id, viewModel, navigate)
        }
        else -> ProfileEditorScreen(null, viewModel, navigate)
    }
}

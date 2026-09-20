package com.pocketssh.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.pocketssh.app.MainViewModel

// Strong ease-out: starts fast, settles gently. Plain screens feel snappier with this than
// the default Compose easing, which is comparatively lazy off the mark.
private val ScreenEasing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private const val ScreenAnimMs = 220

@Composable
fun PocketSshApp(viewModel: MainViewModel, destination: String, navigate: (String) -> Unit) {
    AnimatedContent(
        targetState = destination,
        transitionSpec = {
            // No real back stack, but "servers" is the de-facto root: treat leaving it as
            // pushing forward and returning to it as popping back, so the direction of travel
            // still reads correctly to the eye.
            val poppingBack = targetState == "servers" && initialState != "servers"
            val enterSign = if (poppingBack) -1 else 1
            val exitSign = if (poppingBack) 1 else -1
            (slideInHorizontally(tween(ScreenAnimMs, easing = ScreenEasing)) { width -> enterSign * width / 4 } +
                fadeIn(tween(ScreenAnimMs, easing = ScreenEasing)))
                .togetherWith(
                    fadeOut(tween(ScreenAnimMs / 2)) +
                        slideOutHorizontally(tween(ScreenAnimMs, easing = ScreenEasing)) { width -> exitSign * width / 6 },
                )
        },
        label = "screen",
    ) { dest ->
        when {
            dest == "servers" -> ServerListScreen(viewModel, navigate)
            dest == "terminal" -> TerminalScreen(viewModel, navigate)
            dest == "settings" -> SettingsScreen(viewModel, navigate)
            dest == "lock_style" -> LockStyleScreen(viewModel, navigate)
            dest.startsWith("edit:") -> {
                val id = dest.removePrefix("edit:")
                val profiles by viewModel.profiles.collectAsState()
                ProfileEditorScreen(profiles.firstOrNull { it.id == id }, viewModel, navigate)
            }
            dest.startsWith("files:") -> {
                val id = dest.removePrefix("files:")
                FileBrowserScreen(id, viewModel, navigate)
            }
            else -> ProfileEditorScreen(null, viewModel, navigate)
        }
    }
}

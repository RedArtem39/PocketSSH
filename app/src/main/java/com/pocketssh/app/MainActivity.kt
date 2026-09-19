package com.pocketssh.app

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.pocketssh.app.ui.LockScreen
import com.pocketssh.app.ui.PocketSshApp
import com.pocketssh.app.ui.PocketSshTheme

// BiometricPrompt requires a FragmentActivity host, so this can't stay a plain ComponentActivity.
class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Root(viewModel) }
    }
}

@Composable
private fun Root(viewModel: MainViewModel) {
    var destination by rememberSaveable { mutableStateOf("servers") }
    val isLocked by viewModel.isLocked.collectAsState()
    PocketSshTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Crossfade(targetState = isLocked, animationSpec = tween(280), label = "lock") { locked ->
                if (locked) {
                    LockScreen(viewModel = viewModel)
                } else {
                    PocketSshApp(
                        viewModel = viewModel,
                        destination = destination,
                        navigate = { destination = it },
                    )
                }
            }
        }
    }
}

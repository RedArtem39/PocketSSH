package com.pocketssh.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.pocketssh.app.ui.PocketSshApp

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Root(viewModel) }
    }
}

@Composable
private fun Root(viewModel: MainViewModel) {
    var destination by rememberSaveable { mutableStateOf("servers") }
    PocketSshApp(
        viewModel = viewModel,
        destination = destination,
        navigate = { destination = it },
    )
}

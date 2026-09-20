package com.pocketssh.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Receives the outcome of a [PackageInstaller] session.
 *
 * The interesting case is [PackageInstaller.STATUS_PENDING_USER_ACTION]: the installer does not
 * show its confirmation by itself, it hands back an intent for the app to launch. Launching it
 * here is what puts the user straight into Android's own "update this app?" dialog, instead of
 * the app chooser that an ACTION_VIEW on an APK produces — which on a real device offered
 * Termux and a chat app alongside the package installer.
 *
 * A successful install kills this process, so no success path needs handling; only failures
 * survive long enough to be reported.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { lastFailure.value = it.message }
            }
            PackageInstaller.STATUS_SUCCESS -> lastFailure.value = null
            else -> {
                // Android's own wording is more specific than anything reconstructed from the
                // status code, so it is preferred when present.
                lastFailure.value = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "Install failed"
            }
        }
    }

    companion object {
        /**
         * Last failure text, or null. Static because the receiver is constructed by the system
         * and has nowhere to inject a handle to the UI; the install screen observes it.
         */
        val lastFailure = MutableStateFlow<String?>(null)
    }
}

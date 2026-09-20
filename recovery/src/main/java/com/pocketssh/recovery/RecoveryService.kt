package com.pocketssh.recovery

import android.app.Service
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.IBinder
import java.io.File

/**
 * Performs a PocketSSH downgrade. Headless on purpose: no activity, no launcher entry, nothing
 * to look at. PocketSSH calls this just before asking to be uninstalled and then stops caring.
 *
 * Why a second package at all: Android refuses to install a lower version code over a higher
 * one, so a downgrade has to uninstall first — and an app cannot install anything after
 * uninstalling itself. This process is not touched by that uninstall, so it can finish the job.
 *
 * The APK is copied out of the handed-over content URI immediately. That URI and the folder
 * permission behind it belong to PocketSSH, which is about to stop existing.
 *
 * Two system dialogs remain, one for the uninstall and one for the install. Those belong to
 * Android and only a system installer can skip them.
 */
class RecoveryService : Service() {

    private var apk: File? = null
    private var receiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { startActivity(confirm) }
                }
                PackageInstaller.STATUS_SUCCESS -> when (intent.action) {
                    // The uninstall freed the slot, so the older build can go in now.
                    ACTION_UNINSTALL_STATUS -> installApk()
                    else -> finish()
                }
                // Nothing to report to: PocketSSH is gone by this point and this process has no
                // UI by design. Giving up quietly beats a notification nobody asked for.
                else -> finish()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.getParcelableExtra<Uri>(EXTRA_APK_URI) ?: return finishAndStop()
        if (!copyApk(uri)) return finishAndStop()
        registerStatusReceiver()
        if (isTargetInstalled()) uninstallTarget() else installApk()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (receiverRegistered) runCatching { unregisterReceiver(statusReceiver) }
        super.onDestroy()
    }

    private fun copyApk(uri: Uri): Boolean {
        val target = File(filesDir, "rollback.apk")
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            if (target.length() == 0L) return false
            apk = target
            true
        }.getOrDefault(false)
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_UNINSTALL_STATUS)
            addAction(ACTION_INSTALL_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun isTargetInstalled(): Boolean =
        runCatching { packageManager.getPackageInfo(TARGET_PACKAGE, 0) }.isSuccess

    private fun uninstallTarget() {
        val intent = Intent(ACTION_UNINSTALL_STATUS).setPackage(packageName)
        val pending = PendingIntent.getBroadcast(this, 0, intent, pendingFlags())
        runCatching { packageManager.packageInstaller.uninstall(TARGET_PACKAGE, pending.intentSender) }
            .onFailure { finish() }
    }

    private fun installApk() {
        val file = apk ?: return finish()
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        runCatching {
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("package", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val intent = Intent(ACTION_INSTALL_STATUS).setPackage(packageName)
                val pending = PendingIntent.getBroadcast(this, sessionId, intent, pendingFlags())
                session.commit(pending.intentSender)
            }
        }.onFailure { finish() }
    }

    private fun finish() {
        apk?.delete()
        stopSelf()
    }

    private fun finishAndStop(): Int {
        finish()
        return START_NOT_STICKY
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE

    private companion object {
        const val TARGET_PACKAGE = "com.pocketssh.app"
        const val EXTRA_APK_URI = "apk_uri"
        const val ACTION_UNINSTALL_STATUS = "com.pocketssh.recovery.UNINSTALL_STATUS"
        const val ACTION_INSTALL_STATUS = "com.pocketssh.recovery.INSTALL_STATUS"
    }
}

package com.pocketssh.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.pocketssh.app.data.BackupCodec
import com.pocketssh.app.data.KnownHost
import com.pocketssh.app.data.SecureProfileStore
import com.pocketssh.app.data.ServerProfile
import com.pocketssh.app.ssh.SftpManager
import com.pocketssh.app.ssh.SshConnection
import com.pocketssh.app.ssh.SshSessionManager
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureProfileStore(application)
    private val connection = SshConnection(store)

    private val _profiles = MutableStateFlow(store.loadProfiles())
    val profiles = _profiles.asStateFlow()

    val session = SshSessionManager(application, connection)
    val sftp = SftpManager(application, connection)

    private val _isLocked = MutableStateFlow(store.hasPin())
    val isLocked = _isLocked.asStateFlow()

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            if (store.hasPin()) _isLocked.value = true
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun unlock(pin: String): Boolean {
        val ok = store.verifyPin(pin)
        if (ok) _isLocked.value = false
        return ok
    }

    fun unlockWithBiometric() {
        _isLocked.value = false
    }

    fun hasPin(): Boolean = store.hasPin()

    fun setPin(pin: String) {
        store.setPin(pin)
    }

    fun clearPin() {
        store.clearPin()
    }

    fun knownHosts(): List<KnownHost> = store.listKnownHosts()

    fun forgetHost(host: String, port: Int) {
        store.forgetFingerprint(host, port)
    }

    fun exportBackup(passphrase: String): String = BackupCodec.export(_profiles.value, passphrase)

    /** Returns the number of profiles imported. Imported profiles get fresh ids so existing ones are never overwritten. */
    fun importBackup(payload: String, passphrase: String): Int {
        val imported = BackupCodec.import(payload, passphrase)
        imported.forEach { upsert(it.copy(id = UUID.randomUUID().toString())) }
        return imported.size
    }

    fun upsert(profile: ServerProfile) {
        val updated = _profiles.value.toMutableList()
        val index = updated.indexOfFirst { it.id == profile.id }
        if (index >= 0) updated[index] = profile else updated.add(profile)
        _profiles.value = updated.sortedBy { it.name.lowercase() }
        store.saveProfiles(_profiles.value)
    }

    fun delete(profile: ServerProfile) {
        _profiles.value = _profiles.value.filterNot { it.id == profile.id }
        store.saveProfiles(_profiles.value)
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        session.close()
        sftp.destroy()
        super.onCleared()
    }
}

package com.pocketssh.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.pocketssh.app.data.AutoBackup
import com.pocketssh.app.data.BackupCodec
import com.pocketssh.app.data.KnownHost
import com.pocketssh.app.data.LockAppearance
import com.pocketssh.app.data.LockAppearanceStore
import com.pocketssh.app.data.SecureProfileStore
import com.pocketssh.app.data.ServerProfile
import com.pocketssh.app.data.StorageFolder
import com.pocketssh.app.update.UpdateManager
import com.pocketssh.app.ssh.SftpManager
import com.pocketssh.app.ssh.SshConnection
import com.pocketssh.app.ssh.SshSessionManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureProfileStore(application)
    private val connection = SshConnection(store)

    private val _profiles = MutableStateFlow(store.loadProfiles())
    val profiles = _profiles.asStateFlow()

    val session = SshSessionManager(application, connection)
    val sftp = SftpManager(application, connection)

    private val _isLocked = MutableStateFlow(store.hasPin())
    val isLocked = _isLocked.asStateFlow()

    val storageFolder = StorageFolder(application)
    val autoBackup = AutoBackup(application, storageFolder)
    val updates = UpdateManager(application, storageFolder)

    /**
     * Set when this install has no servers but a readable auto backup is sitting in the shared
     * folder — the signature of a reinstall or a downgrade. The UI offers to restore it.
     */
    private val _restorable = MutableStateFlow(false)
    val restorable = _restorable.asStateFlow()

    /** Number of profiles brought back by the last restore, for the confirmation message. */
    private val _restoredCount = MutableStateFlow(0)
    val restoredCount = _restoredCount.asStateFlow()

    private val appearanceStore = LockAppearanceStore(application)
    private val _lockAppearance = MutableStateFlow(appearanceStore.load())
    val lockAppearance = _lockAppearance.asStateFlow()

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            if (store.hasPin()) _isLocked.value = true
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        refreshRestorable()
    }

    /** True when there is nothing to lose: only then is an unattended restore safe to offer. */
    private fun refreshRestorable() {
        if (_profiles.value.isNotEmpty()) {
            _restorable.value = false
            return
        }
        viewModelScope.launch {
            _restorable.value = withContext(Dispatchers.IO) { autoBackup.latest() != null }
        }
    }

    /**
     * Records the folder. When this install has no servers yet and the folder turns out to hold
     * a readable backup, it is restored immediately — after a reinstall the app cannot remember
     * anything, so pointing at the folder is the only signal available, and making the user then
     * press "restore" as well would be a second step for no decision.
     */
    fun rememberStorageFolder(uri: Uri, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            // Taking the grant and resolving the tree are both provider calls; they ran on the
            // main thread straight out of the picker callback.
            val ok = withContext(Dispatchers.IO) { storageFolder.remember(uri) }
            if (!ok) {
                onDone(0)
                return@launch
            }
            if (_profiles.value.isEmpty()) {
                restoreFromAutoBackup(onDone)
            } else {
                refreshRestorable()
                scheduleAutoBackup()
                onDone(0)
            }
        }
    }

    fun forgetStorageFolder() {
        _restorable.value = false
        viewModelScope.launch { withContext(Dispatchers.IO) { storageFolder.forget() } }
    }

    /**
     * Restores the newest auto backup. Profiles are merged rather than replaced, and each one
     * gets a fresh id, so restoring twice cannot clobber something already set up by hand.
     */
    fun restoreFromAutoBackup(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { autoBackup.restoreLatest() }.orEmpty()
            restored.forEach { upsert(it.copy(id = UUID.randomUUID().toString())) }
            _restoredCount.value = restored.size
            _restorable.value = false
            onDone(restored.size)
        }
    }

    /**
     * Writes an auto backup off the main thread. PBKDF2 at 210k iterations is not something to
     * run on a UI callback, and nothing waits on the result.
     */
    fun scheduleAutoBackup() {
        val snapshot = _profiles.value
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // isAvailable() asks the provider whether the folder is still writable, so it
                // belongs on this side of the switch too — this runs from every profile save.
                if (!autoBackup.isAvailable()) return@withContext
                runCatching { autoBackup.write(snapshot, updates.currentVersionName()) }
            }
        }
    }

    /**
     * Checks the PIN without unlocking. Split from [commitUnlock] so the lock screen can play
     * its success animation while still locked — flipping the flag here would swap the screen
     * out from under it mid-frame.
     */
    fun verifyPin(pin: String): Boolean = store.verifyPin(pin)

    fun commitUnlock() {
        _isLocked.value = false
    }

    fun pinLength(): Int = store.pinLength()

    fun updateLockAppearance(appearance: LockAppearance) {
        _lockAppearance.value = appearance
        appearanceStore.save(appearance)
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
        scheduleAutoBackup()
    }

    fun delete(profile: ServerProfile) {
        _profiles.value = _profiles.value.filterNot { it.id == profile.id }
        store.saveProfiles(_profiles.value)
        scheduleAutoBackup()
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        session.close()
        sftp.destroy()
        super.onCleared()
    }
}

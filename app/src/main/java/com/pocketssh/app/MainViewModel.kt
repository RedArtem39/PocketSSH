package com.pocketssh.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.pocketssh.app.data.SecureProfileStore
import com.pocketssh.app.data.ServerProfile
import com.pocketssh.app.ssh.SshSessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureProfileStore(application)
    private val _profiles = MutableStateFlow(store.loadProfiles())
    val profiles = _profiles.asStateFlow()
    val session = SshSessionManager(application, store)

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
        session.close()
        super.onCleared()
    }
}

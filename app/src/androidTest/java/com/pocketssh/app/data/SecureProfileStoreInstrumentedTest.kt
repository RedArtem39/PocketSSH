package com.pocketssh.app.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * These need a real AndroidKeyStore, so they run as instrumented tests rather than plain
 * JVM unit tests (Robolectric's AndroidKeyStore support is unreliable, and the plain JVM
 * doesn't have one at all).
 */
@RunWith(AndroidJUnit4::class)
class SecureProfileStoreInstrumentedTest {
    private lateinit var store: SecureProfileStore

    @Before
    fun setUp() {
        context().getSharedPreferences("secure_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        store = SecureProfileStore(context())
    }

    @After
    fun tearDown() {
        context().getSharedPreferences("secure_profiles", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun profilesRoundTripThroughKeystoreEncryption() {
        val profile = ServerProfile(
            name = "Test", host = "example.com", port = 22, username = "root",
            authType = AuthType.PASSWORD, password = "hunter2", saveSecret = true,
        )
        store.saveProfiles(listOf(profile))
        val loaded = store.loadProfiles()
        assertEquals(1, loaded.size)
        assertEquals("example.com", loaded[0].host)
        assertEquals("hunter2", loaded[0].password)
    }

    @Test
    fun unsavedSecretsAreNotPersisted() {
        val profile = ServerProfile(name = "Test", host = "example.com", username = "root", password = "hunter2", saveSecret = false)
        store.saveProfiles(listOf(profile))
        val loaded = store.loadProfiles()
        assertEquals("", loaded[0].password)
    }

    @Test
    fun hostFingerprintsRoundTripAndCanBeForgotten() {
        store.rememberFingerprint("example.com", 22, "SHA256:abc123")
        assertEquals("SHA256:abc123", store.knownFingerprint("example.com", 22))
        assertTrue(store.listKnownHosts().any { it.host == "example.com" && it.port == 22 })

        store.forgetFingerprint("example.com", 22)
        assertNull(store.knownFingerprint("example.com", 22))
    }

    @Test
    fun pinCanBeSetVerifiedAndCleared() {
        assertFalse(store.hasPin())
        store.setPin("1234")
        assertTrue(store.hasPin())
        assertTrue(store.verifyPin("1234"))
        assertFalse(store.verifyPin("0000"))

        store.clearPin()
        assertFalse(store.hasPin())
    }

    @Test
    fun backupExportImportRoundTrips() {
        val profile = ServerProfile(name = "Backup me", host = "10.0.0.1", username = "user", password = "pw")
        val payload = BackupCodec.export(listOf(profile), "backup-pass")
        val imported = BackupCodec.import(payload, "backup-pass")
        assertEquals(1, imported.size)
        assertEquals("10.0.0.1", imported[0].host)
        assertEquals("pw", imported[0].password)
    }
}

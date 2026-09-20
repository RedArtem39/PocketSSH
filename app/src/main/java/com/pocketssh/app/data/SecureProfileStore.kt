package com.pocketssh.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class KnownHost(val host: String, val port: Int, val fingerprint: String)

class SecureProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences("secure_profiles", Context.MODE_PRIVATE)
    private val keyAlias = "pocketssh.profile.key.v1"

    fun loadProfiles(): List<ServerProfile> = runCatching {
        val raw = preferences.getString("profiles", null) ?: return emptyList()
        ProfileJson.decode(decrypt(raw))
    }.getOrDefault(emptyList())

    fun saveProfiles(profiles: List<ServerProfile>) {
        preferences.edit().putString("profiles", encrypt(ProfileJson.encode(profiles))).apply()
    }

    fun knownFingerprint(host: String, port: Int): String? =
        preferences.getString(hostKey(host, port), null)?.let(::decrypt)

    fun rememberFingerprint(host: String, port: Int, fingerprint: String) {
        preferences.edit().putString(hostKey(host, port), encrypt(fingerprint)).apply()
    }

    fun listKnownHosts(): List<KnownHost> = preferences.all.keys
        .filter { it.startsWith("host_") }
        .mapNotNull { key ->
            val fingerprint = preferences.getString(key, null)
                ?.let { runCatching { decrypt(it) }.getOrNull() }
                ?: return@mapNotNull null
            val hostPort = key.removePrefix("host_")
            val port = hostPort.substringAfterLast(':').toIntOrNull() ?: return@mapNotNull null
            val host = hostPort.substringBeforeLast(':')
            KnownHost(host, port, fingerprint)
        }
        .sortedBy { it.host }

    fun forgetFingerprint(host: String, port: Int) {
        preferences.edit().remove(hostKey(host, port)).apply()
    }

    fun hasPin(): Boolean = preferences.contains("pin_hash")

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        preferences.edit()
            .putString("pin_salt", encrypt(Base64.encodeToString(salt, Base64.NO_WRAP)))
            .putString("pin_hash", encrypt(hashPin(pin, salt)))
            .putString("pin_length", encrypt(pin.length.toString()))
            .apply()
    }

    /**
     * How many digits the stored PIN has, or 0 when that isn't known — PINs saved before this
     * was recorded have no length entry. Callers use it purely to draw the right number of
     * placeholder dots and to auto-submit on the last digit, so 0 just means "fall back".
     */
    fun pinLength(): Int = preferences.getString("pin_length", null)
        ?.let { runCatching { decrypt(it).toInt() }.getOrNull() }
        ?: 0

    fun verifyPin(pin: String): Boolean {
        val saltEncoded = preferences.getString("pin_salt", null)?.let(::decrypt) ?: return false
        val storedHash = preferences.getString("pin_hash", null)?.let(::decrypt) ?: return false
        val salt = Base64.decode(saltEncoded, Base64.NO_WRAP)
        return hashPin(pin, salt) == storedHash
    }

    fun clearPin() {
        preferences.edit().remove("pin_salt").remove("pin_hash").remove("pin_length").apply()
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return Base64.encodeToString(digest.digest(pin.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun hostKey(host: String, port: Int) = "host_${host.lowercase()}:$port"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val payload = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(payload).toString(Charsets.UTF_8)
    }
}

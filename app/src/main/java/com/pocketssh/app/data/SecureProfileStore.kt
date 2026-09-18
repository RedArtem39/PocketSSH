package com.pocketssh.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences("secure_profiles", Context.MODE_PRIVATE)
    private val keyAlias = "pocketssh.profile.key.v1"

    fun loadProfiles(): List<ServerProfile> = runCatching {
        val raw = preferences.getString("profiles", null) ?: return emptyList()
        val array = JSONArray(decrypt(raw))
        buildList {
            for (index in 0 until array.length()) add(array.getJSONObject(index).toProfile())
        }
    }.getOrDefault(emptyList())

    fun saveProfiles(profiles: List<ServerProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("host", profile.host)
                put("port", profile.port)
                put("username", profile.username)
                put("authType", profile.authType.name)
                put("password", if (profile.saveSecret) profile.password else "")
                put("privateKey", if (profile.saveSecret) profile.privateKey else "")
                put("keyPassphrase", if (profile.saveSecret) profile.keyPassphrase else "")
                put("saveSecret", profile.saveSecret)
            })
        }
        preferences.edit().putString("profiles", encrypt(array.toString())).apply()
    }

    fun knownFingerprint(host: String, port: Int): String? =
        preferences.getString("host_${host.lowercase()}:$port", null)?.let(::decrypt)

    fun rememberFingerprint(host: String, port: Int, fingerprint: String) {
        preferences.edit().putString("host_${host.lowercase()}:$port", encrypt(fingerprint)).apply()
    }

    private fun JSONObject.toProfile() = ServerProfile(
        id = getString("id"),
        name = getString("name"),
        host = getString("host"),
        port = getInt("port"),
        username = getString("username"),
        authType = AuthType.valueOf(getString("authType")),
        password = optString("password"),
        privateKey = optString("privateKey"),
        keyPassphrase = optString("keyPassphrase"),
        saveSecret = optBoolean("saveSecret", true),
    )

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

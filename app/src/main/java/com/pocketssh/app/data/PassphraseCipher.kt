package com.pocketssh.app.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-based envelope encryption for portable backups. Plain JDK crypto only
 * (no AndroidKeyStore), so it works the same on-device and in a plain JVM unit test.
 */
object PassphraseCipher {
    private const val MAGIC = "PSSHB1"
    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12

    fun encrypt(plaintext: ByteArray, passphrase: String): String {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)
        val encoder = Base64.getEncoder()
        return listOf(
            MAGIC,
            encoder.encodeToString(salt),
            encoder.encodeToString(iv),
            encoder.encodeToString(ciphertext),
        ).joinToString("\n")
    }

    fun decrypt(payload: String, passphrase: String): ByteArray {
        val lines = payload.trim().lines()
        require(lines.size == 4 && lines[0] == MAGIC) { "Not a PocketSSH backup file" }
        val decoder = Base64.getDecoder()
        val salt = decoder.decode(lines[1])
        val iv = decoder.decode(lines[2])
        val ciphertext = decoder.decode(lines[3])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}

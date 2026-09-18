package com.pocketssh.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PassphraseCipherTest {

    @Test
    fun `round trip returns the original bytes`() {
        val plaintext = "hello pocketssh backup".toByteArray(Charsets.UTF_8)
        val payload = PassphraseCipher.encrypt(plaintext, "correct horse battery staple")
        val decrypted = PassphraseCipher.decrypt(payload, "correct horse battery staple")
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `wrong passphrase fails to decrypt`() {
        val payload = PassphraseCipher.encrypt("secret".toByteArray(Charsets.UTF_8), "right-passphrase")
        assertThrows(Exception::class.java) {
            PassphraseCipher.decrypt(payload, "wrong-passphrase")
        }
    }

    @Test
    fun `corrupted payload fails to decrypt`() {
        val payload = PassphraseCipher.encrypt("secret".toByteArray(Charsets.UTF_8), "pw")
        val corrupted = payload.dropLast(4) + "abcd"
        assertThrows(Exception::class.java) {
            PassphraseCipher.decrypt(corrupted, "pw")
        }
    }

    @Test
    fun `not a backup file is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PassphraseCipher.decrypt("not a backup", "pw")
        }
    }
}

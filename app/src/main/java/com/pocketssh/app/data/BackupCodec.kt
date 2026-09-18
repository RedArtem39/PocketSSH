package com.pocketssh.app.data

object BackupCodec {
    fun export(profiles: List<ServerProfile>, passphrase: String): String =
        PassphraseCipher.encrypt(ProfileJson.encode(profiles).toByteArray(Charsets.UTF_8), passphrase)

    fun import(payload: String, passphrase: String): List<ServerProfile> =
        ProfileJson.decode(PassphraseCipher.decrypt(payload, passphrase).toString(Charsets.UTF_8))
}

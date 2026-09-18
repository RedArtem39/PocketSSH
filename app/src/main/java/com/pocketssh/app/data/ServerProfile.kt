package com.pocketssh.app.data

import java.util.UUID

enum class AuthType { PASSWORD, PRIVATE_KEY }

data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val keyPassphrase: String = "",
    val saveSecret: Boolean = true,
)

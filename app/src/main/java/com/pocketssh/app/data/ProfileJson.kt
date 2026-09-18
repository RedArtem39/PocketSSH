package com.pocketssh.app.data

import org.json.JSONArray
import org.json.JSONObject

object ProfileJson {
    fun encode(profiles: List<ServerProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject().apply {
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
                },
            )
        }
        return array.toString()
    }

    fun decode(json: String): List<ServerProfile> {
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) add(array.getJSONObject(index).toProfile())
        }
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
}

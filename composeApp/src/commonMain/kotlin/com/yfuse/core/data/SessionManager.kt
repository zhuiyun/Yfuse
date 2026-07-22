package com.yfuse.core.data

import com.russhwolf.settings.Settings

/**
 * Persists the current Emby session: server base url, access token, user id.
 * Backed by multiplatform-settings so it works across platforms.
 */
class SessionManager(private val settings: Settings) {

    private companion object {
        const val KEY_URL = "session.baseUrl"
        const val KEY_TOKEN = "session.token"
        const val KEY_UID = "session.userId"
    }

    fun save(baseUrl: String, token: String, userId: String) {
        settings.putString(KEY_URL, baseUrl)
        settings.putString(KEY_TOKEN, token)
        settings.putString(KEY_UID, userId)
    }

    fun baseUrl(): String? = settings.getStringOrNull(KEY_URL)
    fun token(): String? = settings.getStringOrNull(KEY_TOKEN)
    fun userId(): String? = settings.getStringOrNull(KEY_UID)

    fun hasSession(): Boolean =
        baseUrl() != null && token() != null && userId() != null

    fun clear() {
        settings.remove(KEY_URL)
        settings.remove(KEY_TOKEN)
        settings.remove(KEY_UID)
    }
}

package com.yfuse.core.data

import com.russhwolf.settings.Settings
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WatchTogetherPreferences(private val settings: Settings) {
    companion object {
        private const val ENDPOINT_KEY = "watchTogether.endpoint"
        private const val CLIENT_ID_KEY = "watchTogether.clientId"

        /**
         * Public so invite links can omit the relay when it's this one — a shared link only
         * carries an `e=` parameter (and only then warns the recipient) when the host is on
         * a relay the recipient might not expect.
         */
        const val DEFAULT_ENDPOINT = "http://47.112.219.60"
    }

    private val _endpoint = MutableStateFlow(settings.getString(ENDPOINT_KEY, DEFAULT_ENDPOINT))
    val endpoint: StateFlow<String> = _endpoint.asStateFlow()

    val clientId: String = settings.getStringOrNull(CLIENT_ID_KEY) ?: buildString {
        append(System.currentTimeMillis().toString(36))
        append('-')
        repeat(10) { append("abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)]) }
    }.also { settings.putString(CLIENT_ID_KEY, it) }

    fun setEndpoint(value: String) {
        val normalized = value.trim().trimEnd('/')
        _endpoint.value = normalized
        if (normalized.isEmpty()) settings.remove(ENDPOINT_KEY)
        else settings.putString(ENDPOINT_KEY, normalized)
    }
}

package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.security.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TgtoConnection(
    val endpoint: String,
    val username: String,
    val hasPassword: Boolean,
)

data class Pan123Authorization(
    val phone: String,
    val hasToken: Boolean,
)

class TgtoMediaPreferences(
    private val settings: Settings,
    private val secureStore: SecureStore,
) {
    companion object {
        private const val ENDPOINT_KEY = "tgto.media.endpoint"
        private const val USERNAME_KEY = "tgto.media.username"
        private const val PASSWORD_KEY = "password"
        private const val PAN123_PHONE_KEY = "tgto.media.pan123.phone"
        private const val PAN123_TOKEN_KEY = "pan123.token"
    }

    private val _connection = MutableStateFlow(loadConnection())
    val connection: StateFlow<TgtoConnection> = _connection.asStateFlow()
    private val _pan123Authorization = MutableStateFlow(loadPan123Authorization())
    val pan123Authorization: StateFlow<Pan123Authorization> = _pan123Authorization.asStateFlow()
    private val _openSettingsRequest = MutableStateFlow(0L)
    val openSettingsRequest: StateFlow<Long> = _openSettingsRequest.asStateFlow()

    fun password(): String = secureStore.get(PASSWORD_KEY)?.decodeToString().orEmpty()

    fun pan123Token(): String = secureStore.get(PAN123_TOKEN_KEY)?.decodeToString().orEmpty()

    fun save(
        endpoint: String,
        username: String,
        password: String,
    ) {
        val normalizedEndpoint = endpoint.trim().trimEnd('/').ifBlank { DEFAULT_TGTO_ENDPOINT }
        val normalizedUsername = username.trim().ifBlank { DEFAULT_TGTO_USERNAME }
        settings.putString(ENDPOINT_KEY, normalizedEndpoint)
        settings.putString(USERNAME_KEY, normalizedUsername)
        if (password.isNotBlank()) secureStore.put(PASSWORD_KEY, password.encodeToByteArray())
        _connection.value = loadConnection()
    }

    fun clearPassword() {
        secureStore.remove(PASSWORD_KEY)
        _connection.value = loadConnection()
    }

    fun savePan123Authorization(
        phone: String,
        token: String,
    ) {
        require(phone.isNotBlank() && token.isNotBlank()) { "123 账号和令牌不能为空" }
        settings.putString(PAN123_PHONE_KEY, phone.trim())
        secureStore.put(PAN123_TOKEN_KEY, token.encodeToByteArray())
        _pan123Authorization.value = loadPan123Authorization()
    }

    fun clearPan123Authorization() {
        secureStore.remove(PAN123_TOKEN_KEY)
        _pan123Authorization.value = loadPan123Authorization()
    }

    fun requestOpenSettings() {
        _openSettingsRequest.value += 1L
    }

    fun consumeOpenSettingsRequest() {
        _openSettingsRequest.value = 0L
    }

    private fun loadConnection(): TgtoConnection =
        TgtoConnection(
            endpoint = settings.getString(ENDPOINT_KEY, DEFAULT_TGTO_ENDPOINT).trim().trimEnd('/'),
            username = settings.getString(USERNAME_KEY, DEFAULT_TGTO_USERNAME).trim(),
            hasPassword = secureStore.get(PASSWORD_KEY)?.isNotEmpty() == true,
        )

    private fun loadPan123Authorization(): Pan123Authorization =
        Pan123Authorization(
            phone = settings.getString(PAN123_PHONE_KEY, "").trim(),
            hasToken = secureStore.get(PAN123_TOKEN_KEY)?.isNotEmpty() == true,
        )
}

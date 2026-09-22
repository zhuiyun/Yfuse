package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.account.ACCOUNT_BASE_URL
import com.yfuse.core.util.takeGraphemes
import com.yfuse.core.util.takeGraphemesWithinUtf8Bytes
import com.yfuse.core.util.withoutControlCharacters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

class WatchTogetherPreferences(
    private val settings: Settings,
) {
    companion object {
        private const val ENDPOINT_KEY = "watchTogether.endpoint"
        private const val CLEARTEXT_ENDPOINT_CONFIRMED_KEY =
            "watchTogether.endpointCleartextConfirmed.v1"
        private const val CLIENT_ID_KEY = "watchTogether.clientId"
        private const val NICKNAME_KEY = "watchTogether.nickname"
        private const val AVATAR_ID_KEY = "watchTogether.avatarId"
        private const val CHAT_PREVIEW_KEY = "watchTogether.chatPreview"
        private const val CHAT_DANMAKU_KEY = "watchTogether.chatDanmaku"

        /** Protocol v5 authenticates with the Yfuse account token, so its relay is not configurable. */
        const val DEFAULT_ENDPOINT = ACCOUNT_BASE_URL
        const val DEFAULT_NICKNAME = "影友"
        const val AVATAR_COUNT = 8
        const val MAX_NICKNAME_GRAPHEMES = 24
        const val MAX_NICKNAME_BYTES = 128

        /** Exact base-address check; paths, alternate schemes, and same-origin aliases are rejected. */
        fun isOfficialEndpoint(value: String): Boolean = value.trim().trimEnd('/') == DEFAULT_ENDPOINT
    }

    private val _endpoint = MutableStateFlow(loadEndpoint())
    val endpoint: StateFlow<String> = _endpoint.asStateFlow()

    val clientId: String =
        settings.getStringOrNull(CLIENT_ID_KEY) ?: buildString {
            append(System.currentTimeMillis().toString(36))
            append('-')
            repeat(10) { append("abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)]) }
        }.also { settings.putString(CLIENT_ID_KEY, it) }

    private val _nickname =
        MutableStateFlow(
            settings.getString(NICKNAME_KEY, DEFAULT_NICKNAME).normalizedWatchNickname(),
        )
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    private val _avatarId =
        MutableStateFlow(
            settings.getInt(AVATAR_ID_KEY, defaultAvatarId(clientId)).coerceIn(0, AVATAR_COUNT - 1),
        )
    val avatarId: StateFlow<Int> = _avatarId.asStateFlow()

    private val _chatPreviewEnabled = MutableStateFlow(settings.getBoolean(CHAT_PREVIEW_KEY, true))
    val chatPreviewEnabled: StateFlow<Boolean> = _chatPreviewEnabled.asStateFlow()

    private val _chatDanmakuEnabled = MutableStateFlow(settings.getBoolean(CHAT_DANMAKU_KEY, true))
    val chatDanmakuEnabled: StateFlow<Boolean> = _chatDanmakuEnabled.asStateFlow()

    fun setProfile(
        nickname: String,
        avatarId: Int,
    ) {
        val normalizedName = nickname.normalizedWatchNickname()
        val normalizedAvatar = avatarId.coerceIn(0, AVATAR_COUNT - 1)
        _nickname.value = normalizedName
        _avatarId.value = normalizedAvatar
        settings.putString(NICKNAME_KEY, normalizedName)
        settings.putInt(AVATAR_ID_KEY, normalizedAvatar)
    }

    fun setChatPreviewEnabled(enabled: Boolean) {
        _chatPreviewEnabled.value = enabled
        settings.putBoolean(CHAT_PREVIEW_KEY, enabled)
    }

    fun setChatDanmakuEnabled(enabled: Boolean) {
        _chatDanmakuEnabled.value = enabled
        settings.putBoolean(CHAT_DANMAKU_KEY, enabled)
    }

    /**
     * Protocol v5 sends an account access token during the WebSocket upgrade. Older releases
     * allowed arbitrary relays, so every load overwrites the legacy value with the official
     * account-service origin and clears any historic cleartext approval.
     */
    private fun loadEndpoint(): String {
        settings.putString(ENDPOINT_KEY, DEFAULT_ENDPOINT)
        settings.putBoolean(CLEARTEXT_ENDPOINT_CONFIRMED_KEY, false)
        return DEFAULT_ENDPOINT
    }

    private fun String.normalizedWatchNickname(): String =
        replace('\r', ' ')
            .replace('\n', ' ')
            .withoutControlCharacters()
            .trim()
            .takeGraphemes(MAX_NICKNAME_GRAPHEMES)
            .takeGraphemesWithinUtf8Bytes(MAX_NICKNAME_BYTES)
            .ifBlank { DEFAULT_NICKNAME }

    private fun defaultAvatarId(clientId: String): Int = (clientId.hashCode() and Int.MAX_VALUE) % AVATAR_COUNT
}

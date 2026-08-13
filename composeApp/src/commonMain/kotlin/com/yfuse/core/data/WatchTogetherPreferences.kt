package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.network.ServiceEndpointValidation
import com.yfuse.core.network.validateServiceEndpoint
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
        private const val CLIENT_ID_KEY = "watchTogether.clientId"
        private const val NICKNAME_KEY = "watchTogether.nickname"
        private const val AVATAR_ID_KEY = "watchTogether.avatarId"
        private const val CHAT_PREVIEW_KEY = "watchTogether.chatPreview"
        private const val CHAT_DANMAKU_KEY = "watchTogether.chatDanmaku"

        const val DEFAULT_ENDPOINT = "https://47.112.219.60"
        const val DEFAULT_NICKNAME = "影友"
        const val AVATAR_COUNT = 8
        const val MAX_NICKNAME_GRAPHEMES = 24
        const val MAX_NICKNAME_BYTES = 128
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

    /** Saves any syntactically valid HTTP(S) or WS(S) endpoint. */
    @Suppress("UNUSED_PARAMETER")
    fun setEndpoint(
        value: String,
        localCleartextConfirmed: Boolean = false,
    ): ServiceEndpointValidation {
        val validation = validateServiceEndpoint(value)
        if (!validation.allowed) return validation
        val normalized = validation.normalizedEndpoint ?: return validation
        _endpoint.value = normalized
        settings.putString(ENDPOINT_KEY, normalized)
        return validation
    }

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

    private fun loadEndpoint(): String {
        val stored = settings.getString(ENDPOINT_KEY, DEFAULT_ENDPOINT)
        val validation = validateServiceEndpoint(stored)
        return validation.normalizedEndpoint?.takeIf { validation.allowed } ?: DEFAULT_ENDPOINT
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

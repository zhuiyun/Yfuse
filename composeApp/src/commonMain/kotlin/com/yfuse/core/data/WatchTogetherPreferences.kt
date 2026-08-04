package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.util.takeGraphemes
import com.yfuse.core.util.takeGraphemesWithinUtf8Bytes
import com.yfuse.core.util.withoutControlCharacters
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WatchTogetherPreferences(private val settings: Settings) {
    companion object {
        private const val ENDPOINT_KEY = "watchTogether.endpoint"
        private const val CLIENT_ID_KEY = "watchTogether.clientId"
        private const val NICKNAME_KEY = "watchTogether.nickname"
        private const val AVATAR_ID_KEY = "watchTogether.avatarId"
        private const val CHAT_PREVIEW_KEY = "watchTogether.chatPreview"
        private const val CHAT_DANMAKU_KEY = "watchTogether.chatDanmaku"

        /**
         * Public so invite links can omit the relay when it's this one — a shared link only
         * carries an `e=` parameter (and only then warns the recipient) when the host is on
         * a relay the recipient might not expect.
         */
        const val DEFAULT_ENDPOINT = "http://47.112.219.60"
        const val DEFAULT_NICKNAME = "影友"
        const val AVATAR_COUNT = 8
        const val MAX_NICKNAME_GRAPHEMES = 24
        const val MAX_NICKNAME_BYTES = 128
    }

    private val _endpoint = MutableStateFlow(settings.getString(ENDPOINT_KEY, DEFAULT_ENDPOINT))
    val endpoint: StateFlow<String> = _endpoint.asStateFlow()

    val clientId: String = settings.getStringOrNull(CLIENT_ID_KEY) ?: buildString {
        append(System.currentTimeMillis().toString(36))
        append('-')
        repeat(10) { append("abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)]) }
    }.also { settings.putString(CLIENT_ID_KEY, it) }

    private val _nickname = MutableStateFlow(
        settings.getString(NICKNAME_KEY, DEFAULT_NICKNAME).normalizedWatchNickname(),
    )
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    private val _avatarId = MutableStateFlow(
        settings.getInt(AVATAR_ID_KEY, defaultAvatarId(clientId)).coerceIn(0, AVATAR_COUNT - 1),
    )
    val avatarId: StateFlow<Int> = _avatarId.asStateFlow()

    private val _chatPreviewEnabled = MutableStateFlow(settings.getBoolean(CHAT_PREVIEW_KEY, true))
    val chatPreviewEnabled: StateFlow<Boolean> = _chatPreviewEnabled.asStateFlow()

    private val _chatDanmakuEnabled = MutableStateFlow(settings.getBoolean(CHAT_DANMAKU_KEY, true))
    val chatDanmakuEnabled: StateFlow<Boolean> = _chatDanmakuEnabled.asStateFlow()

    fun setEndpoint(value: String) {
        val normalized = value.trim().trimEnd('/')
        _endpoint.value = normalized
        if (normalized.isEmpty()) settings.remove(ENDPOINT_KEY)
        else settings.putString(ENDPOINT_KEY, normalized)
    }

    fun setProfile(nickname: String, avatarId: Int) {
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

    private fun String.normalizedWatchNickname(): String = replace('\r', ' ')
        .replace('\n', ' ')
        .withoutControlCharacters()
        .trim()
        .takeGraphemes(MAX_NICKNAME_GRAPHEMES)
        .takeGraphemesWithinUtf8Bytes(MAX_NICKNAME_BYTES)
        .ifBlank { DEFAULT_NICKNAME }

    private fun defaultAvatarId(clientId: String): Int =
        (clientId.hashCode() and Int.MAX_VALUE) % AVATAR_COUNT

}

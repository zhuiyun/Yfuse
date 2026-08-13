package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.network.EndpointTransportDecision
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
        private const val HTTPS_ENDPOINT_MIGRATION_KEY = "watchTogether.endpointHttpsMigration.v2"
        private const val CLEARTEXT_ENDPOINT_CONFIRMED_KEY =
            "watchTogether.endpointCleartextConfirmed.v1"
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
        private val FORMER_OFFICIAL_ENDPOINTS =
            setOf(
                "http://47.112.219.60",
                "https://yfuse.zhuiyun.site",
            )
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

    /** Saves only endpoints accepted by the shared public/local cleartext policy. */
    fun setEndpoint(
        value: String,
        localCleartextConfirmed: Boolean = false,
    ): ServiceEndpointValidation {
        val validation = validateServiceEndpoint(value, localCleartextConfirmed)
        if (!validation.allowed) return validation
        val normalized = validation.normalizedEndpoint ?: return validation
        _endpoint.value = normalized
        settings.putString(ENDPOINT_KEY, normalized)
        settings.putBoolean(
            CLEARTEXT_ENDPOINT_CONFIRMED_KEY,
            validation.decision == EndpointTransportDecision.LocalCleartextConfirmed,
        )
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

    /**
     * Moves only the former built-in relay to HTTPS. A migration marker makes this a one-time
     * compatibility step, so a user can still deliberately select the legacy address later and
     * custom HTTP/self-hosted endpoints are never rewritten.
     */
    private fun loadEndpoint(): String {
        val stored = settings.getString(ENDPOINT_KEY, DEFAULT_ENDPOINT)
        val migrationDone = settings.getBoolean(HTTPS_ENDPOINT_MIGRATION_KEY, false)
        settings.putBoolean(HTTPS_ENDPOINT_MIGRATION_KEY, true)
        val migrated =
            if (!migrationDone && stored in FORMER_OFFICIAL_ENDPOINTS) {
                settings.putString(ENDPOINT_KEY, DEFAULT_ENDPOINT)
                settings.putBoolean(CLEARTEXT_ENDPOINT_CONFIRMED_KEY, false)
                DEFAULT_ENDPOINT
            } else {
                stored
            }
        val validation =
            validateServiceEndpoint(
                migrated,
                localCleartextConfirmed =
                    settings.getBoolean(
                        CLEARTEXT_ENDPOINT_CONFIRMED_KEY,
                        false,
                    ),
            )
        return if (validation.allowed) {
            validation.normalizedEndpoint ?: DEFAULT_ENDPOINT
        } else {
            // A persisted cleartext endpoint predates explicit consent. Fail closed rather than
            // silently reconnecting; the user can save a local endpoint again after confirmation.
            settings.putString(ENDPOINT_KEY, DEFAULT_ENDPOINT)
            settings.putBoolean(CLEARTEXT_ENDPOINT_CONFIRMED_KEY, false)
            DEFAULT_ENDPOINT
        }
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

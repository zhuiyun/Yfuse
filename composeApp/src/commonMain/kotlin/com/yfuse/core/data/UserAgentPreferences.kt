package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.network.DEFAULT_EMBY_USER_AGENT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserAgentPreferences(private val settings: Settings) {
    private companion object {
        const val KEY = "network.customUserAgent"
    }

    private val _customValue = MutableStateFlow(settings.getString(KEY, ""))
    private val _userAgent = MutableStateFlow(_customValue.value.effectiveUserAgent())

    /** Raw stored value — blank when the user has cleared the field. */
    val customValue: StateFlow<String> = _customValue.asStateFlow()

    /**
     * Value callers should actually send: the user's custom UA when set, otherwise the
     * stock "Emby for Android Mobile" string so server-side device lists and UA-gated
     * features see us as the official mobile client rather than whatever the platform
     * HTTP stack (`okhttp/4.x`, `libmpv/x`, …) would send by default.
     *
     * The stored value stays blank when the user clears the field, so [customValue] can
     * still feed the dialog's placeholder rendering.
     */
    val userAgent: StateFlow<String> = _userAgent.asStateFlow()

    fun setUserAgent(value: String) {
        val normalized = value.trim().replace("\r", "").replace("\n", "").take(512)
        _customValue.value = normalized
        _userAgent.value = normalized.effectiveUserAgent()
        if (normalized.isEmpty()) settings.remove(KEY) else settings.putString(KEY, normalized)
    }

    private fun String.effectiveUserAgent(): String = trim().ifBlank { DEFAULT_EMBY_USER_AGENT }
}

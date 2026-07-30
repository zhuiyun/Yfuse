package com.yfuse.core.data

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserAgentPreferences(private val settings: Settings) {
    private companion object {
        const val KEY = "network.customUserAgent"
    }

    private val _userAgent = MutableStateFlow(settings.getString(KEY, ""))
    val userAgent: StateFlow<String> = _userAgent.asStateFlow()

    fun setUserAgent(value: String) {
        val normalized = value.trim().replace("\r", "").replace("\n", "").take(512)
        _userAgent.value = normalized
        if (normalized.isEmpty()) settings.remove(KEY) else settings.putString(KEY, normalized)
    }
}

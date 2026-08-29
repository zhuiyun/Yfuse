package com.yfuse.core.sync

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared, atomic source of truth for the progress isolation boundary. */
class ProgressSyncPreferences(
    private val settings: Settings,
) {
    private val _enabled = MutableStateFlow(settings.getBoolean(SETTINGS_KEY, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        settings.putBoolean(SETTINGS_KEY, value)
        _enabled.value = value
    }

    private companion object {
        const val SETTINGS_KEY = "sync.progress"
    }
}

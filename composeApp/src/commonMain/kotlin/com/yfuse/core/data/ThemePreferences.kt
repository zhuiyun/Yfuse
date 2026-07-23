package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.designsystem.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted appearance settings: light/dark mode and accent colour. */
class ThemePreferences(private val settings: Settings) {

    private companion object {
        const val KEY_MODE = "theme.mode"
        const val KEY_ACCENT = "theme.accent"
    }

    private val _mode = MutableStateFlow(load(KEY_MODE, ThemeMode.entries, ThemeMode.Dark))
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    private val _accent = MutableStateFlow(load(KEY_ACCENT, AccentColor.entries, AccentColor.Blue))
    val accent: StateFlow<AccentColor> = _accent.asStateFlow()

    fun setMode(mode: ThemeMode) {
        _mode.value = mode
        settings.putString(KEY_MODE, mode.name)
    }

    fun setAccent(accent: AccentColor) {
        _accent.value = accent
        settings.putString(KEY_ACCENT, accent.name)
    }

    private fun <T : Enum<T>> load(key: String, values: List<T>, fallback: T): T {
        val stored = settings.getStringOrNull(key) ?: return fallback
        return values.firstOrNull { it.name == stored } ?: fallback
    }
}

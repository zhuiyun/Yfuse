package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.PlayerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted appearance settings: light/dark mode and accent colour. */
class ThemePreferences(private val settings: Settings) {

    private companion object {
        const val KEY_MODE = "theme.mode"
        const val KEY_ACCENT = "theme.accent"
        const val KEY_ENGINE = "player.engine"
        const val KEY_DECODER = "player.decoder"
        const val KEY_AUTO_NEXT = "player.autoNext"
        const val KEY_QUALITY = "player.quality"
        const val KEY_REDUCE_TRANSPARENCY = "accessibility.reduceTransparency"
        const val KEY_LARGE_TEXT = "accessibility.largeText"
        const val KEY_REDUCE_MOTION = "accessibility.reduceMotion"
    }

    // The design is the light "轻雾玻璃" direction; dark is the alternative.
    private val _mode = MutableStateFlow(load(KEY_MODE, ThemeMode.entries, ThemeMode.Light))
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    private val _accent = MutableStateFlow(load(KEY_ACCENT, AccentColor.entries, AccentColor.Blue))
    val accent: StateFlow<AccentColor> = _accent.asStateFlow()

    private val _engine = MutableStateFlow(load(KEY_ENGINE, PlayerEngine.selectable, PlayerEngine.Exo))

    /** Preferred playback backend; the player page can override it per session. */
    val engine: StateFlow<PlayerEngine> = _engine.asStateFlow()

    private val _decoder = MutableStateFlow(load(KEY_DECODER, DecoderMode.entries, DecoderMode.Hardware))

    /** Preferred decoder strategy, consumed when a playback activity starts. */
    val decoder: StateFlow<DecoderMode> = _decoder.asStateFlow()

    private val _autoNext = MutableStateFlow(settings.getBoolean(KEY_AUTO_NEXT, true))

    /** Whether an ended item advances to the next queue entry. */
    val autoNext: StateFlow<Boolean> = _autoNext.asStateFlow()

    private val _quality = MutableStateFlow(
        load(KEY_QUALITY, PlaybackQuality.entries, PlaybackQuality.Auto),
    )
    val quality: StateFlow<PlaybackQuality> = _quality.asStateFlow()

    private val _reduceTransparency =
        MutableStateFlow(settings.getBoolean(KEY_REDUCE_TRANSPARENCY, false))
    val reduceTransparency: StateFlow<Boolean> = _reduceTransparency.asStateFlow()

    private val _largeText = MutableStateFlow(settings.getBoolean(KEY_LARGE_TEXT, false))
    val largeText: StateFlow<Boolean> = _largeText.asStateFlow()

    private val _reduceMotion = MutableStateFlow(settings.getBoolean(KEY_REDUCE_MOTION, false))
    val reduceMotion: StateFlow<Boolean> = _reduceMotion.asStateFlow()

    fun setEngine(engine: PlayerEngine) {
        if (!engine.available) return
        _engine.value = engine
        settings.putString(KEY_ENGINE, engine.name)
    }

    fun setDecoder(decoder: DecoderMode) {
        _decoder.value = decoder
        settings.putString(KEY_DECODER, decoder.name)
    }

    fun setAutoNext(enabled: Boolean) {
        _autoNext.value = enabled
        settings.putBoolean(KEY_AUTO_NEXT, enabled)
    }

    fun setQuality(quality: PlaybackQuality) {
        _quality.value = quality
        settings.putString(KEY_QUALITY, quality.name)
    }

    fun setMode(mode: ThemeMode) {
        _mode.value = mode
        settings.putString(KEY_MODE, mode.name)
    }

    fun setAccent(accent: AccentColor) {
        _accent.value = accent
        settings.putString(KEY_ACCENT, accent.name)
    }

    fun setReduceTransparency(enabled: Boolean) {
        _reduceTransparency.value = enabled
        settings.putBoolean(KEY_REDUCE_TRANSPARENCY, enabled)
    }

    fun setLargeText(enabled: Boolean) {
        _largeText.value = enabled
        settings.putBoolean(KEY_LARGE_TEXT, enabled)
    }

    fun setReduceMotion(enabled: Boolean) {
        _reduceMotion.value = enabled
        settings.putBoolean(KEY_REDUCE_MOTION, enabled)
    }

    private fun <T : Enum<T>> load(key: String, values: List<T>, fallback: T): T {
        val stored = settings.getStringOrNull(key) ?: return fallback
        return values.firstOrNull { it.name == stored } ?: fallback
    }
}

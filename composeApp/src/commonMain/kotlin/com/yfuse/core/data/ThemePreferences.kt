package com.yfuse.core.data

import androidx.compose.ui.graphics.Color
import com.russhwolf.settings.Settings
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.DEFAULT_BACKGROUND_DIM
import com.yfuse.core.designsystem.GlassStyle
import com.yfuse.core.designsystem.SplashAnimation
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.model.ServerLayout
import com.yfuse.core.model.StartupTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted appearance, accessibility, and playback preferences. */
class ThemePreferences(
    private val settings: Settings,
) {
    private companion object {
        const val KEY_MODE = "theme.mode"
        const val KEY_ENGINE = "player.engine"
        const val KEY_DECODER = "player.decoder"
        const val KEY_AUTO_NEXT = "player.autoNext"
        const val KEY_REDUCE_TRANSPARENCY = "accessibility.reduceTransparency"
        const val KEY_LARGE_TEXT = "accessibility.largeText"
        const val KEY_REDUCE_MOTION = "accessibility.reduceMotion"
        const val KEY_SPLASH_ANIMATION = "appearance.splashAnimation"
        const val KEY_SPLASH_VARIANT = "appearance.splashVariant.v2"
        const val KEY_STARTUP_TAB = "appearance.startupTab"
        const val KEY_GLASS_STYLE = "appearance.glassStyle"
        const val KEY_SERVER_LAYOUT = "appearance.serverLayout"
        const val KEY_BACKGROUND_IMAGE = "appearance.backgroundImage"
        const val KEY_BACKGROUND_DIM = "appearance.backgroundDim"
        const val MAX_BACKGROUND_URI_CHARS = 2_048
    }

    private val _mode = MutableStateFlow(load(KEY_MODE, ThemeMode.entries, ThemeMode.Light))
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    /**
     * Transitional source compatibility only. This is fixed product identity, not a user
     * preference: it is never persisted and has no setter.
     */
    private val fixedBrandEmphasis = MutableStateFlow<Color>(Brand.Primary)
    internal val accent: StateFlow<Color> = fixedBrandEmphasis.asStateFlow()

    private val _engine = MutableStateFlow(load(KEY_ENGINE, PlayerEngine.selectable, PlayerEngine.Exo))
    val engine: StateFlow<PlayerEngine> = _engine.asStateFlow()

    private val _decoder = MutableStateFlow(load(KEY_DECODER, DecoderMode.entries, DecoderMode.Hardware))
    val decoder: StateFlow<DecoderMode> = _decoder.asStateFlow()

    private val _autoNext = MutableStateFlow(settings.getBoolean(KEY_AUTO_NEXT, true))
    val autoNext: StateFlow<Boolean> = _autoNext.asStateFlow()

    private val _reduceTransparency = MutableStateFlow(settings.getBoolean(KEY_REDUCE_TRANSPARENCY, false))
    val reduceTransparency: StateFlow<Boolean> = _reduceTransparency.asStateFlow()

    private val _largeText = MutableStateFlow(settings.getBoolean(KEY_LARGE_TEXT, false))
    val largeText: StateFlow<Boolean> = _largeText.asStateFlow()

    private val _reduceMotion = MutableStateFlow(settings.getBoolean(KEY_REDUCE_MOTION, false))
    val reduceMotion: StateFlow<Boolean> = _reduceMotion.asStateFlow()

    private val _splashAnimation = MutableStateFlow(settings.getBoolean(KEY_SPLASH_ANIMATION, true))
    val splashAnimation: StateFlow<Boolean> = _splashAnimation.asStateFlow()

    private val _startupTab = MutableStateFlow(load(KEY_STARTUP_TAB, StartupTab.entries, StartupTab.Automatic))
    val startupTab: StateFlow<StartupTab> = _startupTab.asStateFlow()

    private val _glassStyle = MutableStateFlow(load(KEY_GLASS_STYLE, GlassStyle.entries, GlassStyle.Liquid))
    val glassStyle: StateFlow<GlassStyle> = _glassStyle.asStateFlow()

    private val _serverLayout = MutableStateFlow(load(KEY_SERVER_LAYOUT, ServerLayout.entries, ServerLayout.Grid))
    val serverLayout: StateFlow<ServerLayout> = _serverLayout.asStateFlow()

    private val _backgroundImage =
        MutableStateFlow(settings.getStringOrNull(KEY_BACKGROUND_IMAGE)?.takeIf(String::isNotBlank))
    val backgroundImage: StateFlow<String?> = _backgroundImage.asStateFlow()

    private val _backgroundDim =
        MutableStateFlow(
            settings.getFloat(KEY_BACKGROUND_DIM, DEFAULT_BACKGROUND_DIM).coerceIn(0f, 1f),
        )
    val backgroundDim: StateFlow<Float> = _backgroundDim.asStateFlow()

    private val _splashVariant =
        MutableStateFlow(load(KEY_SPLASH_VARIANT, SplashAnimation.entries, SplashAnimation.One))
    val splashVariant: StateFlow<SplashAnimation> = _splashVariant.asStateFlow()

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

    fun setMode(mode: ThemeMode) {
        _mode.value = mode
        settings.putString(KEY_MODE, mode.name)
    }

    fun setServerLayout(layout: ServerLayout) {
        _serverLayout.value = layout
        settings.putString(KEY_SERVER_LAYOUT, layout.name)
    }

    fun setGlassStyle(style: GlassStyle) {
        _glassStyle.value = style
        settings.putString(KEY_GLASS_STYLE, style.name)
    }

    fun setBackgroundImage(uri: String?) {
        val normalized = uri?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_BACKGROUND_URI_CHARS }
        _backgroundImage.value = normalized
        if (normalized ==
            null
        ) {
            settings.remove(KEY_BACKGROUND_IMAGE)
        } else {
            settings.putString(KEY_BACKGROUND_IMAGE, normalized)
        }
    }

    fun setBackgroundDim(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _backgroundDim.value = clamped
        settings.putFloat(KEY_BACKGROUND_DIM, clamped)
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

    fun setSplashAnimation(enabled: Boolean) {
        _splashAnimation.value = enabled
        settings.putBoolean(KEY_SPLASH_ANIMATION, enabled)
    }

    fun setStartupTab(tab: StartupTab) {
        _startupTab.value = tab
        settings.putString(KEY_STARTUP_TAB, tab.name)
    }

    fun setSplashVariant(variant: SplashAnimation) {
        _splashVariant.value = variant
        settings.putString(KEY_SPLASH_VARIANT, variant.name)
    }

    private fun <T : Enum<T>> load(
        key: String,
        values: List<T>,
        fallback: T,
    ): T {
        val stored = settings.getStringOrNull(key) ?: return fallback
        return values.firstOrNull { it.name == stored } ?: fallback
    }
}

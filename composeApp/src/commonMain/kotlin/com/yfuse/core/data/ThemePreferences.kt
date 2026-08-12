package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.designsystem.DEFAULT_BACKGROUND_DIM
import com.yfuse.core.designsystem.GlassStyle
import com.yfuse.core.designsystem.SplashAnimation
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.model.StartupTab
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
        const val KEY_SPLASH_ANIMATION = "appearance.splashAnimation"
        /**
         * Bumped with the water-fire rework. The two variants behind One/Two were replaced
         * wholesale — different artwork, different choreography — so a stored "Two" now
         * selects an animation its owner never chose. Reading a new key drops those values
         * and lands everyone on the current default, which is the design's own pick.
         */
        const val KEY_SPLASH_VARIANT = "appearance.splashVariant.v2"
        const val KEY_STARTUP_TAB = "appearance.startupTab"
        const val KEY_GLASS_STYLE = "appearance.glassStyle"
        const val KEY_BACKGROUND_IMAGE = "appearance.backgroundImage"
        const val KEY_BACKGROUND_DIM = "appearance.backgroundDim"
        /** A content:// grant is long but not unbounded; refuse anything that is not a URI. */
        const val MAX_BACKGROUND_URI_CHARS = 2_048
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

    private val _splashAnimation =
        MutableStateFlow(settings.getBoolean(KEY_SPLASH_ANIMATION, true))
    val splashAnimation: StateFlow<Boolean> = _splashAnimation.asStateFlow()

    private val _startupTab =
        MutableStateFlow(load(KEY_STARTUP_TAB, StartupTab.entries, StartupTab.Automatic))

    /** Which tab a cold start lands on; see [StartupTab]. */
    val startupTab: StateFlow<StartupTab> = _startupTab.asStateFlow()

    private val _glassStyle =
        MutableStateFlow(load(KEY_GLASS_STYLE, GlassStyle.entries, GlassStyle.Liquid))

    /** Which material floating surfaces are drawn in; see [GlassStyle]. */
    val glassStyle: StateFlow<GlassStyle> = _glassStyle.asStateFlow()

    private val _backgroundImage =
        MutableStateFlow(settings.getStringOrNull(KEY_BACKGROUND_IMAGE)?.takeIf(String::isNotBlank))

    /** A persisted content URI for the page backdrop, or null for the theme's own ground. */
    val backgroundImage: StateFlow<String?> = _backgroundImage.asStateFlow()

    private val _backgroundDim = MutableStateFlow(
        settings.getFloat(KEY_BACKGROUND_DIM, DEFAULT_BACKGROUND_DIM).coerceIn(0f, 1f),
    )

    /**
     * How much of the page's own ground is laid over the picture.
     *
     * Text in this app is sized and coloured against a flat palette, so a photograph behind
     * it is a contrast problem before it is a decoration. The default keeps the wallpaper
     * legible as an atmosphere while leaving the copy on a surface it was designed for.
     */
    val backgroundDim: StateFlow<Float> = _backgroundDim.asStateFlow()

    private val _splashVariant =
        MutableStateFlow(load(KEY_SPLASH_VARIANT, SplashAnimation.entries, SplashAnimation.One))

    /** Which launch choreography plays when [splashAnimation] is on. */
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

    fun setQuality(quality: PlaybackQuality) {
        _quality.value = quality
        settings.putString(KEY_QUALITY, quality.name)
    }

    fun setMode(mode: ThemeMode) {
        _mode.value = mode
        settings.putString(KEY_MODE, mode.name)
    }

    fun setGlassStyle(style: GlassStyle) {
        _glassStyle.value = style
        settings.putString(KEY_GLASS_STYLE, style.name)
    }

    /** [uri] is a persisted content grant; null clears the picture. */
    fun setBackgroundImage(uri: String?) {
        val normalized = uri
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_BACKGROUND_URI_CHARS }
        _backgroundImage.value = normalized
        if (normalized == null) {
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

    private fun <T : Enum<T>> load(key: String, values: List<T>, fallback: T): T {
        val stored = settings.getStringOrNull(key) ?: return fallback
        return values.firstOrNull { it.name == stored } ?: fallback
    }
}

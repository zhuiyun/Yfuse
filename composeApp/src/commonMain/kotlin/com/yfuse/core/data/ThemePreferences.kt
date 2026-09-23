package com.yfuse.core.data

import androidx.compose.ui.graphics.Color
import com.russhwolf.settings.Settings
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.DEFAULT_BACKGROUND_DIM
import com.yfuse.core.designsystem.DialogAnimation
import com.yfuse.core.designsystem.GlassMaterial
import com.yfuse.core.designsystem.GlassMaterials
import com.yfuse.core.designsystem.GlassStyle
import com.yfuse.core.designsystem.LoadingAnimation
import com.yfuse.core.designsystem.ParticleLight
import com.yfuse.core.designsystem.ParticleStyle
import com.yfuse.core.designsystem.SplashAnimation
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.model.ServerLayout
import com.yfuse.core.model.StartupTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted appearance, accessibility, and playback preferences.
 *
 * Appearance keeps the switches that change what the product does — theme, glass, wallpaper,
 * server layout, where a launch lands — and the on/off for the few decorative systems. The
 * pickers that only chose *which* of many variants to show (particle style, splash variant,
 * the dialog "lab") went with the variants; their stored values are scrubbed on load.
 */
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
        const val KEY_PARTICLE_LIGHT = "appearance.particleLight"
        const val KEY_PULSE_SWEEP = "appearance.pulseSweep"
        const val KEY_LIBRARY_CAROUSEL = "appearance.libraryCarousel"
        const val KEY_SPLASH_ANIMATION = "appearance.splashAnimation"
        const val KEY_STARTUP_TAB = "appearance.startupTab"
        const val KEY_DIALOG_ANIMATION = "appearance.dialogAnimation"
        const val KEY_GLASS_STYLE = "appearance.glassStyle"
        const val KEY_GLASS_LIGHT = "appearance.glassMaterial.light"
        const val KEY_GLASS_DARK = "appearance.glassMaterial.dark"
        const val KEY_LOADING_ANIMATION = "appearance.loadingAnimation"
        const val KEY_SERVER_LAYOUT = "appearance.serverLayout"
        const val KEY_BACKGROUND_IMAGE = "appearance.backgroundImage"
        const val KEY_BACKGROUND_DIM = "appearance.backgroundDim"
        const val MAX_BACKGROUND_URI_CHARS = 2_048

        /** Written by earlier builds for pickers that no longer exist. */
        val RETIRED_KEYS =
            listOf(
                "appearance.particleStyle",
                "appearance.splashVariant.v2",
                "appearance.dialogAnimationLab",
            )
    }

    init {
        RETIRED_KEYS.forEach(settings::remove)
    }

    // 深色为主: the product's material is dark glass, and a first launch should look like it.
    private val _mode = MutableStateFlow(load(KEY_MODE, ThemeMode.entries, ThemeMode.Dark))
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    /**
     * Transitional source compatibility for the Android player only. This is fixed product
     * identity, not a user preference: it is never persisted and has no setter.
     */
    @Deprecated("Emphasis is Brand.Primary; YfuseTheme no longer takes an accent.")
    internal val accent: StateFlow<Color> = MutableStateFlow<Color>(Brand.Primary).asStateFlow()

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

    private val _particleLight = MutableStateFlow(load(KEY_PARTICLE_LIGHT, ParticleLight.entries, ParticleLight.Gentle))
    val particleLight: StateFlow<ParticleLight> = _particleLight.asStateFlow()

    fun setParticleLight(mode: ParticleLight) {
        _particleLight.value = mode
        settings.putString(KEY_PARTICLE_LIGHT, mode.name)
    }

    /**
     * The one particle style. Not a preference any more — the picker offered three shapes for
     * the same feedback — and never persisted. Kept as a flow for the Android player, which is
     * migrated in the next wave.
     */
    @Deprecated("There is one particle style; read LocalParticleStyle or pass nothing.")
    val particleStyle: StateFlow<ParticleStyle> = MutableStateFlow(ParticleStyle.Stardust).asStateFlow()

    private val _pulseSweep = MutableStateFlow(settings.getBoolean(KEY_PULSE_SWEEP, true))
    val pulseSweep: StateFlow<Boolean> = _pulseSweep.asStateFlow()

    fun setPulseSweep(enabled: Boolean) {
        _pulseSweep.value = enabled
        settings.putBoolean(KEY_PULSE_SWEEP, enabled)
    }

    private val _libraryCarousel = MutableStateFlow(settings.getBoolean(KEY_LIBRARY_CAROUSEL, true))
    val libraryCarousel: StateFlow<Boolean> = _libraryCarousel.asStateFlow()

    fun setLibraryCarousel(enabled: Boolean) {
        settings.putBoolean(KEY_LIBRARY_CAROUSEL, enabled)
        _libraryCarousel.value = enabled
    }

    // Whether a launch plays the splash at all — a real choice, unlike *which* one plays: that is
    // [SplashAnimation.forMotion] and nothing is stored for it.
    private val _splashAnimation = MutableStateFlow(settings.getBoolean(KEY_SPLASH_ANIMATION, true))
    val splashAnimation: StateFlow<Boolean> = _splashAnimation.asStateFlow()

    private val _startupTab = MutableStateFlow(load(KEY_STARTUP_TAB, StartupTab.entries, StartupTab.Automatic))
    val startupTab: StateFlow<StartupTab> = _startupTab.asStateFlow()

    // A name from a retired style falls back to Lift inside [load]; nothing here can throw.
    private val _dialogAnimation =
        MutableStateFlow(load(KEY_DIALOG_ANIMATION, DialogAnimation.entries, DialogAnimation.Lift))
    val dialogAnimation: StateFlow<DialogAnimation> = _dialogAnimation.asStateFlow()

    fun setDialogAnimation(animation: DialogAnimation) {
        _dialogAnimation.value = animation
        settings.putString(KEY_DIALOG_ANIMATION, animation.name)
    }

    private val _glassStyle = MutableStateFlow(load(KEY_GLASS_STYLE, GlassStyle.entries, GlassStyle.Liquid))
    val glassStyle: StateFlow<GlassStyle> = _glassStyle.asStateFlow()

    private val _glassMaterials =
        MutableStateFlow(
            GlassMaterials(
                light = GlassMaterial.decode(settings.getStringOrNull(KEY_GLASS_LIGHT), dark = false),
                dark = GlassMaterial.decode(settings.getStringOrNull(KEY_GLASS_DARK), dark = true),
            ),
        )
    val glassMaterials: StateFlow<GlassMaterials> = _glassMaterials.asStateFlow()

    fun setGlassMaterial(
        dark: Boolean,
        material: GlassMaterial,
    ) {
        val value = material.normalized(dark)
        settings.putString(if (dark) KEY_GLASS_DARK else KEY_GLASS_LIGHT, value.encode())
        _glassMaterials.value =
            if (dark) _glassMaterials.value.copy(dark = value) else _glassMaterials.value.copy(light = value)
    }

    private val _loadingAnimation =
        MutableStateFlow(load(KEY_LOADING_ANIMATION, LoadingAnimation.entries, LoadingAnimation.Orbit))
    val loadingAnimation: StateFlow<LoadingAnimation> = _loadingAnimation.asStateFlow()

    fun setLoadingAnimation(animation: LoadingAnimation) {
        settings.putString(KEY_LOADING_ANIMATION, animation.name)
        _loadingAnimation.value = animation
    }

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

    /**
     * The launch choreography. There is one now, and the shell picks its still-frame variant
     * from the accessibility state, so this is fixed and never persisted. Kept as a flow for
     * the cloud snapshot until that schema drops the field.
     */
    @Deprecated("There is one launch animation; see SplashAnimation.forMotion.")
    val splashVariant: StateFlow<SplashAnimation> = MutableStateFlow(SplashAnimation.One).asStateFlow()

    /** No-op: see [splashVariant]. */
    @Deprecated("There is one launch animation; nothing to set.")
    @Suppress("UNUSED_PARAMETER")
    fun setSplashVariant(variant: SplashAnimation) = Unit

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

    private fun <T : Enum<T>> load(
        key: String,
        values: List<T>,
        fallback: T,
    ): T {
        val stored = settings.getStringOrNull(key) ?: return fallback
        return values.firstOrNull { it.name == stored } ?: fallback
    }
}

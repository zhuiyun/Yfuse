package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.designsystem.DialogAnimation
import com.yfuse.core.designsystem.GlassStyle
import com.yfuse.core.designsystem.LoadingAnimation
import com.yfuse.core.designsystem.MotionTheme
import com.yfuse.core.designsystem.ParticleLight
import com.yfuse.core.designsystem.PlayerTransitionStyle
import com.yfuse.core.designsystem.SplashAnimation
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.ServerLayout
import com.yfuse.core.model.StartupTab
import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.core.playback.PlaybackOptimizationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercise every choice, both toggle directions and a fresh repository over the saved settings. */
class SettingsRoundTripTest {
    @Test
    fun ThemePreferences_autoNext_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in listOf(true, false, true)) {
            prefs.setAutoNext(value)
            assertEquals(value, prefs.autoNext.value)
            assertEquals(value, ThemePreferences(settings).autoNext.value)
        }
    }

    @Test
    fun ThemePreferences_reduceTransparency_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in listOf(true, false, true)) {
            prefs.setReduceTransparency(value)
            assertEquals(value, prefs.reduceTransparency.value)
            assertEquals(value, ThemePreferences(settings).reduceTransparency.value)
        }
    }

    @Test
    fun ThemePreferences_largeText_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in listOf(true, false, true)) {
            prefs.setLargeText(value)
            assertEquals(value, prefs.largeText.value)
            assertEquals(value, ThemePreferences(settings).largeText.value)
        }
    }

    @Test
    fun ThemePreferences_reduceMotion_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in listOf(true, false, true)) {
            prefs.setReduceMotion(value)
            assertEquals(value, prefs.reduceMotion.value)
            assertEquals(value, ThemePreferences(settings).reduceMotion.value)
        }
    }

    @Test
    fun ThemePreferences_pulseSweep_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in listOf(true, false, true)) {
            prefs.setPulseSweep(value)
            assertEquals(value, prefs.pulseSweep.value)
            assertEquals(value, ThemePreferences(settings).pulseSweep.value)
        }
    }

    @Test
    fun ThemePreferences_libraryCarousel_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in listOf(true, false, true)) {
            prefs.setLibraryCarousel(value)
            assertEquals(value, prefs.libraryCarousel.value)
            assertEquals(value, ThemePreferences(settings).libraryCarousel.value)
        }
    }

    @Test
    fun ThemePreferences_customHomeVisible_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in listOf(true, false, true)) {
            prefs.setCustomHomeVisible(value)
            assertEquals(value, prefs.customHomeVisible.value)
            assertEquals(value, ThemePreferences(settings).customHomeVisible.value)
        }
    }

    @Test
    fun ThemePreferences_navCollapseOnScroll_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in listOf(true, false, true)) {
            prefs.setNavCollapseOnScroll(value)
            assertEquals(value, prefs.navCollapseOnScroll.value)
            assertEquals(value, ThemePreferences(settings).navCollapseOnScroll.value)
        }
    }

    @Test
    fun ThemePreferences_splashAnimation_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in listOf(true, false, true)) {
            prefs.setSplashAnimation(value)
            assertEquals(value, prefs.splashAnimation.value)
            assertEquals(value, ThemePreferences(settings).splashAnimation.value)
        }
    }

    @Test
    fun ThemePreferences_mode_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in ThemeMode.entries) {
            prefs.setMode(value)
            assertEquals(value, prefs.mode.value)
            assertEquals(value, ThemePreferences(settings).mode.value)
        }
    }

    @Test
    fun ThemePreferences_glassStyle_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in GlassStyle.entries) {
            prefs.setGlassStyle(value)
            assertEquals(value, prefs.glassStyle.value)
            assertEquals(value, ThemePreferences(settings).glassStyle.value)
        }
    }

    @Test
    fun ThemePreferences_motionTheme_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in MotionTheme.entries) {
            prefs.setMotionTheme(value)
            assertEquals(value, prefs.motionTheme.value)
            assertEquals(value, ThemePreferences(settings).motionTheme.value)
        }
    }

    @Test
    fun ThemePreferences_dialogAnimation_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in DialogAnimation.entries) {
            prefs.setDialogAnimation(value)
            assertEquals(value, prefs.dialogAnimation.value)
            assertEquals(value, ThemePreferences(settings).dialogAnimation.value)
        }
    }

    @Test
    fun ThemePreferences_loadingAnimation_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in LoadingAnimation.entries) {
            prefs.setLoadingAnimation(value)
            assertEquals(value, prefs.loadingAnimation.value)
            assertEquals(value, ThemePreferences(settings).loadingAnimation.value)
        }
    }

    @Test
    fun ThemePreferences_playerTransition_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in PlayerTransitionStyle.entries) {
            prefs.setPlayerTransition(value)
            assertEquals(value, prefs.playerTransition.value)
            assertEquals(value, ThemePreferences(settings).playerTransition.value)
        }
    }

    @Test
    fun ThemePreferences_particleLight_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in ParticleLight.entries) {
            prefs.setParticleLight(value)
            assertEquals(value, prefs.particleLight.value)
            assertEquals(value, ThemePreferences(settings).particleLight.value)
        }
    }

    @Test
    fun ThemePreferences_splashVariant_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in SplashAnimation.selectable) {
            prefs.setSplashVariant(value)
            assertEquals(value, prefs.splashVariant.value)
            assertEquals(value, ThemePreferences(settings).splashVariant.value)
        }
    }

    @Test
    fun ThemePreferences_startupTab_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in StartupTab.selectable) {
            prefs.setStartupTab(value)
            assertEquals(value, prefs.startupTab.value)
            assertEquals(value, ThemePreferences(settings).startupTab.value)
        }
    }

    @Test
    fun ThemePreferences_serverLayout_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in ServerLayout.entries) {
            prefs.setServerLayout(value)
            assertEquals(value, prefs.serverLayout.value)
            assertEquals(value, ThemePreferences(settings).serverLayout.value)
        }
    }

    @Test
    fun ThemePreferences_decoder_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        for (value in DecoderMode.entries) {
            prefs.setDecoder(value)
            assertEquals(value, prefs.decoder.value)
            assertEquals(value, ThemePreferences(settings).decoder.value)
        }
    }

    @Test
    fun PlaybackPreferences_showFrameRate_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in listOf(true, false, true)) {
            prefs.setShowFrameRate(value)
            assertEquals(value, prefs.showFrameRate.value)
            assertEquals(value, PlaybackPreferences(settings).showFrameRate.value)
        }
    }

    @Test
    fun PlaybackPreferences_backgroundAudio_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in listOf(true, false, true)) {
            prefs.setBackgroundAudio(value)
            assertEquals(value, prefs.backgroundAudio.value)
            assertEquals(value, PlaybackPreferences(settings).backgroundAudio.value)
        }
    }

    @Test
    fun PlaybackPreferences_ambientLight_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in listOf(true, false, true)) {
            prefs.setAmbientLight(value)
            assertEquals(value, prefs.ambientLight.value)
            assertEquals(value, PlaybackPreferences(settings).ambientLight.value)
        }
    }

    @Test
    fun PlaybackPreferences_smartCrossServerSource_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in listOf(true, false, true)) {
            prefs.setSmartCrossServerSource(value)
            assertEquals(value, prefs.smartCrossServerSource.value)
            assertEquals(value, PlaybackPreferences(settings).smartCrossServerSource.value)
        }
    }

    @Test
    fun PlaybackPreferences_anonymousQoeSharing_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in listOf(true, false, true)) {
            prefs.setAnonymousQoeSharing(value)
            assertEquals(value, prefs.anonymousQoeSharing.value)
            assertEquals(value, PlaybackPreferences(settings).anonymousQoeSharing.value)
        }
    }

    @Test
    fun PlaybackPreferences_detailThemeSong_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in listOf(true, false, true)) {
            prefs.setDetailThemeSong(value)
            assertEquals(value, prefs.detailThemeSong.value)
            assertEquals(value, PlaybackPreferences(settings).detailThemeSong.value)
        }
    }

    @Test
    fun PlaybackPreferences_core2TrialEnabled_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in listOf(true, false, true)) {
            prefs.setCore2TrialEnabled(value)
            assertEquals(value, prefs.core2TrialEnabled.value)
            assertEquals(value, PlaybackPreferences(settings).core2TrialEnabled.value)
        }
    }

    @Test
    fun PlaybackPreferences_anime4KMode_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in Anime4KMode.entries) {
            prefs.setAnime4KMode(value)
            assertEquals(value, prefs.anime4KMode.value)
            assertEquals(value, PlaybackPreferences(settings).anime4KMode.value)
        }
    }

    @Test
    fun PlaybackPreferences_videoCacheSize_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in VideoCacheSize.entries) {
            prefs.setVideoCacheSize(value)
            assertEquals(value, prefs.videoCacheSize.value)
            assertEquals(value, PlaybackPreferences(settings).videoCacheSize.value)
        }
    }

    @Test
    fun PlaybackPreferences_portraitVideoOrientation_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in PortraitVideoOrientation.entries) {
            prefs.setPortraitVideoOrientation(value)
            assertEquals(value, prefs.portraitVideoOrientation.value)
            assertEquals(value, PlaybackPreferences(settings).portraitVideoOrientation.value)
        }
    }

    @Test
    fun PlaybackPreferences_sourcePreheat_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in SourcePreheatMode.entries) {
            prefs.setSourcePreheat(value)
            assertEquals(value, prefs.sourcePreheat.value)
            assertEquals(value, PlaybackPreferences(settings).sourcePreheat.value)
        }
    }

    @Test
    fun PlaybackPreferences_yCoreBufferDuration_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in YCoreBufferDuration.entries) {
            prefs.setYCoreBufferDuration(value)
            assertEquals(value, prefs.yCoreBufferDuration.value)
            assertEquals(value, PlaybackPreferences(settings).yCoreBufferDuration.value)
        }
    }

    @Test
    fun PlaybackPreferences_frameRateMatch_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in PlaybackFrameRateMatch.entries) {
            prefs.setFrameRateMatch(value)
            assertEquals(value, prefs.frameRateMatch.value)
            assertEquals(value, PlaybackPreferences(settings).frameRateMatch.value)
        }
    }

    @Test
    fun PlaybackPreferences_audioPassthrough_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in PlaybackAudioPassthrough.entries) {
            prefs.setAudioPassthrough(value)
            assertEquals(value, prefs.audioPassthrough.value)
            assertEquals(value, PlaybackPreferences(settings).audioPassthrough.value)
        }
    }

    @Test
    fun PlaybackPreferences_mediaVersionPreference_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in MediaVersionPreference.entries) {
            prefs.setMediaVersionPreference(value)
            assertEquals(value, prefs.mediaVersionPreference.value)
            assertEquals(value, PlaybackPreferences(settings).mediaVersionPreference.value)
        }
    }

    @Test
    fun PlaybackPreferences_optimizationMode_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in PlaybackOptimizationMode.entries) {
            prefs.setOptimizationMode(value)
            assertEquals(value, prefs.optimizationMode.value)
            assertEquals(value, PlaybackPreferences(settings).optimizationMode.value)
        }
    }

    @Test
    fun PlaybackPreferences_engineSelection_saves_every_selection_and_restores_it() {
        val settings = MapSettings()
        val prefs = PlaybackPreferences(settings)
        for (value in PlaybackEngineSelection.selectable) {
            prefs.setEngineSelection(value)
            assertEquals(value, prefs.engineSelection.value)
            assertEquals(value, PlaybackPreferences(settings).engineSelection.value)
        }
    }

    @Test
    fun invalid_numeric_settings_never_reach_the_player_or_the_background_renderer() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val stored =
                MapSettings().apply {
                    putFloat("player.defaultSpeed", value)
                    putFloat("appearance.backgroundDim", value)
                }
            val playback = PlaybackPreferences(stored)
            val theme = ThemePreferences(stored)
            assertTrue(playback.defaultSpeed.value.isFinite())
            assertTrue(theme.backgroundDim.value.isFinite())
            playback.setDefaultSpeed(value)
            theme.setBackgroundDim(value)
            assertEquals(1f, PlaybackPreferences(stored).defaultSpeed.value)
            assertTrue(ThemePreferences(stored).backgroundDim.value.isFinite())
        }
    }

    @Test
    fun legacy_startup_choice_has_the_same_value_before_and_after_restart() {
        val stored = MapSettings()
        val theme = ThemePreferences(stored)
        theme.setStartupTab(StartupTab.Home)
        assertEquals(StartupTab.Library, theme.startupTab.value)
        assertEquals(theme.startupTab.value, ThemePreferences(stored).startupTab.value)
    }
}

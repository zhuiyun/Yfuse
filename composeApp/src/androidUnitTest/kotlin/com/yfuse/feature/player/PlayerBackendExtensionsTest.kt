package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscMenuCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerBackendExtensionsTest {
    @Test
    fun forwards_optional_controls_without_exposing_the_engine_to_callers() {
        val engine = FakeBackendEngine()
        val extensions = PlayerBackendExtensions(engine)

        assertTrue(extensions.supportsSecondarySubtitleTrack)
        assertTrue(extensions.supportsAudioDelay)
        assertTrue(extensions.supportsSubtitleOffset)
        assertTrue(extensions.supportsSubtitleScale)
        assertTrue(extensions.supportsSubtitleBrightness)
        assertTrue(extensions.supportsSubtitlePosition)
        assertTrue(extensions.setAudioDelayMs(125L))
        assertTrue(extensions.selectSecondarySubtitleTrack("7"))
        assertTrue(extensions.setSubtitleOffsetMs(-80L))
        assertTrue(extensions.setSubtitleScale(1.25f))
        assertTrue(extensions.setSubtitleBrightness(0.8f))
        assertTrue(extensions.setSubtitlePosition(0.75f))
        extensions.setPauseAtEndOfCurrentItem(true)
        assertTrue(extensions.switchToTranscode("decoder"))

        assertEquals(125L, engine.audioDelayMs)
        assertEquals("7", engine.secondarySubtitleId)
        assertEquals(-80L, engine.subtitleOffsetMs)
        assertEquals(1.25f, engine.subtitleScale)
        assertEquals(0.8f, engine.subtitleBrightness)
        assertEquals(0.75f, engine.subtitlePosition)
        assertTrue(engine.pauseAtEnd)
        assertEquals("decoder", engine.transcodeReason)
    }

    @Test
    fun unsupported_scale_and_disc_controls_fail_explicitly() {
        val extensions = PlayerBackendExtensions(FakeBackendEngine())

        assertTrue(extensions.setVideoScaleMode(VideoScaleMode.Fit))
        assertFalse(extensions.setVideoScaleMode(VideoScaleMode.Fill))
        assertFalse(extensions.selectDiscTitle(1))
        assertFalse(extensions.selectDiscChapter(1))
        assertFalse(extensions.showDiscMenu())
    }
}

private class FakeBackendEngine : VideoEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState
    override val supportsSecondarySubtitleTrack: Boolean = true
    override val supportsAudioDelay: Boolean = true
    override val supportsSubtitleOffset: Boolean = true
    override val supportsSubtitleScale: Boolean = true
    override val supportsSubtitleBrightness: Boolean = true
    override val supportsSubtitlePosition: Boolean = true

    var audioDelayMs: Long? = null
    var secondarySubtitleId: String? = null
    var subtitleOffsetMs: Long? = null
    var subtitleScale: Float? = null
    var subtitleBrightness: Float? = null
    var subtitlePosition: Float? = null
    var pauseAtEnd: Boolean = false
    var transcodeReason: String? = null

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun setSpeed(speed: Float) = Unit

    override fun selectAudioTrack(id: String) = Unit

    override fun setAudioDelayMs(delayMs: Long): Boolean {
        this.audioDelayMs = delayMs
        return true
    }

    override fun selectSubtitleTrack(id: String) = Unit

    override fun selectSecondarySubtitleTrack(id: String): Boolean {
        secondarySubtitleId = id
        return true
    }

    override fun setSubtitleOffsetMs(offsetMs: Long): Boolean {
        subtitleOffsetMs = offsetMs
        return true
    }

    override fun setSubtitleScale(scale: Float): Boolean {
        subtitleScale = scale
        return true
    }

    override fun setSubtitleBrightness(brightness: Float): Boolean {
        subtitleBrightness = brightness
        return true
    }

    override fun setSubtitlePosition(position: Float): Boolean {
        subtitlePosition = position
        return true
    }

    override fun setPauseAtEndOfCurrentItem(enabled: Boolean) {
        pauseAtEnd = enabled
    }

    override fun selectItem(index: Int) = Unit

    override fun currentPositionMs(): Long = 0L

    override fun retry() = Unit

    override fun switchToTranscode(reason: String?): Boolean {
        transcodeReason = reason
        return true
    }

    override fun sendDiscMenuCommand(command: PlaybackDiscMenuCommand): Boolean = false

    override fun release() = Unit
}

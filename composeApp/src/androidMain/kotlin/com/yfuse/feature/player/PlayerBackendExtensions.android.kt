package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core2.api.YPlayer

/**
 * Temporary compatibility boundary for controls that are not part of the stable [YPlayer] API.
 *
 * Product UI depends on this capability facade while Legacy and Core2 coexist. Unsupported Core2
 * operations keep their existing false/default behavior, allowing PlayerRoot to rebuild or fall
 * back without leaking concrete backend types through the control layer.
 */
internal class PlayerBackendExtensions(
    private val engine: VideoEngine,
) {
    val supportsSecondarySubtitleTrack: Boolean
        get() = engine.supportsSecondarySubtitleTrack

    val supportsAudioDelay: Boolean
        get() = engine.supportsAudioDelay

    val supportsSubtitleOffset: Boolean
        get() = engine.supportsSubtitleOffset

    val supportsSubtitleScale: Boolean
        get() = engine.supportsSubtitleScale

    val supportsSubtitleBrightness: Boolean
        get() = engine.supportsSubtitleBrightness

    val supportsSubtitlePosition: Boolean
        get() = engine.supportsSubtitlePosition

    fun setAudioDelayMs(delayMs: Long): Boolean = engine.setAudioDelayMs(delayMs)

    fun selectSecondarySubtitleTrack(id: String): Boolean = engine.selectSecondarySubtitleTrack(id)

    fun setSubtitleOffsetMs(offsetMs: Long): Boolean = engine.setSubtitleOffsetMs(offsetMs)

    fun setSubtitleScale(scale: Float): Boolean = engine.setSubtitleScale(scale)

    fun setSubtitleBrightness(brightness: Float): Boolean = engine.setSubtitleBrightness(brightness)

    fun setSubtitlePosition(position: Float): Boolean = engine.setSubtitlePosition(position)

    fun setPauseAtEndOfCurrentItem(enabled: Boolean) {
        engine.setPauseAtEndOfCurrentItem(enabled)
    }

    fun prepareForHandover() = engine.prepareForHandover()

    fun switchToTranscode(reason: String? = null): Boolean = engine.switchToTranscode(reason)

    fun appendItems(items: List<PlayerMediaItem>): Boolean = engine.appendItems(items)

    fun setVideoScaleMode(mode: VideoScaleMode): Boolean =
        when (engine) {
            is MpvVideoEngine -> {
                engine.setScaleMode(mode)
                true
            }

            is MdkVideoEngine -> {
                engine.setFill(mode != VideoScaleMode.Fit)
                true
            }

            else -> mode == VideoScaleMode.Fit
        }

    fun selectDiscTitle(index: Int): Boolean = ActiveDiscNavigation.selectTitle(index) || engine.selectDiscTitle(index)

    fun selectDiscChapter(index: Int): Boolean =
        ActiveDiscNavigation.selectChapter(index) || engine.selectDiscChapter(index)

    fun showDiscMenu(): Boolean =
        ActiveDiscNavigation.sendMenuCommand(PlaybackDiscMenuCommand.ShowMenu) ||
            engine.sendDiscMenuCommand(PlaybackDiscMenuCommand.ShowMenu)
}

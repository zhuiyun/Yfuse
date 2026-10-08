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

    val supportsSecondarySubtitleOffset: Boolean get() = engine.supportsSecondarySubtitleOffset

    fun setSecondarySubtitleOffsetMs(offsetMs: Long): Boolean = engine.setSecondarySubtitleOffsetMs(offsetMs)

    val supportsAudioDelay: Boolean
        get() = engine.supportsAudioDelay

    val supportsAudioEnhancement: Boolean
        get() = engine.supportsAudioEnhancement

    val supportsSubtitleOffset: Boolean
        get() = engine.supportsSubtitleOffset

    val supportsSubtitleScale: Boolean
        get() = engine.supportsSubtitleScale

    val supportsSubtitleBrightness: Boolean
        get() = engine.supportsSubtitleBrightness

    val supportsSubtitlePosition: Boolean
        get() = engine.supportsSubtitlePosition

    val supportsSubtitleAppearance: Boolean
        get() = engine.supportsSubtitleAppearance

    fun setAudioDelayMs(delayMs: Long): Boolean = engine.setAudioDelayMs(delayMs)

    fun setAudioEnhancement(mode: AudioEnhancementMode): Boolean = engine.setAudioEnhancement(mode)

    fun selectSecondarySubtitleTrack(id: String): Boolean = engine.selectSecondarySubtitleTrack(id)

    fun setSubtitleOffsetMs(offsetMs: Long): Boolean = engine.setSubtitleOffsetMs(offsetMs)

    fun setSubtitleScale(scale: Float): Boolean = engine.setSubtitleScale(scale)

    fun setSubtitleBrightness(brightness: Float): Boolean = engine.setSubtitleBrightness(brightness)

    fun setSubtitlePosition(position: Float): Boolean = engine.setSubtitlePosition(position)

    fun setSubtitleAppearance(appearance: SubtitleAppearance): Boolean = engine.setSubtitleAppearance(appearance)

    fun setPauseAtEndOfCurrentItem(enabled: Boolean) {
        engine.setPauseAtEndOfCurrentItem(enabled)
    }

    fun prepareForHandover() = engine.prepareForHandover()

    /**
     * Set by the player screen: restarts the session at the same position with the current entry
     * transcoded, for an engine that cannot switch an open source in place.
     */
    var transcodeRebuild: ((reason: String?, viewerRequested: Boolean) -> Boolean)? = null

    fun switchToTranscode(
        reason: String? = null,
        viewerRequested: Boolean = false,
    ): Boolean =
        engine.switchToTranscode(reason, viewerRequested) ||
            transcodeRebuild?.invoke(reason, viewerRequested) == true

    fun appendItems(items: List<PlayerMediaItem>): Boolean = engine.appendItems(items)

    fun updateQueue(
        items: List<PlayerMediaItem>,
        currentIndex: Int,
    ): Boolean = engine.updateQueue(items, currentIndex)

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

    /** Off the main thread: both the disc runtime and the engine's native menu can block on reads. */
    fun showDiscMenu(): Boolean {
        val engineMenu = { engine.sendDiscMenuCommand(PlaybackDiscMenuCommand.ShowMenu) }
        if (ActiveDiscNavigation.sendMenuCommand(PlaybackDiscMenuCommand.ShowMenu, fallback = engineMenu)) return true
        // Work handed to the menu worker cannot report back, so an engine without a disc menu says so here.
        return engine.state.value.discNavigation.menuSupported && ActiveDiscNavigation.dispatchMenuWork(engineMenu)
    }
}

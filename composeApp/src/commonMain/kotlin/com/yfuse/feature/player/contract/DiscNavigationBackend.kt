package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState

/**
 * Optical-disc navigation isolated from video decode/rendering.
 *
 * Main-feature playback must keep working even when an optional HDMV/BD-J backend is absent or
 * fails to initialize. This contract is therefore intentionally smaller than [VideoEngine]: a
 * navigation provider exposes metadata and commands, while YCore remains free to keep the current
 * decoder/render path unchanged.
 */
interface DiscNavigationBackend {
    val navigation: PlaybackDiscNavigationState

    fun selectTitle(index: Int): Boolean

    fun selectChapter(index: Int): Boolean

    fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean = false
}

/** Bridges today's engine navigation into the isolated contract without changing engine lifetimes. */
internal class VideoEngineDiscNavigationBackend(
    private val engine: VideoEngine,
) : DiscNavigationBackend {
    override val navigation: PlaybackDiscNavigationState
        get() = engine.state.value.discNavigation

    override fun selectTitle(index: Int): Boolean = engine.selectDiscTitle(index)

    override fun selectChapter(index: Int): Boolean = engine.selectDiscChapter(index)

    override fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean =
        engine.sendDiscMenuCommand(command)
}

package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState

/** Interactive menu implementation owned by an optical-disc navigation backend. */
enum class DiscMenuRuntime {
    /** Title/chapter navigation only; no interactive menu VM is active. */
    None,

    /** HDMV movie-object menu navigation. */
    Hdmv,

    /** BD-J Java menu/application navigation. */
    BdJ,
}

enum class DiscNavigationBackendLifecycle {
    /** No compatible provider was discovered. Main-feature playback must remain unaffected. */
    Unavailable,

    /** Provider exists and can answer navigation commands. */
    Ready,

    /** Provider existed but failed. The video engine must continue without it. */
    Failed,
}

data class DiscNavigationBackendStatus(
    val lifecycle: DiscNavigationBackendLifecycle = DiscNavigationBackendLifecycle.Unavailable,
    val menuRuntime: DiscMenuRuntime = DiscMenuRuntime.None,
    /** Human-readable diagnostic only; playback policy must not branch on this string. */
    val detail: String? = null,
) {
    val interactiveMenuReady: Boolean
        get() = lifecycle == DiscNavigationBackendLifecycle.Ready && menuRuntime != DiscMenuRuntime.None
}

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

    val status: DiscNavigationBackendStatus
        get() =
            DiscNavigationBackendStatus(
                lifecycle = DiscNavigationBackendLifecycle.Ready,
                menuRuntime = DiscMenuRuntime.None,
                detail = "标题/章节导航可用，交互菜单运行时未接入",
            )

    fun selectTitle(index: Int): Boolean

    fun selectChapter(index: Int): Boolean

    fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean = false

    /** Optional provider lifecycle. Engine-backed adapters do not own the engine and therefore no-op. */
    fun close() = Unit
}

/** Bridges today's engine navigation into the isolated contract without changing engine lifetimes. */
internal class VideoEngineDiscNavigationBackend(
    private val engine: VideoEngine,
) : DiscNavigationBackend {
    override val navigation: PlaybackDiscNavigationState
        get() = engine.state.value.discNavigation

    override val status: DiscNavigationBackendStatus
        get() =
            DiscNavigationBackendStatus(
                lifecycle =
                    if (navigation.available) {
                        DiscNavigationBackendLifecycle.Ready
                    } else {
                        DiscNavigationBackendLifecycle.Unavailable
                    },
                menuRuntime = DiscMenuRuntime.None,
                detail =
                    if (navigation.available) {
                        "当前播放内核提供标题/章节导航，不提供 HDMV/BD-J 菜单运行时"
                    } else {
                        "当前媒体未暴露光盘导航"
                    },
            )

    override fun selectTitle(index: Int): Boolean = engine.selectDiscTitle(index)

    override fun selectChapter(index: Int): Boolean = engine.selectDiscChapter(index)

    override fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean =
        engine.sendDiscMenuCommand(command)
}

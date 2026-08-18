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

    /** Optional multi-angle selection. Index is zero based. */
    fun selectAngle(index: Int): Boolean = false

    fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean = false

    /** Coordinates are in the menu overlay's authored pixel plane. */
    fun selectMenuPoint(
        x: Int,
        y: Int,
        activate: Boolean,
    ): Boolean = false

    /**
     * Push signal for asynchronous native menu/title changes.
     *
     * Engine-backed adapters can no-op because the engine PlaybackState already drives Compose.
     * A real libbluray/BD-J provider invokes this whenever navigation or status changes, avoiding
     * native polling from the common UI.
     */
    fun setChangeListener(listener: (() -> Unit)?) = Unit

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

    // Current engine contract has no stable runtime angle setter. Custom libbluray sessions own
    // authored Blu-ray angles directly; ordinary mpv `--bluray-angle` remains only an open-time knob.
    override fun selectAngle(index: Int): Boolean = false

    override fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean =
        engine.sendDiscMenuCommand(command)
}

/**
 * Keeps title/chapter control on the video engine while an optional native runtime owns menus and
 * native-only optical state such as authored camera angles.
 *
 * This avoids opening a second libbluray title path merely to obtain HDMV input. The menu provider can
 * fail or disappear independently and the engine adapter remains able to select titles/chapters.
 */
internal class CompositeDiscNavigationBackend(
    private val engineBackend: DiscNavigationBackend,
    private val menuBackend: DiscNavigationBackend,
) : DiscNavigationBackend {
    private var listener: (() -> Unit)? = null

    override val navigation: PlaybackDiscNavigationState
        get() {
            val engine = engineBackend.navigation
            val menu = menuBackend.navigation
            return engine.copy(
                angleCount = menu.angleCount.takeIf { it > 0 } ?: engine.angleCount,
                selectedAngleIndex =
                    menu.selectedAngleIndex.takeIf { menu.angleCount > 0 }
                        ?: engine.selectedAngleIndex,
                menuSupported = menu.menuSupported,
                menuActive = menu.menuActive,
            )
        }

    override val status: DiscNavigationBackendStatus
        get() = menuBackend.status

    override fun selectTitle(index: Int): Boolean = engineBackend.selectTitle(index)

    override fun selectChapter(index: Int): Boolean = engineBackend.selectChapter(index)

    override fun selectAngle(index: Int): Boolean =
        if (menuBackend.navigation.effectiveAngleCount > 0) {
            menuBackend.selectAngle(index)
        } else {
            engineBackend.selectAngle(index)
        }

    override fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean =
        menuBackend.sendMenuCommand(command)

    override fun selectMenuPoint(
        x: Int,
        y: Int,
        activate: Boolean,
    ): Boolean = menuBackend.selectMenuPoint(x, y, activate)

    override fun setChangeListener(listener: (() -> Unit)?) {
        this.listener = listener
        menuBackend.setChangeListener(listener)
    }

    override fun close() {
        menuBackend.setChangeListener(null)
        menuBackend.close()
        listener = null
    }
}

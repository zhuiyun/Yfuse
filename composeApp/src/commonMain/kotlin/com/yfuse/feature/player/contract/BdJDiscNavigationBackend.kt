package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState

/**
 * Backend-neutral surface a future Android BD-J runtime must implement.
 *
 * The contract intentionally mirrors only user-visible navigation. It does not expose a JVM, Xlet,
 * AWT or network object to the player. That keeps an eventual Java runtime replaceable and prevents a
 * BD-J failure from becoming part of the video decoder lifecycle.
 */
interface BdJDiscSession {
    fun navigation(): PlaybackDiscNavigationState

    fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean

    fun selectMenuPoint(
        x: Int,
        y: Int,
        activate: Boolean,
    ): Boolean = false

    /** Pushes Xlet/menu lifecycle changes without forcing Compose to poll a runtime. */
    fun setNavigationChangedListener(listener: (() -> Unit)?) = Unit

    fun close()
}

/**
 * Failure boundary for a separately supplied BD-J runtime.
 *
 * A provider is considered ready only while it both survives calls and reports authored menu
 * support. Any exception permanently disables this optional provider for the session and clears menu
 * interception. The main-feature engine remains untouched.
 */
class BdJDiscNavigationBackend(
    private val session: BdJDiscSession,
) : DiscNavigationBackend {
    private var failure: Throwable? = null
    private var closed = false
    private var lastNavigation = PlaybackDiscNavigationState()
    private var changeListener: (() -> Unit)? = null

    init {
        runCatching { session.setNavigationChangedListener(::notifyChanged) }
            .onFailure(::markFailed)
    }

    override val navigation: PlaybackDiscNavigationState
        get() {
            if (failure != null || closed) return lastNavigation.copy(menuSupported = false, menuActive = false)
            return runSafely(lastNavigation.copy(menuSupported = false, menuActive = false)) {
                session.navigation().also { lastNavigation = it }
            }
        }

    override val status: DiscNavigationBackendStatus
        get() =
            when {
                failure != null ->
                    DiscNavigationBackendStatus(
                        lifecycle = DiscNavigationBackendLifecycle.Failed,
                        menuRuntime = DiscMenuRuntime.None,
                        detail = failure?.message?.takeIf(String::isNotBlank) ?: "BD-J 运行时失败",
                    )
                closed ->
                    DiscNavigationBackendStatus(
                        lifecycle = DiscNavigationBackendLifecycle.Unavailable,
                        menuRuntime = DiscMenuRuntime.None,
                        detail = "BD-J 运行时已关闭",
                    )
                !navigation.menuSupported ->
                    DiscNavigationBackendStatus(
                        lifecycle = DiscNavigationBackendLifecycle.Unavailable,
                        menuRuntime = DiscMenuRuntime.None,
                        detail = "当前光盘没有可用的 BD-J 菜单",
                    )
                else ->
                    DiscNavigationBackendStatus(
                        lifecycle = DiscNavigationBackendLifecycle.Ready,
                        menuRuntime = DiscMenuRuntime.BdJ,
                        detail = "BD-J 菜单运行时已就绪",
                    )
            }

    // Title/chapter ownership remains with the engine/libbluray backend. BD-J owns only the authored
    // interactive application surface and therefore never silently replaces main-feature navigation.
    override fun selectTitle(index: Int): Boolean = false

    override fun selectChapter(index: Int): Boolean = false

    override fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean =
        runSafely(false) {
            session.sendMenuCommand(command).also { handled -> if (handled) notifyChanged() }
        }

    override fun selectMenuPoint(
        x: Int,
        y: Int,
        activate: Boolean,
    ): Boolean =
        runSafely(false) {
            session.selectMenuPoint(x, y, activate).also { handled -> if (handled) notifyChanged() }
        }

    override fun setChangeListener(listener: (() -> Unit)?) {
        changeListener = listener
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { session.setNavigationChangedListener(null) }
            .onFailure(::markFailed)
        runCatching { session.close() }
            .onFailure(::markFailed)
        notifyChanged()
    }

    private inline fun <T> runSafely(
        fallback: T,
        block: () -> T,
    ): T {
        if (failure != null || closed) return fallback
        return runCatching(block)
            .getOrElse { error ->
                markFailed(error)
                fallback
            }
    }

    private fun markFailed(error: Throwable) {
        if (failure == null) failure = error
        lastNavigation = lastNavigation.copy(menuSupported = false, menuActive = false)
        notifyChanged()
    }

    private fun notifyChanged() {
        changeListener?.invoke()
    }
}

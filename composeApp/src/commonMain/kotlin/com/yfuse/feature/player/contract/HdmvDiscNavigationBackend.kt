package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState

/**
 * Small native-session surface required from the libbluray JNI bridge.
 *
 * The JNI layer owns libbluray handles/overlay callbacks. This Kotlin side owns lifecycle and failure
 * isolation so a menu crash can never take the video decoder or main-feature playback down with it.
 */
interface HdmvDiscSession {
    fun navigation(): PlaybackDiscNavigationState

    fun selectTitle(index: Int): Boolean

    fun selectChapter(index: Int): Boolean

    fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean

    /** Authored overlay coordinates; [activate] maps a tap to selection + Enter. */
    fun selectMenuPoint(
        x: Int,
        y: Int,
        activate: Boolean,
    ): Boolean = false

    /** Native overlay/navigation callbacks use this instead of forcing the UI to poll JNI. */
    fun setNavigationChangedListener(listener: (() -> Unit)?) = Unit

    fun close()
}

/**
 * HDMV provider with a hard failure boundary around the native menu session.
 *
 * Any exception permanently marks only this optional backend failed. Callers then receive false and
 * can continue normal title playback; no exception is allowed to escape into the player engine.
 */
class HdmvDiscNavigationBackend(
    private val session: HdmvDiscSession,
) : DiscNavigationBackend {
    private var failure: Throwable? = null
    private var closed = false
    private var lastNavigation = PlaybackDiscNavigationState()
    private var changeListener: (() -> Unit)? = null

    init {
        runCatching {
            session.setNavigationChangedListener(::notifyChanged)
        }.onFailure(::markFailed)
    }

    override val navigation: PlaybackDiscNavigationState
        get() {
            if (failure != null || closed) return lastNavigation.copy(menuActive = false)
            return runSafely(lastNavigation.copy(menuActive = false)) {
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
                        detail = failure?.message?.takeIf(String::isNotBlank) ?: "HDMV 导航运行时失败",
                    )
                closed ->
                    DiscNavigationBackendStatus(
                        lifecycle = DiscNavigationBackendLifecycle.Unavailable,
                        menuRuntime = DiscMenuRuntime.None,
                        detail = "HDMV 导航会话已关闭",
                    )
                !navigation.menuSupported ->
                    DiscNavigationBackendStatus(
                        lifecycle = DiscNavigationBackendLifecycle.Unavailable,
                        menuRuntime = DiscMenuRuntime.None,
                        detail = "当前 Blu-ray 没有可用的 HDMV 菜单",
                    )
                else ->
                    DiscNavigationBackendStatus(
                        lifecycle = DiscNavigationBackendLifecycle.Ready,
                        menuRuntime = DiscMenuRuntime.Hdmv,
                        detail = "HDMV 导航运行时已就绪",
                    )
            }

    override fun selectTitle(index: Int): Boolean =
        runSafely(false) {
            session.selectTitle(index).also { selected -> if (selected) notifyChanged() }
        }

    override fun selectChapter(index: Int): Boolean =
        runSafely(false) {
            session.selectChapter(index).also { selected -> if (selected) notifyChanged() }
        }

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
        lastNavigation = lastNavigation.copy(menuActive = false, menuSupported = false)
        notifyChanged()
    }

    private fun notifyChanged() {
        changeListener?.invoke()
    }
}

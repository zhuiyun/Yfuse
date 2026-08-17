package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState

/**
 * Small native-session surface required from a future libbluray JNI bridge.
 *
 * The JNI layer owns libbluray handles/overlay callbacks. This Kotlin side owns lifecycle and failure
 * isolation so a menu crash can never take the video decoder or main-feature playback down with it.
 */
interface HdmvDiscSession {
    fun navigation(): PlaybackDiscNavigationState

    fun selectTitle(index: Int): Boolean

    fun selectChapter(index: Int): Boolean

    fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean

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
                else ->
                    DiscNavigationBackendStatus(
                        lifecycle = DiscNavigationBackendLifecycle.Ready,
                        menuRuntime = DiscMenuRuntime.Hdmv,
                        detail = "HDMV 导航运行时已就绪",
                    )
            }

    override fun selectTitle(index: Int): Boolean =
        runSafely(false) { session.selectTitle(index) }

    override fun selectChapter(index: Int): Boolean =
        runSafely(false) { session.selectChapter(index) }

    override fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean =
        runSafely(false) { session.sendMenuCommand(command) }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { session.close() }
            .onFailure(::markFailed)
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
    }
}

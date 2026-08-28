package com.yfuse.core2.android

import android.content.Context
import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.render.YFrameRateSwitchMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Optional executor for direct optical-disc items prepared by the platform source bridge. */
internal fun interface AndroidCore2DiscRouteFactory {
    fun create(
        item: YMediaItem,
        request: YPlayerOpenRequest,
        startSpeed: Float,
        forceSoftwareDecode: Boolean,
    ): YPlayer?
}

/**
 * Pure YCore Blu-ray route: libbluray supplies the selected title byte stream, FFmpeg demuxes M2TS,
 * and the ordinary enhanced graph keeps ownership of decode, Surface rendering, audio and subtitles.
 */
internal class AndroidYCoreDiscRouteFactory(
    context: Context,
    private val allowAudioPassthrough: Boolean,
    private val frameRateSwitchMode: YFrameRateSwitchMode,
    private val fallback: AndroidCore2DiscRouteFactory? = null,
) : AndroidCore2DiscRouteFactory {
    private val appContext = context.applicationContext

    override fun create(
        item: YMediaItem,
        request: YPlayerOpenRequest,
        startSpeed: Float,
        forceSoftwareDecode: Boolean,
    ): YPlayer? {
        val yCore =
            if (FfmpegNativeBridge.discNavigationAvailable) {
                createYCore(item, request, startSpeed)
            } else {
                null
            }
        return yCore ?: fallback?.create(item, request, startSpeed, forceSoftwareDecode)
    }

    private fun createYCore(
        item: YMediaItem,
        request: YPlayerOpenRequest,
        startSpeed: Float,
    ): YPlayer? {
        val source = AndroidYCoreBluRaySource.create(appContext, item) ?: return null
        val nativeId =
            runCatching { FfmpegNativeBridge.registerBluRaySource(source) }
                .getOrElse {
                    source.closeNativeSource()
                    return null
                }
        source.bindNativeId(nativeId)
        val nativeItem =
            item.copy(
                uri = "$YCORE_BLURAY_SCHEME://$nativeId",
                // The opaque process-local URI contains no credentials and never needs HTTP headers.
                headers = emptyMap(),
            )
        val nativeRequest = request.copy(items = listOf(nativeItem), startIndex = 0, autoNext = false)
        val delegate =
            runCatching {
                AndroidNativeEnhancedYPlayer(
                    context = appContext,
                    request = nativeRequest,
                    allowAudioPassthrough = allowAudioPassthrough,
                    frameRateSwitchMode = frameRateSwitchMode,
                )
            }.getOrElse {
                FfmpegNativeBridge.unregisterBluRaySource(nativeId)
                return null
            }
        delegate.setSpeed(startSpeed)
        return AndroidYCoreBluRayPlayer(
            delegate = delegate,
            source = source,
            nativeId = nativeId,
        )
    }
}

private class AndroidYCoreBluRayPlayer(
    private val delegate: YPlayer,
    private val source: AndroidYCoreBluRaySource,
    private val nativeId: Long,
) : YPlayer by delegate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val state: StateFlow<YPlayerState> =
        combine(delegate.state, source.navigation) { player, navigation ->
            player.copy(
                discNavigation = navigation,
                diagnostics =
                    player.diagnostics.copy(
                        demuxer = "libbluray 1.4.1 + FFmpeg 8.1",
                    ),
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = delegate.state.value.copy(discNavigation = source.navigation.value),
        )

    @Volatile
    private var released = false

    override fun selectDiscTitle(index: Int): Boolean {
        if (released || index !in 0 until state.value.discNavigation.effectiveTitleCount) return false
        val selected = FfmpegNativeBridge.selectDiscTitle(nativeId, index)
        if (selected) delegate.retry()
        return selected
    }

    override fun selectDiscChapter(index: Int): Boolean {
        if (released || index !in 0 until state.value.discNavigation.effectiveChapterCount) return false
        val startMs = FfmpegNativeBridge.discChapterStartMs(nativeId, index) ?: return false
        delegate.seekTo(startMs)
        return true
    }

    override fun selectDiscAngle(index: Int): Boolean {
        if (released || index !in 0 until state.value.discNavigation.effectiveAngleCount) return false
        return FfmpegNativeBridge.selectDiscAngle(nativeId, index)
    }

    override fun sendDiscMenuCommand(command: PlaybackDiscMenuCommand): Boolean =
        !released &&
            state.value.discNavigation.menuSupported &&
            FfmpegNativeBridge.sendDiscMenuCommand(nativeId, command.nativeMenuCode())

    override fun release() {
        if (released) return
        released = true
        delegate.release()
        FfmpegNativeBridge.unregisterBluRaySource(nativeId)
        scope.cancel()
    }
}

private const val YCORE_BLURAY_SCHEME = "ycorebd"

private fun PlaybackDiscMenuCommand.nativeMenuCode(): Int =
    when (this) {
        PlaybackDiscMenuCommand.ShowMenu -> 0
        PlaybackDiscMenuCommand.Back -> 1
        PlaybackDiscMenuCommand.Up -> 2
        PlaybackDiscMenuCommand.Down -> 3
        PlaybackDiscMenuCommand.Left -> 4
        PlaybackDiscMenuCommand.Right -> 5
        PlaybackDiscMenuCommand.Select -> 6
    }

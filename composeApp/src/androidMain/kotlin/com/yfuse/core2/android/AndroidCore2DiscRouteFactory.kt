package com.yfuse.core2.android

import android.content.Context
import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
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
        if (forceSoftwareDecode && item.drmConfiguration != null) return null
        val yCore =
            if (FfmpegNativeBridge.discNavigationAvailable) {
                createYCore(item, request, startSpeed, forceSoftwareDecode)
            } else {
                null
            }
        return yCore ?: fallback?.create(item, request, startSpeed, forceSoftwareDecode)
    }

    private fun createYCore(
        item: YMediaItem,
        request: YPlayerOpenRequest,
        startSpeed: Float,
        forceSoftwareDecode: Boolean,
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
                    forceSoftwareDecode = forceSoftwareDecode,
                )
            }.getOrElse {
                FfmpegNativeBridge.unregisterBluRaySource(nativeId)
                return null
            }
        delegate.setSpeed(startSpeed)
        return AndroidYCoreBluRayPlayer(
            delegate = delegate,
            navigation = source.navigation,
            nativeId = nativeId,
        )
    }
}

internal class AndroidYCoreBluRayPlayer(
    private val delegate: YPlayer,
    navigation: StateFlow<PlaybackDiscNavigationState>,
    private val nativeId: Long,
    private val unregisterSource: (Long) -> Unit = FfmpegNativeBridge::unregisterBluRaySource,
) : YPlayer by delegate,
    AndroidSerializedPlayerRelease {
    private val serializedDelegate = checkNotNull(delegate as? AndroidSerializedPlayerRelease)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val state: StateFlow<YPlayerState> =
        combine(delegate.state, navigation) { player, navigation ->
            player.copy(
                discNavigation = navigation,
                diagnostics =
                    player.diagnostics.copy(
                        demuxer = "libbluray 1.4.1 + FFmpeg",
                    ),
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = delegate.state.value.copy(discNavigation = navigation.value),
        )

    @Volatile
    private var released = false

    @Volatile
    private var delegateReleaseRequested = false

    @Volatile
    private var sourceUnregistered = false

    override val releaseCompleted: Boolean
        get() = delegateReleaseRequested && sourceUnregistered && serializedDelegate.releaseCompleted

    override suspend fun releaseAndJoin() {
        release()
        serializedDelegate.releaseAndJoin()
        check(releaseCompleted) { "Blu-ray decoder cleanup is not complete" }
    }

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

    @Synchronized
    override fun release() {
        released = true
        scope.cancel()
        if (!delegateReleaseRequested) {
            delegate.release()
            delegateReleaseRequested = true
        }
        if (!sourceUnregistered) {
            // Demux retains a native shared_ptr; removing the registry entry does not free an
            // active reader. Decoder completion remains the delegate's serialized barrier.
            unregisterSource(nativeId)
            sourceUnregistered = true
        }
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

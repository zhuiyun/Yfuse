package com.yfuse.core2.legacy

import android.content.Context
import com.yfuse.core.model.DecoderMode
import com.yfuse.core2.android.AndroidCore2DiscRouteFactory
import com.yfuse.core2.android.AndroidCore2FallbackRouteFactory
import com.yfuse.core2.android.AndroidExternalSubtitleLoader
import com.yfuse.core2.android.AndroidSurfaceVideoOutput
import com.yfuse.core2.android.EXTERNAL_SUBTITLE_TRACK_ID
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.api.YVideoOutput
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YRenderPath
import com.yfuse.feature.player.EngineTrack
import com.yfuse.feature.player.MpvVideoEngine
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.PlayerMediaVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * Executes Core2's production compatibility tiers through the bundled, verified libmpv runtime.
 * This is deliberately in `core2.legacy`: strategy and graph packages remain backend-independent.
 * The renderer is libplacebo GPU with explicit tone mapping/scaling/deband/dither policy; it is
 * reported as mpv GPU and is never mislabeled as the optional native Vulkan/AHardwareBuffer path.
 */
internal class AndroidMpvCore2FallbackFactory(
    context: Context,
    private val sourceItems: Map<String, PlayerMediaItem> = emptyMap(),
) : AndroidCore2FallbackRouteFactory,
    AndroidCore2DiscRouteFactory {
    private val appContext = context.applicationContext

    override fun create(
        item: YMediaItem,
        request: YPlayerOpenRequest,
        plan: YPlaybackPlan,
        startSpeed: Float,
    ): YPlayer? {
        if (
            plan.route != YPlaybackRoute.GpuEnhanced &&
            plan.route != YPlaybackRoute.SoftwareFallback
        ) {
            return null
        }
        return AndroidMpvCore2FallbackPlayer(
            context = appContext,
            item = item,
            sourceItem = null,
            request = request,
            plan = plan,
            startSpeed = startSpeed,
        )
    }

    override fun create(
        item: YMediaItem,
        request: YPlayerOpenRequest,
        startSpeed: Float,
        forceSoftwareDecode: Boolean,
    ): YPlayer? {
        val disc = item.disc ?: return null
        val plan = core2DiscCompatibilityPlan(disc, forceSoftwareDecode)
        return AndroidMpvCore2FallbackPlayer(
            context = appContext,
            item = item,
            sourceItem =
                sourceItems[item.id]?.forCore2DiscUri(item.uri)
                    ?: item.toDiscPlayerMediaItem(),
            request = request,
            plan = plan,
            startSpeed = startSpeed,
        )
    }
}

private class AndroidMpvCore2FallbackPlayer(
    context: Context,
    item: YMediaItem,
    sourceItem: PlayerMediaItem?,
    request: YPlayerOpenRequest,
    private val plan: YPlaybackPlan,
    startSpeed: Float,
) : YPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val externalSubtitle =
        item.externalSubtitle?.let { source ->
            runCatching { AndroidExternalSubtitleLoader(context).load(source, item.headers) }.getOrNull()
        }

    @Volatile
    private var externalSubtitleSelected = externalSubtitle != null

    private val engine =
        MpvVideoEngine(
            context = context,
            items =
                listOf(
                    sourceItem
                        ?: PlayerMediaItem(
                            id = item.id,
                            url = item.uri,
                            transcodeUrl = "",
                            fallbackTranscodeUrl = "",
                            title = item.title ?: item.id,
                            serverId = item.providerKey,
                        ),
                ),
            startIndex = 0,
            startPositionMs = request.startPositionMs,
            startPlaybackRequested = request.autoPlay,
            startSpeed = startSpeed,
            decoderMode =
                if (plan.route == YPlaybackRoute.SoftwareFallback) {
                    DecoderMode.Software
                } else {
                    DecoderMode.Hardware
                },
            autoNext = false,
            customUserAgent = item.headers[USER_AGENT_HEADER].orEmpty(),
            scope = scope,
        )
    private val delegate = LegacyYPlayerAdapter(engine)

    override val state: StateFlow<YPlayerState> =
        MappedFallbackStateFlow(delegate.state) { state ->
            state.copy(
                subtitleTracks =
                    state.subtitleTracks.map { track ->
                        track.copy(selected = !externalSubtitleSelected && track.selected)
                    } + listOfNotNull(externalSubtitle?.track?.copy(selected = externalSubtitleSelected)),
                subtitleCues =
                    if (externalSubtitleSelected) {
                        externalSubtitle?.cues.orEmpty()
                    } else {
                        state.subtitleCues
                    },
                diagnostics =
                    state.diagnostics.copy(
                        route = resolvedMpvFallbackRoute(plan.route, state.diagnostics.decoder),
                        demuxer = "libavformat compatibility executor",
                        renderer = "libmpv/libplacebo GPU",
                        reason = "${plan.reason}; compatibility executor active",
                    ),
            )
        }

    override val playbackRequested: Boolean get() = delegate.playbackRequested

    override fun prepare() = Unit

    override fun setVideoOutput(output: YVideoOutput?): Boolean =
        when (output) {
            null -> {
                engine.detach()
                true
            }

            is AndroidSurfaceVideoOutput -> {
                engine.attach(output.surface)
                true
            }

            else -> false
        }

    override fun play() = delegate.play()

    override fun pause() = delegate.pause()

    override fun seekTo(positionMs: Long) = delegate.seekTo(positionMs)

    override fun setSpeed(speed: Float) = delegate.setSpeed(speed)

    override fun selectTrack(
        type: YTrackType,
        id: String,
    ) {
        if (type != YTrackType.Subtitle || externalSubtitle == null) {
            delegate.selectTrack(type, id)
            return
        }
        when (id) {
            EXTERNAL_SUBTITLE_TRACK_ID -> {
                externalSubtitleSelected = true
                delegate.selectTrack(type, EngineTrack.OFF)
            }
            else -> {
                externalSubtitleSelected = false
                delegate.selectTrack(type, id)
            }
        }
    }

    override fun selectItem(index: Int) = delegate.selectItem(index)

    override fun selectDiscTitle(index: Int): Boolean = delegate.selectDiscTitle(index)

    override fun selectDiscChapter(index: Int): Boolean = delegate.selectDiscChapter(index)

    override fun sendDiscMenuCommand(command: com.yfuse.core.playback.PlaybackDiscMenuCommand): Boolean =
        delegate.sendDiscMenuCommand(command)

    override fun currentPositionMs(): Long = delegate.currentPositionMs()

    override fun retry() = delegate.retry()

    override fun release() {
        engine.detach()
        delegate.release()
        scope.cancel()
    }
}

internal fun resolvedMpvFallbackRoute(
    plannedRoute: YPlaybackRoute,
    decoderLabel: String,
): YPlaybackRoute {
    if (plannedRoute == YPlaybackRoute.SoftwareFallback) return plannedRoute
    val normalized = decoderLabel.lowercase()
    return if (
        "software" in normalized ||
        "ffmpeg" in normalized ||
        "软件" in normalized
    ) {
        YPlaybackRoute.SoftwareFallback
    } else {
        plannedRoute
    }
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class MappedFallbackStateFlow(
    private val source: StateFlow<YPlayerState>,
    private val transform: (YPlayerState) -> YPlayerState,
) : StateFlow<YPlayerState> {
    override val value: YPlayerState get() = transform(source.value)

    override val replayCache: List<YPlayerState> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<YPlayerState>): Nothing =
        source.collect { value -> collector.emit(transform(value)) }
}

private const val USER_AGENT_HEADER = "User-Agent"

private fun PlayerMediaItem.forCore2DiscUri(uri: String): PlayerMediaItem {
    val activeId = versionId ?: activeVersion?.id
    return copy(
        url = uri,
        versions =
            versions.map { version ->
                if (version.id == activeId) version.copy(url = uri) else version
            },
    )
}

internal fun core2DiscCompatibilityPlan(
    disc: com.yfuse.core2.api.YDiscMedia,
    forceSoftwareDecode: Boolean,
): YPlaybackPlan =
    YPlaybackPlan(
        route =
            if (forceSoftwareDecode) {
                YPlaybackRoute.SoftwareFallback
            } else {
                YPlaybackRoute.GpuEnhanced
            },
        demuxPath =
            if (forceSoftwareDecode) YDemuxPath.Software else YDemuxPath.Enhanced,
        decodePath =
            if (forceSoftwareDecode) YDecodePath.Software else YDecodePath.Hardware,
        renderPath = YRenderPath.Gpu,
        outputHdrType = YHdrType.Sdr,
        nativeAudio = !forceSoftwareDecode,
        reason = "Direct ${disc.kind} through the verified libbluray compatibility executor",
    )

internal fun YMediaItem.toDiscPlayerMediaItem(): PlayerMediaItem {
    val descriptor = requireNotNull(disc)
    val version =
        PlayerMediaVersion(
            id = id,
            label = descriptor.label ?: descriptor.kind.name,
            detail = descriptor.container ?: descriptor.kind.name,
            url = uri,
            transcodeUrl = "",
            fallbackTranscodeUrl = "",
            container = descriptor.container,
            discSource = true,
        )
    return PlayerMediaItem(
        id = id,
        url = uri,
        transcodeUrl = "",
        fallbackTranscodeUrl = "",
        title = title ?: id,
        serverId = providerKey,
        versions = listOf(version),
        versionId = version.id,
    )
}

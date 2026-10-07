package com.yfuse.core2.legacy

import android.content.Context
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core2.android.AndroidCore2DiscRouteFactory
import com.yfuse.core2.android.AndroidCore2FallbackRouteFactory
import com.yfuse.core2.android.AndroidExternalSubtitleLoader
import com.yfuse.core2.android.AndroidLoadedExternalSubtitle
import com.yfuse.core2.android.AndroidSerializedPlayerRelease
import com.yfuse.core2.android.AndroidSurfaceVideoOutput
import com.yfuse.core2.android.EXTERNAL_SUBTITLE_TRACK_ID
import com.yfuse.core2.api.YAudioEffect
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrack
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
import com.yfuse.feature.player.SubtitleAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Executes Core2's production compatibility tiers through the bundled, verified libmpv runtime.
 * This is deliberately in `core2.legacy`: strategy and graph packages remain backend-independent.
 * The renderer is libplacebo GPU with explicit tone mapping/scaling/deband/dither policy; it is
 * reported as mpv GPU and is never mislabeled as the optional native Vulkan/AHardwareBuffer path.
 */
internal class AndroidMpvCore2FallbackFactory(
    context: Context,
    private val sourceItems: Map<String, PlayerMediaItem> = emptyMap(),
    private val optimizationMode: PlaybackOptimizationMode = PlaybackOptimizationMode.Balanced,
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
        return createMpvCore2FallbackPlayer(
            context = appContext,
            item = item,
            sourceItem = null,
            request = request,
            plan = plan,
            startSpeed = startSpeed,
            optimizationMode = optimizationMode,
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
        return createMpvCore2FallbackPlayer(
            context = appContext,
            item = item,
            sourceItem =
                sourceItems[item.id]?.forCore2DiscUri(item.uri)
                    ?: item.toDiscPlayerMediaItem(),
            request = request,
            plan = plan,
            startSpeed = startSpeed,
            optimizationMode = optimizationMode,
        )
    }
}

private fun createMpvCore2FallbackPlayer(
    context: Context,
    item: YMediaItem,
    sourceItem: PlayerMediaItem?,
    request: YPlayerOpenRequest,
    plan: YPlaybackPlan,
    startSpeed: Float,
    optimizationMode: PlaybackOptimizationMode,
): AndroidMpvCore2FallbackPlayer {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine =
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
            optimizationMode = optimizationMode,
            scope = scope,
            // Core2Surface draws cues, not mpv's sub-text; mpv keeps drawing both subtitles.
            stackTextSubtitlesInOverlay = false,
        )
    return AndroidMpvCore2FallbackPlayer(context, item, plan, scope, engine)
}

/**
 * The libmpv compatibility tier as a YCore child.
 *
 * Everything not overridden here goes straight to [LegacyYPlayerAdapter], so 音频延迟, 音频增强,
 * 副字幕, 碟片角度 and every later YPlayer control reach mpv instead of silently falling back to the
 * interface defaults. The router hands a new child its settings before its first Surface, when
 * mpv does not exist yet; [MpvVideoEngine] drops calls made then, so the latest values are kept
 * here and applied once [setVideoOutput] has created the instance.
 */
private class AndroidMpvCore2FallbackPlayer(
    context: Context,
    item: YMediaItem,
    private val plan: YPlaybackPlan,
    private val scope: CoroutineScope,
    private val engine: MpvVideoEngine,
    private val delegate: YPlayer = LegacyYPlayerAdapter(engine),
) : YPlayer by delegate,
    YNativeSubtitleStyleTarget,
    AndroidSerializedPlayerRelease {
    private val subtitleRevision = MutableStateFlow(0L)

    @Volatile
    private var externalSubtitle =
        item.externalSubtitle?.let { source ->
            AndroidLoadedExternalSubtitle(
                YTrack(
                    EXTERNAL_SUBTITLE_TRACK_ID,
                    YTrackType.Subtitle,
                    source.language ?: "External subtitle",
                    source.language,
                ),
                emptyList(),
            )
        }

    @Volatile
    private var externalSubtitleSelected = externalSubtitle != null

    @Volatile
    private var audioDelayMs = 0L

    @Volatile
    private var audioEffect = YAudioEffect.Off

    @Volatile
    private var subtitleStyle: YNativeSubtitleStyle? = null

    override val state: StateFlow<YPlayerState> =
        MappedFallbackStateFlow(delegate.state, subtitleRevision) { state ->
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

    init {
        item.externalSubtitle?.let { source ->
            scope.launch(Dispatchers.IO) {
                try {
                    val loaded = AndroidExternalSubtitleLoader(context).load(source, item.headers)
                    currentCoroutineContext().ensureActive()
                    externalSubtitle = loaded
                    subtitleRevision.update { it + 1L }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    externalSubtitle = null
                    externalSubtitleSelected = false
                    subtitleRevision.update { it + 1L }
                    AppLog.warning(
                        category = "player.core2",
                        event = "external_subtitle_load_failed",
                        message = "Compatibility subtitle failed; playback continues",
                        attributes = mapOf("exceptionType" to error.javaClass.simpleName),
                    )
                }
            }
        }
    }

    override fun prepare() = Unit

    override fun setVideoOutput(output: YVideoOutput?): Boolean =
        when (output) {
            null -> {
                engine.detach()
                true
            }

            is AndroidSurfaceVideoOutput -> {
                engine.attach(output.surface)
                applyDeferredSettings()
                true
            }

            else -> false
        }

    /** Re-sends what may have arrived before mpv existed; mpv properties are idempotent. */
    private fun applyDeferredSettings() {
        if (!engine.nativeInstanceReady) return
        if (audioDelayMs != 0L) delegate.setAudioDelayMs(audioDelayMs)
        if (audioEffect != YAudioEffect.Off) delegate.setAudioEffect(audioEffect)
        subtitleStyle?.let(::applySubtitleStyle)
    }

    override fun setAudioDelayMs(delayMs: Long): Boolean {
        audioDelayMs = delayMs
        return delegate.setAudioDelayMs(delayMs) || !engine.nativeInstanceReady
    }

    override fun setAudioEffect(effect: YAudioEffect): Boolean {
        audioEffect = effect
        return delegate.setAudioEffect(effect) || !engine.nativeInstanceReady
    }

    override fun setNativeSubtitleStyle(style: YNativeSubtitleStyle): Boolean {
        subtitleStyle = style
        return applySubtitleStyle(style) || !engine.nativeInstanceReady
    }

    @Volatile
    private var customAppearanceSent = false

    private fun applySubtitleStyle(style: YNativeSubtitleStyle): Boolean {
        // Each property is attempted even when an earlier one fails, so one rejected value does
        // not leave the rest of the style unapplied.
        val offset = engine.setSubtitleOffsetMs(style.offsetMs)
        val scale = engine.setSubtitleScale(style.scale)
        val position = engine.setSubtitlePosition(style.position)
        // Any appearance makes mpv restyle ASS subtitles, so authored styling stays untouched until
        // the viewer picks a style; after that the default is the closest restore mpv offers.
        val customAppearance = style.appearance != SubtitleAppearance()
        val appearance =
            if (!customAppearance && !customAppearanceSent) {
                true
            } else {
                engine.setSubtitleAppearance(style.appearance).also { applied ->
                    if (applied) customAppearanceSent = true
                }
            }
        return offset && scale && position && appearance
    }

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
        subtitleRevision.update { it + 1L }
    }

    @Volatile
    private var released = false

    override fun release() {
        if (released) return
        released = true
        scope.cancel()
        engine.detach()
        delegate.release()
    }

    /**
     * mpv destroys its native instance on a thread of its own after [release] returns. The router
     * waits on this before it lets the next route allocate a decoder, as it does for its own
     * children, instead of starting one beside a libmpv that still holds a hardware decoder.
     */
    override val releaseCompleted: Boolean get() = engine.releaseCompleted

    override suspend fun releaseAndJoin() {
        release()
        engine.releaseAndJoin()
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
    private val revision: StateFlow<Long>,
    private val transform: (YPlayerState) -> YPlayerState,
) : StateFlow<YPlayerState> {
    override val value: YPlayerState get() = transform(source.value)

    override val replayCache: List<YPlayerState> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<YPlayerState>): Nothing {
        combine(source, revision) { value, _ -> transform(value) }.collect { collector.emit(it) }
        error("Playback state flows must not complete")
    }
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

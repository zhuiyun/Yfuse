package com.yfuse.feature.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.safeLogcat
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core.playback.PlaybackHdrFormat
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core.playback.playbackBufferProfile
import com.yfuse.core2.android.AndroidSpatialAudioProbe
import com.yfuse.core2.android.createAndroidSpatialAudioStateMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "YfusePlayer"
private val exoRuntimeCadence =
    PlaybackRuntimeCadence(
        activeIntervalMs = PLAYBACK_PROGRESS_STEP_MS,
        idleIntervalMs = 2_000L,
    )
private const val TRANSIENT_RETRY_LIMIT = 2
private const val FAILURE_HISTORY_LIMIT = 4
private const val MPEG_TS_TIMESTAMP_SEARCH_BYTES = 5 * 1024 * 1024

/**
 * ExoPlayer behind the engine-agnostic [VideoEngine] contract.
 *
 * This class owns player lifetime, state and fallback execution. Track metadata normalization and
 * request/media-item construction live in dedicated helpers so changes in those policies do not
 * grow the engine lifecycle class.
 */
@UnstableApi
class ExoVideoEngine(
    context: Context,
    items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    startPlaybackRequested: Boolean,
    startSpeed: Float,
    private val scope: CoroutineScope,
    private val decoderMode: DecoderMode,
    optimizationMode: PlaybackOptimizationMode,
    private val autoNext: Boolean,
    customUserAgent: String,
    videoCacheBytes: Long,
    private val stopEncoding: suspend (String) -> Boolean = { true },
) : VideoEngine {
    private val items = items.toMutableList()
    private val persistentCacheUrls =
        ConcurrentHashMap.newKeySet<String>().apply {
            this@ExoVideoEngine.items.mapNotNullTo(this) { it.persistentPlaybackCacheUrl() }
        }
    private val outputPreferences = GlobalContext.get().get<PlaybackPreferences>()
    private val capabilityProvider =
        runCatching { GlobalContext.get().get<PlaybackDeviceCapabilitiesProvider>() }.getOrNull()
    internal val frameRateMatchMode = outputPreferences.frameRateMatch.value.toPlayerMode()
    private val audioPassthroughMode = outputPreferences.audioPassthrough.value.toPlayerMode()
    private val spatialAudioProbe = AndroidSpatialAudioProbe(context)
    private val dualSubtitleCueMerger = ExoDualSubtitleCueMerger()
    private val secondarySubtitles =
        ExoSecondarySubtitleController(
            context = context,
            customUserAgent = customUserAgent,
            cueMerger = dualSubtitleCueMerger,
        )
    private val startTranscoding =
        this.items
            .getOrNull(startIndex)
            ?.startsWithServerTranscode() == true

    private val _state =
        MutableStateFlow(
            PlaybackState(
                currentIndex = startIndex,
                itemCount = items.size.coerceAtLeast(1),
                speed = startSpeed,
                transcoding = startTranscoding,
                durationMs = this.items.getOrNull(startIndex)?.durationMsHint ?: 0L,
                videoHeight = this.items.getOrNull(startIndex)?.sourceVideoHeight(startTranscoding) ?: 0,
                diagnostics =
                    initialPlaybackDiagnostics(
                        engine = "Media3 / ExoPlayer",
                        decoder = decoderMode.label,
                        item = this.items.getOrNull(startIndex),
                    ).copy(
                        outputEvidence =
                            PlaybackOutputEvidence(
                                sessionRevision = 1L,
                                videoConfidence = PlaybackEvidenceConfidence.Requested,
                                audioConfidence = PlaybackEvidenceConfidence.Requested,
                                renderApi = PlaybackVideoRenderApi.MediaCodecSurface,
                            ),
                    ),
            ),
        )
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val transcodedIndices =
        items.mapIndexedNotNullTo(mutableSetOf()) { index, item ->
            index.takeIf { item.startsWithServerTranscode() }
        }
    private val progressiveTranscodeIndices = mutableSetOf<Int>()
    private val progressiveTransitionIndices = mutableSetOf<Int>()
    private val retryCounts = mutableMapOf<String, Int>()

    /** Compact, credential-free failure trail preserved across replaceMediaItem fallback hops. */
    private val failureHistory = mutableMapOf<Int, MutableList<String>>()
    private var retryJob: Job? = null
    private var fallbackJob: Job? = null
    private var released = false
    private val spatialAudioStateMonitor =
        createAndroidSpatialAudioStateMonitor(context) {
            if (!released) updateAudioOutput()
        }
    private val cacheHandle = VideoCachePool.acquire(context.applicationContext, videoCacheBytes)
    private val trackSelector = DefaultTrackSelector(context)

    val player: ExoPlayer =
        run {
            val httpFactory =
                DefaultHttpDataSource
                    .Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(20_000)
                    .setReadTimeoutMs(20_000)
                    .apply {
                        customUserAgent.trim().takeIf(String::isNotEmpty)?.let { value ->
                            setDefaultRequestProperties(mapOf("User-Agent" to value))
                        }
                    }

            val selector =
                MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    val decoders =
                        MediaCodecUtil.getDecoderInfos(
                            mimeType,
                            requiresSecureDecoder,
                            requiresTunnelingDecoder,
                        )
                    preferExoDecoderMode(decoders, decoderMode) { it.softwareOnly }
                }
            val renderersFactory =
                ExoOutputRenderersFactory(
                    context = context,
                    audioPassthroughMode = audioPassthroughMode,
                    dualSubtitleCueMerger = dualSubtitleCueMerger,
                ).setMediaCodecSelector(selector)
                    .setEnableDecoderFallback(decoderMode != DecoderMode.Hardware)

            val platformUpstream = DefaultDataSource.Factory(context, httpFactory)
            val cachedDataSourceFactory =
                cacheHandle?.let { handle ->
                    CacheDataSource
                        .Factory()
                        .setCache(handle.cache)
                        .setCacheKeyFactory(SecureMediaCacheKeyFactory)
                        .setUpstreamDataSourceFactory(platformUpstream)
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                }
            val playbackDataSourceFactory =
                cachedDataSourceFactory?.let { cachedFactory ->
                    SelectivePlaybackCacheDataSourceFactory(
                        cachedFactory = cachedFactory,
                        upstreamFactory = platformUpstream,
                        shouldCacheUrl = persistentCacheUrls::contains,
                    )
                } ?: platformUpstream
            // Validate the bytes after the optional cache as well as after HTTP. A stale cached HTML
            // error page must not masquerade as an HLS manifest any more than a fresh one may.
            val dataSourceFactory = HlsManifestGuardDataSourceFactory(playbackDataSourceFactory)
            val extractorsFactory =
                DefaultExtractorsFactory()
                    .setTsExtractorFlags(
                        DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
                    ).setTsExtractorTimestampSearchBytes(MPEG_TS_TIMESTAMP_SEARCH_BYTES)
            val loadControl =
                playbackBufferProfile(optimizationMode).let { profile ->
                    DefaultLoadControl
                        .Builder()
                        .setBufferDurationsMs(
                            profile.minBufferMs,
                            profile.maxBufferMs,
                            profile.playbackStartMs,
                            profile.rebufferStartMs,
                        ).setTargetBufferBytes(profile.targetBufferBytes)
                        .setPrioritizeTimeOverSizeThresholds(false)
                        .setBackBuffer(profile.backBufferMs, profile.backBufferMs > 0)
                        .build()
                }

            ExoPlayer
                .Builder(context, renderersFactory)
                .setLoadControl(loadControl)
                .setTrackSelector(trackSelector)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory),
                ).setVideoChangeFrameRateStrategy(exoVideoChangeFrameRateStrategy(frameRateMatchMode))
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    false,
                ).build()
                .apply {
                    videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                }
        }
    private val playerApplicationHandler = Handler(player.applicationLooper)

    override val playbackRequested: Boolean
        get() = player.playWhenReady && player.playbackState != Player.STATE_ENDED

    private var wasBuffering = true
    private var droppedFrames = 0
    private var currentVideoDecoder = decoderMode.label
    private var currentAudioDecoder = ""
    private var currentVideoFormat: Format? = null
    private var currentAudioFormat: Format? = null
    private var currentAudioTrackConfig: AudioSink.AudioTrackConfig? = null
    private var renderedFirstFrame = false
    private var audioUnderrunCount = 0
    private var lastAvSyncSampleAtNs = 0L
    private val videoFrameMetadataListener =
        VideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
            val callbackAtNs = System.nanoTime()
            if (callbackAtNs - lastAvSyncSampleAtNs < AV_SYNC_SAMPLE_INTERVAL_NS) {
                return@VideoFrameMetadataListener
            }
            lastAvSyncSampleAtNs = callbackAtNs
            playerApplicationHandler.post {
                if (released) return@post
                val sampledAtNs = System.nanoTime()
                val releaseDelayMs =
                    ((releaseTimeNs - sampledAtNs) / 1_000_000L)
                        .coerceIn(-MAX_RELEASE_DELAY_MS, MAX_RELEASE_DELAY_MS)
                val mediaClockAtReleaseMs = player.currentPosition + releaseDelayMs
                val offsetMs =
                    (presentationTimeUs / 1_000L - mediaClockAtReleaseMs)
                        .coerceIn(-MAX_REPORTED_AV_SYNC_OFFSET_MS, MAX_REPORTED_AV_SYNC_OFFSET_MS)
                _state.update {
                    it.copy(
                        diagnostics =
                            it.diagnostics.copy(
                                avSyncOffsetMs = offsetMs,
                                avSyncMeasurement = "Media3 呈现/播放时钟",
                            ),
                    )
                }
            }
        }

    private fun clearActiveOutputEvidence() {
        currentVideoDecoder = decoderMode.label
        currentAudioDecoder = ""
        currentVideoFormat = null
        currentAudioFormat = null
        currentAudioTrackConfig = null
        renderedFirstFrame = false
        droppedFrames = 0
        audioUnderrunCount = 0
        lastAvSyncSampleAtNs = 0L
    }

    private fun resetOutputDiagnostics(diagnostics: PlaybackDiagnostics): PlaybackDiagnostics =
        diagnostics.copy(
            decoder = decoderMode.label,
            videoOutput = "等待首帧",
            audioOutput = "等待音频输出",
            videoReadiness = PlaybackOutputReadiness.Waiting,
            audioReadiness = PlaybackOutputReadiness.Waiting,
            dolbyVisionOutput = false,
            immersiveAudioCarrierOutput = false,
            dolbyAtmosOutput = false,
            spatialAudioOutput = false,
            headTrackingAvailable = false,
            droppedFrames = 0,
            avSyncOffsetMs = null,
            avSyncMeasurement = "等待 Media3 呈现时钟",
            outputEvidence =
                diagnostics.outputEvidence.nextSession().copy(
                    videoConfidence = PlaybackEvidenceConfidence.Requested,
                    audioConfidence = PlaybackEvidenceConfidence.Requested,
                    renderApi = PlaybackVideoRenderApi.MediaCodecSurface,
                ),
        )

    private fun updateAudioOutput() {
        val config = currentAudioTrackConfig
        if (config == null) {
            _state.update {
                it.copy(
                    diagnostics =
                        it.diagnostics.copy(
                            audioOutput =
                                currentAudioDecoder.takeIf(String::isNotBlank)?.let { decoder ->
                                    "$decoder · 等待 AudioTrack"
                                } ?: "等待音频输出",
                            audioReadiness = PlaybackOutputReadiness.Waiting,
                            immersiveAudioCarrierOutput = false,
                            dolbyAtmosOutput = false,
                            spatialAudioOutput = false,
                            headTrackingAvailable = false,
                            outputEvidence =
                                it.diagnostics.outputEvidence.copy(
                                    audioReadiness = PlaybackOutputReadiness.Waiting,
                                    audioConfidence = PlaybackEvidenceConfidence.Requested,
                                    audioDecoder = currentAudioDecoder,
                                    audioMode = PlaybackAudioOutputMode.Unknown,
                                ),
                        ),
                )
            }
            return
        }
        val status = exoAudioPassthroughStatus(audioPassthroughMode, config)
        val spatialAudioState =
            if (Util.isEncodingLinearPcm(config.encoding)) {
                spatialAudioProbe.current(
                    sampleRate = config.sampleRate,
                    channelMask = config.channelConfig,
                    encoding = config.encoding,
                )
            } else {
                null
            }
        _state.update {
            it.copy(
                diagnostics =
                    it.diagnostics.copy(
                        audioOutput =
                            exoAudioOutputDiagnosticLabel(
                                status = status,
                                encoding = config.encoding,
                                decoderName = currentAudioDecoder,
                            ),
                        audioReadiness = PlaybackOutputReadiness.Rendering,
                        immersiveAudioCarrierOutput =
                            status is PlaybackOutputStatus.Active &&
                                config.encoding in IMMERSIVE_AUDIO_CARRIER_ENCODINGS,
                        dolbyAtmosOutput =
                            status is PlaybackOutputStatus.Active &&
                                config.encoding in DOLBY_OBJECT_ENCODINGS,
                        spatialAudioOutput = spatialAudioState?.active == true,
                        headTrackingAvailable = spatialAudioState?.headTrackerAvailable == true,
                        outputEvidence =
                            it.diagnostics.outputEvidence.copy(
                                audioReadiness = PlaybackOutputReadiness.Rendering,
                                audioConfidence = PlaybackEvidenceConfidence.Confirmed,
                                audioDecoder = currentAudioDecoder,
                                audioMode =
                                    when {
                                        status is PlaybackOutputStatus.Active ->
                                            PlaybackAudioOutputMode.Passthrough
                                        config.offload -> PlaybackAudioOutputMode.Offload
                                        else -> PlaybackAudioOutputMode.Pcm
                                    },
                            ),
                    ),
            )
        }
    }

    private fun updateVideoOutput() {
        val format = currentVideoFormat
        if (format == null) return
        val sourceDolbyProfile =
            items.getOrNull(player.currentMediaItemIndex)?.activeVersion?.dolbyProfile
        val sourceRange =
            items
                .getOrNull(player.currentMediaItemIndex)
                ?.activeVersion
                ?.sourceDynamicRange
        val detectedRange = format.dynamicRangeLabel(sourceRange).ifBlank { "未知动态范围" }
        val range =
            if (detectedRange.contains("Dolby Vision", ignoreCase = true) && sourceDolbyProfile != null) {
                "Dolby Vision Profile $sourceDolbyProfile"
            } else {
                detectedRange
            }
        val capabilities =
            runCatching { capabilityProvider?.current() }
                .getOrNull()
                ?: PlaybackDeviceCapabilities.conservative()
        val hdrFormat =
            when {
                range.contains("Dolby Vision", ignoreCase = true) -> PlaybackHdrFormat.DolbyVision
                range.contains("HDR10+", ignoreCase = true) -> PlaybackHdrFormat.Hdr10Plus
                range.contains("HLG", ignoreCase = true) -> PlaybackHdrFormat.Hlg
                range.contains("HDR", ignoreCase = true) || range.contains("PQ", ignoreCase = true) ->
                    PlaybackHdrFormat.Hdr10
                else -> null
            }
        val displayReady = hdrFormat == null || hdrFormat in capabilities.hdrFormats
        val nativeDolbyVisionOutput =
            renderedFirstFrame &&
                format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION &&
                hdrFormat == PlaybackHdrFormat.DolbyVision &&
                displayReady
        val secureDecoder = format.exoSecureDecoderActive(currentVideoDecoder)
        val decoderKind =
            if (currentVideoDecoder.isSoftwareVideoDecoder()) "软件解码" else "硬件解码"
        val label =
            when {
                !renderedFirstFrame -> "$range · $decoderKind · 等待首帧"
                hdrFormat == null -> "SDR · $decoderKind · 首帧已输出"
                displayReady -> "$range · $decoderKind · HDR 首帧已输出"
                else -> "$range · $decoderKind · 当前显示链路未声明支持"
            }
        _state.update { state ->
            state.copy(
                diagnostics =
                    state.diagnostics.copy(
                        videoOutput = label,
                        videoReadiness =
                            if (renderedFirstFrame) {
                                PlaybackOutputReadiness.Rendering
                            } else {
                                PlaybackOutputReadiness.Waiting
                            },
                        // The same three facts the label was spelling out: a frame is on
                        // screen, its range is Dolby Vision, and the display chain declared
                        // that format.
                        dolbyVisionOutput = nativeDolbyVisionOutput,
                        outputEvidence =
                            state.diagnostics.outputEvidence.copy(
                                videoReadiness =
                                    if (renderedFirstFrame) {
                                        PlaybackOutputReadiness.Rendering
                                    } else {
                                        PlaybackOutputReadiness.Waiting
                                    },
                                videoConfidence =
                                    if (renderedFirstFrame) {
                                        PlaybackEvidenceConfidence.Confirmed
                                    } else {
                                        PlaybackEvidenceConfidence.Requested
                                    },
                                videoDecoder = currentVideoDecoder,
                                inputDynamicRange = range,
                                outputDynamicRange =
                                    range.takeIf { renderedFirstFrame && displayReady }.orEmpty(),
                                dynamicRangeOutputMode =
                                    if (nativeDolbyVisionOutput) {
                                        PlaybackDynamicRangeOutputMode.DolbyVisionMediaCodec
                                    } else {
                                        PlaybackDynamicRangeOutputMode.Unknown
                                    },
                                renderApi = PlaybackVideoRenderApi.MediaCodecSurface,
                                secureDecoder = secureDecoder,
                                tunneledPlayback = false,
                                bitDepth =
                                    items
                                        .getOrNull(player.currentMediaItemIndex)
                                        ?.activeVersion
                                        ?.sourceBitDepth
                                        ?.coerceAtLeast(0) ?: 0,
                            ),
                    ),
            )
        }
    }

    private val analyticsListener =
        object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                currentVideoDecoder = decoderName
                _state.update {
                    it.copy(
                        diagnostics =
                            it.diagnostics.copy(
                                decoder = decoderName,
                                outputEvidence =
                                    it.diagnostics.outputEvidence.copy(videoDecoder = decoderName),
                            ),
                    )
                }
                updateVideoOutput()
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                currentAudioDecoder = decoderName
                AppLog.info(
                    category = "player.exo",
                    event = "audio_decoder_initialized",
                    message = "ExoPlayer initialized the audio decoder",
                    attributes = mapOf("decoder" to decoderName),
                )
                updateAudioOutput()
            }

            override fun onVideoDecoderReleased(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
            ) {
                if (decoderName != currentVideoDecoder) return
                renderedFirstFrame = false
                _state.update {
                    it.copy(
                        diagnostics =
                            it.diagnostics.copy(
                                videoOutput = "$decoderName · 视频输出已释放",
                                videoReadiness = PlaybackOutputReadiness.Released,
                                dolbyVisionOutput = false,
                                outputEvidence =
                                    it.diagnostics.outputEvidence.copy(
                                        videoReadiness = PlaybackOutputReadiness.Released,
                                        videoConfidence = PlaybackEvidenceConfidence.Confirmed,
                                        outputDynamicRange = "",
                                    ),
                            ),
                    )
                }
            }

            override fun onAudioDecoderReleased(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
            ) {
                if (decoderName != currentAudioDecoder) return
                currentAudioDecoder = ""
                if (currentAudioTrackConfig == null) {
                    _state.update {
                        it.copy(
                            diagnostics =
                                it.diagnostics.copy(
                                    audioOutput = "音频解码器已释放",
                                    audioReadiness = PlaybackOutputReadiness.Released,
                                    immersiveAudioCarrierOutput = false,
                                    dolbyAtmosOutput = false,
                                    spatialAudioOutput = false,
                                    headTrackingAvailable = false,
                                    outputEvidence =
                                        it.diagnostics.outputEvidence.copy(
                                            audioReadiness = PlaybackOutputReadiness.Released,
                                            audioMode = PlaybackAudioOutputMode.Unknown,
                                        ),
                                ),
                        )
                    }
                }
            }

            override fun onVideoCodecError(
                eventTime: AnalyticsListener.EventTime,
                videoCodecError: Exception,
            ) {
                safeLogcat(Log.ERROR, TAG, "video codec failed: $currentVideoDecoder", videoCodecError)
                AppLog.error(
                    category = "player.exo",
                    event = "video_codec_failed",
                    message = "ExoPlayer video codec failed",
                    throwable = videoCodecError,
                    attributes = mapOf("decoder" to currentVideoDecoder),
                )
                _state.update {
                    it.copy(
                        diagnostics =
                            it.diagnostics.copy(
                                videoOutput = "$currentVideoDecoder · 视频解码失败",
                                videoReadiness = PlaybackOutputReadiness.Waiting,
                                dolbyVisionOutput = false,
                                outputEvidence =
                                    it.diagnostics.outputEvidence.copy(
                                        videoReadiness = PlaybackOutputReadiness.Waiting,
                                        videoConfidence = PlaybackEvidenceConfidence.Failed,
                                        codecResetCount =
                                            it.diagnostics.outputEvidence.codecResetCount + 1,
                                    ),
                            ),
                    )
                }
            }

            override fun onAudioCodecError(
                eventTime: AnalyticsListener.EventTime,
                audioCodecError: Exception,
            ) {
                safeLogcat(Log.ERROR, TAG, "audio codec failed: $currentAudioDecoder", audioCodecError)
                AppLog.error(
                    category = "player.exo",
                    event = "audio_codec_failed",
                    message = "ExoPlayer audio codec failed",
                    throwable = audioCodecError,
                    attributes = mapOf("decoder" to currentAudioDecoder.ifBlank { "pending" }),
                )
                currentAudioTrackConfig = null
                _state.update {
                    it.copy(
                        diagnostics =
                            it.diagnostics.copy(
                                audioOutput =
                                    "${currentAudioDecoder.ifBlank { "Media3" }} · 音频解码失败",
                                audioReadiness = PlaybackOutputReadiness.Waiting,
                                immersiveAudioCarrierOutput = false,
                                dolbyAtmosOutput = false,
                                spatialAudioOutput = false,
                                headTrackingAvailable = false,
                                outputEvidence =
                                    it.diagnostics.outputEvidence.copy(
                                        audioReadiness = PlaybackOutputReadiness.Waiting,
                                        audioConfidence = PlaybackEvidenceConfidence.Failed,
                                        codecResetCount =
                                            it.diagnostics.outputEvidence.codecResetCount + 1,
                                    ),
                            ),
                    )
                }
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                currentVideoFormat = format
                val source = items.getOrNull(player.currentMediaItemIndex)?.activeVersion
                AppLog.info(
                    category = "player.exo",
                    event = "video_format_selected",
                    message = "ExoPlayer selected a video input format",
                    attributes =
                        mapOf(
                            "mime" to format.sampleMimeType.orEmpty(),
                            "codecString" to format.codecs.orEmpty(),
                            "decoder" to currentVideoDecoder.ifBlank { "pending" },
                            "width" to format.width.toString(),
                            "height" to format.height.toString(),
                            "colorTransfer" to (format.colorInfo?.colorTransfer?.toString() ?: "unknown"),
                            "dolbyProfile" to (source?.dolbyProfile?.toString() ?: "unknown"),
                        ),
                )
                _state.update {
                    it.copy(
                        diagnostics =
                            it.diagnostics.copy(
                                videoCodec = format.codecs ?: format.sampleMimeType ?: "未知",
                                videoWidth =
                                    format.width.takeIf { value -> value > 0 }
                                        ?: it.diagnostics.videoWidth,
                                dynamicRange =
                                    format
                                        .dynamicRangeLabel(source?.sourceDynamicRange)
                                        .ifBlank { it.diagnostics.dynamicRange },
                                bitrateBitsPerSecond =
                                    format.bitrate
                                        .takeIf { value -> value > 0 }
                                        ?.toLong() ?: it.diagnostics.bitrateBitsPerSecond,
                                frameRate =
                                    format.frameRate.takeIf { value -> value > 0f }
                                        ?: it.diagnostics.frameRate,
                                outputEvidence =
                                    it.diagnostics.outputEvidence.copy(
                                        videoCodecProfile =
                                            format.codecs ?: format.sampleMimeType.orEmpty(),
                                        inputDynamicRange =
                                            format.dynamicRangeLabel(source?.sourceDynamicRange),
                                    ),
                            ),
                    )
                }
                updateVideoOutput()
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                renderedFirstFrame = true
                updateVideoOutput()
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                val source = items.getOrNull(player.currentMediaItemIndex)?.activeVersion
                currentAudioFormat = format
                AppLog.info(
                    category = "player.exo",
                    event = "audio_format_selected",
                    message = "ExoPlayer selected an audio input format",
                    attributes =
                        mapOf(
                            "mime" to format.sampleMimeType.orEmpty(),
                            "codecString" to format.codecs.orEmpty(),
                            "channels" to format.channelCount.toString(),
                            "sourceAtmos" to (source?.dolbyAtmos == true).toString(),
                        ),
                )
                _state.update {
                    it.copy(
                        diagnostics =
                            it.diagnostics.copy(
                                audioFormat =
                                    format
                                        .audioFormatLabel()
                                        .ifBlank { it.diagnostics.audioFormat },
                            ),
                    )
                }
            }

            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig,
            ) {
                currentAudioTrackConfig = audioTrackConfig
                updateAudioOutput()
            }

            override fun onAudioTrackReleased(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig,
            ) {
                if (currentAudioTrackConfig == audioTrackConfig) {
                    currentAudioTrackConfig = null
                }
                _state.update {
                    it.copy(
                        diagnostics =
                            it.diagnostics.copy(
                                audioOutput = "音频输出已释放",
                                audioReadiness = PlaybackOutputReadiness.Released,
                                // The label rule cleared this implicitly, because the released
                                // sentence no longer said 源码输出. A flag has to be told.
                                immersiveAudioCarrierOutput = false,
                                dolbyAtmosOutput = false,
                                spatialAudioOutput = false,
                                headTrackingAvailable = false,
                                outputEvidence =
                                    it.diagnostics.outputEvidence.copy(
                                        audioReadiness = PlaybackOutputReadiness.Released,
                                        audioMode = PlaybackAudioOutputMode.Unknown,
                                    ),
                            ),
                    )
                }
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                this@ExoVideoEngine.droppedFrames += droppedFrames
                _state.update {
                    it.copy(
                        diagnostics =
                            it.diagnostics.copy(
                                droppedFrames = this@ExoVideoEngine.droppedFrames,
                                outputEvidence =
                                    it.diagnostics.outputEvidence.copy(
                                        droppedFramesMeasured = true,
                                    ),
                            ),
                    )
                }
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                audioUnderrunCount++
                AppLog.warning(
                    category = "player.exo",
                    event = "audio_underrun",
                    message = "Media3 AudioTrack underrun",
                    attributes =
                        mapOf(
                            "count" to audioUnderrunCount.toString(),
                            "bufferSize" to bufferSize.toString(),
                            "bufferSizeMs" to bufferSizeMs.toString(),
                            "elapsedSinceLastFeedMs" to elapsedSinceLastFeedMs.toString(),
                        ),
                )
                _state.update {
                    it.copy(
                        diagnostics =
                            it.diagnostics.copy(
                                outputEvidence =
                                    it.diagnostics.outputEvidence.copy(
                                        audioUnderrunCount = audioUnderrunCount,
                                    ),
                            ),
                    )
                }
            }

            override fun onVideoDisabled(
                eventTime: AnalyticsListener.EventTime,
                decoderCounters: DecoderCounters,
            ) {
                decoderCounters.ensureUpdated()
                val countedDrops = decoderCounters.droppedBufferCount.coerceAtLeast(0)
                droppedFrames = maxOf(droppedFrames, countedDrops)
                _state.update {
                    it.copy(
                        diagnostics =
                            it.diagnostics.copy(
                                droppedFrames = droppedFrames,
                                outputEvidence =
                                    it.diagnostics.outputEvidence.copy(
                                        droppedFramesMeasured = true,
                                        codecResetCount =
                                            maxOf(
                                                it.diagnostics.outputEvidence.codecResetCount,
                                                decoderCounters.decoderReleaseCount,
                                            ),
                                        rendererDetail =
                                            "rendered=${decoderCounters.renderedOutputBufferCount}, " +
                                                "maxDrop=${decoderCounters.maxConsecutiveDroppedBufferCount}",
                                    ),
                            ),
                    )
                }
            }

            override fun onAudioDisabled(
                eventTime: AnalyticsListener.EventTime,
                decoderCounters: DecoderCounters,
            ) {
                decoderCounters.ensureUpdated()
                _state.update {
                    it.copy(
                        diagnostics =
                            it.diagnostics.copy(
                                outputEvidence =
                                    it.diagnostics.outputEvidence.copy(
                                        codecResetCount =
                                            maxOf(
                                                it.diagnostics.outputEvidence.codecResetCount,
                                                decoderCounters.decoderReleaseCount,
                                            ),
                                    ),
                            ),
                    )
                }
            }

            override fun onVideoFrameProcessingOffset(
                eventTime: AnalyticsListener.EventTime,
                totalProcessingOffsetUs: Long,
                frameCount: Int,
            ) {
                if (frameCount <= 0) return
                val averageOffsetUs = totalProcessingOffsetUs / frameCount
                _state.update {
                    it.copy(
                        diagnostics =
                            it.diagnostics.copy(
                                outputEvidence =
                                    it.diagnostics.outputEvidence.copy(
                                        rendererDetail = "MediaCodec avgOffset=${averageOffsetUs}us",
                                    ),
                            ),
                    )
                }
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                _state.update {
                    it.copy(
                        diagnostics =
                            it.diagnostics.copy(
                                networkBitsPerSecond = bitrateEstimate.coerceAtLeast(0L),
                            ),
                    )
                }
            }
        }

    private val listener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(playing = isPlaying) }
            }

            override fun onPlaybackStateChanged(state: Int) {
                safeLogcat(Log.INFO, TAG, "exo state=$state")
                val buffering = state == Player.STATE_BUFFERING
                val bufferEvent = buffering && !wasBuffering
                wasBuffering = buffering
                _state.update {
                    it.copy(
                        buffering = buffering,
                        durationMs = knownDuration(it.durationMs),
                        ended = state == Player.STATE_ENDED,
                        diagnostics =
                            it.diagnostics.copy(
                                bufferEvents = it.diagnostics.bufferEvents + if (bufferEvent) 1 else 0,
                            ),
                    )
                }
                if (state == Player.STATE_READY) {
                    retryCounts.remove(retryKey(player.currentMediaItemIndex))
                    fallbackForUnsupportedTracks(
                        tracks = player.currentTracks,
                        includeMissingExpectedAudio = true,
                    )
                }
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                clearActiveOutputEvidence()
                val previousState = _state.value
                val index = player.currentMediaItemIndex
                val item = items.getOrNull(index)
                val transcoding = index in transcodedIndices
                val preservedFallbackReason =
                    previousState.diagnostics.fallbackReason
                        ?.takeIf { previousState.currentIndex == index && it.isNotBlank() }
                        ?: failureHistoryLabel(index)
                _state.update {
                    val sameMedia = it.currentIndex == index
                    it.copy(
                        currentIndex = index,
                        transcoding = transcoding,
                        videoHeight = item?.sourceVideoHeight(transcoding) ?: 0,
                        fallbacksExhausted = false,
                        automaticFallbackBlocked = false,
                        positionMs = if (sameMedia) it.positionMs else 0L,
                        durationMs =
                            knownDuration(
                                if (sameMedia) {
                                    it.durationMs
                                } else {
                                    item?.durationMsHint ?: 0L
                                },
                            ),
                        bufferedPositionMs = if (sameMedia) it.bufferedPositionMs else 0L,
                        error = null,
                        ended = false,
                        diagnostics =
                            initialPlaybackDiagnostics(
                                engine = "Media3 / ExoPlayer",
                                decoder = it.diagnostics.decoder,
                                item = item,
                                transcoding = transcoding,
                            ).copy(
                                fallbackReason = preservedFallbackReason,
                                outputEvidence =
                                    previousState.diagnostics.outputEvidence.nextSession().copy(
                                        videoConfidence = PlaybackEvidenceConfidence.Requested,
                                        audioConfidence = PlaybackEvidenceConfidence.Requested,
                                        renderApi = PlaybackVideoRenderApi.MediaCodecSurface,
                                    ),
                            ),
                    )
                }
            }

            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
                _state.update { it.copy(speed = parameters.speed) }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _state.update {
                    it.copy(
                        videoHeight = videoSize.height,
                        diagnostics = it.diagnostics.copy(videoWidth = videoSize.width),
                    )
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                syncTracks()
                fallbackForUnsupportedTracks(tracks, includeMissingExpectedAudio = false)
            }

            override fun onPlayerError(error: PlaybackException) {
                safeLogcat(Log.ERROR, TAG, "playback failed: ${error.errorCodeName}", error)
                val index = player.currentMediaItemIndex
                val causes = generateSequence(error as Throwable) { it.cause }.toList()
                val httpCause =
                    causes
                        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                        .firstOrNull()
                val invalidHlsCause =
                    causes
                        .filterIsInstance<InvalidHlsManifestResponseException>()
                        .firstOrNull()
                val failedRendererMime =
                    (error as? ExoPlaybackException)?.rendererFormat?.sampleMimeType
                        ?: currentAudioFormat?.sampleMimeType
                rememberPlaybackFailure(index, error, httpCause, invalidHlsCause)
                val failedUrl = httpCause?.dataSpec?.uri?.toString()
                AppLog.error(
                    category = "player.exo",
                    event = "playback_failed",
                    message = "ExoPlayer playback failed",
                    throwable = error,
                    attributes =
                        buildMap {
                            put("errorCode", error.errorCodeName)
                            put("itemIndex", index.toString())
                            put("transcoding", _state.value.transcoding.toString())
                            put("streamVariant", streamVariantOf(index))
                            put("failureChain", failureHistoryLabel(index))
                            failedUrl?.let { url ->
                                put("requestUrl", sanitizePlaybackUrl(url))
                                playbackQueryParameter(url, "MediaSourceId")?.let {
                                    put("mediaSourceId", it)
                                }
                            }
                            invalidHlsCause?.let { invalid ->
                                invalid.contentType?.let { put("hlsContentType", it.take(120)) }
                                invalid.redactedPreview
                                    .takeIf(String::isNotBlank)
                                    ?.let { put("hlsBodyPreview", it) }
                            }
                            httpCause?.let {
                                put("httpStatus", it.responseCode.toString())
                                put("httpMessage", it.responseMessage.orEmpty().take(120))
                                it.responseBody
                                    .takeIf { body -> body.isNotEmpty() }
                                    ?.toString(Charsets.UTF_8)
                                    ?.replace(Regex("\\s+"), " ")
                                    ?.trim()
                                    ?.take(240)
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { body -> put("httpBody", sanitizePlaybackUrl(body)) }
                            }
                        },
                )

                // An HTTP 200 response that is HTML/JSON instead of #EXTM3U is deterministic. Do
                // not retry the same body: advance the fallback ladder immediately and retain the
                // failure chain for diagnostics.
                if (invalidHlsCause != null) {
                    val recovered =
                        if (index in transcodedIndices) {
                            switchToProgressiveTranscode()
                        } else {
                            switchToTranscode()
                        }
                    if (!recovered) {
                        failPlayback(
                            "服务器没有返回有效的 HLS 清单，且已无可用转码方式",
                            kind = PlaybackFailureKind.Container,
                        )
                    }
                    return
                }

                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                        // A malformed manifest is deterministic. Retrying the same bytes only
                        // duplicates the failure and delays the progressive fallback.
                        if (!switchToProgressiveTranscode()) {
                            failPlayback("服务器返回了无效的转码清单", kind = PlaybackFailureKind.Container)
                        }

                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    ->
                        if (failedRendererMime.requiresExoDolbyAudioSafetyFallback()) {
                            failPlayback(
                                "Exo 的 ${failedRendererMime.orEmpty()} 音频路径失败，交给本地兼容内核安全解码",
                                kind = PlaybackFailureKind.AudioSink,
                            )
                        } else if (!switchToTranscode()) {
                            failPlayback(
                                "当前视频无法解码，且服务器未提供可用转码流",
                                kind = PlaybackFailureKind.Decoder,
                            )
                        }

                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    -> {
                        val message =
                            httpFailureMessage(
                                httpCause?.responseCode,
                                httpCause?.responseBody?.toString(Charsets.UTF_8),
                            )
                        if (blocksAutomaticPlaybackFallback(httpCause?.responseCode)) {
                            failPlayback(
                                message,
                                blockAutomaticFallback = true,
                                kind = PlaybackFailureKind.Authorization,
                            )
                        } else if (!advanceFallback()) {
                            failPlayback(message, kind = PlaybackFailureKind.Network)
                        }
                    }

                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    ->
                        if (
                            !scheduleRetry(index, TRANSIENT_RETRY_LIMIT, "transient_network") &&
                            !advanceFallback()
                        ) {
                            failPlayback(
                                "网络连接多次失败，已尝试所有播放方式",
                                kind = PlaybackFailureKind.Network,
                            )
                        }

                    else ->
                        failPlayback(
                            "播放失败：${error.errorCodeName}",
                            kind = error.playbackFailureKind(),
                        )
                }
            }
        }

    private var ticker: Job? = null

    init {
        AppLog.info(
            category = "player.exo",
            event = "output_preferences_applied",
            message = "ExoPlayer output preferences were applied",
            attributes =
                mapOf(
                    "frameRateMatch" to frameRateMatchMode.toString(),
                    "audioPassthrough" to audioPassthroughMode.toString(),
                    "mpegTsHdmvDts" to "true",
                    "mpegTsTimestampSearchBytes" to MPEG_TS_TIMESTAMP_SEARCH_BYTES.toString(),
                ),
        )
        player.addListener(listener)
        player.addAnalyticsListener(analyticsListener)
        player.setVideoFrameMetadataListener(videoFrameMetadataListener)
        player.setMediaItems(
            items.mapIndexed { index, item ->
                mediaItem(item, if (index in transcodedIndices) item.transcodeUrl else item.url)
            },
            startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            startPositionMs,
        )
        player.pauseAtEndOfMediaItems = !autoNext
        _state.update { it.copy(transcoding = startIndex in transcodedIndices) }
        player.setPlaybackSpeed(startSpeed)
        player.playWhenReady = startPlaybackRequested
        player.prepare()

        ticker =
            scope.launch {
                while (isActive) {
                    _state.update {
                        val bufferedDurationMs = player.totalBufferedDuration.coerceAtLeast(0L)
                        it.copy(
                            positionMs = player.currentPosition,
                            durationMs = knownDuration(it.durationMs),
                            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
                            diagnostics =
                                it.diagnostics.copy(
                                    bufferedDurationMs = bufferedDurationMs,
                                ),
                        )
                    }
                    secondarySubtitles.reconcile(
                        mainIndex = player.currentMediaItemIndex,
                        mainPositionMs = player.currentPosition,
                        mainSpeed = player.playbackParameters.speed,
                        mainPlayWhenReady = player.playWhenReady,
                    )
                    delay(
                        exoRuntimeCadence.intervalMs(
                            playing = player.isPlaying,
                            buffering = player.isLoading,
                            pendingWork = secondarySubtitles.needsReconciliation,
                        ),
                    )
                }
            }
    }

    override fun play() {
        _state.update { it.copy(ended = false) }
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun prepareForHandover() {
        player.pause()
        // Prevent the outgoing AudioTrack and Surface from overlapping their replacements during
        // the short interval between Compose creating the new engine and disposing this one.
        player.volume = 0f
        player.clearVideoSurface()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _state.update {
            it.copy(
                positionMs = positionMs,
                bufferedPositionMs = positionMs.coerceAtLeast(0L),
                ended = false,
            )
        }
    }

    override fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun selectAudioTrack(id: String) = select(C.TRACK_TYPE_AUDIO, id)

    override fun selectSubtitleTrack(id: String) = select(C.TRACK_TYPE_TEXT, id)

    override val supportsSecondarySubtitleTrack: Boolean = true

    override val supportsSubtitleScale: Boolean = true

    override val supportsSubtitleBrightness: Boolean = true

    override val supportsSubtitlePosition: Boolean = true

    override fun selectSecondarySubtitleTrack(id: String): Boolean {
        if (id == EngineTrack.OFF) {
            secondarySubtitles.disable()
            return true
        }
        val groupIndex = id.substringBefore(':').toIntOrNull() ?: return false
        val trackIndex = id.substringAfter(':').toIntOrNull() ?: return false
        val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return false
        if (group.type != C.TRACK_TYPE_TEXT || trackIndex !in 0 until group.length) return false
        val mediaItems = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        return secondarySubtitles.select(
            identity = group.getTrackFormat(trackIndex).subtitleTrackIdentity(),
            mediaItems = mediaItems,
            currentIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            speed = player.playbackParameters.speed,
            playWhenReady = player.playWhenReady,
        )
    }

    override fun setSubtitleBrightness(brightness: Float): Boolean = true

    override fun setPauseAtEndOfCurrentItem(enabled: Boolean) {
        player.pauseAtEndOfMediaItems = enabled || !autoNext
    }

    override fun selectItem(index: Int) {
        if (index !in items.indices) return
        failureHistory.remove(index)
        clearActiveOutputEvidence()
        _state.update {
            it.copy(
                error = null,
                buffering = true,
                bufferedPositionMs = 0L,
                ended = false,
                transcoding = index in transcodedIndices,
                fallbacksExhausted = false,
                diagnostics = resetOutputDiagnostics(it.diagnostics),
            )
        }
        player.seekToDefaultPosition(index)
        player.play()
    }

    override fun currentPositionMs(): Long = player.currentPosition

    override fun retry() {
        clearActiveOutputEvidence()
        _state.update {
            it.copy(
                error = null,
                buffering = true,
                bufferedPositionMs = it.positionMs,
                ended = false,
                automaticFallbackBlocked = false,
                diagnostics = resetOutputDiagnostics(it.diagnostics),
            )
        }
        player.prepare()
        player.playWhenReady = true
    }

    override fun release() {
        released = true
        retryJob?.cancel()
        retryJob = null
        fallbackJob?.cancel()
        fallbackJob = null
        ticker?.cancel()
        ticker = null
        secondarySubtitles.release()
        spatialAudioStateMonitor?.release()
        player.clearVideoFrameMetadataListener(videoFrameMetadataListener)
        player.removeListener(listener)
        player.removeAnalyticsListener(analyticsListener)
        player.release()
        cacheHandle?.close()
    }

    private fun select(
        type: Int,
        id: String,
    ) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (id == EngineTrack.OFF) {
            player.trackSelectionParameters =
                builder
                    .clearOverridesOfType(type)
                    .setTrackTypeDisabled(type, true)
                    .build()
            syncTracks()
            return
        }

        val groupIndex = id.substringBefore(':').toIntOrNull() ?: return
        val trackIndex = id.substringAfter(':').toIntOrNull() ?: return
        val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return

        if (type == C.TRACK_TYPE_AUDIO && !group.isTrackSupported(trackIndex)) {
            val format = group.getTrackFormat(trackIndex)
            val index = player.currentMediaItemIndex
            val alreadyTranscoding = index in transcodedIndices
            val recovery = unsupportedTrackRecovery(UnsupportedMediaTrack.Audio, alreadyTranscoding)
            AppLog.warning(
                category = "player.exo",
                event = "unsupported_audio_track_selected",
                message =
                    when (recovery) {
                        UnsupportedTrackRecovery.ServerTranscode ->
                            "Selected audio track is unsupported; attempting server transcode"
                        UnsupportedTrackRecovery.SwitchEngine ->
                            "Selected transcoded audio track is unsupported; trying another playback engine"
                    },
                attributes =
                    mapOf(
                        "itemIndex" to index.toString(),
                        "trackId" to id,
                        "sampleMimeType" to format.sampleMimeType.orEmpty(),
                        "codecs" to format.codecs.orEmpty(),
                        "alreadyTranscoding" to alreadyTranscoding.toString(),
                    ),
            )
            if (recovery == UnsupportedTrackRecovery.ServerTranscode &&
                switchToTranscode("所选音轨不受 ExoPlayer 支持")
            ) {
                return
            }
            player.pause()
            failPlayback(
                if (alreadyTranscoding) {
                    "服务器转码音轨仍不受支持，正在尝试其他播放器"
                } else {
                    "当前音轨不受 ExoPlayer 支持，正在尝试其他播放器"
                },
                kind = PlaybackFailureKind.AudioSink,
            )
            return
        }

        player.trackSelectionParameters =
            builder
                .setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                .build()
        syncTracks()
    }

    private fun syncTracks() {
        val tracks = player.currentTracks
        _state.update {
            it.copy(
                audioTracks = tracks.toEngineTracks(C.TRACK_TYPE_AUDIO, "音轨"),
                subtitleTracks = tracks.toEngineTracks(C.TRACK_TYPE_TEXT, "字幕"),
            )
        }
    }

    private fun fallbackForUnsupportedTracks(
        tracks: Tracks,
        includeMissingExpectedAudio: Boolean,
    ) {
        val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val videoSupported =
            videoGroups.any { group ->
                (0 until group.length).any { group.isTrackSupported(it) }
            }
        val audioSupported =
            audioGroups.any { group ->
                (0 until group.length).any { group.isTrackSupported(it) }
            }
        val index = player.currentMediaItemIndex
        val expectedAudio =
            items
                .getOrNull(index)
                ?.activeVersion
                ?.audioTrackCount
                ?.let { it > 0 } == true
        val unsupported =
            unsupportedMediaTrack(
                hasVideo = videoGroups.isNotEmpty(),
                videoSupported = videoSupported,
                hasAudio =
                    audioGroups.isNotEmpty() ||
                        (includeMissingExpectedAudio && expectedAudio),
                audioSupported = audioSupported,
            ) ?: return

        val type = unsupported.name.lowercase()
        val alreadyTranscoding = index in transcodedIndices
        val recovery = unsupportedTrackRecovery(unsupported, alreadyTranscoding)
        val unsupportedFormats =
            when (unsupported) {
                UnsupportedMediaTrack.Audio -> audioGroups
                UnsupportedMediaTrack.Video -> videoGroups
            }.flatMap { group ->
                (0 until group.length).map { trackIndex ->
                    group.getTrackFormat(trackIndex).let { format ->
                        format.sampleMimeType ?: format.codecs ?: "unknown"
                    }
                }
            }.distinct()
                .joinToString(",")
                .take(160)
        safeLogcat(Log.WARN, TAG, "no supported $type track; recovery=$recovery")
        AppLog.warning(
            category = "player.exo",
            event = "unsupported_${type}_tracks",
            message =
                when (recovery) {
                    UnsupportedTrackRecovery.SwitchEngine ->
                        "No supported $type track; trying another playback engine"
                    UnsupportedTrackRecovery.ServerTranscode ->
                        "No supported $type track; attempting server transcode"
                },
            attributes =
                mapOf(
                    "itemIndex" to index.toString(),
                    "missingExpectedAudio" to
                        (expectedAudio && audioGroups.isEmpty()).toString(),
                    "alreadyTranscoding" to alreadyTranscoding.toString(),
                    "formats" to unsupportedFormats,
                ),
        )
        when (recovery) {
            UnsupportedTrackRecovery.SwitchEngine -> {
                player.pause()
                failPlayback(
                    if (alreadyTranscoding) {
                        "服务器转码流没有可播放的声音，正在尝试其他播放器"
                    } else {
                        "当前音轨不受 ExoPlayer 支持，正在尝试其他播放器"
                    },
                    kind = PlaybackFailureKind.AudioSink,
                )
            }
            UnsupportedTrackRecovery.ServerTranscode ->
                if (!switchToTranscode()) {
                    failPlayback(
                        if (unsupported == UnsupportedMediaTrack.Audio) {
                            "当前音轨不受 ExoPlayer 支持，正在尝试其他播放器"
                        } else {
                            "当前视频无法解码，正在尝试其他播放器"
                        },
                        kind =
                            if (unsupported == UnsupportedMediaTrack.Audio) {
                                PlaybackFailureKind.AudioSink
                            } else {
                                PlaybackFailureKind.Decoder
                            },
                    )
                }
        }
    }

    override fun switchToTranscode(reason: String?): Boolean {
        val index = player.currentMediaItemIndex
        if (index in transcodedIndices) return switchToProgressiveTranscode()
        val item = items.getOrNull(index) ?: return false
        if (!item.allowsServerTranscodeFallback(reason)) return false
        if (item.transcodeUrl.isEmpty()) return switchToProgressiveTranscode()
        transcodedIndices += index
        val position = player.currentPosition
        val fallbackReason = failureChainReason(index, reason ?: "直放失败，已切换服务器转码")
        clearActiveOutputEvidence()
        _state.update {
            it.copy(
                error = null,
                buffering = true,
                bufferedPositionMs = position,
                transcoding = true,
                diagnostics =
                    it.diagnostics.copy(
                        playMethod = "服务器转码",
                        dynamicRange = "",
                        audioFormat = "",
                        videoOutput = "等待转码视频首帧",
                        audioOutput = "等待转码音频输出",
                        videoReadiness = PlaybackOutputReadiness.Waiting,
                        audioReadiness = PlaybackOutputReadiness.Waiting,
                        fallbackReason = fallbackReason,
                        bufferedDurationMs = 0L,
                    ),
            )
        }
        safeLogcat(Log.INFO, TAG, "falling back to transcode for index=$index")
        AppLog.info(
            category = "player.exo",
            event = "transcode_fallback",
            message = "Switching from direct play to server transcode",
            attributes =
                mapOf(
                    "itemIndex" to index.toString(),
                    "failureChain" to failureHistoryLabel(index),
                ),
        )
        player.replaceMediaItem(index, mediaItem(item, item.transcodeUrl))
        secondarySubtitles.replaceMediaItem(index, player.getMediaItemAt(index))
        player.prepare()
        player.seekTo(index, position)
        player.playWhenReady = true
        return true
    }

    override fun appendItems(items: List<PlayerMediaItem>): Boolean {
        if (items.isEmpty()) return true
        val offset = this.items.size
        items.forEachIndexed { relativeIndex, item ->
            if (item.startsWithServerTranscode()) transcodedIndices += offset + relativeIndex
        }
        items.mapNotNullTo(persistentCacheUrls) { it.persistentPlaybackCacheUrl() }
        this.items += items
        val mediaItems =
            items.mapIndexed { relativeIndex, item ->
                val index = offset + relativeIndex
                mediaItem(item, if (index in transcodedIndices) item.transcodeUrl else item.url)
            }
        player.addMediaItems(mediaItems)
        secondarySubtitles.appendMediaItems(mediaItems)
        _state.update { it.copy(itemCount = this.items.size.coerceAtLeast(1)) }
        AppLog.info(
            category = "player.exo",
            event = "queue_extended",
            message = "Queue extended without restarting playback",
            attributes =
                mapOf(
                    "addedCount" to items.size.toString(),
                    "itemCount" to this.items.size.toString(),
                ),
        )
        return true
    }

    private fun switchToProgressiveTranscode(): Boolean {
        val index = player.currentMediaItemIndex
        if (index in progressiveTranscodeIndices) return false
        if (index in progressiveTransitionIndices) return true
        val item = items.getOrNull(index) ?: return false
        if (item.requiresLocalDolbyPipeline && index !in transcodedIndices) return false
        if (item.fallbackTranscodeUrl.isEmpty()) return false
        transcodedIndices += index
        progressiveTransitionIndices += index
        val position = player.currentPosition
        val fallbackReason = failureChainReason(index, "HLS 转码不可用，已改用 MP4 转码")
        clearActiveOutputEvidence()
        _state.update {
            it.copy(
                error = null,
                buffering = true,
                bufferedPositionMs = position,
                transcoding = true,
                diagnostics =
                    it.diagnostics.copy(
                        playMethod = PlaybackMethod.Transcode.label,
                        dynamicRange = "",
                        audioFormat = "",
                        videoOutput = "等待转码视频首帧",
                        audioOutput = "等待转码音频输出",
                        videoReadiness = PlaybackOutputReadiness.Waiting,
                        audioReadiness = PlaybackOutputReadiness.Waiting,
                        fallbackReason = fallbackReason,
                    ),
            )
        }
        player.stop()
        safeLogcat(Log.INFO, TAG, "cleaning HLS encoder before progressive fallback for index=$index")
        AppLog.info(
            category = "player.exo",
            event = "progressive_transcode_cleanup",
            message = "Stopping the HLS encoder before progressive transcode fallback",
            attributes =
                mapOf(
                    "itemIndex" to index.toString(),
                    "failureChain" to failureHistoryLabel(index),
                ),
        )
        fallbackJob?.cancel()
        fallbackJob =
            scope.launch {
                val cleaned =
                    item.playSessionId.isBlank() ||
                        withTimeoutOrNull(5_000L) { stopEncoding(item.playSessionId) } == true
                if (released || player.currentMediaItemIndex != index) return@launch
                progressiveTransitionIndices -= index
                if (!cleaned) {
                    AppLog.warning(
                        category = "player.exo",
                        event = "progressive_transcode_cleanup_failed",
                        message = "The active HLS encoder could not be stopped safely",
                        attributes = mapOf("itemIndex" to index.toString()),
                    )
                    failPlayback(
                        "无法清理旧的服务器转码，正在尝试其他播放器",
                        // The server would not release its old encode. No decoder on this
                        // device was involved, so nothing here may be held against one.
                        kind = PlaybackFailureKind.Network,
                    )
                    return@launch
                }
                progressiveTranscodeIndices += index
                AppLog.info(
                    category = "player.exo",
                    event = "progressive_transcode_fallback",
                    message = "Switching from HLS to progressive transcode",
                    attributes =
                        mapOf(
                            "itemIndex" to index.toString(),
                            "failureChain" to failureHistoryLabel(index),
                        ),
                )
                player.replaceMediaItem(index, mediaItem(item, item.fallbackTranscodeUrl))
                secondarySubtitles.replaceMediaItem(index, player.getMediaItemAt(index))
                player.prepare()
                player.seekTo(index, position)
                player.playWhenReady = true
            }
        return true
    }

    private fun advanceFallback(): Boolean = switchToTranscode() || switchToProgressiveTranscode()

    private fun scheduleRetry(
        index: Int,
        limit: Int,
        reason: String,
    ): Boolean {
        val key = retryKey(index)
        val attempted = retryCounts[key] ?: 0
        if (attempted >= limit) return false
        val nextAttempt = attempted + 1
        retryCounts[key] = nextAttempt
        retryJob?.cancel()
        clearActiveOutputEvidence()
        _state.update {
            it.copy(
                error = null,
                buffering = true,
                diagnostics = resetOutputDiagnostics(it.diagnostics),
            )
        }
        AppLog.info(
            category = "player.exo",
            event = "playback_retry_scheduled",
            message = "Retrying the current playback request after a transient failure",
            attributes =
                mapOf(
                    "itemIndex" to index.toString(),
                    "streamVariant" to streamVariantOf(index),
                    "attempt" to nextAttempt.toString(),
                    "limit" to limit.toString(),
                    "reason" to reason,
                    "failureChain" to failureHistoryLabel(index),
                ),
        )
        retryJob =
            scope.launch {
                delay(if (nextAttempt == 1) 500L else 1_500L)
                if (released || player.currentMediaItemIndex != index) return@launch
                player.prepare()
                player.playWhenReady = true
            }
        return true
    }

    private fun retryKey(index: Int): String = "$index:${streamVariantOf(index)}"

    private fun streamVariantOf(index: Int): String =
        when {
            index in progressiveTranscodeIndices -> "progressive"
            index in transcodedIndices -> "hls"
            else -> "direct"
        }

    private fun rememberPlaybackFailure(
        index: Int,
        error: PlaybackException,
        httpCause: HttpDataSource.InvalidResponseCodeException?,
        invalidHlsCause: InvalidHlsManifestResponseException?,
    ) {
        val detail =
            buildString {
                append(streamVariantOf(index))
                append(':')
                append(error.errorCodeName)
                httpCause?.let { append(":http").append(it.responseCode) }
                if (invalidHlsCause != null) append(":invalid_hls_signature")
            }
        val history = failureHistory.getOrPut(index) { mutableListOf() }
        if (history.lastOrNull() != detail) history += detail
        while (history.size > FAILURE_HISTORY_LIMIT) history.removeAt(0)
    }

    private fun failureHistoryLabel(index: Int): String =
        failureHistory[index]
            .orEmpty()
            .joinToString(" → ")
            .take(480)

    private fun failureChainReason(
        index: Int,
        action: String,
    ): String =
        failureHistoryLabel(index)
            .takeIf(String::isNotBlank)
            ?.let { "$it → $action" }
            ?: action

    private fun failPlayback(
        message: String,
        blockAutomaticFallback: Boolean = false,
        kind: PlaybackFailureKind? = null,
    ) {
        _state.update {
            it.copy(
                error = message,
                errorKind = kind,
                buffering = false,
                fallbacksExhausted = true,
                automaticFallbackBlocked = blockAutomaticFallback,
            )
        }
    }

    private fun knownDuration(previousDurationMs: Long = 0L): Long =
        player.duration
            .takeIf { it != C.TIME_UNSET && it > 0L }
            ?: previousDurationMs.coerceAtLeast(0L)
}

private const val AV_SYNC_SAMPLE_INTERVAL_NS = 1_000_000_000L
private const val MAX_RELEASE_DELAY_MS = 250L
private const val MAX_REPORTED_AV_SYNC_OFFSET_MS = 5_000L

private fun String.isSoftwareVideoDecoder(): Boolean {
    val normalized = lowercase()
    return normalized.startsWith("omx.google.") ||
        normalized.startsWith("c2.android.") ||
        normalized.contains("ffmpeg") ||
        normalized.contains("software")
}

/**
 * Keeps explicit decoder choices deterministic without making a device-specific codec list stale.
 * MediaCodecUtil has already applied Android's secure/tunnel and platform compatibility filters;
 * this final pass only honours the user's requested implementation class.
 */
internal fun <T> preferExoDecoderMode(
    decoders: List<T>,
    decoderMode: DecoderMode,
    isSoftwareOnly: (T) -> Boolean,
): List<T> =
    when (decoderMode) {
        DecoderMode.Software -> decoders.filter(isSoftwareOnly).ifEmpty { decoders }
        DecoderMode.Hardware -> decoders.filterNot(isSoftwareOnly).ifEmpty { decoders }
        DecoderMode.Auto -> decoders
    }

@UnstableApi
private fun Format.exoSecureDecoderActive(decoderName: String): Boolean {
    val mimeType = sampleMimeType ?: return false
    if (drmInitData == null) return false
    val secureDecoderNames =
        runCatching {
            MediaCodecUtil
                .getDecoderInfos(mimeType, true, false)
                .mapTo(hashSetOf()) { it.name.lowercase() }
        }.getOrDefault(emptySet())
    return decoderName.lowercase() in secureDecoderNames ||
        decoderName.contains("secure", ignoreCase = true)
}

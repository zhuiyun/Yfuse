package com.yfuse.feature.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.safeLogcat
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core.playback.PlaybackHdrFormat
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core.playback.isAdaptivePlaybackManifest
import com.yfuse.core.playback.playbackBufferProfile
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

private const val TAG = "YfusePlayer"
private val exoRuntimeCadence =
    PlaybackRuntimeCadence(
        activeIntervalMs = PLAYBACK_PROGRESS_STEP_MS,
        idleIntervalMs = 2_000L,
    )
private const val TRANSIENT_RETRY_LIMIT = 2
private const val MANIFEST_RETRY_LIMIT = 1

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
    decoderMode: DecoderMode,
    optimizationMode: PlaybackOptimizationMode,
    private val autoNext: Boolean,
    private val quality: PlaybackQuality,
    customUserAgent: String,
    videoCacheBytes: Long,
    private val stopEncoding: suspend (String) -> Boolean = { true },
) : VideoEngine {
    private val items = items.map { it.withPlaybackQuality(quality) }.toMutableList()
    private val outputPreferences = GlobalContext.get().get<PlaybackPreferences>()
    private val capabilityProvider =
        runCatching { GlobalContext.get().get<PlaybackDeviceCapabilitiesProvider>() }.getOrNull()
    internal val frameRateMatchMode = outputPreferences.frameRateMatch.value.toPlayerMode()
    private val audioPassthroughMode = outputPreferences.audioPassthrough.value.toPlayerMode()
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
            ?.startsWithServerTranscode(quality) == true

    private val _state =
        MutableStateFlow(
            PlaybackState(
                currentIndex = startIndex,
                itemCount = items.size.coerceAtLeast(1),
                speed = startSpeed,
                transcoding = startTranscoding,
                videoHeight = this.items.getOrNull(startIndex)?.sourceVideoHeight(startTranscoding) ?: 0,
                diagnostics =
                    initialPlaybackDiagnostics(
                        engine = "Media3 / ExoPlayer",
                        decoder = decoderMode.label,
                        item = this.items.getOrNull(startIndex),
                        quality = quality,
                    ),
            ),
        )
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val transcodedIndices =
        items.mapIndexedNotNullTo(mutableSetOf()) { index, item ->
            index.takeIf { item.startsWithServerTranscode(quality) }
        }
    private val progressiveTranscodeIndices = mutableSetOf<Int>()
    private val progressiveTransitionIndices = mutableSetOf<Int>()
    private val retryCounts = mutableMapOf<String, Int>()
    private var retryJob: Job? = null
    private var fallbackJob: Job? = null
    private var released = false
    private val cacheHandle = VideoCachePool.acquire(context.applicationContext, videoCacheBytes)
    private val trackSelector = DefaultTrackSelector(context)
    private var qualityCeiling = quality

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
                if (decoderMode == DecoderMode.Software) {
                    MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                        val decoders =
                            MediaCodecUtil.getDecoderInfos(
                                mimeType,
                                requiresSecureDecoder,
                                requiresTunnelingDecoder,
                            )
                        decoders.filter { it.softwareOnly }.ifEmpty { decoders }
                    }
                } else {
                    MediaCodecSelector.DEFAULT
                }
            val renderersFactory =
                ExoOutputRenderersFactory(
                    context = context,
                    audioPassthroughMode = audioPassthroughMode,
                    dualSubtitleCueMerger = dualSubtitleCueMerger,
                ).setMediaCodecSelector(selector)
                    .setEnableDecoderFallback(decoderMode != DecoderMode.Hardware)

            val upstream = DefaultDataSource.Factory(context, httpFactory)
            val dataSourceFactory =
                cacheHandle?.let { handle ->
                    CacheDataSource
                        .Factory()
                        .setCache(handle.cache)
                        .setCacheKeyFactory(SecureMediaCacheKeyFactory)
                        .setUpstreamDataSourceFactory(upstream)
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                } ?: upstream
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
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setVideoChangeFrameRateStrategy(exoVideoChangeFrameRateStrategy(frameRateMatchMode))
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
    private var currentVideoFormat: Format? = null
    private var renderedFirstFrame = false
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

    private fun updateVideoOutput() {
        val format = currentVideoFormat
        if (format == null) return
        val range = format.dynamicRangeLabel().ifBlank { "未知动态范围" }
        val capabilities =
            runCatching { capabilityProvider?.current() }
                .getOrNull()
                ?: PlaybackDeviceCapabilities.conservative()
        val hdrFormat =
            when {
                range.contains("Dolby Vision", ignoreCase = true) -> PlaybackHdrFormat.DolbyVision
                range.contains("HLG", ignoreCase = true) -> PlaybackHdrFormat.Hlg
                range.contains("HDR", ignoreCase = true) || range.contains("PQ", ignoreCase = true) ->
                    PlaybackHdrFormat.Hdr10
                else -> null
            }
        val displayReady = hdrFormat == null || hdrFormat in capabilities.hdrFormats
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
                        dolbyVisionOutput =
                            renderedFirstFrame &&
                                hdrFormat == PlaybackHdrFormat.DolbyVision &&
                                displayReady,
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
                    it.copy(diagnostics = it.diagnostics.copy(decoder = decoderName))
                }
                updateVideoOutput()
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                currentVideoFormat = format
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
                                        .dynamicRangeLabel()
                                        .ifBlank { it.diagnostics.dynamicRange },
                                bitrateBitsPerSecond =
                                    format.bitrate
                                        .takeIf { value -> value > 0 }
                                        ?.toLong() ?: it.diagnostics.bitrateBitsPerSecond,
                                frameRate =
                                    format.frameRate.takeIf { value -> value > 0f }
                                        ?: it.diagnostics.frameRate,
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
                val status = exoAudioPassthroughStatus(audioPassthroughMode, audioTrackConfig)
                _state.update {
                    it.copy(
                        diagnostics =
                            it.diagnostics.copy(
                                audioOutput =
                                    playbackOutputDiagnosticLabel(
                                        status = status,
                                        activeLabel =
                                            "源码输出 · ${exoAudioEncodingLabel(audioTrackConfig.encoding)}",
                                    ),
                                audioReadiness = PlaybackOutputReadiness.Rendering,
                                // Active is the only status that proves a bitstream left the
                                // device; the encoding says whether it carried Dolby objects.
                                dolbyAtmosOutput =
                                    status is PlaybackOutputStatus.Active &&
                                        audioTrackConfig.encoding in DOLBY_OBJECT_ENCODINGS,
                            ),
                    )
                }
            }

            override fun onAudioTrackReleased(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig,
            ) {
                _state.update {
                    it.copy(
                        diagnostics =
                            it.diagnostics.copy(
                                audioOutput = "音频输出已释放",
                                audioReadiness = PlaybackOutputReadiness.Released,
                                // The label rule cleared this implicitly, because the released
                                // sentence no longer said 源码输出. A flag has to be told.
                                dolbyAtmosOutput = false,
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
                        durationMs = knownDuration(),
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
                currentVideoFormat = null
                renderedFirstFrame = false
                val index = player.currentMediaItemIndex
                val item = items.getOrNull(index)
                val transcoding = index in transcodedIndices
                if (
                    !transcoding &&
                    mediaItem
                        ?.localConfiguration
                        ?.uri
                        ?.toString()
                        .orEmpty()
                        .isAdaptivePlaybackManifest()
                ) {
                    applyTrackSelectionCeiling(qualityCeiling)
                } else {
                    applyTrackSelectionCeiling(PlaybackQuality.Original)
                }
                _state.update {
                    it.copy(
                        currentIndex = index,
                        transcoding = transcoding,
                        videoHeight = item?.sourceVideoHeight(transcoding) ?: 0,
                        fallbacksExhausted = false,
                        automaticFallbackBlocked = false,
                        positionMs = 0L,
                        durationMs = knownDuration(),
                        bufferedPositionMs = 0L,
                        error = null,
                        ended = false,
                        diagnostics =
                            initialPlaybackDiagnostics(
                                engine = "Media3 / ExoPlayer",
                                decoder = it.diagnostics.decoder,
                                item = item,
                                quality = qualityCeiling,
                                transcoding = transcoding,
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
                val httpCause =
                    generateSequence(error as Throwable) { it.cause }
                        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                        .firstOrNull()
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
                            failedUrl?.let { url ->
                                put("requestUrl", sanitizePlaybackUrl(url))
                                playbackQueryParameter(url, "MediaSourceId")?.let {
                                    put("mediaSourceId", it)
                                }
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
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                        if (
                            !scheduleRetry(index, MANIFEST_RETRY_LIMIT, "malformed_manifest") &&
                            !switchToProgressiveTranscode()
                        ) {
                            failPlayback("服务器返回了无效的转码清单", kind = PlaybackFailureKind.Container)
                        }

                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    ->
                        if (!switchToTranscode()) {
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
                ),
        )
        player.addListener(listener)
        player.addAnalyticsListener(analyticsListener)
        player.setVideoFrameMetadataListener(videoFrameMetadataListener)
        val initialItem = items.getOrNull(startIndex)
        if (
            startIndex !in transcodedIndices &&
            initialItem?.url?.isAdaptivePlaybackManifest() == true
        ) {
            applyTrackSelectionCeiling(quality)
        }
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
                            durationMs = knownDuration(),
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

    override fun setQualityCeiling(quality: PlaybackQuality): Boolean {
        val uri =
            player.currentMediaItem
                ?.localConfiguration
                ?.uri
                ?.toString()
                .orEmpty()
        if (_state.value.transcoding || !uri.isAdaptivePlaybackManifest()) return false
        qualityCeiling = quality
        applyTrackSelectionCeiling(quality)
        _state.update {
            it.copy(diagnostics = it.diagnostics.copy(requestedQuality = quality.label))
        }
        return true
    }

    private fun applyTrackSelectionCeiling(quality: PlaybackQuality) {
        val builder = trackSelector.buildUponParameters()
        val maxWidth = quality.maxWidth
        val maxBitrate = quality.videoBitrate
        if (maxWidth == null || maxBitrate == null) {
            builder.clearVideoSizeConstraints().setMaxVideoBitrate(Int.MAX_VALUE)
        } else {
            builder
                // The quality model stores the longest display edge. A square constraint keeps
                // portrait ladders eligible while still capping their effective resolution.
                .setMaxVideoSize(maxWidth, maxWidth)
                .setMaxVideoBitrate(maxBitrate)
        }
        trackSelector.parameters = builder.build()
    }

    override fun selectAudioTrack(id: String) = select(C.TRACK_TYPE_AUDIO, id)

    override fun selectSubtitleTrack(id: String) = select(C.TRACK_TYPE_TEXT, id)

    override val supportsSecondarySubtitleTrack: Boolean = true

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
        _state.update {
            it.copy(
                error = null,
                buffering = true,
                bufferedPositionMs = 0L,
                ended = false,
                transcoding = index in transcodedIndices,
                fallbacksExhausted = false,
            )
        }
        player.seekToDefaultPosition(index)
        player.play()
    }

    override fun currentPositionMs(): Long = player.currentPosition

    override fun retry() {
        _state.update {
            it.copy(
                error = null,
                buffering = true,
                bufferedPositionMs = it.positionMs,
                ended = false,
                automaticFallbackBlocked = false,
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
            AppLog.warning(
                category = "player.exo",
                event = "unsupported_audio_track_selected",
                message = "Selected audio track is unsupported; trying another playback engine",
                attributes =
                    mapOf(
                        "itemIndex" to player.currentMediaItemIndex.toString(),
                        "trackId" to id,
                        "sampleMimeType" to format.sampleMimeType.orEmpty(),
                        "codecs" to format.codecs.orEmpty(),
                    ),
            )
            player.pause()
            failPlayback(
                "当前音轨不受 ExoPlayer 支持，正在尝试其他播放器",
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
                        "当前视频无法解码，正在尝试其他播放器",
                        kind = PlaybackFailureKind.Decoder,
                    )
                }
        }
    }

    override fun switchToTranscode(reason: String?): Boolean {
        val index = player.currentMediaItemIndex
        if (index in transcodedIndices) return switchToProgressiveTranscode()
        val item = items.getOrNull(index) ?: return false
        if (item.transcodeUrl.isEmpty()) return switchToProgressiveTranscode()
        transcodedIndices += index
        val position = player.currentPosition
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
                        fallbackReason = reason ?: "直放失败，已切换服务器转码",
                        bufferedDurationMs = 0L,
                    ),
            )
        }
        safeLogcat(Log.INFO, TAG, "falling back to transcode for index=$index")
        AppLog.info(
            category = "player.exo",
            event = "transcode_fallback",
            message = "Switching from direct play to server transcode",
            attributes = mapOf("itemIndex" to index.toString()),
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
        val qualityItems = items.map { it.withPlaybackQuality(quality) }
        val offset = this.items.size
        qualityItems.forEachIndexed { relativeIndex, item ->
            if (item.startsWithServerTranscode(quality)) transcodedIndices += offset + relativeIndex
        }
        this.items += qualityItems
        val mediaItems =
            qualityItems.mapIndexed { relativeIndex, item ->
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
        if (item.fallbackTranscodeUrl.isEmpty()) return false
        transcodedIndices += index
        progressiveTransitionIndices += index
        val position = player.currentPosition
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
                        fallbackReason = "HLS 转码不可用，已改用 MP4 转码",
                    ),
            )
        }
        player.stop()
        safeLogcat(Log.INFO, TAG, "cleaning HLS encoder before progressive fallback for index=$index")
        AppLog.info(
            category = "player.exo",
            event = "progressive_transcode_cleanup",
            message = "Stopping the HLS encoder before progressive transcode fallback",
            attributes = mapOf("itemIndex" to index.toString()),
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
                    attributes = mapOf("itemIndex" to index.toString()),
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
        _state.update { it.copy(error = null, buffering = true) }
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

    private fun knownDuration(): Long = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
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

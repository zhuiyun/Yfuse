package com.yfuse.feature.player

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.hls.HlsTrackMetadataEntry
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.safeLogcat
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.PlaybackMethod
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

private const val TAG = "YfusePlayer"

/** How often the position is sampled; ExoPlayer has no position callback. */
private const val TICK_MS = 500L
private const val TRANSIENT_RETRY_LIMIT = 2
private const val MANIFEST_RETRY_LIMIT = 1

internal enum class UnsupportedMediaTrack { Audio, Video }

internal fun unsupportedMediaTrack(
    hasVideo: Boolean,
    videoSupported: Boolean,
    hasAudio: Boolean,
    audioSupported: Boolean,
): UnsupportedMediaTrack? = when {
    hasAudio && !audioSupported -> UnsupportedMediaTrack.Audio
    hasVideo && !videoSupported -> UnsupportedMediaTrack.Video
    else -> null
}

/** A track before repeated HLS rendition declarations have been collapsed. */
internal data class ManifestTrackCandidate(
    val id: String,
    val label: String,
    val language: String?,
    val selected: Boolean,
    /** EXT-X-MEDIA identity; null for direct files and manifests without rendition metadata. */
    val manifestGroupId: String?,
    val manifestName: String?,
    /** Codec/channel hint used only when two genuine tracks would otherwise look identical. */
    val qualifier: String? = null,
    val codec: String? = null,
)

/**
 * Collapse only tracks proven to be repeated declarations of one HLS rendition.
 *
 * Language and display label are not identities: two real commentary/main audio tracks, or
 * simplified/traditional subtitles, often share both. HLS gives every EXT-X-MEDIA rendition
 * a stable `(GROUP-ID, NAME)` pair, so only that pair is safe to merge. When the manifest
 * omits it, preserving an apparent duplicate is preferable to making a real track unreachable.
 */
internal fun collapseManifestTrackDuplicates(
    candidates: List<ManifestTrackCandidate>,
): List<EngineTrack> {
    val collapsed = mutableListOf<ManifestTrackCandidate>()
    val renditionIndices = mutableMapOf<Pair<String, String>, Int>()
    candidates.forEach { candidate ->
        val group = candidate.manifestGroupId?.takeIf { it.isNotBlank() }
        val name = candidate.manifestName?.takeIf { it.isNotBlank() }
        val rendition = if (group != null && name != null) group to name else null
        val existingIndex = rendition?.let(renditionIndices::get)
        if (existingIndex == null) {
            rendition?.let { renditionIndices[it] = collapsed.size }
            collapsed += candidate
        } else {
            val existing = collapsed[existingIndex]
            if (candidate.selected && !existing.selected) {
                // The row keeps whichever concrete group Exo currently selected, so its tick
                // and a subsequent selection request both address the active rendition.
                collapsed[existingIndex] = existing.copy(id = candidate.id, selected = true)
            }
        }
    }

    val labelCounts = collapsed.groupingBy { it.label }.eachCount()
    val labelOrdinals = mutableMapOf<String, Int>()
    val uniqueQualifiers = collapsed.groupBy { it.label }.mapValues { (_, group) ->
        val qualifiers = group.mapNotNull { it.qualifier?.takeIf(String::isNotBlank) }
        qualifiers.size == group.size && qualifiers.distinct().size == group.size
    }
    return collapsed.map { candidate ->
        val repeatedLabel = (labelCounts[candidate.label] ?: 0) > 1
        val label = if (!repeatedLabel) {
            candidate.label
        } else if (uniqueQualifiers[candidate.label] == true) {
            "${candidate.label} · ${candidate.qualifier}"
        } else {
            val ordinal = (labelOrdinals[candidate.label] ?: 0) + 1
            labelOrdinals[candidate.label] = ordinal
            "${candidate.label} $ordinal"
        }
        EngineTrack(
            id = candidate.id,
            label = label,
            language = candidate.language,
            selected = candidate.selected,
            codec = candidate.codec,
        )
    }
}

@UnstableApi
private fun Format.hlsRenditionIdentity(): Pair<String, String>? {
    val entries = metadata ?: return null
    for (index in 0 until entries.length()) {
        val rendition = entries[index] as? HlsTrackMetadataEntry ?: continue
        val group = rendition.groupId?.takeIf { it.isNotBlank() } ?: continue
        val name = rendition.name?.takeIf { it.isNotBlank() } ?: continue
        return group to name
    }
    return null
}

private fun Format.trackQualifier(type: Int): String? {
    val codec = codecs
        ?.substringBefore(',')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.uppercase()
        ?: sampleMimeType
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?.uppercase()
    return when (type) {
        C.TRACK_TYPE_AUDIO -> listOfNotNull(
            codec,
            channelCount.takeIf { it > 0 }?.let { "$it 声道" },
        ).joinToString(" · ").takeIf(String::isNotBlank)
        C.TRACK_TYPE_TEXT -> codec
        else -> codec
    }
}

/**
 * ExoPlayer behind the engine-agnostic [VideoEngine] contract.
 *
 * [player] stays public because the picture still needs a `PlayerView` to
 * render into; everything the controls touch goes through the interface.
 *
 * All calls must happen on the thread that built the player (the main thread),
 * which is where [scope] dispatches.
 */
@UnstableApi
class ExoVideoEngine(
    context: Context,
    items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    private val scope: CoroutineScope,
    decoderMode: DecoderMode,
    autoNext: Boolean,
    quality: PlaybackQuality,
    customUserAgent: String,
    videoCacheBytes: Long,
    private val stopEncoding: suspend (String) -> Boolean = { true },
) : VideoEngine {

    private val _state = MutableStateFlow(
        PlaybackState(
            currentIndex = startIndex,
            itemCount = items.size.coerceAtLeast(1),
            diagnostics = PlaybackDiagnostics(
                engine = "Media3 / ExoPlayer",
                decoder = decoderMode.label,
                playMethod = items.getOrNull(startIndex)?.playMethod?.label
                    ?: PlaybackMethod.DirectPlay.label,
            ),
        ),
    )
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Grows via [appendItems]; index-keyed state below stays valid because entries only
     *  ever arrive at the end. */
    private val items = items.toMutableList()

    private val transcodedIndices = items.mapIndexedNotNullTo(mutableSetOf()) { index, item ->
        index.takeIf { item.playMethod == PlaybackMethod.Transcode }
    }
    private val progressiveTranscodeIndices = mutableSetOf<Int>()
    private val progressiveTransitionIndices = mutableSetOf<Int>()
    private val retryCounts = mutableMapOf<String, Int>()
    private var retryJob: Job? = null
    private var fallbackJob: Job? = null
    private var released = false
    private val cacheHandle = VideoCachePool.acquire(context.applicationContext, videoCacheBytes)

    val player: ExoPlayer = run {
        // Emby 302-redirects stream requests to a CDN, often http -> https,
        // which ExoPlayer refuses unless cross-protocol redirects are allowed.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)
            .apply {
                customUserAgent.trim().takeIf { it.isNotEmpty() }?.let { value ->
                    setDefaultRequestProperties(mapOf("User-Agent" to value))
                }
            }

        val selector = if (decoderMode == DecoderMode.Software) {
            MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val decoders = MediaCodecUtil.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder,
                )
                decoders.filter { it.softwareOnly }.ifEmpty { decoders }
            }
        } else {
            MediaCodecSelector.DEFAULT
        }
        val renderersFactory = DefaultRenderersFactory(context)
            .setMediaCodecSelector(selector)
            .setEnableDecoderFallback(decoderMode != DecoderMode.Hardware)

        val upstream = DefaultDataSource.Factory(context, httpFactory)
        val dataSourceFactory = cacheHandle?.let { handle ->
            CacheDataSource.Factory()
                .setCache(handle.cache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } ?: upstream
        val loadControl = DefaultLoadControl.Builder()
            // Keep enough media ahead to ride through ordinary Wi-Fi/reverse-proxy jitter.
            // Time wins over the default byte target so high-bitrate remuxes are not starved.
            .setBufferDurationsMs(
                30_000,
                120_000,
                1_500,
                3_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(15_000, true)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            // Declare what this output is, so the system routes and mixes it as a film
            // rather than as the unspecified default. Focus itself is claimed once for the
            // whole player (see PlayerActivity) because the other two engines can't ask
            // ExoPlayer to do it for them — hence `handleAudioFocus = false` here.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ false,
            )
            .build()
            .apply {
                // Preserve the stream's native display aspect ratio. PlayerView uses
                // FIT by default, so no axis is stretched and no picture is cropped.
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }
    }

    override val playbackRequested: Boolean
        get() = player.playWhenReady && player.playbackState != Player.STATE_ENDED

    private var wasBuffering = true
    private var droppedFrames = 0

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            _state.update {
                it.copy(diagnostics = it.diagnostics.copy(decoder = decoderName))
            }
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            _state.update {
                it.copy(
                    diagnostics = it.diagnostics.copy(
                        videoCodec = format.codecs ?: format.sampleMimeType ?: "未知",
                        bitrateBitsPerSecond = format.bitrate.takeIf { value -> value > 0 }
                            ?.toLong() ?: it.diagnostics.bitrateBitsPerSecond,
                        frameRate = format.frameRate.takeIf { value -> value > 0f }
                            ?: it.diagnostics.frameRate,
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
                    diagnostics = it.diagnostics.copy(
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
                    diagnostics = it.diagnostics.copy(
                        networkBitsPerSecond = bitrateEstimate.coerceAtLeast(0L),
                    ),
                )
            }
        }
    }

    private val listener = object : Player.Listener {
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
                    diagnostics = it.diagnostics.copy(
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

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val index = player.currentMediaItemIndex
            _state.update {
                it.copy(
                    currentIndex = index,
                    transcoding = index in transcodedIndices,
                    fallbacksExhausted = false,
                    automaticFallbackBlocked = false,
                    positionMs = 0L,
                    durationMs = knownDuration(),
                    bufferedPositionMs = 0L,
                    error = null,
                    ended = false,
                    diagnostics = it.diagnostics.copy(
                        playMethod = if (index in transcodedIndices) {
                            PlaybackMethod.Transcode.label
                        } else {
                            items.getOrNull(index)?.playMethod?.label ?: PlaybackMethod.DirectPlay.label
                        },
                        bufferedDurationMs = 0L,
                    ),
                )
            }
        }

        override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
            _state.update { it.copy(speed = parameters.speed) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.update { it.copy(videoHeight = videoSize.height) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            syncTracks()
            fallbackForUnsupportedTracks(tracks, includeMissingExpectedAudio = false)
        }

        override fun onPlayerError(error: PlaybackException) {
            safeLogcat(Log.ERROR, TAG, "playback failed: ${error.errorCodeName}", error)
            val index = player.currentMediaItemIndex
            val httpCause = generateSequence(error as Throwable) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()
            val failedUrl = httpCause?.dataSpec?.uri?.toString()
            AppLog.error(
                category = "player.exo",
                event = "playback_failed",
                message = "ExoPlayer playback failed",
                throwable = error,
                attributes = buildMap {
                    put("errorCode", error.errorCodeName)
                    put("itemIndex", index.toString())
                    put("transcoding", _state.value.transcoding.toString())
                    // Which of the three addresses failed, and — the question the previous
                    // diagnostic bundles could not answer — what the server actually said.
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
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                -> if (
                    !scheduleRetry(index, MANIFEST_RETRY_LIMIT, "malformed_manifest") &&
                    !switchToProgressiveTranscode()
                ) {
                    _state.update {
                        it.copy(
                            error = "服务器返回了无效的转码清单",
                            buffering = false,
                            fallbacksExhausted = true,
                        )
                    }
                }

                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                -> if (!switchToTranscode()) {
                    _state.update {
                        it.copy(
                            error = "当前视频无法解码，且服务器未提供可用转码流",
                            buffering = false,
                            fallbacksExhausted = true,
                        )
                    }
                }

                // The server answered, and refused. This used to fall through to the generic
                // branch: no fallback attempted, and `fallbacksExhausted` left false, so the
                // controls still offered a retry that could only fail the same way. Walking
                // the chain matters most here — a rejected HLS manifest request should still
                // get the progressive attempt rather than stopping at the first refusal.
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                -> if (blocksAutomaticPlaybackFallback(httpCause?.responseCode)) {
                    _state.update {
                        it.copy(
                            error = httpFailureMessage(
                                httpCause?.responseCode,
                                httpCause?.responseBody?.toString(Charsets.UTF_8),
                            ),
                            buffering = false,
                            fallbacksExhausted = true,
                            automaticFallbackBlocked = true,
                        )
                    }
                } else if (!advanceFallback()) {
                    _state.update {
                        it.copy(
                            error = httpFailureMessage(
                                httpCause?.responseCode,
                                httpCause?.responseBody?.toString(Charsets.UTF_8),
                            ),
                            buffering = false,
                            fallbacksExhausted = true,
                        )
                    }
                }

                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                -> if (
                    !scheduleRetry(index, TRANSIENT_RETRY_LIMIT, "transient_network") &&
                    !advanceFallback()
                ) {
                    _state.update {
                        it.copy(
                            error = "网络连接多次失败，已尝试所有播放方式",
                            buffering = false,
                            fallbacksExhausted = true,
                        )
                    }
                }

                else -> _state.update {
                    // Unknown fatal errors still get the bounded engine/version recovery in
                    // PlayerRoot. Leaving this false stranded the user on Exo even though two
                    // independent decoder stacks and possibly another file were available.
                    it.copy(
                        error = "播放失败：${error.errorCodeName}",
                        buffering = false,
                        fallbacksExhausted = true,
                    )
                }
            }
        }
    }

    private var ticker: Job? = null

    init {
        player.addListener(listener)
        player.addAnalyticsListener(analyticsListener)
        player.setMediaItems(
            items.mapIndexed { index, item ->
                mediaItem(
                    url = if (index in transcodedIndices) item.transcodeUrl else item.url,
                    title = item.title,
                )
            },
            startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            startPositionMs,
        )
        player.pauseAtEndOfMediaItems = !autoNext
        _state.update { it.copy(transcoding = startIndex in transcodedIndices) }
        player.playWhenReady = true
        player.prepare()

        ticker = scope.launch {
            while (isActive) {
                _state.update {
                    val bufferedDurationMs = player.totalBufferedDuration.coerceAtLeast(0L)
                    it.copy(
                        positionMs = player.currentPosition,
                        durationMs = knownDuration(),
                        bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
                        diagnostics = it.diagnostics.copy(
                            bufferedDurationMs = bufferedDurationMs,
                        ),
                    )
                }
                delay(TICK_MS)
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

    override fun selectAudioTrack(id: String) = select(C.TRACK_TYPE_AUDIO, id)

    override fun selectSubtitleTrack(id: String) = select(C.TRACK_TYPE_TEXT, id)

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
        player.removeListener(listener)
        player.removeAnalyticsListener(analyticsListener)
        player.release()
        cacheHandle?.close()
    }

    /**
     * Track ids are `"<groupIndex>:<trackIndex>"` into [Player.getCurrentTracks],
     * which is what [TrackSelectionOverride] needs to address a track.
     */
    private fun select(type: Int, id: String) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (id == EngineTrack.OFF) {
            player.trackSelectionParameters = builder
                .clearOverridesOfType(type)
                .setTrackTypeDisabled(type, true)
                .build()
            syncTracks()
            return
        }

        val groupIndex = id.substringBefore(':').toIntOrNull() ?: return
        val trackIndex = id.substringAfter(':').toIntOrNull() ?: return
        val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return

        // Media3 exposes unsupported tracks in the picker too. Forcing one can leave the
        // video running with no audio and no PlayerException, so move to the AAC fallback
        // while the user's requested language is still the active choice.
        if (type == C.TRACK_TYPE_AUDIO && !group.isTrackSupported(trackIndex)) {
            AppLog.warning(
                category = "player.exo",
                event = "unsupported_audio_track_selected",
                message = "Selected audio track is unsupported; attempting server transcode",
                attributes = mapOf(
                    "itemIndex" to player.currentMediaItemIndex.toString(),
                    "trackId" to id,
                ),
            )
            if (!switchToTranscode()) {
                _state.update {
                    it.copy(error = "当前音轨无法解码，且服务器未提供可用转码流")
                }
            }
            return
        }

        player.trackSelectionParameters = builder
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
        syncTracks()
    }

    private fun syncTracks() {
        _state.update {
            it.copy(
                audioTracks = tracksOf(C.TRACK_TYPE_AUDIO, "音轨"),
                subtitleTracks = tracksOf(C.TRACK_TYPE_TEXT, "字幕"),
            )
        }
    }

    private fun fallbackForUnsupportedTracks(
        tracks: Tracks,
        includeMissingExpectedAudio: Boolean,
    ) {
        val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val videoSupported = videoGroups.any { group ->
            (0 until group.length).any { group.isTrackSupported(it) }
        }
        val audioSupported = audioGroups.any { group ->
            (0 until group.length).any { group.isTrackSupported(it) }
        }
        val index = player.currentMediaItemIndex
        val expectedAudio = items.getOrNull(index)?.activeVersion?.audioTrackCount
            ?.let { it > 0 } == true
        val unsupported = unsupportedMediaTrack(
            hasVideo = videoGroups.isNotEmpty(),
            videoSupported = videoSupported,
            hasAudio = audioGroups.isNotEmpty() ||
                (includeMissingExpectedAudio && expectedAudio),
            audioSupported = audioSupported,
        ) ?: return

        // A rejected audio decoder does not necessarily fail playback: ExoPlayer can keep
        // rendering the picture with no selected audio track. Treat it exactly like an
        // unsupported picture and ask Emby for the H.264/AAC fallback. Guard the current
        // index so a final callback from the old stream cannot spend the next fallback.
        if (index in transcodedIndices) return
        val type = unsupported.name.lowercase()
        safeLogcat(Log.WARN, TAG, "no supported $type track; switching to transcode")
        AppLog.warning(
            category = "player.exo",
            event = "unsupported_${type}_tracks",
            message = "No supported $type track; attempting server transcode",
            attributes = mapOf(
                "itemIndex" to index.toString(),
                "missingExpectedAudio" to
                    (expectedAudio && audioGroups.isEmpty()).toString(),
            ),
        )
        switchToTranscode()
    }

    /**
     * The pickable tracks of one type, with proven HLS rendition repetitions collapsed.
     *
     * An HLS manifest — which is what the server's transcode serves — declares its audio and
     * subtitle renditions once per variant stream. ExoPlayer faithfully reports one track
     * group per variant, so a file with a single 国语 track arrived here as five identical
     * 国语 entries and the picker listed all of them. Labels and languages are not unique,
     * though: commentary/main tracks and regional subtitle variants often share both. The
     * manifest's EXT-X-MEDIA `(GROUP-ID, NAME)` pair is therefore the only deduplication key.
     *
     * A duplicate row carries the selection if any concrete Exo group is selected. Genuine
     * same-label tracks are preserved and receive a codec/channel qualifier or an ordinal.
     */
    private fun tracksOf(type: Int, fallbackPrefix: String): List<EngineTrack> {
        var ordinal = 0
        val candidates = mutableListOf<ManifestTrackCandidate>()
        player.currentTracks.groups.withIndex()
            .filter { (_, group) -> group.type == type }
            .forEach { (groupIndex, group) ->
                (0 until group.length).forEach { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    ordinal++
                    val rendition = format.hlsRenditionIdentity()
                    candidates += ManifestTrackCandidate(
                        id = "$groupIndex:$trackIndex",
                        label = format.label
                            ?: rendition?.second
                            ?: format.language
                            ?: "$fallbackPrefix $ordinal",
                        language = format.language,
                        selected = group.isTrackSelected(trackIndex),
                        manifestGroupId = rendition?.first,
                        manifestName = rendition?.second,
                        qualifier = format.trackQualifier(type),
                        codec = format.sampleMimeType?.substringAfterLast('/') ?: format.codecs,
                    )
                }
            }
        return collapseManifestTrackDuplicates(candidates)
    }

    /**
     * Steps the current entry one place down the fallback chain: original file, then the
     * server's HLS transcode, then its progressive MP4.
     *
     * Returning true for an entry that was *already* transcoding used to hide the end of
     * the chain — a decode failure on the transcoded stream reported success, so no error
     * was ever shown and playback simply stopped. One step per call, false when spent.
     */
    override fun switchToTranscode(): Boolean {
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
                diagnostics = it.diagnostics.copy(
                    playMethod = "服务器转码",
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
        player.replaceMediaItem(index, mediaItem(item.transcodeUrl, item.title))
        player.prepare()
        player.seekTo(index, position)
        player.playWhenReady = true
        return true
    }

    /**
     * ExoPlayer can extend a live playlist, so a newly published episode costs nothing.
     * Appending only at the tail is what keeps [transcodedIndices] and the rest of the
     * index-keyed state meaningful.
     */
    override fun appendItems(items: List<PlayerMediaItem>): Boolean {
        if (items.isEmpty()) return true
        this.items += items
        player.addMediaItems(items.map { mediaItem(it.url, it.title) })
        _state.update { it.copy(itemCount = this.items.size.coerceAtLeast(1)) }
        AppLog.info(
            category = "player.exo",
            event = "queue_extended",
            message = "Queue extended without restarting playback",
            attributes = mapOf(
                "addedCount" to items.size.toString(),
                "itemCount" to this.items.size.toString(),
            ),
        )
        return true
    }

    /** Some Emby proxies cannot serve master.m3u8; retry the same item as MP4. */
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
            it.copy(error = null, buffering = true, bufferedPositionMs = position)
        }
        // Stop reading HLS before deleting its encoder. Starting MP4 first can briefly leave
        // two ffmpeg jobs under one session; one-slot servers reject the second with HTTP 400.
        player.stop()
        safeLogcat(Log.INFO, TAG, "cleaning HLS encoder before progressive fallback for index=$index")
        AppLog.info(
            category = "player.exo",
            event = "progressive_transcode_cleanup",
            message = "Stopping the HLS encoder before progressive transcode fallback",
            attributes = mapOf("itemIndex" to index.toString()),
        )
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            val cleaned = item.playSessionId.isBlank() ||
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
                _state.update {
                    it.copy(
                        error = "无法清理旧的服务器转码，正在尝试其他播放器",
                        buffering = false,
                        fallbacksExhausted = true,
                    )
                }
                return@launch
            }
            progressiveTranscodeIndices += index
            AppLog.info(
                category = "player.exo",
                event = "progressive_transcode_fallback",
                message = "Switching from HLS to progressive transcode",
                attributes = mapOf("itemIndex" to index.toString()),
            )
            player.replaceMediaItem(index, mediaItem(item.fallbackTranscodeUrl, item.title))
            player.prepare()
            player.seekTo(index, position)
            player.playWhenReady = true
        }
        return true
    }

    /**
     * The next rung down the ladder: direct play → HLS transcode → progressive transcode.
     *
     * Both switch functions already refuse to repeat a rung they have spent on this entry,
     * so calling them in order is enough to find the next untried one — or to report that
     * there is none, which is what the caller needs to stop offering a pointless retry.
     */
    private fun advanceFallback(): Boolean =
        switchToTranscode() || switchToProgressiveTranscode()

    /** A bounded retry for failures that are commonly one bad proxy connection or startup read. */
    private fun scheduleRetry(index: Int, limit: Int, reason: String): Boolean {
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
            attributes = mapOf(
                "itemIndex" to index.toString(),
                "streamVariant" to streamVariantOf(index),
                "attempt" to nextAttempt.toString(),
                "limit" to limit.toString(),
                "reason" to reason,
            ),
        )
        retryJob = scope.launch {
            delay(if (nextAttempt == 1) 500L else 1_500L)
            if (released || player.currentMediaItemIndex != index) return@launch
            player.prepare()
            player.playWhenReady = true
        }
        return true
    }

    private fun retryKey(index: Int): String = "$index:${streamVariantOf(index)}"

    /** Which address the entry is currently being played from, for the diagnostic log. */
    private fun streamVariantOf(index: Int): String = when {
        index in progressiveTranscodeIndices -> "progressive"
        index in transcodedIndices -> "hls"
        else -> "direct"
    }

    private fun httpFailureMessage(status: Int?, body: String?): String = when (status) {
        401 -> "服务器登录已失效（401），请重新登录该服务器"
        403 -> if (body.isAccessBlockPage()) {
            "服务器入口或 Cloudflare 拒绝了当前网络访问（403），重新登录通常无效"
        } else {
            "当前账号没有播放权限，或服务器入口拒绝了访问（403）"
        }
        400 -> "服务器无法处理当前版本的转码请求（400），正在尝试其他播放方式"
        404 -> "服务器上找不到这个文件（404）"
        // What an Emby server returns once its transcoding slots are all taken, which is the
        // state a leaked encoding leaves it in.
        429, 503 -> "服务器暂时无法提供转码（$status），请稍后再试"
        null -> "服务器拒绝了播放请求"
        else -> "服务器拒绝了播放请求（$status）"
    }

    private fun knownDuration(): Long =
        player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L

}

/** Keeps a failing address useful in diagnostics without exporting the user's server token. */
internal fun sanitizePlaybackUrl(value: String): String {
    val querySafe = value.replace(
        Regex("(?i)(api_key|x-emby-token)=([^&\\s]+)"),
    ) { match -> "${match.groupValues[1]}=<redacted>" }
    return querySafe.replace(
        Regex("(?i)(\"?(?:api_key|x-emby-token)\"?\\s*:\\s*\")([^\"]+)(\")"),
    ) { match -> "${match.groupValues[1]}<redacted>${match.groupValues[3]}" }
}

private fun String?.isAccessBlockPage(): Boolean {
    val value = this?.lowercase().orEmpty()
    return "cloudflare" in value ||
        "sorry, you have been blocked" in value ||
        "access denied" in value ||
        "attention required" in value
}

internal fun playbackQueryParameter(url: String, name: String): String? =
    Regex("(?:[?&])${Regex.escape(name)}=([^&]+)", RegexOption.IGNORE_CASE)
        .find(url)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf(String::isNotBlank)

/** An account or edge-policy rejection applies to every URL/engine for this server. */
internal fun blocksAutomaticPlaybackFallback(httpStatus: Int?): Boolean =
    httpStatus == 401 || httpStatus == 403

private fun mediaItem(url: String, title: String): MediaItem =
    MediaItem.Builder()
        .setUri(url)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .build()

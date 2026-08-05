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
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "YfusePlayer"

/** How often the position is sampled; ExoPlayer has no position callback. */
private const val TICK_MS = 500L

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
    private val items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    private val scope: CoroutineScope,
    decoderMode: DecoderMode,
    autoNext: Boolean,
    quality: PlaybackQuality,
    customUserAgent: String,
    videoCacheBytes: Long,
) : VideoEngine {

    private val _state = MutableStateFlow(
        PlaybackState(
            currentIndex = startIndex,
            itemCount = items.size.coerceAtLeast(1),
            diagnostics = PlaybackDiagnostics(
                engine = "Media3 / ExoPlayer",
                decoder = decoderMode.label,
            ),
        ),
    )
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val transcodedIndices = mutableSetOf<Int>()
    private val progressiveTranscodeIndices = mutableSetOf<Int>()
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
            Log.i(TAG, "exo state=$state")
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
                    positionMs = 0L,
                    durationMs = knownDuration(),
                    error = null,
                    ended = false,
                    diagnostics = it.diagnostics.copy(
                        playMethod = if (index in transcodedIndices) "服务器转码" else "直播放",
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
            Log.e(TAG, "playback failed: ${error.errorCodeName}", error)
            AppLog.error(
                category = "player.exo",
                event = "playback_failed",
                message = "ExoPlayer playback failed",
                throwable = error,
                attributes = mapOf(
                    "errorCode" to error.errorCodeName,
                    "itemIndex" to player.currentMediaItemIndex.toString(),
                    "transcoding" to _state.value.transcoding.toString(),
                ),
            )
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                -> if (!switchToProgressiveTranscode()) {
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

                else -> _state.update {
                    it.copy(error = "播放失败：${error.errorCodeName}", buffering = false)
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
                    it.copy(
                        positionMs = player.currentPosition,
                        durationMs = knownDuration(),
                        diagnostics = it.diagnostics.copy(
                            bufferedDurationMs = player.totalBufferedDuration.coerceAtLeast(0L),
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
        _state.update { it.copy(positionMs = positionMs, ended = false) }
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
        _state.update { it.copy(error = null, buffering = true, ended = false) }
        player.prepare()
        player.playWhenReady = true
    }

    override fun release() {
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
        Log.w(TAG, "no supported $type track; switching to transcode")
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

    private fun tracksOf(type: Int, fallbackPrefix: String): List<EngineTrack> {
        var ordinal = 0
        return player.currentTracks.groups.withIndex()
            .filter { (_, group) -> group.type == type }
            .flatMap { (groupIndex, group) ->
                (0 until group.length).map { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    ordinal++
                    EngineTrack(
                        id = "$groupIndex:$trackIndex",
                        label = format.label ?: format.language ?: "$fallbackPrefix $ordinal",
                        language = format.language,
                        selected = group.isTrackSelected(trackIndex),
                    )
                }
            }
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
                transcoding = true,
                diagnostics = it.diagnostics.copy(playMethod = "服务器转码"),
            )
        }
        Log.i(TAG, "falling back to transcode for index=$index")
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

    /** Some Emby proxies cannot serve master.m3u8; retry the same item as MP4. */
    private fun switchToProgressiveTranscode(): Boolean {
        val index = player.currentMediaItemIndex
        if (index in progressiveTranscodeIndices) return false
        val item = items.getOrNull(index) ?: return false
        if (item.fallbackTranscodeUrl.isEmpty()) return false
        transcodedIndices += index
        progressiveTranscodeIndices += index
        val position = player.currentPosition
        _state.update { it.copy(error = null, buffering = true) }
        Log.i(TAG, "HLS manifest invalid; falling back to progressive transcode for index=$index")
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
        return true
    }

    private fun knownDuration(): Long =
        player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L

}

private fun mediaItem(url: String, title: String): MediaItem =
    MediaItem.Builder()
        .setUri(url)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .build()

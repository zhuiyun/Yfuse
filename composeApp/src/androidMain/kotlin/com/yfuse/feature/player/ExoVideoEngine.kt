package com.yfuse.feature.player

import android.content.Context
import android.util.Log
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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

    /** True when the current queue entry is using the server-transcoded stream. */
    private val _transcoding = MutableStateFlow(false)
    val transcoding: StateFlow<Boolean> = _transcoding.asStateFlow()
    private val transcodedIndices = mutableSetOf<Int>()
    private val progressiveTranscodeIndices = mutableSetOf<Int>()

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

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory)))
            .build()
            .apply {
                // Preserve the stream's native display aspect ratio. PlayerView uses
                // FIT by default, so no axis is stretched and no picture is cropped.
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }
    }

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
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val index = player.currentMediaItemIndex
            _transcoding.value = index in transcodedIndices
            _state.update {
                it.copy(
                    currentIndex = index,
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
            val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            val anySupported = videoGroups.any { group ->
                (0 until group.length).any { group.isTrackSupported(it) }
            }
            // Video present but undecodable (e.g. Dolby Vision P5) -> transcode.
            if (videoGroups.isNotEmpty() && !anySupported) {
                Log.w(TAG, "no supported video track; switching to transcode")
                switchToTranscode()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "playback failed: ${error.errorCodeName}", error)
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                -> if (!switchToProgressiveTranscode()) {
                    _state.update {
                        it.copy(error = "服务器返回了无效的转码清单", buffering = false)
                    }
                }

                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                -> if (!switchToTranscode()) {
                    _state.update {
                        it.copy(error = "当前视频无法解码，且服务器未提供可用转码流", buffering = false)
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
        _transcoding.value = startIndex in transcodedIndices
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
        _transcoding.value = index in transcodedIndices
        _state.update { it.copy(error = null, buffering = true, ended = false) }
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

    /** Swaps the current entry for the server-transcoded HLS stream. */
    fun switchToTranscode(): Boolean {
        val index = player.currentMediaItemIndex
        if (index in transcodedIndices) return true
        val item = items.getOrNull(index) ?: return false
        if (item.transcodeUrl.isEmpty()) return false
        transcodedIndices += index
        _transcoding.value = true
        val position = player.currentPosition
        _state.update {
            it.copy(
                error = null,
                buffering = true,
                diagnostics = it.diagnostics.copy(playMethod = "服务器转码"),
            )
        }
        Log.i(TAG, "falling back to transcode for index=$index")
        player.replaceMediaItem(index, mediaItem(item.transcodeUrl, item.title))
        player.prepare()
        player.seekTo(index, position)
        player.playWhenReady = true
        return true
    }

    /** Some Emby proxies cannot serve master.m3u8; retry the same item as MP4. */
    private fun switchToProgressiveTranscode(): Boolean {
        val index = player.currentMediaItemIndex
        if (index !in transcodedIndices || index in progressiveTranscodeIndices) return false
        val item = items.getOrNull(index) ?: return false
        if (item.fallbackTranscodeUrl.isEmpty()) return false
        progressiveTranscodeIndices += index
        val position = player.currentPosition
        _state.update { it.copy(error = null, buffering = true) }
        Log.i(TAG, "HLS manifest invalid; falling back to progressive transcode for index=$index")
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

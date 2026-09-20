package com.yfuse.core2.android

import com.yfuse.core.playback.PlaybackMediaProbe
import com.yfuse.core.playback.PlaybackProbeDepth
import com.yfuse.core.playback.PlaybackProbeResult
import com.yfuse.core.playback.PlaybackProbeStatus
import com.yfuse.core.playback.toPlaybackAudioCodec
import com.yfuse.core.playback.toPlaybackVideoCodec
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.capability.YHdrType
import com.yfuse.feature.player.PlayerMediaItem

/** Owned by a single evaluator; authenticated source identity never enters telemetry. */
internal class AndroidPlaybackProbeFacts(
    val item: YMediaItem,
    val result: YCore2ProbeResult.Success,
) {
    fun matches(
        current: PlayerMediaItem,
        userAgent: String,
    ): Boolean =
        item.id == current.id &&
            item.providerKey == current.serverId &&
            item.playbackSessionId == current.playSessionId &&
            item.uri == current.url &&
            item.headers["User-Agent"].orEmpty() == userAgent.trim()

    fun presentation(baseline: PlaybackMediaProbe): PlaybackProbeResult {
        val request = result.playbackRequest
        val video = request.video
        val dolby = result.dolbyVisionConfig
        return PlaybackProbeResult(
            status = PlaybackProbeStatus.Complete,
            probe =
                baseline.copy(
                    source =
                        baseline.source.copy(
                            videoCodec = result.videoMime.toPlaybackVideoCodec(),
                            width = video.width.takeIf { it > 0 },
                            height = video.height.takeIf { it > 0 },
                            frameRate = video.frameRate.toDouble().takeIf { it > 0 },
                            bitDepth = video.bitDepth.takeIf { it > 0 },
                            dynamicRange = video.hdrType.name,
                            dolbyVision = video.hdrType == YHdrType.DolbyVision,
                            needsDolbyDecoder = video.hdrType == YHdrType.DolbyVision,
                            dolbyVisionProfile = dolby?.profile,
                            dolbyRpuPresent = dolby?.rpuPresent,
                            dolbyEnhancementLayerPresent = dolby?.enhancementLayerPresent,
                            dolbyBaseLayerPresent = dolby?.baseLayerPresent,
                            dolbyBaseLayerCompatibilityId = dolby?.baseLayerCompatibilityId,
                        ),
                    audioCodec = result.audioMime.toPlaybackAudioCodec(),
                    audioChannelCount = request.audio?.channelCount,
                    durationMs = result.durationMs.takeIf { it > 0 },
                    probeDepth =
                        if (request.platformDemuxSupported) {
                            PlaybackProbeDepth.PlatformExtractor
                        } else {
                            PlaybackProbeDepth.NativeFfmpeg
                        },
                ),
            detail = "复用内核媒体探测",
        )
    }

    override fun toString(): String = "AndroidPlaybackProbeFacts(<private source>)"
}

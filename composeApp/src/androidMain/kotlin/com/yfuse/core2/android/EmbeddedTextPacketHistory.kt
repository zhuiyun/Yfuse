package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxTrack
import com.yfuse.core2.demux.YTrackId
import com.yfuse.core2.subtitle.YEmbeddedSubtitleDecoder
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitleFormat

/** Text packets already read from a single interleaved file. A video keyframe can fall inside a caption. */
internal class EmbeddedTextPacketHistory(
    private val maxBytes: Int = 1024 * 1024,
    private val maxPackets: Int = 2048,
) {
    private val packets = ArrayDeque<YCompressedSample>()
    private var bytes = 0
    var trackIds = emptySet<YTrackId>()
        private set

    init {
        require(maxBytes > 0 && maxPackets > 0)
    }

    fun reset(source: YDemuxOpenResult? = null) {
        packets.clear()
        bytes = 0
        trackIds =
            source
                ?.takeIf { it.container in interleavedContainers }
                ?.tracks
                .orEmpty()
                .mapNotNull { track ->
                    track.id.takeIf { track.subtitle?.format in timedTextFormats }
                }.toSet()
    }

    fun remember(sample: YCompressedSample) {
        val size = sample.data.size
        if (
            sample.trackId !in trackIds ||
            sample.encrypted ||
            sample.endOfStream ||
            size !in 1..maxBytes ||
            (sample.durationUs ?: 0L) <= 0L ||
            sample.presentationTimeUs < 0L
        ) {
            return
        }
        while (packets.size >= maxPackets || bytes > maxBytes - size) {
            bytes -= packets.removeFirst().data.size
        }
        packets.addLast(sample)
        bytes += size
    }

    fun activeAt(
        trackId: YTrackId,
        positionUs: Long,
    ): List<YCompressedSample> =
        packets.filter { sample ->
            sample.trackId == trackId &&
                positionUs >= sample.presentationTimeUs &&
                positionUs - sample.presentationTimeUs < requireNotNull(sample.durationUs)
        }

    fun checkpoint(
        item: YMediaItem,
        tracks: List<YDemuxTrack>,
        positionUs: Long,
    ): SurfaceSubtitleCheckpoint? {
        val active = tracks.filter { it.id in trackIds }.associateWith { activeAt(it.id, positionUs) }
        return active.filterValues { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let {
            SurfaceSubtitleCheckpoint(item, it)
        }
    }

    private companion object {
        val interleavedContainers = setOf(YContainer.Matroska, YContainer.WebM, YContainer.Mp4, YContainer.Mov)
        val timedTextFormats = setOf(YSubtitleFormat.Srt, YSubtitleFormat.WebVtt, YSubtitleFormat.Tx3g)
    }
}

/** Holds only captions spanning the departure position, until this same playlist item gets a new Surface. */
internal class SurfaceSubtitleCheckpoint(
    private val item: YMediaItem,
    private val active: Map<YDemuxTrack, List<YCompressedSample>>,
) {
    fun restore(
        reopenedItem: YMediaItem?,
        tracks: List<YDemuxTrack>,
        history: EmbeddedTextPacketHistory,
    ) {
        if (item !== reopenedItem) return
        for ((previous, packets) in active) {
            val current = tracks.firstOrNull { it.id == previous.id } ?: continue
            if (current.subtitle?.format == previous.subtitle?.format &&
                current.subtitle?.mimeType == previous.subtitle?.mimeType &&
                current.language == previous.language &&
                current.label == previous.label
            ) {
                packets.forEach(history::remember)
            }
        }
    }
}

internal fun YCompressedSample.decodeTextCue(format: YSubtitleFormat): YSubtitleCue? =
    YEmbeddedSubtitleDecoder.decode(
        data,
        format,
        presentationTimeUs,
        durationUs,
        "${trackId.value}:$presentationTimeUs",
    )

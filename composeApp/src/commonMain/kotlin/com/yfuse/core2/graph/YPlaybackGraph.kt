package com.yfuse.core2.graph

import com.yfuse.core2.strategy.YPlaybackPlan

/** Small lifecycle surface shared by every node in a Core2 media graph. */
interface YPlaybackNode {
    val name: String

    fun flush() = Unit

    fun release()
}

interface YDemuxNode : YPlaybackNode

interface YVideoDecodeNode : YPlaybackNode

interface YAudioDecodeNode : YPlaybackNode

interface YVideoRenderNode : YPlaybackNode

interface YAudioRenderNode : YPlaybackNode

/**
 * Concrete graph selected for one playback session.
 *
 * Nodes are deliberately composed instead of hidden behind one giant engine object. This lets
 * Core2 pair an FFmpeg demuxer with MediaCodec, keep audio passthrough independent from video,
 * and replace only the failing node during a future route handover.
 */
data class YPlaybackGraph(
    val plan: YPlaybackPlan,
    val demux: YDemuxNode,
    val videoDecoder: YVideoDecodeNode?,
    val audioDecoder: YAudioDecodeNode?,
    val videoRenderer: YVideoRenderNode?,
    val audioRenderer: YAudioRenderNode?,
) {
    private val nodes: List<YPlaybackNode>
        get() =
            listOfNotNull(
                demux,
                videoDecoder,
                audioDecoder,
                videoRenderer,
                audioRenderer,
            ).fold(emptyList()) { unique, node ->
                if (unique.any { it === node }) unique else unique + node
            }

    fun flush() {
        nodes.asReversed().forEach(YPlaybackNode::flush)
    }

    fun release() {
        nodes.asReversed().forEach(YPlaybackNode::release)
    }
}

fun interface YPlaybackGraphFactory {
    fun create(plan: YPlaybackPlan): YPlaybackGraph
}

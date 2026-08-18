package com.yfuse.core2.graph

import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YRenderPath
import kotlin.test.Test
import kotlin.test.assertEquals

class YPlaybackGraphTest {
    @Test
    fun `flush and release visit every distinct node in reverse graph order`() {
        val events = mutableListOf<String>()
        val demux = FakeDemuxNode("demux", events)
        val video = FakeVideoNode("video", events)
        val renderer = FakeRendererNode("renderer", events)
        val graph =
            YPlaybackGraph(
                plan = plan(),
                demux = demux,
                videoDecoder = video,
                audioDecoder = null,
                videoRenderer = renderer,
                audioRenderer = null,
            )

        graph.flush()
        graph.release()

        assertEquals(
            listOf(
                "renderer.flush",
                "video.flush",
                "demux.flush",
                "renderer.release",
                "video.release",
                "demux.release",
            ),
            events,
        )
    }

    private fun plan(): YPlaybackPlan =
        YPlaybackPlan(
            route = YPlaybackRoute.NativeDirect,
            demuxPath = YDemuxPath.Platform,
            decodePath = YDecodePath.Hardware,
            renderPath = YRenderPath.SurfaceDirect,
            outputHdrType = YHdrType.Sdr,
            reason = "test",
        )

    private class FakeDemuxNode(
        override val name: String,
        private val events: MutableList<String>,
    ) : YDemuxNode {
        override fun flush() {
            events += "$name.flush"
        }

        override fun release() {
            events += "$name.release"
        }
    }

    private class FakeVideoNode(
        override val name: String,
        private val events: MutableList<String>,
    ) : YVideoDecodeNode {
        override fun flush() {
            events += "$name.flush"
        }

        override fun release() {
            events += "$name.release"
        }
    }

    private class FakeRendererNode(
        override val name: String,
        private val events: MutableList<String>,
    ) : YVideoRenderNode {
        override fun flush() {
            events += "$name.flush"
        }

        override fun release() {
            events += "$name.release"
        }
    }
}

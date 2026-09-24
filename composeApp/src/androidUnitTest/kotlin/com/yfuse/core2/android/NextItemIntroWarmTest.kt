package com.yfuse.core2.android

import android.media.MediaFormat
import com.yfuse.core.data.PlaybackNetworkClass
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextItemIntroWarmTest {
    @Test
    fun `the post-intro keyframe is read and the extractor is handed back at the start`() =
        runBlocking {
            val extractor = RecordingExtractor()

            assertTrue(warmNextItemIntroEnd(extractor, introEndMs = 90_000L) { true })

            assertEquals(listOf("select:0", "seek:90000000", "read", "seek:0", "unselect:0"), extractor.calls)
        }

    @Test
    fun `an interrupted warm is reported so playback never adopts that extractor`() =
        runBlocking {
            val extractor = RecordingExtractor(failRead = true)

            assertFalse(warmNextItemIntroEnd(extractor, introEndMs = 90_000L) { true })
        }

    @Test
    fun `next episode follows the preheat network choice`() {
        assertTrue(nextItemNetworkClassAllowed(PlaybackNetworkClass.Unmetered, false, allowMeteredNetwork = false))
        assertFalse(nextItemNetworkClassAllowed(PlaybackNetworkClass.Metered, false, allowMeteredNetwork = false))
        assertTrue(nextItemNetworkClassAllowed(PlaybackNetworkClass.Metered, false, allowMeteredNetwork = true))
        assertFalse(nextItemNetworkClassAllowed(PlaybackNetworkClass.Metered, true, allowMeteredNetwork = true))
        assertFalse(nextItemNetworkClassAllowed(PlaybackNetworkClass.Unmetered, true, allowMeteredNetwork = true))
        assertFalse(nextItemNetworkClassAllowed(PlaybackNetworkClass.Offline, false, allowMeteredNetwork = true))
    }

    private class RecordingExtractor(
        private val failRead: Boolean = false,
    ) : YPlatformExtractorSource {
        val calls = CopyOnWriteArrayList<String>()
        override val name = "recording extractor"
        override val trackCount = 1

        override fun open(source: YAndroidMediaSource) = Unit

        override fun trackFormat(index: Int): MediaFormat = error("unused")

        override fun findFirstTrack(mimePrefix: String): Int? = 0.takeIf { mimePrefix == "video/" }

        override fun readSourcePrefix(maximumBytes: Int): ByteArray? = null

        override fun drmInitializationData(schemeUuid: UUID): ByteArray? = null

        override fun setMediaBitRateBitsPerSecond(value: Long) = Unit

        override fun transportQoeSnapshot(): YTransportPrefetchQoeSnapshot? = null

        override fun blockedForegroundReadMs(): Long = 0L

        override fun selectTrack(index: Int) {
            calls += "select:$index"
        }

        override fun unselectTrack(index: Int) {
            calls += "unselect:$index"
        }

        override fun seekTo(positionUs: Long) {
            calls += "seek:$positionUs"
        }

        override fun readSample(target: ByteBuffer): YExtractorSample? {
            calls += "read"
            if (failRead) throw java.io.InterruptedIOException("range cancelled")
            return YExtractorSample(
                trackIndex = 0,
                data = ByteBuffer.allocate(1),
                presentationTimeUs = 90_000_000L,
                flags = 1,
            )
        }

        override fun advance(): Boolean = false

        override fun release() = Unit
    }
}

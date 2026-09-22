from edit import read, write, replace
A='composeApp/src/androidMain/kotlin/com/yfuse/core2/android/'
write(A+'AndroidVideoDecoderHandoff.kt','''package com.yfuse.core2.android

import android.media.MediaFormat
import android.view.Surface
import com.yfuse.core.logging.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Exact codec configuration, including CSD bytes. Container duration/bitrate are not decoder state. */
internal data class VideoDecoderReuseKey(
    val mime: String,
    val decoderName: String?,
    val integers: List<Int?>,
    val csd: List<List<Byte>>,
)

internal fun videoDecoderReuseKey(format: MediaFormat, decoderName: String?): VideoDecoderReuseKey? {
    val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
    if (mime !in setOf("video/avc", "video/hevc")) return null
    val csd = (0..2).map { index ->
        val buffer = format.getByteBuffer("csd-$index")?.duplicate() ?: return@map emptyList<Byte>()
        if (buffer.remaining() > 64 * 1024) return null
        ByteArray(buffer.remaining()).also { buffer.get(it) }.toList()
    }
    if (csd.all { it.isEmpty() }) return null
    val keys = listOf("width", "height", "profile", "level", "max-input-size", "rotation-degrees",
        "color-standard", "color-range", "color-transfer", "sar-width", "sar-height",
        "crop-left", "crop-right", "crop-top", "crop-bottom")
    return VideoDecoderReuseKey(mime, decoderName, keys.map { key ->
        if (format.containsKey(key)) format.getInteger(key) else null
    }, csd)
}

/** One router-owned decoder, transferred only after the old player's release barrier completes. */
internal class AndroidVideoDecoderHandoff : AutoCloseable {
    private data class Entry(val key: VideoDecoderReuseKey, val surface: Surface, val node: AndroidMediaCodecVideoNode)
    private var entry: Entry? = null
    private var expiry: ScheduledFuture<*>? = null

    @Synchronized
    fun offer(key: VideoDecoderReuseKey, surface: Surface, node: AndroidMediaCodecVideoNode) {
        close()
        node.setOnFrameRenderedListener(listener = null)
        val next = Entry(key, surface, node)
        entry = next
        expiry = releaser.schedule({ expire(next) }, 5L, TimeUnit.SECONDS)
    }

    @Synchronized
    fun take(key: VideoDecoderReuseKey?, surface: Surface): AndroidMediaCodecVideoNode? {
        val previous = entry ?: return null
        entry = null
        expiry?.cancel(false)
        expiry = null
        if (key == null || previous.key != key || previous.surface !== surface || !surface.isValid) {
            previous.node.release()
            return null
        }
        return try {
            // A new timestamp epoch rejects any old Surface callback arriving after the flush.
            previous.node.flush()
            AppLog.info(category = "player.core2", event = "next_item_video_decoder_reused",
                message = "Exact-format SDR decoder reused on the same Surface")
            previous.node
        } catch (_: Exception) {
            previous.node.release()
            null
        }
    }

    @Synchronized
    private fun expire(expected: Entry) {
        if (entry === expected) close()
    }

    @Synchronized
    override fun close() {
        expiry?.cancel(false)
        expiry = null
        val previous = entry
        entry = null
        previous?.node?.release()
    }

    private companion object {
        val releaser = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "YCore-VideoHandoffRelease").apply { isDaemon = true }
        }
    }
}
''')
p=A+'AndroidNativeDirectYPlayer.kt'
replace(p,'    private val preparedExtractor: ((YMediaItem) -> YPlatformExtractorSource?)? = null,','''    private val preparedExtractor: ((YMediaItem) -> YPlatformExtractorSource?)? = null,
    private val videoHandoff: AndroidVideoDecoderHandoff? = null,''')
replace(p,'    private val appContext = context.applicationContext','''    @Volatile
    private var videoHandoffRequested = false

    /** The router calls this only for an adjacent, prepared SDR/PCM direct-play route. */
    fun prepareVideoHandoff() {
        videoHandoffRequested = videoHandoff != null && mutableState.value.diagnostics.videoOutputVerified
    }

    private val appContext = context.applicationContext''')
replace(p,'        private val videoDecoder = AndroidMediaCodecVideoNode()', '        private var videoDecoder = AndroidMediaCodecVideoNode()\n        private var audioDrainStartedNs: Long? = null')
replace(p,'''                    videoDecoder.configure(
                        format = format,
                        surface = surface,
                        decoderName = decoderName,
                        mediaCrypto = drmBinding?.mediaCrypto,
                        isolateFrameTimestamps = true,
                    )''','''                    val reused = if (drmBinding == null && !isAudioPassthrough()) {
                        videoHandoff?.take(videoDecoderReuseKey(format, decoderName), surface)
                    } else null
                    if (reused != null) {
                        videoDecoder.release()
                        videoDecoder = reused
                    } else {
                        videoDecoder.configure(
                            format = format,
                            surface = surface,
                            decoderName = decoderName,
                            mediaCrypto = drmBinding?.mediaCrypto,
                            isolateFrameTimestamps = true,
                        )
                    }''')
replace(p,'            runCatching(videoDecoder::release)\n            runCatching { drmSession?.close() }','''            val retainedVideo = runCatching {
                val surface = surfaceOutput?.surface
                val key = videoFormat?.let { videoDecoderReuseKey(it, decoderName) }
                if (videoHandoffRequested && videoConfigured && drmBinding == null &&
                    !isAudioPassthrough() && key != null && surface?.isValid == true && videoHandoff != null) {
                    videoHandoff.offer(key, surface, videoDecoder)
                    videoDecoder = AndroidMediaCodecVideoNode()
                    true
                } else false
            }.getOrDefault(false)
            videoHandoffRequested = false
            if (!retainedVideo) runCatching(videoDecoder::release)
            runCatching { drmSession?.close() }''')
replace(p,'            frameRateManager.clear()\n            runCatching(demux::release)', '            if (!retainedVideo) frameRateManager.clear()\n            runCatching(demux::release)')
replace(p,'''        private fun finishIfEnded() {
            if (!isEnded()) return''','''        private fun finishIfEnded() {
            if (!isEnded()) return
            // Decoder EOS means all PCM was submitted, not that AudioTrack played its tail.
            // Explicit credits skips intentionally bypass this natural-end drain.
            if (!isAudioPassthrough() && audioInputFormat != null && audioRenderer.hasPendingPcm()) {
                val now = System.nanoTime()
                val started = audioDrainStartedNs ?: now.also { audioDrainStartedNs = it }
                if (now - started < 2_000_000_000L) return
                AppLog.warning(category = "player.core2", event = "next_item_audio_drain_timeout",
                    message = "PCM tail did not drain within the bounded end-of-item window")
            }
            audioDrainStartedNs = null''')
replace(p,'        private fun resetEndState() {\n','        private fun resetEndState() {\n            audioDrainStartedNs = null\n')
p=A+'AndroidAudioTrackRenderNode.kt'
replace(p,'    fun clockSnapshot(): YAudioClockSnapshot? {','''    /** Hardware-clock position, not decoder EOS, determines whether submitted PCM remains. */
    fun hasPendingPcm(): Boolean {
        val format = configuredFormat ?: return false
        val base = basePresentationTimeUs ?: return false
        if (writtenBytes <= 0L || sampleRate <= 0) return false
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else AudioFormat.ENCODING_PCM_16BIT
        val sampleBytes = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            else -> return false
        }
        val frameBytes = sampleBytes * format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        val played = clockSnapshot()?.positionUs?.minus(base)?.coerceAtLeast(0L) ?: return true
        return pcmTailPending(writtenBytes, frameBytes, sampleRate, played)
    }

    fun clockSnapshot(): YAudioClockSnapshot? {''')
text=read(p)+'''
internal fun pcmTailPending(writtenBytes: Long, frameBytes: Int, sampleRate: Int, playedUs: Long): Boolean {
    if (writtenBytes <= 0L || frameBytes <= 0 || sampleRate <= 0) return false
    val submittedFrames = writtenBytes / frameBytes
    val playedFrames = playedUs.coerceAtLeast(0L) / 1_000_000L * sampleRate +
        playedUs.coerceAtLeast(0L) % 1_000_000L * sampleRate / 1_000_000L
    return submittedFrames - playedFrames > 1L
}
'''
write(p,text)
p=A+'AndroidAdaptiveCore2YPlayer.kt'
replace(p,'        var nextItemPreloadJob: Job? = null','        val videoHandoff = AndroidVideoDecoderHandoff()\n        var nextItemPreloadJob: Job? = null')
replace(p,'''            if (warmedRoute != null) {''','''            if (warmedRoute == null) videoHandoff.close()
            if (warmedRoute != null) {''')
replace(p,'''                        preparedExtractor = routeEvaluator::takePreparedExtractor,
                        preferredRemoteBufferTargetUs = preferredRemoteBufferTargetUs,''','''                        preparedExtractor = routeEvaluator::takePreparedExtractor,
                        videoHandoff = videoHandoff.takeIf {
                            item.drmConfiguration == null && decision.probe.playbackRequest.video.hdrType == YHdrType.Sdr &&
                                decision.plan.audioPath == YAudioOutputPath.DecodePcm
                        },
                        preferredRemoteBufferTargetUs = preferredRemoteBufferTargetUs,''')
replace(p,'''                            handoffStartedNs = System.nanoTime()''','''                            val preparedDecision = preloadedNextRoute?.decision
                            if (selectedIndex == currentIndex + 1 &&
                                preparedDecision?.nativeDirectExecutable == true &&
                                preparedDecision.probe.playbackRequest.video.hdrType == YHdrType.Sdr &&
                                preparedDecision.plan.audioPath == YAudioOutputPath.DecodePcm) {
                                (child as? AndroidNativeDirectYPlayer)?.prepareVideoHandoff()
                            } else videoHandoff.close()
                            handoffStartedNs = System.nanoTime()''')
replace(p,'''                routeEvaluator.closePreparedExtractor()
                routeEvaluator.closePreparedEnhancedDemux()
            }
        }
    }''','''                routeEvaluator.closePreparedExtractor()
                routeEvaluator.closePreparedEnhancedDemux()
                videoHandoff.close()
            }
        }
    }''')
write('composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/NextItemOutputHandoffTest.kt','''package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NextItemOutputHandoffTest {
    @Test fun exact_video_configuration_includes_decoder_geometry_and_csd_bytes() {
        val key = VideoDecoderReuseKey("video/avc", "decoder", listOf(1920, 1080), listOf(listOf(1, 2)))
        assertNotEquals(key, key.copy(decoderName = "other"))
        assertNotEquals(key, key.copy(integers = listOf(1280, 720)))
        assertNotEquals(key, key.copy(csd = listOf(listOf(1, 3))))
    }
    @Test fun pcm_end_waits_for_submitted_frames_and_tolerates_clock_rounding() {
        assertTrue(pcmTailPending(192_000, 4, 48_000, 900_000))
        assertFalse(pcmTailPending(192_000, 4, 48_000, 1_000_000))
        assertFalse(pcmTailPending(192_000, 4, 48_000, 999_999))
        assertFalse(pcmTailPending(0, 4, 48_000, 0))
        assertFalse(pcmTailPending(192_000, 0, 48_000, 0))
    }
}
''')

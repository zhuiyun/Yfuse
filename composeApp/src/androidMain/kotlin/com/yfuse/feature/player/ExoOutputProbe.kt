package com.yfuse.feature.player

import android.media.MediaCodecInfo.CodecCapabilities
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import java.util.concurrent.atomic.AtomicReference

internal data class ExoVideoCodecEvidence(
    val decoderName: String,
    val mimeType: String,
    val tunneled: Boolean,
) {
    fun confirmsDolbyVision(
        inputMime: String?,
        rendered: Boolean,
        displaySupportsDolby: Boolean,
    ): Boolean =
        rendered &&
            displaySupportsDolby &&
            inputMime == "video/dolby-vision" &&
            mimeType == "video/dolby-vision"
}

/** Observes successful codec configuration and the sink clock without changing decoder policy. */
@UnstableApi
internal class ExoOutputProbe {
    val videoCodec = AtomicReference<ExoVideoCodecEvidence?>()

    @Volatile
    var audioPositionUs: Long = AudioSink.CURRENT_POSITION_NOT_SET
        private set

    @Volatile
    var audioGeneration: Long = 0L
        private set

    fun codecFactory(delegate: MediaCodecAdapter.Factory): MediaCodecAdapter.Factory =
        MediaCodecAdapter.Factory { configuration ->
            val adapter = delegate.createAdapter(configuration)
            if (!MimeTypes.isVideo(configuration.codecInfo.mimeType)) {
                adapter
            } else {
                val evidence =
                    ExoVideoCodecEvidence(
                        decoderName = configuration.codecInfo.name,
                        mimeType = configuration.codecInfo.mimeType,
                        tunneled =
                            runCatching {
                                configuration.mediaFormat.getFeatureEnabled(CodecCapabilities.FEATURE_TunneledPlayback)
                            }.getOrDefault(false),
                    )
                videoCodec.set(evidence)
                object : MediaCodecAdapter by adapter {
                    override fun release() {
                        try {
                            adapter.release()
                        } finally {
                            videoCodec.compareAndSet(evidence, null)
                        }
                    }
                }
            }
        }

    fun audioSink(delegate: AudioSink): AudioSink =
        object : ForwardingAudioSink(delegate) {
            override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
                super.getCurrentPositionUs(sourceEnded).also { audioPositionUs = it }

            override fun flush() {
                resetClock()
                super.flush()
            }

            override fun reset() {
                resetClock()
                super.reset()
            }

            override fun release() {
                resetClock()
                super.release()
            }
        }

    private fun resetClock() {
        audioPositionUs = AudioSink.CURRENT_POSITION_NOT_SET
        audioGeneration++
    }
}

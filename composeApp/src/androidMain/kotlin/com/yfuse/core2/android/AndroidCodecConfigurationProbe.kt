package com.yfuse.core2.android

import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import com.yfuse.core2.capability.YVideoRequirement
import java.io.IOException

internal enum class YCodecConfigurationProbeResult {
    Configured,
    Rejected,
    Inconclusive,
}

/**
 * Bounded active codec probe used before first playback on a previously unseen route.
 * It proves create/configure/start against a real Surface. First-frame playback subsequently
 * promotes the same registry key from Configured to Rendered; no advertised claim is treated as
 * rendered evidence merely because this preflight succeeds.
 */
internal class AndroidCodecConfigurationProbe {
    fun probe(
        decoderName: String,
        mimeType: String,
        requirement: YVideoRequirement,
    ): YCodecConfigurationProbeResult {
        val width = requirement.width.coerceAtLeast(MIN_PROBE_DIMENSION)
        val height = requirement.height.coerceAtLeast(MIN_PROBE_DIMENSION)
        val imageReader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, PROBE_SURFACE_IMAGES)
        var codec: MediaCodec? = null
        return try {
            val format = MediaFormat.createVideoFormat(mimeType, width, height)
            if (requirement.frameRate > 0f) format.setFloat(MediaFormat.KEY_FRAME_RATE, requirement.frameRate)
            requirement.dolbyVisionProfile
                ?.toAndroidDolbyVisionProfile()
                ?.let { format.setInteger(MediaFormat.KEY_PROFILE, it) }
            codec = MediaCodec.createByCodecName(decoderName)
            codec.configure(format, imageReader.surface, null, 0)
            codec.start()
            YCodecConfigurationProbeResult.Configured
        } catch (error: MediaCodec.CodecException) {
            if (error.isRecoverable || error.isTransient) {
                YCodecConfigurationProbeResult.Inconclusive
            } else {
                YCodecConfigurationProbeResult.Rejected
            }
        } catch (_: IOException) {
            YCodecConfigurationProbeResult.Rejected
        } catch (_: Throwable) {
            // Surface allocation and vendor framework failures do not prove decoder rejection.
            YCodecConfigurationProbeResult.Inconclusive
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            imageReader.close()
        }
    }
}

private const val MIN_PROBE_DIMENSION = 16
private const val PROBE_SURFACE_IMAGES = 2

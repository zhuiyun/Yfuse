package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.capability.YAudioOutputPath
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.render.YFrameRateSwitchMode

internal class NativeDirectSessionConfiguration(
    val request: YPlayerOpenRequest,
    val decoderName: String? = null,
    val runtimeCapabilityKey: YRuntimeVideoCapabilityKey? = null,
    val plannedAudioOutputPath: YAudioOutputPath? = null,
    val frameRateSwitchMode: YFrameRateSwitchMode = YFrameRateSwitchMode.SeamlessOnly,
    val plannedDolbyVisionConfig: YDolbyVisionConfig? = null,
    val confirmedDolbyVisionNalIdentity: Boolean = false,
    val requireDolbyVisionIdentity: Boolean = false,
    val preferredRemoteBufferTargetUs: Long? = null,
    val preparedExtractor: ((YMediaItem) -> YPlatformExtractorSource?)? = null,
    val videoHandoff: AndroidVideoDecoderHandoff? = null,
)

/** Cross-thread signals only; media resources remain owned by the playback actor. */
internal class NativeDirectSessionControl {
    @Volatile var released = false

    @Volatile var releasedAtMs: Long? = null

    @Volatile var videoHandoffRequested = false
}

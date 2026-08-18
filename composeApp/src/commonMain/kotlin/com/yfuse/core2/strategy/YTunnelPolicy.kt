package com.yfuse.core2.strategy

import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YVideoDecoderCapability

/**
 * Conservative eligibility gate for Android multimedia tunneling.
 *
 * A tunneled video decoder alone is insufficient: the path is audio-clocked, so Core2 requires an
 * audio track that can remain on the native audio route as well as a direct Surface presentation.
 * GPU processing, software audio fallback and video-only media stay on NativeDirect.
 */
fun canUseNativeTunnel(
    request: YPlaybackRequest,
    capabilities: YDeviceCapabilities,
    decoder: YVideoDecoderCapability,
): Boolean =
    request.preferTunnel &&
        request.audio != null &&
        capabilities.supportsTunnel &&
        capabilities.supportsSurfaceDirect &&
        capabilities.supportsAudio(request.audio) &&
        decoder.tunneledPlayback &&
        request.platformDemuxSupported

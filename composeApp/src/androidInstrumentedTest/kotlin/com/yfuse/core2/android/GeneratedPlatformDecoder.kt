package com.yfuse.core2.android

import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.test.platform.app.InstrumentationRegistry

/** Explicit codec selection for low-level platform tests; adaptive playback keeps normal selection. */
internal fun generatedPlatformAvcDecoder(): String? {
    val requested =
        InstrumentationRegistry.getArguments().getString("platformAvcDecoder")?.takeIf(String::isNotBlank)
            ?: return null
    check(
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
            !it.isEncoder && it.name == requested && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes
        },
    ) { "Requested platform AVC decoder is unavailable: $requested" }
    return requested
}

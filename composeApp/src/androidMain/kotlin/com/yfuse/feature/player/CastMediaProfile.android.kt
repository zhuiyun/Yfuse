package com.yfuse.feature.player

import com.yfuse.core.cast.CastMediaProfile

/** Builds receiver negotiation facts from the original representation, never from UI labels. */
internal fun PlayerMediaItem.castMediaProfile(): CastMediaProfile {
    val source = activeVersion
    return CastMediaProfile(
        contentType = url.castContentType(),
        videoCodec = source?.castVideoCodec(),
        audioCodec = source?.castAudioCodec(),
        width = source?.sourceWidth,
        height = source?.sourceHeight,
        frameRate = source?.sourceFrameRate,
        dolbyVision = source?.dolbyVision == true,
        dolbyAtmos = source?.dolbyAtmos == true,
    )
}

private fun PlayerMediaVersion.castVideoCodec(): String? {
    val declared = sourceVideoCodec.orEmpty().trim().lowercase()
    if (declared.startsWithAny("dvhe", "dvh1", "dvav", "dva1", "avc1", "avc3", "hvc1", "hev1", "av01")) {
        return declared
    }
    if (dolbyVision) {
        return when (dolbyProfile) {
            5 -> "dvhe.05.06"
            7 -> "dvhe.07.06"
            8 -> "dvh1.08.06"
            9 -> "dvav.09.09"
            else -> null
        }
    }
    return when (declared) {
        "h264", "avc" -> "avc1.640028"
        "h265", "hevc" -> "hvc1.2.4.L153.B0"
        else -> null
    }
}

private fun PlayerMediaVersion.castAudioCodec(): String? {
    val declared = sourceAudio.orEmpty().trim().lowercase()
    return when {
        "truehd" in declared || "mlp" in declared -> "truehd"
        "eac3" in declared || "e-ac-3" in declared || "ec-3" in declared -> "ec-3"
        "ac3" in declared || "ac-3" in declared -> "ac-3"
        "aac" in declared -> "mp4a.40.2"
        else -> null
    }
}

private fun String.startsWithAny(vararg prefixes: String): Boolean = prefixes.any(::startsWith)

private fun String.castContentType(): String =
    when {
        substringBefore('?').endsWith(".m3u8", true) -> "application/x-mpegURL"
        substringBefore('?').endsWith(".webm", true) -> "video/webm"
        else -> "video/mp4"
    }

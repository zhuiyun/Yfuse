package com.yfuse.core.model

/** Numeric media facts shared by version, DTO and cross-server ranking. */
private data class MediaQuality(
    val resolutionPixels: Long?,
    val bitrateBps: Int?,
    val dynamicRangeRank: Int,
    val videoBitDepth: Int?,
    val dolbyAtmos: Boolean,
    val losslessAudio: Boolean,
    val maxAudioChannels: Int?,
    val maxAudioBitrateBps: Int?,
    val sizeBytes: Long?,
)

/** Negative means [left] is the better version and should appear first. */
internal fun compareMediaVersionsBestFirst(
    left: MediaVersion,
    right: MediaVersion,
): Int = compareQuality(left.sortQuality(), right.sortQuality())

/** Negative means [left] is the better source and should appear first. */
internal fun compareSourceInfoBestFirst(
    left: SourceInfo?,
    right: SourceInfo?,
): Int = compareQuality(left.sortQuality(), right.sortQuality())

private fun MediaVersion.sortQuality(): MediaQuality =
    MediaQuality(
        resolutionPixels = resolutionPixels(video?.width, videoHeight ?: video?.height),
        bitrateBps = bitrateBps?.takeIf { it > 0 },
        dynamicRangeRank = dynamicRangeRank(isDolbyVision, videoRange),
        videoBitDepth = video?.bitDepth?.takeIf { it > 0 },
        dolbyAtmos = hasDolbyAtmos,
        losslessAudio = audioTracks.any { it.isLossless },
        maxAudioChannels =
            audioTracks
                .mapNotNull { it.channelCount?.takeIf { count -> count > 0 } }
                .maxOrNull(),
        maxAudioBitrateBps =
            audioTracks
                .mapNotNull { it.bitrateBps?.takeIf { rate -> rate > 0 } }
                .maxOrNull(),
        sizeBytes = sizeBytes?.takeIf { it > 0 },
    )

private fun SourceInfo?.sortQuality(): MediaQuality =
    MediaQuality(
        resolutionPixels = resolutionPixels(this?.videoWidth, this?.videoHeight),
        bitrateBps = this?.bitrateBps?.takeIf { it > 0 },
        dynamicRangeRank = dynamicRangeRank(this?.dolbyVision == true, this?.videoRange),
        videoBitDepth = this?.videoBitDepth?.takeIf { it > 0 },
        dolbyAtmos = this?.dolbyAtmos == true,
        losslessAudio = this?.losslessAudio == true,
        maxAudioChannels = this?.maxAudioChannels?.takeIf { it > 0 },
        maxAudioBitrateBps = this?.maxAudioBitrateBps?.takeIf { it > 0 },
        sizeBytes = this?.sizeBytes?.takeIf { it > 0 },
    )

private fun compareQuality(
    left: MediaQuality,
    right: MediaQuality,
): Int =
    compareDescending(left.resolutionPixels, right.resolutionPixels).nonZero()
        ?: compareDescending(left.bitrateBps, right.bitrateBps).nonZero()
        ?: compareDescending(left.dynamicRangeRank, right.dynamicRangeRank).nonZero()
        ?: compareDescending(left.videoBitDepth, right.videoBitDepth).nonZero()
        ?: compareDescending(left.dolbyAtmos, right.dolbyAtmos).nonZero()
        ?: compareDescending(left.losslessAudio, right.losslessAudio).nonZero()
        ?: compareDescending(left.maxAudioChannels, right.maxAudioChannels).nonZero()
        ?: compareDescending(left.maxAudioBitrateBps, right.maxAudioBitrateBps).nonZero()
        ?: compareDescending(left.sizeBytes, right.sizeBytes)

private fun resolutionPixels(
    width: Int?,
    height: Int?,
): Long? {
    val safeWidth = width?.takeIf { it > 0 }
    val safeHeight = height?.takeIf { it > 0 }
    return when {
        safeWidth != null && safeHeight != null -> safeWidth.toLong() * safeHeight
        // Some endpoints omit one dimension. Approximate a 16:9 frame without parsing the
        // localized quality label.
        safeHeight != null -> safeHeight.toLong() * safeHeight * 16 / 9
        safeWidth != null -> safeWidth.toLong() * safeWidth * 9 / 16
        else -> null
    }
}

private fun dynamicRangeRank(
    dolbyVision: Boolean,
    range: String?,
): Int {
    if (dolbyVision) return 4
    val normalized = range.orEmpty().uppercase().replace(" ", "")
    return when {
        "HDR10+" in normalized || "HDR10PLUS" in normalized -> 3
        "HDR" in normalized || "HLG" in normalized -> 2
        else -> 1
    }
}

private fun <T : Comparable<T>> compareDescending(
    left: T?,
    right: T?,
): Int =
    when {
        left == right -> 0
        left == null -> 1
        right == null -> -1
        else -> right.compareTo(left)
    }

private fun Int.nonZero(): Int? = takeIf { it != 0 }

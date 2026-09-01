package com.yfuse.core2.adaptive

data class YAdaptiveByteRange(
    val length: Long,
    val offset: Long? = null,
) {
    init {
        require(length > 0L)
        require(offset == null || offset >= 0L)
    }
}

data class YAdaptiveVariant(
    val id: String,
    val uri: String,
    val bandwidthBitsPerSecond: Long,
    val averageBandwidthBitsPerSecond: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val codecs: List<String> = emptyList(),
    /** Enhanced codecs compatible with [codecs], including Dolby Vision `/db1p` and `/db4h`. */
    val supplementalCodecs: List<String> = emptyList(),
    val audioGroupId: String? = null,
    val videoGroupId: String? = null,
    val subtitleGroupId: String? = null,
    val closedCaptionsGroupId: String? = null,
    val videoRange: YHlsVideoRange = YHlsVideoRange.Unknown,
    val stableVariantId: String? = null,
) {
    init {
        require(id.isNotBlank())
        require(uri.isNotBlank())
        require(bandwidthBitsPerSecond > 0L)
        require(averageBandwidthBitsPerSecond == null || averageBandwidthBitsPerSecond > 0L)
        require(width == null || width > 0)
        require(height == null || height > 0)
        require(frameRate == null || frameRate.isFinite() && frameRate > 0.0)
    }

    val selectionBandwidthBitsPerSecond: Long
        get() = averageBandwidthBitsPerSecond ?: bandwidthBitsPerSecond

    val isDolbyVision: Boolean
        get() =
            codecs.any(String::isDolbyVisionHlsCodec) ||
                supplementalCodecs.any { it.substringBefore('/').isDolbyVisionHlsCodec() }

    val dolbyVisionCompatibilityBrands: Set<String>
        get() =
            supplementalCodecs
                .filter { it.substringBefore('/').isDolbyVisionHlsCodec() }
                .flatMap { codec -> codec.split('/').drop(1) }
                .map(String::lowercase)
                .filter(String::isNotBlank)
                .toSet()

    /**
     * Apple HLS uses `db1p` for a PQ-compatible Dolby Vision base layer and `db4h` for HLG.
     * Reject contradictory supplemental signalling instead of silently routing it as Dolby Vision.
     */
    val hasUsableDolbyVisionSignaling: Boolean
        get() {
            val brands = dolbyVisionCompatibilityBrands
            if (brands.isEmpty()) return true
            val expectedRanges =
                brands.mapNotNull { brand ->
                    when (brand) {
                        "db1p" -> YHlsVideoRange.Pq
                        "db4h" -> YHlsVideoRange.Hlg
                        else -> null
                    }
                }.toSet()
            return expectedRanges.size == 1 && videoRange == expectedRanges.single()
        }
}

enum class YHlsVideoRange {
    Sdr,
    Pq,
    Hlg,
    Unknown,
}

enum class YHlsRenditionType {
    Audio,
    Video,
    Subtitles,
    ClosedCaptions,
    Unknown,
}

data class YHlsRendition(
    val type: YHlsRenditionType,
    val groupId: String,
    val name: String,
    val uri: String? = null,
    val language: String? = null,
    val default: Boolean = false,
    val autoselect: Boolean = false,
    val forced: Boolean = false,
    val channels: String? = null,
    val characteristics: List<String> = emptyList(),
) {
    init {
        require(groupId.isNotBlank())
        require(name.isNotBlank())
    }

    /** Apple HLS marks Dolby Digital Plus JOC (Atmos) with a /JOC CHANNELS suffix. */
    val isDolbyAtmos: Boolean
        get() = type == YHlsRenditionType.Audio && channels.orEmpty().contains("/JOC", ignoreCase = true)
}

enum class YAdaptiveEncryptionMethod {
    Aes128,
    SampleAes,
    Other,
}

data class YAdaptiveEncryption(
    val method: YAdaptiveEncryptionMethod,
    val keyUri: String?,
    val initializationVector: String? = null,
    val keyFormat: String? = null,
    val keyFormatVersions: String? = null,
)

data class YAdaptiveInitializationSegment(
    val uri: String,
    val byteRange: YAdaptiveByteRange? = null,
)

data class YAdaptiveSegment(
    val sequence: Long,
    val uri: String,
    val startTimeUs: Long,
    val durationUs: Long,
    val byteRange: YAdaptiveByteRange? = null,
    val initialization: YAdaptiveInitializationSegment? = null,
    val encryption: YAdaptiveEncryption? = null,
    val discontinuity: Boolean = false,
) {
    init {
        require(sequence >= 0L)
        require(uri.isNotBlank())
        require(startTimeUs >= 0L)
        require(durationUs > 0L)
    }
}

data class YHlsServerControl(
    val canBlockReload: Boolean = false,
    val canSkipUntilUs: Long? = null,
    val canSkipDateRanges: Boolean = false,
    val holdBackUs: Long? = null,
    val partHoldBackUs: Long? = null,
) {
    init {
        require(canSkipUntilUs == null || canSkipUntilUs > 0L)
        require(holdBackUs == null || holdBackUs > 0L)
        require(partHoldBackUs == null || partHoldBackUs > 0L)
        require(!canSkipDateRanges || canSkipUntilUs != null)
    }
}

data class YHlsPartialSegment(
    val mediaSequence: Long,
    val partIndex: Int,
    val uri: String,
    val startTimeUs: Long,
    val durationUs: Long,
    val independent: Boolean = false,
    val gap: Boolean = false,
    val byteRange: YAdaptiveByteRange? = null,
    val initialization: YAdaptiveInitializationSegment? = null,
    val encryption: YAdaptiveEncryption? = null,
    val discontinuity: Boolean = false,
) {
    init {
        require(mediaSequence >= 0L)
        require(partIndex >= 0)
        require(uri.isNotBlank())
        require(startTimeUs >= 0L)
        require(durationUs > 0L)
    }
}

data class YHlsPreloadHint(
    val uri: String,
    val byteRangeStart: Long? = null,
    val byteRangeLength: Long? = null,
) {
    init {
        require(uri.isNotBlank())
        require(byteRangeStart == null || byteRangeStart >= 0L)
        require(byteRangeLength == null || byteRangeLength > 0L)
    }
}

data class YHlsRenditionReport(
    val uri: String,
    val lastMediaSequence: Long? = null,
    val lastPart: Int? = null,
) {
    init {
        require(uri.isNotBlank())
        require(lastMediaSequence == null || lastMediaSequence >= 0L)
        require(lastPart == null || lastPart >= 0)
    }
}

sealed interface YHlsPlaylist {
    data class Master(
        val variants: List<YAdaptiveVariant>,
        val renditions: List<YHlsRendition> = emptyList(),
    ) : YHlsPlaylist {
        init {
            require(variants.isNotEmpty())
        }
    }

    data class Media(
        val isLive: Boolean,
        val mediaSequence: Long,
        val targetDurationUs: Long?,
        val segments: List<YAdaptiveSegment>,
        val discontinuitySequence: Long = 0L,
        val partTargetDurationUs: Long? = null,
        val serverControl: YHlsServerControl? = null,
        val skippedSegmentCount: Int = 0,
        val partialSegments: List<YHlsPartialSegment> = emptyList(),
        val preloadHint: YHlsPreloadHint? = null,
        val renditionReports: List<YHlsRenditionReport> = emptyList(),
    ) : YHlsPlaylist {
        init {
            require(mediaSequence >= 0L)
            require(targetDurationUs == null || targetDurationUs > 0L)
            require(discontinuitySequence >= 0L)
            require(partTargetDurationUs == null || partTargetDurationUs > 0L)
            require(skippedSegmentCount >= 0)
            require(segments.isNotEmpty() || partialSegments.isNotEmpty())
        }
    }
}

internal fun String.isDolbyVisionHlsCodec(): Boolean {
    val normalized = trim().lowercase()
    return normalized.startsWith("dvhe") ||
        normalized.startsWith("dvh1") ||
        normalized.startsWith("dvav") ||
        normalized.startsWith("dva1")
}

enum class YDashContentType {
    Video,
    Audio,
    Text,
    Unknown,
}

data class YDashContentProtection(
    val schemeIdUri: String,
    val value: String? = null,
    val defaultKeyId: String? = null,
    val psshBase64: String? = null,
    val licenseUri: String? = null,
) {
    init {
        require(schemeIdUri.isNotBlank())
    }
}

data class YDashDescriptor(
    val schemeIdUri: String,
    val value: String? = null,
) {
    init {
        require(schemeIdUri.isNotBlank() && schemeIdUri.isSafeDashMetadata())
        require(value == null || value.isSafeDashMetadata())
    }
}

data class YDashTimelineEntry(
    val startTime: Long? = null,
    val duration: Long,
    val repeat: Int = 0,
) {
    init {
        require(startTime == null || startTime >= 0L)
        require(duration > 0L)
        require(repeat >= -1)
    }
}

data class YDashSegmentTemplate(
    val initialization: String?,
    val media: String,
    val timescale: Long = 1L,
    val duration: Long? = null,
    val startNumber: Long = 1L,
    val timeline: List<YDashTimelineEntry> = emptyList(),
) {
    init {
        require(media.isNotBlank())
        require(timescale > 0L)
        require(duration == null || duration > 0L)
        require(startNumber >= 0L)
        require(duration != null || timeline.isNotEmpty())
    }
}

data class YDashRepresentation(
    val id: String,
    val baseUri: String,
    val bandwidthBitsPerSecond: Long,
    val contentType: YDashContentType,
    val mimeType: String?,
    val codecs: List<String>,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val audioSamplingRate: Int? = null,
    val language: String? = null,
    val segmentTemplate: YDashSegmentTemplate? = null,
    val contentProtections: List<YDashContentProtection> = emptyList(),
    val supplementalProperties: List<YDashDescriptor> = emptyList(),
) {
    init {
        require(id.isNotBlank())
        require(baseUri.isNotBlank())
        require(bandwidthBitsPerSecond > 0L)
        require(width == null || width > 0)
        require(height == null || height > 0)
        require(frameRate == null || frameRate.isFinite() && frameRate > 0.0)
        require(audioSamplingRate == null || audioSamplingRate > 0)
    }

    fun asAdaptiveVariant(): YAdaptiveVariant =
        YAdaptiveVariant(
            id = id,
            uri = baseUri,
            bandwidthBitsPerSecond = bandwidthBitsPerSecond,
            width = width,
            height = height,
            frameRate = frameRate,
            codecs = codecs,
        )

    val isDolbyVision: Boolean
        get() = codecs.any(String::isDolbyVisionHlsCodec)

    val isDolbyAtmos: Boolean
        get() =
            contentType == YDashContentType.Audio &&
                supplementalProperties.any { descriptor ->
                    descriptor.value.orEmpty().contains("JOC", ignoreCase = true) ||
                        descriptor.schemeIdUri.contains("EC3_ExtensionType", ignoreCase = true) &&
                        descriptor.value.orEmpty().contains("2018", ignoreCase = true)
                }
}

data class YDashManifest(
    val isLive: Boolean,
    val minimumUpdatePeriodUs: Long? = null,
    val mediaPresentationDurationUs: Long? = null,
    val availabilityStartTime: String? = null,
    val publishTime: String? = null,
    val timeShiftBufferDepthUs: Long? = null,
    val suggestedPresentationDelayUs: Long? = null,
    val periodStartUs: Long? = null,
    val representations: List<YDashRepresentation>,
) {
    init {
        require(minimumUpdatePeriodUs == null || minimumUpdatePeriodUs > 0L)
        require(mediaPresentationDurationUs == null || mediaPresentationDurationUs > 0L)
        require(availabilityStartTime == null || availabilityStartTime.isSafeDashMetadata())
        require(publishTime == null || publishTime.isSafeDashMetadata())
        require(timeShiftBufferDepthUs == null || timeShiftBufferDepthUs > 0L)
        require(suggestedPresentationDelayUs == null || suggestedPresentationDelayUs > 0L)
        require(periodStartUs == null || periodStartUs >= 0L)
        require(representations.isNotEmpty())
    }
}

private fun String.isSafeDashMetadata(): Boolean = isNotBlank() && none { it == '\r' || it == '\n' }

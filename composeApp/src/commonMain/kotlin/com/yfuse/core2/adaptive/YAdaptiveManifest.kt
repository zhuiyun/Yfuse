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

sealed interface YHlsPlaylist {
    data class Master(
        val variants: List<YAdaptiveVariant>,
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
    ) : YHlsPlaylist {
        init {
            require(mediaSequence >= 0L)
            require(targetDurationUs == null || targetDurationUs > 0L)
            require(segments.isNotEmpty())
        }
    }
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
}

data class YDashManifest(
    val isLive: Boolean,
    val minimumUpdatePeriodUs: Long? = null,
    val mediaPresentationDurationUs: Long? = null,
    val representations: List<YDashRepresentation>,
) {
    init {
        require(minimumUpdatePeriodUs == null || minimumUpdatePeriodUs > 0L)
        require(mediaPresentationDurationUs == null || mediaPresentationDurationUs > 0L)
        require(representations.isNotEmpty())
    }
}

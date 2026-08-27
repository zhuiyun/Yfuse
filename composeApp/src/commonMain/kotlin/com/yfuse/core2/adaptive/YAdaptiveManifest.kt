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

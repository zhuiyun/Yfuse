package com.yfuse.core.model

/**
 * One playable version of a title on a single server — an Emby `MediaSource`.
 *
 * A library commonly holds several files for the same film: a 4K remux next to a 1080p
 * encode, or an original cut next to a dubbed one. Emby returns them all, but only the
 * first was ever read, so the rest were invisible and unreachable — and, because the stream
 * URLs pinned `MediaSourceId` to the item id, unplayable even if they had been listed.
 *
 * Raw values rather than preformatted strings: the same version is described differently in
 * a compact row and in an expanded detail block, and a size in bytes can be compared while
 * "42.3 GB" cannot.
 */
data class MediaVersion(
    /** Emby's `MediaSource.Id`, which is what a stream request must name. */
    val id: String,
    /** The server's own label ("Bluray 2160p"), falling back to the file's container. */
    val name: String,
    val container: String?,
    val sizeBytes: Long?,
    val bitrateBps: Int?,
    val videoCodec: String?,
    val videoHeight: Int?,
    /** `HDR10` / `Dolby Vision`, absent for SDR. */
    val videoRange: String?,
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val subtitleTracks: List<SubtitleTrackInfo> = emptyList(),
) {
    /** `4K` / `1080P`, or null when the server reported no video stream. */
    val resolutionLabel: String?
        get() = videoHeight?.let { height ->
            when {
                height >= 2000 -> "4K"
                height >= 1000 -> "1080P"
                height >= 700 -> "720P"
                else -> "${height}P"
            }
        }

    /** `4K HDR10` — resolution with the dynamic range appended when it has one. */
    val qualityLabel: String
        get() = listOfNotNull(resolutionLabel ?: "未知清晰度", videoRange).joinToString(" ")

    val sizeLabel: String? get() = sizeBytes?.takeIf { it > 0 }?.let(::formatBytes)

    val bitrateLabel: String?
        get() = bitrateBps?.takeIf { it > 0 }?.let { "${it / 1_000_000} Mbps" }

    /** `4K HDR10 · 42.3 GB · 68 Mbps · MKV` — the one-line summary for a collapsed row. */
    val summary: String
        get() = listOfNotNull(
            qualityLabel,
            sizeLabel,
            bitrateLabel,
            container?.uppercase(),
        ).joinToString(" · ")
}

/** One audio stream of a [MediaVersion]. */
data class AudioTrackInfo(
    val codec: String?,
    /** `7.1` / `2.0`, from Emby's channel layout or channel count. */
    val channels: String?,
    /** Already resolved to a human name where the code is one we know. */
    val language: String?,
) {
    /** `国语 · DTS-HD MA · 7.1` */
    val label: String
        get() = listOfNotNull(language, codec?.uppercase(), channels)
            .joinToString(" · ")
            .ifBlank { "未知音轨" }
}

/** One subtitle stream of a [MediaVersion]. */
data class SubtitleTrackInfo(
    val codec: String?,
    val language: String?,
    /** True for a track the server marks as burned into the picture. */
    val forced: Boolean = false,
) {
    val label: String
        get() = listOfNotNull(language, codec?.uppercase(), "强制".takeIf { forced })
            .joinToString(" · ")
            .ifBlank { "未知字幕" }
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    if (gb >= 1.0) {
        val tenths = (gb * 10).toLong()
        return "${tenths / 10}.${tenths % 10} GB"
    }
    return "${bytes / 1024 / 1024} MB"
}

/**
 * ISO 639 codes as they read to a Chinese-speaking user.
 *
 * Emby reports whatever the file was tagged with, which is a three-letter code far more
 * often than a name, and "chi" over a track picker helps nobody. Anything unmapped is shown
 * as-is rather than dropped — an unfamiliar code is still more informative than nothing.
 */
private val LANGUAGE_NAMES = mapOf(
    "chi" to "中文", "zho" to "中文", "zh" to "中文",
    "cmn" to "普通话", "yue" to "粤语",
    "eng" to "英语", "en" to "英语",
    "jpn" to "日语", "ja" to "日语",
    "kor" to "韩语", "ko" to "韩语",
    "fra" to "法语", "fre" to "法语", "fr" to "法语",
    "deu" to "德语", "ger" to "德语", "de" to "德语",
    "spa" to "西班牙语", "es" to "西班牙语",
    "rus" to "俄语", "ru" to "俄语",
    "ita" to "意大利语", "it" to "意大利语",
    "por" to "葡萄牙语", "pt" to "葡萄牙语",
    "tha" to "泰语", "vie" to "越南语", "ara" to "阿拉伯语",
    "und" to "未标注",
)

fun languageDisplayName(code: String?): String? {
    val trimmed = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return LANGUAGE_NAMES[trimmed] ?: trimmed.uppercase()
}

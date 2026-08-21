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
    /** Where the file sits on the server. Shown verbatim — it is how a user finds it. */
    val path: String? = null,
    /** Server media kind, notably `Iso`, `Dvd`, or `BluRay` for navigable disc sources. */
    val videoType: String? = null,
    /** Everything the server knows about the picture, for the 媒体信息 table. */
    val video: VideoStreamInfo? = null,
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val subtitleTracks: List<SubtitleTrackInfo> = emptyList(),
    /** Server-approved playback capabilities from PlaybackInfo, when negotiated. */
    val supportsDirectPlay: Boolean? = null,
    val supportsDirectStream: Boolean? = null,
    val supportsTranscoding: Boolean? = null,
    val directStreamUrl: String? = null,
    val addApiKeyToDirectStreamUrl: Boolean = true,
    val transcodingUrl: String? = null,
) {
    /** `4K` / `1080P`, or null when the server reported no video stream. */
    val resolutionLabel: String?
        get() {
            val height = videoHeight ?: video?.height
            val width = video?.width
            val longEdge = listOfNotNull(width, height).maxOrNull() ?: return null
            val shortEdge = listOfNotNull(width, height).minOrNull() ?: longEdge
            return when {
                // Cinematic encodes are commonly cropped to 3840x1600; height alone labels
                // those as 1080p even though they retain the full UHD horizontal detail.
                longEdge >= 3000 || shortEdge >= 1600 -> "4K"
                longEdge >= 1800 || shortEdge >= 1000 -> "1080P"
                longEdge >= 1200 || shortEdge >= 700 -> "720P"
                else -> "${shortEdge}P"
            }
        }

    /**
     * Compact quality label. Dolby metadata is authoritative over a generic `VideoRange=HDR10`:
     * some servers report the HEVC base layer there while `DvProfile` correctly identifies DV.
     */
    val qualityLabel: String
        get() =
            listOfNotNull(
                resolutionLabel ?: "未知清晰度",
                if (isDolbyVision) rangeLabel else videoRange,
            ).joinToString(" ")

    val sizeLabel: String? get() = sizeBytes?.takeIf { it > 0 }?.let(::formatBytes)

    val bitrateLabel: String?
        get() = bitrateBps?.takeIf { it > 0 }?.let { "${it / 1_000_000} Mbps" }

    /** `4K HDR10 · 42.3 GB · 68 Mbps · MKV` — the one-line summary for a collapsed row. */
    val summary: String
        get() =
            listOfNotNull(
                qualityLabel,
                sizeLabel,
                bitrateLabel,
                container?.uppercase(),
            ).joinToString(" · ")

    /**
     * Whether this copy is Dolby Vision, asked of everything that could say so.
     *
     * Emby reports dynamic range in more than one place and not consistently across
     * versions: `VideoRange` is often just `HDR`, with `DOVI` or a `dvhe`/`dvh1` profile
     * being what actually distinguishes Dolby Vision from HDR10. Checking one field misses
     * it on half the libraries, and the badge exists precisely to answer "is this the
     * Dolby copy" — a badge that is sometimes right is worse than none.
     *
     * A reported `DvProfile` settles it outright and is checked first. It has to be: a
     * server that fills that field often *also* reports `VideoRange` as plain `HDR10` and
     * the HEVC base layer's `Main 10` as the profile, with no `dvhe` tag anywhere — so
     * asking the heuristics alone would answer "not Dolby" about a file whose profile the
     * server just told us. That was worth more than a wrong badge: [dolbyProfile] and
     * [needsDolbyCapableDecoder] both hang off this, so profile 5 would have sailed past
     * the one check that exists to catch it.
     */
    val isDolbyVision: Boolean
        get() =
            video?.dolbyProfile != null ||
                listOf(videoRange, videoCodec, video?.profile, video?.displayTitle)
                    .any(String?::mentionsDolbyVision)

    /** Atmos rides on TrueHD or E-AC-3 and is only named in the track's profile or title. */
    val hasDolbyAtmos: Boolean
        get() =
            audioTracks.any { track ->
                track.profile.mentionsAtmos() || track.displayTitle.mentionsAtmos()
            }

    /**
     * Which Dolby Vision profile this copy is, when it is one at all.
     *
     * The profile is the whole story for playback. 8 and 9 carry a base layer an ordinary
     * HEVC/AVC decoder can read, so a device with no Dolby support still gets a correct —
     * if less bright — picture. **5 does not.** Profile 5 is IPT-PQ-C2 all the way down:
     * a decoder without Dolby support will happily decode it and put a magenta-and-green
     * picture on screen, which is worse than failing, because nothing in the pipeline
     * reports an error for anyone to act on.
     */
    val dolbyProfile: Int? get() = video?.dolbyProfile?.takeIf { isDolbyVision }

    /** True only when the server actually reports a Dolby Vision RPU in this stream. */
    val hasDolbyVisionRpu: Boolean
        get() = isDolbyVision && video?.dolbyRpuPresent == true

    /**
     * True when the server reports an enhancement layer. This is deliberately called EL rather
     * than FEL: Jellyfin/Emby expose presence flags, not enough evidence to prove MEL vs FEL.
     */
    val hasDolbyVisionEnhancementLayer: Boolean
        get() = isDolbyVision && video?.dolbyEnhancementLayerPresent == true

    /**
     * Profile 7 plus an EL is the UHD Blu-ray case that needs physical-device verification before
     * Yfuse may claim FEL composition. Base-layer playback or a Dolby badge is not that proof.
     */
    val requiresDolbyVisionEnhancementValidation: Boolean
        get() = dolbyProfile == 7 && hasDolbyVisionEnhancementLayer

    /**
     * True when only a Dolby-capable decoder can be trusted to render this file correctly.
     *
     * Profile 5, a stream with no base layer at all, and a stream explicitly reporting compatibility
     * id 0 are unconditionally Dolby-only. The important fail-safe is the final branch: some servers
     * identify a stream as Dolby Vision but omit both `DvProfile` and the `dvhe.xx` tag. Treating that
     * unknown profile as ordinary HEVC is how P5 can silently turn into a magenta/green picture. An
     * unknown profile is therefore Dolby-only unless the server positively proves an HDR10/SDR/HLG
     * compatible base layer through `DvBlSignalCompatibilityId`.
     */
    val needsDolbyCapableDecoder: Boolean
        get() {
            if (!isDolbyVision) return false
            if (dolbyProfile == 5) return true
            if (video?.dolbyBaseLayerPresent == false) return true
            val compatibility = video?.dolbyBaseLayerCompatibility
            if (compatibility == 0) return true
            if (dolbyProfile == null) return compatibility !in DOLBY_COMPATIBLE_BASE_LAYER_IDS
            return false
        }

    /** `SDR` when the server reported no range at all — the chip always says something. */
    val rangeLabel: String
        get() =
            when {
                // A dual-layer label describes source evidence only. It intentionally does not say
                // FEL, because EL presence alone cannot prove enhancement-layer composition.
                isDolbyVision ->
                    buildString {
                        append(dolbyProfile?.let { "Dolby Vision P$it" } ?: "Dolby Vision")
                        if (hasDolbyVisionEnhancementLayer) append(" · 双层")
                    }
                else -> videoRange?.takeIf { it.isNotBlank() } ?: "SDR"
            }

    /**
     * A disc image/folder is not one linear media container. It needs DVD/Blu-ray title
     * navigation and random block access, neither of which is available when an Android
     * backend receives the server's ordinary `/Videos/{id}/stream` HTTP URL.
     */
    val requiresDiscNavigation: Boolean
        get() {
            val declaredType = videoType?.trim()?.lowercase()
            val declaredContainer = container?.trim()?.lowercase()
            val normalizedPath = path?.trim()?.replace('\\', '/')?.lowercase()
            return (declaredType != null && declaredType in DISC_VIDEO_TYPES) ||
                (declaredContainer != null && declaredContainer in DISC_CONTAINERS) ||
                normalizedPath?.endsWith(".iso") == true ||
                normalizedPath?.contains("/video_ts/") == true ||
                normalizedPath?.contains("/bdmv/") == true ||
                normalizedPath?.endsWith("/video_ts") == true ||
                normalizedPath?.endsWith("/bdmv") == true
        }

    /** `60fps` — whole frames, for a chip that sits beside a bitrate. */
    val frameRateLabel: String?
        get() =
            video?.frameRate?.takeIf { it > 0 }?.let { rate ->
                val rounded = (rate + 0.5).toInt()
                "${rounded}fps"
            }
}

private val DOLBY_COMPATIBLE_BASE_LAYER_IDS = setOf(1, 2, 4)
private val DISC_VIDEO_TYPES = setOf("iso", "dvd", "bluray", "blu-ray")
private val DISC_CONTAINERS = setOf("iso", "dvd", "bluray", "blu-ray", "bdmv", "video_ts", "udf")

private fun String?.mentionsDolbyVision(): Boolean {
    val value = this?.lowercase() ?: return false
    return "dolby vision" in value ||
        "dolbyvision" in value ||
        "dovi" in value ||
        value.startsWith("dvhe") ||
        value.startsWith("dvh1") ||
        value.startsWith("dvav") ||
        value.startsWith("dva1") ||
        // Keep accepting the old transposition because some hand-authored metadata already uses it.
        value.startsWith("dav1")
}

private fun String?.mentionsAtmos(): Boolean = this?.contains("atmos", ignoreCase = true) == true

/**
 * The picture stream, spelled out.
 *
 * Every field is optional because every one of them is optional in what Emby returns —
 * a remux carries the lot, something transcoded by hand may carry almost none. The UI
 * lists whichever are present rather than printing "未知" a dozen times.
 */
data class VideoStreamInfo(
    val displayTitle: String? = null,
    val language: String? = null,
    val codec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val bitrateBps: Int? = null,
    val videoRange: String? = null,
    val interlaced: Boolean? = null,
    val colorPrimaries: String? = null,
    val colorSpace: String? = null,
    val profile: String? = null,
    val level: Double? = null,
    val aspectRatio: String? = null,
    val bitDepth: Int? = null,
    /** 5 / 7 / 8 / 9, or null when the file is not Dolby Vision (or nobody said). */
    val dolbyProfile: Int? = null,
    /** Emby's `DvBlSignalCompatibilityId`: 1 = HDR10 base, 2 = SDR, 4 = HLG, 0 = none. */
    val dolbyBaseLayerCompatibility: Int? = null,
    /** Dolby Vision metadata flags as exposed by Jellyfin/Emby probing. */
    val dolbyRpuPresent: Boolean? = null,
    val dolbyEnhancementLayerPresent: Boolean? = null,
    val dolbyBaseLayerPresent: Boolean? = null,
) {
    val resolutionLabel: String?
        get() = if (width != null && height != null) "${width}x$height" else null

    /** `25.00fps` — two decimals, because 23.976 and 24 are a meaningful difference. */
    val frameRateLabel: String?
        get() =
            frameRate?.takeIf { it > 0 }?.let { rate ->
                val hundredths = (rate * 100).toLong()
                "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}fps"
            }
}

/** One audio stream of a [MediaVersion]. */
data class AudioTrackInfo(
    val codec: String?,
    /** `7.1` / `2.0`, from Emby's channel layout or channel count. */
    val channels: String?,
    /** Already resolved to a human name where the code is one we know. */
    val language: String?,
    val displayTitle: String? = null,
    val displayLanguage: String? = null,
    val profile: String? = null,
    val bitrateBps: Int? = null,
    val channelCount: Int? = null,
    val sampleRateHz: Int? = null,
    val external: Boolean? = null,
    val default: Boolean? = null,
) {
    /** `国语 · DTS-HD MA · 7.1` */
    val label: String
        get() =
            listOfNotNull(language, codec?.uppercase(), channels)
                .joinToString(" · ")
                .ifBlank { "未知音轨" }

    /** `192 Kbps` — audio is quoted in kilobits everywhere it is quoted at all. */
    val bitrateLabel: String?
        get() = bitrateBps?.takeIf { it > 0 }?.let { "${it / 1_000} Kbps" }

    val sampleRateLabel: String?
        get() = sampleRateHz?.takeIf { it > 0 }?.let { "$it Hz" }

    /** Lossless codecs/profile names as Emby commonly spells them. */
    val isLossless: Boolean
        get() {
            val normalizedCodec = codec?.trim()?.lowercase().orEmpty()
            val descriptor =
                listOfNotNull(codec, profile, displayTitle)
                    .joinToString(" ")
                    .lowercase()
            return normalizedCodec in setOf("truehd", "flac", "alac", "wavpack", "mlp", "pcm") ||
                normalizedCodec.startsWith("pcm_") ||
                listOf(
                    "truehd",
                    "true hd",
                    "dts-hd ma",
                    "dts hd ma",
                    "dts-ma",
                    "dts master audio",
                ).any(descriptor::contains)
        }
}

/** One subtitle stream of a [MediaVersion]. */
data class SubtitleTrackInfo(
    val index: Int? = null,
    val codec: String?,
    val language: String?,
    /** True for a track the server marks as burned into the picture. */
    val forced: Boolean = false,
    val external: Boolean = false,
    val default: Boolean = false,
) {
    val label: String
        get() =
            listOfNotNull(language, codec?.uppercase(), "强制".takeIf { forced })
                .joinToString(" · ")
                .ifBlank { "未知字幕" }

    val requiresStyledRenderer: Boolean
        get() = codec?.lowercase() in setOf("ass", "ssa", "pgs", "pgssub", "dvdsub", "dvbsub")
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
private val LANGUAGE_NAMES =
    mapOf(
        "chi" to "中文",
        "zho" to "中文",
        "zh" to "中文",
        "cmn" to "普通话",
        "yue" to "粤语",
        "eng" to "英语",
        "en" to "英语",
        "jpn" to "日语",
        "ja" to "日语",
        "kor" to "韩语",
        "ko" to "韩语",
        "fra" to "法语",
        "fre" to "法语",
        "fr" to "法语",
        "deu" to "德语",
        "ger" to "德语",
        "de" to "德语",
        "spa" to "西班牙语",
        "es" to "西班牙语",
        "rus" to "俄语",
        "ru" to "俄语",
        "ita" to "意大利语",
        "it" to "意大利语",
        "por" to "葡萄牙语",
        "pt" to "葡萄牙语",
        "tha" to "泰语",
        "vie" to "越南语",
        "ara" to "阿拉伯语",
        "und" to "未标注",
    )

fun languageDisplayName(code: String?): String? {
    val trimmed = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return LANGUAGE_NAMES[trimmed] ?: trimmed.uppercase()
}

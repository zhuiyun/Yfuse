package com.yfuse.core2.adaptive

data class YHlsPlaybackCapabilities(
    val dolbyVisionOutput: Boolean = false,
    val dolbyAtmosOutput: Boolean = false,
)

data class YHlsPlaybackSet(
    val initialVariant: YAdaptiveVariant,
    val variants: List<YAdaptiveVariant>,
    val renditions: List<YHlsRendition>,
    val selectedAudioRendition: YHlsRendition? = null,
)

/**
 * Selects one coherent HLS variant/rendition family while retaining its bitrate ladder.
 * Dolby Vision and Atmos are preferred only when the current output route can carry them.
 */
fun selectYHlsPlaybackSet(
    master: YHlsPlaylist.Master,
    conditions: YAdaptiveSelectionConditions,
    capabilities: YHlsPlaybackCapabilities,
): YHlsPlaybackSet {
    var candidates = master.variants
    val resolutionEligible = candidates.filter { it.fits(conditions) }
    if (resolutionEligible.isNotEmpty()) candidates = resolutionEligible

    val rangePreferred =
        if (capabilities.dolbyVisionOutput) {
            candidates.filter(YAdaptiveVariant::isDolbyVision)
        } else {
            candidates.filterNot(YAdaptiveVariant::isDolbyVision)
        }
    if (rangePreferred.isNotEmpty()) candidates = rangePreferred

    val audioRenditions = master.renditions.filter { it.type == YHlsRenditionType.Audio }
    val audioPreferredGroups =
        audioRenditions
            .filter { it.isDolbyAtmos == capabilities.dolbyAtmosOutput }
            .map(YHlsRendition::groupId)
            .toSet()
    val groupPreferred = candidates.filter { it.audioGroupId in audioPreferredGroups }
    if (groupPreferred.isNotEmpty()) candidates = groupPreferred

    val initial = YAdaptiveVariantSelector.select(candidates, conditions)
    val ladder =
        candidates
            .filter { candidate ->
                candidate.audioGroupId == initial.audioGroupId &&
                    candidate.videoGroupId == initial.videoGroupId &&
                    candidate.subtitleGroupId == initial.subtitleGroupId &&
                    candidate.closedCaptionsGroupId == initial.closedCaptionsGroupId
            }.ifEmpty { listOf(initial) }
            .sortedBy(YAdaptiveVariant::selectionBandwidthBitsPerSecond)

    val selectedAudio =
        initial.audioGroupId?.let { groupId ->
            val group = audioRenditions.filter { it.groupId == groupId }
            val preferred = group.filter { it.isDolbyAtmos == capabilities.dolbyAtmosOutput }
            (preferred.ifEmpty { group })
                .sortedWith(
                    compareByDescending<YHlsRendition>(YHlsRendition::default)
                        .thenByDescending(YHlsRendition::autoselect)
                        .thenBy(YHlsRendition::name),
                ).firstOrNull()
        }
    val auxiliary =
        master.renditions.filter { rendition ->
            when (rendition.type) {
                YHlsRenditionType.Audio -> rendition.groupId == initial.audioGroupId
                YHlsRenditionType.Video -> rendition.groupId == initial.videoGroupId
                YHlsRenditionType.Subtitles -> rendition.groupId == initial.subtitleGroupId
                YHlsRenditionType.ClosedCaptions -> rendition.groupId == initial.closedCaptionsGroupId
                YHlsRenditionType.Unknown -> false
            }
        }
    return YHlsPlaybackSet(
        initialVariant = initial,
        variants = ladder,
        renditions = auxiliary,
        selectedAudioRendition = selectedAudio,
    )
}

/** Renders a bounded master containing only the selected Dolby family and its adaptive ladder. */
fun buildYHlsPlaybackMaster(
    playback: YHlsPlaybackSet,
    localize: (absoluteUri: String, kind: YHlsResourceKind) -> String,
): String =
    buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:7")
        playback.renditions.forEach { rendition ->
            append("#EXT-X-MEDIA:")
            append("TYPE=").append(rendition.type.hlsName())
            append(",GROUP-ID=").appendQuoted(rendition.groupId)
            append(",NAME=").appendQuoted(rendition.name)
            rendition.language?.let { append(",LANGUAGE=").appendQuoted(it) }
            val selectedAudio = rendition == playback.selectedAudioRendition
            val default =
                if (rendition.type == YHlsRenditionType.Audio && playback.selectedAudioRendition != null) {
                    selectedAudio
                } else {
                    rendition.default
                }
            append(",DEFAULT=").append(if (default) "YES" else "NO")
            append(",AUTOSELECT=").append(if (selectedAudio || rendition.autoselect) "YES" else "NO")
            if (rendition.type == YHlsRenditionType.Subtitles) {
                append(",FORCED=").append(if (rendition.forced) "YES" else "NO")
            }
            rendition.channels?.let { append(",CHANNELS=").appendQuoted(it) }
            if (rendition.characteristics.isNotEmpty()) {
                append(",CHARACTERISTICS=").appendQuoted(rendition.characteristics.joinToString(","))
            }
            rendition.uri?.let { uri ->
                append(",URI=").appendQuoted(localize(uri, YHlsResourceKind.RenditionPlaylist))
            }
            appendLine()
        }
        playback.variants.forEach { variant ->
            append("#EXT-X-STREAM-INF:BANDWIDTH=").append(variant.bandwidthBitsPerSecond)
            variant.averageBandwidthBitsPerSecond?.let { append(",AVERAGE-BANDWIDTH=").append(it) }
            if (variant.width != null && variant.height != null) {
                append(",RESOLUTION=").append(variant.width).append('x').append(variant.height)
            }
            variant.frameRate?.let { append(",FRAME-RATE=").append(it) }
            if (variant.codecs.isNotEmpty()) append(",CODECS=").appendQuoted(variant.codecs.joinToString(","))
            variant.audioGroupId?.let { append(",AUDIO=").appendQuoted(it) }
            variant.videoGroupId?.let { append(",VIDEO=").appendQuoted(it) }
            variant.subtitleGroupId?.let { append(",SUBTITLES=").appendQuoted(it) }
            variant.closedCaptionsGroupId?.let { append(",CLOSED-CAPTIONS=").appendQuoted(it) }
            variant.videoRange.hlsName()?.let { append(",VIDEO-RANGE=").append(it) }
            variant.stableVariantId?.let { append(",STABLE-VARIANT-ID=").appendQuoted(it) }
            appendLine()
            appendLine(localize(variant.uri, YHlsResourceKind.VariantPlaylist))
        }
    }.trimEnd()

private fun YAdaptiveVariant.fits(conditions: YAdaptiveSelectionConditions): Boolean =
    (conditions.maximumWidth == null || width == null || width <= conditions.maximumWidth) &&
        (conditions.maximumHeight == null || height == null || height <= conditions.maximumHeight)

private fun YHlsRenditionType.hlsName(): String =
    when (this) {
        YHlsRenditionType.Audio -> "AUDIO"
        YHlsRenditionType.Video -> "VIDEO"
        YHlsRenditionType.Subtitles -> "SUBTITLES"
        YHlsRenditionType.ClosedCaptions -> "CLOSED-CAPTIONS"
        YHlsRenditionType.Unknown -> error("Unknown HLS rendition cannot be rendered")
    }

private fun YHlsVideoRange.hlsName(): String? =
    when (this) {
        YHlsVideoRange.Sdr -> "SDR"
        YHlsVideoRange.Pq -> "PQ"
        YHlsVideoRange.Hlg -> "HLG"
        YHlsVideoRange.Unknown -> null
    }

private fun StringBuilder.appendQuoted(value: String): StringBuilder {
    require(value.none { it == '\r' || it == '\n' || it == '"' }) { "Unsafe HLS attribute" }
    return append('"').append(value).append('"')
}

package com.yfuse.core2.adaptive

data class YDashPlaybackSelection(
    val video: YDashRepresentation,
    val audio: YDashRepresentation?,
    val alternateAudio: List<YDashRepresentation> = emptyList(),
    val text: List<YDashRepresentation> = emptyList(),
)

data class YDashPlaybackCapabilities(
    val dolbyVisionOutput: Boolean = false,
    val dolbyAtmosOutput: Boolean = false,
)

enum class YDashResourceKind {
    Initialization,
    MediaTemplate,
}

/** Selects one bounded video representation and at most one audio representation. */
fun selectYDashPlaybackRepresentations(
    manifest: YDashManifest,
    conditions: YAdaptiveSelectionConditions,
    capabilities: YDashPlaybackCapabilities = YDashPlaybackCapabilities(),
): YDashPlaybackSelection {
    var videos = manifest.representations.filter { it.contentType == YDashContentType.Video }
    require(videos.isNotEmpty()) { "DASH manifest has no video representation" }
    val preferredVideos = videos.filter { it.isDolbyVision == capabilities.dolbyVisionOutput }
    if (preferredVideos.isNotEmpty()) videos = preferredVideos
    val video = YAdaptiveVariantSelector.select(videos.map(YDashRepresentation::asAdaptiveVariant), conditions)
    val selectedVideo = videos.first { it.id == video.id }
    var selectedAudioTracks =
        manifest.representations
            .filter { it.contentType == YDashContentType.Audio && it.segmentTemplate != null }
            .groupBy(YDashRepresentation::audioTrackIdentity)
            .values
            .map { tracks -> tracks.maxBy(YDashRepresentation::bandwidthBitsPerSecond) }
            .sortedWith(
                compareBy<YDashRepresentation> { it.language.orEmpty() }
                    .thenByDescending(YDashRepresentation::bandwidthBitsPerSecond),
            ).take(MAX_DASH_AUDIO_TRACKS)
    val preferredAudio = selectedAudioTracks.filter { it.isDolbyAtmos == capabilities.dolbyAtmosOutput }
    if (preferredAudio.isNotEmpty()) selectedAudioTracks = preferredAudio
    val selectedAudio = selectedAudioTracks.maxByOrNull(YDashRepresentation::bandwidthBitsPerSecond)
    val text =
        manifest.representations
            .filter { it.contentType == YDashContentType.Text && it.segmentTemplate != null }
            .distinctBy(YDashRepresentation::textTrackIdentity)
            .take(MAX_DASH_TEXT_TRACKS)
    return YDashPlaybackSelection(
        video = selectedVideo,
        audio = selectedAudio,
        alternateAudio = selectedAudioTracks.filterNot { it.id == selectedAudio?.id },
        text = text,
    )
}

/**
 * Emits the small, static SegmentTemplate MPD subset executed by YCore's loopback boundary.
 * Unknown source XML is intentionally not copied into the executable manifest.
 */
fun buildYDashPlaybackManifest(
    manifest: YDashManifest,
    selection: YDashPlaybackSelection,
    allowContentProtection: Boolean = false,
    localize: (representation: YDashRepresentation, template: String, kind: YDashResourceKind) -> String,
): String {
    val durationUs =
        if (manifest.isLive) {
            manifest.mediaPresentationDurationUs
        } else {
            requireNotNull(manifest.mediaPresentationDurationUs) { "Static DASH duration is missing" }
        }
    val representations =
        buildList {
            add(selection.video)
            selection.audio?.let(::add)
            addAll(selection.alternateAudio)
            addAll(selection.text)
        }
    require(representations.distinctBy(YDashRepresentation::id).size == representations.size) {
        "DASH selected representation ids are not unique"
    }
    representations.forEach { representation ->
        require(representation in manifest.representations) { "DASH selection is outside the parsed manifest" }
        require(allowContentProtection || representation.contentProtections.isEmpty()) {
            "Protected DASH requires the native DRM route"
        }
        requireNotNull(representation.segmentTemplate) { "DASH SegmentTemplate is required" }
    }
    return buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xmlns:cenc=\"urn:mpeg:cenc:2013\"")
        appendXmlAttribute("type", if (manifest.isLive) "dynamic" else "static")
        durationUs?.let { appendXmlAttribute("mediaPresentationDuration", it.toDashDuration()) }
        manifest.minimumUpdatePeriodUs?.let { appendXmlAttribute("minimumUpdatePeriod", it.toDashDuration()) }
        manifest.availabilityStartTime?.let { appendXmlAttribute("availabilityStartTime", it) }
        manifest.publishTime?.let { appendXmlAttribute("publishTime", it) }
        manifest.timeShiftBufferDepthUs?.let { appendXmlAttribute("timeShiftBufferDepth", it.toDashDuration()) }
        manifest.suggestedPresentationDelayUs?.let {
            appendXmlAttribute("suggestedPresentationDelay", it.toDashDuration())
        }
        appendXmlAttribute("minBufferTime", "PT1.5S")
        appendXmlAttribute(
            "profiles",
            if (manifest.isLive) {
                "urn:mpeg:dash:profile:isoff-live:2011"
            } else {
                "urn:mpeg:dash:profile:isoff-on-demand:2011"
            },
        )
        append(">\n")
        append("  <Period")
        appendXmlAttribute("start", (manifest.periodStartUs ?: 0L).toDashDuration())
        append(">\n")
        representations.forEach { representation ->
            appendRepresentation(representation, allowContentProtection, manifest.isLive, localize)
        }
        append("  </Period>\n")
        append("</MPD>\n")
    }
}

private fun StringBuilder.appendRepresentation(
    representation: YDashRepresentation,
    allowContentProtection: Boolean,
    live: Boolean,
    localize: (YDashRepresentation, String, YDashResourceKind) -> String,
) {
    val template = requireNotNull(representation.segmentTemplate)
    append("    <AdaptationSet contentType=\"")
    append(representation.contentType.xmlValue())
    append("\"")
    representation.mimeType?.let { appendXmlAttribute("mimeType", it) }
    representation.language?.let { appendXmlAttribute("lang", it) }
    append(" segmentAlignment=\"true\">\n")
    if (allowContentProtection) {
        representation.contentProtections.forEach(::appendContentProtection)
    }
    representation.supplementalProperties.forEach(::appendSupplementalProperty)
    append("      <SegmentTemplate")
    appendXmlAttribute("timescale", template.timescale.toString())
    template.duration?.let { appendXmlAttribute("duration", it.toString()) }
    appendXmlAttribute("startNumber", template.startNumber.toString())
    template.initialization?.let {
        appendXmlAttribute(
            "initialization",
            localize(representation, it, YDashResourceKind.Initialization).requireSafeDashUri(),
        )
    }
    appendXmlAttribute(
        "media",
        localize(representation, template.media, YDashResourceKind.MediaTemplate).requireSafeDashUri(),
    )
    if (template.timeline.isEmpty()) {
        append("/>\n")
    } else {
        append(">\n")
        append("        <SegmentTimeline>\n")
        template.timeline.forEach { entry ->
            require(entry.repeat >= 0 || live) { "Open-ended DASH timeline requires a dynamic presentation" }
            append("          <S")
            entry.startTime?.let { appendXmlAttribute("t", it.toString()) }
            appendXmlAttribute("d", entry.duration.toString())
            if (entry.repeat != 0) appendXmlAttribute("r", entry.repeat.toString())
            append("/>\n")
        }
        append("        </SegmentTimeline>\n")
        append("      </SegmentTemplate>\n")
    }
    append("      <Representation")
    appendXmlAttribute("id", representation.id)
    appendXmlAttribute("bandwidth", representation.bandwidthBitsPerSecond.toString())
    if (representation.codecs.isNotEmpty()) appendXmlAttribute("codecs", representation.codecs.joinToString(","))
    representation.width?.let { appendXmlAttribute("width", it.toString()) }
    representation.height?.let { appendXmlAttribute("height", it.toString()) }
    representation.frameRate?.let { appendXmlAttribute("frameRate", it.toString()) }
    representation.audioSamplingRate?.let { appendXmlAttribute("audioSamplingRate", it.toString()) }
    append("/>\n")
    append("    </AdaptationSet>\n")
}

private fun StringBuilder.appendContentProtection(protection: YDashContentProtection) {
    append("      <ContentProtection")
    appendXmlAttribute("schemeIdUri", protection.schemeIdUri)
    protection.value?.let { appendXmlAttribute("value", it) }
    protection.defaultKeyId?.let { appendXmlAttribute("cenc:default_KID", it) }
    val pssh = protection.psshBase64
    if (pssh.isNullOrBlank()) {
        append("/>\n")
    } else {
        append("><cenc:pssh>")
        append(pssh.escapeXml())
        append("</cenc:pssh></ContentProtection>\n")
    }
}

private fun StringBuilder.appendSupplementalProperty(descriptor: YDashDescriptor) {
    append("      <SupplementalProperty")
    appendXmlAttribute("schemeIdUri", descriptor.schemeIdUri)
    descriptor.value?.let { appendXmlAttribute("value", it) }
    append("/>\n")
}

private fun StringBuilder.appendXmlAttribute(
    name: String,
    value: String,
) {
    append(' ')
    append(name)
    append("=\"")
    append(value.escapeXml())
    append('"')
}

private fun String.escapeXml(): String =
    replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "&apos;")

private fun String.requireSafeDashUri(): String =
    also { require(isNotBlank() && '\r' !in this && '\n' !in this) { "Unsafe localized DASH URI" } }

private fun YDashContentType.xmlValue(): String =
    when (this) {
        YDashContentType.Video -> "video"
        YDashContentType.Audio -> "audio"
        YDashContentType.Text -> "text"
        YDashContentType.Unknown -> error("Unknown DASH content type is not executable")
    }

private fun YDashRepresentation.audioTrackIdentity(): String =
    listOf(
        language.orEmpty().lowercase(),
        mimeType.orEmpty().lowercase(),
        codecs.joinToString(",") { it.substringBefore('.').lowercase() },
        if (isDolbyAtmos) "atmos" else "base",
    ).joinToString("|")

private fun YDashRepresentation.textTrackIdentity(): String =
    listOf(language.orEmpty().lowercase(), mimeType.orEmpty().lowercase(), codecs.joinToString(",")).joinToString("|")

private fun Long.toDashDuration(): String {
    val seconds = this / MICROS_PER_SECOND
    val micros = this % MICROS_PER_SECOND
    return if (micros == 0L) {
        "PT${seconds}S"
    } else {
        "PT$seconds.${micros.toString().padStart(6, '0').trimEnd('0')}S"
    }
}

private const val MICROS_PER_SECOND = 1_000_000L
private const val MAX_DASH_AUDIO_TRACKS = 16
private const val MAX_DASH_TEXT_TRACKS = 32

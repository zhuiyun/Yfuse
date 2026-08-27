package com.yfuse.core2.adaptive

data class YDashPlaybackSelection(
    val video: YDashRepresentation,
    val audio: YDashRepresentation?,
)

enum class YDashResourceKind {
    Initialization,
    MediaTemplate,
}

/** Selects one bounded video representation and at most one audio representation. */
fun selectYDashPlaybackRepresentations(
    manifest: YDashManifest,
    conditions: YAdaptiveSelectionConditions,
): YDashPlaybackSelection {
    val videos = manifest.representations.filter { it.contentType == YDashContentType.Video }
    require(videos.isNotEmpty()) { "DASH manifest has no video representation" }
    val video = YAdaptiveVariantSelector.select(videos.map(YDashRepresentation::asAdaptiveVariant), conditions)
    val selectedVideo = videos.first { it.id == video.id }
    val selectedAudio =
        manifest.representations
            .filter { it.contentType == YDashContentType.Audio }
            .maxByOrNull(YDashRepresentation::bandwidthBitsPerSecond)
    return YDashPlaybackSelection(video = selectedVideo, audio = selectedAudio)
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
    require(!manifest.isLive) { "Dynamic DASH requires the live-window controller" }
    val durationUs = requireNotNull(manifest.mediaPresentationDurationUs) { "Static DASH duration is missing" }
    val representations = listOfNotNull(selection.video, selection.audio)
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
        append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xmlns:cenc=\"urn:mpeg:cenc:2013\" type=\"static\"")
        append(" mediaPresentationDuration=\"")
        append(durationUs.toDashDuration())
        append("\" minBufferTime=\"PT1.5S\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n")
        append("  <Period start=\"PT0S\">\n")
        representations.forEach { representation ->
            appendRepresentation(representation, allowContentProtection, localize)
        }
        append("  </Period>\n")
        append("</MPD>\n")
    }
}

private fun StringBuilder.appendRepresentation(
    representation: YDashRepresentation,
    allowContentProtection: Boolean,
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
            require(entry.repeat >= 0) { "Open-ended DASH timeline requires the live-window controller" }
            append("          <S")
            entry.startTime?.let { appendXmlAttribute("t", it.toString()) }
            appendXmlAttribute("d", entry.duration.toString())
            if (entry.repeat > 0) appendXmlAttribute("r", entry.repeat.toString())
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

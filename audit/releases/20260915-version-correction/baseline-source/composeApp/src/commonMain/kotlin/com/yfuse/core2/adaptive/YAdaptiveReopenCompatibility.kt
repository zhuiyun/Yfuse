package com.yfuse.core2.adaptive

/** Broader than seamless switching: these candidates require a fresh demuxer and decoder. */
fun compatibleYHlsReopenVariants(
    selected: YHlsVariantMediaPlaylist,
    candidates: List<YHlsVariantMediaPlaylist>,
): List<YHlsVariantMediaPlaylist> =
    candidates.filter { candidate ->
        candidate.variant.id == selected.variant.id ||
            !selected.playlist.isLive &&
            !candidate.playlist.isLive &&
            selected.variant.codecs.isNotEmpty() &&
            candidate.variant.codecs.reopenCodecFamilies() == selected.variant.codecs.reopenCodecFamilies() &&
            candidate.variant.supplementalCodecs == selected.variant.supplementalCodecs &&
            candidate.variant.videoRange == selected.variant.videoRange &&
            candidate.variant.audioGroupId == selected.variant.audioGroupId &&
            candidate.variant.videoGroupId == selected.variant.videoGroupId &&
            candidate.variant.subtitleGroupId == selected.variant.subtitleGroupId &&
            candidate.variant.closedCaptionsGroupId == selected.variant.closedCaptionsGroupId &&
            candidate.playlist.segments.size == selected.playlist.segments.size &&
            candidate.playlist.segments.zip(selected.playlist.segments).all { (next, current) ->
                next.sequence == current.sequence &&
                    next.startTimeUs == current.startTimeUs &&
                    next.durationUs == current.durationUs &&
                    next.discontinuity == current.discontinuity &&
                    next.encryption == current.encryption
            }
    }

/** Encryption and the authored timeline remain fixed even when initialization data changes. */
fun compatibleYDashReopenRepresentations(
    selected: YDashRepresentation,
    candidates: List<YDashRepresentation>,
): List<YDashRepresentation> {
    val reference = selected.segmentTemplate ?: return listOf(selected)
    return candidates.filter { candidate ->
        val template = candidate.segmentTemplate
        candidate.id == selected.id ||
            template != null &&
            selected.codecs.isNotEmpty() &&
            candidate.contentType == YDashContentType.Video &&
            candidate.codecs.reopenCodecFamilies() == selected.codecs.reopenCodecFamilies() &&
            candidate.mimeType == selected.mimeType &&
            candidate.contentProtections == selected.contentProtections &&
            candidate.supplementalProperties == selected.supplementalProperties &&
            template.timescale == reference.timescale &&
            template.duration == reference.duration &&
            template.timeline == reference.timeline &&
            template.startNumber == reference.startNumber &&
            template.presentationTimeOffset == reference.presentationTimeOffset
    }
}

private fun List<String>.reopenCodecFamilies(): List<String> = map { it.trim().lowercase().substringBefore('.') }

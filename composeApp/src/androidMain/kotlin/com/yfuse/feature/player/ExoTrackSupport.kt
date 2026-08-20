package com.yfuse.feature.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.HlsTrackMetadataEntry

internal enum class UnsupportedMediaTrack { Audio, Video }

/**
 * Chooses the next recovery step without letting ExoPlayer continue with a silent track.
 *
 * Unsupported video first stays on Media3's working network path and asks the server for a
 * compatible stream. Unsupported audio moves to the already bundled FFmpeg engine so EAC3,
 * TrueHD and DTS can be decoded locally without duplicating FFmpeg in the APK.
 */
internal enum class UnsupportedTrackRecovery { SwitchEngine, ServerTranscode }

internal fun unsupportedTrackRecovery(
    track: UnsupportedMediaTrack,
    alreadyTranscoding: Boolean,
): UnsupportedTrackRecovery =
    when (track) {
        // MPV already bundles FFmpeg audio decoders. Moving unsupported Dolby/DTS audio there
        // preserves the original video and avoids waking a server transcode that may copy the
        // same incompatible audio track. No second FFmpeg payload is needed in the APK.
        UnsupportedMediaTrack.Audio -> UnsupportedTrackRecovery.SwitchEngine
        UnsupportedMediaTrack.Video ->
            if (alreadyTranscoding) {
                UnsupportedTrackRecovery.SwitchEngine
            } else {
                UnsupportedTrackRecovery.ServerTranscode
            }
    }

internal fun unsupportedMediaTrack(
    hasVideo: Boolean,
    videoSupported: Boolean,
    hasAudio: Boolean,
    audioSupported: Boolean,
): UnsupportedMediaTrack? =
    when {
        hasAudio && !audioSupported -> UnsupportedMediaTrack.Audio
        hasVideo && !videoSupported -> UnsupportedMediaTrack.Video
        else -> null
    }

/** A track before repeated HLS rendition declarations have been collapsed. */
internal data class ManifestTrackCandidate(
    val id: String,
    val label: String,
    val language: String?,
    val selected: Boolean,
    /** EXT-X-MEDIA identity; null for direct files and manifests without rendition metadata. */
    val manifestGroupId: String?,
    val manifestName: String?,
    /** Codec/channel hint used only when two genuine tracks would otherwise look identical. */
    val qualifier: String? = null,
    val codec: String? = null,
)

/** Collapse only tracks proven to be repeated declarations of one HLS rendition. */
internal fun collapseManifestTrackDuplicates(candidates: List<ManifestTrackCandidate>): List<EngineTrack> {
    val collapsed = mutableListOf<ManifestTrackCandidate>()
    val renditionIndices = mutableMapOf<Pair<String, String>, Int>()
    candidates.forEach { candidate ->
        val group = candidate.manifestGroupId?.takeIf(String::isNotBlank)
        val name = candidate.manifestName?.takeIf(String::isNotBlank)
        val rendition = if (group != null && name != null) group to name else null
        val existingIndex = rendition?.let(renditionIndices::get)
        if (existingIndex == null) {
            rendition?.let { renditionIndices[it] = collapsed.size }
            collapsed += candidate
        } else {
            val existing = collapsed[existingIndex]
            if (candidate.selected && !existing.selected) {
                collapsed[existingIndex] = existing.copy(id = candidate.id, selected = true)
            }
        }
    }

    val labelCounts = collapsed.groupingBy { it.label }.eachCount()
    val labelOrdinals = mutableMapOf<String, Int>()
    val uniqueQualifiers =
        collapsed.groupBy { it.label }.mapValues { (_, group) ->
            val qualifiers = group.mapNotNull { it.qualifier?.takeIf(String::isNotBlank) }
            qualifiers.size == group.size && qualifiers.distinct().size == group.size
        }
    return collapsed.map { candidate ->
        val repeatedLabel = (labelCounts[candidate.label] ?: 0) > 1
        val label =
            when {
                !repeatedLabel -> candidate.label
                uniqueQualifiers[candidate.label] == true -> "${candidate.label} · ${candidate.qualifier}"
                else -> {
                    val ordinal = (labelOrdinals[candidate.label] ?: 0) + 1
                    labelOrdinals[candidate.label] = ordinal
                    "${candidate.label} $ordinal"
                }
            }
        EngineTrack(
            id = candidate.id,
            label = label,
            language = candidate.language,
            selected = candidate.selected,
            codec = candidate.codec,
        )
    }
}

@UnstableApi
internal fun Tracks.toEngineTracks(
    type: Int,
    fallbackPrefix: String,
): List<EngineTrack> {
    var ordinal = 0
    val candidates = mutableListOf<ManifestTrackCandidate>()
    groups
        .withIndex()
        .filter { (_, group) -> group.type == type }
        .forEach { (groupIndex, group) ->
            (0 until group.length).forEach { trackIndex ->
                val format = group.getTrackFormat(trackIndex)
                ordinal++
                val rendition = format.hlsRenditionIdentity()
                candidates +=
                    ManifestTrackCandidate(
                        id = "$groupIndex:$trackIndex",
                        label =
                            format.label
                                ?: rendition?.second
                                ?: format.language
                                ?: "$fallbackPrefix $ordinal",
                        language = format.language,
                        selected = group.isTrackSelected(trackIndex),
                        manifestGroupId = rendition?.first,
                        manifestName = rendition?.second,
                        qualifier = format.trackQualifier(type),
                        codec = format.sampleMimeType?.substringAfterLast('/') ?: format.codecs,
                    )
            }
        }
    return collapseManifestTrackDuplicates(candidates)
}

@UnstableApi
internal fun Format.hlsRenditionIdentity(): Pair<String, String>? {
    val entries = metadata ?: return null
    for (index in 0 until entries.length()) {
        val rendition = entries[index] as? HlsTrackMetadataEntry ?: continue
        val group = rendition.groupId?.takeIf(String::isNotBlank) ?: continue
        val name = rendition.name?.takeIf(String::isNotBlank) ?: continue
        return group to name
    }
    return null
}

internal fun Format.trackQualifier(type: Int): String? {
    val codec =
        codecs
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.uppercase()
            ?: sampleMimeType
                ?.substringAfterLast('/')
                ?.takeIf(String::isNotBlank)
                ?.uppercase()
    return when (type) {
        C.TRACK_TYPE_AUDIO ->
            listOfNotNull(
                codec,
                channelCount.takeIf { it > 0 }?.let { "$it 声道" },
            ).joinToString(" · ").takeIf(String::isNotBlank)
        C.TRACK_TYPE_TEXT -> codec
        else -> codec
    }
}

@UnstableApi
internal fun Format.dynamicRangeLabel(): String =
    when {
        sampleMimeType.equals("video/dolby-vision", ignoreCase = true) -> "Dolby Vision"
        codecs.orEmpty().contains("dvhe", ignoreCase = true) ||
            codecs.orEmpty().contains("dvh1", ignoreCase = true) -> "Dolby Vision"
        colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10 / PQ"
        colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        colorInfo != null -> "SDR"
        else -> ""
    }

internal fun Format.audioFormatLabel(): String {
    val codec =
        (codecs ?: sampleMimeType?.substringAfterLast('/'))
            ?.uppercase()
            ?.takeIf(String::isNotBlank)
    val channels =
        channelCount.takeIf { it > 0 }?.let { count ->
            when (count) {
                1 -> "单声道"
                2 -> "2.0"
                6 -> "5.1"
                8 -> "7.1"
                else -> "$count 声道"
            }
        }
    return listOfNotNull(codec, channels).joinToString(" · ")
}

package com.yfuse.core.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** JSON envelope returned by Plex Media Server when `Accept: application/json` is used. */
@Serializable
data class PlexResponseDto(
    val MediaContainer: PlexMediaContainerDto = PlexMediaContainerDto(),
)

@Serializable
data class PlexMediaContainerDto(
    val size: Int = 0,
    val totalSize: Int? = null,
    val offset: Int? = null,
    val machineIdentifier: String? = null,
    val friendlyName: String? = null,
    val myPlexUsername: String? = null,
    val version: String? = null,
    val Directory: List<PlexMetadataDto> = emptyList(),
    val Metadata: List<PlexMetadataDto> = emptyList(),
    val Hub: List<PlexHubDto> = emptyList(),
)

@Serializable
data class PlexHubDto(
    val title: String? = null,
    val type: String? = null,
    val Metadata: List<PlexMetadataDto> = emptyList(),
)

/**
 * Plex uses the same broad metadata shape for libraries, titles, seasons and episodes.
 * Optional fields keep the adapter tolerant of server-version and agent differences.
 */
@Serializable
data class PlexMetadataDto(
    val key: String? = null,
    val ratingKey: String? = null,
    val type: String? = null,
    val title: String? = null,
    val titleSort: String? = null,
    val summary: String? = null,
    val year: Int? = null,
    val index: Int? = null,
    val parentIndex: Int? = null,
    val parentRatingKey: String? = null,
    val grandparentRatingKey: String? = null,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val thumb: String? = null,
    val art: String? = null,
    val parentThumb: String? = null,
    val grandparentThumb: String? = null,
    val parentArt: String? = null,
    val grandparentArt: String? = null,
    val duration: Long? = null,
    val viewOffset: Long? = null,
    val viewCount: Int? = null,
    val lastViewedAt: Long? = null,
    val addedAt: Long? = null,
    val updatedAt: Long? = null,
    val originallyAvailableAt: String? = null,
    val contentRating: String? = null,
    val audienceRating: Double? = null,
    val rating: Double? = null,
    val leafCount: Int? = null,
    val viewedLeafCount: Int? = null,
    val childCount: Int? = null,
    val guid: String? = null,
    val Guid: List<PlexGuidDto> = emptyList(),
    val Genre: List<PlexTagDto> = emptyList(),
    val Role: List<PlexTagDto> = emptyList(),
    val Media: List<PlexMediaDto> = emptyList(),
    val Marker: List<PlexMarkerDto> = emptyList(),
)

@Serializable
data class PlexGuidDto(
    val id: String = "",
)

@Serializable
data class PlexTagDto(
    val id: Long? = null,
    val key: String? = null,
    val tag: String = "",
    val role: String? = null,
    val thumb: String? = null,
)

@Serializable
data class PlexMediaDto(
    val id: Long? = null,
    val duration: Long? = null,
    val bitrate: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val aspectRatio: Double? = null,
    val audioChannels: Int? = null,
    val audioCodec: String? = null,
    val videoCodec: String? = null,
    val videoResolution: String? = null,
    val videoFrameRate: String? = null,
    val container: String? = null,
    val optimizedForStreaming: Boolean? = null,
    val videoProfile: String? = null,
    val audioProfile: String? = null,
    val has64bitOffsets: Boolean? = null,
    val Part: List<PlexPartDto> = emptyList(),
)

@Serializable
data class PlexPartDto(
    val id: Long? = null,
    val key: String? = null,
    val duration: Long? = null,
    val file: String? = null,
    val size: Long? = null,
    val container: String? = null,
    val videoProfile: String? = null,
    val audioProfile: String? = null,
    val Stream: List<PlexStreamDto> = emptyList(),
)

@Serializable
data class PlexStreamDto(
    val id: Long? = null,
    val key: String? = null,
    val index: Int? = null,
    val streamType: Int? = null,
    val codec: String? = null,
    val language: String? = null,
    val languageCode: String? = null,
    val title: String? = null,
    val displayTitle: String? = null,
    val extendedDisplayTitle: String? = null,
    val selected: Boolean? = null,
    val default: Boolean? = null,
    val forced: Boolean? = null,
    val channels: Int? = null,
    val bitrate: Int? = null,
    val bitDepth: Int? = null,
    val samplingRate: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val profile: String? = null,
    val level: Double? = null,
    val colorSpace: String? = null,
    val colorPrimaries: String? = null,
    val colorTrc: String? = null,
    @SerialName("DOVIPresent") val doviPresent: Boolean? = null,
    @SerialName("DOVIProfile") val doviProfile: Int? = null,
    @SerialName("DOVILevel") val doviLevel: Int? = null,
    @SerialName("DOVIBLPresent") val doviBaseLayerPresent: Boolean? = null,
    @SerialName("DOVIELPresent") val doviEnhancementLayerPresent: Boolean? = null,
    @SerialName("DOVIRPUPresent") val doviRpuPresent: Boolean? = null,
)

@Serializable
data class PlexMarkerDto(
    val type: String? = null,
    val startTimeOffset: Long? = null,
    val endTimeOffset: Long? = null,
)

package com.yfuse.core.data.dto

import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.Person
import com.yfuse.core.model.Season
import com.yfuse.core.model.SourceInfo
import kotlinx.serialization.Serializable

@Serializable
data class PublicInfoDto(
    val ServerName: String? = null,
    val Version: String? = null,
    val Id: String? = null,
)

@Serializable
data class AuthRequestDto(val Username: String, val Pw: String)

@Serializable
data class AuthResultDto(val AccessToken: String, val User: AuthUserDto)

@Serializable
data class AuthUserDto(val Id: String, val Name: String)

@Serializable
data class ViewsDto(val Items: List<ViewItemDto> = emptyList())

@Serializable
data class ViewItemDto(
    val Id: String,
    val Name: String,
    val CollectionType: String? = null,
)

@Serializable
data class UserDataDto(
    val PlayedPercentage: Double? = null,
    val PlaybackPositionTicks: Long? = null,
    val Played: Boolean? = null,
)

@Serializable
data class PersonDto(
    val Id: String,
    val Name: String? = null,
    val Role: String? = null,
    val Type: String? = null,
    val PrimaryImageTag: String? = null,
)

@Serializable
data class MediaStreamDto(
    val Type: String? = null,
    val Height: Int? = null,
    val Width: Int? = null,
    val VideoRange: String? = null,
)

/** Backs the 跨服务器片源对比 column: container size, bitrate and video resolution. */
@Serializable
data class MediaSourceDto(
    val Size: Long? = null,
    val Bitrate: Int? = null,
    val MediaStreams: List<MediaStreamDto>? = null,
)

@Serializable
data class BaseItemDto(
    val Id: String,
    val Name: String? = null,
    val Type: String? = null,
    val ProductionYear: Int? = null,
    val IndexNumber: Int? = null,
    val ParentIndexNumber: Int? = null,
    val SeriesName: String? = null,
    val SeriesId: String? = null,
    val SeriesPrimaryImageTag: String? = null,
    val SeasonId: String? = null,
    val ParentBackdropItemId: String? = null,
    val ParentBackdropImageTags: List<String>? = null,
    val Overview: String? = null,
    val Genres: List<String>? = null,
    val RunTimeTicks: Long? = null,
    val CommunityRating: Double? = null,
    val OfficialRating: String? = null,
    val People: List<PersonDto>? = null,
    val ImageTags: Map<String, String>? = null,
    val BackdropImageTags: List<String>? = null,
    val UserData: UserDataDto? = null,
    val MediaSources: List<MediaSourceDto>? = null,
    val ProviderIds: Map<String, String>? = null,
)

/** Resume (and most list endpoints) wrap items; `Items/Latest` returns a raw array. */
@Serializable
data class ItemsResponseDto(
    val Items: List<BaseItemDto> = emptyList(),
    /** Full size of the matching set, independent of `Limit`. */
    val TotalRecordCount: Int = 0,
)

/** Minimal Emby playback-session payload shared by start/progress/stop calls. */
@Serializable
data class PlaybackReportDto(
    val ItemId: String,
    val PlaySessionId: String,
    val PositionTicks: Long,
    val IsPaused: Boolean,
    val IsMuted: Boolean = false,
    val CanSeek: Boolean = true,
    val PlayMethod: String = "DirectPlay",
)

fun BaseItemDto.toMediaItem(): MediaItem {
    val isEpisode = Type == "Episode"
    val useSeriesPoster = isEpisode && SeriesId != null && SeriesPrimaryImageTag != null

    val title = if (isEpisode) (SeriesName ?: Name ?: "") else (Name ?: "")
    val subtitle = when {
        isEpisode -> buildString {
            if (ParentIndexNumber != null && IndexNumber != null) append("S${ParentIndexNumber}E$IndexNumber ")
            append(Name ?: "")
        }.trim().ifBlank { null }
        ProductionYear != null -> ProductionYear.toString()
        else -> null
    }

    return MediaItem(
        id = Id,
        title = title,
        subtitle = subtitle,
        type = Type ?: "",
        posterItemId = if (useSeriesPoster) SeriesId!! else Id,
        posterTag = if (useSeriesPoster) SeriesPrimaryImageTag else ImageTags?.get("Primary"),
        backdropItemId = Id,
        backdropTag = BackdropImageTags?.firstOrNull(),
        playedPercentage = UserData?.PlayedPercentage,
        overview = Overview,
        year = ProductionYear,
        providerIds = ProviderIds.orEmpty(),
    )
}

fun BaseItemDto.toMediaDetail(): MediaDetail {
    // Episodes usually carry no backdrop of their own, but do reference the
    // series' backdrop/poster — fall back to those so the hero is never blank.
    val ownBackdrop = BackdropImageTags?.firstOrNull()
    val parentBackdrop = ParentBackdropImageTags?.firstOrNull()
    val backdropId = if (ownBackdrop != null) Id else ParentBackdropItemId ?: SeriesId ?: Id
    val backdropTag = ownBackdrop ?: parentBackdrop

    val ownPoster = ImageTags?.get("Primary")
    val posterId = if (ownPoster != null) Id else SeriesId ?: Id
    val posterTag = ownPoster ?: SeriesPrimaryImageTag

    return MediaDetail(
        id = Id,
        title = if (Type == "Episode") "${SeriesName ?: ""} ${Name ?: ""}".trim() else (Name ?: ""),
        type = Type ?: "",
        seriesId = SeriesId,
        overview = Overview,
        year = ProductionYear,
        genres = Genres ?: emptyList(),
        runtimeMinutes = RunTimeTicks?.let { (it / 600_000_000L).toInt() }?.takeIf { it > 0 },
        officialRating = OfficialRating,
        communityRating = CommunityRating,
        posterItemId = posterId,
        posterTag = posterTag,
        backdropItemId = backdropId,
        backdropTag = backdropTag,
        resumePositionTicks = UserData?.PlaybackPositionTicks,
        people = People?.map { it.toPerson() } ?: emptyList(),
        source = MediaSources?.firstOrNull()?.toSourceInfo(),
    )
}

/** `4K HDR · 42.3 GB · 68 Mbps`, from the first video stream and the container. */
fun MediaSourceDto.toSourceInfo(): SourceInfo? {
    val video = MediaStreams?.firstOrNull { it.Type == "Video" }
    val height = video?.Height
    val quality = when {
        height == null -> "未知清晰度"
        height >= 2000 -> "4K"
        height >= 1000 -> "1080P"
        height >= 700 -> "720P"
        else -> "${height}P"
    }
    val hdr = video?.VideoRange?.takeIf { !it.equals("SDR", ignoreCase = true) }
    return SourceInfo(
        quality = if (hdr != null) "$quality $hdr" else quality,
        size = Size?.takeIf { it > 0 }?.let { formatBytes(it) },
        bitrate = Bitrate?.takeIf { it > 0 }?.let { "${it / 1_000_000} Mbps" },
    )
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    if (gb >= 1.0) {
        val tenths = (gb * 10).toLong()
        return "${tenths / 10}.${tenths % 10} GB"
    }
    return "${bytes / 1024 / 1024} MB"
}

fun PersonDto.toPerson() = Person(Id, Name ?: "", Role?.ifBlank { null }, PrimaryImageTag)

fun BaseItemDto.toSeason() = Season(
    id = Id,
    name = Name ?: "第 ${IndexNumber ?: 1} 季",
    indexNumber = IndexNumber,
    posterTag = ImageTags?.get("Primary"),
)

fun BaseItemDto.toEpisode() = Episode(
    id = Id,
    name = Name ?: "",
    indexNumber = IndexNumber,
    seasonId = SeasonId,
    overview = Overview,
    runtimeMinutes = RunTimeTicks?.let { (it / 600_000_000L).toInt() }?.takeIf { it > 0 },
    primaryTag = ImageTags?.get("Primary"),
    playedPercentage = UserData?.PlayedPercentage,
    resumePositionTicks = UserData?.PlaybackPositionTicks,
)

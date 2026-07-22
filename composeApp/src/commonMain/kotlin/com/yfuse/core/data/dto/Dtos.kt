package com.yfuse.core.data.dto

import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.Person
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
    val Overview: String? = null,
    val Genres: List<String>? = null,
    val RunTimeTicks: Long? = null,
    val CommunityRating: Double? = null,
    val OfficialRating: String? = null,
    val People: List<PersonDto>? = null,
    val ImageTags: Map<String, String>? = null,
    val BackdropImageTags: List<String>? = null,
    val UserData: UserDataDto? = null,
)

/** Resume (and most list endpoints) wrap items; `Items/Latest` returns a raw array. */
@Serializable
data class ItemsResponseDto(val Items: List<BaseItemDto> = emptyList())

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
    )
}

fun BaseItemDto.toMediaDetail(): MediaDetail = MediaDetail(
    id = Id,
    title = if (Type == "Episode") "${SeriesName ?: ""} ${Name ?: ""}".trim() else (Name ?: ""),
    overview = Overview,
    year = ProductionYear,
    genres = Genres ?: emptyList(),
    runtimeMinutes = RunTimeTicks?.let { (it / 600_000_000L).toInt() }?.takeIf { it > 0 },
    officialRating = OfficialRating,
    communityRating = CommunityRating,
    posterItemId = Id,
    posterTag = ImageTags?.get("Primary"),
    backdropItemId = Id,
    backdropTag = BackdropImageTags?.firstOrNull(),
    people = People?.map { Person(it.Id, it.Name ?: "", it.Role?.ifBlank { null }, it.PrimaryImageTag) } ?: emptyList(),
)

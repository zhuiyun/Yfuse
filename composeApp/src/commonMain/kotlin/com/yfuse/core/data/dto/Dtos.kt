package com.yfuse.core.data.dto

import com.yfuse.core.model.AudioTrackInfo
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.Person
import com.yfuse.core.model.SubtitleTrackInfo
import com.yfuse.core.model.languageDisplayName
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
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
data class PublicUserDto(
    val Id: String,
    val Name: String = "",
    val HasPassword: Boolean = false,
    val PrimaryImageTag: String? = null,
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
    val IsFavorite: Boolean? = null,
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
    val Codec: String? = null,
    val Language: String? = null,
    val Title: String? = null,
    val DisplayTitle: String? = null,
    val Channels: Int? = null,
    val ChannelLayout: String? = null,
    val IsForced: Boolean? = null,
)

/**
 * One file behind a title. Several can exist for the same item — a 4K remux beside a 1080p
 * encode — which is why [Id] matters: it is what a stream request has to name to get this
 * particular one rather than whichever the server lists first.
 */
@Serializable
data class MediaSourceDto(
    val Id: String? = null,
    val Name: String? = null,
    val Container: String? = null,
    val Size: Long? = null,
    val Bitrate: Int? = null,
    val MediaStreams: List<MediaStreamDto>? = null,
)

@Serializable
data class ChapterDto(
    val StartPositionTicks: Long = 0L,
    val MarkerType: String? = null,
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
    val DateModified: String? = null,
    val Chapters: List<ChapterDto>? = null,
)

/** Resume (and most list endpoints) wrap items; `Items/Latest` returns a raw array. */
@Serializable
data class ItemsResponseDto(
    val Items: List<BaseItemDto> = emptyList(),
    /** Full size of the matching set, independent of `Limit`. */
    val TotalRecordCount: Int = 0,
)

@Serializable
data class PlaylistCreatedDto(val Id: String? = null)

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
    val useSeriesPoster = isEpisode && SeriesId != null
    val ownBackdrop = BackdropImageTags?.firstOrNull()
    val inheritedBackdrop = ParentBackdropImageTags?.firstOrNull()

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
        posterItemId = if (useSeriesPoster) SeriesId ?: Id else Id,
        posterTag = if (useSeriesPoster) SeriesPrimaryImageTag else ImageTags?.get("Primary"),
        backdropItemId = if (ownBackdrop != null) Id else ParentBackdropItemId ?: SeriesId ?: Id,
        backdropTag = ownBackdrop ?: inheritedBackdrop,
        playedPercentage = UserData?.PlayedPercentage,
        overview = Overview,
        year = ProductionYear,
        communityRating = CommunityRating,
        providerIds = ProviderIds.orEmpty(),
        isFavorite = UserData?.IsFavorite == true,
        played = UserData?.Played == true,
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
        seriesName = SeriesName,
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
        versions = MediaSources.orEmpty().mapIndexed { index, dto ->
            dto.toMediaVersion(fallbackId = Id, ordinal = index)
        },
        isFavorite = UserData?.IsFavorite == true,
        played = UserData?.Played == true,
        providerIds = ProviderIds.orEmpty(),
        playbackSegments = playbackSegments(),
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

/**
 * The full picture of one file: what it is, how big, and what tracks are inside it.
 *
 * [fallbackId] stands in when the server omits `MediaSource.Id`, which it does for items
 * with a single source — the item id is the right thing to name in that case anyway.
 */
fun MediaSourceDto.toMediaVersion(fallbackId: String, ordinal: Int): MediaVersion {
    val video = MediaStreams?.firstOrNull { it.Type == "Video" }
    val container = Container?.takeIf { it.isNotBlank() }
    return MediaVersion(
        id = Id?.takeIf { it.isNotBlank() } ?: fallbackId,
        // Emby names a source only when the library has more than one; falling back to the
        // container beats "版本 2" because it is what actually distinguishes the files.
        name = Name?.takeIf { it.isNotBlank() }
            ?: container?.uppercase()
            ?: "版本 ${ordinal + 1}",
        container = container,
        sizeBytes = Size,
        bitrateBps = Bitrate,
        videoCodec = video?.Codec?.takeIf { it.isNotBlank() },
        videoHeight = video?.Height,
        videoRange = video?.VideoRange?.takeIf { !it.equals("SDR", ignoreCase = true) },
        audioTracks = MediaStreams.orEmpty()
            .filter { it.Type == "Audio" }
            .map { stream ->
                AudioTrackInfo(
                    codec = stream.Codec?.takeIf { it.isNotBlank() },
                    channels = stream.ChannelLayout?.takeIf { it.isNotBlank() }
                        ?: stream.Channels?.let { "$it 声道" },
                    language = languageDisplayName(stream.Language)
                        ?: stream.Title?.takeIf { it.isNotBlank() },
                )
            },
        subtitleTracks = MediaStreams.orEmpty()
            .filter { it.Type == "Subtitle" }
            .map { stream ->
                SubtitleTrackInfo(
                    codec = stream.Codec?.takeIf { it.isNotBlank() },
                    language = languageDisplayName(stream.Language)
                        ?: stream.Title?.takeIf { it.isNotBlank() },
                    forced = stream.IsForced == true,
                )
            },
    )
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
    seasonNumber = ParentIndexNumber,
    seasonId = SeasonId,
    overview = Overview,
    runtimeMinutes = RunTimeTicks?.let { (it / 600_000_000L).toInt() }?.takeIf { it > 0 },
    primaryTag = ImageTags?.get("Primary"),
    playedPercentage = UserData?.PlayedPercentage,
    resumePositionTicks = UserData?.PlaybackPositionTicks,
    playbackSegments = playbackSegments(),
    providerIds = ProviderIds.orEmpty(),
)

/** Pairs Emby's IntroStart/IntroEnd markers and treats CreditsStart as open-ended. */
fun BaseItemDto.playbackSegments(): List<PlaybackSegment> {
    val markers = Chapters.orEmpty().sortedBy { it.StartPositionTicks }
    val introStart = markers.firstOrNull { it.MarkerType.equals("IntroStart", true) }
    val introEnd = markers.firstOrNull {
        it.MarkerType.equals("IntroEnd", true) &&
            (introStart == null || it.StartPositionTicks > introStart.StartPositionTicks)
    }
    val intro = if (introStart != null && introEnd != null) {
        PlaybackSegment(
            type = PlaybackSegmentType.Intro,
            startMs = introStart.StartPositionTicks / 10_000L,
            endMs = introEnd.StartPositionTicks / 10_000L,
        )
    } else {
        null
    }
    val credits = markers.firstOrNull { it.MarkerType.equals("CreditsStart", true) }?.let {
        PlaybackSegment(
            type = PlaybackSegmentType.Credits,
            startMs = it.StartPositionTicks / 10_000L,
            endMs = null,
        )
    }
    return listOfNotNull(intro, credits)
}

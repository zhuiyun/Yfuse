package com.yfuse.core.data.dto

import com.yfuse.core.model.AudioTrackInfo
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.Person
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import com.yfuse.core.model.Season
import com.yfuse.core.model.SourceInfo
import com.yfuse.core.model.SubtitleTrackInfo
import com.yfuse.core.model.TrickplayInfo
import com.yfuse.core.model.VideoStreamInfo
import com.yfuse.core.model.languageDisplayName
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import kotlinx.serialization.Serializable

@Serializable
data class PublicInfoDto(
    val ServerName: String? = null,
    val Version: String? = null,
    val Id: String? = null,
    val ProductName: String? = null,
)

@Serializable
data class PublicUserDto(
    val Id: String,
    val Name: String = "",
    val HasPassword: Boolean = false,
    val PrimaryImageTag: String? = null,
)

@Serializable
data class AuthRequestDto(
    val Username: String,
    val Pw: String,
)

@Serializable
data class AuthResultDto(
    val AccessToken: String,
    val User: AuthUserDto,
)

@Serializable
data class AuthUserDto(
    val Id: String,
    val Name: String,
)

@Serializable
data class ViewsDto(
    val Items: List<ViewItemDto> = emptyList(),
)

/** Aggregate counts returned by `/Items/Counts` for one Emby user. */
@Serializable
data class ItemCountsDto(
    val MovieCount: Int = 0,
    val SeriesCount: Int = 0,
)

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
    val LastPlayedDate: String? = null,
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
    val Index: Int? = null,
    val Type: String? = null,
    val Height: Int? = null,
    val Width: Int? = null,
    val VideoRange: String? = null,
    val Codec: String? = null,
    val Language: String? = null,
    val Title: String? = null,
    val DisplayTitle: String? = null,
    val DisplayLanguage: String? = null,
    val Channels: Int? = null,
    val ChannelLayout: String? = null,
    val IsForced: Boolean? = null,
    val IsDefault: Boolean? = null,
    val IsExternal: Boolean? = null,
    /** Provider-owned sidecar address, already authenticated when the player needs URL auth. */
    val DeliveryUrl: String? = null,
    val IsInterlaced: Boolean? = null,
    val BitRate: Int? = null,
    val SampleRate: Int? = null,
    val BitDepth: Int? = null,
    val Profile: String? = null,
    val Level: Double? = null,
    val AspectRatio: String? = null,
    val ColorSpace: String? = null,
    val ColorPrimaries: String? = null,
    /** `"24000/1001"` as often as a decimal, so it is parsed rather than shown raw. */
    val AverageFrameRate: Double? = null,
    val RealFrameRate: Double? = null,
    /**
     * Dolby Vision, as the server reports it. Newer Emby and Jellyfin send these; older
     * ones send nothing and the profile is read out of the codec tag instead.
     *
     * The profile is the only thing that says whether a device without a Dolby decoder can
     * do anything with the file — see [com.yfuse.core.model.MediaVersion.dolbyProfile].
     */
    val DvProfile: Int? = null,
    val DvLevel: Int? = null,
    /** Presence flags reported by Jellyfin/Emby probing; non-zero means present. */
    val RpuPresentFlag: Int? = null,
    val ElPresentFlag: Int? = null,
    val BlPresentFlag: Int? = null,
    /** 1 = HDR10 base layer, 2 = SDR, 4 = HLG. 0 means there is no compatible layer. */
    val DvBlSignalCompatibilityId: Int? = null,
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
    /** `VideoFile`, `Iso`, `Dvd`, or `BluRay` on Emby/Jellyfin servers. */
    val VideoType: String? = null,
    val Size: Long? = null,
    val Bitrate: Int? = null,
    /** The file's location on the server, shown at the foot of 媒体信息. */
    val Path: String? = null,
    val MediaStreams: List<MediaStreamDto>? = null,
    /** Capability flags and URLs filled by `/Items/{id}/PlaybackInfo`. */
    val SupportsDirectPlay: Boolean? = null,
    val SupportsDirectStream: Boolean? = null,
    val SupportsTranscoding: Boolean? = null,
    val DirectStreamUrl: String? = null,
    val AddApiKeyToDirectStreamUrl: Boolean? = null,
    val TranscodingUrl: String? = null,
    val TranscodingContainer: String? = null,
    val TranscodingSubProtocol: String? = null,
    val DefaultAudioStreamIndex: Int? = null,
    val DefaultSubtitleStreamIndex: Int? = null,
)

@Serializable
data class PlaybackInfoResponseDto(
    val MediaSources: List<MediaSourceDto> = emptyList(),
    val PlaySessionId: String? = null,
    val ErrorCode: String? = null,
)

@Serializable
data class PlaybackInfoRequestDto(
    val Id: String,
    val UserId: String,
    val DeviceProfile: DeviceProfileDto,
    val StartTimeTicks: Long = 0L,
    val MediaSourceId: String? = null,
    val CurrentPlaySessionId: String? = null,
    val MaxStreamingBitrate: Long = 120_000_000L,
    val MaxAudioChannels: Int = 8,
    val EnableDirectPlay: Boolean = true,
    val EnableDirectStream: Boolean = true,
    val EnableTranscoding: Boolean = true,
    val AllowInterlacedVideoStreamCopy: Boolean = true,
    val AllowVideoStreamCopy: Boolean = true,
    val AllowAudioStreamCopy: Boolean = true,
    val IsPlayback: Boolean = true,
    val AutoOpenLiveStream: Boolean = true,
)

@Serializable
data class DeviceProfileDto(
    val Name: String = "Yfuse Android",
    val SupportedMediaTypes: String = "Video,Audio",
    val MaxStreamingBitrate: Long = 120_000_000L,
    val DirectPlayProfiles: List<DirectPlayProfileDto> = emptyList(),
    val TranscodingProfiles: List<TranscodingProfileDto> = emptyList(),
    val CodecProfiles: List<CodecProfileDto> = emptyList(),
    val SubtitleProfiles: List<SubtitleProfileDto> = emptyList(),
) {
    companion object {
        fun yfuseAndroid(
            capabilities: PlaybackDeviceCapabilities = PlaybackDeviceCapabilities.conservative(),
        ): DeviceProfileDto = EmbyDeviceProfileFactory.create(capabilities)
    }
}

@Serializable
data class DirectPlayProfileDto(
    val Container: String,
    val Type: String = "Video",
    val VideoCodec: String,
    val AudioCodec: String,
)

@Serializable
data class TranscodingProfileDto(
    val Container: String,
    val Type: String = "Video",
    val VideoCodec: String,
    val AudioCodec: String,
    val Protocol: String,
    val Context: String = "Streaming",
    val MaxAudioChannels: String = "8",
    val MinSegments: Int = 2,
    val BreakOnNonKeyFrames: Boolean = true,
)

@Serializable
data class CodecProfileDto(
    val Type: String,
    val Conditions: List<ProfileConditionDto>,
    val ApplyConditions: List<ProfileConditionDto> = emptyList(),
    val Codec: String? = null,
    val Container: String? = null,
)

@Serializable
data class ProfileConditionDto(
    val Condition: String,
    val Property: String,
    val Value: String,
    val IsRequired: Boolean = false,
)

@Serializable
data class SubtitleProfileDto(
    val Format: String,
    val Method: String,
)

@Serializable
data class RemoteSubtitleInfoDto(
    val Id: String,
    val Name: String? = null,
    val ProviderName: String? = null,
    val Language: String? = null,
    val Format: String? = null,
    val Author: String? = null,
    val Comment: String? = null,
    val DownloadCount: Int? = null,
    val IsHashMatch: Boolean? = null,
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
    /** ISO-8601; only the date half is shown, next to the container and size. */
    val DateCreated: String? = null,
    val Overview: String? = null,
    val Genres: List<String>? = null,
    val RunTimeTicks: Long? = null,
    val CommunityRating: Double? = null,
    val OfficialRating: String? = null,
    val People: List<PersonDto>? = null,
    val ImageTags: Map<String, String>? = null,
    val PremiereDate: String? = null,
    val BackdropImageTags: List<String>? = null,
    val UserData: UserDataDto? = null,
    val MediaSources: List<MediaSourceDto>? = null,
    val ProviderIds: Map<String, String>? = null,
    val DateModified: String? = null,
    val Chapters: List<ChapterDto>? = null,
    /** mediaSourceId -> width -> sprite layout (Jellyfin 10.9+). */
    val Trickplay: Map<String, Map<String, TrickplayInfoDto>?>? = null,
    /** Row identity returned only by `/Playlists/{id}/Items`; required for removal. */
    val PlaylistItemId: String? = null,
    /** Present for folders such as BoxSet/Playlist when requested through Fields. */
    val ChildCount: Int? = null,
)

@Serializable
data class TrickplayInfoDto(
    val Width: Int = 0,
    val Height: Int = 0,
    val TileWidth: Int = 0,
    val TileHeight: Int = 0,
    val ThumbnailCount: Int = 0,
    val Interval: Long = 0L,
)

/** Resume (and most list endpoints) wrap items; `Items/Latest` returns a raw array. */
@Serializable
data class ItemsResponseDto(
    val Items: List<BaseItemDto> = emptyList(),
    /** Full size of the matching set, independent of `Limit`. */
    val TotalRecordCount: Int? = null,
)

@Serializable
data class PlaylistCreatedDto(
    val Id: String? = null,
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
    val useSeriesPoster = isEpisode && SeriesId != null
    val ownBackdrop = BackdropImageTags?.firstOrNull()
    val inheritedBackdrop = ParentBackdropImageTags?.firstOrNull()

    val title = if (isEpisode) (SeriesName ?: Name ?: "") else (Name ?: "")
    val subtitle =
        when {
            isEpisode ->
                buildString {
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
        posterItemId = if (useSeriesPoster) SeriesId else Id,
        posterTag = if (useSeriesPoster) SeriesPrimaryImageTag else ImageTags?.get("Primary"),
        backdropItemId = if (ownBackdrop != null) Id else ParentBackdropItemId ?: SeriesId ?: Id,
        backdropTag = ownBackdrop ?: inheritedBackdrop,
        playedPercentage = UserData?.PlayedPercentage,
        resumePositionTicks = UserData?.PlaybackPositionTicks,
        lastPlayedDate = UserData?.LastPlayedDate,
        overview = Overview,
        year = ProductionYear,
        runtimeMinutes = RunTimeTicks?.let { (it / 600_000_000L).toInt() }?.takeIf { it > 0 },
        communityRating = CommunityRating,
        providerIds = ProviderIds.orEmpty(),
        isFavorite = UserData?.IsFavorite == true,
        played = UserData?.Played == true,
        playlistItemId = PlaylistItemId,
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
        seasonNumber = ParentIndexNumber,
        episodeNumber = IndexNumber,
        overview = Overview,
        year = ProductionYear,
        genres = Genres ?: emptyList(),
        runtimeMinutes = RunTimeTicks?.let { (it / 600_000_000L).toInt() }?.takeIf { it > 0 },
        officialRating = OfficialRating,
        communityRating = CommunityRating,
        posterItemId = posterId,
        posterTag = posterTag,
        // An episode carries no artwork of its own; the show's is the artwork for it, and
        // an empty strip under every episode would be worse than a repeated one.
        backdropTags =
            BackdropImageTags?.takeIf { it.isNotEmpty() }
                ?: ParentBackdropImageTags.orEmpty(),
        dateCreated = DateCreated?.take(10)?.takeIf { it.length == 10 },
        backdropItemId = backdropId,
        backdropTag = backdropTag,
        resumePositionTicks = UserData?.PlaybackPositionTicks,
        people = People?.map { it.toPerson() } ?: emptyList(),
        source = MediaSources?.firstOrNull()?.toSourceInfo(),
        versions =
            MediaSources.orEmpty().mapIndexed { index, dto ->
                dto.toMediaVersion(fallbackId = Id, ordinal = index)
            },
        isFavorite = UserData?.IsFavorite == true,
        played = UserData?.Played == true,
        providerIds = ProviderIds.orEmpty(),
        playbackSegments = playbackSegments(),
        trickplay = bestTrickplay(),
        runtimeTicks = RunTimeTicks?.takeIf { it > 0L },
    )
}

/**
 * The Dolby Vision profile written into the codec tag, for servers that do not report it.
 *
 * `dvhe.05.06` / `dvh1.08.09` — the two digits after the four-character codec are the
 * profile. Emby puts the tag in `Codec` on some libraries and in `Profile` on others, so
 * both are tried; anything that is not a Dolby tag yields null rather than a guess.
 */
internal fun dolbyProfileFromCodecTag(
    codec: String?,
    profile: String?,
): Int? =
    listOfNotNull(codec, profile)
        .firstNotNullOfOrNull { value ->
            val lower = value.lowercase()
            val marker =
                listOf("dvhe.", "dvh1.", "dvav.", "dva1.")
                    .firstOrNull { lower.startsWith(it) || lower.contains(it) }
                    ?: return@firstNotNullOfOrNull null
            lower.substringAfter(marker).take(2).toIntOrNull()
        }

/** `4K HDR · 42.3 GB · 68 Mbps`, from the first video stream and the container. */
fun MediaSourceDto.toSourceInfo(): SourceInfo? {
    val video = MediaStreams?.firstOrNull { it.Type == "Video" }
    val audio = MediaStreams.orEmpty().filter { it.Type == "Audio" }
    val height = video?.Height
    // Built through MediaVersion rather than read off the streams a second time: it already
    // knows where Emby hides Dolby Vision, and one definition of that is enough.
    val version = toMediaVersion(fallbackId = Id.orEmpty(), ordinal = 0)
    val effectiveBitrate = Bitrate?.takeIf { it > 0 } ?: video?.BitRate?.takeIf { it > 0 }
    return SourceInfo(
        quality = version.qualityLabel,
        size = Size?.takeIf { it > 0 }?.let { formatBytes(it) },
        bitrate = effectiveBitrate?.let { "${it / 1_000_000} Mbps" },
        audioTrackCount = audio.size,
        subtitleTrackCount = MediaStreams.orEmpty().count { it.Type == "Subtitle" },
        sizeBytes = Size?.takeIf { it > 0 },
        rangeLabel = version.rangeLabel,
        dolbyVision = version.isDolbyVision,
        dolbyAtmos = version.hasDolbyAtmos,
        frameRate = version.frameRateLabel,
        videoWidth = video?.Width?.takeIf { it > 0 },
        videoHeight = height?.takeIf { it > 0 },
        bitrateBps = effectiveBitrate,
        videoRange = video?.VideoRange?.takeIf { it.isNotBlank() },
        videoBitDepth = video?.BitDepth?.takeIf { it > 0 },
        maxAudioChannels =
            audio
                .mapNotNull { it.Channels?.takeIf { channels -> channels > 0 } }
                .maxOrNull(),
        maxAudioBitrateBps =
            audio
                .mapNotNull { it.BitRate?.takeIf { bitrate -> bitrate > 0 } }
                .maxOrNull(),
        losslessAudio = version.audioTracks.any { it.isLossless },
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
fun MediaSourceDto.toMediaVersion(
    fallbackId: String,
    ordinal: Int,
): MediaVersion {
    val video = MediaStreams?.firstOrNull { it.Type == "Video" }
    val container = Container?.takeIf { it.isNotBlank() }
    return MediaVersion(
        id = Id?.takeIf { it.isNotBlank() } ?: fallbackId,
        // Emby names a source only when the library has more than one; falling back to the
        // container beats "版本 2" because it is what actually distinguishes the files.
        name =
            Name?.takeIf { it.isNotBlank() }
                ?: container?.uppercase()
                ?: "版本 ${ordinal + 1}",
        container = container,
        sizeBytes = Size,
        bitrateBps = Bitrate?.takeIf { it > 0 } ?: video?.BitRate?.takeIf { it > 0 },
        videoCodec = video?.Codec?.takeIf { it.isNotBlank() },
        videoHeight = video?.Height,
        videoRange = video?.VideoRange?.takeIf { !it.equals("SDR", ignoreCase = true) },
        path = Path?.takeIf { it.isNotBlank() },
        videoType = VideoType?.takeIf { it.isNotBlank() },
        video =
            video?.let { stream ->
                VideoStreamInfo(
                    displayTitle = stream.DisplayTitle?.takeIf { it.isNotBlank() },
                    language = languageDisplayName(stream.Language),
                    codec = stream.Codec?.takeIf { it.isNotBlank() }?.uppercase(),
                    width = stream.Width,
                    height = stream.Height,
                    // Emby reports both; the average is the one that matches what plays back.
                    frameRate = stream.AverageFrameRate ?: stream.RealFrameRate,
                    bitrateBps = stream.BitRate,
                    // Unlike the badge on the version row, the table states SDR rather than
                    // omitting it — a blank cell there would read as "unknown", not "standard".
                    videoRange = stream.VideoRange?.takeIf { it.isNotBlank() },
                    interlaced = stream.IsInterlaced,
                    colorPrimaries = stream.ColorPrimaries?.takeIf { it.isNotBlank() },
                    colorSpace = stream.ColorSpace?.takeIf { it.isNotBlank() },
                    profile = stream.Profile?.takeIf { it.isNotBlank() },
                    level = stream.Level,
                    aspectRatio = stream.AspectRatio?.takeIf { it.isNotBlank() },
                    bitDepth = stream.BitDepth,
                    dolbyProfile =
                        stream.DvProfile
                            ?: dolbyProfileFromCodecTag(stream.Codec, stream.Profile),
                    dolbyBaseLayerCompatibility = stream.DvBlSignalCompatibilityId,
                    dolbyRpuPresent = stream.RpuPresentFlag?.let { it != 0 },
                    dolbyEnhancementLayerPresent = stream.ElPresentFlag?.let { it != 0 },
                    dolbyBaseLayerPresent = stream.BlPresentFlag?.let { it != 0 },
                )
            },
        audioTracks =
            MediaStreams
                .orEmpty()
                .filter { it.Type == "Audio" }
                .map { stream ->
                    AudioTrackInfo(
                        codec = stream.Codec?.takeIf { it.isNotBlank() },
                        channels =
                            stream.ChannelLayout?.takeIf { it.isNotBlank() }
                                ?: stream.Channels?.let { "$it 声道" },
                        language =
                            languageDisplayName(stream.Language)
                                ?: stream.Title?.takeIf { it.isNotBlank() },
                        displayTitle = stream.DisplayTitle?.takeIf { it.isNotBlank() },
                        displayLanguage = stream.DisplayLanguage?.takeIf { it.isNotBlank() },
                        profile = stream.Profile?.takeIf { it.isNotBlank() },
                        bitrateBps = stream.BitRate,
                        channelCount = stream.Channels,
                        sampleRateHz = stream.SampleRate,
                        external = stream.IsExternal,
                        default = stream.IsDefault,
                    )
                },
        subtitleTracks =
            MediaStreams
                .orEmpty()
                .filter { it.Type == "Subtitle" }
                .map { stream ->
                    SubtitleTrackInfo(
                        index = stream.Index,
                        codec = stream.Codec?.takeIf { it.isNotBlank() },
                        language =
                            languageDisplayName(stream.Language)
                                ?: stream.Title?.takeIf { it.isNotBlank() },
                        forced = stream.IsForced == true,
                        external = stream.IsExternal == true,
                        default = stream.IsDefault == true,
                        uri = stream.DeliveryUrl?.takeIf(String::isNotBlank),
                    )
                },
        supportsDirectPlay = SupportsDirectPlay,
        supportsDirectStream = SupportsDirectStream,
        supportsTranscoding = SupportsTranscoding,
        directStreamUrl = DirectStreamUrl?.takeIf { it.isNotBlank() },
        addApiKeyToDirectStreamUrl = AddApiKeyToDirectStreamUrl != false,
        transcodingUrl = TranscodingUrl?.takeIf { it.isNotBlank() },
    )
}

fun PersonDto.toPerson() = Person(Id, Name ?: "", Role?.ifBlank { null }, PrimaryImageTag)

fun BaseItemDto.toSeason() =
    Season(
        id = Id,
        name = Name ?: "第 ${IndexNumber ?: 1} 季",
        indexNumber = IndexNumber,
        posterTag = ImageTags?.get("Primary"),
    )

fun BaseItemDto.toEpisode() =
    Episode(
        id = Id,
        name = Name ?: "",
        indexNumber = IndexNumber,
        seasonNumber = ParentIndexNumber,
        seasonId = SeasonId,
        overview = Overview,
        runtimeMinutes = RunTimeTicks?.let { (it / 600_000_000L).toInt() }?.takeIf { it > 0 },
        primaryTag = ImageTags?.get("Primary"),
        playedPercentage = UserData?.PlayedPercentage,
        played = UserData?.Played == true,
        resumePositionTicks = UserData?.PlaybackPositionTicks,
        // Emby sends a full timestamp; the date is the part that means anything here.
        premiereDate = PremiereDate?.take(10)?.takeIf { it.length == 10 },
        playbackSegments = playbackSegments(),
        providerIds = ProviderIds.orEmpty(),
        versions =
            MediaSources.orEmpty().mapIndexed { index, source ->
                source.toMediaVersion(fallbackId = Id, ordinal = index)
            },
        trickplay = bestTrickplay(),
        runtimeTicks = RunTimeTicks?.takeIf { it > 0L },
    )

fun BaseItemDto.bestTrickplay(): TrickplayInfo? =
    Trickplay
        .orEmpty()
        .values
        .filterNotNull()
        .flatMap { it.values }
        .filter { it.Width > 0 && it.Height > 0 && it.TileWidth > 0 && it.TileHeight > 0 && it.Interval > 0L }
        .minWithOrNull(compareBy<TrickplayInfoDto> { kotlin.math.abs(it.Width - 320) }.thenBy { it.Width })
        ?.let {
            TrickplayInfo(
                width = it.Width,
                height = it.Height,
                tileColumns = it.TileWidth,
                tileRows = it.TileHeight,
                intervalMs = it.Interval,
                thumbnailCount = it.ThumbnailCount,
            )
        }

/** Pairs Emby's IntroStart/IntroEnd markers and treats CreditsStart as open-ended. */
fun BaseItemDto.playbackSegments(): List<PlaybackSegment> {
    val markers = Chapters.orEmpty().sortedBy { it.StartPositionTicks }
    val introStart = markers.firstOrNull { it.MarkerType.equals("IntroStart", true) }
    val introEnd =
        markers.firstOrNull {
            it.MarkerType.equals("IntroEnd", true) &&
                (introStart == null || it.StartPositionTicks > introStart.StartPositionTicks)
        }
    val intro =
        if (introStart != null && introEnd != null) {
            PlaybackSegment(
                type = PlaybackSegmentType.Intro,
                startMs = introStart.StartPositionTicks / 10_000L,
                endMs = introEnd.StartPositionTicks / 10_000L,
            )
        } else {
            null
        }
    val credits =
        markers.firstOrNull { it.MarkerType.equals("CreditsStart", true) }?.let {
            PlaybackSegment(
                type = PlaybackSegmentType.Credits,
                startMs = it.StartPositionTicks / 10_000L,
                endMs = null,
            )
        }
    return listOfNotNull(intro, credits)
}

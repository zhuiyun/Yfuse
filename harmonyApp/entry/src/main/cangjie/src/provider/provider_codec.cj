package com.yfuse.harmony.entry.provider

import encoding.json.*
import std.collection.*
import com.yfuse.harmony.entry.data.*

public interface ProviderCodec {
    func decodeAuthentication(payload: String, fallbackServerName: String): ApiResult<AuthSession>
    func decodePage(server: SavedServer, payload: String, startIndex: Int64): ApiResult<MediaPage>
    func decodeDetail(server: SavedServer, payload: String): ApiResult<MediaDetailModel>
    func decodeSources(server: SavedServer, payload: String, itemId: String): ApiResult<ArrayList<MediaSourceModel>>
}

private func mediaKind(raw: String): MediaKind {
    return match (raw) {
        case "Movie" => MediaKind.Movie
        case "Series" | "show" => MediaKind.Series
        case "Season" | "season" => MediaKind.Season
        case "Episode" | "episode" => MediaKind.Episode
        case "LiveTv" => MediaKind.LiveTv
        case "MusicAlbum" | "Audio" | "track" => MediaKind.Music
        case _ => MediaKind.Unknown
    }
}

private func imageUrl(server: SavedServer, itemId: String, imageType: String, tag: String): String {
    if (itemId.isEmpty() || tag.isEmpty()) { return "" }
    return "${server.baseUrl}/Items/${itemId}/Images/${imageType}?tag=${tag}&quality=90"
}

private func embyItem(server: SavedServer, source: JsonObject): MediaItemModel {
    let id = jsonString(source, "Id")
    let userData = match (jsonObject(source, "UserData")) { case Some(value) => value; case None => JsonObject() }
    let tags = match (jsonObject(source, "ImageTags")) { case Some(value) => value; case None => JsonObject() }
    let backdrops = jsonStringList(source, "BackdropImageTags")
    let providerIds = HashMap<String, String>()
    if (let Some(values) <- jsonObject(source, "ProviderIds")) {
        for ((key, value) in values.getFields()) {
            try { providerIds[key] = value.asString().getValue() } catch (_: Exception) {}
        }
    }
    let subtitle = if (!jsonString(source, "SeriesName").isEmpty()) {
        jsonString(source, "SeriesName")
    } else if (jsonInt(source, "ProductionYear") > 0) {
        jsonInt(source, "ProductionYear").toString()
    } else { "" }
    let backdropTag = if (backdrops.isEmpty()) { "" } else { backdrops[0] }
    return MediaItemModel(
        server.id,
        id,
        jsonString(source, "Name", fallback: "未命名"),
        subtitle: subtitle,
        kind: mediaKind(jsonString(source, "Type")),
        posterUrl: imageUrl(server, id, "Primary", jsonString(tags, "Primary")),
        backdropUrl: imageUrl(server, id, "Backdrop", backdropTag),
        overview: jsonString(source, "Overview"),
        year: jsonInt(source, "ProductionYear"),
        runtimeMinutes: jsonInt(source, "RunTimeTicks") / 600000000,
        playedPercentage: jsonFloat(userData, "PlayedPercentage"),
        favorite: jsonBool(userData, "IsFavorite"),
        played: jsonBool(userData, "Played"),
        providerIds: providerIds
    )
}

private func embySource(server: SavedServer, itemId: String, source: JsonObject): MediaSourceModel {
    let sourceId = jsonString(source, "Id")
    var video = JsonObject()
    var audio = JsonObject()
    for (value in jsonArray(source, "MediaStreams").getItems()) {
        let stream = jsonObjectOrEmpty(value)
        if (jsonString(stream, "Type") == "Video" && jsonBool(stream, "IsDefault", fallback: video.size() == 0)) { video = stream }
        if (jsonString(stream, "Type") == "Audio" && jsonBool(stream, "IsDefault", fallback: audio.size() == 0)) { audio = stream }
    }
    let protocol = jsonString(source, "Protocol")
    let path = jsonString(source, "Path")
    let direct = if (protocol == "Http" && (path.startsWith("http://") || path.startsWith("https://"))) {
        path
    } else {
        "${server.baseUrl}/Videos/${itemId}/stream?Static=true&MediaSourceId=${sourceId}"
    }
    let videoRange = jsonString(video, "VideoRangeType", fallback: jsonString(video, "VideoRange"))
    let videoCodec = jsonString(video, "Codec")
    let profile = jsonString(video, "Profile")
    let audioCodec = jsonString(audio, "Codec")
    return MediaSourceModel(
        sourceId,
        jsonString(source, "Name", fallback: jsonString(source, "Container")),
        direct,
        transcodeUrl: jsonString(source, "TranscodingUrl"),
        container: jsonString(source, "Container"),
        videoCodec: videoCodec,
        audioCodec: audioCodec,
        width: jsonInt(video, "Width"),
        height: jsonInt(video, "Height"),
        bitrate: jsonInt(source, "Bitrate"),
        frameRate: jsonFloat(video, "RealFrameRate"),
        hdrType: if (videoRange.isEmpty()) { "SDR" } else { videoRange },
        dolbyVision: profile.contains("DolbyVision") || videoRange.contains("DOVI"),
        dolbyAtmos: jsonString(audio, "Profile").contains("ATMOS"),
        requiresHeaders: true
    )
}

public class EmbyCompatibleCodec <: ProviderCodec {
    public func decodeAuthentication(payload: String, fallbackServerName: String): ApiResult<AuthSession> {
        return match (parseJsonObject(payload)) {
            case Failure(problem) => ApiResult<AuthSession>.Failure(problem)
            case Success(root) =>
                let user = match (jsonObject(root, "User")) { case Some(value) => value; case None => JsonObject() }
                let token = jsonString(root, "AccessToken")
                let userId = jsonString(user, "Id")
                if (token.isEmpty() || userId.isEmpty()) {
                    ApiResult<AuthSession>.Failure(ApiFailure(FailureKind.Decode, "登录响应缺少用户或令牌"))
                } else {
                    ApiResult<AuthSession>.Success(AuthSession(
                        jsonString(root, "ServerId"),
                        jsonString(root, "ServerName", fallback: fallbackServerName),
                        userId,
                        jsonString(user, "Name", fallback: "用户"),
                        token
                    ))
                }
        }
    }

    public func decodePage(server: SavedServer, payload: String, startIndex: Int64): ApiResult<MediaPage> {
        return match (parseJsonObject(payload)) {
            case Failure(problem) => ApiResult<MediaPage>.Failure(problem)
            case Success(root) =>
                let result = ArrayList<MediaItemModel>()
                for (value in jsonArray(root, "Items").getItems()) { result.append(embyItem(server, jsonObjectOrEmpty(value))) }
                ApiResult<MediaPage>.Success(MediaPage(result, totalCount: jsonInt(root, "TotalRecordCount", fallback: result.size), startIndex: startIndex))
        }
    }

    public func decodeDetail(server: SavedServer, payload: String): ApiResult<MediaDetailModel> {
        return match (parseJsonObject(payload)) {
            case Failure(problem) => ApiResult<MediaDetailModel>.Failure(problem)
            case Success(root) =>
                let sources = ArrayList<MediaSourceModel>()
                for (value in jsonArray(root, "MediaSources").getItems()) {
                    sources.append(embySource(server, jsonString(root, "Id"), jsonObjectOrEmpty(value)))
                }
                let userData = match (jsonObject(root, "UserData")) { case Some(value) => value; case None => JsonObject() }
                ApiResult<MediaDetailModel>.Success(MediaDetailModel(
                    embyItem(server, root),
                    genres: jsonStringList(root, "Genres"),
                    sources: sources,
                    resumePositionMs: jsonInt(userData, "PlaybackPositionTicks") / 10000,
                    durationMs: jsonInt(root, "RunTimeTicks") / 10000
                ))
        }
    }

    public func decodeSources(server: SavedServer, payload: String, itemId: String): ApiResult<ArrayList<MediaSourceModel>> {
        return match (parseJsonObject(payload)) {
            case Failure(problem) => ApiResult<ArrayList<MediaSourceModel>>.Failure(problem)
            case Success(root) =>
                let sources = ArrayList<MediaSourceModel>()
                for (value in jsonArray(root, "MediaSources").getItems()) { sources.append(embySource(server, itemId, jsonObjectOrEmpty(value))) }
                ApiResult<ArrayList<MediaSourceModel>>.Success(sources)
        }
    }
}

private func plexMetadata(server: SavedServer, source: JsonObject): MediaItemModel {
    let id = jsonString(source, "ratingKey")
    let thumb = jsonString(source, "thumb")
    let art = jsonString(source, "art")
    return MediaItemModel(
        server.id,
        id,
        jsonString(source, "title", fallback: "未命名"),
        subtitle: jsonString(source, "grandparentTitle", fallback: jsonString(source, "year")),
        kind: mediaKind(jsonString(source, "type")),
        posterUrl: if (thumb.isEmpty()) { "" } else { server.baseUrl + thumb },
        backdropUrl: if (art.isEmpty()) { "" } else { server.baseUrl + art },
        overview: jsonString(source, "summary"),
        year: jsonInt(source, "year"),
        runtimeMinutes: jsonInt(source, "duration") / 60000,
        playedPercentage: 0.0,
        favorite: false,
        played: jsonInt(source, "viewCount") > 0
    )
}

private func plexContainer(root: JsonObject): JsonObject {
    return match (jsonObject(root, "MediaContainer")) { case Some(value) => value; case None => root }
}

public class PlexCodec <: ProviderCodec {
    public func decodeAuthentication(payload: String, fallbackServerName: String): ApiResult<AuthSession> {
        return match (parseJsonObject(payload)) {
            case Failure(problem) => ApiResult<AuthSession>.Failure(problem)
            case Success(root) =>
                let token = jsonString(root, "authToken")
                if (token.isEmpty()) {
                    ApiResult<AuthSession>.Failure(ApiFailure(FailureKind.Unauthorized, "Plex PIN 尚未授权"))
                } else {
                    ApiResult<AuthSession>.Success(AuthSession("plex", fallbackServerName, "plex", "Plex", token))
                }
        }
    }

    public func decodePage(server: SavedServer, payload: String, startIndex: Int64): ApiResult<MediaPage> {
        return match (parseJsonObject(payload)) {
            case Failure(problem) => ApiResult<MediaPage>.Failure(problem)
            case Success(root) =>
                let container = plexContainer(root)
                let result = ArrayList<MediaItemModel>()
                for (value in jsonArray(container, "Metadata").getItems()) { result.append(plexMetadata(server, jsonObjectOrEmpty(value))) }
                ApiResult<MediaPage>.Success(MediaPage(result, totalCount: jsonInt(container, "totalSize", fallback: result.size), startIndex: startIndex))
        }
    }

    public func decodeDetail(server: SavedServer, payload: String): ApiResult<MediaDetailModel> {
        return match (decodePage(server, payload, 0)) {
            case Failure(problem) => ApiResult<MediaDetailModel>.Failure(problem)
            case Success(page) =>
                if (page.items.isEmpty()) {
                    ApiResult<MediaDetailModel>.Failure(ApiFailure(FailureKind.NotFound, "Plex 媒体不存在"))
                } else {
                    ApiResult<MediaDetailModel>.Success(MediaDetailModel(page.items[0]))
                }
        }
    }

    public func decodeSources(server: SavedServer, payload: String, itemId: String): ApiResult<ArrayList<MediaSourceModel>> {
        let result = ArrayList<MediaSourceModel>()
        return match (parseJsonObject(payload)) {
            case Failure(problem) => ApiResult<ArrayList<MediaSourceModel>>.Failure(problem)
            case Success(root) =>
                let container = plexContainer(root)
                for (metadataValue in jsonArray(container, "Metadata").getItems()) {
                    let metadata = jsonObjectOrEmpty(metadataValue)
                    for (mediaValue in jsonArray(metadata, "Media").getItems()) {
                        let media = jsonObjectOrEmpty(mediaValue)
                        for (partValue in jsonArray(media, "Part").getItems()) {
                            let part = jsonObjectOrEmpty(partValue)
                            let key = jsonString(part, "key")
                            result.append(MediaSourceModel(
                                jsonString(part, "id", fallback: itemId),
                                jsonString(media, "videoResolution", fallback: jsonString(part, "container")),
                                if (key.startsWith("http")) { key } else { server.baseUrl + key },
                                container: jsonString(part, "container"),
                                videoCodec: jsonString(media, "videoCodec"),
                                audioCodec: jsonString(media, "audioCodec"),
                                width: jsonInt(media, "width"),
                                height: jsonInt(media, "height"),
                                bitrate: jsonInt(media, "bitrate") * 1000,
                                frameRate: jsonFloat(media, "videoFrameRate"),
                                hdrType: if (jsonString(media, "videoDecision") == "transcode") { "Transcode" } else { "SDR" },
                                requiresHeaders: true
                            ))
                        }
                    }
                }
                ApiResult<ArrayList<MediaSourceModel>>.Success(result)
        }
    }
}

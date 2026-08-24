package com.yfuse.harmony.entry.data

import std.collection.*

public enum ProviderKind {
    | Emby
    | Jellyfin
    | Plex
}

public enum MediaKind {
    | Movie
    | Series
    | Season
    | Episode
    | LiveTv
    | Music
    | Unknown
}

public enum ServerHealth {
    | Unknown
    | Probing
    | Online
    | Offline
    | Unauthorized
}

public class ServerRoute {
    public let id: String
    public let name: String
    public let url: String

    public init(id: String, name: String, url: String) {
        this.id = id
        this.name = name
        this.url = url
    }
}

/** Secret tokens are references only; their values live behind SecretStore. */
public class SavedServer {
    public let id: String
    public let provider: ProviderKind
    public var baseUrl: String
    public var serverName: String
    public let userId: String
    public var userName: String
    public let secretRef: String
    public var routes: ArrayList<ServerRoute>
    public var activeRouteId: String
    public var iconEmoji: String
    public var iconTintArgb: UInt32
    public var health: ServerHealth
    public var localCleartextConfirmed: Bool

    public init(
        id: String,
        provider: ProviderKind,
        baseUrl: String,
        serverName: String,
        userId: String,
        userName: String,
        secretRef: String,
        routes!: ArrayList<ServerRoute> = ArrayList<ServerRoute>(),
        activeRouteId!: String = "primary",
        iconEmoji!: String = "",
        iconTintArgb!: UInt32 = 0,
        health!: ServerHealth = ServerHealth.Unknown,
        localCleartextConfirmed!: Bool = false
    ) {
        this.id = id
        this.provider = provider
        this.baseUrl = baseUrl
        this.serverName = serverName
        this.userId = userId
        this.userName = userName
        this.secretRef = secretRef
        this.routes = routes
        this.activeRouteId = activeRouteId
        this.iconEmoji = iconEmoji
        this.iconTintArgb = iconTintArgb
        this.health = health
        this.localCleartextConfirmed = localCleartextConfirmed
    }
}

public class AuthSession {
    public let serverId: String
    public let serverName: String
    public let userId: String
    public let userName: String
    public let accessToken: String

    public init(serverId: String, serverName: String, userId: String, userName: String, accessToken: String) {
        this.serverId = serverId
        this.serverName = serverName
        this.userId = userId
        this.userName = userName
        this.accessToken = accessToken
    }
}

public class MediaItemModel {
    public let serverId: String
    public let id: String
    public let title: String
    public let subtitle: String
    public let kind: MediaKind
    public let posterUrl: String
    public let backdropUrl: String
    public let overview: String
    public let year: Int64
    public let runtimeMinutes: Int64
    public let playedPercentage: Float64
    public let favorite: Bool
    public let played: Bool
    public let providerIds: HashMap<String, String>

    public init(
        serverId: String,
        id: String,
        title: String,
        subtitle!: String = "",
        kind!: MediaKind = MediaKind.Unknown,
        posterUrl!: String = "",
        backdropUrl!: String = "",
        overview!: String = "",
        year!: Int64 = 0,
        runtimeMinutes!: Int64 = 0,
        playedPercentage!: Float64 = 0.0,
        favorite!: Bool = false,
        played!: Bool = false,
        providerIds!: HashMap<String, String> = HashMap<String, String>()
    ) {
        this.serverId = serverId
        this.id = id
        this.title = title
        this.subtitle = subtitle
        this.kind = kind
        this.posterUrl = posterUrl
        this.backdropUrl = backdropUrl
        this.overview = overview
        this.year = year
        this.runtimeMinutes = runtimeMinutes
        this.playedPercentage = playedPercentage
        this.favorite = favorite
        this.played = played
        this.providerIds = providerIds
    }
}

public class MediaPage {
    public let items: ArrayList<MediaItemModel>
    public let totalCount: Int64
    public let startIndex: Int64

    public init(items!: ArrayList<MediaItemModel> = ArrayList<MediaItemModel>(), totalCount!: Int64 = 0, startIndex!: Int64 = 0) {
        this.items = items
        this.totalCount = totalCount
        this.startIndex = startIndex
    }
}

public class HomeRowModel {
    public let id: String
    public let title: String
    public let items: ArrayList<MediaItemModel>
    public let totalCount: Int64

    public init(id: String, title: String, items: ArrayList<MediaItemModel>, totalCount!: Int64 = 0) {
        this.id = id
        this.title = title
        this.items = items
        this.totalCount = totalCount
    }
}

public class HomeContentModel {
    public let featured: ArrayList<MediaItemModel>
    public let resume: ArrayList<MediaItemModel>
    public let rows: ArrayList<HomeRowModel>

    public init(
        featured!: ArrayList<MediaItemModel> = ArrayList<MediaItemModel>(),
        resume!: ArrayList<MediaItemModel> = ArrayList<MediaItemModel>(),
        rows!: ArrayList<HomeRowModel> = ArrayList<HomeRowModel>()
    ) {
        this.featured = featured
        this.resume = resume
        this.rows = rows
    }
}

public class MediaSourceModel {
    public let id: String
    public let name: String
    public let directUrl: String
    public let transcodeUrl: String
    public let container: String
    public let videoCodec: String
    public let audioCodec: String
    public let width: Int64
    public let height: Int64
    public let bitrate: Int64
    public let frameRate: Float64
    public let hdrType: String
    public let dolbyVision: Bool
    public let dolbyAtmos: Bool
    public let requiresHeaders: Bool

    public init(
        id: String,
        name: String,
        directUrl: String,
        transcodeUrl!: String = "",
        container!: String = "",
        videoCodec!: String = "",
        audioCodec!: String = "",
        width!: Int64 = 0,
        height!: Int64 = 0,
        bitrate!: Int64 = 0,
        frameRate!: Float64 = 0.0,
        hdrType!: String = "SDR",
        dolbyVision!: Bool = false,
        dolbyAtmos!: Bool = false,
        requiresHeaders!: Bool = true
    ) {
        this.id = id
        this.name = name
        this.directUrl = directUrl
        this.transcodeUrl = transcodeUrl
        this.container = container
        this.videoCodec = videoCodec
        this.audioCodec = audioCodec
        this.width = width
        this.height = height
        this.bitrate = bitrate
        this.frameRate = frameRate
        this.hdrType = hdrType
        this.dolbyVision = dolbyVision
        this.dolbyAtmos = dolbyAtmos
        this.requiresHeaders = requiresHeaders
    }
}

public class MediaDetailModel {
    public let item: MediaItemModel
    public let genres: ArrayList<String>
    public let sources: ArrayList<MediaSourceModel>
    public let resumePositionMs: Int64
    public let durationMs: Int64

    public init(
        item: MediaItemModel,
        genres!: ArrayList<String> = ArrayList<String>(),
        sources!: ArrayList<MediaSourceModel> = ArrayList<MediaSourceModel>(),
        resumePositionMs!: Int64 = 0,
        durationMs!: Int64 = 0
    ) {
        this.item = item
        this.genres = genres
        this.sources = sources
        this.resumePositionMs = resumePositionMs
        this.durationMs = durationMs
    }
}

public class PlexPin {
    public let id: Int64
    public let code: String
    public let authToken: String

    public init(id: Int64, code: String, authToken!: String = "") {
        this.id = id
        this.code = code
        this.authToken = authToken
    }
}

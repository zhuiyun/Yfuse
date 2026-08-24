package com.yfuse.harmony.entry.app

import std.collection.*
import com.yfuse.harmony.entry.data.*

public enum LoadPhase {
    | Idle
    | Loading
    | Content
    | Empty
    | Offline
    | Failed
}

public class HomeViewState {
    public var phase: LoadPhase = LoadPhase.Idle
    public var content: HomeContentModel = HomeContentModel()
    public var selectedFeaturedIndex: Int64 = 0
    public var artworkPageArgb: UInt32 = 0xFF080D17
    public var error: String = ""
}

public enum LibraryLayout { | PosterGrid | CompactList }

public class LibraryViewState {
    public var phase: LoadPhase = LoadPhase.Idle
    public var title: String = "媒体库"
    public var items: ArrayList<MediaItemModel> = ArrayList<MediaItemModel>()
    public var genres: ArrayList<String> = ArrayList<String>()
    public var selectedGenre: String = "全部"
    public var sort: String = "名称"
    public var ascending: Bool = true
    public var layout: LibraryLayout = LibraryLayout.PosterGrid
    public var page: Int64 = 0
    public var hasMore: Bool = false
    public var error: String = ""
}

public class SearchResultGroup {
    public let serverId: String
    public let serverName: String
    public let items: ArrayList<MediaItemModel>
    public init(serverId: String, serverName: String, items: ArrayList<MediaItemModel>) {
        this.serverId = serverId
        this.serverName = serverName
        this.items = items
    }
}

public class SearchViewState {
    public var query: String = ""
    public var submittedQuery: String = ""
    public var phase: LoadPhase = LoadPhase.Idle
    public var groups: ArrayList<SearchResultGroup> = ArrayList<SearchResultGroup>()
    public var history: ArrayList<String> = ArrayList<String>()
    public var requestGeneration: UInt64 = 0
    public var error: String = ""

    /** Callers compare generation before accepting a delayed/debounced response. */
    public func beginQuery(value: String): UInt64 {
        query = value
        requestGeneration++
        if (value.isEmpty()) { phase = LoadPhase.Idle; groups.clear() } else { phase = LoadPhase.Loading }
        return requestGeneration
    }

    public func accept(generation: UInt64, value: String, result: ArrayList<SearchResultGroup>): Bool {
        if (generation != requestGeneration) { return false }
        submittedQuery = value
        groups = result
        phase = if (result.isEmpty()) { LoadPhase.Empty } else { LoadPhase.Content }
        if (!value.isEmpty()) {
            let next = ArrayList<String>()
            next.append(value)
            for (entry in history) { if (entry != value && next.size < 10) { next.append(entry) } }
            history = next
        }
        return true
    }
}

public enum DownloadPhase { | Queued | Downloading | Paused | Completed | Failed }

public class DownloadItemState {
    public let id: String
    public let title: String
    public var phase: DownloadPhase
    public var downloadedBytes: Int64
    public var totalBytes: Int64
    public var error: String
    public init(id: String, title: String, phase!: DownloadPhase = DownloadPhase.Queued) {
        this.id = id; this.title = title; this.phase = phase
        this.downloadedBytes = 0; this.totalBytes = 0; this.error = ""
    }
}

public class ProfileViewState {
    public var signedIn: Bool = false
    public var displayName: String = ""
    public var syncing: Bool = false
    public var downloads: ArrayList<DownloadItemState> = ArrayList<DownloadItemState>()
    public var appVersion: String = "1.0.0"
}

public class DetailViewState {
    public var phase: LoadPhase = LoadPhase.Loading
    public var detail: Option<MediaDetailModel> = None
    public var selectedSourceId: String = ""
    public var selectedSeasonId: String = ""
    public var artworkPageArgb: UInt32 = 0xFF080D17
    public var error: String = ""
}

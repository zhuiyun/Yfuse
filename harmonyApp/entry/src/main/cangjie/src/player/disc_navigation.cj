package com.yfuse.harmony.entry.player

import std.collection.*

public enum DiscSourceKind { | IsoFile | BdmvDirectory | RemoteIso | RemoteBdmv }

public class DiscSource {
    public let id: String
    public let kind: DiscSourceKind
    public let uri: String
    public let headersJson: String
    public init(id: String, kind: DiscSourceKind, uri: String, headersJson!: String = "{}") {
        this.id = id; this.kind = kind; this.uri = uri; this.headersJson = headersJson
    }
}

public class DiscPlaylist {
    public let id: Int64
    public let durationMs: Int64
    public let chapterCount: Int64
    public let angleCount: Int64
    public init(id: Int64, durationMs: Int64, chapterCount: Int64, angleCount: Int64) {
        this.id = id; this.durationMs = durationMs; this.chapterCount = chapterCount; this.angleCount = angleCount
    }
}

public enum DiscCommand {
    | SelectPlaylist(Int64)
    | SelectChapter(Int64)
    | SelectAngle(Int64)
    | MenuUp
    | MenuDown
    | MenuLeft
    | MenuRight
    | MenuActivate
    | PopupMenu
    | TopMenu
}

public interface RandomAccessSource {
    prop size: Int64
    func read(offset: Int64, length: Int64): Array<UInt8>
    func close(): Unit
}

public interface DiscNavigator {
    func open(source: DiscSource, randomAccess: RandomAccessSource): ArrayList<DiscPlaylist>
    func command(value: DiscCommand): Bool
    func close(): Unit
}

public enum DiscPreflight {
    | Ready
    | NeedsDownload(String)
    | Unsupported(String)
}

public func preflightDisc(source: DiscSource, capabilities: UInt64, randomAccessAvailable: Bool): DiscPreflight {
    if ((capabilities & CAP_OPTICAL_DISC) == 0 || (capabilities & CAP_CUSTOM_DEMUX) == 0) {
        return DiscPreflight.Unsupported("当前构建未同时启用 libbluray 与 FFmpeg")
    }
    if ((capabilities & CAP_NATIVE_WINDOW) == 0) {
        return DiscPreflight.Unsupported("当前 UI 运行时无法向原生内核提供 NativeWindow")
    }
    if (!randomAccessAvailable) {
        return DiscPreflight.NeedsDownload("远程光盘源不支持 Range 随机访问，需先下载到本地")
    }
    return DiscPreflight.Ready
}

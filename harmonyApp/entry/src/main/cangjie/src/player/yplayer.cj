package com.yfuse.harmony.entry.player

public enum PlaybackPhase {
    | Idle
    | Preparing
    | Ready
    | Ended
    | Failed
}

public enum PlaybackRoute {
    | HarmonySystem
    | NativeTunnel
    | NativeDirect
    | NativeEnhanced
    | GpuEnhanced
    | SoftwareFallback
}

public enum TrackType {
    | Audio
    | Subtitle
}

public class MediaItem {
    public let id: String
    public let uri: String
    public let title: String
    public let mimeType: String
    public let headersJson: String
    public let providerKey: String

    public init(
        id: String,
        uri: String,
        title: String,
        mimeType: String,
        headersJson: String,
        providerKey: String
    ) {
        this.id = id
        this.uri = uri
        this.title = title
        this.mimeType = mimeType
        this.headersJson = headersJson
        this.providerKey = providerKey
    }
}

public class PlayerState {
    public var phase: PlaybackPhase = PlaybackPhase.Idle
    public var route: PlaybackRoute = PlaybackRoute.HarmonySystem
    public var playing: Bool = false
    public var playbackRequested: Bool = false
    public var buffering: Bool = false
    public var positionMs: Int64 = 0
    public var durationMs: Int64 = 0
    public var bufferedPositionMs: Int64 = 0
    public var speed: Float32 = 1.0
    public var engine: String = ""
    public var error: String = ""
}

public interface YPlayer {
    prop state: PlayerState
    func prepare(item: MediaItem, startPositionMs: Int64, autoPlay: Bool): Unit
    func play(): Unit
    func pause(): Unit
    func seekTo(positionMs: Int64): Unit
    func setSpeed(speed: Float32): Unit
    func selectTrack(trackType: TrackType, trackId: String): Unit
    func retry(): Unit
    func release(): Unit
}

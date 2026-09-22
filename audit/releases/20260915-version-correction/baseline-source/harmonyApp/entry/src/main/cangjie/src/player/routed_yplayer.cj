package com.yfuse.harmony.entry.player

/** One stable Player API for page/view-model code, regardless of the selected backend. */
public class RoutedYPlayer <: YPlayer {
    private let system: YPlayer
    private let native: Option<YPlayer>
    private let systemCapabilities: UInt64
    private let nativeCapabilities: UInt64
    private let nativeSurfaceAvailable: Bool
    private var active: YPlayer
    private var lastRequirements: PlaybackRequirements = PlaybackRequirements()

    public init(
        system: YPlayer,
        native: Option<YPlayer>,
        systemCapabilities: UInt64,
        nativeCapabilities: UInt64,
        nativeSurfaceAvailable: Bool
    ) {
        this.system = system
        this.native = native
        this.systemCapabilities = systemCapabilities
        this.nativeCapabilities = nativeCapabilities
        this.nativeSurfaceAvailable = nativeSurfaceAvailable
        this.active = system
    }

    public prop state: PlayerState { get() { active.state } }

    public func prepareWithRequirements(item: MediaItem, startPositionMs: Int64, autoPlay: Bool, requirements: PlaybackRequirements): Unit {
        lastRequirements = requirements
        match (decidePlaybackRoute(requirements, systemCapabilities, nativeCapabilities, nativeSurfaceAvailable)) {
            case RouteDecision.System => active = system; active.prepare(item, startPositionMs, autoPlay)
            case RouteDecision.Native =>
                match (native) {
                    case Some(value) => active = value; active.prepare(item, startPositionMs, autoPlay)
                    case None => failUnsupported("原生播放器尚未装配")
                }
            case RouteDecision.Unsupported(reason) => failUnsupported(reason)
        }
    }

    public func prepare(item: MediaItem, startPositionMs: Int64, autoPlay: Bool): Unit {
        prepareWithRequirements(item, startPositionMs, autoPlay, PlaybackRequirements())
    }

    private func failUnsupported(reason: String): Unit {
        active = system
        active.state.phase = PlaybackPhase.Failed
        active.state.playing = false
        active.state.error = reason
    }

    public func play(): Unit { active.play() }
    public func pause(): Unit { active.pause() }
    public func seekTo(positionMs: Int64): Unit { active.seekTo(positionMs) }
    public func setSpeed(speed: Float32): Unit { active.setSpeed(speed) }
    public func selectTrack(trackType: TrackType, trackId: String): Unit { active.selectTrack(trackType, trackId) }
    public func retry(): Unit { active.retry() }
    public func release(): Unit { system.release(); if (let Some(value) <- native) { value.release() } }
}

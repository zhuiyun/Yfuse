package com.yfuse.harmony.entry.player

public interface SystemPlaybackPort {
    func setSource(uri: String, headersJson: String): Unit
    func prepare(): Unit
    func play(): Unit
    func pause(): Unit
    func seekTo(positionMs: Int64): Unit
    func setSpeed(speed: Float32): Unit
    func selectTrack(trackType: TrackType, trackId: String): Unit
    func release(): Unit
}

/** AVPlayer/Video callbacks call these methods; only this class mutates public player state. */
public class SystemYPlayer <: YPlayer {
    private let port: SystemPlaybackPort
    private let mutableState = PlayerState()
    private var lastItem: Option<MediaItem> = None
    private var lastStart: Int64 = 0
    private var lastAutoPlay: Bool = true

    public init(port: SystemPlaybackPort) { this.port = port }
    public prop state: PlayerState { get() { mutableState } }

    public func prepare(item: MediaItem, startPositionMs: Int64, autoPlay: Bool): Unit {
        if (item.uri.isEmpty() || startPositionMs < 0) {
            onError("invalid_input", "播放地址或起播位置无效")
            return
        }
        lastItem = Some(item); lastStart = startPositionMs; lastAutoPlay = autoPlay
        mutableState.phase = PlaybackPhase.Preparing
        mutableState.route = PlaybackRoute.HarmonySystem
        mutableState.engine = "Harmony AVPlayer"
        mutableState.playbackRequested = autoPlay
        mutableState.positionMs = startPositionMs
        mutableState.error = ""
        port.setSource(item.uri, item.headersJson)
        port.prepare()
    }

    public func onPrepared(durationMs: Int64): Unit {
        mutableState.phase = PlaybackPhase.Ready
        mutableState.durationMs = durationMs
        if (lastStart > 0) { port.seekTo(lastStart) }
        if (lastAutoPlay) { port.play(); mutableState.playing = true }
    }

    public func onProgress(positionMs: Int64, bufferedPositionMs: Int64): Unit {
        mutableState.positionMs = positionMs
        mutableState.bufferedPositionMs = bufferedPositionMs
    }

    public func onBuffering(value: Bool): Unit { mutableState.buffering = value }
    public func onEnded(): Unit { mutableState.phase = PlaybackPhase.Ended; mutableState.playing = false }
    public func onError(category: String, message: String): Unit {
        mutableState.phase = PlaybackPhase.Failed
        mutableState.playing = false
        mutableState.error = "${category}: ${message}"
    }

    public func play(): Unit { mutableState.playbackRequested = true; port.play(); mutableState.playing = true }
    public func pause(): Unit { mutableState.playbackRequested = false; port.pause(); mutableState.playing = false }
    public func seekTo(positionMs: Int64): Unit { if (positionMs >= 0) { port.seekTo(positionMs); mutableState.positionMs = positionMs } }
    public func setSpeed(speed: Float32): Unit { if (speed >= 0.25 && speed <= 4.0) { port.setSpeed(speed); mutableState.speed = speed } }
    public func selectTrack(trackType: TrackType, trackId: String): Unit { port.selectTrack(trackType, trackId) }
    public func retry(): Unit {
        if (let Some(item) <- lastItem) { prepare(item, mutableState.positionMs, mutableState.playbackRequested) }
    }
    public func release(): Unit { port.release(); mutableState.phase = PlaybackPhase.Idle; mutableState.playing = false }
}

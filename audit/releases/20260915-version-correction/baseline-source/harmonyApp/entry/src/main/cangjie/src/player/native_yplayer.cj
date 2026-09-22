package com.yfuse.harmony.entry.player

/** Native route is constructed only after the capability gate proves a usable NativeWindow host. */
public class NativeYPlayer <: YPlayer {
    private let session = NativeYCoreSession()
    private let mutableState = PlayerState()
    private var lastItem: Option<MediaItem> = None
    private var lastStart: Int64 = 0
    private var lastAutoPlay: Bool = true

    public prop state: PlayerState { get() { mutableState } }

    public func prepare(item: MediaItem, startPositionMs: Int64, autoPlay: Bool): Unit {
        lastItem = Some(item); lastStart = startPositionMs; lastAutoPlay = autoPlay
        mutableState.phase = PlaybackPhase.Preparing
        mutableState.route = PlaybackRoute.NativeEnhanced
        mutableState.engine = "YCore Native"
        mutableState.playbackRequested = autoPlay
        let status = session.open(item, startPositionMs, autoPlay, CAP_REMOTE_URL | CAP_REQUEST_HEADERS | CAP_NATIVE_WINDOW)
        if (status != 0) { fail(status) } else { refresh() }
    }

    public func play(): Unit { if (session.play() != 0) { fail(-4) } else { mutableState.playbackRequested = true; refresh() } }
    public func pause(): Unit { if (session.pause() != 0) { fail(-4) } else { mutableState.playbackRequested = false; refresh() } }
    public func seekTo(positionMs: Int64): Unit { if (session.seekTo(positionMs) != 0) { fail(-1) } else { refresh() } }
    public func setSpeed(speed: Float32): Unit { if (session.setSpeed(speed) != 0) { fail(-1) } else { refresh() } }
    public func selectTrack(trackType: TrackType, trackId: String): Unit { session.selectTrack(trackType, trackId); refresh() }
    public func retry(): Unit { if (session.retry() != 0) { fail(-4) } else { refresh() } }
    public func release(): Unit { session.release(); mutableState.phase = PlaybackPhase.Idle }
    public func tick(): Unit { session.tick(); refresh() }

    private func refresh(): Unit { session.copyStateTo(mutableState) }
    private func fail(code: Int32): Unit {
        mutableState.phase = PlaybackPhase.Failed
        mutableState.playing = false
        mutableState.error = "YCore 错误 ${code}"
    }
}

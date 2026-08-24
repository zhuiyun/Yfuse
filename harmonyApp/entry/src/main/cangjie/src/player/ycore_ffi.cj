package com.yfuse.harmony.entry.player

import std.collection.*

public const YCORE_ABI_VERSION: UInt32 = 1

foreign {
    func ycore_session_create(): CPointer<Unit>
    func ycore_session_destroy(session: CPointer<Unit>): Unit
    func ycore_session_play(session: CPointer<Unit>): Int32
    func ycore_session_pause(session: CPointer<Unit>): Int32
    func ycore_session_seek_to(session: CPointer<Unit>, positionMs: Int64): Int32
    func ycore_session_set_speed(session: CPointer<Unit>, speed: Float32): Int32
    func ycore_session_retry(session: CPointer<Unit>): Int32
    func ycore_session_tick(session: CPointer<Unit>): Int32
    func ycore_session_handover(session: CPointer<Unit>): Int32
    func ycore_session_open_values(
        session: CPointer<Unit>,
        mediaId: CString,
        uri: CString,
        mimeType: CString,
        headersJson: CString,
        providerKey: CString,
        startPositionMs: Int64,
        autoPlay: Int32,
        requirements: UInt64
    ): Int32
    func ycore_session_select_track(session: CPointer<Unit>, trackType: Int32, trackId: CString): Int32
    func ycore_session_state_phase(session: CPointer<Unit>): Int32
    func ycore_session_state_route(session: CPointer<Unit>): Int32
    func ycore_session_state_playing(session: CPointer<Unit>): Int32
    func ycore_session_state_playback_requested(session: CPointer<Unit>): Int32
    func ycore_session_state_buffering(session: CPointer<Unit>): Int32
    func ycore_session_state_position_ms(session: CPointer<Unit>): Int64
    func ycore_session_state_duration_ms(session: CPointer<Unit>): Int64
    func ycore_session_state_buffered_position_ms(session: CPointer<Unit>): Int64
    func ycore_session_state_speed(session: CPointer<Unit>): Float32
    func ycore_session_state_engine(session: CPointer<Unit>): CString
    func ycore_session_state_reason(session: CPointer<Unit>): CString
}

/** Owns the opaque native session. Unsafe FFI is confined to this class. */
public class NativeYCoreSession {
    private var handle: CPointer<Unit> = unsafe { ycore_session_create() }
    private var released: Bool = false

    public func open(item: MediaItem, startPositionMs: Int64, autoPlay: Bool, requirements: UInt64): Int32 {
        if (released || handle.isNull() || item.uri.isEmpty() || startPositionMs < 0) { return -1 }
        unsafe {
            var mediaId = LibC.mallocCString(item.id)
            var uri = LibC.mallocCString(item.uri)
            var mimeType = LibC.mallocCString(item.mimeType)
            var headersJson = LibC.mallocCString(item.headersJson)
            var providerKey = LibC.mallocCString(item.providerKey)
            let result = ycore_session_open_values(
                handle,
                mediaId,
                uri,
                mimeType,
                headersJson,
                providerKey,
                startPositionMs,
                if (autoPlay) { 1 } else { 0 },
                requirements
            )
            LibC.free(mediaId); LibC.free(uri); LibC.free(mimeType); LibC.free(headersJson); LibC.free(providerKey)
            return result
        }
    }

    public func play(): Int32 {
        if (released || handle.isNull()) { return -4 }
        return unsafe { ycore_session_play(handle) }
    }

    public func pause(): Int32 {
        if (released || handle.isNull()) { return -4 }
        return unsafe { ycore_session_pause(handle) }
    }

    public func seekTo(positionMs: Int64): Int32 {
        if (released || handle.isNull() || positionMs < 0) { return -1 }
        return unsafe { ycore_session_seek_to(handle, positionMs) }
    }

    public func setSpeed(speed: Float32): Int32 {
        if (released || handle.isNull() || speed < 0.25 || speed > 4.0) { return -1 }
        return unsafe { ycore_session_set_speed(handle, speed) }
    }

    public func tick(): Int32 {
        if (released || handle.isNull()) { return -4 }
        return unsafe { ycore_session_tick(handle) }
    }

    public func handover(): Int32 {
        if (released || handle.isNull()) { return -4 }
        return unsafe { ycore_session_handover(handle) }
    }

    public func selectTrack(trackType: TrackType, trackId: String): Int32 {
        if (released || handle.isNull()) { return -4 }
        unsafe {
            var value = LibC.mallocCString(trackId)
            let result = ycore_session_select_track(handle, if (trackType == TrackType.Audio) { 0 } else { 1 }, value)
            LibC.free(value)
            return result
        }
    }

    public func retry(): Int32 {
        if (released || handle.isNull()) { return -4 }
        return unsafe { ycore_session_retry(handle) }
    }

    public func copyStateTo(target: PlayerState): Unit {
        if (released || handle.isNull()) { target.phase = PlaybackPhase.Idle; return }
        unsafe {
            target.phase = match (ycore_session_state_phase(handle)) {
                case 1 => PlaybackPhase.Preparing
                case 2 => PlaybackPhase.Ready
                case 3 => PlaybackPhase.Ended
                case 4 => PlaybackPhase.Failed
                case _ => PlaybackPhase.Idle
            }
            target.route = match (ycore_session_state_route(handle)) {
                case 1 => PlaybackRoute.NativeTunnel
                case 2 => PlaybackRoute.NativeDirect
                case 3 => PlaybackRoute.NativeEnhanced
                case 4 => PlaybackRoute.GpuEnhanced
                case 5 => PlaybackRoute.SoftwareFallback
                case _ => PlaybackRoute.HarmonySystem
            }
            target.playing = ycore_session_state_playing(handle) != 0
            target.playbackRequested = ycore_session_state_playback_requested(handle) != 0
            target.buffering = ycore_session_state_buffering(handle) != 0
            target.positionMs = ycore_session_state_position_ms(handle)
            target.durationMs = ycore_session_state_duration_ms(handle)
            target.bufferedPositionMs = ycore_session_state_buffered_position_ms(handle)
            target.speed = ycore_session_state_speed(handle)
            target.engine = ycore_session_state_engine(handle).toString()
            target.error = if (target.phase == PlaybackPhase.Failed) { ycore_session_state_reason(handle).toString() } else { "" }
        }
    }

    public func release(): Unit {
        if (!released && !handle.isNull()) {
            unsafe { ycore_session_destroy(handle) }
        }
        released = true
    }
}

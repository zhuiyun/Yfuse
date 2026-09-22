package tests.player

// Link-only stubs for the YCore C ABI.
//
// The player package declares these `foreign` functions so `NativeYPlayer` and
// `NativeBuildCapabilities` can reach the native engine. None of the tests in this package
// exercise that path: they cover the pure routing, subtitle, output, disc and timer decisions.
// The stubs exist so a host test binary links at all, and they deliberately report a session that
// can do nothing, so a test that wandered into the native path would fail rather than pass on a
// convincing-looking fake.

@C
public func ycore_session_create(): CPointer<Unit> {
    return CPointer<Unit>()
}

@C
public func ycore_session_destroy(_: CPointer<Unit>): Unit {}

@C
public func ycore_session_play(_: CPointer<Unit>): Int32 {
    return -1
}

@C
public func ycore_session_pause(_: CPointer<Unit>): Int32 {
    return -1
}

@C
public func ycore_session_seek_to(_: CPointer<Unit>, _: Int64): Int32 {
    return -1
}

@C
public func ycore_session_set_speed(_: CPointer<Unit>, _: Float32): Int32 {
    return -1
}

@C
public func ycore_session_retry(_: CPointer<Unit>): Int32 {
    return -1
}

@C
public func ycore_session_tick(_: CPointer<Unit>): Int32 {
    return -1
}

@C
public func ycore_session_handover(_: CPointer<Unit>): Int32 {
    return -1
}

@C
public func ycore_session_open_values(
    _: CPointer<Unit>,
    _: CString,
    _: CString,
    _: CString,
    _: CString,
    _: CString,
    _: Int64,
    _: Int32,
    _: UInt64
): Int32 {
    return -1
}

@C
public func ycore_session_select_track(_: CPointer<Unit>, _: Int32, _: CString): Int32 {
    return -1
}

@C
public func ycore_session_state_phase(_: CPointer<Unit>): Int32 {
    return 0
}

@C
public func ycore_session_state_route(_: CPointer<Unit>): Int32 {
    return 0
}

@C
public func ycore_session_state_playing(_: CPointer<Unit>): Int32 {
    return 0
}

@C
public func ycore_session_state_playback_requested(_: CPointer<Unit>): Int32 {
    return 0
}

@C
public func ycore_session_state_buffering(_: CPointer<Unit>): Int32 {
    return 0
}

@C
public func ycore_session_state_position_ms(_: CPointer<Unit>): Int64 {
    return 0
}

@C
public func ycore_session_state_duration_ms(_: CPointer<Unit>): Int64 {
    return 0
}

@C
public func ycore_session_state_buffered_position_ms(_: CPointer<Unit>): Int64 {
    return 0
}

@C
public func ycore_session_state_speed(_: CPointer<Unit>): Float32 {
    return 1.0
}

@C
public func ycore_session_state_engine(_: CPointer<Unit>): CString {
    return CString(CPointer<UInt8>())
}

@C
public func ycore_session_state_reason(_: CPointer<Unit>): CString {
    return CString(CPointer<UInt8>())
}

@C
public func ycore_get_compiled_capabilities(): UInt64 {
    return 0
}

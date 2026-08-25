package com.yfuse.harmony.entry.player

foreign {
    func ycore_get_compiled_capabilities(): UInt64
}

public class NativeBuildCapabilities {
    public let mask: UInt64
    public init() { this.mask = unsafe { ycore_get_compiled_capabilities() } }
    public prop ffmpeg: Bool { get() { (mask & CAP_CUSTOM_DEMUX) != 0 } }
    public prop nativeWindow: Bool { get() { (mask & CAP_NATIVE_WINDOW) != 0 } }
    public prop opticalDisc: Bool { get() { (mask & CAP_OPTICAL_DISC) != 0 } }
    public prop hardwareVideo: Bool { get() { (mask & CAP_HARDWARE_VIDEO) != 0 } }
}

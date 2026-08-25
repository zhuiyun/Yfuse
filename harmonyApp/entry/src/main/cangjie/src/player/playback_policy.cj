package com.yfuse.harmony.entry.player

public const CAP_REMOTE_URL: UInt64 = UInt64(1) << 0
public const CAP_REQUEST_HEADERS: UInt64 = UInt64(1) << 1
public const CAP_HARDWARE_VIDEO: UInt64 = UInt64(1) << 2
public const CAP_CUSTOM_DEMUX: UInt64 = UInt64(1) << 3
public const CAP_EXTERNAL_SUBTITLE: UInt64 = UInt64(1) << 4
public const CAP_BITMAP_SUBTITLE: UInt64 = UInt64(1) << 5
public const CAP_HDR_OUTPUT: UInt64 = UInt64(1) << 6
public const CAP_DOLBY_VISION_OUTPUT: UInt64 = UInt64(1) << 7
public const CAP_ENCODED_AUDIO_OUTPUT: UInt64 = UInt64(1) << 8
public const CAP_OPTICAL_DISC: UInt64 = UInt64(1) << 9
public const CAP_DISC_MENU: UInt64 = UInt64(1) << 10
public const CAP_DRM: UInt64 = UInt64(1) << 11
public const CAP_NATIVE_WINDOW: UInt64 = UInt64(1) << 12

public class PlaybackRequirements {
    public var remote: Bool = true
    public var requestHeaders: Bool = false
    public var customDemux: Bool = false
    public var bitmapSubtitle: Bool = false
    public var hdr: Bool = false
    public var dolbyVision: Bool = false
    public var encodedAudio: Bool = false
    public var opticalDisc: Bool = false
    public var discMenu: Bool = false
    public var drm: Bool = false

    public func mask(): UInt64 {
        var result: UInt64 = CAP_HARDWARE_VIDEO
        if (remote) { result |= CAP_REMOTE_URL }
        if (requestHeaders) { result |= CAP_REQUEST_HEADERS }
        if (customDemux) { result |= CAP_CUSTOM_DEMUX }
        if (bitmapSubtitle) { result |= CAP_BITMAP_SUBTITLE }
        if (hdr) { result |= CAP_HDR_OUTPUT }
        if (dolbyVision) { result |= CAP_DOLBY_VISION_OUTPUT }
        if (encodedAudio) { result |= CAP_ENCODED_AUDIO_OUTPUT }
        if (opticalDisc) { result |= CAP_OPTICAL_DISC }
        if (discMenu) { result |= CAP_DISC_MENU }
        if (drm) { result |= CAP_DRM }
        return result
    }
}

public enum RouteDecision {
    | System
    | Native
    | Unsupported(String)
}

/** The system route wins when it can satisfy requirements; native is never selected without a surface. */
public func decidePlaybackRoute(
    requirements: PlaybackRequirements,
    systemCapabilities: UInt64,
    nativeCapabilities: UInt64,
    nativeSurfaceAvailable: Bool
): RouteDecision {
    let required = requirements.mask()
    if ((systemCapabilities & required) == required) { return RouteDecision.System }
    if (nativeSurfaceAvailable && (nativeCapabilities & required) == required) { return RouteDecision.Native }
    if (requirements.opticalDisc && !nativeSurfaceAvailable) {
        return RouteDecision.Unsupported("当前仓颉 ArkUI SDK 未提供可验证的原生渲染 Surface，暂不能播放 ISO/BDMV")
    }
    return RouteDecision.Unsupported("当前设备与已安装播放器内核无法满足此媒体的解码或输出要求")
}

public func mayAutomaticHandover(category: String): Bool {
    return category != "authorization" && category != "drm"
}

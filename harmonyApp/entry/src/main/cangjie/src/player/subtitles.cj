package com.yfuse.harmony.entry.player

import std.collection.*

public enum SubtitleFormat { | Srt | WebVtt | Ass | Pgs | ExternalText | Unknown }
public enum SubtitlePosition { | Top | Middle | Bottom }

public class SubtitleStyle {
    public var fontScale: Float64 = 1.0
    public var textArgb: UInt32 = 0xFFFFFFFF
    public var outlineArgb: UInt32 = 0xFF000000
    public var outlineWidth: Float64 = 2.0
    public var backgroundArgb: UInt32 = 0x66000000
    public var position: SubtitlePosition = SubtitlePosition.Bottom
    public var bottomMarginVp: Float64 = 34.0
}

public class SubtitleCue {
    public let id: String
    public let startMs: Int64
    public let endMs: Int64
    public let text: String
    public let bitmapHandle: Int64
    public init(id: String, startMs: Int64, endMs: Int64, text!: String = "", bitmapHandle!: Int64 = 0) {
        this.id = id; this.startMs = startMs; this.endMs = endMs
        this.text = text; this.bitmapHandle = bitmapHandle
    }
}

public class SubtitleTrackModel {
    public let id: String
    public let label: String
    public let language: String
    public let format: SubtitleFormat
    public let external: Bool
    public init(id: String, label: String, language: String, format: SubtitleFormat, external!: Bool = false) {
        this.id = id; this.label = label; this.language = language; this.format = format; this.external = external
    }
}

public class DualSubtitleState {
    public var primaryTrackId: String = ""
    public var secondaryTrackId: String = ""
    public var offsetMs: Int64 = 0
    public var primary: ArrayList<SubtitleCue> = ArrayList<SubtitleCue>()
    public var secondary: ArrayList<SubtitleCue> = ArrayList<SubtitleCue>()

    public func visibleAt(positionMs: Int64): (ArrayList<SubtitleCue>, ArrayList<SubtitleCue>) {
        let first = ArrayList<SubtitleCue>()
        let second = ArrayList<SubtitleCue>()
        let adjusted = positionMs + offsetMs
        for (cue in primary) { if (cue.startMs <= adjusted && cue.endMs > adjusted) { first.append(cue) } }
        for (cue in secondary) { if (cue.startMs <= adjusted && cue.endMs > adjusted) { second.append(cue) } }
        return (first, second)
    }

    public func setOffset(valueMs: Int64): Unit {
        offsetMs = if (valueMs < -30000) { -30000 } else if (valueMs > 30000) { 30000 } else { valueMs }
    }
}

public enum SubtitleRouteDecision {
    | SystemTrack
    | AppTextOverlay
    | NativeLibass
    | NativeBitmap
    | Unsupported(String)
}

public func subtitleRoute(format: SubtitleFormat, external: Bool, nativeCapabilities: UInt64): SubtitleRouteDecision {
    return match (format) {
        case SubtitleFormat.Srt | SubtitleFormat.WebVtt | SubtitleFormat.ExternalText =>
            if (external) { SubtitleRouteDecision.AppTextOverlay } else { SubtitleRouteDecision.SystemTrack }
        case SubtitleFormat.Ass =>
            if ((nativeCapabilities & CAP_EXTERNAL_SUBTITLE) != 0 && (nativeCapabilities & CAP_NATIVE_WINDOW) != 0) {
                SubtitleRouteDecision.NativeLibass
            } else {
                SubtitleRouteDecision.Unsupported("当前构建没有可渲染 ASS 样式的 libass + NativeWindow 链路")
            }
        case SubtitleFormat.Pgs =>
            if ((nativeCapabilities & CAP_BITMAP_SUBTITLE) != 0 && (nativeCapabilities & CAP_NATIVE_WINDOW) != 0) {
                SubtitleRouteDecision.NativeBitmap
            } else {
                SubtitleRouteDecision.Unsupported("当前构建没有 PGS 位图字幕渲染链路")
            }
        case SubtitleFormat.Unknown => SubtitleRouteDecision.Unsupported("未知字幕格式")
    }
}

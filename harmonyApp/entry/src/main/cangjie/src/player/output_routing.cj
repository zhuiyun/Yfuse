package com.yfuse.harmony.entry.player

public enum DynamicRange { | Sdr | Hdr10 | Hlg | DolbyVision }
public enum AudioOutputMode { | Pcm | EncodedPassthrough }

public class OutputCapabilities {
    public var hdr10: Bool = false
    public var hlg: Bool = false
    public var dolbyVision: Bool = false
    public var encodedAc3: Bool = false
    public var encodedEac3: Bool = false
    public var encodedTrueHd: Bool = false
    public var encodedDts: Bool = false
    public var encodedDtsHd: Bool = false
}

public class OutputEvidence {
    public let requested: String
    public let decoder: String
    public let renderer: String
    public let sink: String
    public let verified: Bool
    public init(requested: String, decoder: String, renderer: String, sink: String, verified: Bool) {
        this.requested = requested; this.decoder = decoder; this.renderer = renderer
        this.sink = sink; this.verified = verified
    }
}

public enum OutputDecision {
    | Direct
    | DecodeToPcm
    | ToneMapToSdr
    | ServerTranscode
    | Unsupported(String)
}

public func dynamicRangeDecision(range: DynamicRange, output: OutputCapabilities, nativeCapabilities: UInt64): OutputDecision {
    return match (range) {
        case DynamicRange.Sdr => OutputDecision.Direct
        case DynamicRange.Hdr10 =>
            if (output.hdr10) { OutputDecision.Direct }
            else if ((nativeCapabilities & CAP_HDR_OUTPUT) != 0) { OutputDecision.ToneMapToSdr }
            else { OutputDecision.ServerTranscode }
        case DynamicRange.Hlg =>
            if (output.hlg) { OutputDecision.Direct }
            else if ((nativeCapabilities & CAP_HDR_OUTPUT) != 0) { OutputDecision.ToneMapToSdr }
            else { OutputDecision.ServerTranscode }
        case DynamicRange.DolbyVision =>
            if (output.dolbyVision && (nativeCapabilities & CAP_DOLBY_VISION_OUTPUT) != 0) { OutputDecision.Direct }
            else { OutputDecision.ServerTranscode }
    }
}

public func audioDecision(codec: String, output: OutputCapabilities, passthroughRequested: Bool): OutputDecision {
    if (!passthroughRequested) { return OutputDecision.DecodeToPcm }
    let supported = match (codec) {
        case "ac3" => output.encodedAc3
        case "eac3" => output.encodedEac3
        case "truehd" => output.encodedTrueHd
        case "dts" => output.encodedDts
        case "dtshd" => output.encodedDtsHd
        case _ => false
    }
    return if (supported) { OutputDecision.Direct } else { OutputDecision.DecodeToPcm }
}

/** UI badges may only say “active” when the whole output chain has evidence. */
public func verifiedOutputBadge(evidence: OutputEvidence): String {
    return if (evidence.verified && !evidence.decoder.isEmpty() && !evidence.renderer.isEmpty() && !evidence.sink.isEmpty()) {
        "${evidence.requested} 已输出"
    } else {
        "${evidence.requested} 已请求（未验证输出）"
    }
}

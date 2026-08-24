package com.yfuse.harmony.entry.player

import encoding.json.*

public class PlaybackEvidence {
    public var sourceId: String = ""
    public var route: String = ""
    public var engine: String = ""
    public var container: String = ""
    public var videoCodec: String = ""
    public var audioCodec: String = ""
    public var decoder: String = ""
    public var renderer: String = ""
    public var audioSink: String = ""
    public var hdrRequested: String = "SDR"
    public var hdrOutputVerified: Bool = false
    public var dolbyVisionOutputVerified: Bool = false
    public var encodedAudioOutputVerified: Bool = false
    public var avSyncOffsetMs: Int64 = 0
    public var droppedFrames: Int64 = 0
    public var rebufferCount: Int64 = 0
    public var handoverCount: Int64 = 0
    public var failureCategory: String = ""
    public var failureReason: String = ""

    /** Tokens and full URLs are intentionally excluded from exported evidence. */
    public func toJson(): String {
        let value = JsonObject()
        value.put("sourceId", JsonString(sourceId))
        value.put("route", JsonString(route))
        value.put("engine", JsonString(engine))
        value.put("container", JsonString(container))
        value.put("videoCodec", JsonString(videoCodec))
        value.put("audioCodec", JsonString(audioCodec))
        value.put("decoder", JsonString(decoder))
        value.put("renderer", JsonString(renderer))
        value.put("audioSink", JsonString(audioSink))
        value.put("hdrRequested", JsonString(hdrRequested))
        value.put("hdrOutputVerified", JsonBool(hdrOutputVerified))
        value.put("dolbyVisionOutputVerified", JsonBool(dolbyVisionOutputVerified))
        value.put("encodedAudioOutputVerified", JsonBool(encodedAudioOutputVerified))
        value.put("avSyncOffsetMs", JsonInt(avSyncOffsetMs))
        value.put("droppedFrames", JsonInt(droppedFrames))
        value.put("rebufferCount", JsonInt(rebufferCount))
        value.put("handoverCount", JsonInt(handoverCount))
        value.put("failureCategory", JsonString(failureCategory))
        value.put("failureReason", JsonString(failureReason))
        return value.toJsonString()
    }
}

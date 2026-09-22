package com.yfuse.harmony.entry.player

/** What actually happened on the last playback attempt; serialized by the export module. */
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
}

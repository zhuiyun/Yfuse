package com.yfuse.harmony.entry.support

import stdx.encoding.json.*
import std.collection.*
import com.yfuse.harmony.entry.data.*
import com.yfuse.harmony.entry.player.*

public class DiagnosticSnapshot {
    public let appVersion: String
    public let osVersion: String
    public let deviceModel: String
    public let networkType: String
    public let playback: Option<PlaybackEvidence>
    public let recentEvents: ArrayList<String>
    public init(
        appVersion: String,
        osVersion: String,
        deviceModel: String,
        networkType: String,
        playback!: Option<PlaybackEvidence> = None,
        recentEvents!: ArrayList<String> = ArrayList<String>()
    ) {
        this.appVersion = appVersion; this.osVersion = osVersion; this.deviceModel = deviceModel
        this.networkType = networkType; this.playback = playback; this.recentEvents = recentEvents
    }

    public func redactedJson(): String {
        let root = JsonObject()
        root.put("schemaVersion", JsonInt(1))
        root.put("appVersion", JsonString(appVersion))
        root.put("osVersion", JsonString(osVersion))
        root.put("deviceModel", JsonString(deviceModel))
        root.put("networkType", JsonString(networkType))
        if (let Some(value) <- playback) { root.put("playback", JsonValue.fromStr(playbackEvidenceJson(value))) }
        let events = JsonArray()
        for (event in recentEvents) { events.add(JsonString(redact(event))) }
        root.put("recentEvents", events)
        return root.toJsonString()
    }
}

private func redact(value: String): String {
    /* Event producers must avoid secrets; this second line of defense catches common header names. */
    if (value.contains("X-Emby-Token") || value.contains("X-Plex-Token") || value.contains("Authorization")) {
        return "[已移除包含凭据的诊断事件]"
    }
    return value
}

/**
 * Redacted playback evidence.
 *
 * This lives with the exporter rather than on `PlaybackEvidence` so the player package stays
 * free of a serialization dependency and remains testable on a host compiler. Tokens and full
 * URLs are never part of exported evidence.
 */
public func playbackEvidenceJson(evidence: PlaybackEvidence): String {
    let evidenceJson = JsonObject()
    evidenceJson.put("sourceId", JsonString(evidence.sourceId))
    evidenceJson.put("route", JsonString(evidence.route))
    evidenceJson.put("engine", JsonString(evidence.engine))
    evidenceJson.put("container", JsonString(evidence.container))
    evidenceJson.put("videoCodec", JsonString(evidence.videoCodec))
    evidenceJson.put("audioCodec", JsonString(evidence.audioCodec))
    evidenceJson.put("decoder", JsonString(evidence.decoder))
    evidenceJson.put("renderer", JsonString(evidence.renderer))
    evidenceJson.put("audioSink", JsonString(evidence.audioSink))
    evidenceJson.put("hdrRequested", JsonString(evidence.hdrRequested))
    evidenceJson.put("hdrOutputVerified", JsonBool(evidence.hdrOutputVerified))
    evidenceJson.put("dolbyVisionOutputVerified", JsonBool(evidence.dolbyVisionOutputVerified))
    evidenceJson.put("encodedAudioOutputVerified", JsonBool(evidence.encodedAudioOutputVerified))
    evidenceJson.put("avSyncOffsetMs", JsonInt(evidence.avSyncOffsetMs))
    evidenceJson.put("droppedFrames", JsonInt(evidence.droppedFrames))
    evidenceJson.put("rebufferCount", JsonInt(evidence.rebufferCount))
    evidenceJson.put("handoverCount", JsonInt(evidence.handoverCount))
    evidenceJson.put("failureCategory", JsonString(evidence.failureCategory))
    evidenceJson.put("failureReason", JsonString(evidence.failureReason))
    return evidenceJson.toJsonString()
}

public interface DiagnosticExportPort {
    func writeAndShare(fileName: String, content: String): ApiResult<Unit>
}

public class DiagnosticExporter {
    private let port: DiagnosticExportPort
    public init(port: DiagnosticExportPort) { this.port = port }
    public func export(snapshot: DiagnosticSnapshot): ApiResult<Unit> {
        return port.writeAndShare("yfuse-diagnostics.json", snapshot.redactedJson())
    }
}

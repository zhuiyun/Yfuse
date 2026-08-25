package com.yfuse.harmony.entry.support

import encoding.json.*
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
        if (let Some(value) <- playback) { root.put("playback", JsonValue.fromStr(value.toJson())) }
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

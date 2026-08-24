package com.yfuse.harmony.entry.player

import std.collection.*

public class SkipMarker {
    public let type: String
    public let startMs: Int64
    public let endMs: Int64
    public init(type: String, startMs: Int64, endMs: Int64) { this.type = type; this.startMs = startMs; this.endMs = endMs }
}

public enum SkipMode { | Off | Ask | Automatic }

public class SkipController {
    public var introMode: SkipMode = SkipMode.Ask
    public var creditsMode: SkipMode = SkipMode.Ask
    private let consumed = HashSet<String>()

    public func actionAt(positionMs: Int64, markers: ArrayList<SkipMarker>): Option<(String, Int64, Bool)> {
        for (marker in markers) {
            let key = "${marker.type}:${marker.startMs}:${marker.endMs}"
            if (positionMs >= marker.startMs && positionMs < marker.endMs && !consumed.contains(key)) {
                let mode = if (marker.type == "credits") { creditsMode } else { introMode }
                match (mode) {
                    case SkipMode.Off => consumed.add(key)
                    case SkipMode.Ask => return Some((marker.type, marker.endMs, false))
                    case SkipMode.Automatic => consumed.add(key); return Some((marker.type, marker.endMs, true))
                }
            }
        }
        return None
    }
}

public enum SleepTimerMode { | Off | AfterMinutes(Int64) | EndOfEpisode }

public class SleepTimer {
    public var mode: SleepTimerMode = SleepTimerMode.Off
    private var deadlineEpochMs: Int64 = 0
    public func startAfter(nowEpochMs: Int64, minutes: Int64): Unit {
        let safe = if (minutes < 1) { 1 } else if (minutes > 240) { 240 } else { minutes }
        deadlineEpochMs = nowEpochMs + safe * 60000
        mode = SleepTimerMode.AfterMinutes(safe)
    }
    public func shouldStop(nowEpochMs: Int64, ended: Bool): Bool {
        return match (mode) {
            case SleepTimerMode.Off => false
            case SleepTimerMode.AfterMinutes(_) => nowEpochMs >= deadlineEpochMs
            case SleepTimerMode.EndOfEpisode => ended
        }
    }
}

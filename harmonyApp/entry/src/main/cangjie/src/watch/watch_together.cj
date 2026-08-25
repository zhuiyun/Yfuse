package com.yfuse.harmony.entry.watch

import std.collection.*

public enum RoomRole { | Host | Guest }
public enum RoomConnection { | Disconnected | Connecting | Connected | Reconnecting }

public class Participant {
    public let id: String
    public let name: String
    public let avatar: String
    public var online: Bool
    public init(id: String, name: String, avatar: String, online!: Bool = true) {
        this.id = id; this.name = name; this.avatar = avatar; this.online = online
    }
}

public enum RoomEvent {
    | Play(Int64, Int64)
    | Pause(Int64, Int64)
    | Seek(Int64, Int64)
    | Heartbeat(Int64, Bool, Int64)
    | Chat(String, String, Int64)
    | Reaction(String, String, Int64)
}

public enum DriftCorrection {
    | None
    | SetSpeed(Float32, Int64)
    | SeekTo(Int64)
}

public class WatchRoomState {
    public var roomId: String = ""
    public var inviteCode: String = ""
    public var role: RoomRole = RoomRole.Guest
    public var connection: RoomConnection = RoomConnection.Disconnected
    public var participants: ArrayList<Participant> = ArrayList<Participant>()
    public var lastSequence: Int64 = 0

    /** Small drift is corrected smoothly; large drift seeks. Host is authoritative. */
    public func correction(localPositionMs: Int64, hostPositionMs: Int64, baseSpeed: Float32): DriftCorrection {
        let drift = hostPositionMs - localPositionMs
        let magnitude = if (drift < 0) { -drift } else { drift }
        if (magnitude < 250) { return DriftCorrection.None }
        if (magnitude <= 1800) {
            let delta: Float32 = if (drift > 0) { 0.05 } else { -0.05 }
            return DriftCorrection.SetSpeed(baseSpeed + delta, 5000)
        }
        return DriftCorrection.SeekTo(hostPositionMs)
    }

    public func accept(sequence: Int64): Bool {
        if (sequence <= lastSequence) { return false }
        lastSequence = sequence
        return true
    }
}

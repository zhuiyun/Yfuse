package com.yfuse.harmony.entry.player

public enum RemoteCommand {
    | Play
    | Pause
    | SeekTo(Int64)
    | Next
    | Previous
    | Stop
}

public interface PlaybackSystemPort {
    func publishMetadata(title: String, subtitle: String, durationMs: Int64): Unit
    func publishState(playing: Bool, positionMs: Int64, speed: Float32): Unit
    func startBackgroundSession(): Unit
    func stopBackgroundSession(): Unit
    func requestPictureInPicture(): Bool
    func updatePictureInPicture(playing: Bool): Unit
}

/** Keeps notification/lock-screen/PiP state driven by YPlayer instead of duplicating truth. */
public class PlaybackSystemCoordinator {
    private let player: YPlayer
    private let system: PlaybackSystemPort
    private var inPictureInPicture: Bool = false

    public init(player: YPlayer, system: PlaybackSystemPort) { this.player = player; this.system = system }

    public func begin(title: String, subtitle: String): Unit {
        system.startBackgroundSession()
        system.publishMetadata(title, subtitle, player.state.durationMs)
        publish()
    }

    public func handle(command: RemoteCommand): Unit {
        match (command) {
            case RemoteCommand.Play => player.play()
            case RemoteCommand.Pause => player.pause()
            case RemoteCommand.SeekTo(value) => player.seekTo(value)
            case RemoteCommand.Next => ()
            case RemoteCommand.Previous => player.seekTo(0)
            case RemoteCommand.Stop => player.release()
        }
        publish()
    }

    public func enterPictureInPicture(): Bool {
        inPictureInPicture = system.requestPictureInPicture()
        if (inPictureInPicture) { system.updatePictureInPicture(player.state.playing) }
        return inPictureInPicture
    }

    public func publish(): Unit {
        system.publishState(player.state.playing, player.state.positionMs, player.state.speed)
        if (inPictureInPicture) { system.updatePictureInPicture(player.state.playing) }
    }

    public func end(): Unit { player.release(); system.stopBackgroundSession() }
}

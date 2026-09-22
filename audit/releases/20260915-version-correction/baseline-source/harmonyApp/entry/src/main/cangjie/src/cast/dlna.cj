package com.yfuse.harmony.entry.cast

import std.collection.*
import com.yfuse.harmony.entry.data.*

public enum CastDeviceState { | Discovered | Connecting | Connected | Unavailable }

public class CastDevice {
    public let id: String
    public let name: String
    public let model: String
    public var state: CastDeviceState
    public init(id: String, name: String, model: String, state!: CastDeviceState = CastDeviceState.Discovered) {
        this.id = id; this.name = name; this.model = model; this.state = state
    }
}

public class CastMedia {
    public let title: String
    public let uri: String
    public let mimeType: String
    public let durationMs: Int64
    public let posterUrl: String
    public init(title: String, uri: String, mimeType: String, durationMs: Int64, posterUrl!: String = "") {
        this.title = title; this.uri = uri; this.mimeType = mimeType; this.durationMs = durationMs; this.posterUrl = posterUrl
    }
}

public interface CastDiscoveryPort {
    func start(onDevices: (ArrayList<CastDevice>) -> Unit): Unit
    func stop(): Unit
}

public interface CastSessionPort {
    func connect(device: CastDevice, completion: (ApiResult<Unit>) -> Unit): Unit
    func load(media: CastMedia, startPositionMs: Int64, completion: (ApiResult<Unit>) -> Unit): Unit
    func play(): Unit
    func pause(): Unit
    func seekTo(positionMs: Int64): Unit
    func close(): Unit
}

/** Credentialed streams are cast through an app-bound proxy; tokens are never put in DIDL URLs. */
public interface CastProxyPort {
    func publish(upstreamUrl: String, headersJson: String): ApiResult<String>
    func revokeAll(): Unit
}

public class CastCoordinator {
    private let session: CastSessionPort
    private let proxy: CastProxyPort
    public var activeDevice: Option<CastDevice> = None

    public init(session: CastSessionPort, proxy: CastProxyPort) { this.session = session; this.proxy = proxy }

    public func load(
        media: CastMedia,
        headersJson: String,
        startPositionMs: Int64,
        completion: (ApiResult<Unit>) -> Unit
    ): Unit {
        if (headersJson.isEmpty() || headersJson == "{}") {
            session.load(media, startPositionMs, completion)
            return
        }
        match (proxy.publish(media.uri, headersJson)) {
            case Failure(problem) => completion(ApiResult<Unit>.Failure(problem))
            case Success(proxyUrl) =>
                session.load(CastMedia(media.title, proxyUrl, media.mimeType, media.durationMs, posterUrl: media.posterUrl), startPositionMs, completion)
        }
    }

    public func close(): Unit { session.close(); proxy.revokeAll(); activeDevice = None }
}

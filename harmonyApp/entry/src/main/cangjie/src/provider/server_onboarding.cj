package com.yfuse.harmony.entry.provider

import std.collection.*
import encoding.json.*
import com.yfuse.harmony.entry.data.*
import com.yfuse.harmony.entry.network.*

public class DiscoveredServer {
    public let provider: ProviderKind
    public let name: String
    public let address: String
    public let serverId: String
    public init(provider: ProviderKind, name: String, address: String, serverId!: String = "") {
        this.provider = provider; this.name = name; this.address = address; this.serverId = serverId
    }
}

public interface LanDiscoveryPort {
    func start(onUpdate: (ArrayList<DiscoveredServer>) -> Unit, onError: (ApiFailure) -> Unit): Unit
    func stop(): Unit
}

public enum QuickConnectPhase { | Idle | Requesting | Waiting | Authorized | Expired | Failed }

public class QuickConnectState {
    public var phase: QuickConnectPhase = QuickConnectPhase.Idle
    public var secret: String = ""
    public var code: String = ""
    public var expiresAtMs: Int64 = 0
    public var error: String = ""
}

public class QuickConnectApi {
    private let transport: HttpTransport
    public init(transport: HttpTransport) { this.transport = transport }

    public func initiate(baseUrl: String, deviceId: String, completion: (ApiResult<HttpResponsePayload>) -> Unit): Unit {
        let headers = HashMap<String, String>()
        headers["Accept"] = "application/json"
        headers["X-Emby-Authorization"] = "MediaBrowser Client=\"Yfuse\", Device=\"HarmonyOS\", DeviceId=\"${deviceId}\", Version=\"1.0.0\""
        match (endpointPath(baseUrl, "/QuickConnect/Initiate")) {
            case Failure(problem) => completion(ApiResult<HttpResponsePayload>.Failure(problem))
            case Success(url) => transport.execute(HttpRequestSpec(HttpMethod.Post, url, headers: headers, credentialOrigin: baseUrl), completion)
        }
    }

    public func check(baseUrl: String, secret: String, completion: (ApiResult<HttpResponsePayload>) -> Unit): Unit {
        let query = ArrayList<(String, String)>()
        query.append(("Secret", secret))
        match (endpointPath(baseUrl, "/QuickConnect/Connect")) {
            case Failure(problem) => completion(ApiResult<HttpResponsePayload>.Failure(problem))
            case Success(url) => transport.execute(HttpRequestSpec(
                HttpMethod.Get,
                url + encodedQuery(query),
                credentialOrigin: baseUrl
            ), completion)
        }
    }

    public func authenticate(baseUrl: String, secret: String, deviceId: String, completion: (ApiResult<HttpResponsePayload>) -> Unit): Unit {
        let headers = HashMap<String, String>()
        headers["Accept"] = "application/json"
        headers["Content-Type"] = "application/json"
        headers["X-Emby-Authorization"] = "MediaBrowser Client=\"Yfuse\", Device=\"HarmonyOS\", DeviceId=\"${deviceId}\", Version=\"1.0.0\""
        let body = "{\"Secret\":${jsonQuoted(secret)}}"
        match (endpointPath(baseUrl, "/Users/AuthenticateWithQuickConnect")) {
            case Failure(problem) => completion(ApiResult<HttpResponsePayload>.Failure(problem))
            case Success(url) => transport.execute(HttpRequestSpec(HttpMethod.Post, url, headers: headers, body: body, credentialOrigin: baseUrl), completion)
        }
    }
}

private func jsonQuoted(value: String): String {
    return JsonString(value).toJsonString()
}

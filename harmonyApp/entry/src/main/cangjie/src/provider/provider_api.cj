package com.yfuse.harmony.entry.provider

import std.collection.*
import com.yfuse.harmony.entry.data.*
import com.yfuse.harmony.entry.network.*

public interface ProviderApi {
    prop kind: ProviderKind
    func probe(baseUrl: String): ApiResult<HttpRequestSpec>
    func authenticate(baseUrl: String, bodyJson: String, deviceId: String): ApiResult<HttpRequestSpec>
    func home(server: SavedServer, token: String): ApiResult<HttpRequestSpec>
    func search(server: SavedServer, token: String, query: String, startIndex: Int64, limit: Int64): ApiResult<HttpRequestSpec>
    func detail(server: SavedServer, token: String, itemId: String): ApiResult<HttpRequestSpec>
    func playbackInfo(server: SavedServer, token: String, itemId: String, bodyJson: String): ApiResult<HttpRequestSpec>
    func progress(server: SavedServer, token: String, bodyJson: String): ApiResult<HttpRequestSpec>
}

private func embyHeaders(token: String, deviceId!: String = ""): HashMap<String, String> {
    let headers = HashMap<String, String>()
    headers["Accept"] = "application/json"
    headers["Content-Type"] = "application/json"
    let authorization = "MediaBrowser Client=\"Yfuse\", Device=\"HarmonyOS\", DeviceId=\"${deviceId}\", Version=\"1.0.0\""
    headers["X-Emby-Authorization"] = authorization
    if (!token.isEmpty()) { headers["X-Emby-Token"] = token }
    return headers
}

private func requestAt(
    serverUrl: String,
    path: String,
    method: HttpMethod,
    headers: HashMap<String, String>,
    query!: ArrayList<(String, String)> = ArrayList<(String, String)>(),
    body!: String = "",
    credentialed!: Bool = true
): ApiResult<HttpRequestSpec> {
    return match (endpointPath(serverUrl, path)) {
        case Success(url) => ApiResult<HttpRequestSpec>.Success(HttpRequestSpec(
            method,
            url + encodedQuery(query),
            headers: headers,
            body: body,
            credentialOrigin: if (credentialed) { serverUrl } else { "" }
        ))
        case Failure(problem) => ApiResult<HttpRequestSpec>.Failure(problem)
    }
}

/** Emby and Jellyfin share the MediaBrowser API but keep distinct product identities. */
public class EmbyCompatibleProviderApi <: ProviderApi {
    private let providerKind: ProviderKind

    public init(kind: ProviderKind) {
        if (kind == ProviderKind.Plex) { throw IllegalArgumentException("Plex requires PlexProviderApi") }
        this.providerKind = kind
    }

    public prop kind: ProviderKind { get() { providerKind } }

    public func probe(baseUrl: String): ApiResult<HttpRequestSpec> {
        return requestAt(baseUrl, "/System/Info/Public", HttpMethod.Get, embyHeaders(""), credentialed: false)
    }

    public func authenticate(baseUrl: String, bodyJson: String, deviceId: String): ApiResult<HttpRequestSpec> {
        return requestAt(baseUrl, "/Users/AuthenticateByName", HttpMethod.Post, embyHeaders("", deviceId), body: bodyJson, credentialed: false)
    }

    public func home(server: SavedServer, token: String): ApiResult<HttpRequestSpec> {
        let query = ArrayList<(String, String)>()
        query.append(("UserId", server.userId))
        query.append(("Limit", "30"))
        query.append(("Fields", "Overview,PrimaryImageAspectRatio,BackdropImageTags,ProviderIds,DateCreated,RunTimeTicks"))
        query.append(("EnableUserData", "true"))
        query.append(("Recursive", "true"))
        query.append(("SortBy", "DateCreated"))
        query.append(("SortOrder", "Descending"))
        return requestAt(server.baseUrl, "/Users/${server.userId}/Items", HttpMethod.Get, embyHeaders(token), query: query)
    }

    public func search(server: SavedServer, token: String, query: String, startIndex: Int64, limit: Int64): ApiResult<HttpRequestSpec> {
        let values = ArrayList<(String, String)>()
        values.append(("UserId", server.userId))
        values.append(("SearchTerm", query))
        values.append(("Recursive", "true"))
        values.append(("IncludeItemTypes", "Movie,Series,Episode"))
        values.append(("Fields", "Overview,PrimaryImageAspectRatio,BackdropImageTags,ProviderIds,RunTimeTicks"))
        values.append(("StartIndex", startIndex.toString()))
        values.append(("Limit", limit.toString()))
        return requestAt(server.baseUrl, "/Users/${server.userId}/Items", HttpMethod.Get, embyHeaders(token), query: values)
    }

    public func detail(server: SavedServer, token: String, itemId: String): ApiResult<HttpRequestSpec> {
        return requestAt(server.baseUrl, "/Users/${server.userId}/Items/${itemId}", HttpMethod.Get, embyHeaders(token))
    }

    public func playbackInfo(server: SavedServer, token: String, itemId: String, bodyJson: String): ApiResult<HttpRequestSpec> {
        let query = ArrayList<(String, String)>()
        query.append(("UserId", server.userId))
        return requestAt(server.baseUrl, "/Items/${itemId}/PlaybackInfo", HttpMethod.Post, embyHeaders(token), query: query, body: bodyJson)
    }

    public func progress(server: SavedServer, token: String, bodyJson: String): ApiResult<HttpRequestSpec> {
        return requestAt(server.baseUrl, "/Sessions/Playing/Progress", HttpMethod.Post, embyHeaders(token), body: bodyJson)
    }
}

public class PlexProviderApi <: ProviderApi {
    public prop kind: ProviderKind { get() { ProviderKind.Plex } }

    private func headers(token: String): HashMap<String, String> {
        let values = HashMap<String, String>()
        values["Accept"] = "application/json"
        values["X-Plex-Product"] = "Yfuse"
        values["X-Plex-Version"] = "1.0.0"
        values["X-Plex-Platform"] = "HarmonyOS"
        if (!token.isEmpty()) { values["X-Plex-Token"] = token }
        return values
    }

    public func probe(baseUrl: String): ApiResult<HttpRequestSpec> {
        return requestAt(baseUrl, "/identity", HttpMethod.Get, headers(""), credentialed: false)
    }

    /** bodyJson contains the already issued Plex PIN exchange payload. */
    public func authenticate(baseUrl: String, bodyJson: String, deviceId: String): ApiResult<HttpRequestSpec> {
        let values = headers("")
        values["X-Plex-Client-Identifier"] = deviceId
        return ApiResult<HttpRequestSpec>.Success(HttpRequestSpec(
            HttpMethod.Post,
            "https://plex.tv/api/v2/pins",
            headers: values,
            body: bodyJson
        ))
    }

    public func home(server: SavedServer, token: String): ApiResult<HttpRequestSpec> {
        return requestAt(server.baseUrl, "/hubs/home", HttpMethod.Get, headers(token))
    }

    public func search(server: SavedServer, token: String, query: String, startIndex: Int64, limit: Int64): ApiResult<HttpRequestSpec> {
        let values = ArrayList<(String, String)>()
        values.append(("query", query))
        values.append(("limit", limit.toString()))
        values.append(("X-Plex-Container-Start", startIndex.toString()))
        return requestAt(server.baseUrl, "/hubs/search", HttpMethod.Get, headers(token), query: values)
    }

    public func detail(server: SavedServer, token: String, itemId: String): ApiResult<HttpRequestSpec> {
        return requestAt(server.baseUrl, "/library/metadata/${itemId}", HttpMethod.Get, headers(token))
    }

    public func playbackInfo(server: SavedServer, token: String, itemId: String, bodyJson: String): ApiResult<HttpRequestSpec> {
        let values = ArrayList<(String, String)>()
        values.append(("path", "/library/metadata/${itemId}"))
        values.append(("directPlay", "1"))
        values.append(("directStream", "1"))
        return requestAt(server.baseUrl, "/video/:/transcode/universal/decision", HttpMethod.Get, headers(token), query: values)
    }

    public func progress(server: SavedServer, token: String, bodyJson: String): ApiResult<HttpRequestSpec> {
        return requestAt(server.baseUrl, "/:/timeline", HttpMethod.Get, headers(token), body: bodyJson)
    }
}

package com.yfuse.harmony.entry.provider

import std.collection.*
import com.yfuse.harmony.entry.data.*
import com.yfuse.harmony.entry.network.*

public enum DiscoveryList { | Trending | Popular | Upcoming | NowPlaying | Calendar }

/** TMDB credentials are supplied by build/user secure settings and never embedded in source. */
public class TmdbRepository {
    private let transport: HttpTransport
    private let endpoint: String
    private let bearerToken: String
    public init(transport: HttpTransport, endpoint: String, bearerToken: String) {
        this.transport = transport; this.endpoint = endpoint; this.bearerToken = bearerToken
    }

    public func request(list: DiscoveryList, page: Int64, completion: (ApiResult<HttpResponsePayload>) -> Unit): Unit {
        if (bearerToken.isEmpty()) {
            completion(ApiResult<HttpResponsePayload>.Failure(ApiFailure(FailureKind.Unauthorized, "未配置 TMDB 凭据")))
            return
        }
        let path = match (list) {
            case DiscoveryList.Trending => "/3/trending/all/week"
            case DiscoveryList.Popular => "/3/movie/popular"
            case DiscoveryList.Upcoming => "/3/movie/upcoming"
            case DiscoveryList.NowPlaying => "/3/movie/now_playing"
            case DiscoveryList.Calendar => "/3/tv/on_the_air"
        }
        let query = ArrayList<(String, String)>()
        query.append(("language", "zh-CN")); query.append(("page", page.toString()))
        let headers = HashMap<String, String>()
        headers["Authorization"] = "Bearer ${bearerToken}"
        headers["Accept"] = "application/json"
        match (endpointPath(endpoint, path)) {
            case Failure(problem) => completion(ApiResult<HttpResponsePayload>.Failure(problem))
            case Success(url) => transport.execute(HttpRequestSpec(HttpMethod.Get, url + encodedQuery(query), headers: headers, credentialOrigin: endpoint), completion)
        }
    }
}

package com.yfuse.harmony.entry.provider

import std.collection.*
import com.yfuse.harmony.entry.data.*
import com.yfuse.harmony.entry.network.*
import com.yfuse.harmony.entry.storage.*

/** Executes provider requests and is the only layer allowed to resolve an access token. */
public class MediaRepository {
    private let transport: HttpTransport
    private let registry: ServerRegistry
    private let embyApi: ProviderApi = EmbyCompatibleProviderApi(ProviderKind.Emby)
    private let jellyfinApi: ProviderApi = EmbyCompatibleProviderApi(ProviderKind.Jellyfin)
    private let plexApi: ProviderApi = PlexProviderApi()
    private let embyCodec: ProviderCodec = EmbyCompatibleCodec()
    private let plexCodec: ProviderCodec = PlexCodec()

    public init(transport: HttpTransport, registry: ServerRegistry) {
        this.transport = transport
        this.registry = registry
    }

    private func api(server: SavedServer): ProviderApi {
        return match (server.provider) {
            case ProviderKind.Emby => embyApi
            case ProviderKind.Jellyfin => jellyfinApi
            case ProviderKind.Plex => plexApi
        }
    }

    private func codec(server: SavedServer): ProviderCodec {
        return if (server.provider == ProviderKind.Plex) { plexCodec } else { embyCodec }
    }

    private func authenticatedRequest(
        server: SavedServer,
        build: (ProviderApi, String) -> ApiResult<HttpRequestSpec>,
        completion: (ApiResult<HttpResponsePayload>) -> Unit
    ): Unit {
        match (registry.accessToken(server)) {
            case Failure(problem) => completion(ApiResult<HttpResponsePayload>.Failure(problem))
            case Success(token) =>
                match (build(api(server), token)) {
                    case Failure(problem) => completion(ApiResult<HttpResponsePayload>.Failure(problem))
                    case Success(request) => transport.execute(request, completion)
                }
        }
    }

    public func login(kind: ProviderKind, endpoint: String, userName: String, password: String,
        cleartextConfirmed: Bool, completion: (ApiResult<SavedServer>) -> Unit): Unit {
        let checked = validateServerEndpoint(endpoint)
        if (!checked.allowed || (checked.decision == TransportDecision.Cleartext && !cleartextConfirmed)) {
            completion(ApiResult<SavedServer>.Failure(ApiFailure(FailureKind.InvalidInput,
                "请输入 HTTPS 地址；可信局域网 HTTP 需要勾选明文连接确认")))
            return
        }
        if (kind == ProviderKind.Plex) {
            completion(ApiResult<SavedServer>.Failure(ApiFailure(FailureKind.Unsupported, "此登录页支持 Emby 和 Jellyfin")))
            return
        }
        let provider = EmbyCompatibleProviderApi(kind)
        match (provider.authenticate(checked.normalizedEndpoint, embyAuthenticationPayload(userName, password), "yfuse-harmony")) {
            case Failure(problem) => completion(ApiResult<SavedServer>.Failure(problem))
            case Success(request) => transport.execute(request, { response =>
                match (response) {
                    case Failure(problem) => completion(ApiResult<SavedServer>.Failure(problem))
                    case Success(value) => match (embyCodec.decodeAuthentication(value.body, "媒体服务器")) {
                        case Failure(problem) => completion(ApiResult<SavedServer>.Failure(problem))
                        case Success(session) => completion(registry.addAuthenticatedServer(kind,
                            checked.normalizedEndpoint, session, localCleartextConfirmed: cleartextConfirmed))
                    }
                }
            })
        }
    }

    public func progress(server: SavedServer, itemId: String, sourceId: String, positionMs: Int64,
        paused: Bool, completion: (ApiResult<HttpResponsePayload>) -> Unit): Unit {
        if (server.provider == ProviderKind.Plex || positionMs < 0 || positionMs > 922337203685477) {
            completion(ApiResult<HttpResponsePayload>.Failure(ApiFailure(FailureKind.Unsupported, "当前片源不支持进度上报")))
            return
        }
        authenticatedRequest(server, { value, token => value.progress(server, token,
            embyProgressPayload(itemId, sourceId, "", positionMs * 10000, paused)) }, completion)
    }

    /** Video takes a URL; only the authenticated server origin may receive its token. */
    public func playbackUrl(server: SavedServer, source: MediaSourceModel): ApiResult<String> {
        if (!sameOrigin(server.baseUrl, source.directUrl)) {
            return ApiResult<String>.Failure(ApiFailure(FailureKind.Unsupported, "此片源需要额外请求头或跨域转发，系统播放暂不支持"))
        }
        return match (registry.accessToken(server)) {
            case Failure(problem) => ApiResult<String>.Failure(problem)
            case Success(token) =>
                let query = ArrayList<(String, String)>()
                query.add(("api_key", token))
                let suffix = encodedQuery(query)
                ApiResult<String>.Success(source.directUrl +
                    if (source.directUrl.contains("?")) { suffix.replace("?", "&") } else { suffix })
        }
    }

    public func home(server: SavedServer, completion: (ApiResult<MediaPage>) -> Unit): Unit {
        authenticatedRequest(server, { value, token => value.home(server, token) }, { result =>
            match (result) {
                case Failure(problem) => completion(ApiResult<MediaPage>.Failure(problem))
                case Success(response) => completion(codec(server).decodePage(server, response.body, 0))
            }
        })
    }

    public func search(server: SavedServer, query: String, startIndex: Int64, limit: Int64, completion: (ApiResult<MediaPage>) -> Unit): Unit {
        authenticatedRequest(server, { value, token => value.search(server, token, query, startIndex, limit) }, { result =>
            match (result) {
                case Failure(problem) => completion(ApiResult<MediaPage>.Failure(problem))
                case Success(response) => completion(codec(server).decodePage(server, response.body, startIndex))
            }
        })
    }

    public func detail(server: SavedServer, itemId: String, completion: (ApiResult<MediaDetailModel>) -> Unit): Unit {
        authenticatedRequest(server, { value, token => value.detail(server, token, itemId) }, { result =>
            match (result) {
                case Failure(problem) => completion(ApiResult<MediaDetailModel>.Failure(problem))
                case Success(response) => completion(codec(server).decodeDetail(server, response.body))
            }
        })
    }

    public func playbackSources(server: SavedServer, itemId: String, profileJson: String, completion: (ApiResult<ArrayList<MediaSourceModel>>) -> Unit): Unit {
        authenticatedRequest(server, { value, token =>
            value.playbackInfo(server, token, itemId, embyPlaybackInfoPayload(profileJson))
        }, { result =>
            match (result) {
                case Failure(problem) => completion(ApiResult<ArrayList<MediaSourceModel>>.Failure(problem))
                case Success(response) => completion(codec(server).decodeSources(server, response.body, itemId))
            }
        })
    }
}

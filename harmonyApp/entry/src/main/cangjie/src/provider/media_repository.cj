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

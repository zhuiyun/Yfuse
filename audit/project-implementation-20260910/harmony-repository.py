from pathlib import Path
p=Path('harmonyApp/entry/src/main/cangjie/src/provider/media_repository.cj')
s=p.read_text(encoding='utf-8')
idx=s.index('    public func home(')
s=s[:idx]+'''    public func login(kind: ProviderKind, endpoint: String, userName: String, password: String,
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
        if (server.provider == ProviderKind.Plex || positionMs < 0 || positionMs > Int64.Max / 10000) {
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
                    if (source.directUrl.contains("?")) { "&" + suffix[1..] } else { suffix })
        }
    }

'''+s[idx:]
s=s.replace('Int64.Max / 10000','922337203685477')
# Avoid language-specific string slicing: construct separator via the query encoder helper.
s=s.replace('"&" + suffix[1..]', 'suffix.replace("?", "&")')
p.write_text(s,encoding='utf-8')
p=Path('harmonyApp/entry/src/main/cangjie/src/storage/playback_progress.cj')
p.write_text('''package com.yfuse.harmony.entry.storage

import encoding.json.*
import com.yfuse.harmony.entry.data.*

/** Offline checkpoint keyed by both server and item; URLs and credentials never enter this file. */
public class PlaybackProgressStore {
    private let documents: StringPreferenceStore
    private var records: JsonObject = JsonObject()
    private var readable: Bool = false
    public init(documents: StringPreferenceStore) { this.documents = documents }
    public func restore(): ApiResult<Unit> {
        match (documents.read("playback_progress_v1")) {
            case Failure(problem) => return ApiResult<Unit>.Failure(problem)
            case Success(value) =>
                try {
                    if (!value.isEmpty()) {
                        match (JsonValue.fromStr(value)) {
                            case object: JsonObject => records = object
                            case _ => throw Exception("invalid document")
                        }
                    }
                    readable = true
                    return ApiResult<Unit>.Success(())
                } catch (_: Exception) {
                    return ApiResult<Unit>.Failure(ApiFailure(FailureKind.Decode, "播放记录读取失败，原记录已保留"))
                }
        }
    }
    private func key(serverId: String, itemId: String): String {
        return JsonString(serverId).toJsonString() + ":" + JsonString(itemId).toJsonString()
    }
    public func position(serverId: String, itemId: String, fallback: Int64): Int64 {
        for ((name, value) in records.getFields()) {
            if (name == key(serverId, itemId)) {
                match (value) {
                    case number: JsonInt => return if (number.getValue() >= 0) { number.getValue() } else { fallback }
                    case _ => return fallback
                }
            }
        }
        return fallback
    }
    public func save(serverId: String, itemId: String, positionMs: Int64): ApiResult<Unit> {
        if (!readable || positionMs < 0) {
            return ApiResult<Unit>.Failure(ApiFailure(FailureKind.SecureStorage, "播放记录暂时不可写入"))
        }
        let next = JsonObject()
        let identity = key(serverId, itemId)
        var count: Int64 = 0
        for ((name, value) in records.getFields()) {
            if (name != identity && count < 499) { next.put(name, value); count++ }
        }
        next.put(identity, JsonInt(positionMs))
        return match (documents.writeAndFlush("playback_progress_v1", next.toJsonString())) {
            case Failure(problem) => ApiResult<Unit>.Failure(problem)
            case Success(_) => records = next; ApiResult<Unit>.Success(())
        }
    }
}
''',encoding='utf-8')

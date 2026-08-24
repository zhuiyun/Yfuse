package com.yfuse.harmony.entry.storage

import std.collection.*
import com.yfuse.harmony.entry.data.*
import com.yfuse.harmony.entry.network.*

public interface SecretStore {
    func get(reference: String): ApiResult<String>
    func put(reference: String, secret: String): ApiResult<Unit>
    func remove(reference: String): ApiResult<Unit>
}

public interface RegistryDocumentStore {
    func load(): ApiResult<String>
    func save(document: String): ApiResult<Unit>
}

public interface RegistryCodec {
    func decode(document: String): ApiResult<(ArrayList<SavedServer>, String)>
    func encode(servers: ArrayList<SavedServer>, defaultServerId: String): ApiResult<String>
}

public interface SecretReferenceFactory {
    func create(): String
}

/**
 * Owns the multi-server invariant. SavedServer contains only a random secret reference; tokens
 * are written to SecretStore before metadata is committed and are rolled back on failure.
 */
public class ServerRegistry {
    private let secrets: SecretStore
    private let documents: RegistryDocumentStore
    private let codec: RegistryCodec
    private let references: SecretReferenceFactory
    private var mutableServers: ArrayList<SavedServer> = ArrayList<SavedServer>()
    private var mutableDefaultServerId: String = ""

    public init(
        secrets: SecretStore,
        documents: RegistryDocumentStore,
        codec: RegistryCodec,
        references: SecretReferenceFactory
    ) {
        this.secrets = secrets
        this.documents = documents
        this.codec = codec
        this.references = references
    }

    public prop servers: ArrayList<SavedServer> { get() { mutableServers } }
    public prop defaultServerId: String { get() { mutableDefaultServerId } }

    public func restore(): ApiResult<Unit> {
        return match (documents.load()) {
            case Success(document) =>
                if (document.isEmpty()) {
                    ApiResult<Unit>.Success(())
                } else {
                    match (codec.decode(document)) {
                        case Success((loaded, defaultId)) =>
                            mutableServers = loaded
                            mutableDefaultServerId = defaultId
                            ApiResult<Unit>.Success(())
                        case Failure(problem) => ApiResult<Unit>.Failure(problem)
                    }
                }
            case Failure(problem) => ApiResult<Unit>.Failure(problem)
        }
    }

    public func addAuthenticatedServer(
        provider: ProviderKind,
        baseUrl: String,
        session: AuthSession,
        localCleartextConfirmed!: Bool = false
    ): ApiResult<SavedServer> {
        let endpoint = validateServerEndpoint(baseUrl)
        if (!endpoint.allowed) {
            return ApiResult<SavedServer>.Failure(ApiFailure(FailureKind.InvalidInput, endpoint.message))
        }
        if (endpoint.decision == TransportDecision.Cleartext && !localCleartextConfirmed) {
            return ApiResult<SavedServer>.Failure(ApiFailure(
                FailureKind.InvalidInput,
                "HTTP 会以明文发送服务器令牌，必须由用户确认仅用于可信局域网"
            ))
        }
        if (session.accessToken.isEmpty() || session.accessToken.size > 1024) {
            return ApiResult<SavedServer>.Failure(ApiFailure(FailureKind.InvalidInput, "服务器令牌无效"))
        }

        let identity = endpoint.normalizedEndpoint + "#" + session.userId
        var existingIndex: Int64 = -1
        for (index in 0..mutableServers.size) {
            if (mutableServers[index].id == identity) { existingIndex = index }
        }
        let secretRef = if (existingIndex >= 0) { mutableServers[existingIndex].secretRef } else { references.create() }
        let routeList = ArrayList<ServerRoute>()
        routeList.append(ServerRoute("primary", "主线路", endpoint.normalizedEndpoint))
        let saved = SavedServer(
            identity,
            provider,
            endpoint.normalizedEndpoint,
            session.serverName,
            session.userId,
            session.userName,
            secretRef,
            routes: routeList,
            localCleartextConfirmed: localCleartextConfirmed
        )

        return match (secrets.put(secretRef, session.accessToken)) {
            case Failure(problem) => ApiResult<SavedServer>.Failure(problem)
            case Success(_) =>
                let previous = mutableServers
                let next = ArrayList<SavedServer>()
                for (server in mutableServers) {
                    if (server.id != identity) { next.append(server) }
                }
                next.append(saved)
                mutableServers = next
                if (mutableDefaultServerId.isEmpty()) { mutableDefaultServerId = identity }
                match (persist()) {
                    case Success(_) => ApiResult<SavedServer>.Success(saved)
                    case Failure(problem) =>
                        mutableServers = previous
                        if (existingIndex < 0) { secrets.remove(secretRef) }
                        ApiResult<SavedServer>.Failure(problem)
                }
        }
    }

    public func accessToken(server: SavedServer): ApiResult<String> {
        return secrets.get(server.secretRef)
    }

    public func setDefault(serverId: String): ApiResult<Unit> {
        var found = false
        for (server in mutableServers) {
            if (server.id == serverId) { found = true }
        }
        if (!found) {
            return ApiResult<Unit>.Failure(ApiFailure(FailureKind.NotFound, "服务器不存在"))
        }
        let previous = mutableDefaultServerId
        mutableDefaultServerId = serverId
        return match (persist()) {
            case Success(_) => ApiResult<Unit>.Success(())
            case Failure(problem) =>
                mutableDefaultServerId = previous
                ApiResult<Unit>.Failure(problem)
        }
    }

    public func remove(serverId: String): ApiResult<Unit> {
        let next = ArrayList<SavedServer>()
        var removed: Option<SavedServer> = None
        for (server in mutableServers) {
            if (server.id == serverId) { removed = Some(server) } else { next.append(server) }
        }
        if (let Some(server) <- removed) {
            let previous = mutableServers
            let previousDefault = mutableDefaultServerId
            mutableServers = next
            if (mutableDefaultServerId == serverId) {
                mutableDefaultServerId = if (next.isEmpty()) { "" } else { next[0].id }
            }
            return match (persist()) {
                case Success(_) =>
                    secrets.remove(server.secretRef)
                    ApiResult<Unit>.Success(())
                case Failure(problem) =>
                    mutableServers = previous
                    mutableDefaultServerId = previousDefault
                    ApiResult<Unit>.Failure(problem)
            }
        }
        return ApiResult<Unit>.Failure(ApiFailure(FailureKind.NotFound, "服务器不存在"))
    }

    private func persist(): ApiResult<Unit> {
        return match (codec.encode(mutableServers, mutableDefaultServerId)) {
            case Success(document) => documents.save(document)
            case Failure(problem) => ApiResult<Unit>.Failure(problem)
        }
    }
}

/** Test/demo implementations never belong in release composition roots. */
public class MemorySecretStore <: SecretStore {
    private let values = HashMap<String, String>()
    public func get(reference: String): ApiResult<String> {
        return match (values.get(reference)) {
            case Some(value) => ApiResult<String>.Success(value)
            case None => ApiResult<String>.Failure(ApiFailure(FailureKind.SecureStorage, "令牌不存在"))
        }
    }
    public func put(reference: String, secret: String): ApiResult<Unit> {
        values[reference] = secret
        return ApiResult<Unit>.Success(())
    }
    public func remove(reference: String): ApiResult<Unit> {
        values.remove(reference)
        return ApiResult<Unit>.Success(())
    }
}

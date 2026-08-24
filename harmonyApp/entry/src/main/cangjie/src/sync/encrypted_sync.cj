package com.yfuse.harmony.entry.sync

import std.collection.*
import com.yfuse.harmony.entry.data.*

public enum SyncEntityKind { | Playback | Favorite | ServerMetadata | Preference }

public class SyncMutation {
    public let id: String
    public let entityKind: SyncEntityKind
    public let entityId: String
    public let updatedAtMs: Int64
    public let payloadJson: String
    public init(id: String, entityKind: SyncEntityKind, entityId: String, updatedAtMs: Int64, payloadJson: String) {
        this.id = id; this.entityKind = entityKind; this.entityId = entityId
        this.updatedAtMs = updatedAtMs; this.payloadJson = payloadJson
    }
}

public class EncryptedEnvelope {
    public let mutationId: String
    public let entityKind: SyncEntityKind
    public let entityId: String
    public let updatedAtMs: Int64
    public let keyId: String
    public let nonceBase64: String
    public let ciphertextBase64: String
    public let aadBase64: String
    public init(
        mutationId: String,
        entityKind: SyncEntityKind,
        entityId: String,
        updatedAtMs: Int64,
        keyId: String,
        nonceBase64: String,
        ciphertextBase64: String,
        aadBase64: String
    ) {
        this.mutationId = mutationId; this.entityKind = entityKind; this.entityId = entityId
        this.updatedAtMs = updatedAtMs
        this.keyId = keyId; this.nonceBase64 = nonceBase64
        this.ciphertextBase64 = ciphertextBase64; this.aadBase64 = aadBase64
    }
}

public interface AccountCipher {
    func encrypt(mutation: SyncMutation, aad: String): ApiResult<EncryptedEnvelope>
    func decrypt(envelope: EncryptedEnvelope, aad: String): ApiResult<String>
}

public interface SyncRemote {
    func push(envelopes: ArrayList<EncryptedEnvelope>, completion: (ApiResult<ArrayList<String>>) -> Unit): Unit
    func pull(cursor: String, completion: (ApiResult<(ArrayList<EncryptedEnvelope>, String)>) -> Unit): Unit
}

public interface SyncLocalStore {
    func pending(): ArrayList<SyncMutation>
    func acknowledge(ids: ArrayList<String>): Unit
    func apply(mutation: SyncMutation): ApiResult<Unit>
    func cursor(): String
    func setCursor(value: String): Unit
}

/** Server sees ciphertext only. AAD binds account, entity and mutation id against swapping. */
public class EncryptedSyncEngine {
    private let accountId: String
    private let cipher: AccountCipher
    private let remote: SyncRemote
    private let local: SyncLocalStore

    public init(accountId: String, cipher: AccountCipher, remote: SyncRemote, local: SyncLocalStore) {
        this.accountId = accountId; this.cipher = cipher; this.remote = remote; this.local = local
    }

    private func aad(mutation: SyncMutation): String { "yfuse:v1:${accountId}:${mutation.entityId}:${mutation.id}" }

    public func push(completion: (ApiResult<Unit>) -> Unit): Unit {
        let pending = local.pending()
        let envelopes = ArrayList<EncryptedEnvelope>()
        for (mutation in pending) {
            match (cipher.encrypt(mutation, aad(mutation))) {
                case Failure(problem) => completion(ApiResult<Unit>.Failure(problem)); return
                case Success(envelope) => envelopes.append(envelope)
            }
        }
        remote.push(envelopes, { result =>
            match (result) {
                case Failure(problem) => completion(ApiResult<Unit>.Failure(problem))
                case Success(ids) => local.acknowledge(ids); completion(ApiResult<Unit>.Success(()))
            }
        })
    }

    public func pull(completion: (ApiResult<Unit>) -> Unit): Unit {
        remote.pull(local.cursor(), { result =>
            match (result) {
                case Failure(problem) => completion(ApiResult<Unit>.Failure(problem))
                case Success((envelopes, cursor)) =>
                    for (envelope in envelopes) {
                        let mutation = SyncMutation(
                            envelope.mutationId,
                            envelope.entityKind,
                            envelope.entityId,
                            envelope.updatedAtMs,
                            ""
                        )
                        match (cipher.decrypt(envelope, aad(mutation))) {
                            case Failure(problem) => completion(ApiResult<Unit>.Failure(problem)); return
                            case Success(cleartext) =>
                                let decoded = SyncMutation(
                                    envelope.mutationId,
                                    envelope.entityKind,
                                    envelope.entityId,
                                    envelope.updatedAtMs,
                                    cleartext
                                )
                                match (local.apply(decoded)) {
                                    case Failure(problem) => completion(ApiResult<Unit>.Failure(problem)); return
                                    case Success(_) => ()
                                }
                        }
                    }
                    local.setCursor(cursor)
                    completion(ApiResult<Unit>.Success(()))
            }
        })
    }
}

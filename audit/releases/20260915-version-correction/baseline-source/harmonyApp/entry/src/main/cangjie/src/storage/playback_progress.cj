package com.yfuse.harmony.entry.storage

import stdx.encoding.json.*
import com.yfuse.harmony.entry.data.*

/** Offline checkpoint keyed by both server and item; URLs and credentials never enter this file. */
public class PlaybackProgressStore {
    private let documents: StringPreferenceStore
    private var records: JsonObject = JsonObject()
    private var readable: Bool = false
    public init(documents: StringPreferenceStore) { this.documents = documents }
    public func restore(): ApiResult<Unit> {
        readable = false
        match (documents.read("playback_progress_v1")) {
            case Failure(problem) => return ApiResult<Unit>.Failure(problem)
            case Success(value) =>
                try {
                    records = JsonObject()
                    if (!value.isEmpty()) {
                        records = JsonValue.fromStr(value).asObject()
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
                try { let position = value.asInt().getValue(); return if (position >= 0) { position } else { fallback } }
                catch (_: Exception) { return fallback }
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

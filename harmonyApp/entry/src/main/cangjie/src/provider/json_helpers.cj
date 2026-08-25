package com.yfuse.harmony.entry.provider

import encoding.json.*
import std.collection.*
import com.yfuse.harmony.entry.data.*

/** Defensive helpers for inconsistent Emby/Jellyfin/Plex payloads. */
public func parseJsonObject(payload: String): ApiResult<JsonObject> {
    try {
        return ApiResult<JsonObject>.Success(JsonValue.fromStr(payload).asObject())
    } catch (_: Exception) {
        return ApiResult<JsonObject>.Failure(ApiFailure(FailureKind.Decode, "服务器返回了无法解析的 JSON"))
    }
}

public func jsonObject(parent: JsonObject, key: String): Option<JsonObject> {
    return match (parent.get(key)) {
        case Some(value) =>
            try { Some(value.asObject()) } catch (_: Exception) { None }
        case None => None
    }
}

public func jsonArray(parent: JsonObject, key: String): JsonArray {
    return match (parent.get(key)) {
        case Some(value) =>
            try { value.asArray() } catch (_: Exception) { JsonArray() }
        case None => JsonArray()
    }
}

public func jsonString(parent: JsonObject, key: String, fallback!: String = ""): String {
    return match (parent.get(key)) {
        case Some(value) =>
            try { value.asString().getValue() } catch (_: Exception) { fallback }
        case None => fallback
    }
}

public func jsonInt(parent: JsonObject, key: String, fallback!: Int64 = 0): Int64 {
    return match (parent.get(key)) {
        case Some(value) =>
            try { value.asInt().getValue() } catch (_: Exception) {
                try { Int64(value.asFloat().getValue()) } catch (_: Exception) { fallback }
            }
        case None => fallback
    }
}

public func jsonFloat(parent: JsonObject, key: String, fallback!: Float64 = 0.0): Float64 {
    return match (parent.get(key)) {
        case Some(value) =>
            try { value.asFloat().getValue() } catch (_: Exception) {
                try { Float64(value.asInt().getValue()) } catch (_: Exception) { fallback }
            }
        case None => fallback
    }
}

public func jsonBool(parent: JsonObject, key: String, fallback!: Bool = false): Bool {
    return match (parent.get(key)) {
        case Some(value) =>
            try { value.asBool().getValue() } catch (_: Exception) { fallback }
        case None => fallback
    }
}

public func jsonStringList(parent: JsonObject, key: String): ArrayList<String> {
    let result = ArrayList<String>()
    for (value in jsonArray(parent, key).getItems()) {
        try { result.append(value.asString().getValue()) } catch (_: Exception) {}
    }
    return result
}

public func requireJsonString(parent: JsonObject, key: String): ApiResult<String> {
    let value = jsonString(parent, key)
    if (value.isEmpty()) {
        return ApiResult<String>.Failure(ApiFailure(FailureKind.Decode, "响应缺少字段 ${key}"))
    }
    return ApiResult<String>.Success(value)
}

public func jsonObjectOrEmpty(value: JsonValue): JsonObject {
    try { return value.asObject() } catch (_: Exception) { return JsonObject() }
}

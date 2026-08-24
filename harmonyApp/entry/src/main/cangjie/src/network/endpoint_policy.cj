package com.yfuse.harmony.entry.network

import encoding.url.*
import std.collection.*
import std.unicode.*
import com.yfuse.harmony.entry.data.*

public enum TransportDecision {
    | Secure
    | Cleartext
    | Invalid
}

public class EndpointValidation {
    public let normalizedEndpoint: String
    public let decision: TransportDecision
    public let message: String

    public init(normalizedEndpoint: String, decision: TransportDecision, message!: String = "") {
        this.normalizedEndpoint = normalizedEndpoint
        this.decision = decision
        this.message = message
    }

    public prop allowed: Bool {
        get() { decision != TransportDecision.Invalid }
    }
}

public func validateServerEndpoint(raw: String): EndpointValidation {
    var value = raw.trim()
    while (value.size > 8 && value.endsWith("/")) {
        value = value[..(value.size - 1)]
    }
    if (value.isEmpty()) {
        return EndpointValidation("", TransportDecision.Invalid, message: "请输入完整的 HTTP 或 HTTPS 地址")
    }
    try {
        let parsed = URL.parse(value)
        if ((parsed.scheme != "http" && parsed.scheme != "https") || parsed.hostName.isEmpty()) {
            return EndpointValidation("", TransportDecision.Invalid, message: "仅支持 HTTP 或 HTTPS 地址")
        }
        if (!parsed.userInfo.username().isEmpty() || parsed.userInfo.password().isSome()) {
            return EndpointValidation("", TransportDecision.Invalid, message: "地址中不能包含用户名或密码")
        }
        if (parsed.query.isSome() || parsed.fragment.isSome()) {
            return EndpointValidation("", TransportDecision.Invalid, message: "服务器地址不能包含查询参数或锚点")
        }
        return EndpointValidation(
            value,
            if (parsed.scheme == "https") { TransportDecision.Secure } else { TransportDecision.Cleartext }
        )
    } catch (_: Exception) {
        return EndpointValidation("", TransportDecision.Invalid, message: "服务器地址格式无效")
    }
}

public func endpointPath(baseUrl: String, path: String): ApiResult<String> {
    let endpoint = validateServerEndpoint(baseUrl)
    if (!endpoint.allowed) {
        return ApiResult<String>.Failure(ApiFailure(FailureKind.InvalidInput, endpoint.message))
    }
    if (!path.startsWith("/") || path.contains("://") || path.contains("..")) {
        return ApiResult<String>.Failure(ApiFailure(FailureKind.InvalidInput, "服务器路径不安全"))
    }
    return ApiResult<String>.Success(endpoint.normalizedEndpoint + path)
}

public func encodedQuery(values: ArrayList<(String, String)>): String {
    let form = Form()
    for ((key, value) in values) {
        form.add(key, value)
    }
    if (form.isEmpty()) { return "" }
    return "?" + form.toEncodeString()
}

/** Credentials are only attached to URLs whose origin is exactly the saved server origin. */
public func sameOrigin(serverUrl: String, requestUrl: String): Bool {
    try {
        let server = URL.parse(serverUrl)
        let request = URL.parse(requestUrl)
        return server.scheme == request.scheme && server.hostName == request.hostName && server.port == request.port
    } catch (_: Exception) {
        return false
    }
}

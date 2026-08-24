package com.yfuse.harmony.entry.data

public enum FailureKind {
    | InvalidInput
    | Network
    | Unauthorized
    | Forbidden
    | NotFound
    | Server
    | Decode
    | SecureStorage
    | Unsupported
    | Unknown
}

public class ApiFailure {
    public let kind: FailureKind
    public let message: String
    public let statusCode: UInt32

    public init(kind: FailureKind, message: String, statusCode!: UInt32 = 0) {
        this.kind = kind
        this.message = message
        this.statusCode = statusCode
    }
}

public enum ApiResult<T> {
    | Success(T)
    | Failure(ApiFailure)
}

public func failureForStatus(statusCode: UInt32): ApiFailure {
    if (statusCode == 401) {
        return ApiFailure(FailureKind.Unauthorized, "登录状态已失效", statusCode: statusCode)
    }
    if (statusCode == 403) {
        return ApiFailure(FailureKind.Forbidden, "服务器拒绝访问", statusCode: statusCode)
    }
    if (statusCode == 404) {
        return ApiFailure(FailureKind.NotFound, "服务器资源不存在", statusCode: statusCode)
    }
    if (statusCode >= 500) {
        return ApiFailure(FailureKind.Server, "服务器暂时不可用", statusCode: statusCode)
    }
    return ApiFailure(FailureKind.Unknown, "请求失败", statusCode: statusCode)
}

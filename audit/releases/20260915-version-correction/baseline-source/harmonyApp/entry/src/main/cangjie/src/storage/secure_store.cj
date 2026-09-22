package com.yfuse.harmony.entry.storage

import com.yfuse.harmony.entry.data.*

@C
public struct YSecureReadResult {
    public var status: Int32
    public var value: CString
    public init(status: Int32, value: CString) { this.status = status; this.value = value }
}

foreign {
    func ysecure_put(alias: CString, value: CString): Int32
    func ysecure_get(alias: CString): YSecureReadResult
    func ysecure_remove(alias: CString): Int32
    func ysecure_free(value: CString): Unit
    func ysecure_random_reference(): CString
}

public class NativeSecretReferenceFactory <: SecretReferenceFactory {
    public func create(): String {
        unsafe {
            let value = ysecure_random_reference()
            if (value.isNull()) { throw Exception("无法创建安全存储引用") }
            let result = value.toString()
            ysecure_free(value)
            return "yfuse.token.${result}"
        }
    }
}

/** Release implementation backed by encrypted Asset Store; no plaintext fallback exists. */
public class AssetStoreSecretStore <: SecretStore {
    public func get(reference: String): ApiResult<String> {
        try {
            unsafe {
                var alias = LibC.mallocCString(reference)
                let result = ysecure_get(alias)
                LibC.free(alias)
                if (result.status != 0 || result.value.isNull()) {
                    return ApiResult<String>.Failure(ApiFailure(FailureKind.SecureStorage, "无法从 Asset Store 读取服务器令牌"))
                }
                let value = result.value.toString()
                ysecure_free(result.value)
                return ApiResult<String>.Success(value)
            }
        } catch (_: Exception) {
            return ApiResult<String>.Failure(ApiFailure(FailureKind.SecureStorage, "Asset Store 数据转换失败"))
        }
    }

    public func put(reference: String, secret: String): ApiResult<Unit> {
        let status = unsafe {
            var alias = LibC.mallocCString(reference)
            var value = LibC.mallocCString(secret)
            let result = ysecure_put(alias, value)
            LibC.free(alias); LibC.free(value)
            result
        }
        return if (status == 0) {
            ApiResult<Unit>.Success(())
        } else {
            ApiResult<Unit>.Failure(ApiFailure(FailureKind.SecureStorage, "无法将服务器令牌写入 Asset Store"))
        }
    }

    public func remove(reference: String): ApiResult<Unit> {
        let status = unsafe {
            var alias = LibC.mallocCString(reference)
            let result = ysecure_remove(alias)
            LibC.free(alias)
            result
        }
        return if (status == 0 || status == -2) {
            ApiResult<Unit>.Success(())
        } else {
            ApiResult<Unit>.Failure(ApiFailure(FailureKind.SecureStorage, "无法删除 Asset Store 令牌"))
        }
    }
}

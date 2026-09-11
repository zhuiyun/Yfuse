package com.yfuse.harmony.entry.storage

import com.yfuse.harmony.entry.data.*

@C
public struct YPreferencesReadResult {
    public var status: Int32
    public var value: CString
    public init(status: Int32, value: CString) { this.status = status; this.value = value }
}
foreign {
    func ypreferences_get(key: CString): YPreferencesReadResult
    func ypreferences_put(key: CString, value: CString): Int32
    func ypreferences_free(value: CString): Unit
}

/** Metadata only. Access tokens remain in Asset Store. Native API 13 works with target API 20. */
public class NativeStringPreferenceStore <: StringPreferenceStore {
    public func read(key: String): ApiResult<String> {
        unsafe {
            let name = LibC.mallocCString(key)
            let result = ypreferences_get(name)
            LibC.free(name)
            if (result.status == -2) { return ApiResult<String>.Success("") }
            if (result.status != 0 || result.value.isNull()) {
                return ApiResult<String>.Failure(ApiFailure(FailureKind.SecureStorage, "本地记录读取失败，请保留应用数据后重试"))
            }
            try { return ApiResult<String>.Success(result.value.toString()) }
            finally { ypreferences_free(result.value) }
        }
    }
    public func writeAndFlush(key: String, value: String): ApiResult<Unit> {
        let status = unsafe {
            let name = LibC.mallocCString(key)
            let document = LibC.mallocCString(value)
            let result = ypreferences_put(name, document)
            LibC.free(name); LibC.free(document)
            result
        }
        return if (status == 0) { ApiResult<Unit>.Success(()) }
        else { ApiResult<Unit>.Failure(ApiFailure(FailureKind.SecureStorage, "本地记录未能保存")) }
    }
}

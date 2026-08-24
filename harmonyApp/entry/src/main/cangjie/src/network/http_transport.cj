package com.yfuse.harmony.entry.network

import ohos.net.http.*
import std.collection.*
import com.yfuse.harmony.entry.data.*

public enum HttpMethod {
    | Get
    | Post
    | Put
    | Delete
}

public class HttpRequestSpec {
    public let method: HttpMethod
    public let url: String
    public let headers: HashMap<String, String>
    public let body: String
    public let credentialOrigin: String

    public init(
        method: HttpMethod,
        url: String,
        headers!: HashMap<String, String> = HashMap<String, String>(),
        body!: String = "",
        credentialOrigin!: String = ""
    ) {
        this.method = method
        this.url = url
        this.headers = headers
        this.body = body
        this.credentialOrigin = credentialOrigin
    }
}

public class HttpResponsePayload {
    public let statusCode: UInt32
    public let body: String
    public let headers: HashMap<String, String>

    public init(statusCode: UInt32, body: String, headers!: HashMap<String, String> = HashMap<String, String>()) {
        this.statusCode = statusCode
        this.body = body
        this.headers = headers
    }
}

public interface HttpTransport {
    func execute(request: HttpRequestSpec, completion: (ApiResult<HttpResponsePayload>) -> Unit): Unit
}

private func platformMethod(method: HttpMethod): RequestMethod {
    return match (method) {
        case HttpMethod.Get => RequestMethod.GET
        case HttpMethod.Post => RequestMethod.POST
        case HttpMethod.Put => RequestMethod.PUT
        case HttpMethod.Delete => RequestMethod.DELETE
    }
}

/** One HttpRequest instance per call, as required by the HarmonyOS HTTP wrapper. */
public class HarmonyHttpTransport <: HttpTransport {
    public func execute(request: HttpRequestSpec, completion: (ApiResult<HttpResponsePayload>) -> Unit): Unit {
        if (!request.credentialOrigin.isEmpty() && !sameOrigin(request.credentialOrigin, request.url)) {
            completion(ApiResult<HttpResponsePayload>.Failure(
                ApiFailure(FailureKind.InvalidInput, "已阻止向非同源地址发送服务器凭据")
            ))
            return
        }

        let task = createHttp()
        let responseHeaders = HashMap<String, String>()
        task.onceHeadersReceive({ headers =>
            for ((key, value) in headers) { responseHeaders[key] = value }
        })

        let callback = { error, response =>
            if (let Some(problem) <- error) {
                task.destroy()
                completion(ApiResult<HttpResponsePayload>.Failure(
                    ApiFailure(FailureKind.Network, problem.message)
                ))
                return
            }
            if (let Some(value) <- response) {
                let status = value.responseCode.getValue()
                let payload = HttpResponsePayload(status, value.result.toString(), headers: responseHeaders)
                task.destroy()
                if (status >= 200 && status < 300) {
                    completion(ApiResult<HttpResponsePayload>.Success(payload))
                } else {
                    completion(ApiResult<HttpResponsePayload>.Failure(failureForStatus(status)))
                }
                return
            }
            task.destroy()
            completion(ApiResult<HttpResponsePayload>.Failure(
                ApiFailure(FailureKind.Network, "服务器未返回响应")
            ))
        }

        if (request.body.isEmpty()) {
            let options = HttpRequestOptions(
                method: platformMethod(request.method),
                expectDataType: HttpDataType.STRING,
                header: request.headers
            )
            task.request(request.url, callback, options: options)
        } else {
            let options = HttpRequestOptions(
                method: platformMethod(request.method),
                extraData: HttpData.STRING_DATA(request.body),
                expectDataType: HttpDataType.STRING,
                header: request.headers
            )
            task.request(request.url, callback, options: options)
        }
    }
}

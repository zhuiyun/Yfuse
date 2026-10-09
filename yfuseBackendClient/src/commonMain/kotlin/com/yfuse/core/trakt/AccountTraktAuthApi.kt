package com.yfuse.core.trakt

import com.yfuse.backend.BackendAccess
import com.yfuse.backend.BackendEndpoints
import com.yfuse.backend.BackendFeature
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.watch.protocol.TraktAuthChallenge
import com.yfuse.watch.protocol.TraktAuthPoll
import com.yfuse.watch.protocol.TraktAuthStart
import com.yfuse.watch.protocol.TraktConfiguration
import com.yfuse.watch.protocol.TraktRefreshRequest
import com.yfuse.watch.protocol.TraktRevokeRequest
import com.yfuse.watch.protocol.TraktToken
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json

private val traktJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

class TraktApiException(
    val status: Int,
    val retryAfterSeconds: Int = 0,
) : IllegalStateException(
        when (status) {
            401 -> "Trakt 授权已失效，请重新连接"
            403 -> "Trakt 不允许此操作，请检查应用权限"
            404 -> "Trakt 接口尚未启用或内容不存在"
            429 -> "Trakt 请求过多，请稍后重试"
            else -> "Trakt 暂不可用（$status）"
        },
    )

interface TraktAuthApi {
    suspend fun configuration(): TraktConfiguration

    suspend fun begin(device: Boolean): TraktAuthChallenge

    suspend fun poll(id: String): TraktAuthPoll

    suspend fun cancel(id: String)

    suspend fun refresh(request: TraktRefreshRequest): TraktToken

    suspend fun revoke(accessToken: String)
}

class AccountTraktAuthApi(
    private val client: HttpClient,
    private val tokens: AccountAccessTokenSource,
    baseUrl: String = BackendEndpoints.ORIGIN,
    private val access: BackendAccess = BackendAccess.Default,
) : TraktAuthApi {
    private val endpoint =
        "${baseUrl.trimEnd('/')}/api/v1/account/trakt".also {
            require(
                it.startsWith("https://") && tokens.trusts(it),
            )
        }

    override suspend fun configuration(): TraktConfiguration = decoded("/configuration")

    override suspend fun begin(device: Boolean): TraktAuthChallenge =
        decoded("/authorize", HttpMethod.Post) {
            setBody(TraktAuthStart(device))
        }

    override suspend fun poll(id: String): TraktAuthPoll {
        validateId(id)
        return decoded("/authorize/$id")
    }

    override suspend fun cancel(id: String) {
        validateId(id)
        send("/authorize/$id", HttpMethod.Delete)
    }

    override suspend fun refresh(request: TraktRefreshRequest): TraktToken =
        decoded("/refresh", HttpMethod.Post) {
            setBody(request)
        }

    override suspend fun revoke(accessToken: String) {
        send("/revoke", HttpMethod.Post) { setBody(TraktRevokeRequest(accessToken)) }
    }

    private fun validateId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9-]{16,80}")))
    }

    private suspend inline fun <reified T> decoded(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        noinline body: HttpRequestBuilder.() -> Unit = {
        },
    ): T = traktJson.decodeFromString(send(path, method, body))

    private suspend fun send(
        path: String,
        method: HttpMethod,
        body: HttpRequestBuilder.() -> Unit = {},
    ): String {
        access.requireEnabled(BackendFeature.TraktAuthorization)

        suspend fun attempt(token: String) =
            client
                .prepareRequest("$endpoint$path") {
                    this.method = method
                    auth(token)
                    body()
                }.execute { response ->
                    response.requireSuccess()
                    response.boundedText(64 * 1024)
                }
        return try {
            attempt(tokens.validAccessTokenFor(endpoint) ?: error("请先登录鱼服账号"))
        } catch (failure: TraktApiException) {
            if (failure.status != HttpStatusCode.Unauthorized.value) throw failure
            attempt(tokens.refreshAccessTokenFor(endpoint) ?: error("鱼服登录已失效"))
        }
    }

    private fun HttpRequestBuilder.auth(token: String) {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
    }
}

private fun HttpResponse.requireSuccess() {
    if (!status.isSuccess()) {
        throw TraktApiException(
            status.value,
            headers["Retry-After"]?.toIntOrNull()?.coerceIn(1, 3600) ?: 0,
        )
    }
}

private suspend fun HttpResponse.boundedText(maxBytes: Int): String {
    val chunks = mutableListOf<ByteArray>()
    var size = 0
    val bytes = ByteArray(8192)
    val channel = bodyAsChannel()
    while (true) {
        val count = channel.readAvailable(bytes)
        if (count < 0) break
        check(count <= maxBytes - size) { "Trakt 返回内容过大" }
        if (count > 0) {
            chunks += bytes.copyOf(count)
            size += count
        }
    }
    val output = ByteArray(size)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(output, destinationOffset = offset)
        offset += chunk.size
    }
    return output.decodeToString(throwOnInvalidSequence = true)
}

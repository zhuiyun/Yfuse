package com.yfuse.core.handoff

import com.yfuse.core.account.ACCOUNT_BASE_URL
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.watch.protocol.HandoffHeartbeat
import com.yfuse.watch.protocol.HandoffInbox
import com.yfuse.watch.protocol.HandoffOffer
import com.yfuse.watch.protocol.HandoffRequest
import com.yfuse.watch.protocol.HandoffTransition
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess

interface HandoffApi {
    suspend fun heartbeat(value: HandoffHeartbeat): HandoffInbox

    suspend fun inbox(): HandoffInbox

    suspend fun offer(value: HandoffOffer): HandoffRequest

    suspend fun transition(
        id: String,
        value: HandoffTransition,
    ): HandoffRequest
}

/** The application owns [client]; this adapter never closes the shared account client. */
class AccountHandoffApi(
    private val client: HttpClient,
    private val tokens: AccountAccessTokenSource,
    baseUrl: String = ACCOUNT_BASE_URL,
) : HandoffApi {
    private val endpoint =
        "${baseUrl.trimEnd('/')}/api/v1/account/handoff".also {
            require(it.startsWith("https://") && tokens.trusts(it))
        }

    override suspend fun heartbeat(value: HandoffHeartbeat): HandoffInbox =
        send { token ->
            client.post("$endpoint/heartbeat") {
                authorize(token)
                setBody(value)
            }
        }.body()

    override suspend fun inbox(): HandoffInbox = send { token -> client.get(endpoint) { authorize(token) } }.body()

    override suspend fun offer(value: HandoffOffer): HandoffRequest =
        send { token ->
            client.post(endpoint) {
                authorize(token)
                setBody(value)
            }
        }.body()

    override suspend fun transition(
        id: String,
        value: HandoffTransition,
    ): HandoffRequest {
        require(id.matches(Regex("[A-Za-z0-9-]{16,80}")))
        return send { token ->
            client.post("$endpoint/$id") {
                authorize(token)
                setBody(value)
            }
        }.body()
    }

    private suspend fun send(block: suspend (String) -> HttpResponse): HttpResponse {
        var response = block(tokens.validAccessTokenFor(endpoint) ?: error("请先登录鱼服账号"))
        if (response.status == HttpStatusCode.Unauthorized) {
            response = block(tokens.refreshAccessTokenFor(endpoint) ?: error("登录已失效，请重新登录"))
        }
        check(response.status.isSuccess()) {
            when (response.status.value) {
                404 -> "账号服务器尚未支持设备接力"
                409 -> "接力请求已结束，请刷新"
                429 -> "接力操作太频繁，请稍后重试"
                else -> "设备接力暂不可用（${response.status.value}）"
            }
        }
        return response
    }

    private fun HttpRequestBuilder.authorize(token: String) {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
    }
}

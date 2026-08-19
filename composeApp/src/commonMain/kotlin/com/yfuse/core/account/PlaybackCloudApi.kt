package com.yfuse.core.account

import com.yfuse.core.sync.playback.PlaybackDeltaResponse
import com.yfuse.core.sync.playback.PlaybackPushRequest
import com.yfuse.core.sync.playback.PlaybackPushResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

class PlaybackCloudApi(
    private val client: HttpClient,
    baseUrl: String = ACCOUNT_BASE_URL,
) {
    val origin: String =
        baseUrl.trimEnd('/').also {
            require(it.startsWith("https://")) { "账号服务必须使用 HTTPS" }
        }

    suspend fun pull(
        accessToken: String,
        afterCursor: Long,
        limit: Int = 200,
    ): PlaybackDeltaResponse =
        client
            .get("$origin/api/v1/account/playback") {
                bearerAuth(accessToken)
                url {
                    parameters.append("after", afterCursor.coerceAtLeast(0L).toString())
                    parameters.append("limit", limit.coerceIn(1, 200).toString())
                }
            }.decodedPlayback()

    suspend fun push(
        accessToken: String,
        request: PlaybackPushRequest,
    ): PlaybackPushResponse =
        client
            .post("$origin/api/v1/account/playback") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(request)
            }.decodedPlayback()
}

private suspend inline fun <reified T> HttpResponse.decodedPlayback(): T {
    if (status.isSuccess()) return body()
    val envelope = runCatching { body<ErrorEnvelope>() }.getOrNull()
    throw AccountApiException(
        code = envelope?.error?.code ?: "http_${status.value}",
        message = envelope?.error?.message ?: "播放记录同步暂时不可用",
        status = status,
        currentVersion = envelope?.error?.currentVersion,
    )
}

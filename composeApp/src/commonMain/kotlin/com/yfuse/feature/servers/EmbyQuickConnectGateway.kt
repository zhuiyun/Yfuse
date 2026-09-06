package com.yfuse.feature.servers

import com.yfuse.core.data.AuthedServer
import com.yfuse.core.data.dto.AuthResultDto
import com.yfuse.core.data.dto.PublicInfoDto
import com.yfuse.core.data.mediaServerKind
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.normalizeBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * Emby and Jellyfin Quick Connect: the server hands out a short code, the user approves it
 * in a session that is already signed in, and this device trades the secret for a real
 * session. The secret is the session id here; it is polled, never displayed.
 *
 * Nothing is persisted until `/Users/AuthenticateWithQuickConnect` has returned a token, so
 * a displayed code can never masquerade as success.
 */
internal class EmbyQuickConnectGateway(
    private val client: HttpClient,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : QuickConnectGateway {
    @Serializable
    private data class InitiateDto(
        val Secret: String,
        val Code: String,
    )

    @Serializable
    private data class ConnectDto(
        val Authenticated: Boolean = false,
    )

    @Serializable
    private data class AuthenticateDto(
        val Secret: String,
    )

    override suspend fun start(baseUrl: String): Result<QuickConnectStartResult> =
        gatewayCall {
            val url = normalizeBaseUrl(baseUrl)
            val initiated =
                try {
                    client.post("$url/QuickConnect/Initiate").body<InitiateDto>()
                } catch (failure: ResponseException) {
                    if (failure.response.status.isUnsupported()) {
                        return@gatewayCall QuickConnectStartResult.Unsupported()
                    }
                    throw failure
                }
            if (initiated.Secret.isBlank() || initiated.Code.isBlank()) {
                return@gatewayCall QuickConnectStartResult.Unsupported()
            }
            QuickConnectStartResult.AwaitingApproval(
                QuickConnectSession(
                    id = initiated.Secret,
                    code = initiated.Code,
                    expiresAtEpochMs = nowEpochMs() + SESSION_TTL_MS,
                ),
            )
        }

    override suspend fun poll(
        baseUrl: String,
        sessionId: String,
    ): Result<QuickConnectPollResult> =
        gatewayCall {
            val url = normalizeBaseUrl(baseUrl)
            val approved =
                try {
                    client
                        .get("$url/QuickConnect/Connect") { parameter("Secret", sessionId) }
                        .body<ConnectDto>()
                        .Authenticated
                } catch (failure: ResponseException) {
                    if (failure.response.status == HttpStatusCode.NotFound) {
                        return@gatewayCall QuickConnectPollResult.Expired
                    }
                    if (failure.response.status.isUnsupported()) {
                        return@gatewayCall QuickConnectPollResult.Rejected(QuickConnectUnsupportedMessage)
                    }
                    throw failure
                }
            if (!approved) return@gatewayCall QuickConnectPollResult.Pending
            val auth =
                client
                    .post("$url/Users/AuthenticateWithQuickConnect") {
                        contentType(ContentType.Application.Json)
                        setBody(AuthenticateDto(sessionId))
                    }.body<AuthResultDto>()
            val info =
                runCatching { client.get("$url/System/Info/Public").body<PublicInfoDto>() }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
            QuickConnectPollResult.Authenticated(
                AuthedServer(
                    baseUrl = url,
                    serverName = info?.ServerName ?: url,
                    userId = auth.User.Id,
                    userName = auth.User.Name,
                    accessToken = auth.AccessToken,
                    kind = info.mediaServerKind(),
                ),
            )
        }

    override suspend fun cancel(
        baseUrl: String,
        sessionId: String,
    ): Result<Unit> = Result.success(Unit)

    private fun HttpStatusCode.isUnsupported(): Boolean =
        this == HttpStatusCode.Unauthorized ||
            this == HttpStatusCode.Forbidden ||
            this == HttpStatusCode.NotFound ||
            this == HttpStatusCode.MethodNotAllowed

    private suspend fun <T> gatewayCall(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            AppLog.warning(
                category = "servers.quick_connect",
                event = "request_failed",
                message = "Quick Connect request failed",
                throwable = failure,
            )
            Result.failure(failure)
        }

    private companion object {
        /** Emby retires an unapproved code after a few minutes; the client stops polling first. */
        const val SESSION_TTL_MS = 5 * 60_000L
    }
}

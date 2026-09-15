package com.yfuse.watch.account

import com.yfuse.watch.protocol.TraktAuthChallenge
import com.yfuse.watch.protocol.TraktAuthPoll
import com.yfuse.watch.protocol.TraktAuthStatus
import com.yfuse.watch.protocol.TraktConfiguration
import com.yfuse.watch.protocol.TraktRefreshRequest
import com.yfuse.watch.protocol.TraktToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

internal data class TraktOAuthResponse(
    val status: Int,
    val body: String,
    val retryAfterSeconds: Int = 5,
)

internal fun interface TraktOAuthTransport {
    suspend fun post(
        path: String,
        body: JsonObject,
    ): TraktOAuthResponse
}

/** Secrets remain on the account server. Browser state/device codes are scoped to one login. */
internal class TraktOAuthBroker(
    private val clientId: String = System.getenv("TRAKT_CLIENT_ID").orEmpty().trim(),
    private val clientSecret: String = System.getenv("TRAKT_CLIENT_SECRET").orEmpty().trim(),
    private val redirectUri: String = System.getenv("TRAKT_REDIRECT_URI").orEmpty().trim(),
    private val transport: TraktOAuthTransport = ProductionTraktOAuthTransport(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Pending(
        val sessionId: String,
        val challenge: TraktAuthChallenge,
        val state: String?,
        val deviceCode: String?,
        var nextPollAt: Long,
        var result: TraktAuthPoll = TraktAuthPoll(TraktAuthStatus.Pending),
        val lock: Mutex = Mutex(),
    )

    private data class RefreshResult(
        val sessionId: String,
        val request: TraktRefreshRequest,
        val token: TraktToken,
        val expiresAt: Long,
    )

    private val pending = mutableMapOf<String, Pending>()
    private val refreshResults = mutableMapOf<String, RefreshResult>()
    private val refreshLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    fun configuration() =
        TraktConfiguration(
            clientId = clientId,
            oauthAvailable = configured() && validRedirect(),
            deviceAvailable = configured(),
        )

    suspend fun begin(
        account: AuthenticatedAccount,
        device: Boolean,
    ): TraktAuthChallenge {
        checkRequest(configured() && (device || validRedirect()), "trakt_not_configured", "Trakt 尚未配置，请联系应用维护者")
        cleanup()
        synchronized(pending) {
            checkRequest(pending.size < 1024, "trakt_busy", "授权服务繁忙，请稍后重试")
            pending.entries.removeAll { it.value.sessionId == account.sessionId }
        }
        val id = UUID.randomUUID().toString()
        val state = if (device) null else UUID.randomUUID().toString() + UUID.randomUUID().toString()
        var deviceCode: String? = null
        val challenge =
            if (device) {
                val response = transport.post("/oauth/device/code", buildJsonObject { put("client_id", clientId) })
                checkResponse(response)
                val body = json.decodeFromString<JsonObject>(response.body)
                deviceCode = body.getValue("device_code").jsonPrimitive.content
                val url = body.getValue("verification_url").jsonPrimitive.content
                val userCode = body.getValue("user_code").jsonPrimitive.content
                checkRequest(
                    deviceCode.length in 1..4096 &&
                        userCode.length in 1..32 &&
                        url.length <= 2048 &&
                        trustedVerificationUrl(url),
                    "trakt_response_invalid",
                    "授权地址或代码无效",
                )
                TraktAuthChallenge(
                    id,
                    url,
                    userCode,
                    now() + body
                        .getValue("expires_in")
                        .jsonPrimitive.int
                        .coerceIn(1, 1800) * 1000L,
                    body
                        .getValue("interval")
                        .jsonPrimitive.int
                        .coerceIn(1, 60),
                )
            } else {
                val query =
                    mapOf(
                        "response_type" to "code",
                        "client_id" to clientId,
                        "redirect_uri" to redirectUri,
                        "state" to state!!,
                    ).entries
                        .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }
                TraktAuthChallenge(
                    id,
                    "https://auth.trakt.tv/oauth/authorize?$query",
                    expiresAtEpochMs =
                        now() + 600_000,
                )
            }
        synchronized(pending) {
            pending[id] =
                Pending(account.sessionId, challenge, state, deviceCode, now() + challenge.intervalSeconds * 1000L)
        }
        return challenge
    }

    suspend fun poll(
        account: AuthenticatedAccount,
        id: String,
    ): TraktAuthPoll {
        cleanup()
        val entry = synchronized(pending) { pending[id] } ?: return TraktAuthPoll(TraktAuthStatus.Expired)
        authorize(entry.sessionId == account.sessionId)
        return entry.lock.withLock {
            if (entry.result.status != TraktAuthStatus.Pending) return@withLock entry.result
            if (now() >= entry.challenge.expiresAtEpochMs) return@withLock TraktAuthPoll(TraktAuthStatus.Expired)
            if (now() < entry.nextPollAt || entry.deviceCode == null) {
                return@withLock TraktAuthPoll(
                    TraktAuthStatus.Pending,
                    retryAfterSeconds =
                        ((entry.nextPollAt - now()) / 1000 + 1).toInt().coerceIn(
                            entry.challenge.intervalSeconds,
                            60,
                        ),
                )
            }
            entry.nextPollAt = now() + entry.challenge.intervalSeconds * 1000L
            val response = transport.post("/oauth/device/token", credentials(mapOf("code" to entry.deviceCode)))
            val result =
                when (response.status) {
                    200 -> TraktAuthPoll(TraktAuthStatus.Connected, token(response.body))
                    400 -> TraktAuthPoll(TraktAuthStatus.Pending, retryAfterSeconds = entry.challenge.intervalSeconds)
                    404, 410 -> TraktAuthPoll(TraktAuthStatus.Expired)
                    409 -> TraktAuthPoll(TraktAuthStatus.Used)
                    418 -> TraktAuthPoll(TraktAuthStatus.Denied)
                    429 ->
                        TraktAuthPoll(
                            TraktAuthStatus.Pending,
                            retryAfterSeconds =
                                maxOf(
                                    response.retryAfterSeconds,
                                    entry.challenge.intervalSeconds + 5,
                                ),
                        )
                    else -> {
                        checkResponse(response)
                        error("Unexpected OAuth response")
                    }
                }
            entry.nextPollAt = now() + result.retryAfterSeconds.coerceIn(1, 3600) * 1000L
            entry.result = result
            result
        }
    }

    /** Browser callback has no account cookie. A random, single-use state binds it to begin(). */
    suspend fun callback(
        state: String,
        code: String?,
        denied: Boolean,
    ) {
        checkRequest(state.length in 32..128, "trakt_state_invalid", "授权状态无效，请重新连接")
        cleanup()
        val entry =
            synchronized(pending) { pending.values.firstOrNull { it.state == state } }
                ?: invalid("trakt_state_invalid", "授权已过期，请重新连接")
        entry.lock.withLock {
            checkRequest(
                now() < entry.challenge.expiresAtEpochMs && entry.result.status == TraktAuthStatus.Pending,
                "trakt_state_used",
                "此授权已使用或过期",
            )
            // Consume before exchange: a failed or ambiguous exchange requires a fresh login.
            entry.result = TraktAuthPoll(TraktAuthStatus.Denied)
            if (denied) return@withLock
            checkRequest(code != null && code.length in 1..2048, "trakt_code_invalid", "授权代码无效")
            val response =
                transport.post(
                    "/oauth/token",
                    credentials(
                        mapOf(
                            "code" to code!!,
                            "redirect_uri" to redirectUri,
                            "grant_type" to "authorization_code",
                        ),
                    ),
                )
            checkResponse(response)
            entry.result = TraktAuthPoll(TraktAuthStatus.Connected, token(response.body))
        }
    }

    suspend fun refresh(
        account: AuthenticatedAccount,
        request: TraktRefreshRequest,
    ): TraktToken =
        refreshLock.withLock {
            cleanup()
            checkRequest(
                request.requestId.matches(Regex("[A-Za-z0-9-]{16,80}")) && request.refreshToken.length in 1..4096,
                "trakt_refresh_invalid",
                "授权刷新请求无效",
            )
            val key = "${account.sessionId}:${request.requestId}"
            synchronized(refreshResults) { refreshResults[key] }?.let {
                authorize(it.sessionId == account.sessionId && it.request == request)
                return@withLock it.token
            }
            checkRequest(configured(), "trakt_not_configured", "Trakt 尚未配置")
            val response =
                transport.post(
                    "/oauth/token",
                    credentials(
                        mapOf(
                            "refresh_token" to request.refreshToken,
                            "redirect_uri" to redirectUri.ifBlank { "urn:ietf:wg:oauth:2.0:oob" },
                            "grant_type" to "refresh_token",
                        ),
                    ),
                )
            checkResponse(response)
            token(response.body).also {
                synchronized(refreshResults) {
                    if (refreshResults.size >= 1024) refreshResults.remove(refreshResults.keys.first())
                    refreshResults[key] = RefreshResult(account.sessionId, request, it, now() + 120_000)
                }
            }
        }

    suspend fun revoke(accessToken: String) {
        checkRequest(accessToken.length in 1..4096, "trakt_token_invalid", "授权无效")
        checkResponse(transport.post("/oauth/revoke", credentials(mapOf("token" to accessToken))))
    }

    fun cancel(
        account: AuthenticatedAccount,
        id: String,
    ) {
        synchronized(pending) {
            pending[id]?.let {
                authorize(it.sessionId == account.sessionId)
                pending.remove(id)
            }
        }
    }

    private fun credentials(extra: Map<String, String>) =
        JsonObject(
            extra.mapValues { JsonPrimitive(it.value) } +
                mapOf("client_id" to JsonPrimitive(clientId), "client_secret" to JsonPrimitive(clientSecret)),
        )

    private fun configured() = clientId.isNotBlank() && clientSecret.isNotBlank()

    private fun validRedirect(): Boolean =
        runCatching {
            val uri = URI(redirectUri)
            uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                uri.path == "/api/v1/account/trakt/callback"
        }.getOrDefault(false)

    private fun token(body: String) =
        json.decodeFromString<TraktToken>(body).also {
            checkRequest(it.valid(), "trakt_response_invalid", "授权响应无效")
        }

    private fun cleanup() {
        synchronized(pending) { pending.entries.removeAll { now() > it.value.challenge.expiresAtEpochMs + 120_000 } }
        synchronized(refreshResults) { refreshResults.entries.removeAll { now() > it.value.expiresAt } }
    }

    private fun checkResponse(response: TraktOAuthResponse) {
        if (response.status in 200..299) return
        if (response.status ==
            429
        ) {
            throw AccountServiceException(
                AccountProblem.RateLimited,
                "trakt_rate_limited",
                "Trakt 请求过多，请稍后重试",
                retryAfterSeconds = response.retryAfterSeconds.toLong(),
            )
        }
        invalid(
            if (response.status in
                400..499
            ) {
                "trakt_authorization_failed"
            } else {
                "trakt_unavailable"
            },
            if (response.status in
                400..499
            ) {
                "Trakt 授权无效，请重新连接"
            } else {
                "Trakt 暂不可用，请稍后重试"
            },
        )
    }

    private fun checkRequest(
        condition: Boolean,
        code: String,
        message: String,
    ) {
        if (!condition) invalid(code, message)
    }

    private fun authorize(condition: Boolean) {
        if (!condition) throw AccountServiceException(AccountProblem.Forbidden, "trakt_forbidden", "不能读取另一登录设备的授权")
    }

    private fun invalid(
        code: String,
        message: String,
    ): Nothing = throw AccountServiceException(AccountProblem.InvalidRequest, code, message)
}

internal fun trustedVerificationUrl(value: String): Boolean =
    runCatching {
        val uri = URI(value)
        uri.scheme == "https" &&
            uri.host in setOf("auth.trakt.tv", "trakt.tv", "app.trakt.tv") &&
            uri.userInfo == null &&
            uri.port in setOf(-1, 443)
    }.getOrDefault(false)

/** Fixed destination, no redirects, bounded body and deadlines; never logs OAuth material. */
private class ProductionTraktOAuthTransport : TraktOAuthTransport {
    override suspend fun post(
        path: String,
        body: JsonObject,
    ): TraktOAuthResponse =
        runInterruptible(Dispatchers.IO) {
            require(path in setOf("/oauth/device/code", "/oauth/device/token", "/oauth/token", "/oauth/revoke"))
            val connection = URI("https://auth.trakt.tv$path").toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5_000
                connection.readTimeout = 15_000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("User-Agent", "Yfuse/TraktOAuth")
                val request = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(request.size)
                connection.outputStream.use { it.write(request) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { it.readNBytes(65_537) } ?: byteArrayOf()
                check(bytes.size <= 65_536) { "OAuth response too large" }
                TraktOAuthResponse(
                    status,
                    bytes.toString(Charsets.UTF_8),
                    connection.getHeaderField("Retry-After")?.toIntOrNull()?.coerceIn(1, 3600) ?: 5,
                )
            } finally {
                connection.disconnect()
            }
        }
}
